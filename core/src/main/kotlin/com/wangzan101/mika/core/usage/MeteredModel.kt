package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** 呼び出しが発行される目的。 [key] はストレージに記録されます。認識されないキー (新しいバージョンで書き込まれた) は、[Other] として読み取られます。 */
enum class UsagePurpose(val key: String) {
    Main("main"), Vision("vision"), Light("light"), Other("other");

    companion object {
        fun of(role: ModelRole): UsagePurpose = when (role) {
            ModelRole.Main -> Main
            ModelRole.Vision -> Vision
            ModelRole.Light -> Light
        }

        fun of(key: String): UsagePurpose = entries.firstOrNull { it.key == key } ?: Other
    }
}

/** それは誰のモデルで、何の目的で作られていますか?利用記録時はそのままお持ちください。 [providerName] は、モデルを構築するときのサービス プロバイダーの名前です (サービス プロバイダーが削除されても保持されます)。 */
data class UsageSource(val providerId: String, val providerName: String, val model: String, val purpose: UsagePurpose)

/** への呼び出しの結果: [usage] は null です。これは、サービス プロバイダーが使用状況を報告しなかった (または呼び出しが失敗した) ことを意味します。今回は回数のみがカウントされ、トークンの推定は行われません。 */
fun interface UsageSink {
    fun record(source: UsageSource, usage: Usage?)

    fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) = record(source, usage)
}

/**
 * モデルの論理呼び出しと実際のHTTPリクエスト数を記録する。成功時のトークンは最終応答の分だけ加算する。
 * 互換性のための再送はHTTP回数に含め、ローカルで送信前に失敗した場合は含めない。
 * 名前・機能・例外・ストリーミングは元のモデルを保持し、記録の失敗はモデルの呼び出しへ影響させない。
 */
fun ChatModel.metered(source: UsageSource, sink: UsageSink): ChatModel =
    if (this is StreamingChatModel) MeteredStreamingModel(this, source, sink) else MeteredModel(this, source, sink)

private open class MeteredModel(private val inner: ChatModel, protected val source: UsageSource, protected val sink: UsageSink) : ChatModel {
    override val capabilities get() = inner.capabilities
    override val name: String get() = inner.name

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        counted { inner.complete(system, messages, tools) }

    protected suspend fun counted(call: suspend () -> ModelReply): ModelReply {
        // は実行中に呼び出され、この実行の名前にも記録されます (RunUsageTally を参照)
        val tally = currentCoroutineContext()[RunUsageTally]
        val attempts = ModelHttpAttempts()
        val reply = try {
            withContext(attempts) { call() }
        } catch (e: Throwable) {
            note(null, tally, attempts.started)
            throw e
        }
        note(reply.usage, tally, attempts.started)
        return reply
    }

    private fun note(usage: Usage?, tally: RunUsageTally?, httpAttempts: Int) {
        runCatching { sink.record(source, usage, httpAttempts) }
        tally?.add(usage, httpAttempts)
    }
}

private class MeteredStreamingModel(private val inner: StreamingChatModel, source: UsageSource, sink: UsageSink) :
    MeteredModel(inner, source, sink), StreamingChatModel {
    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = counted { inner.completeStreaming(system, messages, tools, onText) }
}
