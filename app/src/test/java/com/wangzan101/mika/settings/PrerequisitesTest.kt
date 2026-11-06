package com.wangzan101.mika.settings

import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerequisitesTest {
    private val ready = SystemStatus(
        sdkInt = 34,
        builtinShell = BuiltinShellStatus.Running,
        accessibility = AccessibilityState.On,
        notificationsAllowed = true,
    )

    @Test
    fun allReadyCanStart() {
        val c = Prerequisites.check(ready, hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.problems.isEmpty())
    }

    @Test
    fun android11CanStartWithoutPhoneControl() {
        val c = Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.blockers.isEmpty())
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.SystemTooOld), c.problems)
    }

    @Test
    fun oldAndroidDoesNotAskToSetUpUnsupportedPhoneFeatures() {
        val c = Prerequisites.check(
            ready.copy(sdkInt = 30, builtinShell = BuiltinShellStatus.NotPaired, accessibility = AccessibilityState.Off),
            hasMainModel = true,
        )
        assertTrue(c.canStartTask)
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.SystemTooOld), c.problems)
    }

    @Test
    fun oldAndroidStillNeedsAMainModel() {
        val c = Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = false)
        assertFalse(c.canStartTask)
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun noMainModelBlocksStart() {
        val c = Prerequisites.check(ready, hasMainModel = false)
        assertFalse(c.canStartTask)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun accessibilityOnlyNeededForPhoneApps() {
        for (a in listOf(AccessibilityState.Off, AccessibilityState.EnabledNotRunning)) {
            val c = Prerequisites.check(ready.copy(accessibility = a), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.Accessibility(a)), c.problems)
        }
    }

    @Test
    fun restrictedSettingsStateReachesTheAccessibilityProblem() {
        val c = Prerequisites.check(
            ready.copy(accessibility = AccessibilityState.EnabledNotRunning, restrictedSettings = true),
            hasMainModel = true,
        )
        assertEquals(
            listOf(Problem.Accessibility(AccessibilityState.EnabledNotRunning, restrictedSettings = true)),
            c.problems,
        )
    }

    @Test
    fun notificationsOffOnlyWarns() {
        val c = Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertEquals(listOf(Problem.NotificationsOff), c.problems)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun android12And13CanControlAppsWithAFocusWarning() {
        for (sdk in 31..33) {
            val c = Prerequisites.check(ready.copy(sdkInt = sdk), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertTrue(c.phoneReady)
            assertEquals(listOf(Problem.FocusMayLeak), c.problems)
        }
        assertTrue(Prerequisites.check(ready.copy(sdkInt = 34), hasMainModel = true).problems.isEmpty())
        assertEquals(listOf(Problem.SystemTooOld), Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = true).problems)
    }

    @Test
    fun android12StillNeedsHelperAndAccessibilityForPhoneControl() {
        for (sdk in 31..32) {
            val c = Prerequisites.check(
                ready.copy(sdkInt = sdk, builtinShell = BuiltinShellStatus.NotPaired, accessibility = AccessibilityState.Off),
                hasMainModel = true,
            )
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off), Problem.FocusMayLeak), c.problems)
        }
    }

    @Test
    fun problemsOrderedBySeverity() {
        val c = Prerequisites.check(
            SystemStatus(33, BuiltinShellStatus.NotPaired, AccessibilityState.Off, false), hasMainModel = false,
        )
        assertEquals(
            listOf(
                Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off),
                Problem.NoModel, Problem.NotificationsOff, Problem.FocusMayLeak,
            ),
            c.problems,
        )
    }

    // 修復 1 M4: ユーザーまたはシステムによって拒否された場合、「必要です」リマインダーが表示されますが、タスクはブロックされません。
    @Test
    fun attentionLoweredIsAWarning() {
        val c = Prerequisites.check(ready.copy(attentionLowered = true), hasMainModel = true)
        assertEquals(listOf<Problem>(Problem.AttentionLowered(attention = true, result = false)), c.problems)
        assertTrue(c.canStartTask)
    }

    // 「Need Your Handling」と「Task Result」の 2 つのチャンネルをさまざまに組み合わせて 1 か所で判断し、どちらが断られたかを提示
    @Test
    fun loweredChannelCombinations() {
        fun problems(attention: Boolean, result: Boolean) =
            Prerequisites.check(ready.copy(attentionLowered = attention, resultLowered = result), hasMainModel = true).problems
        assertEquals(emptyList<Problem>(), problems(false, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, false)), problems(true, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(false, true)), problems(false, true))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, true)), problems(true, true))
        assertTrue(Prerequisites.check(ready.copy(resultLowered = true), hasMainModel = true).canStartTask)
    }

    // メイン スイッチがオフの場合、「通知は許可されていません」と報告されるだけで、チャネルに繰り返しプロンプトが表示されません。
    @Test
    fun masterSwitchOffHidesChannelProblems() {
        val c = Prerequisites.check(
            ready.copy(notificationsAllowed = false, attentionLowered = true, resultLowered = true), hasMainModel = true,
        )
        assertEquals(listOf<Problem>(Problem.NotificationsOff), c.problems)
    }

    // Web ページとサンドボックス タスクには組み込みアシスタントは必要ありません。これらは引き続き開始でき、携帯電話を使用している場合にのみ、phone_unavailable を報告します。問題は引き続きリストされ、プロンプトが表示されます。
    @Test
    fun phoneHelperOnlyNeededForPhoneApps() {
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode),
        ).forEach { s ->
            val c = Prerequisites.check(ready.copy(builtinShell = s), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf<Problem>(Problem.PhoneHelper(s)), c.problems)
        }
        // Android 12 以下では、システムが古すぎるとのみ報告されます
        assertEquals(
            listOf<Problem>(Problem.SystemTooOld),
            Prerequisites.check(ready.copy(sdkInt = 30, builtinShell = BuiltinShellStatus.NotPaired), hasMainModel = true).problems,
        )
    }
}
