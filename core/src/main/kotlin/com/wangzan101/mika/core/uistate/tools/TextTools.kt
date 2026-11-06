package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.uistate.DESCRIBED_ELEMENT
import com.wangzan101.mika.core.uistate.DESCRIBED_ROLE
import com.wangzan101.mika.core.tool.COORD
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.REF
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.SNAP
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.switchHint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.lastOrSnapshot
import com.wangzan101.mika.core.uistate.locatedInfo
import com.wangzan101.mika.core.uistate.toPx
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.withPortRef

private const val KEYCODE_ENTER = 66

/** 追加パラメータを入力してクリアします: 点の座標 (参照は、@vN の画像を見ることで見つけることもできます)。ツリーが入力ボックスを読み取れない場合は、その箇所に直接入力します。 */
private val POINT_ARGS = mapOf(
    "x" to int("Instead of a ref: the input box's point (e.g. a visibleText center), tapped before typing. $COORD", 0, 999),
    "y" to int(COORD, 0, 999),
)

/** 入力する場所: ツリー内の要素 (数値)、またはポイント (ピクセル。[located] は、ピクチャ @vN を調べて見つかりました。null ではありません)。 */
private sealed interface FieldAim {
    data class Ref(val ref: Pair<String, String>?) : FieldAim
    data class Point(val x: Int, val y: Int, val located: String?) : FieldAim
}

/**
 * パラメータに従って入力する場所を決定します。点が指定されていない場合、それは数値 (またはフォーカスがあるフレーム) です。座標または @vN が指定されている場合、それが点であり、@vN は見つかったときに画像と一致する必要があります。
 * 使用不可 @vN 失敗の結果がモデルに返されました。
 */
private suspend fun aim(a: Args, ctx: ToolContext): Pair<FieldAim?, ToolExecution?> {
    val ref = a.ref(located = true)
    val x = a.coord("x")
    val y = a.coord("y")
    if (ref != null && (x != null || y != null)) throw ArgError("x", "give either ref+snapshotId or x+y, not both")
    if ((x == null) != (y == null)) throw ArgError(if (x == null) "x" else "y", "x and y go together")
    if (ref != null && LOCATED_REF.matches(ref.first)) {
        val p = locatedPixel(ctx, ref.first, ref.second) ?: return null to refExpired(Feedback["located_stale"], "located_stale")
        return FieldAim.Point(p.first, p.second, ref.first) to null
    }
    if (x != null && y != null) {
        val s = ctx.lastOrSnapshot()
        return FieldAim.Point(toPx(x, s.width), toPx(y, s.height), null) to null
    }
    return FieldAim.Ref(ref) to null
}

/** 画像を見て見つけた点を入力し、クリアするときに結果で探している内容を書き込みます。裏付けとなる証拠なしに要点をマークします。 */
private fun located(ctx: ToolContext, aim: FieldAim?, r: ToolExecution): ToolExecution {
    val ref = (aim as? FieldAim.Point)?.located ?: return r
    val info = ctx.locatedInfo[ref] ?: return r
    val extra = listOfNotNull(info.target?.let { "located" to JsonPrimitive(it) }, ("unverifiedPoint" to JsonPrimitive(true)).takeIf { info.unverified })
    return if (extra.isEmpty()) r else r.with(content = JsonObject(r.content + extra))
}

