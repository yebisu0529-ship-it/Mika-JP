package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.UiAutomation
import kotlinx.coroutines.runBlocking
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.chat.ConversationStore
import androidx.compose.ui.test.onAllNodesWithContentDescription
import android.net.Uri
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.wangzan101.mika.ui.viewer.FileViewerActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 実機のダイアログ ページをウォークスルーします。仮想画面でスクリプト モデル設定を開き、手順、小さなウィンドウ (折りたたみ、展開、ドラッグ、必要に応じて自動的に展開)、および結果カードを確認します。
 * 各段階で、アプリの外部ファイル ディレクトリshots/ に写真が撮影されます (開発マシンから adb pull によって手動で表示されます)。
 * では、組み込みアシスタントが実行されており、Mika アクセシビリティ サービスがオンになっている必要があります。
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    // インターフェイスから開始されたタスクは、最初のアクションの前に意図について別の質問をする必要があります。決まった答えは「操作する」です。スクリプト内の各質問は、タスク サイクル自体のステップです。
    private val model = ScriptedModel().apply { intent = "ACT" }
    private val vm get() = (context.applicationContext as MikaApp).chat
    private val anyway = Prerequisites.Check(emptyList())
    private var savedSpeed: SpeedFlags? = null

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        File(context.getExternalFilesDir(null), "shots").mkdirs()
        // インターフェイスのステータスはプロセス全体で共有されます。前のテストのすべてのタスクが完了するまで待ってから、新しい会話に切り替えて、終了ステータスへの接続を回避します。
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // パラレル スイッチは永続的な設定です。他のテスト (または途中でクラッシュ) は true のままになる場合があります。各テストはそれ自体で決定され、デフォルトではオフになっています。
        FeatureFlags.get(context).let { f -> savedSpeed = f.speedFlags(); f.setSpeed(savedSpeed!!.copy(parallelTasks = false)) }
        // 会話ページが前の会話の終了ステータスのままハングしている場合、newConversation は「ビジー」とみなされ実行されません。アイドル状態に戻るのを待ち、空の会話に置き換えられたことを確認します。
        assertNotNull("composer never went idle", pollUntil(20_000) { true.takeIf { !vm.busy() } })
        compose.runOnUiThread { vm.newConversation() }
        compose.waitForIdle()
        assertTrue("not a fresh conversation: ${vm.ui.value.exchanges}", vm.ui.value.exchanges.isEmpty())
    }

    @After
    fun tearDown() {
        DeviceTaskState.runs.value.forEach { it.cancel() }
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.active() } }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
        // テストに使用したスイッチ上のデバイスを離れずに、永続的な設定をこのテスト前の状態に復元します。
        savedSpeed?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    private fun TaskState.active() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled && this != TaskState.Idle

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        // デフォルトの UiAutomation は他のアクセシビリティ サービスを抑制し、Mika のサービスは切断されます
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(context.getExternalFilesDir(null), "shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** dumpsys の現時点でフォアグラウンドにあるアクティビティの行。 */
    private fun topResumed(dump: String): String? =
        dump.lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }

    /** このアプリのフォアグラウンドのアクティビティは [cls] です (dumpsys では「パッケージ名/.相対クラス名」として記述されます)。 */
    private fun onTop(cls: Class<*>): Boolean {
        val line = topResumed(sh("dumpsys activity activities")) ?: return false
        val short = "${context.packageName}/" + cls.name.removePrefix(context.packageName)
        return line.contains("$short ") || line.contains("${context.packageName}/${cls.name} ")
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    @Test
    fun runsTaskWithStepsPipAndResult() {
        shot("01-idle")

        compose.typeAndSend("打开设置看看电池")
        if (vm.ui.value.blocked != null) {
            // この電話にはモデルなどが装備されていません: 停止時にプロンプ​​トのスクリーンショットを撮り、スクリプト モデルを使用して実行してチェックを回避します。
            shot("02-blocked")
            // はチェックをバイパスし、インターフェイスのステータスに直接転送します。入力ボックス内の単語「interface」はクリアされません。タスクの開始後は入力ボックスを編集できないため、最初に入力ボックスをクリアしてください。
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.runOnUiThread { vm.dismissBlocked(); assertTrue(vm.send("打开设置看看电池", anyway)) }
        }

        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS","reason":"先打开系统设置"}""")
        assertEquals(2, model.awaitCall().number)
        model.feed("phone_observe", """{"reason":"看看设置首页有什么"}""")
        assertEquals(3, model.awaitCall().number)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        // が実行されている場合、行の現在の進行状況が下の垂直線にリストされます。アクションはモデル独自の言葉を使用し、参照する場所を読み取りおよび書き込みます (読み取りのためにモデルの目的を書き込まないでください)。
        // は現在、モデルの 3 回目の呼び出しを待機しています。進捗ラインには「処理中」と表示されます。最後のステップ「設定の表示」が現在のステップです。
        compose.waitUntil(5_000) { compose.onAllNodesWithText(appString(R.string.chat_working)).fetchSemanticsNodes().isNotEmpty() }
        val settingsName = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(SETTINGS, 0)).toString()
        compose.onNodeWithText(appString(R.string.step_viewed, settingsName)).assertExists()
        compose.onNodeWithText(ChatCopy.BACKGROUND_NOTE).assertExists()
        compose.onNodeWithText("先打开系统设置").assertExists()
        compose.onNodeWithText(ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true)).assertExists()
        assertTrue(vm.ui.value.pip.visible)
        shot("03-running-collapsed")

        compose.onNodeWithContentDescription(appString(R.string.pip_screen)).performClick()
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        shot("04-running-expanded")
        compose.onNodeWithContentDescription(appString(R.string.collapse)).performClick()
        compose.waitForIdle()

        // 小さなウィンドウは通常、右下隅 (入力フィールドの上) にあります。それを上に投げて左に移動すると、左上隅に吸い込まれるはずです。
        val d = context.resources.displayMetrics.density
        val pip = compose.onNodeWithContentDescription(appString(R.string.pip_screen)).fetchSemanticsNode().boundsInWindow
        val x = pip.center.x
        val y = pip.center.y
        sh("input swipe ${x.toInt()} ${y.toInt()} ${(x - 250 * d).toInt()} ${(y - 300 * d).toInt()} 120")
        compose.waitForIdle()
        val moved = compose.onNodeWithContentDescription(appString(R.string.pip_screen)).fetchSemanticsNode().boundsInWindow
        assertTrue("pip did not move up-left: $pip -> $moved", moved.center.x < x && moved.center.y < y)
        shot("05-dragged")

        model.feed("hand_to_user", """{"reason":"captcha","message":"设置里弹出了验证(脚本演示),需要你处理。"}""")
        awaitState { it is TaskState.WaitingTakeover }
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        compose.onNodeWithText(appString(R.string.takeover_title_reason, appString(R.string.takeover_reason_captcha))).assertExists()
        compose.onNodeWithText(appString(R.string.chat_paused)).assertExists()
        shot("06-takeover")

        // 拡張された小さなウィンドウとマスクが会話中のカードを覆います。最初にカードを折り、次にカードのボタンをクリックします。
        compose.onNodeWithContentDescription(appString(R.string.collapse)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(appString(R.string.action_cancel_task)).performClick()
        awaitState { it == TaskState.Cancelled }
        // 停止後に追加された命令 (スクリプト モデルは書き込まれません。レコードを押して直接入力します) は、「停止」の下にリストされます。
        compose.onNodeWithText(appString(R.string.chat_stopped)).assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("停止しました。停止前の状況:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("06b-stopped-summary")

        // 次に、新しいタスクを開始します。サービスはまだ前のタスクを終了している可能性があり、インターフェイスはタスクを引き渡す前に終了するまで待つ必要があります。
        compose.runOnUiThread { assertTrue(vm.send("设置里电池是多少", anyway)) }
        model.awaitCall() // スクリプトモデルの呼び出し番号は前のタスクからカウントダウンされます。
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "打开设置")
        model.awaitCall()
        model.answer("脚本演示:电池信息在「设置 › 电池」里。")
        awaitState { it is TaskState.Succeeded }
        compose.waitForIdle()
        compose.onNodeWithText(appString(R.string.source_label), substring = true).assertExists()
        // キャンセルされた質問と完了した質問にはそれぞれ「閲覧処理」がございます
        assertEquals(2, compose.onAllNodesWithText(appString(R.string.chat_view_process)).fetchSemanticsNodes().size)
        shot("07-done")

        // サイドバー: 会話ページ (サイド スライド) に戻り、最初にサイドバーを開きます。 5 つの入り口と最近の会話
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.drawer_plans)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.memory_and_skills)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.drawer_browser)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.drawer_recent)).assertIsDisplayed()
        shot("08-drawer")
    }

    @Test
    fun sentTurnIsVisibleAtTheTailOfALongConversation() {
        val store = ConversationStore(context)
        val id = store.newConversation()
        repeat(30) { i ->
            store.append(id, Turn("旧问题 $i", "旧回答 $i\n" + "这是一段用于撑满长会话视口的历史内容。\n".repeat(8), "succeeded"), emptyList())
        }
        compose.runOnUiThread { vm.openConversation(id) }
        compose.waitUntil(5_000) { vm.ui.value.conversationId == id && vm.ui.value.exchanges.size == 30 }
        compose.waitForIdle()

        val message = "刚发送的新一轮必须直接显示"
        compose.runOnUiThread { assertTrue(vm.send(message, anyway)) }
        compose.waitUntil(5_000) { vm.ui.value.exchanges.size == 31 }
        // 不能只 assertExists:回归的正是新行已存在、却被旧布局行数留在视口外。
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    /** 附件按钮只在沙箱不是「此设备不支持」时出现(未安装、安装中、坏了都还能装、能修)。 */
    @Test
    fun attachButtonHiddenOnlyWhenSandboxUnsupported() {
        val unsupported = SandboxState.get(context).status.value is SandboxStatus.Unsupported
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithContentDescription(appString(R.string.attach_add)).fetchSemanticsNodes()
        assertEquals("sandbox unsupported=$unsupported", !unsupported, nodes.isNotEmpty())
    }

    /** ファイル カードには名前とサイズが表示されます。テキスト ファイルをクリックしてアプリの表示ページに入ります (アプリの選択をシステムに任せることはなくなりました)。ファイルの内容を確認してダイアログに戻ることができます。 */
    @Test
    fun fileCardOpens() {
        val ws = SandboxDirs(context).workspace("card-device-test").apply { mkdirs() }
        val f = File(ws, "card.txt").apply { writeText("hello") }
        val name = "mika-card-${System.currentTimeMillis()}.txt"
        val card = runBlocking { com.wangzan101.mika.cap.sandbox.AndroidFdSys.gate.openRead(ws, f.name).use { ShareExporter(context).export(it, name) } }
        val store = ConversationStore(context)
        val id = store.newConversation()
        store.append(id, Turn("导出", "好了", "succeeded"), listOf(card))
        try {
            compose.runOnUiThread { vm.openConversation(id) }
            compose.onNodeWithText(card.name).assertExists()
            compose.onNodeWithText(card.name).performClick()
            val shown = pollUntil(5_000) { true.takeIf { onTop(FileViewerActivity::class.java) } }
            assertNotNull("file viewer did not open: ${topResumed(sh("dumpsys activity activities"))}", shown)
            // 表示ページは別のアクティビティですが、会話ページと同じプロセスにあります。そのインターフェースもここにあります。名前はトップバーにあり、内容はそのまま表示されます。
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello", substring = true, useUnmergedTree = true).assertIsDisplayed()
            shot("file-viewer")
            sh("input keyevent KEYCODE_BACK")
            val back = pollUntil(5_000) { true.takeIf { onTop(MainActivity::class.java) } }
            assertNotNull("back did not return to the chat page", back)
        } finally {
            context.contentResolver.delete(Uri.parse(card.uri), null, null)
            ws.deleteRecursively()
        }
    }

    /** 並列スイッチがオンになっています。2 つの会話のそれぞれがタスクを実行します。メニューに小さな点があります。 「進行中」サイドバーにある他の会話をクリックして、その会話に切り替えます。 */
    @Test
    fun otherTaskChipSwitches() {
        val flags = FeatureFlags.get(context)
        val prior = flags.speedFlags()
        flags.setSpeed(prior.copy(parallelTasks = true))
        val models = listOf(ScriptedModel().apply { intent = "ACT" }, ScriptedModel().apply { intent = "ACT" })
        // モデル ファクトリは、タスクの開始時にフェッチされるだけではありません。新しいダイアログのタイトルもバックグラウンドで別の時間にフェッチされますが、順序は不確かです。カウントによって回転すると、最初のタスクが 2 番目のモデルを取得します。
        // が最初のタスクに変更されました。本当にモデルについて尋ねたら、私は前に1番目のモデルを、その後に2番目のモデルを答えるでしょう。 (タイトルの質問で、どのモデルの答えが決まっていますか)
        val firstStarted = AtomicBoolean(false)
        testTasks.modelFactory = { _, _ -> if (firstStarted.get()) models[1] else models[0] }
        try {
            compose.runOnUiThread { assertTrue(vm.send("第一个任务", anyway)) }
            assertEquals(1, models[0].awaitCall().number)
            firstStarted.set(true)
            val first = vm.ui.value.conversationId
            compose.runOnUiThread {
                vm.newConversation()
                assertTrue(vm.send("第二个任务", anyway))
            }
            assertEquals(1, models[1].awaitCall().number)
            val second = vm.ui.value.conversationId
            assertTrue(first != second)

            compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performClick()
            compose.waitForIdle()
            // 「進行中」は、セグメントのタイトルと、別の会話の行の下にある小さな文字の両方です。位置によって個別に認識されます - タイトルが上部にあり、行が下にあります (タイトルと小さな文字は、クリック可能なノードに結合されています)
            val inDrawer = hasAnyAncestor(hasTestTag("drawer"))
            val other = compose.onNode(hasText("第一个任务") and hasText(appString(R.string.drawer_running)) and inDrawer)
            other.assertIsDisplayed()
            val header = compose.onNode(hasText(appString(R.string.drawer_running)) and !hasText("第一个任务") and inDrawer)
            header.assertIsDisplayed()
            val headerTop = header.fetchSemanticsNode().boundsInWindow.top
            val otherTop = other.fetchSemanticsNode().boundsInWindow.top
            assertTrue("実行中の項目が「実行中」の見出しより下にない: $headerTop と $otherTop", otherTop > headerTop)
            other.performClick()
            compose.waitForIdle()
            assertEquals(first, vm.ui.value.conversationId)
            assertEquals("第一个任务", vm.ui.value.exchanges.last().goal)
            shot("parallel-drawer")

            models.forEach { it.answer("done") }
            assertNotNull(pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } })
        } finally {
            DeviceTaskState.runs.value.forEach { it.cancel() }
            pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
            flags.setSpeed(prior)
        }
    }
}
