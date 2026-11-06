package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.HINT_PROTECTED
import com.wangzan101.mika.core.tool.HINT_DISPLAY_CAPTURE
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.visual.Recall
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.uistate.screenText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.session

/** この画面を読んでください: 要素テーブル (構成に従ってスクリーンショットを観察してください)、個々のスクリーンショット。 */
internal class Observe(f: ScreenFamily) : ScreenTool(f, "observe") {
    override val verb = Verb.Observe
    override val label = "画面を見る"
    override val risk = Risk.Read
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.session.observe(
        withImage = ctx.config.visionEnabled && !ctx.config.adaptiveVision,
        adaptiveImage = ctx.config.visionEnabled && ctx.config.adaptiveVision,
    )
}

internal class ListElements(f: ScreenFamily) : ScreenTool(f, "list_elements") {
    override val verb = Verb.ListElements
    override val label = "画面要素を読み取る"
    override val risk = Risk.Read
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.session.observe(withImage = false, recordFrame = false)
}

/** のメインモデルは画像が確認できる場合のみ提供されます。 */
internal class Screenshot(f: ScreenFamily) : ScreenTool(f, "screenshot") {
    override val verb = Verb.Screenshot
    override val label = "スクリーンショット"
    override val risk = Risk.Read
    override val def by lazy { ToolDef(name, describe()) }

    override fun usable(ctx: ToolContext) = ctx.config.visionEnabled

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val screen = ctx.screen
        if (screen.isProtected()) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "protected_content"); put("protected", true)
            put("message", HINT_PROTECTED); put("recoverable", true)
        })
        val png = screen.screenshotPng()
        if (png == null) {
            val captureError = screen.peek().captureError
            if (captureError == ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE) {
                return fail(captureError, HINT_DISPLAY_CAPTURE, recoverable = false)
            }
            return fail("screenshot_unavailable", Feedback["screenshot_unavailable"])
        }
        val s = ctx.lastSnapshot
        val shots = ctx.shots
        // 画面読み上げと同じ方法でレビューを登録します。古い画像が切り取られた後は、screenshotId を押して取得できます。支払いページの写真は保持されません。
        val shotId = shots.next()
        shots.remember(Recall(shotId, ctx.currentStep, s?.packageName, png, s?.let(::screenText).orEmpty()))
        return ToolExecution(okObj { s?.let { put("width", it.width); put("height", it.height) }; put("screenshotId", shotId) }, image = png, imageIds = listOf(shotId))
    }
}

