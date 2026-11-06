package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.AppLanguage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.memory.MemoryRuntime
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor

class MainActivity : ComponentActivity() {
    private lateinit var monitor: SystemStatusMonitor
    private var requestedConversation by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // ユーザーが「次回からは尋ねない」を選択すると、システムはウィンドウをポップアップ表示せず、設定でのみ開くことができます。
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        monitor.refresh()
    }

    // Android 12 以前の場合、アプリで選択した言語が適用されます。 13 以降、システムは単独で適用できます。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        monitor = SystemStatusMonitor(this, PhoneServices.of(this).shellControl)
        val store = ProviderStore.get(this)
        setContent {
            // の外観は設定に従って設定されます。変更する場合はその場で変更してください。システム バー アイコンの深さはインターフェイスに従います。
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                // 入力ボックスの外側をクリックまたはドラッグして、アプリ全体で統一されたキーボードを非表示にします
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    MikaRoot(store, monitor, ::perform, requestedConversation) { requestedConversation = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
        // は、新しいバージョンを開くたびに静かにチェックし (2 分以内に開いた場合は 1 回のみ)、失敗した場合は音を立てません。
        (application as MikaApp).updates.autoCheckInBackground()
        // コンピューター接続は開いていますが、サービスが存在しません (システムによって停止され、バックグラウンドからの起動に失敗しました)。インターフェースはフォアグラウンドにあるときに復元されます。
        (application as MikaApp).mcp.ensureRunning()
    }

    override fun onResume() {
        super.onResume()
        monitor.refresh()
        (application as MikaApp).paymentNotifications.refresh()
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    private val phone get() = PhoneServices.of(this)

    private fun perform(fix: Fix) {
        when (fix) {
            Fix.OpenAccessibility -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.AllowNotifications -> {
                val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
            }
            Fix.OpenAttentionChannel -> openChannelSettings(TaskService.CHANNEL_ATTENTION)
            Fix.OpenResultChannel -> openChannelSettings(TaskService.CHANNEL_RESULT)
            Fix.OpenProgressChannel -> openChannelSettings(TaskService.CHANNEL_PROGRESS)
            Fix.OpenPairingChannel -> openChannelSettings(PairingNotifier.ensureChannel(this).id)
            Fix.AddModel -> Unit // 自分で行う設定ページ
            // ページングは MegaRoot によって処理されます
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> Unit
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellControl.retry()
            }
            Fix.UnpairPhoneHelper -> phone.pairing.scope.launch { phone.recovery.unpair() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = open(appNotificationSettings())

    /** 特定の通知チャネルの設定ページを直接開きます。システムにこのページがない場合は、アプリケーション通知の一般ページに戻ります。 */
    private fun openChannelSettings(channelId: String) {
        val channelPage = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        firstThatStarts(listOf(channelPage, appNotificationSettings())) { intent ->
            if (intent.resolveActivity(packageManager) == null) false
            else try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
        }
    }

    /** 順番に試して、最初に開けるものを使用してください。すべての設定ページが同じ入り口に一致するわけではありません。 */
    private fun openFirst(intents: List<Intent>) {
        val hit = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        open(hit)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        private const val TAG = "MikaMain"
    }
}

/** システム ブラウザを使用して外部リンク (GitHub、X など) を開きます。ブラウザがない場合にメッセージを表示します。 */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

/** は [candidates] を順番に試行します。最初の [start] が成功した場合は停止します。失敗した場合は false を返します。すべての企業にシステム設定ページがあるわけではないため、これを使用して一般ページに戻ることができます。 */
internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)

/**
 * ページ切り替え: ダイアログ ページがルートで、サイドバーと設定、その他の入り口が [NavStack] に押され、ページ上のリターン キーと戻るボタンがすべて [NavStack.back] に移動します。
 * 各ページはコールバックを通じてのみ出入りし、相互には認識しません。
 */
@Composable
private fun MikaRoot(store: ProviderStore, monitor: SystemStatusMonitor, activityFix: (Fix) -> Unit,
    requestedConversation: String?, onConversationOpened: () -> Unit) {
    val nav = rememberNavStack()
    // 設定ページ「再ペアリング」から入る場合はペアリング手順に戻ります
    var setupRepair by rememberSaveable { mutableStateOf(false) }
    // 初めて開始するときに実行される手順。空の場合は、開始プロセス中ではありません。構成モデルのページが元のページに戻ります。
    var onboarding by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    val onFix: (Fix) -> Unit = { fix ->
        when (fix) {
            Fix.AddModel -> nav.push(Route.Models)
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> {
                setupRepair = fix == Fix.RepairPhoneHelper
                nav.push(Route.PhoneSetup)
            }
            else -> activityFix(fix)
        }
    }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLegacyText by rememberSaveable { mutableStateOf<String?>(null) }
    val status by monitor.status.collectAsState()
    val roles by store.roles.collectAsState()
    val hasMain = roles.main != null
    val check = Prerequisites.check(status, hasMain)
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    // 新しいバージョンが見つかりました: サイドバー、設定ホームページ、バージョン情報ページにある赤い点を確認してください。
    val updateState by app.updates.state.collectAsState()
    LaunchedEffect(requestedConversation) {
        if (requestedConversation != null) {
            app.chat.openConversation(requestedConversation)
            nav.reset()
            onConversationOpened()
        }
    }
    val release = remember { Build.VERSION.RELEASE }
    // 結果カードの「見る」をクリック: スキルセグメンテーションを開く
    val memoryRuntime = remember { MemoryRuntime.of(context) }
    val phoneSetup = remember { PhoneServices.of(context).setupPrompt }
    val openMemory by memoryRuntime.openRequest.collectAsState()
    LaunchedEffect(openMemory) { if (openMemory != null) nav.push(Route.Memory) }
    // を初めて開きます:「ようこそ」→「モデルの構成」→「モバイル操作」。モデルを構成している場合は、最初の 2 つの手順をスキップしてください。モバイル操作の準備ができている場合、またはガイドを参照した場合は、最後のステップをスキップしてください
    val onboardingState = remember { OnboardingState(context) }
    fun onboardingInputs() = Onboarding.Inputs(
        hasMainModel = store.roles.value.main != null,
        modelSetupDone = onboardingState.modelSetupDone(),
        phoneNeeded = status.builtinShell != BuiltinShellStatus.Running && status.sdkInt >= Prerequisites.MIN_SDK,
        phoneGuideSeen = phoneSetup.guideSeen(),
    )
    fun goOnboarding(step: OnboardingStep?) {
        onboarding = step
        when (step) {
            // を開始するための各ステップは、ダイアログ ページのすぐ上に配置されます。完了したらダイアログページに戻ります。
            OnboardingStep.Welcome -> nav.show(Route.Welcome)
            OnboardingStep.Model -> nav.show(Route.ModelSetup)
            OnboardingStep.Phone -> { setupRepair = false; nav.show(Route.PhoneSetup) }
            null -> nav.reset()
        }
    }
    LaunchedEffect(Unit) {
        if (onboarding == null) Onboarding.start(onboardingInputs())?.let(::goOnboarding)
    }
    val phoneSetupNeeded by phoneSetup.needed.collectAsState()
    // ページに独自のリターン処理 (設定ページのサブページとサイドバー) がある場合、それらは最初に接続されます。ここで前のページに戻るだけです
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val back: () -> Unit = { nav.back() }
    // チャットページは、組み合わせツリーから抜けたときのリスト位置と入力内容を保持し、設定ページなどのサブページに戻ると元の状態に戻ります。
    val screenStates = rememberSaveableStateHolder()
    when (nav.current) {
        Route.PhoneSetup -> PhoneSetupScreen(status, setupRepair, onFix, onboarding = onboarding == OnboardingStep.Phone) {
            phoneSetup.markGuideSeen()
            if (onboarding == OnboardingStep.Phone) goOnboarding(null) else nav.back()
        }
        Route.Welcome -> WelcomeScreen { goOnboarding(OnboardingStep.Model) }
        Route.ModelSetup -> {
            val inOnboarding = onboarding == OnboardingStep.Model
            ModelSetupScreen(
                store, onboarding = inOnboarding,
                onBack = { if (inOnboarding) goOnboarding(OnboardingStep.Welcome) else nav.back() },
                onDone = {
                    if (inOnboarding) {
                        onboardingState.markModelSetupDone()
                        goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
                    } else nav.back()
                },
            )
        }
        Route.Chat -> screenStates.SaveableStateProvider(Route.Chat.name) {
            // 会話ページが再び表示されます (フロント デスクに戻り、他のページから戻ってきます)。別の場所に保存されたラウンドを補い、別の場所で開始されたタスクを選択します。
            LifecycleStartEffect(Unit) {
                app.chat.refresh()
                onStopOrDispose {}
            }
            ChatWithDrawer(
                vm = app.chat,
                check = check,
                reduceMotion = MikaTheme.reduceMotion,
                conversations = { ConversationStore.shared(context).list() },
                conversationChanges = ConversationStore.shared(context).changes,
                conversationTitle = { ConversationStore.shared(context).title(it) },
                onPlans = { nav.pushFromDrawer(Route.Plans) },
                onMemory = { nav.pushFromDrawer(Route.Memory) },
                onSettings = { nav.pushFromDrawer(Route.Settings) },
                onBrowser = { nav.pushFromDrawer(Route.Browser) },
                onScreens = { nav.pushFromDrawer(Route.Screens) },
                onAbout = { nav.pushFromDrawer(Route.About) },
                updateAvailable = AboutCopy.drawerDot(updateState),
                reopen = nav.reopenDrawer,
                onReopened = nav::drawerReopened,
            ) { openDrawer ->
                ChatScreen(
                    vm = app.chat,
                    check = check,
                    frames = app.taskState.frames,
                    webOpen = app.tasks.webScreenOpen,
                    reduceMotion = MikaTheme.reduceMotion,
                    onOpenDrawer = openDrawer,
                    openSettings = { nav.push(Route.Settings) },
                    openRun = { runId = it; nav.push(Route.Run) },
                    phoneSetupNeeded = phoneSetupNeeded && status.builtinShell != BuiltinShellStatus.Running,
                    openPhoneSetup = { onFix(Fix.OpenPhoneSetup) },
                    loadSource = { id ->
                        withContext(Dispatchers.IO) { evidenceSource(RunStore(context).events(id)) { ConfirmationNotifier.appLabel(context, it) } }
                    },
                    openSource = { id, legacy ->
                        sourceRunId = id
                        sourceLegacyText = legacy
                        nav.push(Route.Source)
                    },
                    voice = app.voice,
                    openSpeechModel = { nav.push(Route.SpeechModel) },
                )
            }
        }
        Route.Settings -> {
            val autonomous by app.approvals.autonomous.collectAsState()
            SettingsScreen(store, status, release, onFix, onBack = back, onOpenMemory = { nav.push(Route.Memory) },
                onOpenModels = { nav.push(Route.Models) },
                autonomous = autonomous, onAutonomous = app.approvalControl::setAutonomous,
                updateVersion = updateState.update?.version, onOpenAbout = { nav.push(Route.About) },
                mcpServers = app.mcpServers)
        }
        Route.Models -> ModelsScreen(store, onBack = back, onOpenSpeech = { nav.push(Route.SpeechModel) }, onOpenUsage = { nav.push(Route.Usage) })
        Route.Usage -> UsageScreen(app.usage, store.providers, onBack = back)
        Route.SpeechModel -> SpeechModelScreen(store, onBack = back)
        Route.About -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME, build = BuildConfig.GIT_HASH, beta = BuildConfig.BETA,
            update = updateState,
            issueUrl = remember {
                MikaLinks.issueUrl(
                    MikaLinks.Device(
                        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                        BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH,
                    ),
                )
            },
            onCheck = app.updates::checkInBackground,
            onOpenUrl = { openInBrowser(context, it) },
            onOpenLicense = { nav.push(Route.License) },
            onBack = back,
            diagnostics = {
                Diagnostics.render(DiagnosticsCollector.collect(context, status))
            },
        )
        Route.License -> LicensesScreen(onBack = back)
        Route.Browser -> BrowserScreen(remember { UserBrowser.of(context) }, onBack = back)
        Route.Memory -> {
            val registry = remember { WebSites.registry(context) }
            val sites by registry.entries.collectAsState()
            val webSites = WebSitesState(
                sites = sites,
                onOpenLogin = { WebHandover.showLogin(context, it) },
                onLogout = { WebSites.login(context).logout(it) },
                onLogoutAll = { WebSites.logoutAll(context) },
            )
            val routeStore = remember { FileRouteStore.shared(context) }
            var routeCount by remember { mutableStateOf<Int?>(null) }
            // メモリに初めてアクセスするときは、ディスクを読み取って復号化し、それをバックグラウンド スレッドに置きます。ページのコルーチンはこの画面に従います。
            val vmScope = rememberCoroutineScope()
            LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
            val vm by produceState<MemoryViewModel?>(null) {
                value = withContext(Dispatchers.IO) {
                    val bundled = GuideCatalog.loadBundled()
                    MemoryViewModel(
                        memoryRuntime.store(), memoryRuntime.settings(), memoryRuntime.skills(),
                        vmScope, memoryRuntime.file().brokenNotice,
                        wipe = { memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.wipeAll() } },
                        onForgotten = { id -> memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.stripMemory(setOf(id)) } },
                        runnableScripts = bundled.runnableScripts(),
                        appDrafts = memoryRuntime.appKnowledge(),
                        builtinSkillNames = bundled.items.map { it.name }.toSet(),
                    )
                }
            }
            vm?.let { m ->
                // 結果カードの「Look」から入力：スキルセグメントを直接見る
                LaunchedEffect(m) { if (memoryRuntime.openRequest.value != null) m.select(Segment.Skills) }
                MemoryDataScreen(
                    m, webSites, routeCount,
                    onClearRoutes = {
                        vmScope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } }
                    },
                    onBack = { memoryRuntime.requestOpen(null); nav.back() },
                )
            }
        }
        Route.Run -> {
            val id = runId
            val vm = remember(id) { id?.let { RunTimelineViewModel(
                    RunStore(context), it, resolveMemory = app.chat::usedMemoryTexts,
                    alive = { run -> app.taskState.runs.value.any { r -> r.runId.value == run } },
                ) } }
            if (vm != null) RunTimelineScreen(vm, back) else LaunchedEffect(Unit) { nav.back() }
        }
        Route.Source -> {
            val vm = remember(sourceRunId, sourceLegacyText) {
                SourceViewModel(RunStore(context), sourceRunId, sourceLegacyText) { ConfirmationNotifier.appLabel(context, it) }
            }
            SourceScreen(vm, back)
        }
        Route.Plans -> PlansScreen(context, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
        Route.Screens -> ScreensScreen(app.taskState, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
    }
}
