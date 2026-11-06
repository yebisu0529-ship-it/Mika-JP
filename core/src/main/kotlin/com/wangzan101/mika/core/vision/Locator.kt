package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.TreeAdequacy
import com.wangzan101.mika.core.uistate.TreeQuality
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** 2 つのスクリーンショットのハッシュ距離がそれを超えない場合、同じ画面とみなされます (コントロール ツリーは画面と同期されており、@v 番号はまだ有効です)。 */
const val LOCATE_SCREEN_CHANGE = 8

sealed interface LocateResult {
    /** [x]/[y]/[box] は 0 ～ 999 で正規化され、タップと同じ座標系です。 [source] は「要素」または「ビジョン」です。 */
    data class Found(
        val ref: String,
        val snapshotId: String,
        val x: Int,
        val y: Int,
        val box: IntArray?,
        val seen: String?,
        val source: String,
        val note: String?,
        /** 証拠なしで写真を見てください (この画面のツリーは使用できません。比較するものはありません)。クリックして効果を確認してください。 */
        val unverified: Boolean = false,
    ) : LocateResult

    data class NotFound(val reason: String, val message: String) : LocateResult
}

/** 画面を取得できないか、ビジュアル モデルに表示できません。 [reason] は protected_content、no_app_open などです。 */
class ScreenUnavailable(val reason: String, message: String) : Exception(message)

/** モデルが見つからない、または表示できない場合の手順。名前が付けられているのは、この画面とそのツールのセットです。 */
private class LocateMessages(f: ScreenFamily) {
    private val describe = f.tool("describe")
    private val dump = f.tool("list_elements")
    val notFoundNext = "Try another description or pass the exact on-screen label as text, look with $describe, or call hand_to_user."
    val protectedScreen = "This screen is protected and cannot be captured, so nothing on it can be located by sight. " +
        "Use $dump and tap by ref, or call hand_to_user."
    val noApp = "Nothing is open on this screen yet, so there is nothing to look at. Open an app or a page first."
    val noShot = "The screen could not be captured right now. Wait a moment and try again, or use $dump."
    val unchecked = "The screen could not be read, so its picture was not sent to the vision model. " +
        "Wait a moment and try again, or use $dump."
    val checkSeen = "Check that seen is what you want before tapping; if it is not, do not tap it."
    val uncorroborated = "Something was marked by sight, but this vision model's boxes are not verified and nothing backed this one up " +
        "(no on-screen label under it matching the text, and a second look did not mark the same element), so it was not used. " +
        "Use $describe to look at the screen, then tap by coordinates with a target; or pass the exact on-screen label as text."
    val visionOnly = "Marked by sight alone: this screen's element list cannot back the mark up, so it was not cross-checked. " +
        "Check that seen is what you want, tap it, then read the result's effect: if it is no_effect, the mark missed; do not tap it again, " +
        "find it again with a different description or tap a visibleText center instead."
}

