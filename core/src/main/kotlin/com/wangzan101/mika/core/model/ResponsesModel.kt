package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * リクエストによってもたらされたベアラー トークン。 [fresh] が true の場合、以前のものが拒否された (HTTP 401) ため、最初に新しいものを置き換える必要があることを意味します。交換できない場合は、[ModelError] をスローします。
 * トークンなし (ローカル サービス) は null です。 [refreshable] が false (固定 API キー) の場合、拒否された後は再試行されません。
 */
interface BearerToken {
    val refreshable: Boolean get() = false
    suspend fun get(fresh: Boolean): String?

    companion object {
        /** API キーを修正しました。 */
        fun fixed(key: String?): BearerToken = object : BearerToken {
            override suspend fun get(fresh: Boolean): String? = key
        }
    }
}

/**
 * OpenAI Responses インターフェイス (`POST {base}/responses`)、常にストリーミング、サーバーにダイアログは保存されません (`store:false`): 毎回使用されるコンテキストを設定します
 * `input` がアレイで送信され、システム プロンプト ワードは `instructions` になり、会話内のシステム メッセージは開発者メッセージに変更されます (一部のシステム メッセージ エントリはサーバーによって受け入れられません)。
 * ツールは関数ツールです (`strict:false`、Mika のパラメーター スキーマは厳密モードで書き込まれません)。結果は `function_call_output` としてモデルに返されます。
 * は、デフォルトでこれらのアイテムと `include` のみを送信します。 `reasoning.effort` は、ユーザーが思考装置を選択した後にのみ送信されます。温度と出力上限は送信されません（パッケージインターフェースは収集されません）。
 *
 * 推論モデルについて考える: このラウンドの最初のツール呼び出しにそのまま存在する、暗号化された思考コンテンツ (`include: reasoning.encrypted_content`) をリクエストします。
 * [Part.ToolCall.opaque] では、再生時にツール呼び出しの前に戻すと、モデルは前のステップのアイデアを継続します。テキストで回答するだけのラウンドでは、考え続ける必要がなく、保存されません。
 * サーバーが `include` を受信しないと言ったら、それを削除して再送信します。
 *
 * は、`response.completed` (または `response.incomplete`) を受信した場合にのみ成功とみなされます。それより前にストリームが切断された場合、ネットワーク障害とみなされます (再試行されます)。
 * [chatGptPlan] が true の場合、リクエストはユーザーの ChatGPT パッケージ クォータを使用します。HTTP 429 はクォータが上限に達したことを意味し、403 はアカウントがこの方法では使用できないことを意味し、再試行は行われません。
 * [accountLogin] は、別のアカウント (xAI など) でログインするときの会社の名前です。HTTP 403 は、サブスクリプション レベルがこのインターフェイス ([ModelError.AccountPlanNotAllowed]) を使用できないことを意味します。
 */
