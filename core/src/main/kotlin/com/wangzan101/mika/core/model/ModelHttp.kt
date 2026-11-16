package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 会話アダプターに加えて、エラー (音声からテキストへなど) を報告するために同じメソッドが使用されます。HTTP ステータスとエラー内容を [ModelError] に変換するリクエストを送信します。
 * マッピング自体は各アダプターからそのまま移動し、2 つのコピーを変更しないように、ここでアダプターを変更します。
 */
object ModelHttp {
    class Reply(val status: Int, val retryAfter: String?, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** はリクエストを送信し、応答を読み取ります。ネットワークに障害が発生すると、[ModelError.Network] がスローされます。コルーチンをキャンセルするとリクエストもキャンセルされます。 */
    suspend fun send(http: OkHttpClient, request: Request): Reply =
        HttpSupport.execute(http, request).let { Reply(it.status, it.retryAfter, it.body) }

    /** OpenAI 互換インターフェイス (同じ一連のダイアログと音訳) のマッピングが正しくありません。 [model] は、要求されたモデル名です。 */
    fun openAiError(status: Int, retryAfter: String?, body: String, model: String): ModelError =
        ModelAccessErrors.of(status, body, model) ?: reasoningEffortRejected(status, body) ?: when {
            status == 401 -> ModelError.Auth()
            status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
            status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter, body))
            contextTooLong(status, body) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            status == 403 -> ModelError.Auth()
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    /** HTTP 400 がコンテキスト上限超過を示すか判定します。OpenAI互換・Responses・Anthropic で共用します。 */
    fun contextTooLong(status: Int, body: String): Boolean = status == 400 && saysContextTooLong(body)

    /** HTTP 400 明确指出思考强度参数无效。只认参数名或完整的协议字段,不从任意“unsupported”文字猜。 */
    fun reasoningEffortRejected(status: Int, body: String): ModelError.ReasoningEffortRejected? {
        if (status != 400) return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val param = (error?.get("param") as? JsonPrimitive)?.contentOrNull ?: (root?.get("param") as? JsonPrimitive)?.contentOrNull
        val lower = body.lowercase()
        val namesParameter = param in setOf("reasoning.effort", "reasoning_effort") ||
            (param == null && ("reasoning.effort" in lower || "reasoning_effort" in lower))
        val rejected = listOf("invalid", "unsupported", "not supported", "unknown").any { it in lower }
        return if (namesParameter && rejected) ModelError.ReasoningEffortRejected(ModelAccessErrors.detail(body)) else null
    }

    /** 上限を超える表現と、その近くの context length・size・limit・window、model length を大文字小文字を区別せず確認します。従来の context_length・maximum context・context length of only も認識し、単に context window があるだけでは上限超過と判定しません。 */
    internal fun saysContextTooLong(text: String): Boolean = CONTEXT_TOO_LONG.containsMatchIn(text)

    private val CONTEXT_TOO_LONG = Regex(
        """(?:exceed|longer than|larger than|greater than|max)[^.\n]{0,40}?(?:context[ _](?:length|size|limit|window)|model[ _]length)|context_length|maximum context|context length of only""",
        RegexOption.IGNORE_CASE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Gemini ネイティブ インターフェイスのマッピングが正しくありません。 */
    fun geminiError(status: Int, retryAfter: String?, body: String, model: String): ModelError {
        val err = try { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject } catch (e: Exception) { null }
        val st = (err?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
        val lower = msg.lowercase()
        ModelAccessErrors.of(status, body, model)?.let { return it }
        return when {
            status == 401 || status == 403 -> ModelError.Auth()
            // クォータがゼロの 429 モデルは使用不可能とみなされています。残りの 429 は、再試行可能な現在の制限として扱われます (現在の制限とクォータ コピーが混在しており、確実に区別できません)。
            status == 429 || st == "RESOURCE_EXHAUSTED" -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter, msg))
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status == 413 || (status == 400 && lower.contains("token") &&
                (lower.contains("exceed") || lower.contains("too long") || lower.contains("maximum"))) -> ModelError.ContextTooLong()
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }
    }
}
