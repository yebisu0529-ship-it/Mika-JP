package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.usage.ModelHttpAttempts
import com.wangzan101.mika.core.trace.TraceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawResponse(val status: Int, val retryAfter: String?, val body: String, private val onPacket: ((String) -> Unit)? = null) {
    /** Some chat endpoints send a buffered SSE body even when streaming was not requested. */
    fun replay(events: List<String>, accept: (String) -> Unit) {
        for (event in events) {
            onPacket?.invoke(event)
            accept(event)
        }
    }
}

/** HTTP の実行 (エンキュー + コルーチンのキャンセル) と再試行後の解決はすべてのアダプターで共有されます。 */
internal object HttpSupport {
    suspend fun execute(http: OkHttpClient, req: Request): RawResponse =
        observe(req) { attempt -> executeRaw(http, req, attempt) }.also(::requireProxyAccepted)

    /** HTTP 407 は、途中のエージェントがアカウント パスワードを要求した (またはパスワードの提供を拒否した) ことだけを意味し、サービス プロバイダーからの応答は意味しません。 */
    private fun requireProxyAccepted(raw: RawResponse) {
        if (raw.status == 407) throw ModelError.Proxy(ProxyFailure.AuthFailed)
    }

    /** 接続エラー: プロキシが失われ、エラーがプロキシ セクションにある場合、それはプロキシの問題であると言われます。それ以外の場合は、依然としてネットワーク障害とみなされます。 */
    fun networkError(http: OkHttpClient, req: Request, e: IOException): ModelError =
        NetProxy.failure(http, req.url, e)?.let { ModelError.Proxy(it, e) } ?: ModelError.Network(e)

    private suspend inline fun observe(req: Request, send: (TraceCall.Attempt?) -> RawResponse): RawResponse {
        val wire = req.tag(InferenceRequest::class.java) ?: return send(null)
        val call = currentCoroutineContext()[TraceCall] ?: return send(null)
        val attempt = call.sending(wire) ?: return send(null)
        val response = send(attempt)
        if (response.status !in 200..299) {
            call.finish("error", null)
        } else if (response.body.isNotEmpty()) {
            attempt.packet(response.body)
            return response.copy(onPacket = attempt::packet)
        }
        return response
    }

    private suspend fun executeRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?): RawResponse = suspendCancellableCoroutine { cont ->
        if (req.tag(InferenceRequest::class.java) != null) cont.context[ModelHttpAttempts]?.start()
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    attempt?.httpStatus(it.code)
                    try {
                        val raw = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                        if (cont.isActive) cont.resume(raw)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
                    }
                }
            }
        })
    }

    /** SSE をイベント単位で配信し、キャンセルを HTTP リクエストにも伝えます。[onEvent] が true ならプロトコルの終端として接続を閉じ、EOF を待ちません。正常終端のない応答は [ModelError.StreamCut]、イベントのない非SSE応答は [ModelError.BadResponse] として扱います。 */
    suspend fun stream(http: OkHttpClient, req: Request, onEvent: (String) -> Boolean): RawResponse =
        observe(req) { attempt -> streamRaw(http, req, attempt, onEvent) }.also(::requireProxyAccepted)

    private suspend fun streamRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?, onEvent: (String) -> Boolean): RawResponse = suspendCancellableCoroutine { cont ->
        if (req.tag(InferenceRequest::class.java) != null) cont.context[ModelHttpAttempts]?.start()
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    attempt?.httpStatus(it.code)
                    try {
                        if (it.code !in 200..299) {
                            val error = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                            if (cont.isActive) cont.resume(error)
                            return
                        }
                        if (it.header("Content-Type")?.contains("application/json", ignoreCase = true) == true) {
                            if (cont.isActive) cont.resume(RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty()))
                            return
                        }
                        val source = it.body?.source() ?: throw ModelError.BadResponse("ストリーミング応答に内容がありません")
                        val data = mutableListOf<String>()
                        var events = 0
                        /** イベントを配信し、[DONE] などの終端があれば接続終了を待たず読取りを終えます。 */
                        fun dispatch(): Boolean {
                            if (data.isEmpty()) return false
                            events++
                            val event = data.joinToString("\n")
                            attempt?.packet(event)
                            val terminal = onEvent(event)
                            data.clear()
                            return terminal
                        }
                        while (cont.isActive) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> if (dispatch()) break
                                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
                            }
                        }
                        if (cont.isActive) {
                            dispatch()
                            val eventStream = it.header("Content-Type")?.contains("event-stream", ignoreCase = true) == true
                            if (events == 0 && !eventStream) throw ModelError.BadResponse("ストリーミング応答ではありません（Content-Type: ${it.header("Content-Type").orEmpty()}）")
                            cont.resume(RawResponse(it.code, it.header("retry-after"), ""))
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                    }
                }
            }
        })
    }

    fun retryAfterMs(header: String?): Long? = header?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
}
