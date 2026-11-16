package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationTitler
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.ChannelInteraction
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverActivity
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.StatusCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import com.wangzan101.mika.core.cap.web.KeptPageClose

/**
 * この通知は引き続き送信され、通知バーで点灯しますが、音も振動もせず、バナーもポップアップしません。 システムのグループ リマインダー ルールを使用します (グループ サマリー リマインダーのみが許可されます。これはグループ内の通常の通知です)。
 * グループには要約はありません)。これは、システム独自の「サイレント通知」と同じです。チャンネル変更も新しいチャンネルもありません。 Android 8 以降で利用可能で、Mika がサポートするすべてのシステムで使用できます。
 */
internal fun Notification.Builder.alertQuietly(): Notification.Builder =
    setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)

private const val QUIET_GROUP = "com.wangzan101.mika.quiet"

/**
 * フロントエンド サービス: エージェント仮想画面でタスクを実行します。単一タスクのステータスと実行は [TaskRun] にあり、実行中のタスクは [TaskRegistry] に登録されます。
 * スイッチのParallelTask​​s がオフになっている場合、一度に 1 つだけが実行され、他の会話から渡されたタスクは後ろのキューに入れられます (どこに渡されたとしても、タスクは失われません)。
 * を開くと、さまざまな会話のタスクを同時に実行でき、それぞれが仮想画面を占有します。上限は[ParallelCapability]の通りで、超過分はキューイングされます。
 * 各タスクには一連の通知があります (番号とボタンのリクエスト コードは [TaskRun.slot] で区切られ、ボタンには会話番号が表示されます)。
 * フロント常駐通知は、タスクの 1 つの進捗通知です。同時に複数のタスクがある場合は、「n 個のタスクが進行中」と表示されます。
 * タスクの実行中は画面が常にオンになります ([KeepAwake]、システム設定と明るさは変更されません)。ターゲット アプリはタスク中にミュートされます (元のモードは永続的であり、異常終了後の次回の起動時に復元されます)。
 * AgentLoop のゲートは [TaskGate] によって実装されます。画面はオフになるかロックが解除されるのを待ってロックされ、アプリはホーム画面に移動するかモバイル操作アシスタントが切断され、ユーザーが「続行」をクリックするまで一時停止されます。
 * エージェントは、引き継ぎを要求したり質問したりするときに、別の目を引く通知を送信します。引き継ぎプロセスについては、「[Takeover]」を参照してください。
 *
 * Android 15 では、specialUse:dataSync を使用するフロントエンド サービス タイプの 1 日の上限は 6 時間であり、セマンティクスが一貫していません。
 * specialUse には実行時の制限はなく、マニフェストでサブタイプを指定するだけです。
 */
class TaskService : Service() {
    internal val taskState: TaskCenterState get() = (application as com.wangzan101.mika.MikaApp).taskState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 前回の起動時に読み取られたスイッチ。オフの場合、レジストリの上限は常に 1 になります。 */
    @Volatile private var parallel = false
    private val center by lazy {
        TaskCenter<TaskRun>(::limit, { it.conversationId }, { it.key }, (application as com.wangzan101.mika.MikaApp).runStore,
            (application as com.wangzan101.mika.MikaApp).resources) { c, g ->
            TaskRun(c, g, this, scope, freeSlot(), parallel)
        }
    }
    private val outbox get() = (application as com.wangzan101.mika.MikaApp).outbox
    private val localChannel by lazy { LocalChannel { _, delivery ->
        if (delivery is Delivery.Ask) {
            center.byConversation(delivery.threadId)?.let(::refreshNotifications)
            SendOutcome.Delivered
        } else {
            val resultId = if (delivery is Delivery.Result && center.byConversation(delivery.threadId)?.parallel == false) ID_RESULT
                else nextResultId()
            notifyDelivery(this, delivery, resultId)
        }
    } }
    /** フロントデスク常駐通知が属するタスク。終了後も、別のタスクに変更されるまで保持してください。 */
    @Volatile private var foreground: TaskRun? = null
    private val notifyLock = Any()
    @Volatile private var lastStartId = 0

    internal val clipLock get() = center.clipLock
    internal val takeoverQueue get() = center.takeoverQueue
    /** 複数のタスクで共有される常時オンの画面ロック。 */
    internal val wakeLock by lazy { SharedWakeLock(ScreenWakeLock(this)) }

    private fun limit(): ParallelLimit {
        if (!parallel) return ParallelLimit(1, null)
        val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        return ParallelCapability.detect(Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(this).parallelMax(), taskState.secondDisplayFailed)
            .also { taskState._parallelLimit.value = it }
    }

