package com.wangzan101.mika

import com.wangzan101.mika.record.DetailedTraces
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResourceTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ChatCopy
import android.app.Application
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import com.wangzan101.mika.chat.AndroidBranchWorkspace
import com.wangzan101.mika.chat.ConversationDeleter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.ui.AttachFiles
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.web.WebFiles
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.SkillsSync
import com.wangzan101.mika.capsule.AppForeground
import com.wangzan101.mika.capsule.CapsuleController
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.memory.AppLearningActions
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.ApprovalControl
import com.wangzan101.mika.settings.ApprovalStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.usage.UsageLedger
import com.wangzan101.mika.task.FileOutboxStore
import com.wangzan101.mika.task.FileRunStateStore
import com.wangzan101.mika.task.LocalChannel
import com.wangzan101.mika.task.RunRecovery
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.task.TriggerOutcomes
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import com.wangzan101.mika.cap.web.WebOwner
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.sandbox.SandboxOwner
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.ResourceScheduler
import com.wangzan101.mika.task.RemoteTaskHub
import com.wangzan101.mika.task.FileChildRunStore
import com.wangzan101.mika.task.TriggerRuntime

/**
 * アプリ コンテナー: プロセスで共有されるすべてのサービスはここで作成され、それぞれのオーナー インターフェイスを通じて Android ポータル (インターフェイス、サービス、ブロードキャスト、プロバイダー) に渡されます。
 * コードの残りの部分は、構築パラメータを通じて取得されます。ヘルパー プロセス (HelperMain、ShellService) にはこのオブジェクトがなく、ここではサービスを使用しません。
 */
class MikaApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 電話対応サービス: 内蔵アシスタント、共有シェル接続、ペアリング、「設定に移動」プロンプト。 */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** Web ページ タグの DevTools セッション入口: プロセス全体の 1 つのコピー。接続できない場合は、内蔵アシスタントを使用して接続できます。 */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** 各タスクのタグプールが共有するタグの総数の上限。 */
    val resources = ResourceScheduler()
    val detailedTraces by lazy { DetailedTraces(this) }

    val remoteTasks by lazy { RemoteTaskHub(this) }

    /** 本人が許可したメイン画面の起動・ロック解除。PC接続と定時タスクで同じ処理を共有する。 */
    val deviceAccess by lazy { com.wangzan101.mika.device.AndroidDeviceAccess(this) { phone.hub.shell.value } }

    /**
     * コンピューター接続 (MCP): スイッチ、アクセス トークン (サービス プロバイダーのキーと同様に暗号化および保存)、モニタリング。 LAN タイプはデフォルトではオフになっています。
     * USB は、今度はシャットダウンされるまで、adb 経由でコンピューターによって開かれます (ブート番号は、シェルで読み取られた `settings get global boot_count` と同じです)。
     */
    val mcp by lazy {
        val settingsDir = java.io.File(filesDir, "settings")
        com.wangzan101.mika.mcp.McpRuntime(this,
            com.wangzan101.mika.settings.McpSettings(settingsDir, com.wangzan101.mika.settings.KeystoreKeyCipher("mika-mcp-token")),
            com.wangzan101.mika.settings.McpUsbSettings(settingsDir,
                bootId = { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.BOOT_COUNT) ?: "null" },
                infoFile = { getExternalFilesDir(null)?.let { java.io.File(it, com.wangzan101.mika.core.mcp.McpUsb.INFO_FILE) } }),
            remoteTasks, deviceAccess,
            com.wangzan101.mika.settings.RelaySettings(settingsDir, com.wangzan101.mika.settings.KeystoreKeyCipher("mika-remote-relay")))
    }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }

    /** 外部 MCP サーバー設定 (「コンピューターに接続」の受信設定とは別)。 */
    val mcpServers by lazy {
        com.wangzan101.mika.settings.McpServerStore(
            java.io.File(filesDir, "settings"),
            com.wangzan101.mika.settings.KeystoreKeyCipher("mika-mcp-servers"),
            stdio = com.wangzan101.mika.core.cap.mcp.McpStdioLauncher { workspace, argv ->
                val process = AndroidSandbox(this, SandboxState.get(this)).openProcess(workspace, argv, null)
                object : com.wangzan101.mika.core.cap.mcp.McpStdioProcess {
                    override val stdin get() = process.stdin
                    override val stdout get() = process.stdout
                    override val stderr get() = process.stderr
                    override fun close() = process.close()
                }
            },
        )
    }
    override val tabBudget get() = resources.tabBudget

    /** バックグラウンドに残り、タスクの完了後も実行を続けるページ。閉じた場合は、ソースダイアログラウンドに理由を記録します (ダイアログページの下に「バックグラウンド Web ページが閉じられました」と表示されます)。 */
    override val pageKeeper: com.wangzan101.mika.cap.web.PageKeeper by lazy {
        com.wangzan101.mika.cap.web.PageKeeper(this, tabBudget).also { k ->
            k.onClosed = { p, reason ->
                p.runId?.let { runId ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { conversations.updateTurn(p.conversationId, runId) { it.copy(keptPageClosed = reason.wire) } }
                            .onFailure { android.util.Log.w("MikaApp", "could not note the kept page closing", it) }
                    }
                }
            }
        }
    }

    /** 記憶、個人スキル、スイッチ、結果カードのヒント。 */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** ネイティブ トークン使用量台帳: モデル設定を通じて作成された各モデルのすべての呼び出しがここに記録されます。 */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** モデル構成とキー;構築されたすべてのモデルは測定され、[usage] として記録されます。 */
    override val providers: ProviderStore by lazy { ProviderStore.create(this, usage, "Mika/${BuildConfig.VERSION_NAME}") }

    /** Speech-to-text の選択: プロセス全体のコピーが 1 つあり、設定ページを変更した直後に入力ボックスが表示されます (2 つのインスタンスは互いの設定を確認できません)。 */
    val speech: com.wangzan101.mika.voice.SpeechSettings by lazy { com.wangzan101.mika.voice.SpeechSettings.get(this) }

    /** 音声入力入口、入力ボックス、フローティングカプセルは共用です。 */
    val voice: com.wangzan101.mika.voice.VoiceInput by lazy { com.wangzan101.mika.voice.VoiceInput(this, speech, providers) }

    /** ChatGPT を使用してログインします。アプリのスコープで実行し、この期間中にプロセスをサポートするフォアグラウンド サービスを開きます ([com.wangzan101.mika.settings.ChatGptSignIns] を参照)。 */
    val chatGptSignIn by lazy {
        com.wangzan101.mika.settings.ChatGptSignIns(scope,
            keepAlive = { com.wangzan101.mika.settings.ChatGptSignInService.start(this) },
            log = { msg, e -> Log.w("MikaChatGpt", msg, e) })
    }

    /** 体験ルート。 */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** 異常終了後に残ったシステム変更のリカバリ。実行中のタスクはここに登録されます。 */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** 実行元帳と配送元帳: プロセス全体に 1 つのコピー。タスク サービスと起動時のリカバリによって共有されます (同じファイルに 2 つのインスタンスを持つことはできません)。 */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** 未完了の操作の余波 (標準の中断、補足配信、通知): プロセス内で 1 回だけ実行され、アプリの起動時とタスク サービスの開始時に呼び出されます。 */
    val runRecovery: RunRecovery by lazy {
        val app = this
        RunRecovery(runStore, outbox, LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId())
        }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope)
    }

    /** サイト登録フォーム (ログイン Web サイトなど): 設定ページとタグプールは共有されます。 */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** `files/sandbox/` のツリー ロック: インストール、リセット、コマンド、ダウンロード、添付ファイルはすべてその権限に書き込まれています。 */
    override val sandboxTree = SandboxTree()

    /** タスクは、アプリからモデルソース、評価フック、画面スイッチを取得します。 */
    override val tasks = TaskEnvironment()

    /** サンドボックスのステータス: インストール サービスが更新され、設定ページはエージェントに表示されるものと同じです。 */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** 会話レコード: プロセス全体のコピー 1 つ、タスク サービスの共有ボタン、チャット インターフェイス、および結果カード。 */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /** 会話を削除します (サイドバーを長押し): タスク、スケジュールされたタスク、ワークスペースも一緒に削除します。 */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            forgetRemoteTasks = { id ->
                if (com.wangzan101.mika.core.remote.RemoteTaskService.isConversation(id)) remoteTasks.forget(id)
            },
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            removeSchedule = { TriggerRuntime.remove(app, it) },
            deleteWorkspace = { id ->
                // ワークスペースには添付ファイル、ユーザーから送信された画面、タスクによって書き込まれたファイルがあります。サンドボックス内のコマンドにはここからアクセスでき、コマンド、インポート、リセットとは相互に排他的です。
                SandboxDirs(app).deleteWorkspace(id)
            },
        )
    }

    /** ユーザーによって送信された画面 (それぞれの会話とともにワークスペースに保存されます)。 */
    val userScreens: com.wangzan101.mika.chat.UserScreenFiles by lazy { com.wangzan101.mika.chat.UserScreenFiles(this) }

    /** グローバル クエリ/自律: プロセス全体、設定ページ、および各タスクに対して 1 つのコピーが共有されます。 */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    val appAccess: com.wangzan101.mika.settings.AppAccessStore by lazy {
        com.wangzan101.mika.settings.AppAccessStore(java.io.File(filesDir, "app-access.json"))
    }

    /** グローバルな調査/自律性と各タスクの独自の分担。開発者向けオプションの「自律モード」を変更できます。 */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** 各実行のキューと表示ステータスはアプリ プロセスに従い、サービスが再構築されるときも同じコピーが引き続き使用されます。 */
    val taskState = TaskCenterState()

    /** ミカ自身のインターフェイスは現在非表示です。 */
    val foreground = AppForeground()

    /** アップデートの確認: 見つかった新しいバージョンはプロセスに従っており、サイドバー、設定ホームページ、およびバージョン情報ページの赤い点は同じです。 */
    val updates: com.wangzan101.mika.about.UpdateChecker by lazy {
        com.wangzan101.mika.about.UpdateChecker(java.io.File(filesDir, "update-v1.json"), BuildConfig.VERSION_NAME,
            deviceAbis = android.os.Build.SUPPORTED_ABIS.toList())
    }

    /** 他のアプリの上に浮かぶ浮遊カプセル。 */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    val paymentPause by lazy {
        com.wangzan101.mika.core.shell.PaymentPauseController(
            phone.recoverySettings,
            shouldRestoreAccessibility = {
                phone.recovery.accessibility.value.enabled ||
                    phone.recoverySettings.consent.value.wants(com.wangzan101.mika.core.shell.RecoveryCapability.Accessibility)
            },
            disableAccessibility = { phone.recovery.setAccessibilityForPayment(false) },
            enableAccessibility = { phone.recovery.setAccessibilityForPayment(true) },
            resumeAllowed = { !com.wangzan101.mika.quit.MikaQuit.blocksWork(this) },
        )
    }
    val paymentNotifications by lazy { com.wangzan101.mika.payment.PaymentPauseNotifications(this) }

    /** ダイアログ ページのステータスはプロセスに従います。インターフェイスが閉じられてから開かれた後も、実行中のタスクのステップはそのまま残ります。 */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun withdrawSteer(conversationId: String, messageId: String) = taskState.run(conversationId)?.withdrawSteer(messageId) ?: false
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // は、押された瞬間にダイアログタスクの番号に変更されます。後でダイアログが切り替わったり、タスクが変更された場合、アクションは他のタスクには影響しません。
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
                override fun unmuteIn(conversationId: String) { taskState.run(conversationId)?.unmute() }
            },
            conversations,
            taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // アプリが仮想画面上で開かれている、または内蔵ブラウザが使用されている: ピクチャーインピクチャーでは表示する画像のみが含まれます (空白の画面のみのものはカウントされません)
            screenOpen = tasks.previewOpen
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentAppShown.value || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            // は会話に応じて独自のタスクを実行し、外部タスク (並行して表示される別のタスク) を提供する必要はありません。
            liveRunId = { taskState.run(it)?.runId?.value },
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // 添付ファイルは会話のワークスペースに入ります。実行環境は必要ありません。デバイスがサポートしている場合は、添付ボタンが表示されます。
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(id), dirs.gate, dirs.tree, sandbox::freeBytes) { sandbox.workspaceBytes(id) }
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = conversationDeleter::delete,
            keptPage = pageKeeper.page,
            closeKept = { pageKeeper.close(com.wangzan101.mika.core.cap.web.KeptPageClose.User) },
            branchWorkspace = AndroidBranchWorkspace(app),
            // タイトルの後に現在のインターフェース言語の「ブランチ」接尾辞を追加します。ソースのタイトルが長すぎる場合は、まずタイトルを短くし、サフィックスを残します。
            branchTitle = { base -> ChatCopy.branchTitle(base, tr(R.string.chat_branch_title_suffix)) },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyDefaultLocale(this)
    }

    private var runtimeStarted = false
    private val restoreWishSync by lazy {
        com.wangzan101.mika.cap.phone.setup.RestoreWishSync(
            phone.hub, FeatureFlags.get(this).capsuleModeChanges(), taskState.busy,
            phone.recoverySettings.consent, phone.recovery.accessibility,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // インターフェース以外のテキスト（通知、サービス、フローティングカプセル）はアプリ言語に従って取得されます。
        AppLanguage.initializeDefault(this)
        AppLanguage.applyDefaultLocale(this)
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        registerActivityLifecycleCallbacks(foreground)
        startRuntime()
    }

    internal fun startRuntime() {
        if (runtimeStarted || com.wangzan101.mika.quit.MikaQuit.requested(this)) return
        runtimeStarted = true
        // 設定 › ネットワーク プロキシ: 最初のリクエストからミカ自身の接続が渡されます。 SOCKS プロキシのアカウント パスワードは、プロセス全体の認証者によって与えられます。
        com.wangzan101.mika.settings.NetProxyStore.get(this)
        com.wangzan101.mika.core.net.NetProxy.shared.installSocksAuth()
        // 内蔵ブラウザ: プロキシを設定した後にのみ WebView を設定します (読み取り設定は復号化してバックグラウンドに置く必要があります。プロキシを設定しないと、このために WebView は読み込まれません)。
        scope.launch(Dispatchers.IO) { runCatching { com.wangzan101.mika.cap.web.WebProxy.apply(this@MikaApp) } }
        capsule.start()
        // は組み込みアシスタントの実際の実装であり、アシスタントの監視を開始します (Mika プロセスの開始後、まだ生きているアシスタントが自らバインダーを引き渡します)
        phone.shellManager.start()
        // は、接続が利用可能になるとすぐにデフォルトで有効になるいくつかの機能を追加します。
        phone.recovery.start()
        // 内蔵アシスタントに指示: 殺された後にミカを引き上げるかどうか (これはカプセルが常に表示されている場合、またはタスクが実行されている場合にのみ必要です)
        restoreWishSync.start()
        paymentNotifications.start()
        // 前回の異常終了（アプリがミュート）時に残された変更がここに復元されます。
        scope.launch { taskRecovery.recover() }
        // 最後のプロセスが強制終了されたときに操作は終了しませんでした。現在中断としてマークして通知し、タスクが処理されるのを待ちます。
        runRecovery.recover().let { if (it.isNotEmpty()) Log.w("MikaTask", "${it.size} runs interrupted by process restart") }
        // まずデバイスのタイム ゾーンを確認します。覚えていない場合は、現在のタイム ゾーンを書き留めます (アップグレード ユーザーにはベースラインもあります)。書き留めたものと異なる場合は、ローカル タイム プランを再計算します (タイム ゾーンがオフになっているときに変更された場合は、それも補います)。
        scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.syncTimeZone(this@MikaApp); TriggerRuntime.rearm(this@MikaApp) } }
        // は、コンピューターに対して「USB 経由で接続」スクリプトを実行します。ちなみに、前回の起動時に残ったUSB接続情報はクリアされます。
        scope.launch(Dispatchers.IO) { runCatching { mcp.writeUsbScript() }.onFailure { Log.w("MikaMcp", "cannot write USB script", it) } }
        // 最後のエクスポートの途中で強制終了されて残った空のシェル エントリ
        scope.launch { runCatching { ShareExporter(this@MikaApp).cleanPending() } }
        // 最後のプロセスによって残された Web ページのコピーをアップロードします
        scope.launch { runCatching { WebFiles.clearStale(this@MikaApp, SandboxDirs(this@MikaApp).gate) } }
        // アップデートがインストールされると、インストール パッケージが [バージョン情報] ページで利用可能になります。
        scope.launch(Dispatchers.IO) { runCatching { com.wangzan101.mika.about.ApkUpdater(this@MikaApp).dropInstalled() } }
        // オペレーティング環境の準備ができていません (最初の起動、Mika アップグレード後)。バックグラウンドで準備されており、インターネットに接続されておらず、ユーザーには表示されません。
        RuntimePreparer.ensure(this)
        // サンドボックスの準備ができたら (起動時に準備ができているか、インストールしたばかりの場合)、スキル ディレクトリを一度確認してください。アプリの更新によってもたらされたスクリプトはすぐに使用できます。
        scope.launch {
            SandboxState.get(this@MikaApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@MikaApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@MikaApp)) { state.status.value is SandboxStatus.Ready } }
        }
    }

    internal suspend fun stopRuntimeForQuit() {
        scope.cancel()
        capsule.stopNow()
        phone.shellManager.stop()
        phone.recovery.stop()
        paymentNotifications.stop()
        restoreWishSync.stop()
    }
}
