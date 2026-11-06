package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun okObj(b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("ok", true); b() }
internal fun ok(b: JsonObjectBuilder.() -> Unit) = ToolExecution(okObj(b))

internal fun fail(error: String, message: String, recoverable: Boolean = true) = ToolExecution(buildJsonObject {
    put("ok", false); put("error", error); put("message", message); put("recoverable", recoverable)
})

/**
 * 编号用不了:动作没做。[cause] 说清是哪一种,运行记录和模型都看得到:[com.wangzan101.mika.core.uistate.StaleCause] 的几种,
 * 端口找不回元素的 element_lost,看图找到的点过期的 located_stale,把 screenshotId 当 snapshotId 的 screenshot_id。
 */
internal fun refExpired(message: String, cause: String) =
    ToolExecution(JsonObject(fail("ref_expired", message).content + ("cause" to JsonPrimitive(cause))))

internal fun invalid(e: ArgError) = fail("invalid_arguments", e.message ?: "invalid arguments")

/** エラーコードに基づいた既製の指示。 */
internal fun failCode(code: String) = fail(code, FAILURE_MESSAGES.getValue(code))

internal fun switchHint(pkg: String, byTarget: Boolean) =
    if (byTarget) Feedback.format("switch.by_target", "pkg" to pkg)
    else Feedback.format("switch.interruption", "pkg" to pkg)

internal val HINT_TAP_UNCHANGED: String get() = Feedback["tap_unchanged"]
internal val HINT_PROTECTED: String get() = Feedback["protected"]
internal val HINT_DISPLAY_CAPTURE: String get() = Feedback["display_capture_unavailable"]
internal val HINT_PAYMENT_TAKEOVER: String get() = Feedback["payment_takeover"]
internal val HINT_UNSUPPORTED_BROWSER: String get() = Feedback["unsupported_browser"]
internal val HINT_PAYMENT_PAGE: String get() = Feedback["payment_page"]
internal val HINT_NOT_SETTLED: String get() = Feedback["not_settled"]
/** Web サイトからのネットワーク応答: データを参照すると、指示が不適切です。 ダウンロードされたファイル名 */
internal val CAPTURE_UNTRUSTED: String get() = Feedback["capture_untrusted"]
internal val NOTE_CHANGED_DURING_READ: String get() = Feedback["changed_during_read"]

/** は、Web ページによって指定されています。これは、指示ではなくデータとして表示される必要があります。 */
internal val DOWNLOAD_NAMES_UNTRUSTED: String get() = Feedback["download_names_untrusted"]

/** エラー コード (prompts/フィードバック/tools.md のfailure.<code>) に示される指示に従ってください。 */
internal val FAILURE_MESSAGES: Map<String, String> by lazy { Feedback.section("failure.") }