    /** レジストリ内の使用されていない最小のスロット。レジストリのロック内でのみ呼び出されます。 */
    private fun freeSlot(): Int {
        val used = center.runs().map { it.slot }.toSet()
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    internal fun reservePackage(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.reserve(pkg, runKey, stillUsed)
    internal fun packageFree(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.appFree(pkg, runKey, stillUsed)
    internal suspend fun awaitPackage(pkg: String, runKey: String, timeoutMs: Long, free: suspend () -> Boolean) =
        center.awaitApp(pkg, runKey, timeoutMs, free)

    /** この会話が空の場合は、その場で会話を開始してください。タスクがある場合は、それを保管し、終了したら渡します ([onRunEnded])。チェックして同じロックに留まります。 */
    internal fun deferMessage(threadId: String, text: String) {
        if (center.deferOrStart(threadId, text)) start(this, text, threadId)
    }

    internal fun interaction(threadId: String) = ChannelInteraction(outbox, localChannel,
        ChannelPeer(LocalChannel.ID, "phone-owner", threadId))

    /** タスク完了後にバックグラウンドに残されたページ ([com.wangzan101.mika.cap.web.PageKeeper] を参照): サービスは終了しても戻らず、フロント デスク通知がそれに置き換えられます。 */
    private val keeper get() = (application as com.wangzan101.mika.MikaApp).pageKeeper

    internal fun otherRunHasDisplay(run: TaskRun) = center.running().any { it !== run && it.displayId.value >= 0 }
    internal fun hasActiveRuns() = center.running().isNotEmpty()
    internal fun hasQueuedRuns() = center.queued().isNotEmpty()

    private fun publishRuns() {
        val r = center.runs()
        taskState._runs.value = r
        taskState._activeConversations.value = r.map { it.conversationId }
    }

    private fun id(base: Int, run: TaskRun) = base + SLOT_STRIDE * run.slot

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // 残りのページが変更されました: 通知が変更されました。オフになっていてタスクがない場合、フォアグラウンドを終了してサービスを停止します。
        // は「維持 → 変更 / オフ」のみに従います。開始中のタスクでサービスを急いで停止しないように、先頭の「いいえ」はカウントされません。
        scope.launch {
            var had = false
            keeper.page.collect { p ->
                if (p == null && !had) return@collect
                had = p != null
                onKeptPage(p)
            }
        }
        // 未完了の実行は、アプリの起動時に処理されました。ここではステップが 1 つだけ追加され (プロセス内で 1 回だけ実行されます)、現在実行中の実行は除外されます。
        (application as com.wangzan101.mika.MikaApp).runRecovery.recover(center.runs().map { RunId(it.key) }.toSet())
    }

    private fun waitKeyOf(intent: Intent) = intent.getIntExtra(EXTRA_WAIT_KEY, -1).takeIf { it >= 0 }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (com.wangzan101.mika.quit.MikaQuit.blocksWork(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastStartId = startId
        // 通知ボタンにはタスク番号が含まれており、そのタスクに対してのみ動作します (タスクが終了すると有効になりません)。アプリケーションのボタンは表示されたタスクに作用します
        val run = if (intent?.hasExtra(EXTRA_RUN) == true) intent.getStringExtra(EXTRA_RUN)?.let(center::byKey) else taskState.shownRun
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                messageId = intent.getStringExtra(EXTRA_MESSAGE_ID))
            ACTION_START_TRIGGER -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
                triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY),
                triggerActionGoal = intent.getStringExtra(EXTRA_TRIGGER_ACTION),
                triggerThreadId = intent.getStringExtra(EXTRA_TRIGGER_THREAD))
            ACTION_START_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id ->
                if ((application as com.wangzan101.mika.MikaApp).remoteTasks.claimStart(id)) {
                    begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                        provenance = RunProvenance(RunOrigin.Mcp,
                            Attendance.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ATTENDANCE) } ?: Attendance.Unattended),
                        mcpExternalId = id)
                }
            }
            ACTION_CANCEL_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id -> center.runs().firstOrNull { it.mcpExternalId == id } }
                ?.let { r -> if (center.cancelIfQueued(r)) cancelQueued(r) else r.cancel() }
            ACTION_CANCEL -> if (run != null && center.cancelIfQueued(run)) cancelQueued(run) else run?.cancel()
            ACTION_RESUME -> run?.resume()
            ACTION_TAKE_OVER -> run?.takeOver(waitKeyOf(intent))
            ACTION_HAND_BACK -> run?.handBack(waitKeyOf(intent))
            ACTION_FINISHED_MYSELF -> run?.finishedMyself(waitKeyOf(intent))
            ACTION_SKIP -> run?.skip(waitKeyOf(intent))
            ACTION_PUT_ASIDE -> run?.putAside(waitKeyOf(intent))
            ACTION_ANSWER -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_ANSWER)
                if (!text.isNullOrBlank()) run?.answer(text.trim(), ::answerTooLate)
            }
            ACTION_CLOSE_KEPT_PAGE -> keeper.close(KeptPageClose.User)
            ACTION_APPROVE, ACTION_DECLINE -> {
                val key = if (intent.hasExtra(ConfirmationNotifier.EXTRA_REQUEST)) intent.getIntExtra(ConfirmationNotifier.EXTRA_REQUEST, 0) else null
                run?.confirm(if (intent.action == ACTION_APPROVE) ConfirmChoice.Once else ConfirmChoice.Deny, key)
            }
        }
        if (!taskState._busy.value && keeper.page.value == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun begin(newGoal: String, requestedConversation: String?,
        provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
        triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null, messageId: String? = null) {
        parallel = FeatureFlags.get(this).speedFlags().parallelTasks
        // 同じ会話内で実行中またはキューに入れられているタスクが新しいメッセージを受信すると、そのメッセージはその受信箱に渡されます。
        requestedConversation?.let(center::byConversation)?.let { existing ->
            if (provenance.origin == RunOrigin.Ui) {
                if (messageId != null) existing.steer(newGoal, messageId) else existing.steer(newGoal)
                keepForeground()
                return
            }
        }
        // が割り込んだとき、タスクはすでに完了していました。この会話の次の点として、サービスがまだ完了しているからといってタスクを破棄しないでください。
        if (messageId != null && requestedConversation != null) {
            deferMessage(requestedConversation, newGoal)
            keepForeground()
            return
        }
        if (triggerKey != null && center.runs().any { it.triggerWindowKey == triggerKey &&
                it.conditionCheck == (triggerActionGoal != null) }) {
            keepForeground()
            return
        }
        // 他のダイアログのタスクは、実行中または並行して閉じられたとき (または上限に達したとき) には失われません。レジストリはそのタスクをキューの最後尾に置き、タスクの終了時に解放します。
        // 会話ページは、サービスが無料になるのを待ってから引き渡します。ただし、検索が完了してから引き渡されるまでの間に、別の場所 (タイミング、コンピューター) で別の検索が開始される可能性があり、ここでもキューに入れられます。
        val store = ConversationStore.shared(this)
        val conversationId = requestedConversation ?: store.newConversation()
        // 定期的にコンピューターに渡されたタスクを失わないようにしてください。このダイアログにタスクがある場合、それらはダイアログの後ろでキューに入れられます。
        val queueHere = provenance.origin == RunOrigin.Trigger || provenance.origin == RunOrigin.Mcp
        when (val r = center.tryStart(conversationId, newGoal, queueInConversation = queueHere)) {
            StartResult.AlreadyRunningInConversation -> keepForeground()
            is StartResult.Queued -> {
                nameConversation(store, conversationId, newGoal, provenance)
                r.run.provenance = provenance
                r.run.triggerWindowKey = triggerKey
                r.run.triggerActionGoal = triggerActionGoal
                r.run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(r.run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey))
                }
                r.run.mcpExternalId = mcpExternalId
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // 最初に登録してから表示します: インターフェイスがステータスを受信すると、すでにタスク リストに含まれています (ダイアログ、オリジナルの単語)
                publishRuns()
                // は成功基準、実行番号をクリアし、キューを表示します。
                if (center.running().none { it.conversationId == conversationId }) r.run.show()
                r.run.queue(r.position)
                keepForeground()
            }
            is StartResult.Started -> {
                nameConversation(store, conversationId, newGoal, provenance)
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // は成功基準と実行番号をクリアし、ステータスを「準備中」に設定します。
                val run = r.run
                run.provenance = provenance
                run.triggerWindowKey = triggerKey
                run.triggerActionGoal = triggerActionGoal
                run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey))
                }
                run.mcpExternalId = mcpExternalId
                // 最初に登録してから表示します: インターフェイスがステータスを受信すると、すでにタスク リストに含まれています (ダイアログ、オリジナルの単語)
                publishRuns()
                run.show()
                val fg = foreground
                if (fg != null && fg !== run && fg in center.running()) keepForeground()
                else {
                    foreground = run
                    startInForeground(run, progressNotification(run, run.state.value))
                }
                launch(run, store)
            }
        }
    }

    /** 新しい会話でユーザー (またはコンピューター) によって渡された最初の文: バックエンドはモデルにタイトルを書き留めるように求めます (一度だけ、思いつかない場合は書き留めないでください)。 タイトル */
    private fun nameConversation(store: ConversationStore, id: String, first: String, provenance: RunProvenance) {
        if (provenance.origin != RunOrigin.Ui && provenance.origin != RunOrigin.Mcp) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val env = TaskEnvironment.of(this@TaskService)
                // もこの会話に属しており、会話内のタスクと同じ会話番号を使用します。
                val session = ModelSession.of(id)
                ConversationTitler.ensure(store, id, first) {
                    listOfNotNull(env.lightModelFactory(this@TaskService, session), env.modelFactory(this@TaskService, session))
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** すでにフォアグラウンドにある (または終了しようとしている) 場合に、再度フォアグラウンドに入ります。フォアグラウンドでのタスクの進行状況通知を使用します。 */
    private fun keepForeground() {
        val fg = foreground
        if (fg != null) startInForeground(fg, progressNotification(fg, fg.state.value))
        else startInForeground(null, preparingNotification())
    }

    private fun launch(run: TaskRun, store: ConversationStore) {
        run.job = scope.launch {
            center.markRunning(run)
            val result = try {
                run.execute(runCatching { store.turns(run.conversationId) }.getOrDefault(emptyList()).map(::withKeptState))
            } catch (e: CancellationException) {
                TaskState.Cancelled
            } catch (e: RemoteException) {
                run.shellDied = true
                TaskState.Failed(SHELL_DIED, FailKind.Environment)
            } catch (e: UnattendedLockException) {
                TaskState.Failed(e.message, FailKind.Environment)
            } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
                val reason = when (e.code) {
                    "app_blocked" -> tr(R.string.task_app_blocked, e.packageName ?: tr(R.string.task_app_this_app))
                    "app_access_unavailable" -> tr(R.string.task_app_access_unavailable)
                    "app_access_protected" -> tr(R.string.task_app_protected)
                    else -> tr(R.string.task_app_unverified)
                }
                TaskState.Failed(reason, FailKind.Environment)
            } catch (e: Exception) {
                TaskState.Failed(tr(R.string.task_error, e.message ?: e::class.simpleName), FailKind.Environment)
            }
            // シェル プロセスが失われ、仮想画面とその上のアプリも一緒に失われます。タスクを続行できません。
            val end = if (run.shellDied && result !is TaskState.Succeeded) TaskState.Failed(SHELL_DIED, FailKind.Environment) else result
            // 停止: インターフェースはその場で停止したことを示し、指示は後で追加されます (最初に「編成」を登録すると、インターフェースが最終ステータスを受け取るときにさらに単語が来ることがわかります)
            val draft = if (end == TaskState.Cancelled) run.draftText() else null
            val stopRunId = run.runId.value?.takeIf { end == TaskState.Cancelled && !run.conditionCheck && run.hasStopMaterial(draft) }
            stopRunId?.let { id -> taskState._summarizing.update { it + id } }
            run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, end, run.steers.value) }
            run.end(end)
            if (!run.conditionCheck) recordTurn(store, run.conversationId, run.goal, end, run.runId.value, run.sharedFiles.value, run.joinedSteers(), run.usedGroups, run.joinedInterrupted(), run.joinedFinished())
            stopRunId?.let { summarizeStop(run, it, draft, store) }
            if (run.conditionCheck) {
                val outcome = if (end is TaskState.Succeeded) ConditionOutcome.parse(end.answer)
                    else ConditionOutcome.Error((end as? TaskState.Failed)?.reason ?: tr(R.string.task_condition_interrupted))
                TriggerRuntime.checked(this@TaskService, run.triggerWindowKey.orEmpty(), run.triggerActionGoal.orEmpty(),
                    run.triggerThreadId.orEmpty(), outcome)
                if (outcome is ConditionOutcome.Error) {
                    val failure = TaskState.Failed(tr(R.string.task_check_unfinished, outcome.reason), FailKind.Environment)
                    recordTurn(store, run.conversationId, run.goal, failure, run.runId.value)
                    runCatching { notifyResult(run, failure, "condition:${run.key}") }
                        .onFailure { Log.w(TAG, "condition failure notification failed", it) }
                }
                center.markDelivered(run)
                onRunEnded(run, store)
                return@launch
            }
            val deliveryId = "result:${run.key}"
            center.markResult(run, describe(end).second, deliveryId, successful = end is TaskState.Succeeded)
            run.clearTakeoverStage()
            synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_ATTENTION, run)) }
            // は最初に結果通知を送信し、次にバックグラウンドで学習します (メモリの抽出、スキルの結果の記憶、製図スキル)。プロセスレベルのスコープを使用すると、サービスの終了も完了します
            var delivered = false
            val app = applicationContext
            runCatching {
                RunLearning.launchAfter(MemoryRuntime.of(this@TaskService).scope, {
                    delivered = notifyResult(run, end, deliveryId) == DeliveryState.Delivered
                    // スケジュールされたタスクの結果は、その場でエンディングとして記録されます（以前と同じ）。完了していない場合は、再度変更されます。サービスに依存せずに常に記録されます。
                    if (delivered) run.triggerWindowKey?.let { key ->
                        runCatching {
                            if (end is TaskState.Succeeded) TriggerRuntime.delivered(app, key)
                            else TriggerRuntime.actionFailed(app, key, describe(end).second)
                        }.onFailure { Log.w(TAG, "could not record trigger result", it) }
                    }
                }) {
                    // 引き渡し後: 回答はユーザーの要件に応じて決定されます (走行記録と今回の対話に記録されます)。完了した学習のみ
                    val judged = runCatching { run.settleOutcome(end, store) }.onFailure { Log.w(TAG, "outcome judging failed", it) }.getOrNull()
                    // 答えが完了していないか、一部しか完了していなかった場合は、あきらめたかのように失敗として記録されます。完了していない場合は引き渡されたものとしてカウントされます。
                    val notDone = judged?.outcome == RunOutcome.Partial || judged?.outcome == RunOutcome.NotPossible
                    if (delivered && notDone && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                        runCatching { TriggerRuntime.actionFailed(app, key, judged?.reason?.ifBlank { null } ?: describe(end).second) }
                            .onFailure { Log.w(TAG, "could not downgrade trigger result", it) }
                    }
                    if (run.provenance.origin == RunOrigin.Ui) runCatching { run.learnAfter(end, judged?.outcome, store) }
                        .onFailure { Log.w(TAG, "learning after run failed", it) }
                }
            }.onFailure { Log.w(TAG, "result notification failed", it) }
            if (delivered) center.markDelivered(run)
            onRunEnded(run, store)
        }
        // サービス終了や開始前の停止で引き継げなかった画面ロックを解放します。
        run.job?.invokeOnCompletion { run.wakeHandoff?.release(); run.wakeHandoff = null }
    }

    /**
     * 終了後に解説を書き、このラウンドの解答として記録します（エンディングはキャンセルされたままです）。プロセス レベルのスコープを使用してバックグラウンドで書き込みます。終了をブロックせずに次の処理を実行し、もう一度 [停止] をクリックすると、サービスの終了も完了します。
     * は、うまく書けた場合、または書けなかった場合、「整理」から削除されます。
     */
    private fun summarizeStop(run: TaskRun, runId: String, draft: String?, store: ConversationStore) {
        MemoryRuntime.of(this).scope.launch {
            try {
                val text = runCatching { run.stopSummary(draft) }.onFailure { Log.w(TAG, "stop summary failed", it) }.getOrNull()
                if (text != null) withContext(Dispatchers.IO) {
                    runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = text) } }
                        .onFailure { Log.w(TAG, "could not save stop summary", it) }
                }
            } finally {
                taskState._summarizing.update { it - runId }
            }
        }
    }

    /** タスクが完了したら: 終了したらフロントデスクを出てください。他にタスクがある場合は、フロントデスクに渡してキューにあるタスクを解放します。 */
    private fun onRunEnded(run: TaskRun, store: ConversationStore) {
        val wasShown = taskState.shownRun === run
        val started = center.onEnded(run)
        // 引継ぎ用ロックを次に開始する最初のタスクへ渡し、開始するタスクがなければ解放します。
        run.wakeHandoff?.let { lease ->
            run.wakeHandoff = null
            started.firstOrNull()?.let { it.wakeHandoff = lease } ?: lease.release()
        }
        publishRuns()
        if (wasShown) started.firstOrNull { it.conversationId == run.conversationId }?.show()
        // 他の対話タスクを待たずに、利用可能になったらすぐに対話を任せ始めます。
        val ready = center.takeReady()
        if (center.runs().isEmpty()) {
            if (ready.isNotEmpty()) {
                ready.forEach { (threadId, message) -> begin(message, threadId) }
                return
            }
            // にはまだバックグラウンドで実行されているページが 1 つあります。フォアグラウンド通知はそのページに置き換えられ、サービスは戻りません (プロセスはそれによってサポートされています)。オフの場合は、[onKeptPage] によって終了します。
            val kept = keeper.page.value
            if (kept != null) {
                synchronized(notifyLock) {
                    startForeground(ID_KEPT, keptNotification(kept))
                    getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run))
                }
                foreground = null
                taskState._busy.value = false
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            // 最初に番号を取得してから解放します。解放後に到着する新しいタスクは更新された番号をもたらしますが、ここでの stopSelf はそれを停止しません。
            val id = lastStartId
            taskState._busy.value = false
            stopSelf(id)
            return
        }
        // 最初に「準備中」に変更します。リリースされたタスクがたまたまフロント デスクに引き継がれる可能性があり、通知ではまだキュー中であるとは言えません。
        started.forEach { it.dequeue() }
        if (foreground === run) center.running().firstOrNull()?.let { next ->
            foreground = next
            startInForeground(next, progressNotification(next, next.state.value))
        }
        synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run)) }
        started.forEach {
            launch(it, store)
            refreshNotifications(it)
        }
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
        center.running().forEach(::refreshNotifications)
        ready.forEach { (threadId, message) -> begin(message, threadId) }
    }

    /** キュー内のタスクはキャンセルされました (チームから移動されました)。タスクは開始されず、キャンセルされたものとして直接記録されました。 */
    private fun cancelQueued(run: TaskRun) {
        run.handoffUnconsumed(cancelled = true)
        run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, TaskState.Cancelled, run.steers.value) }
        run.end(TaskState.Cancelled)
        recordTurn(ConversationStore.shared(this), run.conversationId, run.goal, TaskState.Cancelled, null)
        publishRuns()
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    }

    /**
     * 残りのページが変更されました。ページがまだ存在する場合は、その常駐通知を送信 (または変更) します。実行中のタスクがない場合は、フォアグラウンド通知になります。オフになっている場合は、通知を削除します。
     * 現時点でタスクがない場合は、フォアグラウンドを終了してサービスを停止します。
     */
    private fun onKeptPage(page: com.wangzan101.mika.cap.web.KeptPage?) {
        val nm = getSystemService(NotificationManager::class.java)
        synchronized(notifyLock) {
            val idle = center.runs().isEmpty()
            if (page != null) {
                if (idle && foreground == null) startForeground(ID_KEPT, keptNotification(page)) else nm.notify(ID_KEPT, keptNotification(page))
                return
            }
            nm.cancel(ID_KEPT)
            if (!idle || taskState._busy.value) return
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    /** バックグラウンドに残されたページの永続的な通知: サイト、自動シャットダウンまでの残り時間 (カウントダウン)、ボタン「Web ページを閉じる」および「会話を開く」。 */
    private fun keptNotification(page: com.wangzan101.mika.cap.web.KeptPage): Notification {
        val close = PendingIntent.getService(
            this, REQUEST_CLOSE_KEPT, Intent(this, TaskService::class.java).setAction(ACTION_CLOSE_KEPT_PAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.kept_notif_title))
            .setContentText(page.host)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 自動的にオフになるまでの時間がカウントダウンされます (通知テキストは期限切れになりません)。
            .setWhen(page.closesAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(openApp(page.conversationId))
            .addAction(Notification.Action.Builder(null, tr(R.string.kept_close), close).build())
            .addAction(Notification.Action.Builder(null, tr(R.string.kept_open_chat), openApp(page.conversationId)).build())
            .build()
    }

    /**
     * 上記のモデルの記事で、現時点で開いたままになっているページは、「キープ」とマークされていても存在しない場合（ミカが途中でやめた場合）、閉じたものとしてカウントされます。
     */
    private fun withKeptState(t: Turn): Turn =
        if (t.keptPage != null && t.keptPageClosed == null && keeper.page.value?.runId != t.runId) t.copy(keptPageClosed = KeptPageClose.AppExit.wire) else t

    /** タスクが完了すると 1 ラウンドが記録されます。後続のメッセージで参照できるよう、この情報を持参してください。ディスクへの書き込みに失敗しても、タスクの結果には影響しません。 */
    private fun recordTurn(store: ConversationStore, id: String, goal: String, end: TaskState, runId: String?, files: List<SharedFile> = emptyList(),
        steers: List<String> = emptyList(), groups: List<String> = emptyList(), interrupted: List<String> = emptyList(),
        finished: List<String> = emptyList()) {
        val turn = when (end) {
            // 検証されていないもの (チャットや回答に検証可能な値がない) は、検証済みのものとして成功として記録されます。検証されたが検証されなかったものは未検証として記録されます。
            is TaskState.Succeeded -> if (end.doubtful) Turn(goal, end.answer, "unverified", end.note, unconfirmed = end.unconfirmed) else Turn(goal, end.answer, "succeeded")
            is TaskState.Failed -> Turn(goal, end.reason, "failed")
            TaskState.Cancelled -> Turn(goal, Conversation.CANCELLED_OUTCOME, "cancelled")
            else -> return
        }.copy(runId = runId, steers = steers, groups = groups, interruptedReplies = interrupted, finishedReplies = finished)
            // このラウンドにはページが残っています。サイトを書き留めてください (理由とともにすでに閉鎖されています)。そのラウンドではダイアログ ページがそれに応じて表示されます。
            .let { t -> keeper.recordFor(runId)?.let { (host, closed) -> t.copy(keptPage = host, keptPageClosed = closed) } ?: t }
        runCatching { store.append(id, turn, files) }.onFailure { Log.w(TAG, "could not save conversation turn", it) }
    }

    /**
     * 現在のステータスに応じて通知を再描画します。複数のコルーチンが連続して呼び出され、ロック内の最新のステータスを取得します。
     * それ以外の場合、古いステータスを保持している当事者は、新しいステータスによって通知が削除された後、再度通知を送信する可能性があります。
     */
    internal fun refreshNotifications(run: TaskRun) {
        synchronized(notifyLock) {
            val shown = run.shownState() ?: return
            if (!shown.isActive() || center.isQueued(run)) return
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(id(ID_PROGRESS, run), progressNotification(run, shown))
            val attention = attentionNotification(run, shown)
            if (attention != null) nm.notify(id(ID_ATTENTION, run), attention) else nm.cancel(id(ID_ATTENTION, run))
        }
    }

    /** 返された短いセクションですが、ループはまだ終了していません。引き継ぎボタンは表示されなくなりました。 */
    private fun awaitingUser(run: TaskRun, state: TaskState) =
        state !is TaskState.WaitingTakeover || run.takeoverStage.value != Takeover.Stage.None

    /** ホーム画面では一度に 1 つのアプリのみを引き継ぎます。引き継ぎが他のタスクの後ろでキューに入れられている場合は、引き継ぎボタンを押さないでください。 */
    private fun takeoverFirst(run: TaskRun) = run.takeover?.let(takeoverQueue::isFirst) ?: true

    /** は、進行状況通知番号 [run] を使用してフロント デスクに入ります。タスク オブジェクトがない場合は、最初のスロットが使用されます。 */
    private fun startInForeground(run: TaskRun?, n: Notification) {
        val nid = run?.let { id(ID_PROGRESS, it) } ?: ID_PROGRESS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(nid, n)
        }
    }

    private fun progressNotification(run: TaskRun, state: TaskState): Notification {
        val (_, text) = describe(state) { ConfirmationNotifier.appLabel(this, it) }
        val warning = run.muteFailedFor?.let { "\n" + tr(R.string.task_may_make_sound) }.orEmpty() +
            (if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) "\n$TAKEOVER_QUEUED" else "")
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.task_notif_title, ScreenCopy.plain(run.goal)))
            .setContentText(text + warning)
            .setStyle(Notification.BigTextStyle().bigText(text + warning))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        val active = center.running().size
        if (active >= 2) b.setSubText(trPlural(R.plurals.task_active, active))
        when (state) {
            is TaskState.WaitingTakeover ->
                if (awaitingUser(run, state) && takeoverFirst(run)) takeoverActions(run).forEach(b::addAction)
                else b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
            else -> {
                if (state is TaskState.Held && state.reason.resumable) b.addAction(action(run, tr(R.string.chat_continue), ACTION_RESUME, 1))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
        }
        return b.build()
    }

    /** 忙しいのに目的のタスクがまだない場合（エンディングが中断された場合など）、すぐにフロントに入らなければなりません。 */
    private fun preparingNotification(): Notification = Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Mika")
        .setContentText(PREPARING)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun answerTooLate() = Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, getString(R.string.input_too_late, INPUT_TIMEOUT_MINUTES), Toast.LENGTH_LONG).show()
    }

    /**
     * 引き継ぎまたは応答を待機しているときに、別の目を引く通知が送信されます。それ以外のステータスはありません。
     * フローティング カプセルは、明るい画面に表示されているときもポップアップ表示されますが、静かに通知バーに入ります (カプセルがオレンジ色に変わりました。[alertQuietly] を参照)。画面がロックされているとき、カプセルが閉じているとき、または描画できないときにもポップアップが表示されます。
     */
    private fun attentionNotification(run: TaskRun, state: TaskState): Notification? {
        if (!awaitingUser(run, state)) return null
        val quiet = (application as com.wangzan101.mika.MikaApp).capsule.showing.value
        if (state is TaskState.WaitingConfirmation) {
            return ConfirmationNotifier.build(
                this, CHANNEL_ATTENTION, state, TaskService::class.java, ACTION_APPROVE, ACTION_DECLINE, openApp(),
                codeOffset = SLOT_STRIDE * run.slot, runExtra = EXTRA_RUN to run.key, quiet = quiet,
            )
        }
        if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) return null
        val (title, text) = when (state) {
            is TaskState.WaitingTakeover -> {
                val extra = when (run.takeover?.problem?.value) {
                    Takeover.Problem.MoveToMainFailed -> getString(R.string.takeover_move_to_main_failed)
                    Takeover.Problem.WebBusy -> getString(R.string.takeover_web_busy)
                    Takeover.Problem.MoveBackFailed -> getString(R.string.takeover_move_back_failed)
                    null -> if (run.takeoverStage.value == Takeover.Stage.HandedOver) getString(R.string.takeover_handed_over_hint) else null
                }
                val payment = StatusCopy.paymentLines(state.item, state.amount, state.address)
                val behind = run.takeover?.let(takeoverQueue::behind) ?: 0
                val waiting = if (behind > 0) trPlural(R.plurals.task_behind, behind) else null
                takeoverTitle(state.reason) to listOfNotNull(state.message, payment, extra, waiting).joinToString("\n")
            }
            is TaskState.WaitingInput -> getString(R.string.input_title) to
                "${state.prompt}\n${getString(R.string.no_answer_note, INPUT_TIMEOUT_MINUTES)}"
            else -> return null
        }
        val b = Notification.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true) // 同じ待ち時間中にボタンを変更すると、ボタンは鳴りません。待機中に削除すると、次回再度通知されます。
            .setContentIntent(openApp())
        if (quiet) b.alertQuietly()
        when (state) {
            is TaskState.WaitingTakeover -> takeoverActions(run).forEach(b::addAction)
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                // Android の通知には最大 3 つのボタンが表示されるため、「返信」+最初の 2 つのオプションになります。
                // すべてのオプションは返信ショートカット オプション (RemoteInput の選択肢) に入れられ、追加のオプションはそこで選択できます。
                state.options.orEmpty().take(2).forEachIndexed { i, o -> b.addAction(answerAction(run, o, i)) }
            }
            else -> Unit
        }
        return b.build()
    }

    private fun takeoverTitle(reason: String): String {
        val label = when (reason) {
            "captcha" -> R.string.takeover_reason_captcha
            "login" -> R.string.takeover_reason_login
            "access_blocked" -> R.string.takeover_reason_access_blocked
            "payment" -> R.string.takeover_reason_payment
            "decision" -> R.string.takeover_reason_decision
            else -> return getString(R.string.takeover_title)
        }
        return getString(R.string.takeover_title_reason, getString(label))
    }

    private fun takeoverActions(run: TaskRun): List<Notification.Action> {
        val cancel = action(run, getString(R.string.action_cancel_task), ACTION_CANCEL, 2)
        return if (run.takeoverStage.value == Takeover.Stage.HandedOver) listOf(
            takeoverAction(run, getString(R.string.action_hand_back), ACTION_HAND_BACK, 4),
            takeoverAction(run, getString(R.string.action_finished_myself), ACTION_FINISHED_MYSELF, 5),
            cancel,
        ) else listOf(
            takeoverAction(run, getString(R.string.action_take_over), ACTION_TAKE_OVER, 3),
            takeoverAction(run, getString(R.string.action_skip), ACTION_SKIP, 7),
            cancel,
        )
    }

    /** インターフェイスを表示しないアクティビティ経由: 通知バーは、アクティビティが通知から開始された場合にのみ閉じられます。 */
    private fun takeoverAction(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getActivity(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RUN, run.key)
                .apply { run.takeover?.let { putExtra(EXTRA_WAIT_KEY, it.waitKey) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun replyAction(run: TaskRun, options: List<String>?): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.reply_hint))
            .apply { if (!options.isNullOrEmpty()) setChoices(options.toTypedArray()) }
            .build()
        // 応答コンテンツはシステムによってインテントに入力される必要があり、変数 PendingIntent のみを使用できます (インテントは明示的です)。
        val pi = PendingIntent.getService(
            this, 6 + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(R.string.action_reply), pi).addRemoteInput(input).build()
    }

    private fun answerAction(run: TaskRun, option: String, index: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, 10 + index + SLOT_STRIDE * run.slot,
            Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, option).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, option, pi).build()
    }

    private suspend fun notifyResult(run: TaskRun, end: TaskState, deliveryId: String): DeliveryState {
        val (title, text) = describe(end)
        val peer = ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
        val delivered = outbox.deliver(localChannel, peer, Delivery.Result(deliveryId, run.conversationId, text, title = title))
        if (delivered.state != DeliveryState.Delivered) Log.w(TAG, "result delivery ${delivered.state}: $deliveryId")
        return delivered.state
    }

    private fun action(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(action).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun openApp(threadId: String? = null) = openApp(this, threadId)

    override fun onDestroy() {
        scope.cancel()
        // サービスがなくなった場合、プロセスを続行できません。残りのページを閉じて、会話に書き留めてください。
        keeper.close(KeptPageClose.AppExit)
        getSystemService(NotificationManager::class.java).cancel(ID_KEPT)
        if (center.runs().isNotEmpty()) {
            // 中断後のその後の処理 (タイミング ウィンドウ、補足配信、通知、ダイアログへの書き込み) に対処します。プロセスはまだ存在しており、次の開始を待ちません。アプリレベルのスコープでは、サービスがなくなってもサービスは終了します
            center.store.interruptUnfinished()
            val app = application as com.wangzan101.mika.MikaApp
            center.runs().forEach { r -> r.mcpExternalId?.let { app.remoteTasks.ended(it, TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted), r.steers.value) } }
            (application as com.wangzan101.mika.MikaApp).runRecovery.settleInterrupted()
            taskState._state.value = TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted)
            taskState._runs.value = emptyList()
            taskState._activeConversations.value = emptyList()
            taskState.shownRun = null
        }
        taskState._busy.value = false // エンディングをキャンセルすると、常に新しいタスクをブロックできなくなります。
        super.onDestroy()
    }

    companion object {
        /** 通知チャネルを作成します。繰り返し作成しても副作用はありません。アプリ起動時のリカバリも利用するため、サービスインスタンスに依存しません。 */
        internal fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val names = mapOf(CHANNEL_PROGRESS to tr(R.string.channel_progress), CHANNEL_RESULT to tr(R.string.channel_result), CHANNEL_ATTENTION to tr(R.string.channel_attention))
            // 古い結果チャンネルは低いレベルで構築されており、アプリを高いレベルに調整することはできません。設定に別の項目が追加されないように、ID を新しい ID に変更し、古い ID を削除してください。
            nm.deleteNotificationChannel(OLD_CHANNEL_RESULT)
            CHANNEL_IMPORTANCE.forEach { (id, importance) -> nm.createNotificationChannel(NotificationChannel(id, names.getValue(id), importance)) }
        }

        internal fun openApp(context: Context, threadId: String? = null): PendingIntent = PendingIntent.getActivity(
            context, threadId?.hashCode() ?: 0, openAppIntent(context, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** はミカを開きます。 [threadId] を与えると、そのダイアログに直接移動します。通知と一時停止カプセルは共有されます。 */
        internal fun openAppIntent(context: Context, threadId: String? = null): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (threadId != null) putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId) }

        /** 並列（周期的に使用）される結果通知の次の番号。 */
        internal fun nextResultId() = ID_RESULT_FIRST + resultCount.getAndIncrement() % RESULT_IDS

        /** の結果とプロンプトは通知として送信されます (サービス内で共有され、アプリの起動時に回復するため)。 [resultId]は結果通知の番号です。 */
        internal fun notifyDelivery(context: Context, delivery: Delivery, resultId: Int): SendOutcome {
            val nm = context.getSystemService(NotificationManager::class.java)
            when (delivery) {
                is Delivery.Result -> nm.notify(resultId, Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(delivery.title)
                    .setContentText(delivery.text)
                    .setStyle(Notification.BigTextStyle().bigText(delivery.text))
                    .setContentIntent(openApp(context, delivery.threadId))
                    .setAutoCancel(true)
                    .build())
                is Delivery.Notice -> nm.notify(nextResultId(), Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("Mika")
                    .setContentText(delivery.text).setContentIntent(openApp(context, delivery.threadId)).setAutoCancel(true).build())
                is Delivery.Ask, is Delivery.Progress -> {}
            }
            return SendOutcome.Delivered
        }

        /** 質問と回答を待つ時間制限: AgentLoop に渡され、通知とカードに書かれた分数もここから取得されます。 */
        const val INPUT_TIMEOUT_MS = AgentLoop.DEFAULT_INPUT_TIMEOUT_MS
        const val INPUT_TIMEOUT_MINUTES = INPUT_TIMEOUT_MS / 60_000
        const val ACTION_START = "com.wangzan101.mika.task.START"
        const val ACTION_START_TRIGGER = "com.wangzan101.mika.task.START_TRIGGER"
        const val ACTION_START_MCP = "com.wangzan101.mika.task.START_MCP"
        const val ACTION_CANCEL_MCP = "com.wangzan101.mika.task.CANCEL_MCP"
        const val ACTION_CANCEL = "com.wangzan101.mika.task.CANCEL"
        const val ACTION_RESUME = "com.wangzan101.mika.task.RESUME"
        const val ACTION_TAKE_OVER = "com.wangzan101.mika.task.TAKE_OVER"
        const val ACTION_HAND_BACK = "com.wangzan101.mika.task.HAND_BACK"
        const val ACTION_FINISHED_MYSELF = "com.wangzan101.mika.task.FINISHED_MYSELF"
        const val ACTION_SKIP = "com.wangzan101.mika.task.SKIP"
        /** Web ページ引き継ぎインターフェイスでシステムのリターン キーを押しました。そのままにして、引き継ぎを待機するために戻ります。 */
        const val ACTION_PUT_ASIDE = "com.wangzan101.mika.task.PUT_ASIDE"
        /** は、通知ボタンの「待機回数」を引き継ぎます。[Takeover.waitKey] を参照してください。 */
        const val EXTRA_WAIT_KEY = "com.wangzan101.mika.task.WAIT_KEY"
        const val ACTION_ANSWER = "com.wangzan101.mika.task.ANSWER"
        const val ACTION_APPROVE = "com.wangzan101.mika.task.APPROVE"
        /** タスクが完了した後、バックグラウンドに残るページ (通知上のボタン) を閉じます。 */
        const val ACTION_CLOSE_KEPT_PAGE = "com.wangzan101.mika.task.CLOSE_KEPT_PAGE"
        const val ACTION_DECLINE = "com.wangzan101.mika.task.DECLINE"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val EXTRA_TRIGGER_KEY = "trigger_key"
        private const val EXTRA_TRIGGER_ACTION = "trigger_action"
        private const val EXTRA_TRIGGER_THREAD = "trigger_thread"
        private const val EXTRA_MCP_ID = "mcp_id"
        private const val EXTRA_ATTENDANCE = "attendance"
        private const val EXTRA_ANSWER = "answer"
        /** 通知ボタンが属するタスクの番号 ([TaskRun.key])。 */
        const val EXTRA_RUN = "com.wangzan101.mika.task.RUN"
        /** ユーザーが回答を確認してから [同意しない] を押すまでに、最大でこれほど長い時間がかかることがあります (電話のそばにいないときにタスクが常にハングするとは限らず、自動的に同意されることもありません)。 */
        const val CONFIRM_TIMEOUT_MS = 15 * 60_000L
        private const val KEY_REPLY = "reply"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_PROGRESS = "task_progress"
        const val CHANNEL_RESULT = "task_result_v2"
        internal const val OLD_CHANNEL_RESULT = "task_result"
        /** 確立されたときの各通知チャネルのレベル。 「あなたが必要です」（引き継ぎ、質問、確認）と結果がポップアップ表示され、進行状況は静かに保たれます。ユーザーが後で値を下げた場合、アプリは値を元に戻すことはできません。 */
        internal val CHANNEL_IMPORTANCE = mapOf(
            CHANNEL_PROGRESS to NotificationManager.IMPORTANCE_LOW,
            CHANNEL_RESULT to NotificationManager.IMPORTANCE_HIGH,
            CHANNEL_ATTENTION to NotificationManager.IMPORTANCE_HIGH,
        )
        private const val ID_PROGRESS = 1
        internal const val ID_RESULT = 2
        private const val ID_ATTENTION = 3
        /** バックグラウンドに残されたページの常駐通知 (スロットに関係なく、グローバルにページは 1 つだけあります)。 */
        private const val ID_KEPT = 4
        private const val REQUEST_CLOSE_KEPT = 99
        /** n 番目のスロットのタスク通知番号と要求コードを n × it だけ増加します。最初のスロットは、タスクが 1 つだけ実行される場合と同じです。 */
        private const val SLOT_STRIDE = 100
        /** 並列化中の結果通知の番号セグメントがスロット番号の範囲外です。循環的に使用すると、この数まで同時に残すことができます。 */
        private const val ID_RESULT_FIRST = 10_000
        private const val RESULT_IDS = 1_000
        private val resultCount = AtomicInteger()
        private val TAKEOVER_QUEUED: String get() = tr(R.string.task_takeover_queued)
        private const val TAG = "MikaTask"
        val TOO_OLD: String get() = tr(R.string.task_too_old)
        internal val PREPARING: String get() = tr(R.string.runtime_preparing)
        val SHELL_DIED: String get() = tr(R.string.task_shell_died)

        /** サイクル状態のうち、パブリッシュによって書き出すことができるものはどれですか。 */
        internal fun loopMayPublish(state: TaskState) = state.isActive()

        /** はタスクを開始します。他のダイアログ タスクは実行中はその後ろのキューに入れられますが、同時に実行することはできません。新しい会話を開くには、[conversationId] は空です (番号については、[conversationId] を参照してください)。 */
        /** [messageId] これが進行中のタスクの場合、インターフェイスはこの文の指示に従います。 */
        fun start(context: Context, goal: String, conversationId: String? = null, messageId: String? = null) {
            context.startForegroundService(
                Intent(context, TaskService::class.java).setAction(ACTION_START).putExtra(EXTRA_GOAL, goal)
                    .apply { if (conversationId != null) putExtra(EXTRA_CONVERSATION, conversationId) }
                    .apply { if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId) },
            )
        }

        fun startTrigger(context: Context, goal: String, threadId: String, windowKey: String, conditionGoal: String?) {
            val checking = conditionGoal != null
            val runGoal = if (checking) "$conditionGoal\nRead-only check. At the end, output only JSON: {\"met\":true or false,\"evidence\":\"short supporting evidence\"}. If you cannot check, report failure; do not guess." else goal
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_TRIGGER)
                .putExtra(EXTRA_GOAL, runGoal).putExtra(EXTRA_CONVERSATION, threadId)
                .putExtra(EXTRA_TRIGGER_KEY, windowKey).putExtra(EXTRA_TRIGGER_THREAD, threadId)
                .apply { if (checking) putExtra(EXTRA_TRIGGER_ACTION, goal) })
        }

        /** コンピュータから渡されたタスク ([RemoteTaskHub]): ダイアログ [conversation] に記録され、ソースは MCP として記録されます。 */
        fun startMcp(context: Context, goal: String, conversation: String, externalId: String, attendance: Attendance) {
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_MCP)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, conversation)
                .putExtra(EXTRA_MCP_ID, externalId).putExtra(EXTRA_ATTENDANCE, attendance.name))
        }

        fun cancelMcp(context: Context, externalId: String) {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            if (state.runs.value.none { it.mcpExternalId == externalId }) return
            context.startService(Intent(context, TaskService::class.java).setAction(ACTION_CANCEL_MCP)
                .putExtra(EXTRA_MCP_ID, externalId))
        }

        /** [runKey] [send] を参照。アプリケーションのボタンには画面上の会話のタスク番号が表示され、他のタスクには影響しません。 */
        fun cancel(context: Context, runKey: String? = null) = send(context, ACTION_CANCEL, runKey = runKey)

        /** ユーザーが「続行」をクリックすると、削除されたアプリがミカの画面に戻ります。 */
        fun resume(context: Context, runKey: String? = null) = send(context, ACTION_RESUME, runKey = runKey)

        /** 「私が処理します」: アプリをホーム画面に移動し、サウンドを復元します。 */
        fun takeOver(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_TAKE_OVER, waitKey, runKey)

        /** 「ハンドド」: 仮想画面に戻り、再び音声をミュートします。エージェントは続行する前に画面を読み取って確認します。 */
        fun handBack(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_HAND_BACK, waitKey, runKey)

        /** 「自分で終了しました」: 仮想画面に戻り、エージェントは結果を確認するだけで終了します。 */
        fun finishedMyself(context: Context, waitKey: Int? = null, runKey: String? = null) =
            send(context, ACTION_FINISHED_MYSELF, waitKey, runKey)

        /** "スキップ": このステップは処理されません。エージェントは「返品が発生しませんでした」を受け取った後、方法を変更するか、真実を報告します。 */
        fun skip(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_SKIP, waitKey, runKey)

        fun putAside(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_PUT_ASIDE, waitKey, runKey)

        /** はエージェントの質問 (ask_user) に答えます。 */
        fun answer(context: Context, text: String, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, text)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** は、[key] ([ConfirmationNotifier.requestKey]) 番号の宣言に同意します。別の宣言に変更されている場合はカウントされません。 */
        fun approve(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_APPROVE, key, runKey)

        /** は同意しません。エージェントは今後この操作を試行せず、現状を報告した後に終了します。 */
        fun decline(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_DECLINE, key, runKey)

        private fun confirm(context: Context, action: String, key: Int, runKey: String?) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).putExtra(ConfirmationNotifier.EXTRA_REQUEST, key)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** 現時点でのタスクのステータス。タスクが使用できなくなった場合、タスクはアイドル状態になります (アクションは無効です)。 [runKey] が空の場合、表示されているタスクです。 */
        private fun stateFor(context: Context, runKey: String?): TaskState {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            return if (runKey == null) state.state.value else state.runs.value.firstOrNull { it.key == runKey }?.state?.value ?: TaskState.Idle
        }

        /** [runKey] が空の場合は、表示されているタスクに作用します。それ以外の場合は、そのタスクにのみ作用します。 */
        private fun send(context: Context, action: String, waitKey: Int? = null, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).apply {
                    if (waitKey != null) putExtra(EXTRA_WAIT_KEY, waitKey)
                    if (runKey != null) putExtra(EXTRA_RUN, runKey)
                },
            )
        }

        internal fun TaskState.isActive() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        /** 通知のタイトルとテキスト。 [appLabel] パッケージ名をアプリ名に置き換えます。 */
        internal fun describe(state: TaskState, appLabel: (String) -> String = { it }): Pair<String, String> = when (state) {
            TaskState.Idle -> "Mika" to ""
            is TaskState.Running -> tr(R.string.task_running) to (state.progress?.let(StatusCopy::progress) ?: when {
                state.step == 0 -> state.note
                else -> tr(R.string.task_step_note, state.step, state.note).trimEnd(':', ' ')
            })
            is TaskState.Held -> when {
                state.reason == TaskGate.LOCKED -> tr(R.string.task_wait_unlock) to tr(R.string.task_wait_unlock_body)
                state.reason.queued -> tr(R.string.chat_queued) to state.reason.why
                state.reason.resumable -> tr(R.string.chat_paused) to state.reason.why
                else -> tr(R.string.chat_waiting_for_you) to state.reason.why
            }
            is TaskState.WaitingTakeover -> tr(R.string.takeover_title) to state.message
            is TaskState.WaitingInput -> tr(R.string.input_title) to state.prompt
            is TaskState.WaitingConfirmation -> ConfirmationCopy.title(state.request) to ConfirmationCopy.body(state.request, appLabel(state.request.app))
            is TaskState.Succeeded ->
                if (!state.doubtful) tr(R.string.task_done) to state.answer
                else tr(R.string.chat_unverified) to (state.note?.let { tr(R.string.task_reason, it) + "\n" }.orEmpty() + state.answer)
            is TaskState.Failed -> tr(R.string.task_not_done) to state.reason
            TaskState.Cancelled -> tr(R.string.task_stopped) to tr(R.string.task_stopped_body)
        }
    }
}