/** 入力(台車を持ち帰る際に送信される場合があり、対象に応じて不可逆と判断する必要があります)。 */
internal class TypeText(f: ScreenFamily) : ScreenTool(f, "type_text") {
    override val verb = Verb.TypeText
    override val batchable = true
    override val routable = f.routable
    override val label = "テキストを入力"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Enter
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "text" to buildJsonObject { put("type", "string"); put("description", "Text to enter, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "pressEnter" to bool("Press Enter after typing (default false)."),
            "ref" to str(REF), "snapshotId" to str(SNAP),
            DESCRIBED_ELEMENT to str("Instead of ref+snapshotId: the input field's label, accessible name or placeholder, as shown on the screen. It must match exactly one input field on the screen right now."),
            DESCRIBED_ROLE to str(DESCRIBE_ROLE),
        ) + POINT_ARGS, listOf("text")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an input field here; use ref+snapshotId")
        val text = a.str("text", max = 10000)!!
        val enter = a.bool("pressEnter", false)
        val (target, failed) = aim(a, ctx)
        failed?.let { return it }
        return when (target) {
            is FieldAim.Point -> located(ctx, target, typeInto(ctx, text, enter) { ctx.screen.typeAt(target.x, target.y, text) })
            else -> ctx.withPortRef((target as FieldAim.Ref).ref) { ref -> typeInto(ctx, text, enter) { ctx.screen.typeText(ref?.first, ref?.second, text) } }
        }
    }

    private suspend fun typeInto(ctx: ToolContext, text: String, enter: Boolean, type: suspend () -> ActionResult): ToolExecution {
        val screen = ctx.screen
        val typed = type()
        if (typed !is ActionResult.Ok) return ctx.action(typed)
        val v = typed.verification
        if (v == Verification.MISMATCH) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_not_applied"); put("verification", v.wire)
            put("message", Feedback["type.not_applied"])
            put("recoverable", true)
        })
        val switched = typed.appSwitched
        // 読み返せない場合は、Enter キーを押さないでください。入力されていないコンテンツが送信される可能性があります。フロントデスクが切り替わったときに Enter を押すと、他のアプリに入力されます。
        val pressEnter = enter && v != Verification.UNKNOWN && switched == null
        if (pressEnter) {
            val k = screen.pressKey(KEYCODE_ENTER)
            if (k !is ActionResult.Ok) return ctx.action(k)
        }
        return ToolExecution(okObj {
            put("typed", text.length); put("pressedEnter", pressEnter)
            put("secret", typed.secret)
            if (typed.relocated) put("relocated", true)
            typed.relocatedTo?.takeIf { typed.relocated }?.let { put("relocatedTo", it) }
            v?.let { put("verification", it.wire) }
            if (enter && !pressEnter) put("enterSkipped", true)
            switched?.let { put("app_switched", it) }
            val hints = listOfNotNull(
                typed.hint,
                (if (enter) Feedback.format("type.unverified_enter", "unverified" to Feedback["type.unverified"]) else Feedback["type.unverified"])
                    .takeIf { v == Verification.UNKNOWN },
                switched?.let { switchHint(it, typed.switchedByTarget).let { h -> if (enter) Feedback.format("type.switched_enter", "switch" to h) else h } },
            )
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = typed.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = refTarget(o, r, ctx.lastSnapshot) ?: pointTarget(o)

    // ポートがパスワード ボックスではないことを明確に示している場合にのみ、入力された単語が書き込まれます。
    override fun logTyped(o: JsonObject, r: ToolExecution) =
        o.argStr("text")?.takeIf { r.content["secret"] == JsonPrimitive(false) }?.let { "「${it.take(40)}」" }
}

internal class ClearText(f: ScreenFamily) : ScreenTool(f, "clear_text") {
    override val verb = Verb.ClearText
    override val batchable = true
    override val routable = f.routable
    override val label = "入力欄をクリア"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe(),
        mapOf("ref" to str(REF), "snapshotId" to str(SNAP)) + POINT_ARGS) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val (target, failed) = aim(a, ctx)
        failed?.let { return it }
        return when (target) {
            is FieldAim.Point -> located(ctx, target, ctx.action(ctx.screen.clearAt(target.x, target.y)))
            else -> ctx.withPortRef((target as FieldAim.Ref).ref) { r -> ctx.action(ctx.screen.clearText(r?.first, r?.second)) }
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = refTarget(o, r, ctx.lastSnapshot) ?: pointTarget(o)
}

/** 座標点を入力してクリアするときにアクション リストに書き込まれるオブジェクト。 */
private fun pointTarget(o: JsonObject): String? = o["x"]?.let { x -> "${x},${o["y"]}" }
