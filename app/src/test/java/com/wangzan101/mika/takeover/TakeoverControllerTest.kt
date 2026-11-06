package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.task.restorePipSound
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverControllerTest {
    /** 仮想画面は画面番号 9 で、メイン画面は画面番号 0 です。タスク 1 は、phone_launch_app によって開かれたアプリであり、タスク 2 は、それによって開かれたログイン ページです。 */
    internal class FakePort : TakeoverPort {
        val log = mutableListOf<String>()
        val displays = mutableMapOf(1 to AGENT)
        val packages = mutableMapOf(1 to "a.app")
        var target = 1
        var moveToMainOk = true
        var moveBackOk = true
        override suspend fun agentTopTask() = displays.filterValues { it == AGENT }.keys.maxOrNull() ?: -1
        override suspend fun targetTask() = target
        override fun targetPackage() = if (target >= 0) "a.app" else null
        override suspend fun packageOf(taskId: Int) = packages[taskId]
        override suspend fun displayOf(taskId: Int) = displays[taskId] ?: -1
        override suspend fun moveToMain(taskId: Int): Boolean {
            log += "toMain:$taskId"
            if (moveToMainOk) displays[taskId] = 0
            return moveToMainOk
        }
        override suspend fun moveBack(taskId: Int): Boolean {
            log += "back:$taskId"
            if (moveBackOk) displays[taskId] = AGENT
            return moveBackOk
        }
        override fun setSound(packageName: String, on: Boolean) { log += (if (on) "unmute:" else "mute:") + packageName }
        override fun unmute(packageName: String) { log += "pip-unmute:$packageName" }
        val results = mutableListOf<HandBack>()
        var watching: String? = null
        var activity = HandoverActivity(0, null)
        override fun watchInput(packageName: String) { watching = packageName }
        override suspend fun handoverActivity() = activity.takeIf { watching != null }
        override fun stopWatching() { watching = null }
        override suspend fun resume(result: HandBack) {
            results += result
            log += "resume:${result.outcome}" + if (result.userFinished) "/finished" else ""
        }
        override fun cancel() { log += "cancel" }
    }

    internal companion object { const val AGENT = 9 }

    private val port = FakePort()
    private lateinit var takeover: Takeover

    /** 各ユースケースは、独自のテスト スケジューラ上にテイクオーバーを構築し、タイミングに仮想時間を使用します。 */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        takeover = Takeover(port, backgroundScope) { testScheduler.currentTime }
        body()
    }

    private val waiting = TaskState.WaitingTakeover("captcha", "请拖动滑块")

    @Test
    fun handOverMovesToMainAndRestoresSound() = test {
        takeover.onState(waiting)
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertTrue(takeover.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertTrue(takeover.handedOver)
    }

    @Test
    fun handOverMovesTheTopTaskAndItsPackage() = test {
        // ログイン ページは、新しいタスクの別のアプリによって開かれ、それをカバーします。
        port.displays[2] = AGENT
        port.packages[2] = "login.app"
        takeover.onState(waiting)
        takeover.handOver()
        assertEquals(listOf("toMain:2", "unmute:login.app"), port.log)
        port.log.clear()
        takeover.handBack()
        assertEquals(listOf("back:2", "mute:login.app", "mute:a.app", "resume:complete"), port.log)
    }

    @Test
    fun handBackMovesBackRemutesThenResumes() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete"), port.log)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertFalse(takeover.handedOver)
    }

    @Test
    fun moveBackOfVanishedTaskStillResumes() = test {
        port.displays[2] = AGENT
        port.packages[2] = "a.app"
        takeover.onState(waiting)
        takeover.handOver()
        // ユーザーがホーム画面で支払いページを完了しましたが、ページが自動的に閉じられました: タスク 2 は利用できなくなりました
        port.displays.remove(2)
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("mute:a.app", "resume:complete"), port.log)
        assertNull(takeover.problem.value)
    }

    @Test
    fun originalTaskLeftOnMainScreenIsMovedBackToo() = test {
        port.displays[2] = AGENT
        port.packages[2] = "a.app"
        takeover.onState(waiting)
        takeover.handOver()
        // ページを閉じると、ユーザーはホーム画面上のアプリの元のタスクに戻ります。
        port.displays.remove(2)
        port.displays[1] = 0
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete"), port.log)
    }

    @Test
    fun finishedMyselfMovesBackAndAsksOnlyToVerify() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        assertTrue(takeover.finishedMyself())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete/finished"), port.log)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun finishedMyselfWithoutHandOverResumesWithoutMoving() = test {
        // ユーザーが他の場所 (別のデバイス、コンピューター) でプロセスを完了し、「処理します」をクリックしなかった可能性があります。
        takeover.onState(waiting)
        assertTrue(takeover.finishedMyself())
        assertEquals(listOf("resume:complete/finished"), port.log)
    }

    @Test
    fun handOverWithNoAppStillHandsOver() = test {
        port.displays.clear()
        port.target = -1
        takeover.onState(waiting)
        assertTrue(takeover.handOver())
        assertTrue(takeover.handBack())
        assertEquals(listOf("resume:complete"), port.log)
    }

    @Test
    fun cancelWhileWaiting() = test {
        takeover.onState(waiting)
        takeover.cancel()
        assertEquals(listOf("cancel"), port.log)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun cancelWhileHandedOverLeavesAppOnMainScreen() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        takeover.cancel()
        assertEquals(listOf("cancel"), port.log)
        assertEquals(0, port.displays[1])
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun cancelWithoutTakeoverStillCancels() = test {
        takeover.onState(TaskState.Running(3, "phone_tap"))
        takeover.cancel()
        assertEquals(listOf("cancel"), port.log)
    }

    @Test
    fun failedMoveToMainStaysWaitingAndSaysSo() = test {
        takeover.onState(waiting)
        port.moveToMainOk = false
        assertFalse(takeover.handOver())
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveToMainFailed, takeover.problem.value)
        assertFalse(takeover.handedOver)
        assertEquals(listOf("toMain:1"), port.log)

        port.moveToMainOk = true
        assertTrue(takeover.handOver())
        assertNull(takeover.problem.value)
    }

    @Test
    fun failedMoveBackKeepsUserInChargeAndDoesNotResume() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        port.moveBackOk = false
        assertFalse(takeover.handBack())
        assertEquals(listOf("back:1"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveBackFailed, takeover.problem.value)

        port.moveBackOk = true
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete"), port.log)
        assertNull(takeover.problem.value)
    }

    @Test
    fun actionsIgnoredWhenNotWaitingForTakeover() = test {
        takeover.onState(TaskState.Running(2, "phone_observe"))
        assertFalse(takeover.handOver())
        assertFalse(takeover.handBack())
        assertFalse(takeover.finishedMyself())
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun handOverTwiceMovesOnce() = test {
        takeover.onState(waiting)
        takeover.handOver()
        assertFalse(takeover.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), port.log)
    }

    @Test
    fun pipUnmuteWithoutRealHandoverFallsBackToPhone() = test {
        var phoneCalls = 0
        takeover.onState(TaskState.Running(1, "phone_observe"))
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(1, phoneCalls)
        // 接管对象一直存在;即使正在等待用户,普通当前 App 仍走 phone/AppMuter
        takeover.onState(waiting)
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(2, phoneCalls)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun pipUnmuteDuringAppHandoverUsesTakeoverAndRepeatedClicksRetry() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        var phoneCalls = 0
        restorePipSound(takeover) { phoneCalls++ }
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(listOf("pip-unmute:a.app", "pip-unmute:a.app"), port.log)
        assertEquals(0, phoneCalls)
    }

    @Test
    fun pipUnmuteDuringHandoverWithoutAppFallsBackToPhone() = test {
        port.displays.clear()
        port.target = -1
        takeover.onState(waiting)
        takeover.handOver()
        var phoneCalls = 0
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(1, phoneCalls)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun republishingTheAnsweredTakeoverDoesNotReArm() = test {
        takeover.onState(waiting)
        takeover.handBack()
        // ループが実行状態に遷移していない場合、再度同じ待ち状態が発行される可能性があります。
        takeover.onState(waiting)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertFalse(takeover.handBack())
    }

    @Test
    fun secondIdenticalTakeoverAfterResumeIsArmed() = test {
        takeover.onState(waiting)
        takeover.handBack()
        takeover.onState(TaskState.Running(5, "phone_observe"))
        // いつものように前回と同じ内容の新たなテイクオーバーが待っています。
        takeover.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertTrue(takeover.handBack())
    }

    @Test
    fun taskEndingClearsStage() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.onState(TaskState.Cancelled)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertFalse(takeover.handedOver)
    }

    @Test
    fun staleWaitKeyIsIgnored() = test {
        takeover.onState(waiting)
        val first = takeover.waitKey
        assertTrue(takeover.skip(first))
        takeover.onState(TaskState.Running(3, "phone_tap"))
        val second = TaskState.WaitingTakeover("login", "请登录")
        takeover.onState(second)
        assertTrue(takeover.waitKey != first)
        // 残りのクリックを最後に待機しています: アクションなし
        assertFalse(takeover.handOver(first))
        assertFalse(takeover.skip(first))
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertTrue(takeover.handOver(takeover.waitKey))
        assertFalse(takeover.handBack(first))
        assertFalse(takeover.finishedMyself(first))
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertTrue(takeover.handBack(takeover.waitKey))
    }

    @Test
    fun skipReturnsSkipped() = test {
        takeover.onState(waiting)
        assertTrue(takeover.skip())
        assertEquals(listOf("resume:skipped"), port.log)
        assertEquals(HandBack(HandBackOutcome.skipped), port.results.single())
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun skipWhileHandedOverMovesAppBackFirst() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        port.moveBackOk = false
        // 元に戻れません: そのままになります。ユーザーはまだホーム画面にいます。
        assertFalse(takeover.skip())
        assertEquals(listOf("back:1"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveBackFailed, takeover.problem.value)

        port.moveBackOk = true
        port.log.clear()
        assertTrue(takeover.skip())
        assertEquals(listOf("back:1", "mute:a.app", "resume:skipped"), port.log)
        assertEquals(AGENT, port.displays[1])
        assertNull(port.watching)
    }

    @Test
    fun handBackReportsActivityOfHandedOverApp() = test {
        port.displays[2] = AGENT
        port.packages[2] = "login.app"
        takeover.onState(waiting)
        takeover.handOver()
        assertEquals("login.app", port.watching)
        port.activity = HandoverActivity(3, "登录")
        testScheduler.advanceTimeBy(42_000)
        assertTrue(takeover.handBack())
        assertEquals(HandBack(HandBackOutcome.complete, durationMs = 42_000, inputCount = 3, stoppedAt = "登录"), port.results.single())
        assertNull(port.watching)
    }

    @Test
    fun failedMoveBackKeepsCounting() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.moveBackOk = false
        takeover.handBack()
        assertEquals("a.app", port.watching)
    }

    @Test
    fun cancelStopsCounting() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.cancel()
        assertNull(port.watching)
    }

    @Test
    fun handBackWithoutHandOverHasNoCounts() = test {
        takeover.onState(waiting)
        assertTrue(takeover.handBack())
        assertEquals(HandBack(HandBackOutcome.complete), port.results.single())
    }
}
