package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.uistate.PlacePace
import kotlin.random.Random
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.usage.RunUsageTally
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.ui.StatusCopy
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.SharedFile
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.RootShell
import com.wangzan101.mika.cap.root.RootCommands
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.ModelRouter
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.ui.ToolLabels
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.child.ChildAgentRunner
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.takeover.HandoverActivityCounter
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverPort
import com.wangzan101.mika.takeover.stoppedAt
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import com.wangzan101.mika.core.knowledge.ChannelFacts
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.cap.web.KeptOrigin
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.browser.PageLoans
import com.wangzan101.mika.browser.UserBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.WEB

/**
 * 浮层恢复声音的路由:只有真正把 App 交到用户手上的接管才处理;接管对象从任务开跑起就一直存在,
 * 不能仅凭非空提前返回。等待接管、网页接管或没有接管目标时都落到普通手机音频路径。
 */
internal fun restorePipSound(takeover: Takeover?, restorePhone: () -> Unit) {
    if (takeover?.unmute() != true) restorePhone()
}

/**
 * タスクのすべてのステータス: 仮想画面、ループ、レベル、テイクオーバー、ミュート、モニタリング。 [TaskService] は、サービスのライフサイクル、インテントの配布、および通知のみを考慮します。
 * このタスク自身のストリームは、「表示」タスク ([service.taskState.shownRun]) の場合にのみ、サービスの外部ストリームに同期的に書き込まれます。
 * 他のタスク (並行) の書き込み、または新しいタスクによって置き換えられた古いタスクは、それ自体の部分にのみ適用され、表示内のタスクは上書きされません。
 * [scope] はサービスのスコープです。バイパス コルーチンはその下にハングされ、タスク コルーチンとともにキャンセルされません。最後に[execute]でキャンセルされます。
 * [slot] は通知番号とボタン要求コードを決定します。 [parallel] が true (ParallelTask​​s の切り替え) の場合、アプリを開く前に他のタスクが使用中かどうかを確認します。
 */
