package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRestoreTest {
    private val mika = PermissionCommands.accessibilityService("com.wangzan101.mika")
    private val other = "com.example.reader/.ReaderService"
    private val yes = RestoreWish(revive = true, accessibility = true)

    @Test
    fun wishFollowsCapsuleModeAndTasks() {
        assertTrue(RestoreWish.of(capsuleAlways = true, taskRunning = false, accessibilityWanted = true, accessibilityOn = true).revive)
        assertTrue(RestoreWish.of(capsuleAlways = false, taskRunning = true, accessibilityWanted = true, accessibilityOn = true).revive)
        // カプセルはタスク中にのみ表示され、タスクはありません。ユーザーが強制的に停止すると停止します。
        assertEquals(RestoreWish.NONE, RestoreWish.of(capsuleAlways = false, taskRunning = false, accessibilityWanted = true, accessibilityOn = true))
    }

    @Test
    fun accessibilityOnlyWhenWantedAndOn() {
        assertTrue(RestoreWish.of(true, false, accessibilityWanted = true, accessibilityOn = true).accessibility)
        // ユーザーがミカのアクセシビリティをオフにし、私に代わってアクセシビリティをオンにしました
        assertFalse(RestoreWish.of(true, false, accessibilityWanted = false, accessibilityOn = true).accessibility)
        // ユーザーは、ミカが生きている間にシステム設定でミカをオフにしました。
        assertFalse(RestoreWish.of(true, false, accessibilityWanted = true, accessibilityOn = false).accessibility)
    }

    @Test
    fun flagsRoundTrip() {
        for (w in listOf(RestoreWish.NONE, yes, RestoreWish(true, false), RestoreWish(false, true))) {
            assertEquals(w, RestoreWish.fromFlags(w.flags))
        }
        assertEquals(0, RestoreWish.NONE.flags)
        assertEquals(RestoreWish.REVIVE or RestoreWish.ACCESSIBILITY, yes.flags)
    }

    @Test
    fun senderSendsOnNewShellOrChangedWish() {
        val sent = mutableListOf<Pair<String, RestoreWish>>()
        val sender = RestoreWishSender<String> { s, w -> sent += s to w; true }
        val a = "helper-a"
        sender.update(null, yes)
        assertTrue("ヘルパーがなければ引き渡しません", sent.isEmpty())
        sender.update(a, yes)
        sender.update(a, yes)
        assertEquals(listOf(a to yes), sent)
        sender.update(a, RestoreWish.NONE)
        assertEquals(a to RestoreWish.NONE, sent.last())
        // アシスタントが新しいものに変更されました (新しいバインダー): ウィッシュが変更されていない場合でも、再度送信してください
        val b = String(charArrayOf('h'))
        sender.update(b, RestoreWish.NONE)
        assertEquals(3, sent.size)
        // アシスタントが消えて戻ってきました: もう一度送信してください
        sender.update(null, RestoreWish.NONE)
        sender.update(b, RestoreWish.NONE)
        assertEquals(4, sent.size)
    }

    @Test
    fun senderRetriesAfterFailure() {
        var ok = false
        var calls = 0
        val sender = RestoreWishSender<String> { _, _ -> calls++; ok }
        sender.update("h", yes)
        ok = true
        sender.update("h", yes)
        sender.update("h", yes)
        assertEquals("失敗した引き渡しは次回に再試行し、成功した引き渡しは繰り返しません", 2, calls)
    }

    @Test
    fun policyRestoresOnlyWhenWantedAndAppNotBack() {
        val p = HelperRestorePolicy()
        assertEquals(HelperRestorePolicy.Decision.NotWanted, p.decide(RestoreWish.NONE, appBack = false, now = 1_000))
        assertEquals(HelperRestorePolicy.Decision.AppBack, p.decide(yes, appBack = true, now = 1_000))
        assertTrue("復帰を一度も試していません", p.report(1_000).empty)
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, appBack = false, now = 2_000))
        assertEquals(HelperRestoreReport(2_000, 1, 0), p.report(2_000))
    }

    @Test
    fun forceStopPreventsRestoreUntilTheUserOpensTheApp() {
        val p = HelperRestorePolicy()
        assertEquals(HelperRestorePolicy.Decision.ForceStopped, p.decide(yes, false, 1_000, forceStopped = true))
        assertTrue(p.report(1_000).empty)
        assertEquals(HelperRestorePolicy.Decision.AppBack, p.decide(yes, true, 2_000, forceStopped = true))
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, 3_000, forceStopped = false))
    }

    @Test
    fun policyBacksOffWithinWindowThenAllowsAgain() {
        val p = HelperRestorePolicy(maxAttempts = 3, windowMs = 600_000)
        repeat(3) { assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 10_000L + it * 30_000)) }
        // がプルアップしてすぐに停止します: ウィンドウは 4 回目にプルアップされません
        assertEquals(HelperRestorePolicy.Decision.BackedOff, p.decide(yes, false, now = 100_000))
        assertEquals(HelperRestoreReport(70_000, 3, 100_000), p.report(100_000))
        // は、初めて窓からスライドさせた後、再度引き出すことができます。
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 610_000))
        assertEquals(3, p.report(610_000).recent)
        assertEquals(0, p.report(10 * 600_000L).recent)
    }

    @Test
    fun policyIgnoresClockGoingBack() {
        val p = HelperRestorePolicy(maxAttempts = 1, windowMs = 600_000)
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 500_000))
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 1_000))
    }

    @Test
    fun accessibilityCommandsAppendWithoutDuplicates() {
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$other:$mika'", "settings put secure accessibility_enabled 1"),
            RestoreCommands.accessibility(other, mika),
        )
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$mika'", "settings put secure accessibility_enabled 1"),
            RestoreCommands.accessibility("null", mika),
        )
        // はすでにリストにあります (カウントの書き込み方法は問わない): リストを書き換えず、メイン スイッチをオンにするだけです。
        assertEquals(listOf(RestoreCommands.ENABLE_ACCESSIBILITY), RestoreCommands.accessibility("$mika:$other", mika))
        assertEquals(
            listOf(RestoreCommands.ENABLE_ACCESSIBILITY),
            RestoreCommands.accessibility("com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService", mika),
        )
    }

    @Test
    fun startCheckClassifiesLoss() {
        val removed = A11yObservation(enabled = false, connected = false)
        val unbound = A11yObservation(enabled = true, connected = false)
        val fine = A11yObservation(enabled = true, connected = true)
        assertEquals(A11yLoss.Removed, A11yStartCheck.loss(removed, connectedSoon = false, expected = true))
        // は立ち上がるときに削除され、その後修復されました: まだ一度削除されたことが記録されています
        assertEquals(A11yLoss.Removed, A11yStartCheck.loss(removed, connectedSoon = true, expected = true))
        assertEquals(A11yLoss.Unbound, A11yStartCheck.loss(unbound, connectedSoon = false, expected = true))
        assertNull("数秒以内にシステムが自動で再接続しています", A11yStartCheck.loss(unbound, connectedSoon = true, expected = true))
        assertNull(A11yStartCheck.loss(fine, connectedSoon = true, expected = true))
        assertNull("未設定で一度も接続していない場合は対象外です", A11yStartCheck.loss(removed, connectedSoon = false, expected = false))
    }
}
