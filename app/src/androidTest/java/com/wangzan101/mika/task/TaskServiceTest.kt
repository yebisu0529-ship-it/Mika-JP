package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.verify.Verifier
import com.wangzan101.mika.shell.IMikaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 実機テスト: スクリプト モデルを使用して TaskService を駆動し、仮想画面でシステム設定を開き、常時オン、ミュート、削除、一時停止、ロックされているかどうかを確認します。
 * では、組み込みアシスタントが実行されており、Mika アクセシビリティ サービスがオンになっている必要があります。
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    /**
     * 各タスクにはスクリプト モデルがあります。モデルの呼び出し番号と各タスクの検証回数です。共有する場合、前のタスクが通話を奪っていない
     * (検証、終了) は次のタスクによってフェッチされ、テストはタスクの先頭まで実行されます。
     * タスクでは、工場からのモデルを必要とするのはタスク自体だけではありません。新しい会話のタイトルと会話が停止された後の説明もそれぞれ 1 回ずつ必要であり、それらはタスクと同時に行われます。 誰が最初に
     * を入手するかは誰にもわかりません。したがって、ファクトリは「要求された回数」に応じてモデルを発行するのではなく、最後に開始されたタスクのモデルのみを発行します ([model] を参照)。
     */
    private val models = List(MAX_TASKS) { ScriptedModel() }
    /** ファクトリは、タスク サービスのスレッドでそれを読み取ります。 */
    @Volatile private var started = 0
    /** 最新のタスク [start] のモデル (タスクを開始する前の最初のタスクに使用されます)。 */
    private val model: ScriptedModel get() = models[maxOf(0, started - 1)]

    private fun start(goal: String) {
        started++
        TaskService.start(context, goal)
    }
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        // ここでのタスクはすべてインターフェイスから開始されます。最初のツールを呼び出すときに、意図を再度尋ねることをお勧めします。常に「操作する」と答えて、スクリプトを占有しないでください。
        models.forEach { it.intent = "ACT" }
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // テストが途中で失敗した場合、設定がホーム画面に残る場合があります。テストを終了してタスクをホーム画面に移動するだけにしてください。
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    /** タスクを開き、仮想画面の設定を開いて仮想画面番号を返します。現時点では、モデルは 2 番目のステップを待っています。 */
    private fun startWithSettingsOpen(): Int {
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        return displayId
    }

    /** 検証可能な値を指定せずに回答を送信し、タスクが終了するのを待ちます。文字通りの検証では何も修正する必要はなく、追加の検証は必要なく、結果は完了します。 [appOpened] は、呼び出し側サイトで読み取れるようにするためだけに使用されます。 */
    private fun finishAndAwait(@Suppress("UNUSED_PARAMETER") appOpened: Boolean = true) {
        model.answer("done")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done" && !end.doubtful)
        // ステータスが最初に完了に変わり、最後の仕上げが完了するまでサウンドが復元され、その後ビジーが false に戻ります。
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun mikaScreenLock(): String? =
        sh("dumpsys power").lines().map { it.trim() }.firstOrNull { "'Mika:task'" in it }

    /**
     * 製品パス上の回答には追加の検証は必要ありません (タスクは reviewFinalAnswer = false で実行されます)。モデルによって与えられた回答がそのまま引き継がれ、結果は検証なしで完了します。
     * メインモデルも軽量モデルも検証リクエストを受信しませんでした。検証のルール自体は、コア単体テストによって管理されます。
     */
    @Test
    fun answerHandedOverWithoutReview() {
        val lightChecks = AtomicInteger()
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // に軽量モデルが装備されている場合は、まず目的とタイトルを決定するために質問する必要があります。それ以外の場合は、確認してカウントダウンすることしかできません。
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system == TITLE) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
                    if (system == Verifier.PROMPT) lightChecks.incrementAndGet()
                    return ModelReply(listOf(Part.Text(ScriptedModel.CANNOT_VERIFY)), StopReason.EndTurn, null)
                }
            }
        }
        model.verdict = ScriptedModel.CANNOT_VERIFY
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "电量是 9731 毫安时" && !end.reviewed && end.unconfirmed.isEmpty())
        assertEquals(0, lightChecks.get())
        assertEquals(0, model.verifierRequests.get())
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    /** アプリを開かないタスクでは仮想画面は作成されません。読み取り画面は空白の画面で、最初にphone_launch_appのプロンプトが表示され、書き込み操作はno_app_openに失敗し、画面がありません。 */
    @Test
    fun noDisplayUntilLaunchApp() {
        start("你好")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        val second = model.awaitCall()
        assertTrue(second.messages.last().toString(), "Start with phone_launch_app" in second.messages.last().toString())
        model.feed("phone_tap", """{"x":500,"y":500}""")
        val third = model.awaitCall()
        assertTrue(third.messages.last().toString(), "no_app_open" in third.messages.last().toString())
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, DeviceTaskState.frames.value)
        finishAndAwait(appOpened = false)
        assertEquals(-1, testTasks.agentDisplayId.value)

        // 初めてphone_launch_app が構築され、画面が 1 つだけ構築されました。 2 番目のタスクには、1 から開始される独自のスクリプト モデルがあります。
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        assertEquals(-1, testTasks.agentDisplayId.value)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val first = testTasks.agentDisplayId.value
        assertTrue(first > 0)
        settingsTask = shell.topTaskId(first)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(3, model.awaitCall().number)
        assertEquals(first, testTasks.agentDisplayId.value)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        finishAndAwait()
        assertEquals(-1, testTasks.agentDisplayId.value)
    }

    @Test
    fun openingSameAppPauses() {
        val displayId = startWithSettingsOpen()
        // バイパス コルーチンで開いているアプリに従ってミュートが行われます。しばらくお待ちください。
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } } ?: shell.getAudioMode(SETTINGS))

        // は、ユーザーがホーム画面で同じアプリを開いていることをシミュレートします。タスクはホーム画面に移動します。
        assertTrue(shell.moveTask(settingsTask, Display.DEFAULT_DISPLAY))
        // モデルは、まだ「推論中」(ゲートに到達する前) にすぐに一時停止を表示する必要があります。
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        // は一時停止中に自動的に戻りません
        Thread.sleep(1_500)
        assertEquals(settingsTask, shell.topTaskId(Display.DEFAULT_DISPLAY))

        TaskService.resume(context)
        assertEquals(3, model.awaitCall().number)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)

        finishAndAwait()
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**
     * は、グローバル設定を変更せずに、dumpsys の電源のみを確認します。「USB を接続してオンのまま」を一時的にオフにして、実際の画面オフのタイムアウトを待ちます。
     * を使用すると、最後のユーザー操作 (テスト済み) から長時間が経過すると、ホーム画面がオフになり、すぐにロックされます。これは、無人の場合には許容できません。
     * ロックがシステムによって無効になっている場合 (バックグラウンド制限など)、dumpsys は DISABLED とマークされますが、ここでは除外されます。
     */
    @Test
    fun keepsScreenOnDuringTask() {
        startWithSettingsOpen()
        val line = mikaScreenLock()
        assertNotNull("Mika holds no screen wake lock while running", line)
        // 一部のシステムでは、タイプの前にロック オブジェクト番号 (mLock: <id>) を追加し、タイプのみを認識します。
        // は画面をオンのままにするだけで、システムを暗くすることができ、最大の明るさにロックされません。
        assertTrue(line!!, "SCREEN_DIM_WAKE_LOCK" in line)
        assertFalse(line, "DISABLED" in line)
        val summary = sh("dumpsys power").lines().map { it.trim() }.first { it.startsWith("mWakeLockSummary=") }
        // ダイジェストはビット マスク (0x..)、AOSP の WAKE_LOCK_SCREEN_DIM = 1 shl 2;一部のバージョンはテキストとして印刷されます
        val bits = Regex("0x([0-9a-fA-F]+)").find(summary)?.groupValues?.get(1)?.toInt(16)
        assertTrue(summary, if (bits != null) bits and (1 shl 2) != 0 else "SCREEN_DIM" in summary)

        finishAndAwait()
        val released = pollUntil(3_000) { if (mikaScreenLock() == null) true else null }
        assertTrue("screen wake lock still held after the task: ${mikaScreenLock()}", released == true)
    }

    /** 最初のステップの前の準備 (モデルを開く、ナレッジを読み取る、ルートの選択) も実行中とみなされます。モデルを開く前に画面がロックされています。 */
    @Test
    fun keepsScreenOnWhilePreparing() {
        val opening = java.util.concurrent.CountDownLatch(1)
        testTasks.modelFactory = { _, _ -> opening.await(); model }
        start("测试任务")
        val held = pollUntil(5_000) { mikaScreenLock() }
        opening.countDown()
        assertNotNull("Mika holds no screen wake lock while preparing", held)
        assertEquals(1, model.awaitCall().number)
        finishAndAwait()
    }

    /**
     * 停止: インターフェイスはその場で停止され、その後、このラウンドの答えとして説明の段落が追加されます (エンディングはキャンセルされたままです)。軽量モデルを作成し、ツールは提供しないでください。
     * 軽量モデルでエラーが発生した場合、メインモデルに変更します。メインモデルを書き込めない場合は、レコードを押して直接作成してください。
     */
    @Test
    fun stopLeavesASummaryAsTheAnswer() {
        val asked = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // ゲーム開始を決定する意図に関する質問と回答は「操作が必要」です。他のライトコールは今回のみ書いて説明できます。
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system != SUMMARY) throw IllegalStateException("not for this test")
                    assertTrue(tools.isEmpty())
                    asked.incrementAndGet()
                    // まだ書き終えないでください。「整理中」が表示されます
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return ModelReply(listOf(Part.Text("停止前打开了设置,还没看任何页面。")), StopReason.EndTurn, null)
                }
            }
        }
        startWithSettingsOpen()
        val conversation = DeviceTaskState.conversationId.value!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val runId = pollUntil(5_000) { DeviceTaskState.endedRunIds.value[conversation] }!!
        assertTrue(runId in pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId in it } }.orEmpty())
        val store = com.wangzan101.mika.chat.ConversationStore.shared(context)
        // まず「キャンセル」と書いてください、説明を待つのはやめてください
        val placeholder = pollUntil(5_000) { store.turns(conversation).lastOrNull { it.runId == runId } }!!
        assertEquals("cancelled" to com.wangzan101.mika.core.agent.Conversation.CANCELLED_OUTCOME, placeholder.status to placeholder.outcome)
        gate.countDown()
        val turn = pollUntil(15_000) { store.turns(conversation).lastOrNull { it.runId == runId && it.outcome != com.wangzan101.mika.core.agent.Conversation.CANCELLED_OUTCOME } }!!
        assertEquals("停止前打开了设置,还没看任何页面。", turn.outcome)
        assertEquals("cancelled", turn.status)
        assertEquals(1, asked.get())
        assertNotNull(pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId !in it } })
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }

        // 軽量モデルが間違っており、メイン モデルが無駄です: レコードを押して直接綴ってください。
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    if (system == INTENT) ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null) else throw IllegalStateException("down")
            }
        }
        startWithSettingsOpen()
        val second = DeviceTaskState.conversationId.value!!
        val secondRun = pollUntil(5_000) { DeviceTaskState.run(second)?.runId?.value }!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val plain = pollUntil(20_000) { store.turns(second).lastOrNull { it.runId == secondRun && it.outcome != com.wangzan101.mika.core.agent.Conversation.CANCELLED_OUTCOME } }!!
        assertTrue(plain.outcome, plain.outcome.startsWith("停止しました。停止前の状況:"))
        assertEquals(1, model.summaryRequests.get())
    }

    // は、無人時には有効にしないでください。KEYCODE_SLEEP を送信して画面をオフにして電話をロックし、パスワードを入力してロックを解除できるのはユーザーだけです。
    // @Ignore を手動で削除し、ユーザーが存在しロックを解除する準備ができている場合にのみ実行します。
    @Ignore("needs user to unlock: pressing power locks the phone and only a person can unlock it")
    @Test
    fun lockPausesAndUnlockResumes() {
        startWithSettingsOpen()
        sh("input keyevent KEYCODE_SLEEP")
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
        // は誰かによってロックが解除されました。ロック解除後は自動的に続行されるため、「続行」をクリックする必要はありません
        val resumed = pollUntil(120_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Running } }
        assertNotNull(resumed)
        assertEquals(3, model.awaitCall().number)
        finishAndAwait()
    }

    @Test
    fun scheduledTaskFailsAndNotifiesWhenUnlockIsNotAllowed() {
        val access = (context.applicationContext as MikaApp).deviceAccess
        val wasEnabled = access.settings.config.value.enabled
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val before = notifications.activeNotifications.map { it.key }.toSet()
        val conversation = "test-scheduled-unlock-${System.nanoTime()}"
        access.settings.setEnabled(false)
        try {
            TaskService.startTrigger(context, "测试定时任务", conversation, conversation, null)
            assertEquals(1, model.awaitCall().number)
            sh("input keyevent KEYCODE_SLEEP")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive } })

            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            val end = awaitState { it is TaskState.Failed } as TaskState.Failed
            val expected = context.getString(com.wangzan101.mika.R.string.trigger_unlock_failed)
            assertEquals(expected, end.reason)
            assertEquals(FailKind.Environment, end.kind)

            val posted = pollUntil(5_000) {
                notifications.activeNotifications.firstOrNull {
                    it.key !in before && it.notification.channelId == TaskService.CHANNEL_RESULT &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == expected
                }
            }
            assertNotNull("missing scheduled-task failure notification", posted)
        } finally {
            access.settings.setEnabled(wasEnabled)
            sh("input keyevent KEYCODE_WAKEUP")
        }
    }

    @Test
    fun uiTaskStillWaitsForManualUnlock() {
        val power = context.getSystemService(PowerManager::class.java)
        try {
            start("测试普通任务")
            assertEquals(1, model.awaitCall().number)
            sh("input keyevent KEYCODE_SLEEP")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive } })

            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
            Thread.sleep(1_500)
            assertEquals("ordinary task attempted unattended unlock", TaskState.Held(TaskGate.LOCKED), DeviceTaskState.state.value)
        } finally {
            sh("input keyevent KEYCODE_WAKEUP")
        }
    }

    private companion object {
        const val MAX_TASKS = 4
        val INTENT by lazy { com.wangzan101.mika.core.prompt.Prompts.role("intent_gate") }
        val SUMMARY by lazy { com.wangzan101.mika.core.prompt.Prompts.role("stop_summary") }
        val TITLE by lazy { com.wangzan101.mika.core.prompt.Prompts.role("conversation_title") }
    }
}