class TaskRun(
    val conversationId: String,
    val goal: String,
    private val service: TaskService,
    private val scope: CoroutineScope,
    val slot: Int = 0,
    val parallel: Boolean = false,
) {
    internal var provenance = RunProvenance()
    internal var triggerWindowKey: String? = null
    internal var triggerActionGoal: String? = null
    internal var triggerThreadId: String? = null
    internal var mcpExternalId: String? = null
    internal val conditionCheck get() = triggerActionGoal != null
    @Volatile private var children: ChildAgentRunner? = null
    /** このタスクに接続された外部 MCP サーバー。セッションが終了したら閉じます。 */
    @Volatile private var mcpConnections: com.wangzan101.mika.settings.McpConnections? = null

    /**
     * このタスクに接続されているすべての外部 MCP エンドポイントは、toolDispatcher に渡されます。他のソース (将来の拡張機能など) もそのようなエンドポイントを提供する場合は、ここに入力してください。
     * は、toolDispatcher にパラメータを追加しません。各ソースからのすべての接続はタスクの ResourceScope に属し、最後にまとめて閉じられます。
     */
    private fun mcpEndpoints(): List<com.wangzan101.mika.core.cap.mcp.McpConnection> = buildList {
        mcpConnections?.let { addAll(it.connections) }
    }
    /** 自身のコピー。表示されると、同時にサービスの外部コピーに書き込まれます。 [show] のコピー全体をコピーします。 */
    /** アプリは、モデルソース、評価フック、画面切り替えを提供します。 */
    private val env = TaskEnvironment.of(service)
    /** この会話の外部会話番号: この実行で構築されたすべてのモデル (メイン、軽量、画像表示、検証、意図判断、停止説明などのバイパス) がこれを使用します。 */
    private val session = ModelSession.of(conversationId)
    /** アプリは、携帯電話サービス (共有シェル接続、「設定に移動」プロンプト)、メモリー、体験ルート、および異常終了後の回復を提供します。 */
    private val phoneServices = PhoneServices.of(service)
    private val memoryRuntime = MemoryRuntime.of(service)
    private val routeStore = FileRouteStore.shared(service)
    private val recovery = TaskRecovery.of(service)

    private inner class Mirrored<T>(initial: T, private val shown: MutableStateFlow<T>) {
        private val own = MutableStateFlow(initial)
        val flow: StateFlow<T> = own.asStateFlow()
        var value: T
            get() = own.value
            set(v) {
                synchronized(service.taskState.shownLock) {
                    own.value = v
                    if (service.taskState.shownRun === this@TaskRun) shown.value = v
                }
            }
        fun copyOut() { shown.value = own.value }
    }

    /** ミュートとその他の回復記録はそれに応じて分離され、いくつかのタスクが個別に記録されます。 */
    val key: String = UUID.randomUUID().toString()
    private val inbox = RunInbox()

    private val _runId = Mirrored<String?>(null, service.taskState._runId)
    private val _sharedFiles = Mirrored<List<SharedFile>>(emptyList(), service.taskState._sharedFiles)
    private val _state = Mirrored<TaskState>(TaskState.Running(0, TaskService.PREPARING), service.taskState._state)
    /** は同じ状態に会話番号を追加し、それを [_state] と一緒に書き込みます。インターフェイスは画面上のその会話の状態のみを収集します。 */
    private val _tagged = Mirrored<Pair<String, TaskState>?>(conversationId to _state.value, service.taskState._shownTask)
    private val _takeoverStage = Mirrored(Takeover.Stage.None, service.taskState._takeoverStage)
    private val _frames = Mirrored<Bitmap?>(null, service.taskState._frames)
    private val _activities = Mirrored<List<RunEvent.Activity>>(emptyList(), service.taskState._activities)
    /** アクティビティは追加のみです。最初の n 項目の読み取り専用ビューが公開され、新しい項目ごとに既存のビューはコピーされません。 */
    private val activityLog = ArrayList<RunEvent.Activity>()
    private fun logView(n: Int): List<RunEvent.Activity> = object : AbstractList<RunEvent.Activity>() {
        override val size = n
        override fun get(index: Int): RunEvent.Activity = synchronized(activityLog) { activityLog[index] }
    }
    private val _steers = Mirrored<List<SteerNote>>(emptyList(), service.taskState._steers)
    private val _displayId = Mirrored(-1, env.agentDisplayId)
    /** アプリが仮想画面上で開かれました。それに応じてピクチャーインピクチャーが表示されます (空白の画面のみが作成され、アプリが開かれていないときは画像は表示されません)。 */
    private val _appShown = Mirrored(false, env.agentAppShown)
    /** は、組み込みブラウザーのピクチャー・イン・ピクチャーを使用して Web ページを表示します。 */
    private val _webOpen = Mirrored(false, env.webScreenOpen)

    val state: StateFlow<TaskState> = _state.flow
    val runId: StateFlow<String?> = _runId.flow
    /** このタスクによってエクスポートされたファイル。終了すると、このラウンドの会話に保存されます。 */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.flow
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.flow
    val frames: StateFlow<Bitmap?> = _frames.flow
    /** 現時点までのこのタスクのアクティビティ (実際に実行されたこと)。吊り下げられたカプセルのカードは、ディスプレイ上のカードだけでなく、タスクに従って取得されます。 */
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.flow
    /** このタスクの仮想画面番号。画面は、システム コマンド、イベント ツール、またはアプリが初めて開かれるまで作成されません。リリース前後では-1です。 */
    val displayId: StateFlow<Int> = _displayId.flow
    /** は現在、内蔵ブラウザを使用しています (ツールのインターフェイスは Web ページです)。これは役に立たず、タスクが完了すると false になります。 */
    val webOpen: StateFlow<Boolean> = _webOpen.flow
    /** このタスク中に挿入された単語とその所在。吊り下げられたカプセルの対話カードはタスクに応じて取得されます。 */
    val steers: StateFlow<List<SteerNote>> = _steers.flow
    // 最初に会話番号を持つものを書き込みます。番号なしのステータスを受信したときに読み取られる番号は、すでにこのタスク用のものです (それに応じて、インターフェイスは他の会話のステータスを画面に接続しません)
    private val mirrored = listOf(_runId, _sharedFiles, _tagged, _state, _takeoverStage, _frames, _displayId, _appShown, _webOpen, _activities, _steers)

    private fun setState(s: TaskState) = synchronized(service.taskState.shownLock) {
        _tagged.value = conversationId to s
        _state.value = s
    }

    /** インターフェイスにこのタスクを表示させます。表示されたタスクになり、独自の項目をサービスの外部フローにコピーします。 */
    fun show() = synchronized(service.taskState.shownLock) {
        service.taskState.shownRun = this
        mirrored.forEach { it.copyOut() }
    }

    /** は、サービスの開始後に入力される [execute] のコルーチンを実行します。 */
    @Volatile var job: Job? = null
        internal set(v) {
            field = v
            if (cancelRequested) v?.cancel()
        }
    @Volatile private var cancelRequested = false
    /** ユーザーが [停止] をクリックしました: まだモデルに渡されていない割り込みは無効になり、次のものとしてみなされません。 */
    @Volatile private var stopPressed = false
    @Volatile private var loop: AgentLoop? = null
    val activeAsk: Delivery.Ask? get() = loop?.activeAsk
    @Volatile private var gate: TaskGate? = null
    @Volatile var takeover: Takeover? = null
        private set
    @Volatile var muteFailedFor: String? = null
        private set
    @Volatile private var awake: KeepAwake? = null
    /** 次のタスク用の画面ロックを先に確保し、起動後にそのタスク自身のロックへ引き継ぎます。起動前に終了した場合はサービスが解放し、重複解放は [SharedWakeLock] が吸収します。 */
    @Volatile internal var wakeHandoff: WakeLockPort? = null
        /** シェル プロセスがなくなり、仮想画面とその上のアプリがなくなりました。タスクを続行できません。 */
    @Volatile var shellDied = false
        internal set
    /** 已接上的手机侧;浮层恢复声音据此找到当前目标 App,收尾时清空。 */
    @Volatile private var phone: PhoneSide? = null
    /** 发布与收尾互斥;[ended] 之后不再写状态,迟到的发布不能盖掉终态或重新拿锁。 */
    private val publishLock = Any()
    private var ended = false

    fun cancel() {
        stopPressed = true
        children?.cancelAll(RunId(key))
        val running = loop
        val t = takeover
        when {
            t != null -> scope.launch { t.cancel() }
            running != null -> inbox.cancel()
            else -> {
                // チームから解放されたばかりで、コルーチンがハングアップしていない場合のキャンセル: ハングアップしたらすぐにキャンセルします。
                cancelRequested = true
                job?.cancel()
            }
        }
    }

    fun resume() {
        val g = gate ?: return
        val wasOnPage = g.userOnPagePending
        g.resume()
        // ユーザーがページ上で操作する一時停止の種類。ループはまだそのレベルに達していない可能性があり (まだ検討中)、現在の状態に応じて再表示され、一時停止とは表示されなくなります。
        if (wasOnPage) loop?.state?.value?.takeIf { it is TaskState.Running }?.let(::publish)
    }
    fun takeOver(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handOver(waitKey) } } }
    fun handBack(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handBack(waitKey) } } }
    fun finishedMyself(waitKey: Int?) { takeover?.let { t -> scope.launch { t.finishedMyself(waitKey) } } }
    fun skip(waitKey: Int?) { takeover?.let { t -> scope.launch { t.skip(waitKey) } } }
    fun putAside(waitKey: Int?) { takeover?.let { t -> scope.launch { t.putAside(waitKey) } } }

    /** 浮层上的一次性「恢复声音」;重复点击会重试真实恢复,没有手机侧或目标 App 时不动。 */
    fun unmute() {
        scope.launch(Dispatchers.IO) {
            restorePipSound(takeover) {
                val p = phone ?: return@restorePipSound
                val target = p.screen.target.value ?: return@restorePipSound
                if (!p.audio.unmute(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not restore sound of ${target.packageName} from pip")
                }
            }
        }
    }

    /** 回答提问;已经不在等(超时后才答、已答过)时调 [tooLate] 告诉用户这条回答没用上。 */
    fun answer(text: String, tooLate: () -> Unit) {
        if (loop == null) return
        if (_state.value is TaskState.WaitingInput) scope.launch {
            if (inbox.send(RunInput.Answer(text)) != InputReceipt.Accepted) tooLate()
        }
        else tooLate()
    }

    /**
     * タスクの進行中の同じ会話の新しい元の単語は、次のモデル ステップに渡されます。準備フェーズも最初に受信箱のキューに入れられます。
     * [messageId] はインターフェイスによってこのセンテンスに与えられた番号であり、インターフェイスはそれに応じて宛先をマークします ([SteerNote] を参照)。
     */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) {
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value + SteerNote(messageId, text, SteerState.Pending)
            // 受信箱で受け取った後、ユーザーにはすぐに通知されます: 現時点でのモデルのリクエストは取り下げられており、次のステップはこの文を念頭に置きます (モデルに引き渡されるときに追加されるように変更します)
            if (inbox.steer(messageId, text) == InputReceipt.Accepted) markSteers(setOf(messageId), SteerState.Received, from = SteerState.Pending)
            else {
                if (stopPressed) markSteers(setOf(messageId), SteerState.Cancelled)
                else {
                    markSteers(setOf(messageId), SteerState.Next)
                    service.deferMessage(conversationId, text)
                }
            }
        }
    }

    /** リモート追加は現在のタスクにのみ属します。これはステータス公開とは相互に排他的であり、受信トレイは終了/キャンセルされたかどうかを原子的にチェックされます。 */
    fun steerRemote(text: String): String? = synchronized(publishLock) delivery@{
        if (ended || stopPressed || mcpExternalId == null || _state.value !is TaskState.Running) return@delivery null
        if (loop?.state?.value?.let { it !is TaskState.Running } == true) return@delivery null
        val id = UUID.randomUUID().toString()
        synchronized(service.taskState.shownLock) {
            // まずメッセージを思い出してから、モデルを起動します。消費イベントはすぐに発生する可能性があり、参加済みを受信済みに戻すことはできません。
            _steers.value = _steers.value + SteerNote(id, text, SteerState.Received, remote = true)
            if (inbox.steerCurrent(id, text) != InputReceipt.Accepted) {
                _steers.value = _steers.value.filterNot { it.id == id }
                return@delivery null
            }
            id
        }
    }

    /** 携帯電話に挿入された文を元に戻します。モデルは削除されていません。コンピュータによって削除、終了、または追加された文 (受信箱拒否) は false を返し、宛先は変更されません。 */
    fun withdrawSteer(messageId: String): Boolean = synchronized(service.taskState.shownLock) {
        if (!inbox.withdrawSteer(messageId)) return@synchronized false
        markSteers(setOf(messageId), SteerState.Withdrawn)
        true
    }

    /** 携帯電話でのチャットは次のタスクに転送できます。リモート チャットは現在のタスクにのみ使用でき、未使用のチャットは無効になり、ソースと権限は変更されません。 */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed) {
        val left = inbox.handoffUnconsumed()
        val (discard, next) = left.partition { cancelled || it.currentTaskOnly }
        markSteers(discard.map { it.messageId }.toSet(), SteerState.Cancelled)
        markSteers(next.map { it.messageId }.toSet(), SteerState.Next)
        next.forEach { service.deferMessage(conversationId, it.text) }
    }

    /** このタスクに与えられた間投詞は、このラウンドの会話に保存されます。 */
    /** このミッションで実際に使用された能力グループ (携帯電話、Web ページ、サンドボックス)。このラウンドで記録され、次のラウンドが事前に開始されます。 */
    @Volatile var usedGroups: List<String> = emptyList()
        private set

    fun joinedSteers(): List<String> = joined().map { it.text }

    /** と [joinedSteers] は 1 対 1 に対応しており、各割り込みによって割り込まれる応答は空の文字列です。 */
    fun joinedInterrupted(): List<String> = joined().map { it.interrupted.orEmpty() }

    /** と [joinedSteers] は 1 対 1 に対応します。各感動詞はモデルの前に終了した応答に与えられ、空の文字列はありません。 */
    fun joinedFinished(): List<String> = joined().map { it.finished.orEmpty() }

    private fun joined() = _steers.value.filter { it.state == SteerState.Joined }

    /** 間投詞から削除された応答は、この間投詞のバッチの最初の文がモデルに与えられるときにそれに添付されます。 */
    private val steerJoin = SteerJoin()

    private fun joinSteer(id: String) {
        synchronized(service.taskState.shownLock) { _steers.value = steerJoin.joined(_steers.value, id) }
    }

    /** 文 [ids] を [to] に変更します。 [from] を与えるときは、まだその状態にあるものだけを変更してください (モデルの受け入れと引き渡しは逆になる可能性があり、元に戻されることはありません)。 */
    private fun markSteers(ids: Set<String>, to: SteerState, from: SteerState? = null) {
        if (ids.isEmpty()) return
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value.map { if (it.id in ids && (from == null || it.state == from)) it.copy(state = to) else it }
        }
    }

    /** 実行中のイベントのインターフェイスは同じである必要があります。つまり、実際に実行されたものと、介入がモデルに引き継がれています。 */
    private val liveEvents = object : RunSink {
        override fun event(e: RunEvent) {
            when {
                e is RunEvent.Activity -> synchronized(service.taskState.shownLock) {
                    val n = synchronized(activityLog) { activityLog += e; activityLog.size }
                    _activities.value = logView(n)
                }
                e is RunEvent.State && e.name == "reply_interrupted" -> steerJoin.interrupted(e.detail)
                e is RunEvent.State && e.name == "reply_finished_before_steer" -> steerJoin.finished(e.detail)
                e is RunEvent.State && e.name == "steer_received" -> joinSteer(e.detail)
            }
        }
    }

    /** 通知とカード ボタンにはすべて、[key] に属する宣言が含まれています。古い通知や置き換えられた古いカードをクリックしてもカウントされません。 */
    fun confirm(choice: ConfirmChoice, key: Int?) {
        val running = loop
        val waiting = _state.value as? TaskState.WaitingConfirmation
        if (running != null && waiting != null && key == ConfirmationNotifier.requestKey(waiting)) {
            scope.launch { inbox.send(RunInput.Confirm(choice)) }
        }
    }

    /**
     * タスクの最終状態: [execute] は、完了後に [publish] なしで書き込まれます。あなたであろうとなかろうと、まずは今回の走行番号を記録してください。
     * は自分の会話名 ([TaskService.endedRunIds]) の下にあります。インターフェイスが終了ステータスを受信したら、ダイアログ ボタンを押してそれを取得します。表示されたら、[TaskService.endedRunId] も書き込みます。
     */
    fun end(end: TaskState) = synchronized(service.taskState.shownLock) {
        service.taskState._endedRunIds.value = service.taskState._endedRunIds.value + (conversationId to _runId.value)
        if (service.taskState.shownRun === this) service.taskState._endedRunId.value = _runId.value
        setState(end)
    }

    /** は、並列制限を超えるとキューに入れられます。 [dequeue] がリリースされ、実行が開始されました。 */
    fun queue(position: Int) { setState(TaskState.Held(GateReason.queued(trPlural(R.plurals.queue_ahead, position)))) }
    fun dequeue() { setState(TaskState.Running(0, TaskService.PREPARING)) }

    /** このタスクのサンドボックス: タスクごとに 1 つのコピー (エクスポート量はタスクに基づいて計算されます)、ワークスペースはこの会話に属します。 */
    private val sandbox by lazy { AndroidSandbox(service, SandboxState.get(service), onShared = ::addShared) }

    /** サンドボックスはファイル (share_file または `mika share`) をエクスポートしました。 */
    fun addShared(f: SharedFile) = synchronized(service.taskState.shownLock) { _sharedFiles.value = _sharedFiles.value + f }

    fun clearTakeoverStage() { _takeoverStage.value = Takeover.Stage.None }

    /** 現時点で表示されるステータス。終了すると空になり、通知は再描画されません。 */
    fun shownState(): TaskState? = synchronized(publishLock) { if (ended) null else _state.value }

    /** モデルが失敗し、メイン モデルがそのストアでのテストに合格しなかった場合、そのストアに行って「再テスト」するように求められます ([RetestHint] を参照)。メインモデルをテストする場合、設定の構成はチェックされません。 */
    private fun withRetestHint(end: TaskState): TaskState {
        if (end !is TaskState.Failed || env.modelFactory !== TaskEnvironment.defaultModelFactory) return end
        return RetestHint.apply(end, ProviderStore.get(service).untestedMainProvider()) { tr(R.string.fail_model_untested, it) }
    }

    suspend fun execute(priorTurns: List<Turn>): TaskState {
        // 携帯電話側 (内蔵アシスタント、アクセシビリティ、仮想画面) は、app の初回起動時にのみ接続されます: Web ページとサンドボックスのみを使用するタスクにはそれらは必要ありません
        var connection: com.wangzan101.mika.cap.phone.shell.ShellBinding? = null
        phone = null
        // 虚拟屏、网页显示等:建好当场归它,收尾时一起放掉;不经「建完再记到变量」,取消落在中间也不会漏
        val resources = ResourceScope()
        var watcher: DeviceStateWatcher? = null
        var mute: AudioMute? = null
        // モデルのタスクが完了すると、各アプリの宛先 (携帯電話ポート上)。最後に、組み込みアシスタントに渡します
        var afterTask: com.wangzan101.mika.core.uistate.AfterTaskMarks? = null
        var shellDeath: IBinder.DeathRecipient? = null
        var shellBinder: IBinder? = null
        val traces = (service.application as MikaApp).detailedTraces
        val traceTicket = traces.ticket()
        var record: RunHandle? = null
        var runStore: RunStore? = null
        // この実行で測定されたモデル リクエスト (バイパス呼び出しを含む) は、最後に実行レコードに記録されます。
        val tally = RunUsageTally()
        // は、Web サイトを初めて開いたときに構築されます。以前にビルドされていない場合は、閉じる必要はありません。
        var webStack: WebStack? = null
        // 現在のインターフェイス (携帯電話/Web ページ) はディストリビュータによって異なります。ピクチャー・イン・ピクチャーの映像ソースもそれに合わせて切り替わります
        var dispatcher: ToolDispatcher? = null
        var mcpEvents: AutoCloseable? = null
        var liveFeed: AutoCloseable? = null
        // 問い合わせ / 自主性: 作業を開始するときにコピーを 1 部取り、開発者向けオプションでスイッチが変更されたときに変更します。最後に返してください
        val approvalControl = (service.application as MikaApp).approvalControl
        var approval: RunApproval? = null
        // タスク中はコルーチンをバイパス（アシスタント接続ステータス、ミュートと削除の監視、ステータス解除）、タスク終了時にキャンセル
        val side = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "task side job failed", e) },
        )
        sideScope = side
        try {
            // ユーザーは、「作業中」には常時点灯ライトをオフにし、ウェイクロックを取得しません。実行を開始するときは、現在のステータスに応じて実行します (準備中)。
            // モデルを開き、知識を読み取り、ルートを選択し、意図を判断する最初のステップである準備と推論中に画面がオフになりません。
            awake = if (FeatureFlags.get(service).keepScreenOn()) KeepAwake(service.wakeLock.lease(), side).also { it.onState(_state.value) } else null
            // 引継ぎ用のロックは、自分のロックを取得してから解放します。
            wakeHandoff?.release()
            wakeHandoff = null
            val model = env.modelFactory(service, session) ?: return TaskState.Failed(tr(R.string.fail_no_main_model), FailKind.Environment)
            // ユーザーは、ブラウザ ページ上のページに、どのページを現在の Web ページ ラベルとしてこのタスクに貸与する必要があるかを尋ねました (PageLoans を参照)。実行中にページがまだ開いている場合にのみ借用できます。
            val pageLoan = if (provenance.origin == RunOrigin.Ui) withContext(Dispatchers.Main) { UserBrowser.existing()?.claim(conversationId, key) } else null
            val light = env.lightModelFactory(service, session)
            // 同一模型分成两个实例并不会更便宜或更快;禁止它给批量/收尾工具反而只会增加改派和逐步操作。
            // 这只跳过逐步分工,轻量角色仍可用于意图判断、标题和收尾等辅助调用。
            val routedLight = light?.takeIf {
                !ProviderStore.get(service).roles.value.sameModel(ModelRole.Main, ModelRole.Light)
            }
            val vision = env.visionModelFactory(service, session)

            val (knowledge, skills) = withContext(Dispatchers.IO) {
                // ユーザーによるアプリ/サイトへの追加は、プライベート ディレクトリpersonal/appsおよびpersonal/sitesに配置されます。
                val userNotes = KnowledgeStore.loadNotes(File(service.filesDir, "personal"))
                // 自習アプリのナレッジにおいて、以前に記載していたツール名を現在のものに変更しました
                val learnedNotes = legacyNames().let { legacy -> memoryRuntime.appKnowledge().notes().mapValues { it.value.copy(text = legacy.rewrite(it.value.text)) } }
                KnowledgeStore(KnowledgeStore.loadBundled(), userNotes, learnedNotes) to GuideCatalog.loadBundled()
            }
            val appAccess = (service.application as MikaApp).appAccess
            // アプリケーションのアクセス境界: ツールとプレビュー画面は 1 つのコピーを共有します (以下の携帯電話ポートに接続するときに構築されます)。ウィンドウが変化しない場合には、検証結果がそのまま使用される。プレビューはフレームごとにウィンドウに問い合わせる必要はありません。 アプリでの
            var accessGuard: com.wangzan101.mika.core.uistate.AppAccessScreenPort? = null
            suspend fun previewAllowed(): Boolean {
                val guard = accessGuard ?: return false
                return try {
                    guard.checkAppAccess()
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: com.wangzan101.mika.core.access.AppAccessDenied) { false }
            }
            val metrics = mainScreenMetrics()
            // 操作のリズム (設定 › 作業中)。評価と実機受け入れはフックでカバー
            val pace = env.evalHooks.operatingPace ?: FeatureFlags.get(service).operatingPace()
            // アシスタントが接続されているかどうか。携帯電話に接続されるまで接続されたとみなされます (Web ページやサンドボックスのステップでは見ないでください)。
            val shellUp = MutableStateFlow(true)
            val states = DeviceStateWatcher(service) { id -> phone?.shell?.topTaskId(id) ?: -1 }.also { watcher = it }

            /** 携帯電話に接続します: 内蔵アシスタントに接続し、アクセシビリティを待ち、仮想画面を準備します (最初の launch_app までに構築されます)。何かが不足している場合は、phone_unavailable が報告されます。 */
            suspend fun connectPhone(): AndroidScreen {
                if (phoneServices.recoverySettings.paymentPause.value != null) {
                    publish(TaskState.Held(GateReason("payment_paused", tr(R.string.payment_paused_body))))
                    phoneServices.recoverySettings.paymentPause.first { it == null }
                }
                // バージョンでは携帯電話の操作のみが必要です。古いシステムでもチャットしたり、Web ページやサンドボックスを使用したりできますが、サポートされていないアシスタントとペアリングするように指示されるべきではありません。
                if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) throw PhoneUnavailable(TaskService.TOO_OLD)
                val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
                val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
                    ?: run {
                        phoneServices.setupPrompt.request()
                        // USB コマンドのみを使用して開始されたアシスタントが停止しました。真実を伝えてコマンドを再度実行し、ユーザーがワイヤレス デバッグを探すのを防ぎます。
                        throw PhoneUnavailable(
                            if (phoneServices.shellControl.restart == HelperRestart.UsbCommand) NO_PHONE_HELPER_USB else NO_PHONE_HELPER,
                        )
                    }
                // サービス カードは「開いていますが実行されていません」であり、自動的に再起動しています。しばらく待って修正して続行し、あきらめて通常どおりエラーを報告します。
                val a11y = AccessibilityRepair.awaitService(ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
                    ?: throw PhoneUnavailable(StatusCopy.accessibilityUnavailable(phoneServices.recovery.accessibility.value.enabled))
                recovery.beginRun(shell, key)
                // 携帯電話を使用するタスクのみがシェルの生と死に従います。シェルはなくなり、仮想画面はその上のアプリとともに消えます。
                shellDeath = IBinder.DeathRecipient {
                    shellDied = true
                    val running = loop
                    if (running != null) running.cancel() else job?.cancel()
                }.also { shell.asBinder().linkToDeath(it, 0) }
                shellBinder = shell.asBinder()
                side.launch { conn.shell.map { it != null }.collect { shellUp.value = it } }
                val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                    suspend fun create() = resources.acquire(Dispatchers.IO, AgentDisplay::release) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, service.getSystemService(DisplayManager::class.java)) }
                    try {
                        create()
                    } catch (e: Exception) {
                        if (e is CancellationException || e !is IllegalStateException && e !is RemoteException) throw e
                        if (!parallel || !service.otherRunHasDisplay(this@TaskRun)) throw e
                        // 2 番目の画面をシェルに構築することはできません。この間、並列操作は行われません。このタスクは後ろのキューに入れられ、他のタスクのすべての画面が解放された後に構築されます。
                        service.taskState.markSecondDisplayFailed()
                        publish(TaskState.Held(GateReason.queued(WAITING_DISPLAY)))
                        while (service.otherRunHasDisplay(this@TaskRun)) delay(DISPLAY_RETRY_MS)
                        create()
                    }.also { d ->
                        _displayId.value = d.displayId
                        side.launch { d.frames.collect { if (dispatcher?.surface != WEB) _frames.value = if (previewAllowed()) it else null } }
                        side.launch { appAccess.state.collect { if (dispatcher?.surface != WEB && !previewAllowed()) _frames.value = null } }
                    }
                }
                val audio = recovery.audioMute(shell, key).also { mute = it }
                val muter = appMuter(shell, audio)
                // は登録内の名前を占有します。別のユーザーの共有は個別に占有され、元の共有をブロックしません。占有されているタスクがまだそれを使用している場合は、内蔵アシスタントに尋ねます
                fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
                fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
                    runCatching { if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user) }.getOrDefault(-1) >= 0
                }
                val screen = AndroidScreen(
                    a11y, shell, agentDisplay, { recovery.markClipHeld(it) },
                    clipLock = service.clipLock, checkOtherTasks = parallel,
                    reserve = { pkg -> service.reservePackage(pkg, key, stillUsed(pkg, null)) },
                    reserveCopy = { pkg, user -> service.reservePackage(claimKey(pkg, user), key, stillUsed(pkg, user)) },
                    appFree = { pkg, user -> service.packageFree(claimKey(pkg, user), key, stillUsed(pkg, user)) },
                    // モデルは待機する必要があります: 待機中、インターフェイスには「アプリの使用が完了する別のタスクを待機しています」と表示され (アプリはまだ動作しており、処理する必要はありません)、待機後、元の文に戻ります。
                    awaitApp = { pkg, user, timeoutMs, free ->
                        waitingForApp(ConfirmationNotifier.appLabel(service, pkg)) { service.awaitPackage(claimKey(pkg, user), key, timeoutMs, free) }
                    },
                    clipSaved = { original, ours -> recovery.saveClip(original, ours) },
                    handedOver = { handoverRef?.handedOver == true },
                    // 一度に 1 段落ずつ入力します。アプリの知識によると、アプリの閲覧速度を制限すると入力が遅くなるそうです。
                    typing = { pkg -> pace.typing(PlacePace.of(pkg?.let(skills::appPace))) },
                    // は開始前に沈黙し、最初の数秒間は音が聞こえません。起動が拒否された場合は復元します。
                    beforeOpen = muter::beforeOpen,
                    notOpened = muter::notOpened,
                    appAccess = appAccess,
                )
                val side0 = PhoneSide(shell, a11y, screen, agentDisplay, muter)
                phone = side0
                watchTarget(side0, states)
                return screen
            }
            val phoneScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) } },
                // 別のユーザーのアプリが組み込みアシスタントに質問しようとしています: アシスタントがすでに接続されている場合にのみ質問し、それに応答しません
                appCopies = {
                    phoneServices.hub.shell.value?.let { s ->
                        withContext(Dispatchers.IO) { runCatching { AppCopyReader.read(s, launchableApps(service.packageManager, service.packageName)) }.getOrNull() }
                    }.orEmpty()
                }) { connectPhone() }
            val accessedPhone = com.wangzan101.mika.core.uistate.AppAccessScreenPort(
                phoneScreen, appAccess,
                packages = { phone?.screen?.accessPackages().orEmpty() },
                protectedUi = { appAccess.sensitiveUi }, ownPackage = service.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() },
            ).also { accessGuard = it }
            afterTask = phoneScreen.afterTask
            // 仮想画面に戻り、ウィンドウが表示されるまで待ちます。次にエージェントが画面を読み上げます。
            suspend fun moveTask(taskId: Int, to: Int): Boolean {
                val p = phone ?: return false
                val moved = withContext(Dispatchers.IO) { runCatching { p.shell.moveTask(taskId, to) }.getOrDefault(false) }
                if (moved && to == p.display.current?.displayId) p.screen.awaitContent()
                return moved
            }
            // 元に戻されたアプリは元々仮想画面上にあり、画面は構築されている必要があります。スクリーンがなければ、後戻りすることはできません。
            suspend fun moveBackToAgent(taskId: Int): Boolean {
                val id = phone?.display?.current?.displayId ?: return false
                return moveTask(taskId, id)
            }
            val taskGate = TaskGate(states.locked, shellUp, phoneServices.recoverySettings.paymentPause,
                refreshLocked = states::refresh,
                unattendedUnlock = if (provenance.origin == RunOrigin.Trigger) {
                    {
                        val result = (service.application as MikaApp).deviceAccess.access.unlock()
                        states.refresh()
                        Log.i(TAG, "scheduled unlock finished: ${result.status}")
                    }
                } else null,
            ) {
                val t = phone?.screen?.target?.value
                t != null && t.taskId >= 0 && moveBackToAgent(t.taskId)
            }
            gate = taskGate
            // レコードを開けない場合、レコードは記録されず、タスクは引き続き実行されます。
            val sink: RunSink = withContext(Dispatchers.IO) {
                runCatching {
                    val runs = RunStore(service).also { runStore = it }
                    runs.open(goal).also { record = it; _runId.value = it.runId }
                }.onFailure { Log.w(TAG, "run record unavailable, running without it", it) }.getOrNull()
            } ?: RunSink.NONE
            val trace = record?.takeIf { traceTicket != null }?.let { run ->
                resources.acquire(Dispatchers.IO, traces.store::finish) { traces.start(traceTicket, run.runId) }
            }
            // にインストールされているアプリ: 各タスクを 1 回読みます。読めない場合は無視してください。参考にしてください
            val apps = try { phoneScreen.listApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val speed = FeatureFlags.get(service).speedFlags()
            // 記憶と個人スキル: プロセス内の同じコピー。メインスイッチがオフの場合、メモリは与えられません (検索なし、注入なし、抽出なし、メモリツールなし)。 今回の
            val switches = memoryRuntime.settings().switches.value.let { sw ->
                sw.copy(learnSkills = env.evalHooks.learnSkills ?: sw.learnSkills, autoRemember = env.evalHooks.rememberMemory ?: sw.autoRemember)
            }
            val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
            val profileSnapshot = if (switches.useMemory) withContext(Dispatchers.IO) {
                runCatching { memoryRuntime.profile().snapshot() }.onFailure { Log.w(TAG, "profile unavailable", it) }.getOrDefault(emptyList())
            } else emptyList()
            // ユーザーの発言：記憶した名言やユーザーの要望に沿って書かれたスキルの名言は全て適宜チェックされる（記憶の中だけでタスククリアが始まる）
            val utterances = UserUtterances()
            // 名前変更前に蓄積された個人スキル: 内部の古いツール名はロード時に現在のものに置き換えられます
            val catalog = GuideCatalog.withPersonal(skills, memoryRuntime.skills(), legacyNames()::rewrite)
            // 内蔵ブラウザ: 各タスクには独自のバックグラウンド Web ページ表示、タグ プール、およびスクリプト エンジンがあり、これらは URL が初めて開かれたときに構築されます (携帯電話のみを使用するタスク用には構築されていません)。 システムに WebView がない場合、
            // は Web ツールを提供しません
            val webInstalled = runCatching { WebViewCompat.getCurrentWebViewPackage(service) != null }.getOrDefault(false)
            val web = if (!webInstalled) null else LazyWebPort {
                val stack = resources.acquire(Dispatchers.Main, WebStack::close) { WebStack.create(
                        service, key, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                        // この会話のワークスペースをダウンロードしてアップロードします。これはアプリのプライベート ディレクトリ内の単なるフォルダーであり、サンドボックスをインストールする必要はありません。
                        workspace = SandboxDirs(service).let { d -> WebStack.Workspace(d.workspace(conversationId), d.gate, d.tree) },
                    ) }
                webStack = stack
                // モデルで web_keep_open とマークされたページは、タスクが完了するとバックグラウンドに残され、このラウンドの名前で記録されます (ラウンドの下部がダイアログに表示され、閉じられた場合は文が追加されます)
                stack.keepOrigin = { KeptOrigin(conversationId, _runId.value, goal) }
                side.launch {
                    // Web インターフェイスでは、Web ページに表示されている画面をピクチャ イン ピクチャで表示します。インターフェースを変更するときは、まず前の画面をクリアしてください。表示方法については、「WebPreview」を参照してください。
                    var last: String? = null
                    while (true) {
                        delay(WEB_FRAME_MS)
                        val surface = dispatcher?.surface
                        if (surface != last) {
                            _frames.value = null
                            last = surface
                        }
                        // 現在のラベルが借用ページの場合、ユーザーの目の前にあり、引き継ぎ時のように画面は表示されません。
                        val show = WebPreview.of(onWeb = surface == WEB, hasTab = stack.hasVisibleTab, handedOver = stack.handedOver || stack.onBorrowedTab)
                        when (show) {
                            WebPreview.Frames -> withContext(Dispatchers.IO) { stack.host.latestFrame() }?.let { _frames.value = it }
                            WebPreview.Hidden, WebPreview.NoPage -> _frames.value = null
                            WebPreview.Off -> Unit
                        }
                        _webOpen.value = show.open
                    }
                }
                stack.screen
            }
            if (conditionCheck && !webInstalled) return TaskState.Failed(tr(R.string.fail_no_web), FailKind.Environment)
            // 借用ページ: 最初に Web ページを作成し、それを現在のラベルとして借用します。ユーザーがタッチすると一時停止します（現時点では携帯電話の画面で操作している場合はカウントされません）
            if (pageLoan != null && web != null && web.prepare()) webStack?.let { stack ->
                withContext(Dispatchers.Main) {
                    stack.borrowedTouches = { pageLoan.touches }
                    UserBrowser.existing()?.loans?.bind(pageLoan, object : PageLoans.Borrower<WebTab> {
                        override fun take(tab: WebTab) = stack.borrow(tab)
                        override fun giveBack(tab: WebTab) = stack.giveBack(tab)
                        override fun swap(prev: WebTab, next: WebTab) = stack.swapBorrowed(prev, next)
                        override fun adopt(tab: WebTab) = stack.adopt(tab)
                        override fun touched() {
                            if (stack.onBorrowedTab && !(phoneScreen.acquired && dispatcher?.surface != WEB)) userOnPage()
                        }
                    })
                }
            }
            // この会話でユーザーによって送信された画面 (会話とともにワークスペースに保存されます)
            val userScreens = (service.application as MikaApp).userScreens.source(conversationId)
            val stepLimit = if (conditionCheck) null else FeatureFlags.get(service).taskStepLimit()
            val runSpec = RunSpec(RunId(key), goal, conversationId,
                priorTurns,
                provenance, if (conditionCheck) setOf("web", "sandbox") else null, readOnly = conditionCheck,
                stepBudget = stepLimit)
            // 外部 MCP サーバーはメイン タスクにのみ接続されています。親タスクの承認をバイパスして評価の再現性に影響を与えることを避けるため、サブタスク/条件チェック/評価は接続されていません。
            if (!conditionCheck) {
                // 接続されているリソースはその場で返却されます。最後にスコープがクローズされた場合（キャンセル、失敗、接続途中での停止を含む）、stdioサーバープロセスとサンドボックスのリースは解放されます。
                mcpConnections = (service.application as MikaApp).mcpServers.connectionsFor(
                    resources,
                    com.wangzan101.mika.mcp.ConversationMcpResultStore(SandboxDirs(service), conversationId),
                    conversationId,
                )
                // 接続が失敗した場合、または合計制限時間を超えた後に接続に失敗した場合は、タスクは中断されずにタスク カードにプロンプ​​トのみが表示されます。詳細は設定ページの健全性ステータスにあります
                mcpConnections?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                    val store = (service.application as MikaApp).mcpServers
                    val names = store.servers.value.associate { it.id to it.name }
                    (_state.value as? TaskState.Running)?.let {
                        publish(it.copy(note = tr(R.string.mcp_servers_run_failed, failures.joinToString(", ") { f -> names[f.first] ?: f.first })))
                    }
                }
            }
            val runApproval = approvalControl.start(remote = provenance.origin == RunOrigin.Mcp).also { approval = it }
            val childRunner = if (provenance.origin == RunOrigin.Ui && web != null) ChildAgentRunner(
                create = { childSpec ->
                    val childTools = toolDispatcher(accessedPhone, model.capabilities.vision,
                        web = web, webPacing = { PagePacing.human() }, webPlacePace = { PlacePace.of(catalog.sitePace(it)) },
                        webIdleScroll = { IdleScroll(Random.Default) },
                        appPacing = { pace.appPacing() }, appPace = { PlacePace.of(catalog.appPace(it)) }, appIdleScroll = { IdleScroll(Random.Default) },
                        sandbox = sandbox, workspace = { conversationId }, runSpec = childSpec,
                        conversationId = conversationId, inlineScreen = speed.inlineScreen,
                        adaptiveVision = speed.adaptiveVision)
                    AgentLoop(model, childTools, SystemPrompt.identity(), maxSteps = childSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                        // サブタスクは問い合わせ/自律に続きます。また、要求されたときにレベルをパスし、お金がかかるサブタスクのステップは実行されません。
                        confirmations = runApproval.gate(), approvals = runApproval, autonomous = runApproval.startedAutonomous,
                        reviewFinalAnswer = false, flags = speed, outwardPacing = OutwardPacing.human())
                },
                store = (service.application as MikaApp).childRuns,
                ownerAlive = { job?.isActive == true },
            ).also { children = it } else null
            val tools = toolDispatcher(accessedPhone, model.capabilities.vision, catalog, recordFrames = true, vision = vision?.let { VisionService(it, BitmapImageOps) },
                // は、Mika が設定で自身を変更できる場合にのみ、settings_list と settings_set をモデルに送信します。
                selfConfig = if (FeatureFlags.get(service).selfConfig()) {
                    com.wangzan101.mika.settings.SelfConfigImpl(FeatureFlags.get(service))
                } else null,
                stableRefs = speed.inlineScreen, inlineScreen = speed.inlineScreen,
                adaptiveVision = speed.adaptiveVision,
                deepLinks = speed.deepLinks, memory = memory, profile = if (memory != null) memoryRuntime.profile() else null,
                utterances = utterances, conversationId = conversationId,
                web = web,
                // 同じ Web サイトをページごとに表示します。ページを変更する前に一時停止を埋めて、中断することなく数秒で開きます。
                webPacing = { PagePacing.human() },
                // 滞在中は、人間のようにゆっくりとページを数回スクロールしてください。
                webIdleScroll = { IdleScroll(Random.Default) },
                // アプリのステップバイステップ操作: アクションの間にしばらく一時停止します。ブラウジング速度を制限するアプリは一時停止時間が長くなり、画面数が制限されます (設定の操作リズムに応じて)
                appPacing = { pace.appPacing() },
                appIdleScroll = { IdleScroll(Random.Default) },
                // アップロードは、この会話のワークスペース内の通常のファイルのみを認識します (サンドボックスと同じパス規則のセット)。スキルディレクトリとワークスペースの外はすべて範囲外です
                workspacePath = web?.let { { p: String -> sandbox.uploadCheck(conversationId, p) } },
                // ワークスペース = 会話: 同じ会話内の質問は前回のラウンドのファイルを引き続き使用し、異なる会話は相互に表示されません。
                sandbox = sandbox, workspace = { conversationId },
                onSandboxCommand = { sandboxNote(StatusCopy.sandboxCommand(it, sandboxOnline())) },
                onSandboxProgress = { sandboxNote(StatusCopy.sandboxProgress(it, sandboxOnline())) },
                onSandboxPreparing = { sandboxNote(StatusCopy.RUNTIME_PREPARING) },
                onSandboxScript = { sandboxNote(StatusCopy.sandboxScript(sandboxOnline())) },
                imageOps = BitmapImageOps, runSpec = runSpec,
                userScreens = userScreens,
                scheduleTrigger = if (provenance.origin == RunOrigin.Ui) { trigger -> TriggerRuntime.schedule(service, trigger) } else null,
                scheduleAdmin = if (provenance.origin == RunOrigin.Ui) LocalScheduleAdmin(service) else null,
                childHost = childRunner,
                // ユーザーのリクエストに応じて個人スキルを書き換えます (自習で収集したものと同じ場所に配置し、設定ですぐに確認できます)
                personalSkills = memoryRuntime.skills(),
                // この会話の過去の実行記録。history_read は runSpec に含まれるラウンドだけを参照する。
                runRecords = RunStore(service).reader(),
                mcpServers = mcpEndpoints(),
                // ユーザーが明示的に許可したときだけ root ツールを登録する。条件チェックや子タスクには渡さない。
                root = phoneServices.shellManager.root.takeIf { !conditionCheck && it.commandsAllowed() }
                    ?.let { r -> RootCommands(RootShell::su, r::commandsAllowed) { a -> if (a == null) r.suGone() else r.commandsDenied(a) } })
            dispatcher = tools
            val agent = AgentLoop(
                model, tools,
                SystemPrompt.identity(),
                // アシスタントが切断または移動されました。この手順では携帯電話を使用する場合のみチェックを入れてください。
                gate = { w ->
                    if (phoneScreen.acquired && dispatcher?.surface != WEB) phone?.screen?.let { awaitUserPick(it, taskGate, w) }
                    taskGate.pass({ phoneScreen.acquired && dispatcher?.surface != WEB }, w)
                }, knowledge = knowledge, sink = sink, trace = trace, installedApps = { apps },
                // チャネル選択に関する事実: どのアプリがインストールされているか、どの Web サイトがミカにログインしているか、内蔵ブラウザがあるかどうか
                channelFacts = {
                    val registry = WebSites.registry(service)
                    ChannelFacts(
                        installed = apps.map { it.first }.toSet(),
                        loggedInSites = withContext(Dispatchers.IO) { registry.list().filter { it.loggedInByUser }.map { it.host }.toSet() },
                        webAvailable = web != null,
                        challengedSites = withContext(Dispatchers.IO) { registry.challengedSites() },
                    )
                },
                maxSteps = env.evalHooks.maxSteps ?: runSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                reviewFinalAnswer = false,
                inputTimeoutMs = TaskService.INPUT_TIMEOUT_MS,
                // 問い合わせ / 自律: 問い合わせるときは、最初にお金を要求するだけです (確認レベルと request_confirmation があります)。自立していれば、支払いについて尋ねることさえしません。
                // 作業開始時に質問し、途中で独立に変更し、次のステップからは質問しなくなります。仕事を始めるときに自主的に質問し、質問に切り替えて次のタスクに任せる
                confirmations = runApproval.gate(),
                approvals = runApproval,
                autonomous = runApproval.startedAutonomous,
                confirmTimeoutMs = TaskService.CONFIRM_TIMEOUT_MS,
                selfPackage = service.packageName,
                flags = speed,
                // 只在 lightSteps 打开、轻量模型能调工具且与主模型不是同一份配置时生效
                router = routedLight?.let { ModelRouter(model, it) },
                intentJudge = if (provenance.origin == RunOrigin.Ui) light ?: model else null,
                // ルート選択は補助的な判断です。メインモデルではなく軽量モデルを使用してください。
                routes = if (speed.replay) RouteMatcher(light ?: model, routeStore, knowledge) else null,
                skills = catalog,
                memory = memory,
                interaction = service.interaction(conversationId),
                profileSnapshot = profileSnapshot,
                streamReplies = true,
                userScreens = userScreens,
                // 同じ Web サイトまたはアプリ上で他の人に連絡して送信する場合: 2 回の試行の間にランダムな時間が経過します。
                outwardPacing = OutwardPacing.human(),
            )
            loop = agent
            liveFeed = agent.events.subscribe(liveEvents)
            mcpExternalId?.let { mcpEvents = (service.application as MikaApp).remoteTasks.attach(it, agent.events) }
            kit = LearnKit(switches, memory, utterances, tools, agent, catalog, knowledge, model, light, runStore, agent.flags.replay, priorTurns)
            suspend fun <T> io(fallback: T, call: () -> T): T =
                withContext(Dispatchers.IO) { runCatching(call).getOrDefault(fallback) }
            // 引き継ぎ中にホーム画面のアクセシビリティ イベントのみをサブスクライブします。通常は誰もそれらをサブスクライブせず、サービスはイベントをコピーしません。
            var counter: HandoverActivityCounter? = null
            var counting: Job? = null
            val handover = Takeover(object : TakeoverPort {
                override suspend fun agentTopTask() = phone?.let { p -> p.display.current?.let { d -> io(-1) { p.shell.topTaskId(d.displayId) } } } ?: -1
                override suspend fun targetTask() = phone?.screen?.target?.value?.taskId ?: -1
                override fun targetPackage() = phone?.screen?.target?.value?.packageName
                override suspend fun packageOf(taskId: Int) = phone?.let { p -> io(null) { p.shell.packageOfTask(taskId) } }
                override suspend fun displayOf(taskId: Int) = phone?.let { p -> io(-1) { p.shell.displayOfTask(taskId) } } ?: -1
                override suspend fun moveToMain(taskId: Int) = moveTask(taskId, Display.DEFAULT_DISPLAY)
                override suspend fun moveBack(taskId: Int) = moveBackToAgent(taskId)
                override fun setSound(packageName: String, on: Boolean) {
                    val p = phone ?: return
                    // 対象アプリが引き渡された場合、対象が属するシェアがカウントされます。それ以外の場合は、元のシェアがカウントされます。
                    val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { copyUser(it.userId) }
                    if (on) {
                        if (!p.audio.release(packageName, user)) Log.w(TAG, "could not restore sound of $packageName for takeover")
                    } else {
                        p.audio.opened(packageName, user)
                    }
                }
                override fun unmute(packageName: String) {
                    val p = phone ?: return
                    val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { copyUser(it.userId) }
                    if (!p.audio.unmute(packageName, user)) Log.w(TAG, "could not restore sound of $packageName from pip takeover")
                }
                override fun watchInput(packageName: String) {
                    counting?.cancel()
                    val c = HandoverActivityCounter(packageName).also { counter = it }
                    // 今すぐ購読: 次に起こる最初の操作を見逃すことはできません
                    val a11y = phone?.a11y ?: return
                    counting = side.launch(start = CoroutineStart.UNDISPATCHED) {
                        a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                            c.onEvent(Display.DEFAULT_DISPLAY, e.packageName, e.eventType, e.className, e.eventTime, e.windowId to e.source?.hashCode())
                        }
                    }
                }
                override suspend fun handoverActivity(): HandoverActivity? {
                    val c = counter ?: return null
                    val pm = service.packageManager
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull()
                    return HandoverActivity(c.inputCount, stoppedAt(phone?.a11y?.let { mainWindowTitle(it, c.packageName) }, label, c.lastActivity))
                }
                override fun stopWatching() {
                    counting?.cancel()
                    counting = null
                    counter = null
                }
                override suspend fun resume(result: HandBack) { inbox.send(RunInput.HandBack(result)) }
                override fun cancel() { inbox.cancel() }

                // Web インターフェイス: 現在のタブ (同じページ) をメイン画面の引き継ぎインターフェイスに移動します。モバイルインターフェイスはnullを返し、タスクを移動します
                override suspend fun showWeb(reason: String, key: Int): Boolean? {
                    // が開かれていない場合、Web インターフェイスには表示されません。
                    val w = webStack ?: return null
                    if (tools.surface != WEB) return null
                    val message = takeoverMessage(_state.value)
                    return withContext(Dispatchers.Main) {
                        w.showForTakeover(reason, message, key, WebHandover.taskActions(service, key, this@TaskRun.key)) { takeover?.waitKey }
                    }
                }

                override fun webBusy() = WebHandover.loginOpen

                override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? =
                    webStack?.let { w -> withContext(Dispatchers.Main) { w.returnFromTakeover(loggedIn) } }
            }, side, queue = service.takeoverQueue)
            takeover = handover
            handoverRef = handover

            side.launch {
                agent.state.collect {
                    if (it == TaskState.Idle) return@collect
                    if (it is TaskState.Running && it.preview != null && (gate?.movedAwayPending == true || gate?.userOnPagePending == true)) return@collect
                    val previous = _state.value as? TaskState.Running
                    if (it is TaskState.Running && previous != null && previous.preview != it.preview &&
                        previous.copy(preview = it.preview) == it) {
                        // テキストの増加はチャットのみを更新します。通知、テイクオーバー、デバイスのウェイクアップをトークンごとに再実行する必要はありません。
                        synchronized(publishLock) { if (!ended) setState(it) }
                        return@collect
                    }
                    handover.onState(it)
                    publish(it)
                    env.evalHooks.answerFor(it)?.let { answer -> agent.answerInput(answer) }
                    env.evalHooks.decisionFor(it)?.let { approved -> agent.answerConfirmation(approved) }
                }
            }
            // 他のタスクの引き継ぎがキューに出入りする場合、このタスクの引き継ぎ通知もそれに応じて変更する必要があります (キューの先頭に入れられるか、最後にもう 1 つ追加されます)。
            if (parallel) side.launch { service.takeoverQueue.changes.collect { service.refreshNotifications(this@TaskRun) } }
            side.launch {
                combine(handover.stage, handover.problem) { stage, _ -> stage }.collect {
                    _takeoverStage.value = it
                    service.refreshNotifications(this@TaskRun)
                }
            }
            return withContext(tally) { agent.run(runSpec, inbox) }.also { usedGroups = agent.usedGroups.toList() }.let(::withRetestHint)
        } finally {
            handoffUnconsumed()
            side.cancel()
            _frames.value = null
            _webOpen.value = false
            synchronized(publishLock) { ended = true }
            if (awake != null && service.hasQueuedRuns()) wakeHandoff = service.wakeLock.lease().also { it.acquire() }
            awake?.close()
            awake = null
            shellDeath?.let { d -> runCatching { shellBinder?.unlinkToDeath(d, 0) } }
            loop = null
            children = null
            // 早めに閉じてください。画面と Web ページが後で終了するまで待つ必要があります。リソースが閉じられると、再び閉じられます (1 回だけ、繰り返されません)。
            mcpConnections?.close()
            mcpConnections = null
            mcpEvents?.close()
            liveFeed?.close()
            approval?.let { approvalControl.end(it) }
            gate = null
            takeover = null
            handoverRef = null
            sideScope = null
            // 借用したページをブラウザページに戻します（Webページを閉じる前）
            withContext(Dispatchers.Main + NonCancellable) { runCatching { UserBrowser.existing()?.loans?.end(key) } }
            withContext(Dispatchers.IO + NonCancellable) {
                // 前の手順でエラーが発生した場合は、画面と Web ページの表示も表示されます (逆の順序)。
                try {
                    // まず、Mika 画面上のどこにアプリを保持するかを決定し (残りのアプリをメイン画面の後ろに移動し、音が出ているアプリをオフにします)、画面を放してからミュートに戻します。残りのアプリが突然音を出すことはありません。
                    val apps = phone?.takeIf { !shellDied }?.let { p ->
                        p.display.current?.let { d -> AppsAfterTaskEnd.finish(p.shell, d.displayId, afterTask) }
                    }
                    record?.let { r ->
                        apps?.let { r.event(RunEvent.State(System.currentTimeMillis(), "apps_after_task", it.detail)) }
                        r.event(tally.event(System.currentTimeMillis()))
                        r.close()
                        if (BuildConfig.DEBUG) runStore?.mirrorToExternal(r.runId)
                    }
                    // エラーを回復するときは、「実行中」の登録も削除する必要があります。そうしないと、このレコードは復元されません。
                    try { mute?.restoreAll() } finally { recovery.endRun(key) }
                    watcher?.close()
                } finally {
                    resources.close()
                }
            }
            phone = null
            // 屏释放后、对外报结束前清掉:界面看到任务结束时已经没有屏了
            _displayId.value = -1
            _appShown.value = false
            if (connection != null) phoneServices.sharedShell.release()
        }
    }

    /** 携帯電話側：携帯電話で使用したタスクのみ利用可能です。 */
    private class PhoneSide(
        val shell: com.wangzan101.mika.shell.IMikaShell, val a11y: ScreenAccessibility, val screen: AndroidScreen,
        val display: LazyAgentDisplay, val audio: AppMuter,
    )

    /**
     * このタスクのミュート ルール ([AppMuter] を参照): ミカ自身はミュートされていません。デスクトップ エントリを持たないシステム パッケージ (システム インターフェイス、選択ボックスなど) はミュートされません。
     * ユーザーは、ホーム画面が静かではないことがわかります。静かでない場合は、タスクの進行状況を知らせる音が鳴る可能性があることに注意してください。
     */
    private fun appMuter(shell: com.wangzan101.mika.shell.IMikaShell, audio: AudioMute): AppMuter {
        val pm = service.packageManager
        val own = service.packageName
        fun system(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(service).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !system(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = { pkg ->
                muteFailedFor = pkg
                publish(_state.value)
            },
        )
    }

    /** タスクが属するユーザー → どのコピーをミュートするか: 元のコピー (または読み取れない) は null で、他のユーザー コピーにあるものがそのユーザー番号です。 */
    private fun copyUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != AppCopyReader.myUser }

    @Volatile private var handoverRef: Takeover? = null
    @Volatile private var sideScope: CoroutineScope? = null

    /**
     * システムはユーザーにホーム画面でどれを開くかを尋ねます ([AndroidScreen.userPick] を参照): ここで停止します。ユーザーは画面上でカプセルを選択するように求められ、選択後は自動的に続行されます。
     * 選択したアプリはユーザーが個人的に処理する必要があり、ホーム画面に残されます。ユーザーが処理を完了してから続行してください。待たずに直接返却。
     */
    private suspend fun awaitUserPick(screen: AndroidScreen, gate: TaskGate, setWaiting: (TaskState?) -> Unit) {
        val pick = screen.userPick.value ?: return
        setWaiting(TaskState.Held(TaskGate.userStep(TaskGate.pickCopy(pick.app))))
        val r = screen.awaitUserPick() as? AndroidScreen.PickResult.NeedsUser ?: return
        gate.pauseForUser(TaskGate.finishCopy(r.app), setWaiting)
        screen.afterUserStep(r)
    }

    /**
     * 変更後に対象のアプリをミュートし (開始前にミュートされている可能性が高いですが、ミュートされていないものは次のとおりです)、ユーザーによってホーム画面に移動されたかどうかを監視します。
     * Mika 別のアプリが画面上にポップアップ表示されると (ターゲット アプリからクリックすると、別のコピーがアップロードされます)、最初に表示されたときはミュートになります。ミュートするには、「パッケージ + ユーザー」を押すと、他のアプリが単独でミュートされます。
     * ユーザーの引き継ぎ中に画面に表示されているものはユーザーのものであり、消えることはありません。
     */
    private fun watchTarget(p: PhoneSide, states: DeviceStateWatcher) {
        val side = sideScope ?: return
        side.launch(Dispatchers.IO) {
            p.screen.foregroundApps().collect { t ->
                if (handoverRef?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                // ウィンドウは非常に密になります。2 つの時間の間には少なくともしばらく時間が必要で、中央の変更は 1 回合成されます。
                delay(FOREGROUND_GAP_MS)
            }
        }
        side.launch(Dispatchers.IO) {
            p.screen.target.filterNotNull().collectLatest { t ->
                _appShown.value = true
                if (handoverRef?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                // 対象のAppが存在する場合、launch_appはすでに画面を構築していることを意味します。
                val displayId = p.display.current?.displayId
                if (t.taskId >= 0 && displayId != null) {
                    states.taskMovedAway(displayId, t.taskId).collect {
                        // ユーザーが「処理します」をクリックすると、アプリがホーム画面に表示されます。
                        if (handoverRef?.handedOver == true) return@collect
                        gate?.onTaskMovedAway()
                        publish(_state.value) // 次のツールによる画面の変更を待たずに、表示がすぐに一時停止されます。
                    }
                }
            }
        }
    }

    /**
     * ユーザーがブラウザ ページでこのタスクに割り当てられたページをタッチしました。作業中にすぐに一時停止が表示され、画面を変更する次のツールの前に停止しました。
     * ユーザーは「続行」をクリックして続行します ([TaskGate.onUserOnPage] を参照)。ユーザーの応答待ち、引き継ぎなどの状態は変わりません。メインスレッド。
     */
    private fun userOnPage() {
        if (_state.value !is TaskState.Running) return
        val g = gate ?: return
        g.onUserOnPage()
        publish(_state.value)
    }

    /** は、ミッション完了後に結果を決定し、学習するために使用されます。 [execute]を記入してください。 */
    private class LearnKit(
        val switches: MemorySwitches, val memory: MemoryStore?, val utterances: UserUtterances?, val dispatcher: ToolDispatcher,
        val agent: AgentLoop, val catalog: GuideCatalog, val knowledge: KnowledgeStore,
        val model: ChatModel, val light: ChatModel?, val runStore: RunStore?,
        val routesOn: Boolean, val priorTurns: List<Turn>,
    )
    @Volatile private var kit: LearnKit? = null

    /** 停止時に書き込まれていたテキスト (モデルリクエストが流出した目に見えるテキスト)。終了前に取得され、終了後に最終状態に変更されます。 */
    fun draftText(): String? = (_state.value as? TaskState.Running)?.preview?.takeIf(String::isNotBlank)

    /** 停止後に説明することはありますか。少なくとも 1 つのことを実行したか、停止したときにすでに何かを書いていました。 */
    fun hasStopMaterial(draft: String?): Boolean = synchronized(activityLog) { activityLog.isNotEmpty() } || !draft.isNullOrBlank()

    /**
     * ユーザーが停止した後: 今回何が行われたかを説明するために数行を書き込み ([StopSummary] を参照)、実行レコードを読み取るだけで、何も操作しません。
     * 軽量モデル優先のため、メインモデルはございません。モデル、エラー、タイムアウトがない場合は、レコードを押すだけでプロセスが開始されます。説明するものが何もない場合は null。
     */
    suspend fun stopSummary(draft: String?): String? {
        val k = kit
        val runId = _runId.value
        val recorded = withContext(Dispatchers.IO) { runId?.let { id -> runCatching { k?.runStore?.events(id) }.getOrNull() } }
        val events = recorded?.takeIf { e -> e.any { it is RunEvent.Activity } } ?: synchronized(activityLog) { activityLog.toList() }
        if (!StopSummary.worthSaying(events, draft)) return null
        // はモデルが起動される前に停止しました (準備段階): 今すぐ取得するには設定を押してください
        val (main, light) = if (k != null) k.model to k.light else withContext(Dispatchers.IO) {
            runCatching { env.modelFactory(service, session) }.getOrNull() to runCatching { env.lightModelFactory(service, session) }.getOrNull()
        }
        val labels = ToolLabels(knownTools())
        return StopSummary(light ?: main, main).summarize(k?.agent?.taskText ?: goal, events, draft, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * は、結果がユーザーに引き渡された後に呼び出されます (引き渡された応答を遅らせたり変更したりすることなく): 応答はユーザーの要求に従って決定されます ([OutcomeJudge]、軽量モデルが推奨されます)。
     * は走行記録とこのラウンドの会話に記録されます。完了したルート ([LearnGate]) のみが記録され、再生されたルートは成功として記録されます。
     * は諦め・失敗・中止（エンディングはクリア済み）の判定は行われずnullが返ります。判定されなかった場合は[RunOutcome.Unknown]となります。
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? {
        val k = kit ?: return null
        if (end !is TaskState.Succeeded) return null
        val judged = OutcomeJudge(k.light ?: k.model, k.model).judge(k.agent.taskText ?: goal, end.answer, k.priorTurns)
        val runId = _runId.value
        if (runId != null) withContext(Dispatchers.IO) {
            k.runStore?.append(runId, judged.event(System.currentTimeMillis()))
            if (BuildConfig.DEBUG) k.runStore?.mirrorToExternal(runId)
            runCatching { conversations.updateTurn(conversationId, runId) { it.copy(result = judged.outcome.wire) } }
                .onFailure { Log.w(TAG, "could not save run outcome", it) }
        }
        k.agent.noteOutcome(judged.outcome)
        // 完了したルートのみがルートとして記録されます。プレースメントの実行イベントから取得されます
        if (runId != null && LearnGate.shouldSaveRoute(k.routesOn, end, judged.outcome, runId, goal)) k.runStore?.let { s ->
            saveRoute(goal, withContext(Dispatchers.IO) { s.events(runId) }, k.dispatcher.index)
        }
        return judged
    }

    /**
     * 結果通知の送信後に呼び出されます。使用した個人スキルの結果を書き留め、記憶を取得し、スキルをドラフトします。プロンプトはこのラウンドの結果カードにハングアップされ、ダイアログに保存されます。
     * はプロセス レベルのスコープで実行され、サービスが終了しても中断されません。
     */
    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) {
        val k = kit ?: return
        val runId = _runId.value
        val learning = RunLearning(
            k.switches, k.memory, memoryRuntime.skills(), k.catalog,
            extractor = k.memory?.let { MemoryExtractor(k.light ?: k.model) },
            drafter = SkillDrafter(k.model),
            appDrafter = AppKnowledgeDrafter(k.light ?: k.model),
            appDrafts = memoryRuntime.appKnowledge(),
            ids = { UUID.randomUUID().toString() },
            autoApprove = env.evalHooks.autoApproveSkillDrafts,
            yieldToForeground = { while (service.hasActiveRuns()) delay(500) },
        )
        val notes = learning.learn(RunAftermath(
            conversationId, runId, k.agent.taskText ?: goal, end, k.utterances.takeIf { k.memory != null }?.all.orEmpty(), k.dispatcher.savedChanges, k.agent.usedMemoryIds,
            k.dispatcher.definitions().map { it.name }.toSet(), k.dispatcher.index,
            events = { withContext(Dispatchers.IO) { runId?.let { k.runStore?.events(it) }.orEmpty() } },
            origin = provenance.origin,
            knowledge = k.knowledge,
            outcome = outcome ?: RunOutcome.Unknown,
        ))
        if (runId == null || notes.memoryNotes.isEmpty() && notes.usedMemory.isEmpty() && notes.skillNotes.isEmpty()) return
        memoryRuntime.publish(notes)
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.updateTurn(conversationId, runId) { it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes) }
            }.onFailure { Log.w(TAG, "could not save memory notes", it) }
        }
    }

    /** 成功した操作はルートとして記録されます。記録に失敗しても (記録すべきではない、ディスクへの書き込みに失敗しました)、タスクの結果には影響しません。 */
    private suspend fun saveRoute(goal: String, events: List<RunEvent>, tools: ToolIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val route = RouteRecorder.record(goal, events, System.currentTimeMillis(), tools) ?: return@runCatching
            val versions = route.packages.mapNotNull { p ->
                runCatching { service.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()?.let { p to it }
            }.toMap()
            routeStore.putReplacing(route.copy(appVersions = versions))
        }.onFailure { Log.w(TAG, "route not saved", it) }
    }

    /** ホーム画面上のパッケージの上部ウィンドウのタイトル。ウィンドウは上から下に配置されます。 */
    private fun mainWindowTitle(a11y: ScreenAccessibility, packageName: String): String? = runCatching {
        a11y.windowsOn(Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { w ->
            w.title?.toString()?.takeIf { it.isNotBlank() && w.root?.packageName?.toString() == packageName }
        }
    }.getOrNull()

    /** サンドボックス ステップの手順は、タスク カード (現在のステップのメモ) に書かれています。 */
    private fun sandboxNote(text: String) {
        val s = _state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    private fun sandboxOnline() = (sandbox.status() as? SandboxStatus.Ready)?.network == true

    /**
     * が別のタスクが [app] を解放するのを待っている間、外部の進行状況は「別のタスクが [app] を終了するのを待っている」と書き込まれ、まだ実行中であり、一時停止されていないため、これに対処する必要はありません。
     * 待った後（手を離す、待ちきれない、立ち止まる）、待つ前の文に戻します。動作しなくなるまでは変更しないでください (たとえば、停止して処理を要求するなど)。
     */
    private suspend fun <T> waitingForApp(app: String, wait: suspend () -> T): T {
        val before = _state.value as? TaskState.Running ?: return wait()
        val waiting = before.copy(progress = RunProgress.WaitingForApp(app))
        publish(waiting)
        try {
            return wait()
        } finally {
            if (_state.value == waiting) publish(before)
        }
    }

    private fun publish(state: TaskState) {
        // が移動された後、ゲートで停止される前に、ループはまだ推論を行っている可能性があります。最初に一時停止を外の世界に表示します。
        // ループ自体によって報告される最終状態は送信されません。最終状態は、実行中の番号を記憶した後で [end] によってのみ書き込まれます。それ以外の場合、インターフェイスは最初に終了を受信し、番号を取得できません。
        if (!TaskService.loopMayPublish(state)) return
        val shown = when {
            state is TaskState.Running && gate?.movedAwayPending == true -> TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY))
            // ユーザーが借用したページで操作: レベルに到達する前に一時停止も表示されます。
            state is TaskState.Running && gate?.userOnPagePending == true -> TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))
            else -> state
        }
        synchronized(publishLock) {
            if (ended) return
            setState(shown)
            awake?.onState(state)
        }
        service.refreshNotifications(this)
    }

    @Suppress("DEPRECATION")
    private fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** Web ページの乗っ取りインターフェイスの上部にある指示: 乗っ取りの理由がある場合は、支払いの際に製品、金額、住所をご持参ください。 */
    private fun takeoverMessage(state: TaskState): String? {
        val w = state as? TaskState.WaitingTakeover ?: return null
        return listOfNotNull(
            w.message.takeIf { it.isNotBlank() },
            w.item?.let { tr(R.string.payment_item, it) },
            w.amount?.let { tr(R.string.payment_amount, it) },
            w.address?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }
    }

    private companion object {
        /** Web インターフェースのピクチャー・イン・ピクチャー画像間の間隔。 */
        const val WEB_FRAME_MS = 500L
        const val SHELL_WAIT_MS = 15_000L
        const val DISPLAY_RETRY_MS = 1_000L
        /** ミカの画面上のフロントエンド アプリを再読み込みするまでに、少なくともこのくらいの時間がかかります。 */
        const val FOREGROUND_GAP_MS = 300L
        val WAITING_DISPLAY: String get() = tr(R.string.task_waiting_display)
        const val A11Y_WAIT_MS = 5_000L
        val NO_PHONE_HELPER: String get() = tr(R.string.task_no_phone_helper)
        val NO_PHONE_HELPER_USB: String get() = tr(R.string.task_no_phone_helper_usb)
        const val TAG = "MikaTask"
    }
}
