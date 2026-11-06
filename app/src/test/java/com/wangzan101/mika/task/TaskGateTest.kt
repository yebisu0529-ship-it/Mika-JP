package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.shell.PaymentPauseRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGateTest {
    @get:Rule val texts = ChineseTexts()

    private val locked = MutableStateFlow(false)
    private val shellUp = MutableStateFlow(true)
    private var moveBackCalls = 0
    private var moveBackResult = true
    private val gate = TaskGate(locked, shellUp) { moveBackCalls++; moveBackResult }
    private val reported = mutableListOf<TaskState?>()

    @Test fun paymentPauseCannotBeBypassedByTaskContinueOrScreenUnlock() = runTest {
        val paused = MutableStateFlow<PaymentPauseRecord?>(PaymentPauseRecord(true))
        val paymentGate = TaskGate(locked, shellUp, paused) { true }
        var passed = false
        launch { paymentGate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals("payment_paused", (reported.last() as TaskState.Held).reason.code)
        paymentGate.resume()
        locked.value = true
        locked.value = false
        runCurrent()
        assertFalse(passed)
        paused.value = null
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun passesImmediatelyWhenNothingBlocks() = runTest {
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test fun paymentPauseAlsoBlocksTheUnattendedUnlockAddedUpstream() = runTest {
        val paused = MutableStateFlow<PaymentPauseRecord?>(PaymentPauseRecord(true))
        var unlocks = 0
        var passed = false
        var systemLocked = true
        val paymentGate = TaskGate(locked, shellUp, paused,
            refreshLocked = { locked.value = systemLocked },
            unattendedUnlock = { unlocks++; systemLocked = false; locked.value = false }) { true }
        launch { paymentGate.pass { reported += it }; passed = true }
        runCurrent()
        paymentGate.resume()
        runCurrent()
        assertFalse(passed)
        assertEquals(0, unlocks)
        assertEquals("payment_paused", (reported.last() as TaskState.Held).reason.code)
        // 明示的な再開の後にだけ、ロック解除の既存処理へ進める。
        paused.value = null
        runCurrent()
        assertTrue(passed)
        assertEquals(1, unlocks)
    }

    @Test
    fun nonInteractiveWaitsForUnlockThenResumesByItself() = runTest {
        locked.value = LockState.Reading(interactive = false, keyguardLocked = false).locked
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        locked.value = LockState.Reading(interactive = true, keyguardLocked = true).locked // 画面はオンですが、ロック画面のままです。
        runCurrent()
        assertFalse(passed)
        locked.value = LockState.Reading(interactive = true, keyguardLocked = false).locked
        runCurrent()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    @Test
    fun gateRefreshesSystemLockStateBeforeAllowingWrite() = runTest {
        var systemLocked = true
        var unlocks = 0
        val refreshed = TaskGate(locked, shellUp,
            refreshLocked = { locked.value = systemLocked }, // 熄屏已发生，广播对应的状态流还没更新
            unattendedUnlock = {
                unlocks++
                systemLocked = false
                locked.value = false
            }) { true }

        refreshed.pass { reported += it }

        assertEquals(1, unlocks)
        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun unattendedTaskUnlocksThenContinues() = runTest {
        locked.value = true
        var unlocks = 0
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = {
            unlocks++
            locked.value = false
        }) { true }

        unattended.pass { reported += it }

        assertEquals(1, unlocks)
        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun unattendedTaskFailsWhenUnlockIsUnavailable() = runTest {
        locked.value = true
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = { }) { true }

        val result = withTimeoutOrNull(1_000) { runCatching { unattended.pass { reported += it } } }

        assertTrue(result?.exceptionOrNull() is UnattendedLockException)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
    }

    @Test
    fun unattendedTaskTrustsRefreshedSystemStateOverAttemptOutcome() = runTest {
        locked.value = true
        // 流程因权限等原因返回时，用户可能刚好已经手动解锁；实际系统状态才是依据。
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = { locked.value = false }) { true }

        unattended.pass { reported += it }

        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun movedAwayPausesUntilUserContinuesThenMovesBack() = runTest {
        gate.resume() // 一時停止前の「続行」はカウントされません
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), reported.last())
        assertEquals(0, moveBackCalls)

        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)

        // は戻った後に一時停止しなくなりました
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    /** ユーザーは、貸し出されたページでアクションを実行します。どのような画面であっても、停止し、「続行」をクリックしてその場で解放します。前の「続行」はカウントされず、何も移動されません。 */
    @Test
    fun userOnPagePausesAnyStepUntilContinue() = runTest {
        gate.onUserOnPage()
        assertTrue(gate.userOnPagePending)
        var passed = false
        launch { gate.pass({ false }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE)), reported.last())
        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertFalse(gate.userOnPagePending)
        assertEquals(0, moveBackCalls)
        // レベルに到達する前に、「続行: 停止なし」をクリックしました。
        gate.onUserOnPage()
        gate.resume()
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun failedMoveBackStaysPaused() = runTest {
        moveBackResult = false
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        gate.resume()
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVE_BACK_FAILED)), reported.last())

        moveBackResult = true
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun helperLossPausesUntilBackAndUserContinues() = runTest {
        shellUp.value = false
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())

        gate.resume() // アシスタントはまだ復元されていません: 継続は無効です
        runCurrent()
        assertFalse(passed)

        shellUp.value = true
        runCurrent()
        assertFalse(passed) // 回復後、ユーザーが [続行] をクリックするまで待つ必要があります。
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun movedAwayMeansOnMainAndNotOnAgentDisplay() {
        assertTrue(DeviceStateWatcher.isMovedAway(agentTop = 5, mainTop = 7, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = 7, mainTop = 3, taskId = 7))
        // エージェントはアプリを自動的に閉じました。アプリはどの画面の上部にもないため、削除されたものとしてカウントされません。
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = 3, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = -1, taskId = -1))
    }

    @Test
    fun continueTappedRightAsPauseIsShownIsNotLost() = runTest {
        gate.onTaskMovedAway()
        var passed = false
        // 通知に「一時停止」と表示されるとすぐに、ユーザーは続行するためにクリックします。クリックは setWaiting コールバック中に発生します。
        launch {
            gate.pass { reported += it; if (it is TaskState.Held && moveBackCalls == 0) gate.resume() }
            passed = true
        }
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)
    }

    @Test
    fun exposesPendingMoveForImmediateNotice() {
        assertFalse(gate.movedAwayPending)
        gate.onTaskMovedAway()
        assertTrue(gate.movedAwayPending)
    }

    @Test
    fun continueTappedAfterMoveDetectedButBeforeGateCounts() = runTest {
        // 移動されたことが検出されると、通知にはすぐに一時停止が表示されます。エージェントがゲートに到着する前に、ユーザーは [続行] をクリックします。
        gate.onTaskMovedAway()
        gate.resume()
        gate.pass { reported += it }
        assertEquals(1, moveBackCalls)
        assertEquals(null, reported.last())
    }

    /** Web ページとサンドボックスの手順には組み込みアシスタントは必要なく、ホーム画面には移動されません。ロック画面のみが表示されます。 */
    @Test
    fun shellAndMovedAwayOnlyCheckedForPhoneSteps() = runTest {
        shellUp.value = false
        gate.onTaskMovedAway()
        gate.pass({ false }) { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
        var passed = false
        launch { gate.pass({ true }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())
        // 画面をロックしても Web ページをブロックする手順
        reported.clear()
        locked.value = true
        var webPassed = false
        launch { gate.pass({ false }) { reported += it }; webPassed = true }
        runCurrent()
        assertFalse(webPassed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(webPassed)
        coroutineContext.cancelChildren()
    }
}
