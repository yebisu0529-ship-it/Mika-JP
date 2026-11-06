package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.HINT_PROTECTED
import com.wangzan101.mika.core.tool.HINT_DISPLAY_CAPTURE
import com.wangzan101.mika.core.tool.HINT_UNSUPPORTED_BROWSER
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.ok
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.session

/** 診断は空白、読み込み中、ロックされた画面または保護された画面です。ロック画面レベルを通過する必要があります (ロック画面診断では何も見つかりません)。 */
internal class Diagnose(f: ScreenFamily) : ScreenTool(f, "diagnose") {
    override val verb = Verb.Diagnose
    override val label = "画面を確認"
    override val risk = Risk.Write
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val place = family.placeKey
        ctx.screen.checkScreen()?.let { c ->
            return ok {
                put(place, c.currentApp)
                put("state", c.state.wire)
                put("usable", c.state == ScreenState.NORMAL)
                put("needsHuman", c.needsHuman)
                c.takeoverReason?.let { put("takeoverReason", it) }
                c.cause?.let { put("cause", it) }
                when (c.state) {
                    ScreenState.NORMAL -> if (c.takeoverReason == ScreenDiagnosis.UNSUPPORTED_BROWSER) put("hint", HINT_UNSUPPORTED_BROWSER)
                    ScreenState.BLANK -> put("hint", when {
                        c.currentApp == null && c.cause == ScreenDiagnosis.NO_WINDOW -> Feedback[family.emptyHint]
                        // フロントエンド アプリは存在しますが、ウィンドウを読み取ることができません。開いていないわけではないので、再度起動しないでください。
                        c.currentApp != null && c.cause == ScreenDiagnosis.UNREADABLE_WINDOW -> Feedback.format("unreadable_window", "app" to c.currentApp)
                        else -> Feedback["screen.blank"]
                    })
                    ScreenState.LOADING -> put("hint", Feedback["screen.loading"])
                    ScreenState.PROTECTED -> put("hint", HINT_PROTECTED)
                    ScreenState.CAPTURE_UNAVAILABLE -> put("hint", HINT_DISPLAY_CAPTURE)
                    // にはアクセスが制限されています。ユーザーに引き渡すだけでなく、すぐに再試行しないでください。
                    ScreenState.NEEDS_HUMAN -> put("hint", if (c.takeoverReason == "access_blocked") Feedback["barrier.access_blocked"]
                        else Feedback.format("screen.needs_human", "reason" to c.takeoverReason))
                }
            }
        }
        val s = ctx.session.snapshot()
        val usable = s.elements.any { it.meaningful }
        return ok {
            put(place, s.packageName ?: s.unreadableApp)
            put("usable", usable)
            put("needsHuman", false)
            if (!usable) put("hint", s.unreadableApp?.let { Feedback.format("unreadable_window", "app" to it) } ?: Feedback["screen.no_elements"])
        }
    }
}
