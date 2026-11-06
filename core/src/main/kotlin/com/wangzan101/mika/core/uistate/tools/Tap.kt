package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
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
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.toPx
import com.wangzan101.mika.core.uistate.DESCRIBED_ELEMENT
import com.wangzan101.mika.core.uistate.DESCRIBED_ROLE
import com.wangzan101.mika.core.vision.LOCATE_SCREEN_CHANGE
import com.wangzan101.mika.core.vision.hamming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.described
import com.wangzan101.mika.core.uistate.lastOrSnapshot
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.uistate.located
import com.wangzan101.mika.core.uistate.locatedInfo
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.session
import com.wangzan101.mika.core.uistate.withPortRef

internal class Tap(f: ScreenFamily) : ScreenTool(f, "tap") {
    override val verb = Verb.Tap
    override val batchable = true
    override val routable = f.routable
    override val label = "タップ"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Tap
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "ref" to str(REF), "snapshotId" to str(SNAP),
            "x" to int(COORD, 0, 999), "y" to int(COORD, 0, 999),
            "target" to str("Short label of what is being tapped (coordinate taps)."),
            DESCRIBED_ELEMENT to str(DESCRIBE_TAP), DESCRIBED_ROLE to str(DESCRIBE_ROLE),
            "clicks" to int("1 taps once (default), 2 double-taps.", 1, 2),
        )) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref(located = true)
        val x = a.coord("x"); val y = a.coord("y")
        val clicks = a.int("clicks", 1, 2) ?: 1
        val target = a.str("target", max = 80)
        // 実行エントリの説明が数字に変更されました。ここに到達しても、実行エントリや推測が存在しないことを示す記述がまだあります。
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an element here; use ref+snapshotId")
        if (ref == null && ctx.described && target == null) {
            throw ArgError("target", "required for a coordinate tap after ${family.tool("describe")}: say what you are tapping")
        }
        if (ref != null) {
            if (x != null || y != null) throw ArgError("x", "give either ref+snapshotId or x+y, not both")
        } else {
            if (x == null) throw ArgError("x", "required with y when no ref is given")
            if (y == null) throw ArgError("y", "required with x when no ref is given")
        }
        val twice = clicks == 2
        if (ref != null) {
            if (LOCATED_REF.matches(ref.first)) return tapLocated(ref.first, ref.second, twice, ctx)
            return ctx.withPortRef(ref) { r ->
                ctx.action(if (twice) ctx.screen.doubleTapRef(r!!.first, r.second) else ctx.screen.tapRef(r!!.first, r.second))
            }
        }
        val s = ctx.lastOrSnapshot()
        return ctx.action(tapAt(ctx, toPx(x!!, s.width), toPx(y!!, s.height), twice))
    }

    private suspend fun tapAt(ctx: ToolContext, x: Int, y: Int, twice: Boolean) =
        if (twice) ctx.screen.doubleTapXY(x, y) else ctx.screen.tapXY(x, y)

    /** ポイント find_visually found ポイント: まず、もう一度スクリーンショットを撮ります。写真が見つかったものとあまりにも異なる場合 (または撮影できない場合)、それをクリックします。 */
    private suspend fun tapLocated(ref: String, snapshotId: String, twice: Boolean, ctx: ToolContext): ToolExecution {
        val (x, y) = locatedPixel(ctx, ref, snapshotId) ?: return refExpired(Feedback["located_stale"], "located_stale")
        val info = ctx.locatedInfo[ref]
        val target = info?.target
        val r = ctx.action(tapAt(ctx, x, y, twice))
        if (target == null || r.content["ok"] != JsonPrimitive(true)) return r
        // 裏付ける証拠はありません。クリックした場合にのみ効果を確認できます。プロンプトでわかりやすく説明してください。
        val hint = listOfNotNull(Feedback["located.unverified"].takeIf { info.unverified }, (r.content["hint"] as? JsonPrimitive)?.content)
        val extra = mapOf("located" to JsonPrimitive(target)) +
            (if (info.unverified) mapOf("unverifiedPoint" to JsonPrimitive(true)) else emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap())
        return ToolExecution(JsonObject(r.content + extra), changed = r.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String =
        refTarget(o, r, ctx.lastSnapshot) ?: o.argStr("target") ?: "${o["x"]},${o["y"]}"
}

/**
 * find_visually 現時点での見つかったポイントのピクセル位置 ([ref] は @vN): まずスクリーンショットを撮り直します。発見時の画像と違いすぎる（またはキャプチャできない）場合はnullとなるので、そこをクリックしないでください。
 * は、現在の画面に応じてピクセルを変換します (読み取れない場合は最新の画面に戻ります)。
 */
internal suspend fun locatedPixel(ctx: ToolContext, ref: String, snapshotId: String): Pair<Int, Int>? {
    val (p, hash) = ctx.located.get(ref, snapshotId) ?: return null
    val screen = ctx.screen
    val png = if (screen.isProtected()) null else screen.screenshotPng()
    if (png == null || hamming(ctx.config.vision!!.ops.aHash(png), hash) > LOCATE_SCREEN_CHANGE) return null
    val s = ctx.session.peek() ?: ctx.lastOrSnapshot()
    return toPx(p.x, s.width) to toPx(p.y, s.height)
}

/** 番号で操作する場合、アクションリストにオブジェクトを書き込みます。画像を見て、見つかった点で探しているものを書き込み、他の要素の名前は番号を書かずに書き込みます。 */
internal fun refTarget(o: JsonObject, r: ToolExecution, last: ScreenSnapshot?): String? = o.argStr("ref")?.let { ref ->
    (r.content["located"] as? JsonPrimitive)?.content
        ?: last?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref
}

internal const val DESCRIBE_TAP = "Instead of ref+snapshotId: the element's visible text or accessible name, as shown on the screen. It must match exactly one element on the screen right now."
internal const val DESCRIBE_ROLE = "Optional with element: the element's role as shown in the element list (e.g. button, link, textbox), to tell same-named elements apart."
