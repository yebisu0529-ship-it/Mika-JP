package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.TaskGate

import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.ui.PipPhysics.Corner
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val state = MutableStateFlow<TaskState>(TaskState.Idle)
    private val stage = MutableStateFlow(Takeover.Stage.None)
    private val calls = mutableListOf<String>()
    private val acts = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    private val steerFlow = MutableStateFlow<List<SteerNote>>(emptyList())
    private val steerIds = mutableListOf<String>()
    private var canWithdraw = true
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text"; steerIds += messageId }
        override fun withdrawSteer(conversationId: String, messageId: String): Boolean {
            calls += "withdraw:$messageId"
            return canWithdraw
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() { calls += "resume" }
        override fun takeOver() { calls += "takeOver" }
        override fun handBack() { calls += "handBack" }
        override fun finishedMyself() { calls += "finishedMyself" }
        override fun skip() { calls += "skip" }
        override fun answer(text: String) { calls += "answer:$text" }
        override fun approve(key: Int) { calls += "approve:$key" }
        override fun decline(key: Int) { calls += "decline:$key" }
        override fun show(conversationId: String) { calls += "show:$conversationId" }
        override fun cancelIn(conversationId: String) { calls += "cancel@$conversationId" }
        override fun resumeIn(conversationId: String) { calls += "resume@$conversationId" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approveIn(conversationId: String, key: Int) { calls += "approve@$conversationId:$key" }
        override fun unmuteIn(conversationId: String) { calls += "unmute@$conversationId" }
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    private fun TestScope.vm(store: ConversationStore = ConversationStore(tmp.newFolder())) =
        ChatViewModel(backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it },
            activities = acts, steers = steerFlow).also { runCurrent() }

    /** は [vm] と同じですが、画面のオン/オフを指定できます。 */
    private fun TestScope.vmWithScreen(open: MutableStateFlow<Boolean>) =
        ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            appLabel = { if (it == "com.chat") "聊天" else it }, activities = acts, steers = steerFlow,
            screenOpen = open).also { runCurrent() }

    private fun TestScope.emit(s: TaskState) {
        state.value = s
        runCurrent()
    }

    /** ランニングイベントで実際に行われることがもう 1 つあります。 */
    private fun TestScope.did(step: Int, kind: String, tool: String, target: String?, effect: String = "completed", title: String? = null, failed: Boolean = false) {
        acts.value = acts.value + RunEvent.Activity(0, step, kind, tool, target, effect, title = title, failed = failed)
        runCurrent()
    }

    @Test fun blocksWhenPrerequisiteMissing() = runTest {
        val vm = vm()
        val check = Prerequisites.check(ready.copy(accessibility = AccessibilityState.Off), hasMainModel = false)
        assertFalse(vm.send("查天气", check))
        assertTrue(calls.isEmpty())
        // アクセシビリティは、モバイル アプリを操作する場合にのみ必要であり、タスクをブロックしません。モデルのみがブロックされます。
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // 通知がオンになっていない場合は、道路を妨害したとはみなされません。
        assertTrue(vm.send("查天气", Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)))
        assertNull(vm.ui.value.blocked)
        assertEquals(listOf("start:查天气"), calls)
    }

    @Test fun showsStepsFromState() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())

        emit(TaskState.Running(1, ""))
        var steps = vm.ui.value.exchanges.single().steps
        assertTrue(steps.isEmpty())
        assertEquals("作業中", liveLine(vm.ui.value.exchanges.single()))

        // ツールを選択しましたが、まだ実行していません: ツールを連続してリストせず、実際に完了するまで待ちます。
        emit(TaskState.Running(1, "我先打开设置", "phone_launch_app"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())
        did(1, "act", "phone_launch_app", "com.chat", "place_changed")
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "アプリを開く · 聊天", "", StepState.Run), steps.single())
        assertEquals("アプリを開く · 聊天", liveLine(vm.ui.value.exchanges.single()))

        // 読み取り: 「画面を見てください」が表示されません。同じ場所の連続した読み取り値は 1 行に結合されます。どこを読んでいるのかわかりません。
        emit(TaskState.Running(2, "我先打开设置"))
        emit(TaskState.Running(2, "phone_observe", "phone_observe"))
        did(2, "observe", "phone_observe", "com.chat", title = "会话列表")
        did(2, "observe", "phone_diagnose", "com.chat", title = "会话列表")
        did(2, "observe", "phone_observe", null)
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("アプリを開く · 聊天", "聊天 の 会话列表 を確認"), steps.map { it.text })
        assertEquals(listOf(StepState.Ok, StepState.Run), steps.map { it.state })

        emit(TaskState.WaitingTakeover("captcha", "出现滑块验证码"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals("あなたの対応が必要", liveLine(ex))
        assertEquals(ExchangeStatus.NeedsTakeover("captcha", "出现滑块验证码"), ex.status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.takeOver()
        assertEquals("takeOver", calls.last())
    }

    @Test fun paymentTakeoverCarriesDetailsAndCanBeSkipped() = runTest {
        val vm = vm()
        vm.send("买一杯咖啡", ok)
        emit(TaskState.WaitingTakeover("payment", "请付款", item = "拿铁 大杯", amount = "¥18", address = "公司前台"))
        assertEquals(
            ExchangeStatus.NeedsTakeover("payment", "请付款", "拿铁 大杯", "¥18", "公司前台"),
            vm.ui.value.exchanges.single().status,
        )
        vm.skip()
        assertEquals("skip", calls.last())
    }

    // 答えはそのまま表示されます。ソースは答えから分離されておらず、実行記録に書き留められたコードによって与えられます。
    @Test fun finalAnswerShownAsIs() = runTest {
        val vm = vm()
        vm.send("电量多少", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        did(1, "observe", "phone_observe", "com.chat")
        emit(TaskState.Running(2, "phone_observe")) // 次のステップはまだモデルを待っていますが、これで終了です。この行は残されません。
        emit(TaskState.Succeeded("电量 86%,正在充电。来源:设置 › 电池"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("电量 86%,正在充电。来源:设置 › 电池", null), ex.status)
        assertEquals(listOf(StepRow(1, "聊天 を確認", "", StepState.Ok)), ex.steps)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        assertEquals("没有依据段落" to null, splitEvidence("没有依据段落"))
        // 旧レコード（会話ファイル）の書き方：最後の一行が「Based on:」で始まり、区切られたまま
        assertEquals("已把亮度调到 50%。\n自动亮度已关闭。" to "设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关",
            splitEvidence("已把亮度调到 50%。\n自动亮度已关闭。\n依据: 设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关"))
        assertEquals("结论。" to "来自搜索结果第一条", splitEvidence("结论。证据：来自搜索结果第一条"))
    }

    /** の各ステップには実際のアクションがあります。このステップをクリックすると、どのツールが使用されたか、何が使用されたか、および結果がどうなったかを確認できます。 */
    @Test fun eachStepCarriesWhatItActuallyDid() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        did(1, "act", "phone_tap", "com.chat", "changed")
        did(1, "act", "phone_type_text", "com.other", "unknown_code", failed = true)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(
            listOf(
                // は、ツール名なしでツール インターフェイス ステートメントを使用します。
                StepAction("タップ", "聊天", "changed", false),
                StepAction("テキストを入力", "com.other", "unknown_code", true),
            ),
            ex.stepActions.getValue(1),
        )
        // はアクションのあるステップでのみハングします
        assertEquals(setOf(1), ex.stepActions.keys)
        // クリックすると、それぞれに 1 行が表示されます: 何が行われたか、何が行われたか、結果。エラーコードは他の人には表示されず、「失敗しました」と表示されます
        val lines = ex.stepActions.getValue(1).map(ChatCopy::actionLine)
        assertEquals("タップ · 聊天 · ${tr(R.string.result_effective)}", lines[0])
        assertEquals("テキストを入力 · com.other · ${tr(R.string.step_failed)}", lines[1])
        lines.forEach { assertFalse(it, it.contains("phone_") || it.contains("unknown_code")) }
    }

    @Test fun unverifiedIsMarkedOnTheResult() = runTest {
        val vm = vm()
        vm.send("查余额", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元", verified = false, note = "结果页受系统防截屏保护"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("余额 10 元", null, verified = false, note = "结果页受系统防截屏保护"), ex.status)
        assertEquals("完了しましたが、確認できませんでした: 结果页受系统防截屏保护", (ex.status as ExchangeStatus.Done).unverifiedLine)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)

        vm.send("再查", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元"))
        assertNull((vm.ui.value.exchanges.last().status as ExchangeStatus.Done).unverifiedLine)
    }

    @Test fun liveLineUsesTheModelsReasonAndCollapsesToASummary() = runTest {
        var now = 1_000L
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen, clock = { now },
            activities = acts).also { runCurrent() }
        vm.send("查上海到杭州的车次", ok)
        emit(TaskState.Running(1, "", "phone_launch_app", reason = "打开 12306"))
        did(1, "act", "phone_launch_app", "cn.railway")
        screen.value = true
        runCurrent()
        var ex = vm.ui.value.exchanges.single()
        assertEquals(StepRow(1, "アプリを開く · cn.railway", "", StepState.Run, "打开 12306"), ex.steps.single())
        assertEquals("打开 12306", liveLine(ex))
        assertTrue(vm.ui.value.operatingPhone)

        // モデルは、リクエストの転送中はニュートラル状態を示し、新しいステップは作成されません。
        emit(TaskState.Running(2, ""))
        ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.steps.size)
        assertEquals("作業中", liveLine(ex))

        emit(TaskState.Running(2, "", "phone_tap", reason = "正在 12306 搜索上海到杭州的车次"))
        assertEquals("正在 12306 搜索上海到杭州的车次", liveLine(vm.ui.value.exchanges.single()))
        did(2, "act", "phone_tap", "cn.railway", "changed")

        // などはロック解除時のバックグラウンド操作としてカウントされません。
        emit(TaskState.Held(TaskGate.LOCKED))
        assertFalse(vm.ui.value.operatingPhone)

        emit(TaskState.Running(3, "正在核实结果", reason = "正在核实结果"))
        did(3, "observe", "phone_observe", "cn.railway", title = "查询结果")
        now = 81_000L
        emit(TaskState.Succeeded("有 3 趟\n依据:12306 查询结果页"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(80_000L, ex.elapsedMs)
        assertEquals("3 ステップ · 1 分 20 秒", stepsSummary(ex))
        assertFalse(vm.ui.value.operatingPhone)
        // 時間がわからない場合は、歩数を言ってください。
        assertEquals("3 ステップ", stepsSummary(ex.copy(elapsedMs = null)))
    }

    /** 一番上の列のサブタイトル「Operating...」: 最新のオブジェクトが配置されているアプリ (パッケージ名をアプリ名に置き換えます)。 */
    @Test fun livePlaceIsTheLatestTargetedApp() = runTest {
        val vm = vm()
        vm.send("看聊天", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertNull(vm.livePlace(vm.ui.value.exchanges.single()))
        did(1, "act", "phone_launch_app", "com.chat")
        did(2, "act", "phone_wait", null)
        val ex = vm.ui.value.exchanges.single()
        assertEquals("聊天", vm.livePlace(ex))
        assertEquals("聊天 で作業中" to ChatCopy.Tone.Accent, ChatCopy.subtitle(ex, vm.livePlace(ex)))
    }

    // 通話で書かれた進行状況の説明は、通話の意図よりも優先されます。
    @Test fun liveLinePrefersNarration() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("查车次", ok)
        emit(TaskState.Running(1, "先打开 12306 看看", "phone_launch_app", reason = "打开 12306", narration = "先打开 12306 看看"))
        assertEquals("先打开 12306 看看", liveLine(vm.ui.value.exchanges.single()))
        emit(TaskState.Running(2, "", "phone_tap", reason = "点搜索"))
        assertEquals("点搜索", liveLine(vm.ui.value.exchanges.single()))
    }

    @Test fun streamedTextIsVisibleDuringRequestAndClearedAtCompletion() = runTest {
        val vm = vm()
        vm.send("解释这个概念", ok)
        emit(TaskState.Running(1, "", preview = "第一段"))
        var ex = vm.ui.value.exchanges.single()
        assertEquals("第一段", ex.draft)
        assertTrue(ex.steps.isEmpty())
        assertEquals("作業中", liveLine(ex))

        emit(TaskState.Running(1, "", preview = "第一段和第二段"))
        assertEquals("第一段和第二段", vm.ui.value.exchanges.single().draft)

        emit(TaskState.Succeeded("完整答复"))
        ex = vm.ui.value.exchanges.single()
        assertNull(ex.draft)
        assertEquals(ExchangeStatus.Done("完整答复", null), ex.status)
    }

    // 一度返却した後も一致しないものがいくつかあります。緑色のチェックを入れずに結果カードに 1 つずつ記載します
    @Test fun unconfirmedItemsAreListedOnTheCard() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("看价格", ok)
        emit(TaskState.Running(1, "", "phone_observe"))
        emit(TaskState.Succeeded("咖啡豆 ¥79", verified = false, note = "未确认:79", unconfirmed = listOf("79")))
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals("咖啡豆 ¥79", done.answer)
        assertNull(done.evidence)
        assertEquals("確認できませんでした: 79", done.unverifiedLine)
    }

    @Test fun phoneHintOnlyWhileTheScreenIsOpen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("算一下 1+1", ok)
        emit(TaskState.Running(1, "", "run_command"))
        // は、バックグラウンドで電話を操作することは言うまでもなく、画面 (ドキュメント、Web ページ、純粋な会話タスク) を開いたことがありません。
        assertFalse(vm.ui.value.operatingPhone)
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.operatingPhone)
        emit(TaskState.Succeeded("2"))
        assertFalse(vm.ui.value.operatingPhone)
    }

    @Test fun answerPartsPrefersAStructuredEvidence() {
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池"))
        assertEquals("电量 86%" to "电池页", answerParts("电量 86% ", evidence = " 电池页 "))
        assertEquals("电量 86%\n依据:设置 › 电池" to "电池页", answerParts("电量 86%\n依据:设置 › 电池", evidence = "电池页"))
        // 空の場合は、回答から削除してください。
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池", evidence = " "))
        assertEquals("没有依据" to null, answerParts("没有依据"))
    }

    @Test fun evidenceHeadingsAcceptJapaneseAndLegacyAliasesWithoutChangingTheBody() {
        val body = "# 回答\n\n結果は「確認済み」です。\n\n- ID: sample-42\n- 更新日: 2026-11-03"
        for (heading in listOf("根拠", "出典", "依据", "证据", "来源")) {
            assertEquals(heading, body to "確認したページ", splitEvidence("$body\n\n## **$heading**： 確認したページ"))
        }
        assertEquals("回答です。" to "確認したページ", splitEvidence("回答です。根拠: 確認したページ"))
        assertEquals("回答です！" to "確認したページ", splitEvidence("回答です！出典：確認したページ"))
    }

    @Test fun ordinaryEvidenceWordsAndIncompleteBlocksStayInTheBody() {
        val body = "# 出典について\n根拠の説明も本文です。\n価格: 500円"
        assertEquals(body to null, splitEvidence(body))
        for (heading in listOf("根拠", "出典")) {
            val quoted = "ラベルは「$heading: 未確認777」です。"
            assertEquals(quoted to null, splitEvidence(quoted))
        }
        assertEquals("回答です。\n根拠:" to null, splitEvidence("回答です。\n根拠:"))
        assertEquals("出典: 確認したページ" to null, splitEvidence("出典: 確認したページ"))
        val answer = "回答です。\n根拠: 本文中の案内"
        assertEquals(answer to "記録された出典", answerParts(answer, evidence = "記録された出典"))
    }

    // 新しいレコード: ソースは証拠台帳 (ソース イベント) のコードによって検出され、1 つずつリストされます。画像はこれらを読み取るステップを示しています。
    @Test fun evidenceSourceUsesRecordedSources() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Sources(3, listOf(
                com.wangzan101.mika.core.verify.AnswerSource(1, "screen", "com.chat", null, 1, listOf(1)),
                com.wangzan101.mika.core.verify.AnswerSource(4, "web", "https://www.12306.cn/index", "车次查询", 3, emptyList()),
            )),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(1), listOf("ステップ 1 · 聊天", "ステップ 3 · www.12306.cn · 车次查询")), source)
        assertEquals("ソース · www.12306.cn · スクリーンショット 1 枚", sourceLabel(source))
    }

    @Test fun evidenceSourceIsTheLastSavedScreen() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Media(3, 3, 3, null),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(2, 3)), source)
        assertEquals("ソース · www.12306.cn · スクリーンショット 2 枚", sourceLabel(source))
        assertEquals(EvidenceSource("聊天", listOf(1)), evidenceSource(events.take(1)) { if (it == "com.chat") "聊天" else it })
        // にはスクリーンショット (ファイル、純粋な対話タスク) はありません。取得していない場合は「ソース」とだけ書いてください。
        assertEquals(EvidenceSource(null, emptyList()), evidenceSource(emptyList()))
        assertEquals("ソース", sourceLabel(EvidenceSource(null, emptyList())))
        assertEquals("ソース", sourceLabel(null))
    }

    @Test fun livePreviewVisibleOnlyWhileRunning() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        assertFalse(vm.ui.value.pip.visible)
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)

        emit(TaskState.Succeeded("好了"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()
        assertFalse(vm.ui.value.pip.visible)

        screen.value = false
        runCurrent()
        vm.send("再来", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        emit(TaskState.Failed("出错了", FailKind.GaveUp))
        assertFalse(vm.ui.value.pip.visible)
    }

    @Test fun pipOnlyOnceTheTaskHasAScreen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("打开设置看安卓版本", ok)
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        assertFalse(vm.ui.value.pip.visible)
        vm.expandPip()
        assertFalse(vm.ui.value.pip.expanded)
        // アプリを開く前の質問はチャット カードのみにあります
        emit(TaskState.WaitingInput("哪个?", null))
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(2, "phone_launch_app", "phone_launch_app"))

        screen.value = true // Phone_launch_app が初めて画面を構築したとき
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)
        // 通常どおり画面を開いた後、引き継ぎを処理する必要があります。
        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        // 最後に、最初に画面を削除してから終了をアナウンスします。完了後の数秒は表示されたままです。
        screen.value = false
        runCurrent()
        emit(TaskState.Succeeded("安卓 14\n依据:关于手机页"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
    }

    @Test fun chatOnlyTaskNeverShowsPip() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = MutableStateFlow(false)).also { runCurrent() }
        vm.send("你好", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Running(2, ""))
        emit(TaskState.Succeeded("你好!", verified = false, reviewed = false))
        assertFalse(vm.ui.value.pip.visible)
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals(ExchangeStatus.Done("你好!", null, verified = false, reviewed = false), done)
        // 何もチェックされていないチャット: マークなし
        assertNull(done.unverifiedLine)
    }

    @Test fun pipSnapsToNearestCorner() {
        val b = PipPhysics.Bounds(1080f, 2400f, 200f, 420f, margin = 30f, top = 300f, bottom = 250f)
        assertEquals(850f to 330f, b.position(Corner.TopEnd))
        assertEquals(30f to 1700f, b.position(Corner.BottomStart))
        // は右上付近で停止し、速度がありません: 右上に戻ります
        assertEquals(Corner.TopEnd, PipPhysics.snapCorner(700f, 500f, 0f, 0f, b))
        // 同じ位置で下に投げる: 投射着点が画面の下半分にある
        assertEquals(Corner.BottomEnd, PipPhysics.snapCorner(700f, 500f, 0f, 2500f, b))
        // 左投げ
        assertEquals(Corner.BottomStart, PipPhysics.snapCorner(700f, 500f, -2000f, 2500f, b))
        assertEquals(499f, PipPhysics.project(1000f), 0.01f)
        // ウィンドウが短すぎます (キーボードがポップアップするなど)。上部バーの下にドリルされず、下の角が上の角と同じレベルまで後退します。
        val short = b.copy(height = 900f)
        assertEquals(30f to 330f, short.position(Corner.BottomStart))

        // 臨界減衰: 目標に収束し、超えない
        val spring = PipPhysics.Spring()
        var m = PipPhysics.Motion(0f, 0f)
        var max = 0f
        repeat(120) {
            m = spring.step(m, 100f, 1f / 60)
            max = maxOf(max, m.position)
        }
        assertTrue(spring.settled(m, 100f))
        assertTrue("overshoot $max", max <= 100.5f)
    }

    @Test fun pipAutoExpandsWhenNeedsUser() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded)

        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        vm.collapsePip()
        assertFalse(vm.ui.value.pip.expanded)
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded) // は同じ待機中に繰り返しバウンスしません
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)

        emit(TaskState.Running(2, "", null))
        assertFalse(vm.ui.value.pip.expanded)
        // テイクオーバーのみが自動的に展開されます。質問するときは、入力ボックスとオプションを目の前に置いてください。
        emit(TaskState.WaitingInput("选哪个?", listOf("A", "B")))
        assertFalse(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        assertTrue(vm.send("A", ok))
        assertEquals("answer:A", calls.last())
    }

    @Test fun startWaitsUntilServiceFinishedPreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
    }

    @Test fun stopWhileQueuedBehindServiceCancelsTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        vm.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.toString(), calls.isEmpty()) // は開始されておらず、仕上げプロセスの前のタスクもキャンセルされません。
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
    }

    // サービスが無料になるのを待っているとき、メッセージの下部に、前のタスクを待っていることが明確に示されます。これは、スタックしているようなものではありません。サービスに引き渡した後は、タスク自体の進行状況に置き換えられます。
    @Test fun waitingMessageSaysItWaitsForThePreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        val waiting = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, waiting.status)
        assertEquals("前のタスクが終わってから始まります", ChatCopy.progressLine(waiting))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
        emit(TaskState.Running(1, "打开设置", reason = "打开设置"))
        assertEquals("打开设置", ChatCopy.progressLine(vm.ui.value.exchanges.single()))
    }

    // 会話ページでサービスが空であることが確認されると、サービスが引き渡されます。引き渡す前に、別のサービスが別の場所 (タイミング、コンピューター) で開始されます。サービスは、このサービスをその後ろにランク付けします (失われないようにします)。
    // ダイアログにキューが表示されます。タスクが完了すると、このタスクは通常どおりに開始および終了します。
    private fun TestScope.raceVm(): ChatViewModel {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), MutableStateFlow(false),
            shownTask = shown, activeConversations = active).also { runCurrent() }
        assertTrue(vm.send("订会议室", ok))
        assertEquals(listOf("start:订会议室"), calls)
        val mine = vm.ui.value.conversationId!!
        // 他の場所のタスクが最初に開始され、サービスに表示されます: この会話を受信しないでください
        active.value = listOf("remote")
        shown.value = "remote" to TaskState.Running(3, "查快递")
        emit(TaskState.Running(3, "查快递"))
        assertEquals("订会议室", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // はその後ろでキューに入れられており、サービスにはそのキューのステータスが表示されます。
        val queued = TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务"))
        active.value = listOf("remote", mine)
        shown.value = mine to queued
        emit(queued)
        return vm
    }

    @Test fun messageQueuedBehindATaskStartedElsewhereStartsAfterIt() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        assertEquals(ExchangeStatus.Queued("排队中:前面还有 1 个任务"), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // そのタスクは終了しました。このタスクは解放され、完了するまで実行が開始されます。
        active.value = listOf(mine)
        shown.value = mine to TaskState.Running(1, "打开日历", reason = "打开日历")
        emit(TaskState.Running(1, "打开日历", reason = "打开日历"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        shown.value = mine to TaskState.Succeeded("订好了")
        emit(TaskState.Succeeded("订好了"))
        assertEquals("订好了", (vm.ui.value.exchanges.single().status as ExchangeStatus.Done).answer)
    }

    // キューイング時に停止: 会話のみを停止し、番号なしの停止は送信しません (サービスによって表示され、他の場所で開始されたタスクは停止します)。
    @Test fun stopWhileQueuedInServiceCancelsOnlyThisMessage() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        vm.cancel()
        assertEquals("cancel@$mine", calls.last())
        assertFalse("cancel" in calls)
    }

    @Test fun historyLoadsWhenOpeningConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        store.append(id, Turn("订票", "验证码过不去", "failed"))
        store.append(id, Turn("查余额", "余额 10 元\n依据:钱包页", "unverified", note = "防截屏"))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges
        assertEquals(listOf("查天气", "订票", "查余额"), ex.map { it.goal })
        assertEquals(ExchangeStatus.Done("余额 10 元", "钱包页", verified = false, note = "防截屏"), ex[2].status)
        assertEquals(ExchangeStatus.Done("晴", "天气 App"), ex[0].status)
        assertEquals(ExchangeStatus.Failed("验证码过不去"), ex[1].status)

        vm.send("再查一次", ok)
        assertEquals("start:再查一次", calls.last())
        assertEquals(id, vm.ui.value.conversationId)
    }

    @Test fun confirmationCardShowsButtons() = runTest {
        val vm = vm()
        assertTrue(vm.send("给张三发明天见", ok))
        emit(TaskState.Running(3, "打字", "phone_type_text"))
        did(3, "act", "phone_type_text", "com.chat", "changed")
        val waiting = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        emit(waiting)
        val ex = vm.ui.value.exchanges.last()
        assertEquals(ExchangeStatus.NeedsConfirmation("許可が必要: 送信", "聊天 で\n宛先: 张三\n内容: 明天见", ConfirmationNotifier.requestKey(waiting)), ex.status)
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals("あなたの許可を待っています", liveLine(ex))
        assertFalse(vm.ui.value.operatingPhone)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // は応答後に実行に戻ります
        emit(TaskState.Running(4, "", null))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    @Test fun approveCallsAction() = runTest {
        val vm = vm()
        // カード ボタンには、それが属する宣言が含まれています
        vm.approve(7)
        vm.decline(8)
        assertEquals(listOf("approve:7", "decline:8"), calls)
    }

    @Test fun endedTaskCarriesItsRunId() = runTest {
        val endedRunId = MutableStateFlow<String?>("r-before")
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r-old"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId)
        runCurrent()
        vm.openConversation(id)
        assertEquals("r-old", vm.ui.value.exchanges.single().runId)

        vm.send("查电量", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        assertNull(vm.ui.value.exchanges.last().runId) // は実行中は入力できません。
        // サービスは、最初に今回の番号を書き留めてから、終了を報告します。
        endedRunId.value = "r-new"
        emit(TaskState.Succeeded("85%"))
        assertEquals("r-new", vm.ui.value.exchanges.last().runId)

        // 後で到着した次のタスクの番号。終了したタスクはそれに応じて変更されません。
        vm.send("再查", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        endedRunId.value = "r-next"
        runCurrent()
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
        emit(TaskState.Failed("没找到", FailKind.GaveUp))
        assertEquals("r-next", vm.ui.value.exchanges[2].runId)
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
    }

    // 停止: その場で停止し、入力ボックスは空です。説明はまだ書かれており、タイムマークは「整理中」です。それを書き留めて対話に入力すると、それがこのラウンドの答えになります。
    @Test fun stoppedTurnShowsSummaryWhenItArrives() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.openConversation(id)
        vm.send("看四个设置页", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        vm.cancel()
        assertEquals("cancel", calls.last())
        // サービスは、最初に「編成」を登録し、番号を書き留め、次に終了を報告し、次に「キャンセル」ラウンドを書き留めます
        summarizing.value = setOf("r1")
        endedRunId.value = "r1"
        emit(TaskState.Cancelled)
        store.append(id, Turn("看四个设置页", "已取消", "cancelled", runId = "r1"))
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        store.updateTurn(id, "r1") { it.copy(outcome = "停止前看完了网络、蓝牙;显示、声音还没看。") }
        runCurrent()
        summarizing.value = emptySet()
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), ex.status)
        assertEquals("r1", ex.runId)
        // 会話を再開します: 保存されたコピーから説明を読みます
        vm.newConversation()
        vm.openConversation(id)
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), vm.ui.value.exchanges.single().status)
    }

    // 説明を書くことができません（または説明するものがありません）: 「編成」を削除して「停止」のみを残します
    @Test fun stoppedTurnWithoutSummaryDropsTheHint() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.send("看设置", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        summarizing.value = setOf("r2")
        endedRunId.value = "r2"
        emit(TaskState.Cancelled)
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        summarizing.value = emptySet()
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
    }

    @Test fun attachmentsGoWithTheNextMessage() = runTest {
        val sandbox = MutableStateFlow(false)
        val imported = mutableListOf<Pair<String, List<String>>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = sandbox,
            importAttachments = { id, uris ->
                imported += id to uris
                com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/报表 (1).csv" to 2048L), listOf("big.iso"))
            },
            describeAttachments = { uris -> uris.map { PendingAttachment(it, if (it.endsWith("1")) "报表.csv" else "big.iso", 10L) } },
        ).also { runCurrent() }
        // サンドボックスの準備ができていません: 添付ボタンもコレクションもありません
        assertFalse(vm.ui.value.attachEnabled)
        vm.attach(listOf("content://x/1"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())

        sandbox.value = true
        runCurrent()
        assertTrue(vm.ui.value.attachEnabled)
        // 選択すると、入力ボックスの上にのみ表示され、コピーされません。
        vm.attach(listOf("content://x/1", "content://x/2"))
        runCurrent()
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertTrue(imported.isEmpty())

        // は送信時にワークスペースにコピーされ、パスがメッセージに書き込まれます。
        assertTrue(vm.send("汇总一下", ok))
        runCurrent()
        val id = vm.ui.value.conversationId!!
        assertEquals(listOf(id to listOf("content://x/1", "content://x/2")), imported)
        assertEquals(listOf("start:汇总一下\n添付:/workspace/inbox/报表 (1).csv(2 KB)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals(listOf("big.iso"), vm.ui.value.rejectedAttachments)
        assertFalse(vm.ui.value.importing)
        // タスクの実行時に添付ファイルを受信しない
        vm.attach(listOf("content://x/3"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun attachmentsAloneCanBeSent() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 3L * 1024 * 1024), emptyList()) },
        ).also { runCurrent() }
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(listOf("start:添付:/workspace/inbox/a.jpg(3.0 MB)"), calls)
    }

    @Test fun nothingIsSentWhenEveryAttachmentFailsAndThereAreNoWords() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(emptyList(), listOf("huge.mov")) },
        ).also { runCurrent() }
        vm.toggleAttachment(PendingAttachment("content://m/1", "huge.mov", 1L))
        vm.send("", ok)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(listOf("huge.mov"), vm.ui.value.rejectedAttachments)
    }

    @Test fun pickingPhotosStopsAtTheLimit() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            describeAttachments = { uris -> uris.map { PendingAttachment(it, "p.jpg", 1L) } },
        ).also { runCurrent() }
        repeat(AttachTray.LIMIT) { assertTrue(vm.toggleAttachment(PendingAttachment("content://m/$it", "p.jpg", 1L))) }
        assertFalse(vm.toggleAttachment(PendingAttachment("content://m/x", "p.jpg", 1L)))
        // キャンセルするには選択したものをクリックしてください
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/0", "p.jpg", 1L)))
        assertEquals(AttachTray.LIMIT - 1, vm.ui.value.attachments.size)
        vm.attach(listOf("content://f/1", "content://f/2"))
        runCurrent()
        assertEquals(AttachTray.LIMIT, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
        vm.dismissRejected()
        assertEquals(0, vm.ui.value.overLimit)
    }

    @Test fun sharedFilesShowOnTheLiveExchangeAndAreRestored() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val store = ConversationStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedFiles = files).also { runCurrent() }
        vm.send("导出表格", ok)
        emit(TaskState.Running(1, "整理"))
        val f = com.wangzan101.mika.chat.SharedFile("表.xlsx", 5000, "Download/Mika/表.xlsx", "content://media/1")
        files.value = listOf(f)
        runCurrent()
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)
        emit(TaskState.Succeeded("好了\n依据: 已导出"))
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)

        val id = vm.ui.value.conversationId!!
        store.append(id, Turn("导出表格", "好了", "succeeded"), listOf(f))
        vm.openConversation(id)
        assertEquals(listOf(f), vm.ui.value.exchanges.single().files)
    }

    @Test fun sizeLabels() {
        assertEquals("12 B", ChatViewModel.sizeLabel(12))
        assertEquals("2 KB", ChatViewModel.sizeLabel(2048))
        assertEquals("1.5 MB", ChatViewModel.sizeLabel(1_572_864))
    }

    // ---- 並列タスク (ParallelTasks スイッチがオン) ----
    private val shown = MutableStateFlow<Pair<String, TaskState>?>(null)
    private val active = MutableStateFlow<List<String>>(emptyList())

    private fun TestScope.parallelVm(
        store: ConversationStore,
        files: MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>> = MutableStateFlow(emptyList()),
    ) = ChatViewModel(
        backgroundScope, state, stage, actions, store, serviceBusy = MutableStateFlow(true),
        parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = { if (it == "b") "查快递" else null },
        sharedFiles = files,
    ).also { runCurrent() }

    private fun TestScope.show(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        runCurrent()
    }

    /** 画面上のこのダイアログを削除します。その場で新しい空のダイアログに置き換えます。サイド列にはリストされなくなり、削除はバックグラウンドに残ります。 */
    @Test fun deletingTheOpenConversationShowsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { removed += it; store.delete(it) }).also { runCurrent() }
        vm.openConversation("a")
        assertEquals(1, vm.ui.value.exchanges.size)

        vm.deleteConversation("a")
        runCurrent()
        assertEquals(listOf("a"), removed)
        assertEquals(setOf("a"), vm.deleted.value)
        assertNull(vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        // 他の会話を削除しました: 画面は動きません
        vm.openConversation("b")
        vm.deleteConversation("x")
        runCurrent()
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(listOf("订票"), vm.ui.value.exchanges.map { it.goal })
    }

    /** 名前を変更してサイドバーの一番上に固定します。保存し、保存したものに従ってサイドバーを再配置します (一番上のものが「トップ」セクションにあり、最後に固定されたものが前面にあります)。削除された会話は変更されなくなります。 */
    @Test fun renameAndPinReorderTheDrawer() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("写周报", "好了", "succeeded"))
        var now = 1_000L
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, clock = { now }, removeConversation = { store.delete(it) }).also { runCurrent() }
        fun sections() = drawerSections(store.list(), CurrentConversation(null, false, "", ""), emptyList(), vm::runningGoal, vm.canSwitch(), 0L)

        vm.renameConversation("b", " 回家的票 ")
        vm.setPinned("c", true)
        now = 2_000L
        vm.setPinned("b", true)
        val s = sections()
        assertEquals(listOf("回家的票", "写周报"), s.pinned.map { it.title })
        assertEquals(listOf("a"), s.recent.map { it.id })

        vm.setPinned("b", false)
        assertEquals(listOf("c"), sections().pinned.map { it.id })
        assertEquals(setOf("a", "b"), sections().recent.mapNotNull { it.id }.toSet())

        vm.deleteConversation("c")
        runCurrent()
        vm.renameConversation("c", "不该写进去")
        vm.setPinned("c", true)
        assertNull(store.title("c"))
        assertNull(store.pinnedAt("c"))
    }

    /** 誰かが削除された会話を再度保存しました (サスペンション カプセルはその番号で発言しました): サイドバーに再リストします。 */
    @Test fun aDeletedConversationThatIsWrittenAgainIsListedAgain() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { store.delete(it) }).also { runCurrent() }
        vm.deleteConversation("a")
        runCurrent()
        assertEquals(setOf("a"), vm.deleted.value)
        store.append("a", Turn("再来", "好", "succeeded"))
        runCurrent()
        assertTrue(vm.deleted.value.isEmpty())
    }

    /** 実行中のタスクの画面上のダイアログを並行して削除します。そのタスクには従わなくなり、その後のステータスは新しいダイアログに含まれなくなります。 */
    @Test fun deletingARunningOpenConversationStopsFollowingIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = parallelVm(store)
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        active.value = listOf("a")
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.deleteConversation("a")
        runCurrent()
        val fresh = vm.ui.value.conversationId
        assertTrue(fresh != null && fresh != "a")
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
        show("a", TaskState.Running(2, "看屏幕"))
        show("a", TaskState.Cancelled)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(fresh, vm.ui.value.conversationId)
    }

    @Test fun otherRunningExcludesCurrent() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        vm.switchTo("a")
        active.value = listOf("a", "b", "c")
        runCurrent()
        assertEquals(listOf("b", "c"), vm.otherRunning.value)
        vm.switchTo("b")
        runCurrent()
        assertEquals(listOf("a", "c"), vm.otherRunning.value)
    }

    @Test fun switchToLoadsThatConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        val vm = parallelVm(store)
        // ダイアログ a のタスクが実行中です
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // ダイアログ b 実行中のタスクもあります。それに切り替えてサービスに最初に表示させ、履歴をロードして現在のステータスに接続します。
        active.value = listOf("a", "b")
        show("b", TaskState.Running(2, "看屏幕"))
        vm.switchTo("b")
        assertEquals("b", vm.ui.value.conversationId)
        assertTrue("show:b" in calls)
        assertEquals(listOf("查天气", "查快递"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        // a のステータスは b の会話に入りません
        show("a", TaskState.Succeeded("done"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)

        // 画面は b: 最初に表示を変更せずに b へのタスクのみの送信を停止します。
        calls.clear()
        vm.cancel()
        assertEquals(listOf("cancel@b"), calls)

        // タスクなしの新しいダイアログ: 他のタスクの実行中に新しいタスクを開始でき、サービスが空くまで待つ必要はありません。
        vm.newConversation()
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    private val learnCalls = mutableListOf<String>()
    private val savedNotes = mutableListOf<com.wangzan101.mika.memory.RunNotes>()
    private val learning = object : LearningActions {
        override suspend fun undo(token: String): Boolean { learnCalls += "undo:$token"; return token != "expired" }
        override suspend fun approve(pendingId: String): Boolean { learnCalls += "approve:$pendingId"; return true }
        override suspend fun forget(memoryId: String): Boolean { learnCalls += "forget:$memoryId"; return true }
        override fun viewSkill(draftId: String) { learnCalls += "view:$draftId" }
        override suspend fun restoreSkill(name: String) { learnCalls += "restore:$name" }
        override suspend fun deleteSkill(name: String) { learnCalls += "delete:$name" }
        override suspend fun save(notes: com.wangzan101.mika.memory.RunNotes) { savedNotes += notes }
    }
    private val notesFlow = MutableStateFlow<Map<String, com.wangzan101.mika.memory.RunNotes>>(emptyMap())
    private fun item(id: String, content: String) = com.wangzan101.mika.core.memory.MemoryItem(
        id, com.wangzan101.mika.core.memory.MemoryKind.preference, content, emptyList(),
        com.wangzan101.mika.core.memory.MemorySource("manual", atMs = 0), com.wangzan101.mika.core.memory.MemoryStatus.active, createdAt = 0, updatedAt = 0,
    )
    private val items = MutableStateFlow(listOf(item("a", "用户点外卖不要香菜"), item("b", "用户喝咖啡不加糖"), item("p1", "点外卖先比价")))

    private fun TestScope.vmWithHistory(): ChatViewModel {
        val store = ConversationStore(tmp.newFolder())
        store.append("c1", Turn("点外卖", "已下单\n依据: 订单页", "succeeded", runId = "r1"))
        return ChatViewModel(backgroundScope, state, stage, actions, store, runNotes = notesFlow, learning = learning, memoryItems = items).also {
            runCurrent(); it.openConversation("c1"); runCurrent()
        }
    }

    private val remembered = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "a", "t1", null)
    private val procedure = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.pending, "p1", "t2", "p1")

    @Test fun memoryNotesShownUnderResult() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered)))
        runCurrent()
        val note = vm.ui.value.exchanges.single().memoryNotes.single()
        assertEquals("覚えました:用户点外卖不要香菜", vm.noteText(note))
        // コンテンツの取得: そのアイテムを削除した後、「削除されたメモリ」を書き込みます
        items.value = items.value.filter { it.id != "a" }
        assertEquals("覚えました:削除済みのメモリ", vm.noteText(note))
        // 会話を再度開くときに、保存されたラウンドから読み戻します (タイプと番号のみが保存されます)
        val store = ConversationStore(tmp.newFolder())
        store.append("c2", Turn("点外卖", "ok", "succeeded", runId = "r2", memoryNotes = listOf(remembered), usedMemory = listOf("a")))
        val vm2 = ChatViewModel(backgroundScope, state, stage, actions, store).also { runCurrent(); it.openConversation("c2"); runCurrent() }
        assertEquals(listOf(remembered), vm2.ui.value.exchanges.single().memoryNotes)
    }

    @Test fun undoRemovesNote() = runTest {
        val vm = vmWithHistory()
        val expired = remembered.copy(undoToken = "expired")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered, expired)))
        runCurrent()
        vm.undoNote("r1", remembered); runCurrent()
        assertEquals(listOf("undo:t1"), learnCalls)
        assertEquals(listOf(expired), vm.ui.value.exchanges.single().memoryNotes)
        // トークンの無効化: プロンプトは削除せず、「取り消し」のみを削除してください。
        vm.undoNote("r1", expired); runCurrent()
        assertEquals(listOf(expired.copy(undoToken = null)), vm.ui.value.exchanges.single().memoryNotes)
        assertEquals(listOf(expired.copy(undoToken = null)), savedNotes.last().memoryNotes)
    }

    @Test fun pendingProcedureShowsYesNo() = runTest {
        val vm = vmWithHistory()
        val second = procedure.copy(undoToken = "expired", pendingId = "p2", memoryId = "p2")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(procedure, second)))
        runCurrent()
        assertEquals("今後もこのようにしますか？点外卖先比价", vm.noteText(procedure))
        vm.approveNote("r1", procedure); runCurrent()
        assertEquals(listOf("approve:p1"), learnCalls)
        // 「不要」の場合、トークンの有効期限が切れています: そのメソッドを直接削除してください すべてクリアすると、
        vm.rejectNote("r1", vm.ui.value.exchanges.single().memoryNotes.single()); runCurrent()
        assertEquals(listOf("approve:p1", "undo:expired", "forget:p2"), learnCalls)
        assertTrue(vm.ui.value.exchanges.single().memoryNotes.isEmpty())
    }

    @Test fun usedMemoryCountShown() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", usedMemory = listOf("a", "b")))
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("用户点外卖不要香菜", "用户喝咖啡不加糖"), vm.usedMemoryTexts(ex.usedMemory))
        // はリストされなくなります (それに応じて、インターフェイスには「n メモリ使用中」は表示されません)。
        items.value = emptyList()
        assertTrue(vm.usedMemoryTexts(ex.usedMemory).isEmpty())
    }

    @Test fun suspendedSkillRestoreAndView() = runTest {
        val vm = vmWithHistory()
        val learned = com.wangzan101.mika.core.skills.SkillNote("Mika 学会了一项技能:发微信", draftId = "d1")
        val suspended = com.wangzan101.mika.core.skills.SkillNote("这项技能最近不灵,先停用了", suspended = "wechat-msg")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", skillNotes = listOf(learned, suspended)))
        runCurrent()
        vm.viewSkill(learned)
        vm.restoreSkill("r1", suspended); runCurrent()
        assertEquals(listOf("view:d1", "restore:wechat-msg"), learnCalls)
        assertEquals(listOf(learned), vm.ui.value.exchanges.single().skillNotes)
    }

    // タスクのないダイアログでボタンを押しても、他のダイアログのタスクに分類されることはありません。押すと、その瞬間の画面にダイアログが表示されます。
    @Test fun buttonsInConversationWithoutRunGoNowhere() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        show("a", TaskState.WaitingInput("哪个?", null))
        vm.switchTo("b")
        calls.clear()
        vm.cancel(); vm.resume(); vm.answer("第一个"); vm.approve(7)
        assertTrue(calls.isEmpty())

        vm.switchTo("a")
        calls.clear()
        vm.resume()
        vm.switchTo("b")
        assertEquals(listOf("resume@a"), calls.filter { "@" in it })
    }

    // は、進行状況が画面上のダイアログにのみ関係するタスクを引き継ぎます。タスクのないダイアログに切り替えると、それがクリアされます。
    @Test fun takeoverStageFollowsScreenConversation() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        vm.switchTo("a")
        show("a", TaskState.WaitingTakeover("login", "请登录"))
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)
        vm.switchTo("b")
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        stage.value = Takeover.Stage.Waiting
        runCurrent()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        vm.newConversation()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
    }

    // エクスポートされたファイルと成功基準は、各タスク独自のものです。サービスが他の会話のタスクを表示する場合、画面上のこの会話のカードにはハングしません。
    @Test fun sharedFilesFollowScreenConversation() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val vm = parallelVm(ConversationStore(tmp.newFolder()), files)
        active.value = listOf("a", "b")
        vm.switchTo("a")
        show("a", TaskState.Running(1, "整理"))
        val mine = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Mika/a.csv", "content://media/1")
        files.value = listOf(mine)
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
        show("b", TaskState.Running(1, "查快递"))
        files.value = listOf(com.wangzan101.mika.chat.SharedFile("b.csv", 1, "Download/Mika/b.csv", "content://media/2"))
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
    }

    // 各会話カードは、サービスがその時点でどのタスクを表示しているかに関係なく、独自の実行番号を使用します。
    @Test fun endedCardUsesItsConversationsRunId() = runTest {
        val ended = MutableStateFlow<Map<String, String?>>(emptyMap())
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunId = MutableStateFlow("r-shown-other"), endedRunIds = ended,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        // 他のダイアログのタスクは終了し、独自の名前でのみ記録されます
        ended.value = mapOf("b" to "r-b")
        runCurrent()
        assertNull(vm.ui.value.exchanges.last().runId)
        ended.value = mapOf("b" to "r-b", "a" to "r-a")
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // 実行中は、実行中の番号を切ります (「実行中のプロセス」はその場で入力できます)。カードが配置されているダイアログをクリックします。サービス表示内のダイアログは使用しないでください。
    @Test fun runningCardUsesItsOwnConversationsLiveRunId() = runTest {
        val live = mutableMapOf<String, String>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunIds = MutableStateFlow(mapOf("a" to "r-a")), liveRunId = { live[it] },
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        live["b"] = "r-b"
        show("a", TaskState.Running(0, "正在准备"))
        assertNull("この会話のタスクにはまだ ID がないため、別の会話の ID を使わない", vm.ui.value.exchanges.last().runId)
        live["a"] = "r-a"
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // スイッチはインターフェイスの構築後にオンになります。タスクの開始時に再読み込みされ、インターフェイスを再構築する必要はありません。
    @Test fun parallelFlagReadWhenStarting() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        flag = true
        assertTrue(vm.send("设置版本号", ok))
        assertEquals("start:设置版本号", calls.last())
        val first = vm.ui.value.conversationId
        active.value = listOf(first!!)
        runCurrent()
        // は新しい会話で 2 番目の会話を開くことができます
        vm.newConversation()
        assertTrue(vm.ui.value.conversationId != first)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    // ダイアログ変更時にスイッチも再読み込み
    @Test fun parallelFlagReadWhenSwitching() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        active.value = listOf("b")
        runCurrent()
        flag = true
        vm.openConversation("b")
        // タスクが並行して実行されているダイアログに切り替えると、サービスによってダイアログが表示されます。
        assertTrue("show:b" in calls)
    }

    // 画面の有無もタスクごとに異なります。表示中のタスクが別の対話タスクの場合、このカードは入力されません。
    @Test fun otherConversationsScreenStaysOut() = runTest {
        val screen = MutableStateFlow(true)
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        // 他のダイアログ タスクの画面が開いています。新しいタスクには画面がなく、ピクチャ イン ピクチャも表示されません。
        assertTrue(vm.send("设置版本号", ok))
        assertFalse(vm.ui.value.pip.visible)
        show("b", TaskState.Running(1, "看屏幕"))
        runCurrent()
        show("a", TaskState.Running(1, "打开设置"))
        runCurrent()
    }

    // 実マシン上のインターフェイス スレッドはすぐにスケジュールされます。サービスの表示が変更されるとすぐに、ステータスがその場で送信されます。一旦会話を切り上げてから、また会話を切り戻します。
    // 質問と回答の元の文言、手順 (締め切り期間を含む)、および文書がそこに存在する必要があり、別の空の元の文を開始することはできません。
    @Test fun switchingBackRestoresTheRunningExchange() = runTest {
        val runStates = mapOf("a" to MutableStateFlow<TaskState>(TaskState.Running(0, "")), "b" to MutableStateFlow<TaskState>(TaskState.Running(0, "")))
        val fileA = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Mika/a.csv", "content://media/1")
        val runFiles = mapOf("a" to listOf(fileA), "b" to emptyList())
        val goals = mapOf("a" to "第一个任务", "b" to "第二个任务")
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        // サービスと同じ: 表示を変更するときは、最初にファイルをコピーしてから、会話番号のステータスをコピーします
        val immediate = object : TaskActions by actions {
            override fun show(conversationId: String) {
                files.value = runFiles.getValue(conversationId)
                shown.value = conversationId to runStates.getValue(conversationId).value
            }
        }
        fun publish(c: String, s: TaskState) {
            runStates.getValue(c).value = s
            if (shown.value?.first == c) shown.value = c to s
        }
        val ui = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val vm = ChatViewModel(
            ui, state, stage, immediate, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = goals::get, runState = runStates::get,
            sharedFiles = files,
        )
        vm.switchTo("a")
        assertTrue(vm.send("第一个任务", ok))
        active.value = listOf("a")
        immediate.show("a")
        publish("a", TaskState.Running(1, "打开设置", "phone_launch_app"))

        // タスクなしで b にカットし、2 番目のタスクを開始します。a の質疑応答を停止し、a は実行を継続します。
        vm.switchTo("b")
        assertTrue(vm.send("第二个任务", ok))
        active.value = listOf("a", "b")
        immediate.show("b")
        publish("b", TaskState.Running(1, "看屏幕"))
        publish("a", TaskState.Running(2, "点版本号", "phone_tap", reason = "点版本号"))
        assertEquals("第二个任务", vm.ui.value.exchanges.last().goal)

        vm.switchTo("a")
        val ex = vm.ui.value.exchanges.last()
        assertEquals("第一个任务", ex.goal)
        assertEquals(listOf(fileA), ex.files)
        // カットオフ期間中のaの進捗はまだ記憶されています
        assertEquals("点版本号", ex.line)
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    @Test fun withdrawRemovesOnlyTheUnprocessedMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("撤回这句", ok)
        vm.send("留下这句", ok)
        val id = steerIds.first()
        assertTrue(vm.withdrawSteer(id))
        assertEquals(listOf(SteerState.Withdrawn, SteerState.Pending), vm.ui.value.exchanges.single().steers.map { it.state })
        assertFalse(vm.withdrawSteer(id))
        assertEquals(1, calls.count { it == "withdraw:$id" })
        assertEquals("留下这句", vm.ui.value.exchanges.single().steers.last().text)
    }

    @Test fun editingRestoresQuoteAndTextWithoutOverwritingAnotherQuote() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        val quote = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有雨")
        vm.quote(quote.from, quote.text)
        vm.send("改这句话", ok)
        val id = steerIds.single()
        vm.quote(QuoteMessage.From.You, "正在写的引用")
        assertNull(vm.editSteer(id))
        vm.clearQuote()
        assertEquals("改这句话", vm.editSteer(id))
        assertEquals(quote, vm.ui.value.quote)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
        vm.send("改好的话", ok)
        assertEquals("steer:${QuoteMessage.compose(quote, "改好的话")}", calls.last())
    }

    @Test fun failedWithdrawalDoesNotPretendTheModelMissedTheMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("模型刚取走", ok)
        val id = steerIds.single()
        canWithdraw = false
        assertNull(vm.editSteer(id))
        assertEquals(SteerState.Pending, vm.ui.value.exchanges.single().steers.single().state)
        assertNull(vm.ui.value.quote)
        steerFlow.value = listOf(SteerNote(id, "模型刚取走", SteerState.Joined))
        runCurrent()
        assertFalse(vm.withdrawSteer(id))
        assertEquals(SteerState.Joined, vm.ui.value.exchanges.single().steers.single().state)
    }

    // パソコンで追加した場合は会話にも表示されますが、長押ししても撤回や編集はされず、直接転送の場合は撤回されず、サービスも求められません。
    @Test fun remoteSteersOfferNoWithdrawOrEdit() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("手机插话", ok)
        val local = steerIds.single()
        steerFlow.value = listOf(SteerNote(local, "手机插话", SteerState.Received), SteerNote("r", "电脑追加", SteerState.Received, remote = true))
        runCurrent()
        val exchanges = vm.ui.value.exchanges
        assertEquals(listOf(false, true), exchanges.single().steers.map { it.remote })
        assertNull(withdrawableSteer(exchanges, "m0-r"))
        assertEquals(local, withdrawableSteer(exchanges, "m0-$local")?.id)
        assertFalse(vm.withdrawSteer("r"))
        assertNull(vm.editSteer("r"))
        assertFalse(calls.any { it == "withdraw:r" })
        assertEquals(SteerState.Received, vm.ui.value.exchanges.single().steers.last().state)
    }

    // フローティング カプセルには、画面とページの説明が記載されたブラウザ ページが付属しています。これを挿入すると、ダイアログにも表示されます。エディターを取得すると、マークされた元のテキストが入力ボックスに入力されます。削除されるだけであり、編集はされません。
    @Test fun steersWithAScreenOrAPageCanOnlyBeWithdrawn() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        val screen = UserScreenMessage.compose("s1", "这个按钮是干什么的")
        val page = AttachedPage.compose(AttachedPage.Page("https://example.com/a", "标题", "选中的一段"), "这段什么意思")
        steerFlow.value = listOf(SteerNote("s", screen, SteerState.Received), SteerNote("p", page, SteerState.Received))
        runCurrent()
        assertEquals("s", withdrawableSteer(vm.ui.value.exchanges, "m0-s")?.id)
        assertFalse(editableSteer(screen))
        assertFalse(editableSteer(page))
        assertNull(vm.editSteer("s"))
        assertNull(vm.editSteer("p"))
        assertFalse(calls.any { it.startsWith("withdraw:") })
        assertTrue(vm.withdrawSteer("p"))
        assertEquals(listOf(SteerState.Received, SteerState.Withdrawn), vm.ui.value.exchanges.single().steers.map { it.state })
        // 編集できるのは単語と引用のみです。
        assertTrue(editableSteer("改这句话"))
        assertTrue(editableSteer(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有雨"), "改这句话")))
    }

    @Test fun withdrawingBeforeTheTaskStartsPreventsLaterDelivery() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = busy)
        runCurrent()
        vm.send("当前任务", ok)
        vm.send("别发这句", ok)
        val id = vm.ui.value.exchanges.single().steers.single().id
        assertTrue(vm.withdrawSteer(id))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:当前任务"), calls)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
    }

    // タスクは、実行時に入力ボックスで送信することもできます。送信すると、この質問と回答の下でハングされ、現在のタスクに渡されます。宛先はサービスによって報告されます
    @Test fun messageWhileRunningSteersTheTask() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        assertTrue(vm.send("改成后天", ok))
        assertEquals("steer:改成后天", calls.last())
        var ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("改成后天"), ex.steers.map { it.text })
        assertNull(steerLabel(ex.steers.single().state))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // モデルは次のステップに進む前にこの文を取得しました
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Joined))
        runCurrent()
        assertEquals("実行中のタスクに追加しました", steerLabel(vm.ui.value.exchanges.single().steers.single().state))

        // 別の文を言うとミッションは終了します: この文は次のこととしてミッションには引き継がれません。
        assertTrue(vm.send("顺便订午饭", ok))
        emit(TaskState.Succeeded("订好了"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(listOf(SteerState.Joined, SteerState.Next), ex.steers.map { it.state })
        assertEquals("次のタスクとして待機しています", steerLabel(ex.steers.last().state))
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // は作成中の応答を中断しました。段落は中断の方向とともに報告され、中断を保留しました。中断後もステータスは失われません。
    @Test fun interruptedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高"))
        assertTrue(vm.send("改成查明天", ok))

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))
        emit(TaskState.Running(2, ""))
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals("今天晴,最高", note.interrupted)
        assertEquals(SteerState.Joined, note.state)

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高", vm.ui.value.exchanges.single().steers.single().interrupted)
    }

    // タスクが受信箱に受信されるとすぐに、「受信しました、次のステップ」という応答が返されます。タスクが完了すると、まだ送信されていないため、次のタスクとして扱われます。
    @Test fun receivedSteerIsShownAtOnceAndBecomesNextIfTheRunEnds() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals(SteerState.Received, note.state)
        assertEquals("了解です — 次のステップへ", steerLabel(note.state))
        emit(TaskState.Succeeded("订好了"))
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun stopCancelsReceivedSteersToo() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        vm.cancel()
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
    }

    // サービスは完了後に報告されます。 To: 引き続きその質問と回答に変更します
    @Test fun lateSteerOutcomeStillLandsOnItsExchange() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.send("只看顺丰的", ok)
        emit(TaskState.Failed("网络断了", FailKind.Environment))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "只看顺丰的", SteerState.Next))
        runCurrent()
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    // アクションが失敗した場合、失敗したことだけが表示され、エラー コードは他の人には表示されません。
    @Test fun failedActionHidesErrorCode() = runTest {
        val vm = vm()
        vm.send("发消息", ok)
        emit(TaskState.Running(1, "", "phone_tap"))
        did(1, "act", "phone_tap", "com.chat", "ref_expired", failed = true)
        val row = vm.ui.value.exchanges.single().steps.single()
        assertEquals("タップ · 聊天", row.title)
        assertEquals("失敗", row.subtitle)
        assertFalse(row.toString().contains("ref_expired"))
    }

    // 元の呼び出しのタスクはまだサービスが完了するのを待っています。最後の割り込みは、最初に元の呼び出しのタスクを開始し、次に割り込みをそれに渡します。
    @Test fun steerDuringPendingStartIsSentAfterTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy, steers = steerFlow)
        runCurrent()
        assertTrue(vm.send("订会议室", ok))
        assertTrue(vm.send("要大一点的", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.single().steers.map { it.text })
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:订会议室", "steer:要大一点的"), calls)
    }

    // 停止すると、タスクに渡されていない割り込みは無効になり、キャンセル済みとしてマークされます。サービスを待っている間に停止すると、割り込みは送信されません。
    @Test fun stopCancelsPendingSteers() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.send("只看顺丰的", ok)
        vm.cancel()
        assertEquals("cancel", calls.last())
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
        assertEquals("キャンセルしました", steerLabel(SteerState.Cancelled))
        emit(TaskState.Cancelled)
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)

        val busy = MutableStateFlow(true)
        calls.clear()
        val vm2 = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        vm2.send("订会议室", ok)
        vm2.send("要大一点的", ok)
        vm2.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(SteerState.Cancelled, vm2.ui.value.exchanges.single().steers.single().state)
    }

    // 次は介入が行われる可能性があります: 新しいタスクを開くのと同じチェックに合格する
    @Test fun steerIsCheckedLikeANewTask() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertFalse(vm.send("只看顺丰的", Prerequisites.check(ready, hasMainModel = false)))
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    // 引用符は次の文とともに送信されます。新しいタスクとコメントが含まれており、送信後にクリアされます。言葉のない引用のみは送信されません。
    @Test fun quoteGoesWithTheNextMessage() = runTest {
        val vm = vm()
        vm.quote(QuoteMessage.From.Mika, "  明天有小雨 ")
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有小雨"), vm.ui.value.quote)
        assertFalse(vm.send("  ", ok))
        assertTrue(calls.isEmpty())

        assertTrue(vm.send("那要带伞吗", ok))
        val goal = "> [Quoting your earlier reply]\n> 明天有小雨\n\n那要带伞吗"
        assertEquals(listOf("start:$goal"), calls)
        assertEquals(goal, vm.ui.value.exchanges.single().goal)
        assertNull(vm.ui.value.quote)

        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.quote(QuoteMessage.From.You, "查天气")
        assertTrue(vm.send("改成后天", ok))
        val steer = "> [Quoting my earlier message]\n> 查天气\n\n改成后天"
        assertEquals("steer:$steer", calls.last())
        assertEquals(steer, vm.ui.value.exchanges.single().steers.single().text)
        assertNull(vm.ui.value.quote)

        // 引用を削除: 通常どおり単語を送信するだけです
        vm.quote(QuoteMessage.From.Mika, "x")
        vm.clearQuote()
        vm.send("再快一点", ok)
        assertEquals("steer:再快一点", calls.last())
    }

    // 参照は、画面上のこのダイアログにのみ属します。ダイアログを変更するとき、または新しいダイアログを開くときにクリアします。
    @Test fun quoteIsDroppedWhenTheConversationChanges() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.quote(QuoteMessage.From.Mika, "晴")
        vm.openConversation(id)
        assertNull(vm.ui.value.quote)
        vm.quote(QuoteMessage.From.Mika, "晴")
        vm.newConversation()
        assertNull(vm.ui.value.quote)
    }

    // 引用符付きで保存された単語は、再度開くとそのまま表示されます (インターフェイスはそれに応じて引用符を描画します)。古い言葉には引用符がありません。
    @Test fun quotedTurnsSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val quoted = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "晴"), "那后天呢")
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.append(id, Turn(quoted, "多云", "succeeded", steers = listOf(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "查天气"), "顺便看风"))))
        val vm = vm(store)
        vm.openConversation(id)
        val (old, new) = vm.ui.value.exchanges
        assertNull(QuoteMessage.parse(old.goal).quote)
        assertEquals("查天气", QuoteMessage.parse(old.goal).words)
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mika, "晴"), QuoteMessage.parse(new.goal).quote)
        assertEquals("那后天呢", QuoteMessage.parse(new.goal).words)
        assertEquals("顺便看风", QuoteMessage.parse(new.steers.single().text).words)
        assertEquals("查天气", ChatCopy.title(vm.ui.value.exchanges))
    }

    // タスクに割り当てられた割り込みは、そのラウンドとともに保存されます。会話を再開した後も、そのラウンドの下に表示されます。
    @Test fun joinedSteersSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("订会议室", "订好了", "succeeded", steers = listOf("要大一点的")))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("要大一点的"), ex.steers.map { it.text })
        assertEquals("実行中のタスクに追加しました", steerLabel(ex.steers.single().state))
    }

    // 割り込みによって中断された応答もそのラウンドとともに保存され、再開時にもその割り込みに添付されたままになります。中断されなかった応答は中断されません。
    @Test fun interruptedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.interrupted })
    }

    // 中断する前に完了した応答: 中断の方向を報告します。ステータスは保存後に失われません。保存して再度開いた後も、割り込みはハングアップしたままになります。
    @Test fun finishedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高 25 度。"))
        assertTrue(vm.send("改成查明天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, finished = "今天晴,最高 25 度。"))
        emit(TaskState.Running(2, ""))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高 25 度。", vm.ui.value.exchanges.single().steers.single().finished)
    }

    @Test fun finishedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), finishedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.finished })
    }

    // ---- 他の場所で開始されたタスク (中断されたカプセル、時間指定されたタスク) と保存されたラウンド ----

    /** スイッチはオフになっており、サービス ステータスには会話番号 ([shown]) が含まれています。 */
    private fun TestScope.taggedVm(store: ConversationStore, endedRunId: MutableStateFlow<String?> = MutableStateFlow(null)) = ChatViewModel(
        backgroundScope, state, stage, actions, store, endedRunId = endedRunId,
        shownTask = shown, activeConversations = active, runGoal = { if (it == "a") "胶囊里说的" else null }, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** サービスに表示されるタスクのステータスが変更されました。サービス内と同様に、番号付きのコピーが最初に書き込まれます。 */
    private fun TestScope.serve(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        state.value = s
        runCurrent()
    }

    // 他の場所で実行した後、この会話を画面に保存します。その場で仲直りします。1 つだけです
    @Test fun turnSavedElsewhereAppearsOnce() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        store.append("a", Turn("订会议室", "订好了", "succeeded", runId = "r2", steers = listOf("要大一点的")))
        runCurrent()
        assertEquals(listOf("查天气", "订会议室"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.last().steers.map { it.text })
        // 次の変更ラウンド (思い出のヒントの追加): 再読み込み、1 つだけ
        store.updateTurn("a", "r2") { it.copy(outcome = "订好了 3 楼") }
        runCurrent()
        assertEquals(2, vm.ui.value.exchanges.size)
        assertEquals("订好了 3 楼", (vm.ui.value.exchanges.last().status as ExchangeStatus.Done).answer)
    }

    // 別の会話の順番を保存し、この会話を画面に表示したままにします
    @Test fun turnSavedInAnotherConversationLeavesScreenAlone() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        val before = vm.ui.value
        store.append("b", Turn("订会议室", "订好了", "succeeded", runId = "r2"))
        runCurrent()
        assertEquals(before, vm.ui.value)
    }

    // これは、タスクの終了と入金ラウンドに関するダイアログです。最初に保存するか最初に報告するかに関係なく、ステップは 1 つだけです。ここでの手順をそのままにしておきます。
    @Test fun ownRunSavedIsNotDuplicated() = runTest {
        val ended = MutableStateFlow<String?>(null)
        for (savedFirst in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            val vm = taggedVm(store, ended)
            assertTrue(vm.send("查快递", ok))
            val id = vm.ui.value.conversationId!!
            serve(id, TaskState.Running(1, "", "phone_launch_app"))
            did(1, "act", "phone_launch_app", "com.chat")
            val turn = Turn("查快递", "到了", "succeeded", runId = "r-$savedFirst")
            ended.value = "r-$savedFirst"
            if (savedFirst) { store.append(id, turn); runCurrent() }
            serve(id, TaskState.Succeeded("到了"))
            if (!savedFirst) { store.append(id, turn); runCurrent() }
            val ex = vm.ui.value.exchanges.single()
            assertEquals("r-$savedFirst", ex.runId)
            assertTrue(ex.live)
            assertEquals(1, ex.steps.size)
            assertEquals(ComposerMode.Idle, vm.ui.value.composer)
            acts.value = emptyList()
        }
    }

    // 画面上のこの会話の他の場所でタスクが開始されました。その後表示されます。元の単語はタスク自身のものであり、他の場所に挿入された単語もハングアップされます。最後が救われた後は一つだけ
    @Test fun runStartedElsewhereInThisConversationIsFollowed() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val ended = MutableStateFlow<String?>(null)
        val vm = taggedVm(store, ended)
        vm.openConversation("a")
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        steerFlow.value = listOf(SteerNote("m1", "只看顺丰的", SteerState.Joined))
        runCurrent()
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
        ended.value = "r2"
        serve("a", TaskState.Succeeded("好了"))
        store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2", steers = listOf("只看顺丰的")))
        runCurrent()
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
    }

    // タスクが実行中であり、ここでフォローされていない会話を開く (または戻る): 次のステータスの変化を待たずに、その場で選択します。
    @Test fun openingOrRefreshingAttachesActiveRun() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = taggedVm(store)
        active.value = listOf("a")
        shown.value = "a" to TaskState.Running(2, "看屏幕")
        vm.openConversation("a")
        assertTrue("show:a" in calls)
        assertEquals("胶囊里说的", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)

        // フロントデスクに戻ったらもう一度答えてください: すでにフォローしています。別のアカウントを開始しないでください
        vm.refresh()
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // スイッチがオフになると、他のダイアログのタスクは画面上のこのダイアログに接続されません。ここでフォローしているタスクを置き換えて表示するとサービス表示に戻ります。
    @Test fun serialRunInAnotherConversationIsNotAttached() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        vm.openConversation("a")
        active.value = listOf("b")
        serve("b", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        serve("b", TaskState.Succeeded("好了"))
        assertEquals(1, vm.ui.value.exchanges.size)

        assertTrue(vm.send("查快递", ok))
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "", "phone_launch_app"))
        calls.clear()
        // スケジュールされたタスクはダイアログ b でスケジュールされており、サービスはそれを表示します
        active.value = listOf("a", "b")
        serve("b", TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务")))
        assertEquals(listOf("show:a"), calls)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    // ダイアログがない場合に受信したタスク: そのダイアログの保存されたラウンドと一緒に表示され、後で保存されたものも一致します
    @Test fun adoptingARunLoadsItsConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // ---- 他の場所で開始されたタスクは、サービス内の実際の順序に従います。最初に表示され (ステータスが送信され)、次にタスク リストに登録されます ----

    private val goals = mutableMapOf<String, String>()
    private val endedRun = MutableStateFlow<String?>(null)

    private fun TestScope.registryVm(store: ConversationStore, parallel: Boolean) = ChatViewModel(
        backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it }, endedRunId = endedRun, parallelFlag = { parallel },
        shownTask = shown, activeConversations = active, runGoal = goals::get, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** サービスはタスクを開始します。タスクは最初に表示され（番号付きステータス、ステータス）、次にタスクリストに登録されます（ダイアログ、元の単語）。 */
    private fun TestScope.startedElsewhere(conversationId: String, goal: String) {
        val s = TaskState.Running(0, "正在准备")
        shown.value = conversationId to s
        state.value = s
        runCurrent()
        goals[conversationId] = goal
        active.value = active.value + conversationId
        runCurrent()
    }

    // この会話の他の場所 (中断されたカプセル) に投稿された単語が画面上にある場合: その文とステップは、ここに投稿されたものと同様に、ランニング中に表示されます。
    @Test fun runStartedElsewhereShowsMessageWhileRunning() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("a", "胶囊里说的")
            assertEquals("parallel=$parallel", listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ComposerMode.Busy, vm.ui.value.composer)
            shown.value = "a" to TaskState.Running(1, "打开设置", "phone_launch_app")
            state.value = shown.value!!.second
            runCurrent()
            did(1, "act", "phone_launch_app", "com.chat")
            assertEquals(listOf("アプリを開く · 聊天"), vm.ui.value.exchanges.last().steps.map { it.text })
            // フロントデスクに戻って再度受け取ります: 新しいものはありません
            vm.refresh()
            assertEquals(2, vm.ui.value.exchanges.size)
            assertEquals("胶囊里说的", vm.ui.value.exchanges.last().goal)
            endedRun.value = "r2-$parallel"
            serve("a", TaskState.Succeeded("好了"))
            store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2-$parallel"))
            runCurrent()
            assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            active.value = emptyList(); acts.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // 画面にダイアログがないときに他の場所で開始されたタスクを引き継ぐ: その文もそこにあります
    @Test fun adoptedRunShowsItsMessage() = runTest {
        val vm = registryVm(ConversationStore(tmp.newFolder()), parallel = false)
        startedElsewhere("a", "胶囊里说的")
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // 新しい会話の別の場所で開始されたタスク: サイドバーには進行中として表示されます。それを開くと (Mika に戻ると)、その文と実行ステータスが表示されます。 「
    @Test fun runInNewConversationElsewhereOpensWithMessage() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("n", "新对话里说的")
            assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(listOf("n"), vm.otherRunning.value)
            val sections = drawerSections(store.list(), CurrentConversation("a", false, "查天气", ""), vm.otherRunning.value, vm::runningGoal, vm.canSwitch(), 0L)
            assertEquals(listOf("新对话里说的"), sections.running.map { it.title })
            vm.openConversation("n")
            assertEquals("n", vm.ui.value.conversationId)
            assertEquals(listOf("新对话里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
            serve("n", TaskState.Succeeded("好了"))
            active.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // 」が成長しているという回答は、モデルに与えられたコメントの後ろと、モデルに与えられていないコメントの前に表示されます。
    @Test fun draftSitsBetweenJoinedAndWaitingSteers() {
        val joined = SteerNote("a", "改成明天", SteerState.Joined)
        val received = SteerNote("b", "简短点", SteerState.Received)
        val pending = SteerNote("c", "再简单点", SteerState.Pending)
        assertEquals(listOf(joined) to listOf(received, pending), steersAroundDraft(listOf(joined, received, pending)))
        assertEquals(listOf(joined) to emptyList<SteerNote>(), steersAroundDraft(listOf(joined)))
        assertEquals(emptyList<SteerNote>() to listOf(received), steersAroundDraft(listOf(received)))
    }
    /** は、別のタスクを待っている間にアプリがリリースされても動作し続けます。誰を待っているかについて書かれた進行状況の行は、一時停止またはユーザーが処理するものとしてカウントされません。 */
    @Test fun waitingForAnotherTasksAppIsStillWorking() = runTest {
        val vm = vm()
        vm.send("给张三发消息", ok)
        emit(TaskState.Running(2, "打开聊天", tool = "phone_launch_app", progress = com.wangzan101.mika.core.agent.RunProgress.WaitingForApp("聊天")))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals("ほかのタスクが 聊天 を使い終わるのを待っています", ex.line)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
    }

    /** 画面がオンになるとすぐに、小さな画面がポップアップ表示されます。ユーザーがオフにした後は、小さなボタンだけが残り、タスクはまだ実行されています。 */
    @Test fun smallWindowCanBeClosedForThisTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertFalse("画面を開く前は小画面を表示しない", vm.ui.value.pip.visible)
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)

        vm.closePip()
        assertTrue("閉じた後も小さなボタンを表示する", vm.ui.value.pip.visible)
        assertTrue(vm.ui.value.pip.closed)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // 以降の進歩により、それが自然に再浮上することはありません。
        emit(TaskState.Running(1, "点了设置"))
        assertTrue(vm.ui.value.pip.closed)

        vm.reopenPip()
        assertFalse("小さなボタンを押すと再表示する", vm.ui.value.pip.closed)
    }

    @Test fun pipUnmuteIsOneShotAndRepeatedClicksDoNotInventToggleState() = runTest {
        val vm = vm()
        vm.send("打开视频", ok)
        val id = vm.ui.value.conversationId!!
        calls.clear()
        val before = vm.ui.value.pip
        vm.unmutePip()
        vm.unmutePip()
        assertEquals(listOf("unmute@$id", "unmute@$id"), calls)
        assertEquals(before, vm.ui.value.pip)
    }

    /** 关着小画面时要你接管:不自己弹开,但小按钮进入「要你处理」的样子,要你做的事照样在对话卡片里。 */
    @Test fun closedSmallWindowStillSignalsWhenItNeedsYou() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("付款", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.WaitingTakeover("payment", "要你付款"))
        val pip = vm.ui.value.pip
        assertTrue(pip.visible)
        assertTrue(pip.closed)
        assertFalse("閉じている場合は引き継ぎ時も自動展開しない", pip.expanded)
        assertEquals(PipMode.NeedsYou, pip.mode)
        assertTrue(vm.ui.value.exchanges.single().status is ExchangeStatus.NeedsTakeover)
    }

    /** そのタスクだけをオフにします。次のタスクは引き続き小さな画面に表示されます。 */
    @Test fun closingTheSmallWindowOnlyLastsForThatTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.Succeeded("电量 80%"))
        open.value = false
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()

        vm.send("再看看存储", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)
    }

    // ---------- カプセルに続くダイアログ ----------
    // カプセルは、誰が会話ファイルを最後に書いたかを調べませんが、インターフェースが現在どの会話に参加しているかを調べます。これら 3 つの変更はそれぞれ 1 回伝えられます。

    @Test fun newChatTellsTheCapsuleItIsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        assertEquals(null, store.currentChat())
        vm.newConversation()
        assertEquals(CurrentChat.Fresh, store.currentChat())
    }

    @Test fun openingAConversationTellsTheCapsuleWhichOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("c-old", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.openConversation("c-old")
        assertEquals(CurrentChat.Open("c-old"), store.currentChat())
    }

    /** 新しい対話で話される最初の文には番号が付けられます。対話は前の文ではなく、カプセルの後に続きます。 */
    @Test fun theFirstMessageInANewChatTellsTheCapsuleItsId() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        vm.newConversation()
        assertTrue(vm.send("查天气", ok))
        val id = vm.ui.value.conversationId
        assertTrue(id != null)
        assertEquals(CurrentChat.Open(id!!), store.currentChat())
        emit(TaskState.Succeeded("晴"))
        // 終わった後も同じです: カプセルは前の会話に戻りません
        assertEquals(CurrentChat.Open(id), store.currentChat())
    }

    /** は、どの 2 つのワークスペースがコピーされたかを記録するだけで、事前に指定された結果を返します。 */
    private class FakeBranchWorkspace(var result: BranchCopyResult? = BranchCopyResult(copied = 1)) : BranchWorkspace {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? {
            calls += sourceId to targetId
            return result
        }
    }

    /** 回答ブランチから: このラウンド (包括的) にコピーし、タイトルにサフィックスを追加して、新しい会話に切り替えます。元の会話は変更されず、ワークスペースの受信トレイもコピーされます。 */
    @Test fun branchFromCopiesThroughTheTurnAndOpensTheBranch() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        store.setTitle(source, "原对话")
        val workspace = FakeBranchWorkspace()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            branchWorkspace = workspace, branchTitle = { it + " · 分支" }, clock = { 123L },
        ).also { runCurrent() }
        vm.openConversation(source)

        val target = vm.branchFrom(1)

        assertNotNull(target)
        assertTrue(target != source)
        assertEquals(target, vm.ui.value.conversationId)
        assertEquals(listOf("一", "二"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
        assertEquals("原对话 · 分支", store.title(target!!))
        assertEquals(BranchOrigin(source, 1, afterTurn = true, at = 123L), store.branchOrigin(target))
        assertEquals(listOf(source to target), workspace.calls)
    }

    /** ソースの会話にタイトルがない場合は、最初の文をベースとして使用し、接尾辞を追加します。 */
    @Test fun branchTitleFallsBackToTheFirstWords() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("帮我整理发票", "好了", "succeeded"))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store, branchTitle = { it + " · 分支" },
        ).also { runCurrent() }
        vm.openConversation(source)
        val target = vm.branchFrom(0)
        assertNotNull(target)
        assertEquals("帮我整理发票 · 分支", store.title(target!!))
    }

    /** タスクは実行中です。入力ボックスはビジーです。ラウンドは範囲外です。ラウンドはまだ終了していません。ダイアログはありません。分岐もありません。 */
    @Test fun branchFromRefusesRunningBusyAndBadTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        vm.openConversation(source)

        activeNow.value = listOf(source)
        runCurrent()
        assertTrue(vm.currentRunning())
        assertNull(vm.branchFrom(0))

        activeNow.value = emptyList()
        runCurrent()
        assertFalse(vm.currentRunning())
        assertNull(vm.branchFrom(1))
        assertNull(vm.branchFrom(-1))

        // 新しい文の送信後に入力ボックスがビジー状態になり、分岐できなくなります。
        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchFrom(0))
    }

    /** ワークスペースは上限を超えているためコピーされません。通常どおり丸い分岐が発生し、インターフェイスに「すべての添付ファイルがコピーされていません」というプロンプトが表示されます。 */
    @Test fun branchFromReportsAttachmentsThatDidNotCopy() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val workspace = FakeBranchWorkspace(BranchCopyResult(copied = 0, truncated = true))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)

        assertNotNull(vm.branchFrom(0))
        assertEquals(BranchCopyResult(copied = 0, truncated = true), vm.branchCopy.value)
        vm.clearBranchCopy()
        assertNull(vm.branchCopy.value)
    }

    /** ソース会話ファイルが壊れています。null が返され、切り取られません。 */
    @Test fun branchFromFailsWithoutSwitchingWhenTheSourceIsUnreadable() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val vm = vm(store)
        vm.openConversation(source)
        java.io.File(dir, "$source.json").writeText("{not json")

        assertNull(vm.branchFrom(0))
        assertEquals(source, vm.ui.value.conversationId)
    }

    /** は、ユーザー メッセージから再度尋ねます。このラウンドをコピーする前に、事前に入力されるテキストを返してください。元の会話は変更されず、新しい会話に切り替わります。 */
    @Test fun branchBeforeCopiesEarlierTurnsAndReturnsPrefill() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        store.setTitle(source, "原对话")
        val workspace = FakeBranchWorkspace()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            branchWorkspace = workspace, branchTitle = { it + " · 分支" }, clock = { 7L },
        ).also { runCurrent() }
        vm.openConversation(source)

        val result = vm.branchBefore(1)

        assertNotNull(result)
        val branch = result!!.conversationId
        assertEquals("二", result.prefill)
        assertFalse(result.needsReattach)
        assertEquals(listOf("一"), store.turns(branch).map { it.userText })
        assertEquals("原对话 · 分支", store.title(branch))
        assertEquals(BranchOrigin(source, 1, afterTurn = false, at = 7L), store.branchOrigin(branch))
        assertEquals(branch, vm.ui.value.conversationId)
        assertEquals(listOf("一"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf(source to branch), workspace.calls)
    }

    /** 最初のラウンドの前にラウンドはありません。新しい会話は空です。元のメッセージに添付ファイルがある場合は、添付ファイルを再追加するように求められます。 */
    @Test fun branchBeforeTheFirstTurnIsEmptyAndFlagsAttachments() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("看这个\n附件:/workspace/inbox/a.pdf(1 KB)", "好", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchTitle = { it + " · 分支" }).also { runCurrent() }
        vm.openConversation(source)

        val result = vm.branchBefore(0)

        assertNotNull(result)
        assertEquals("看这个", result!!.prefill)
        assertTrue(result.needsReattach)
        assertTrue(store.turns(result.conversationId).isEmpty())
        assertTrue(store.list().none { it.id == result.conversationId })
        assertEquals(result.conversationId, vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
    }

    /** メニューに表示される入り口は、クリック後の判定と一致します。ラウンドが終了していない場合は、どちらの入り口も表示されず、アクションは実行されません。走っているときや忙しいときも同様です。 */
    @Test fun branchMenuMatchesWhatTheActionAllows() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        assertNull(vm.branchTurn("r0"))
        assertNull(vm.retryTurn("u0"))
        vm.openConversation(source)

        assertEquals(0, vm.branchTurn("r0"))
        assertEquals(0, vm.retryTurn("u0"))
        assertNull(vm.branchTurn("u0"))
        assertNull(vm.retryTurn("r0"))

        activeNow.value = listOf(source)
        runCurrent()
        assertNull(vm.branchTurn("r0"))
        assertNull(vm.retryTurn("u0"))

        activeNow.value = emptyList()
        runCurrent()
        // は新しい文を送信しました: 最初のラウンドはまだ終わっていません。入力ボックスはビジーで、どちらの入り口も表示されず、アクションは実行されません。
        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchTurn("r1"))
        assertNull(vm.retryTurn("u1"))
        assertNull(vm.retryTurn("u0"))
        assertNull(vm.branchBefore(1))
        assertNull(vm.branchFrom(1))
    }

    /** タスクは実行中、ラウンドは範囲外、入力ボックスはビジー状態です。再度質問する必要はありません。 */
    @Test fun branchBeforeRefusesRunningBusyAndBadTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        vm.openConversation(source)

        activeNow.value = listOf(source)
        runCurrent()
        assertNull(vm.branchBefore(0))

        activeNow.value = emptyList()
        runCurrent()
        assertNull(vm.branchBefore(1))
        assertNull(vm.branchBefore(-1))

        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchBefore(0))
    }
}