private val BBOX_2D = Regex("\"?bbox_2d\"?\\s*:\\s*\\[\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]")
private val BARE_BOX = Regex("^\\[\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]$")
private val FOUR_NUMBERS = Regex("\\[\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*]")
private val SEEN = Regex("\"seen\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val YES = Regex("^(是|yes(?![A-Za-z0-9_]))", RegexOption.IGNORE_CASE)
private const val MAX_SEEN = 120

private val PROMPTS: Pair<String, String> by lazy {
    val (locate, confirm) = Prompts.role("locate").split("<!-- confirm -->")
    locate.trim() to confirm.trim()
}
private val DESCRIBE_PROMPT: String by lazy { Prompts.role("describe") }

/**
 * 画面上の要素を検索します。元のテキストがあり、コントロール ツリーが画面と同期している場合は、まずコントロール ツリー内で完全に一致する一意の要素を検索します。
 * それ以外の場合は、ビジュアル モデルをフレーム化してから、名前を確認し、赤枠を描画して再度確認します。見つかった点は[registry]に登録されます。
 * 保護されたページ (システム アンチスクリーンショット) の画面はビジュアル モデルに渡されません。
 */
class Locator(private val screen: ScreenPort, private val vision: VisionService, private val registry: LocateRegistry, family: ScreenFamily) {
    private val m = LocateMessages(family)
    private class Box(val l: Int, val t: Int, val r: Int, val b: Int, val seen: String?)

    suspend fun locate(target: String, text: String?): LocateResult {
        val first = capture().let { it as? Shot.Ok ?: return (it as Shot.Missing).asNotFound() }
        val elements = if (text != null) readElements() else null
        val second = capture().let { it as? Shot.Ok ?: return (it as Shot.Missing).asNotFound() }
        val synced = elements != null && hamming(first.hash, second.hash) <= LOCATE_SCREEN_CHANGE

        if (text != null && synced) {
            val hit = exactMatch(elements!!, text)
            if (hit != null) {
                val b = hit.bounds
                val x = mid(b[0], b[2]); val y = mid(b[1], b[3])
                val (ref, sid) = registry.put(x, y, second.hash)
                return LocateResult.Found(ref, sid, x, y, intArrayOf(b[0], b[1], b[2], b[3]), null, "elements", null)
            }
        }

        clearForVision()?.let { return it.asNotFound() }
        val (locatePrompt, confirmPrompt) = PROMPTS
        val box = try {
            parseBox(vision.ask(locatePrompt, if (text != null) "$target (label: \"$text\")" else target, listOf(second.png)))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
        } ?: return LocateResult.NotFound("not_found", "The described element was not found on the screen. ${m.notFoundNext}")

        if (text != null && box.seen != null && disagree(box.seen, text)) {
            return LocateResult.NotFound("name_mismatch", "The closest match reads \"${box.seen.take(40)}\", not \"$text\", so it was not used. ${m.notFoundNext}")
        }
        val x = mid(box.l, box.r); val y = mid(box.t, box.b)
        val occupant = if (text != null && synced) elementAt(elements!!, x, y) else null
        if (occupant != null && occupant.name.isNotBlank() && disagree(occupant.name, text!!)) {
            return LocateResult.NotFound("name_mismatch", "The spot that looked like it is labelled \"${occupant.name.take(40)}\", not \"$text\", so it was not used. ${m.notFoundNext}")
        }

        val boxArr = intArrayOf(box.l, box.t, box.r, box.b)
        val confirmed = try {
            val framed = vision.ops.drawFrame(second.png, boxArr)
            YES.containsMatchIn(vision.ask(confirmPrompt.replace("{target}", target), "Yes or no?", listOf(framed)).trim())
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
        }
        if (!confirmed) return LocateResult.NotFound("not_confirmed", "Something was marked, but a second look did not confirm it is the described element. ${m.notFoundNext}")

        val nameChecked = occupant != null && occupant.name.isNotBlank()
        // 位置決め精度が検証されていないモデル: 画像のみに依存するボックスがサポートされている必要があります。ボックスの中央の要素が探している単語です (名前は上記で確認されています)。または、単語が一貫していることを確認するためにボックスを再度チェックし、2 回再表示する必要があります。
        // ツリーが使用できない場合、またはピースが不足していて、探しているものがツリーにない場合は、比較するものはありません。とにかくそれを渡し、裏付けとなる証拠なしとしてマークし、クリックした後に結果を見てください。
        val visionOnly = !vision.groundingVerified && !nameChecked && nothingToCheckAgainst(text)
        if (!vision.groundingVerified && !nameChecked && !visionOnly) {
            val again = try {
                secondLook(target, text, box, second.png, locatePrompt, confirmPrompt)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
            }
            if (!again) return LocateResult.NotFound("unverified_grounding", m.uncorroborated)
        }

        val (ref, sid) = registry.put(x, y, second.hash)
        if (visionOnly) return LocateResult.Found(ref, sid, x, y, boxArr, box.seen, "vision", m.visionOnly, unverified = true)
        return LocateResult.Found(ref, sid, x, y, boxArr, box.seen, "vision", m.checkSeen.takeUnless { nameChecked && box.seen != null })
    }

    /**
     * この画面にはピクチャ フレームをサポートできるものはありません。ツリーは使用できません。または、ピースが欠落しており、探している単語 ([text]) がツリー内にありません (単語がない場合のクリックはカウントされません)。
     * ツリーが足りていても、判定が正しくなくても、画面が読めなくても、サポートは必要です。
     */
    private suspend fun nothingToCheckAgainst(text: String?): Boolean {
        val s = peekOrNull() ?: return false
        return when (TreeAdequacy.ofTree(s).quality) {
            TreeQuality.Unusable -> true
            TreeQuality.Partial -> text == null || s.elements.none { it.name.isNotBlank() && norm(text) in norm(it.name) }
            else -> false
        }
    }

    /**
     * 同じ写真が再度フレームに入れられます。2 つのフレームは互いの中心を覆い、表示される単語は一貫しています (すべて存在する必要があります)。2 番目のフレームは赤色で再度チェックされ、証拠とみなされます。
     */
    private suspend fun secondLook(target: String, text: String?, first: Box, png: ByteArray, locatePrompt: String, confirmPrompt: String): Boolean {
        val box = parseBox(vision.ask(locatePrompt, if (text != null) "$target (label: \"$text\")" else target, listOf(png))) ?: return false
        val seenA = first.seen ?: return false
        val seenB = box.seen ?: return false
        if (disagree(seenA, seenB) || (text != null && disagree(seenB, text))) return false
        if (!contains(first, mid(box.l, box.r), mid(box.t, box.b)) || !contains(box, mid(first.l, first.r), mid(first.t, first.b))) return false
        val framed = vision.ops.drawFrame(png, intArrayOf(box.l, box.t, box.r, box.b))
        return YES.containsMatchIn(vision.ask(confirmPrompt.replace("{target}", target), "Yes or no?", listOf(framed)).trim())
    }

    private fun contains(b: Box, x: Int, y: Int) = x in b.l..b.r && y in b.t..b.b

    /** 現在のスクリーンショットと「今やりたいこと」をビジュアルモデルに渡し、説明をそのまま返します。画面が取得できない場合は、[ScreenUnavailable]がスローされます。モデルが失敗すると、ModelError がスローされます。 */
    suspend fun describe(task: String): String {
        val shot = capture().let { it as? Shot.Ok ?: throw (it as Shot.Missing).let { m -> ScreenUnavailable(m.reason, m.message) } }
        clearForVision()?.let { throw ScreenUnavailable(it.reason, it.message) }
        return vision.ask(DESCRIBE_PROMPT, "What I want to do now: $task", listOf(shot.png))
    }

    /**
     * 現在のピクチャをビジュアル モデルに渡すことができるかどうか。null にすることもできます。それができない場合はそれが理由です
     * (保護コンテンツ、画面が読み取れない場合はビジョン利用不可)。
     */
    internal suspend fun visionBlocker(): String? = clearForVision()?.reason

    private sealed interface Shot {
        class Ok(val png: ByteArray, val hash: Long) : Shot
        class Missing(val reason: String, val message: String) : Shot {
            fun asNotFound() = LocateResult.NotFound(reason, message)
        }
    }

    /**
     * スクリーンショットを撮ります。画面上にスクリーンショット防止ウィンドウがある場合、スクリーンショットは撮影されません (スクリーンショットも黒くなり、支払いページはアップロードできません)。
     * 切断できない時間は、「アプリがまだ開いていない」場合と、一時的に切断できない時間に分かれます。
     */
    private suspend fun capture(): Shot {
        if (screen.isProtected()) return Shot.Missing("protected_content", m.protectedScreen)
        val png = screen.screenshotPng()
        if (png == null) {
            val s = peekOrNull()
            if (s?.captureError == ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE) {
                return Shot.Missing(s.captureError, Feedback["display_capture_unavailable"])
            }
            val empty = s != null && s.packageName == null && s.elements.isEmpty() && !s.hasWindow
            return if (empty) Shot.Missing("no_app_open", m.noApp) else Shot.Missing("screenshot_unavailable", m.noShot)
        }
        // スクリーンショットを撮ってもう一度見る: スクリーンショットの処理中に、保護されたページに切り替わりました。
        if (vision.ops.isMostlyBlack(png) && screen.isProtected()) return Shot.Missing("protected_content", m.protectedScreen)
        return Shot.Ok(png, vision.ops.aHash(png))
    }

    /**
     * ビジュアル モデルに渡す前に画面をもう一度読み取ります。保護されたページは転送されません。読み取れない画面は転送されません。
     * 支払いページは通常のページとして処理されます。 null として渡すことができます。
     */
    private suspend fun clearForVision(): Shot.Missing? {
        val s = peekOrNull() ?: return Shot.Missing("vision_unavailable", m.unchecked)
        if (s.captureError == ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE) {
            return Shot.Missing(s.captureError, Feedback["display_capture_unavailable"])
        }
        if (s.protected) return Shot.Missing("protected_content", m.protectedScreen)
        return null
    }

    private suspend fun readElements(): List<UiElement>? = peekOrNull()?.takeIf { !it.protected }?.elements

    private suspend fun peekOrNull(): ScreenSnapshot? =
        try { screen.peek() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** は、名前またはリソース ID (空白と小文字が削除された) が [text] と正確に等しい唯一の要素です。入れ子または同心円状のものは同じものとしてカウントされ、複数のものは null になります。 */
    private fun exactMatch(elements: List<UiElement>, text: String): UiElement? {
        val want = norm(text)
        val hits = elements.filter { e -> onScreen(e) && (norm(e.name) == want || e.resourceId?.let { norm(it) == want } == true) }
        val distinct = hits.filterIndexed { i, e -> hits.take(i).none { o -> center(o) == center(e) || nested(o, e) } }
        return distinct.singleOrNull()
    }

    /** その時点での最小要素、画面の半分未満。 */
    private fun elementAt(elements: List<UiElement>, x: Int, y: Int): UiElement? = elements.filter { e ->
        val b = e.bounds
        b.size == 4 && x in b[0]..b[2] && y in b[1]..b[3] && area(e) < 999L * 999 / 2
    }.minByOrNull(::area)

    private fun parseBox(reply: String): Box? {
        val t = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t == "null") return null
        if (Regex("bbox_2d").findAll(t).count() > 1 || FOUR_NUMBERS.findAll(t).count() > 1) return null
        val m = BBOX_2D.find(t) ?: BARE_BOX.find(t) ?: return null
        val (x1, y1, x2, y2) = m.groupValues.drop(1).map { it.toDouble() }
        if (listOf(x1, y1, x2, y2).any { it > 1000 } || x2 <= x1 || y2 <= y1) return null
        // 0–1 の小数ボックスは別の座標規則であり、変換は信頼できません。
        if (maxOf(x1, y1, x2, y2) <= 1) return null
        fun scale(v: Double) = (v * 999 / 1000).roundToInt()
        val seen = SEEN.find(t)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(MAX_SEEN)?.ifEmpty { null }
        return Box(scale(x1), scale(y1), scale(x2), scale(y2), seen)
    }

    private fun unescape(raw: String): String =
        try { (Json.parseToJsonElement("\"$raw\"") as JsonPrimitive).content } catch (e: Exception) { raw }

    private fun disagree(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && y !in x && x !in y
    }

    /** は画面内にあり、領域があります (画面読み取りでは画面外の要素が端に押し出され、領域は 0)。 */
    private fun onScreen(e: UiElement): Boolean {
        val b = e.bounds
        return b.size == 4 && b[0] >= 0 && b[1] >= 0 && b[2] <= 999 && b[3] <= 999 && b[2] > b[0] && b[3] > b[1]
    }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
    private fun mid(a: Int, b: Int) = ((a + b) / 2.0).roundToInt()
    private fun center(e: UiElement) = mid(e.bounds[0], e.bounds[2]) to mid(e.bounds[1], e.bounds[3])
    private fun area(e: UiElement): Long = (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    private fun nested(a: UiElement, b: UiElement): Boolean {
        val p = a.bounds; val q = b.bounds
        return (p[0] <= q[0] && p[1] <= q[1] && p[2] >= q[2] && p[3] >= q[3]) || (q[0] <= p[0] && q[1] <= p[1] && q[2] >= p[2] && q[3] >= p[3])
    }
}
