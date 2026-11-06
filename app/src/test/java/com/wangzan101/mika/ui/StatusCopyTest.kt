package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Problem
import org.junit.Test

class StatusCopyTest {
    @get:Rule val texts = ChineseTexts()

    // チャンネル設定ページが開けない場合は、最初に開けるページに戻ります。
    @Test
    fun firstThatStartsFallsBack() {
        val tried = mutableListOf<String>()
        assertTrue(firstThatStarts(listOf("channel", "app")) { tried += it; it == "app" })
        assertEquals(listOf("channel", "app"), tried)
        tried.clear()
        assertTrue(firstThatStarts(listOf("channel", "app")) { tried += it; true })
        assertEquals(listOf("channel"), tried)
        assertFalse(firstThatStarts(listOf("channel", "app")) { false })
    }

    @Test
    fun unavailableAccessibilityDistinguishesEnabledFromOff() {
        assertEquals("Mika 的无障碍服务没有开启:请在系统设置的无障碍里打开 Mika。", StatusCopy.accessibilityUnavailable(enabled = false))
        val enabled = StatusCopy.accessibilityUnavailable(enabled = true)
        assertTrue(enabled.contains("已开启"))
        assertTrue(enabled.contains("关闭后重新开启"))
        assertTrue(enabled.contains("允许受限制的设置"))
        assertFalse(enabled.contains("没有开启"))
    }

    @Test
    fun restrictedAccessibilityPointsToAppInfoInsteadOfOnlyToggling() {
        val p = Problem.Accessibility(AccessibilityState.EnabledNotRunning, restrictedSettings = true)
        assertTrue(StatusCopy.banner(p).detail.contains("允许受限制的设置"))
        val e = StatusCopy.explain(p, "14")
        assertEquals(Fix.OpenAppInfo, e.fix)
        assertTrue(e.body.contains("应用信息"))
        assertTrue(e.body.contains("允许受限制的设置"))
    }

    @Test
    fun lockedAccessibilityKeepsTheUnlockGuidanceEvenWhenRestricted() {
        val p = Problem.Accessibility(
            AccessibilityState.EnabledNotRunning,
            userLocked = true,
            restrictedSettings = true,
        )
        assertTrue(StatusCopy.banner(p).detail.contains("解锁一次"))
        val e = StatusCopy.explain(p, "14")
        assertNull(e.fix)
        assertNull(e.action)
        assertTrue(e.body.contains("解锁一次"))
    }

    @Test
    fun paymentLinesOnePerField() {
        assertEquals("商品: 拿铁 大杯\n金額: ¥18\n住所: 公司前台", StatusCopy.paymentLines("拿铁 大杯", "¥18", "公司前台"))
        assertEquals("金額: ¥18", StatusCopy.paymentLines(null, "¥18", " "))
        assertNull(StatusCopy.paymentLines(null, null, null))
    }

    // 記憶したルートに従い、一度にいくつかのステップを実行するときに何を行っているかを書き留めます。
    @Test
    fun replayAndBatchCopy() {
        assertEquals("既知の手順をたどっています(ステップ 2/5)", StatusCopy.progress(RunProgress.Replay(2, 5)))
        assertEquals("3 ステップを一度に実行中", StatusCopy.progress(RunProgress.Batch(3)))

        val ex = Exchange("查电量", emptyList(), ExchangeStatus.Working, live = true)
        val replaying = ChatViewModel.reduce(ex, TaskState.Running(0, "按以往的做法操作", progress = RunProgress.Replay(2, 5)))
        assertEquals("既知の手順をたどっています(ステップ 2/5)", liveLine(replaying))
        val batching = ChatViewModel.reduce(replaying, TaskState.Running(1, "一起做", "do_steps", RunProgress.Batch(3)))
        assertEquals("3 ステップを一度に実行中", liveLine(batching))
        // ステップ リストには、実際に実行された内容のみがリストされます。
        assertEquals(emptyList<StepRow>(), batching.steps)
    }

    @Test
    fun headsUpCopyForEachCombination() {
        val both = Problem.AttentionLowered(attention = true, result = true)
        val b = StatusCopy.banner(both)
        assertEquals("「あなたの対応が必要」の通知はポップアップ表示されません", b.title)
        assertEquals(
            "操作を引き継ぐときや許可するとき、これらの通知は今は通知シェードに静かに届くので、見逃しやすくなっています。「タスクの結果」も同様です。「オンにする」をタップして、「ポップアップ表示」をオンにしてください。\n" +
                "設定 › アプリ › Mika JP › 通知 › あなたの対応が必要 › ポップアップ表示(機種によっては「バナー」や「フローティング通知」と呼ばれます)",
            b.detail,
        )
        assertEquals("オンにする", b.link)
        assertEquals(Fix.OpenAttentionChannel, StatusCopy.directFix(both))
        assertEquals(Fix.OpenAttentionChannel, StatusCopy.explain(both, "14").fix)

        val attentionOnly = StatusCopy.banner(Problem.AttentionLowered(true, false))
        assertFalse(attentionOnly.detail.contains("も同様です"))

        val resultOnly = Problem.AttentionLowered(attention = false, result = true)
        assertEquals("「タスクの結果」の通知はポップアップ表示されません", StatusCopy.banner(resultOnly).title)
        assertTrue(StatusCopy.banner(resultOnly).detail.contains("通知 › タスクの結果 › ポップアップ表示"))
        assertEquals(Fix.OpenResultChannel, StatusCopy.directFix(resultOnly))
        val e = StatusCopy.explain(resultOnly, "14")
        assertEquals("オンにする", e.action)
        assertEquals(Fix.OpenResultChannel, e.fix)
    }
}
