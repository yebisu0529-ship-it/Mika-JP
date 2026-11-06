package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.CdpKeys
import com.wangzan101.mika.core.uistate.KeyModifier
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.Resolved
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.PAGE_REF_RE
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.toPx
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.lastOrSnapshot
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.uistate.session
import com.wangzan101.mika.core.uistate.withPortRef
import com.wangzan101.mika.core.uistate.DwellStop
import com.wangzan101.mika.core.uistate.dwellStopped

/** コピー、カット、ペースト。 */
private val CLIPBOARD_KEYS = setOf("KeyC", "KeyX", "KeyV")

/**
 * web_interact: アクションに従ってパラメーターを検証し (余分なパラメーターはエラーを報告し、黙って無視されません)、座標は最新の画面に従ってビュー ピクセルに置き換えられます。
 * 安定した番号付けがオンになっている場合、両端の番号が最初にポート番号に変換されます。キーの組み合わせ、ドラッグ、およびドロップダウン選択によって送信がトリガーされる場合がありますが、それが元に戻せないかどうかを判断するには、ターゲットが送信を決定する必要があります。
 */
internal object Interact : WebTool {
    override val verb = Verb.Interact
    override val risk = Risk.External
    override val gateTarget = GateTarget.Interact
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def = ToolDef("web_interact",
        Prompts.tool("web_interact"),
        mapOf(
            "action" to str("What to do.", listOf("hover", "drag", "select", "key", "dialog")),
            "ref" to str("Element from the latest web_observe/web_list_elements: hover target, drag start, or the drop-down for select."),
            "snapshotId" to str("snapshotId of that web_observe/web_list_elements result (also used for toRef)."),
            "x" to int("Instead of ref for hover/drag: point 0-999 across the screen.", 0, 999),
            "y" to int("Instead of ref for hover/drag: point 0-999 down the screen.", 0, 999),
            "toRef" to str("drag: element to release over."),
            "toX" to int("drag: release point 0-999 across, instead of toRef.", 0, 999),
            "toY" to int("drag: release point 0-999 down, instead of toRef.", 0, 999),
            "steps" to int("drag: mouse moves between press and release, 1-$DRAG_STEPS_MAX (default $DRAG_STEPS_DEFAULT).", 1, DRAG_STEPS_MAX),
            "option" to str("select: the option's shown text or its value."),
            "answer" to str("dialog: accept (OK) or dismiss (Cancel) the page's open dialog.", listOf("accept", "dismiss")),
            "text" to str("dialog: text to enter into a prompt dialog before accepting (default: its prefilled text)."),
            "key" to str("key: one key (Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp/Down/Left/Right, Home, End, PageUp, PageDown, a letter or digit), optionally after Control+, Shift+, Alt+ or Meta+."),
        ), listOf("action"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action")!!
        val allowed = when (action) {
            "hover" -> setOf("ref", "snapshotId", "x", "y")
            "drag" -> setOf("ref", "snapshotId", "x", "y", "toRef", "toX", "toY", "steps")
            "select" -> setOf("ref", "snapshotId", "option")
            "key" -> setOf("key")
            "dialog" -> setOf("answer", "text")
            else -> throw ArgError("action", "must be hover, drag, select, key or dialog")
        }
        a.o.keys.firstOrNull { it != "action" && it != "reason" && it !in allowed }?.let { throw ArgError(it, "not used by action $action") }
        val w = ctx.web!!
        return when (action) {
            "dialog" -> {
                val answer = a.str("answer") ?: throw ArgError("answer", "required for action dialog: accept or dismiss")
                if (answer != "accept" && answer != "dismiss") throw ArgError("answer", "must be accept or dismiss")
                ctx.action(w.answerDialog(answer == "accept", a.str("text", max = 2000)))
            }
            "key" -> {
                val spec = a.str("key", max = 60) ?: throw ArgError("key", "required for action key")
                val combo = CdpKeys.parseCombo(spec) ?: throw ArgError("key", "not a supported key or combination: '$spec'. Use names like Enter, Escape, Tab, ArrowDown, Control+A, Shift+Tab")
                // クリップボード ショートカット キーは、ユーザーのクリップボードの内容を Web ページに転送します (またはクリップボードを変更します): しないでください
                if (combo.modifiers.any { it == KeyModifier.Control || it == KeyModifier.Meta } && combo.key.code in CLIPBOARD_KEYS) {
                    throw ArgError("key", "clipboard shortcuts (copy, cut, paste) are not allowed; use web_type_text to enter text")
                }
                // Enter などのキーは送信してページを変更できます。クリックと同じように、最初にこのページに滞在した時間を埋め合わせます。
                try { ctx.dwell?.before(port = w) } catch (e: DwellStop) { return dwellStopped(e) }
                ctx.action(w.pressCombo(combo))
            }
            "select" -> {
                val ref = a.ref() ?: throw ArgError("ref", "required for action select")
                val option = a.str("option", max = 200) ?: throw ArgError("option", "required for action select")
                ctx.withPortRef(ref) { r -> ctx.action(w.selectOption(r!!.first, r.second, option)) }
            }
            "hover" -> {
                val at = point(a, ctx, "ref", "x", "y") ?: throw ArgError("ref", "give ref+snapshotId or x+y")
                withPortPoint(at, ctx) { p -> ctx.action(w.hover(p)) }
            }
            else -> {
                val from = point(a, ctx, "ref", "x", "y") ?: throw ArgError("ref", "give ref+snapshotId or x+y for where the drag starts")
                val to = point(a, ctx, "toRef", "toX", "toY") ?: throw ArgError("toRef", "give toRef (with snapshotId) or toX+toY for where the drag ends")
                val steps = a.int("steps", 1, DRAG_STEPS_MAX) ?: DRAG_STEPS_DEFAULT
                withPortPoint(from, ctx) { pf ->
                    withPortPoint(to, ctx) { pt -> ctx.action(w.drag(pf, pt, steps)) }
                }
            }
        }
    }

    /** ドロップ ポイント: 数値 ([refKey]、snapshotId によって共有) または 0 ～ 999 の座標。両方が指定された場合、座標の半分のみが指定された場合はエラーが報告され、どちらも指定されなかった場合は null になります。 */
    private suspend fun point(a: Args, ctx: ToolContext, refKey: String, xKey: String, yKey: String): WebPoint? {
        val ref = a.str(refKey)
        val x = a.coord(xKey); val y = a.coord(yKey)
        if (ref != null) {
            if (x != null || y != null) throw ArgError(xKey, "give either $refKey or $xKey+$yKey, not both")
            if (!PAGE_REF_RE.matches(ref)) throw ArgError(refKey, "must look like @e3")
            val sid = a.str("snapshotId") ?: throw ArgError("snapshotId", "required when $refKey is given")
            return WebPoint.Ref(ref, sid)
        }
        if ((x == null) != (y == null)) throw ArgError(if (x == null) xKey else yKey, "give both $xKey and $yKey")
        if (x == null) return null
        val s = ctx.lastOrSnapshot()
        return WebPoint.Xy(toPx(x, s.width), toPx(y!!, s.height))
    }

    /** 稳定编号开着时先翻译模型编号;失效原因原样交给统一的 ref_expired 结果,不执行动作。 */
    private suspend fun withPortPoint(p: WebPoint, ctx: ToolContext, act: suspend (WebPoint) -> ToolExecution): ToolExecution {
        val st = ctx.session.stabilizer
        if (p !is WebPoint.Ref || st == null) return act(p)
        return when (val r = st.resolve(p.ref, p.snapshotId)) {
            is Resolved.Current -> act(WebPoint.Ref(r.portRef, r.portSnapshotId))
            is Resolved.Stale -> refExpired(Feedback["ref_expired"], r.cause.wire)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("action"), o.argStr("answer"), o.argStr("key") ?: o.argStr("option")?.let { "「${it.take(40)}」" },
            o.argStr("ref")?.let { ref -> ctx.lastSnapshot?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref },
            o.argStr("toRef")?.let { "→ $it" }).joinToString(" ")
}