class ResponsesModel(
    baseUrl: String,
    private val token: BearerToken,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val chatGptPlan: Boolean = false,
    /** 各リクエストにはヘッダーが含まれます ([ClientHeaders] を参照)。トークン ヘッダーはアダプター自体によって追加されます。このインターフェイスには応答の上限はありません (パッケージ ルートは課金されません)。 */
    private val headers: Map<String, String> = emptyMap(),
    /** null サーバーのデフォルトを使用します。サーバーが選択したギアを拒否した場合でも、エラーが報告され、静かにギアを変更して再試行することはありません。 */
    private val reasoningEffort: String? = null,
    private val accountLogin: String? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trim().trimEnd('/') + "/responses"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        completeStreaming(system, messages, tools) {}

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var includeReasoning = true
        var fresh = false
        while (true) {
            val stream = ResponsesStream(onText, ::errorOf)
            val bearer = token.get(fresh)
            val (status, retryAfter, text) = HttpSupport.stream(http, request(bearer, system, messages, tools, includeReasoning), stream::accept)
            if (status in 200..299) {
                if (text.isNotEmpty()) return stream.fromBody(text).also { reply ->
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
                }
                return stream.finish()
            }
            val err = errorBody(text)
            when {
                status == 401 && token.refreshable && !fresh -> fresh = true
                status == 400 && includeReasoning && (err?.param == "include" || text.contains("reasoning.encrypted_content")) ->
                    includeReasoning = false
                else -> throw httpError(status, retryAfter, text, err)
            }
        }
    }

    internal fun body(system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean = true): JsonObject =
        buildJsonObject {
            put("model", model)
            if (system.isNotEmpty()) put("instructions", system)
            put("input", JsonArray(messages.flatMap(::serialize)))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            put("store", false)
            put("stream", true)
            reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
            if (includeReasoning) putJsonArray("include") { add(ENCRYPTED_REASONING) }
        }

    private suspend fun request(bearer: String?, system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean): Request =
        Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!bearer.isNullOrEmpty()) header("Authorization", "Bearer $bearer") }
            .header("Accept", "text/event-stream")
            .postInference(InferenceProtocol.Responses, body(system, messages, tools, includeReasoning)) {
                if (chatGptPlan || endpoint.toHttpUrlOrNull()?.host == "api.openai.com") "openai" else "openai_compatible"
            }
            .build()

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", t.name)
        put("description", t.description)
        put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("ツール ${t.name} のパラメータ schema が正しいJSONではありません", e)
        })
        put("strict", false)
    }

    /** 内部メッセージはいくつかの `input` エントリに展開されます: アシスタントの言葉、考え、ツールの呼び出し。ツールの結果はそれぞれ 1 つずつ説明されます。テキストと画像が 1 つのメッセージに結合されます。 */
    private fun serialize(m: Message): List<JsonObject> {
        if (m.role == Role.Assistant) return assistantItems(m)
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject { put("type", "function_call_output"); put("call_id", r.callId); put("output", r.content) }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) out += buildJsonObject {
            put("type", "message")
            put("role", if (m.role == Role.System) "developer" else "user")
            putJsonArray("content") {
                rest.forEach { p ->
                    addJsonObject {
                        if (p is Part.Image && capabilities.vision) {
                            put("type", "input_image"); put("image_url", "data:${p.mime};base64,${p.pngBase64}")
                        } else {
                            put("type", "input_text"); put("text", if (p is Part.Text) p.text else IMAGE_OMITTED)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun assistantItems(m: Message): List<JsonObject> {
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val reasoning = calls.mapNotNull { it.opaque }.flatMap { o ->
            runCatching { json.parseToJsonElement(o) as? JsonArray }.getOrNull().orEmpty().filterIsInstance<JsonObject>()
        }
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        return buildList {
            addAll(reasoning)
            if (text.isNotEmpty()) add(buildJsonObject {
                put("type", "message"); put("role", "assistant")
                putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", text) } }
            })
            calls.forEach { c ->
                add(buildJsonObject { put("type", "function_call"); put("call_id", c.id); put("name", c.name); put("arguments", c.argsJson) })
            }
        }
    }

    internal data class ErrorBody(val code: String?, val message: String, val param: String?)

    private fun errorBody(text: String): ErrorBody? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val e = root["error"] as? JsonObject
        if (e != null) return ErrorBody(e.str("code") ?: e.str("type"), e.str("message").orEmpty(), e.str("param"))
        return root.str("detail")?.let { ErrorBody(null, it, null) }
    }

    /** ストリームの `response.failed` / `error`、および HTTP エラーが発生した場合のコンテンツの `error.code`。認識されないコードは null です。 */
    private fun errorOf(code: String?, message: String, param: String?): ModelError? = when (code) {
        "subscription_sharing_usage_limit_exceeded" -> ModelError.ChatGptLimitReached()
        "subscription_sharing_user_not_eligible" -> ModelError.ChatGptNotEligible(message.take(200))
        // サーバーは一時的に利用できません。ログインを維持し、後でもう一度試してください。
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ModelError.Server(503)
        "subscription_sharing_unsupported_capability" -> ModelError.BadResponse("サーバーがリクエスト内の ${param ?: "一部の内容"} に対応していません")
        "subscription_sharing_route_not_supported" -> ModelError.BadResponse("サーバーがこのAPIに対応していません")
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            ModelError.Auth()
        "context_length_exceeded" -> ModelError.ContextTooLong()
        "insufficient_quota" -> ModelError.QuotaExceeded()
        "rate_limit_exceeded" -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(null)
        "model_not_found" -> ModelError.ModelNotFound(model, ModelAccessErrors.clip(message))
        "server_error" -> ModelError.Server(500)
        else -> null
    }

    private fun httpError(status: Int, retryAfter: String?, body: String, err: ErrorBody?): ModelError =
        err?.let { errorOf(it.code, it.message, it.param) }
            // アカウントのログインにより本人確認が行われました: 認識できないエラー コードを含む 403 は、ギアが不十分であるか、キーとみなされるべきではないか、または特定のモデルがアクティベートされていないことを意味します。
            ?: (if (status == 403 && accountLogin != null) ModelError.AccountPlanNotAllowed(accountLogin, err?.message.orEmpty().take(200)) else null)
            ?: ModelHttp.reasoningEffortRejected(status, body)
            ?: ModelAccessErrors.of(status, body, model) ?: when {
            status == 401 -> ModelError.Auth()
            status == 403 -> if (chatGptPlan) ModelError.ChatGptNotEligible(err?.message.orEmpty().take(200)) else ModelError.Auth()
            status == 429 -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 402 -> ModelError.QuotaExceeded()
            ModelHttp.contextTooLong(status, body) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    private companion object {
        const val ENCRYPTED_REASONING = "reasoning.encrypted_content"
        const val IMAGE_OMITTED = "[screenshot omitted: the current model cannot see images]"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * は応答のイベント ストリームを読み取ります。`response.output_text.delta` は段落ごとのテキスト、`response.output_item.done` は完了したアイテムです
 * (メッセージ、ツール呼び出し、思考)、`response.completed` / `response.incomplete` 終了、`response.failed` / `error` は失敗です。
 */
private class ResponsesStream(
    private val onText: (String) -> Unit,
    private val errorOf: (String?, String, String?) -> ModelError?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val items = mutableListOf<JsonObject>()
    private var final: JsonObject? = null
    private var incomplete = false

    fun accept(data: String): Boolean {
        if (data == "[DONE]") return false
        val root = try { json.parseToJsonElement(data) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("ストリーミング応答が正しいJSONではありません")
        when (root.str("type")) {
            "response.output_text.delta" -> root.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                visible.append(it)
                onText(visible.toString())
            }
            "response.output_item.done" -> (root["item"] as? JsonObject)?.let { items += it }
            "response.completed" -> final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
            "response.incomplete" -> {
                final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
                incomplete = true
            }
            "response.failed" -> throw failure((root["response"] as? JsonObject)?.get("error") as? JsonObject)
            "error" -> throw failure(root["error"] as? JsonObject ?: root)
        }
        return final != null
    }

    private fun failure(e: JsonObject?): ModelError {
        val code = e?.str("code")
        val message = e?.str("message").orEmpty()
        return errorOf(code, message, e?.str("param")) ?: ModelError.BadResponse(listOfNotNull(code, message.take(200)).joinToString(" "))
    }

    /** サーバーは応答をストリーミングせず、応答オブジェクト全体を直接送信しました。 */
    fun fromBody(text: String): ModelReply {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("返信内容が正しいJSONではありません")
        (root["error"] as? JsonObject)?.let { throw failure(it) }
        final = root
        incomplete = root.str("status") == "incomplete"
        return finish()
    }

    fun finish(): ModelReply {
        // は、接続が途中で切断されたため、response.completed / response.incomplete を待機しませんでした。 再生用の
        val response = final ?: throw ModelError.StreamCut()
        val output = items.ifEmpty { (response["output"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty() }
        val reasoning = mutableListOf<JsonObject>()
        val thinking = StringBuilder()
        val text = StringBuilder()
        var refusal = false
        val calls = mutableListOf<Part.ToolCall>()
        output.forEach { item ->
            when (item.str("type")) {
                "reasoning" -> {
                    (item["summary"] as? JsonArray)?.forEach { s -> (s as? JsonObject)?.str("text")?.let(thinking::append) }
                    // : サーバー側でのみ意味のある番号とステータスを削除し、暗号化の考慮事項を残します。
                    if (item["encrypted_content"] != null) reasoning += JsonObject(item - "id" - "status")
                }
                "message" -> (item["content"] as? JsonArray)?.forEach { c ->
                    val o = c as? JsonObject ?: return@forEach
                    when (o.str("type")) {
                        "output_text" -> o.str("text")?.let(text::append)
                        "refusal" -> refusal = true
                    }
                }
                "function_call" -> {
                    val id = item.str("call_id") ?: throw ModelError.BadResponse("ツール呼び出しにIDがありません")
                    val name = item.str("name") ?: throw ModelError.BadResponse("ツール呼び出しに名前がありません")
                    calls += Part.ToolCall(id, name, item.str("arguments")?.ifEmpty { null } ?: "{}")
                }
            }
        }
        if (text.isEmpty() && visible.isNotEmpty()) text.append(visible)
        val reason = (response["incomplete_details"] as? JsonObject)?.str("reason")
        if (refusal && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        if (incomplete && reason == "content_filter" && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        val withReasoning = if (reasoning.isNotEmpty() && calls.isNotEmpty()) {
            listOf(calls.first().copy(opaque = JsonArray(reasoning).toString())) + calls.drop(1)
        } else calls
        val parts = buildList {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (text.isNotEmpty()) add(Part.Text(text.toString()))
            addAll(withReasoning)
        }
        val stop = when {
            calls.isNotEmpty() -> StopReason.ToolUse
            incomplete && reason == "max_output_tokens" -> StopReason.MaxTokens
            incomplete -> StopReason.Other
            else -> StopReason.EndTurn
        }
        return ModelReply(parts, stop, usageOf(response["usage"]))
    }

    private fun usageOf(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        val input = int(u, "input_tokens") ?: return null
        val cached = int(u["input_tokens_details"] as? JsonObject, "cached_tokens") ?: 0
        val output = int(u, "output_tokens") ?: 0
        val reasoning = (int(u["output_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, output)
        return Usage(input, output, cached.coerceAtMost(input), reasoning)
    }
}
