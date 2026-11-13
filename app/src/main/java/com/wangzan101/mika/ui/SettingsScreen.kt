package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.settings.ProviderConnection
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.NetProxyStore
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.notificationOverview
import com.wangzan101.mika.task.ParallelCapability
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 設定の子ページです。[Home] を入口とし、接続・保守・開発項目は [Advanced] にまとめます。 */
private enum class SettingsPage { Home, Advanced, AppAccess, PhoneOps, DuringWork, Capsule, Language, Notifications, Sandbox, Recovery, SelfCheck, ComputerAccess, McpServers, Developer, RunStats }

/** 設定をよく使う項目・自分のデータ・その他へ分けます。モデル・メモリ・アプリ情報と権限修復は Activity へ委譲し、子ページから元の画面へ戻します。 */
@Composable
fun SettingsScreen(
    store: ProviderStore,
    status: SystemStatus,
    androidRelease: String,
    onFix: (Fix) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: (() -> Unit)? = null,
    /** タスク実行設定の自律モードと、その変更処理です。 */
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    /** は見つかった新しいバージョン番号です。それが見つかると、「About Mega」行に「x は更新できます」と赤い点が表示されます。 */
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
    /** 外部 MCP サーバー設定 (アウトバウンド);空の場合、エントリは表示されません。 */
    mcpServers: com.wangzan101.mika.settings.McpServerStore? = null,
) {
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    // 開発者向けオプションの「表示画像の位置確認」は画像モデルに従います（メインモデルを使用して画像を表示する場合は、メインモデルの構成となります）
    val visionConfig = remember(configs, roles) { store.configFor(ModelRole.Vision) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var explaining by remember { mutableStateOf<Problem?>(null) }

    fun fix(f: Fix) = if (f == Fix.AddModel) onOpenModels() else onFix(f)
    val onProblem: (Problem) -> Unit = { p -> StatusCopy.directFix(p)?.let(::fix) ?: run { explaining = p } }

    // 並列タスクの上限は、実験的なスイッチParallelTask​​sがオンになっている場合にのみ表示されます。デバイスの能力はこの時点に基づいて計算され、2 番目の画面の構築に失敗した場合は失敗として計算されます。
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    var parallelMax by remember { mutableIntStateOf(flags.parallelMax()) }
    var keepScreenOn by remember { mutableStateOf(flags.keepScreenOn()) }
    var muteTarget by remember { mutableStateOf(flags.muteTarget()) }
    var operatingPace by remember { mutableStateOf(flags.operatingPace()) }
    var stepLimit by remember { mutableStateOf(flags.taskStepLimit()) }
    var selfConfig by remember { mutableStateOf(flags.selfConfig()) }
    var capsuleMode by remember { mutableStateOf(flags.capsuleMode()) }
    // 言語を変更するとインターフェースが再構築されます。ここに来たらいつでも読むことができます。
    val language = remember { AppLanguage.current(context) }
    val runtimeLimit by (context.applicationContext as com.wangzan101.mika.MikaApp).taskState.parallelLimit.collectAsState()
    var speed by remember { mutableStateOf(flags.speedFlags()) }
    val scope = rememberCoroutineScope()
    val sandboxStatus by remember { SandboxState.get(context) }.status.collectAsState()
    // ネットワーク プロキシ: ここでのみ変更します。復号化する設定を読み取り、ブラウザが WebView の読み込みをサポートしているかどうかを確認し、すべてバックグラウンドで実行されます。
    val proxies = remember { NetProxyStore.get(context) }
    var proxy by remember { mutableStateOf<String?>(null) }
    var browserProxy by remember { mutableStateOf<WebProxy.Mode?>(null) }
    var editingProxy by remember { mutableStateOf(false) }
    LaunchedEffect(proxy) {
        if (proxy == null) proxy = withContext(Dispatchers.IO) { proxies.get() }
        browserProxy = withContext(Dispatchers.IO) { WebProxy.mode() }
    }
    val mcp = remember { (context.applicationContext as com.wangzan101.mika.MikaApp).mcp }
    val mcpNetworkOn by mcp.settings.enabled.collectAsState()
    val mcpUsbOn by mcp.usb.enabled.collectAsState()
    val mcpOn = mcpNetworkOn || mcpUsbOn
    val parallel = if (!speed.parallelTasks) null else ParallelSetting(
        parallelMax,
        ParallelCapability.detect(
            status.sdkInt, context.getSystemService(ActivityManager::class.java).isLowRamDevice, parallelMax,
            secondDisplayFailed = runtimeLimit.reason == ParallelCapability.SECOND_DISPLAY_FAILED,
        ),
    )
    // 開発者向けオプションは、デバッグ バージョンとパフォーマンス テスト バージョンにのみ記載されています。リリース バージョンの各スイッチのデフォルト値は、評価結果に基づいて決定されます。
    val showDeveloper = com.wangzan101.mika.record.DetailedTraces.supportedBuild
    val toHome = { page = SettingsPage.Home }
    val toAdvanced = { page = SettingsPage.Advanced }
    val back = {
        page = when (page) {
            SettingsPage.RunStats -> SettingsPage.Developer
            SettingsPage.SelfCheck -> SettingsPage.Recovery
            SettingsPage.Sandbox, SettingsPage.Recovery, SettingsPage.ComputerAccess,
            SettingsPage.McpServers, SettingsPage.Developer -> SettingsPage.Advanced
            else -> SettingsPage.Home
        }
    }

    if (page != SettingsPage.Home) BackHandler { back() }

    val visiblePage = if (!showDeveloper && (page == SettingsPage.Developer || page == SettingsPage.RunStats)) SettingsPage.Home else page
    when (visiblePage) {
        SettingsPage.Home -> SettingsContent(
            status = status, androidRelease = androidRelease,
            mainModel = roles.main?.let { id -> configs.firstOrNull { it.id == id } }?.let(ProviderConnection::displayName),
            onBack = onBack, onProblem = onProblem, onOpenModels = onOpenModels,
            onOpenPhoneOps = { page = SettingsPage.PhoneOps },
            onOpenAppAccess = { page = SettingsPage.AppAccess },
            onOpenDuringWork = { page = SettingsPage.DuringWork },
            capsuleMode = capsuleMode, onOpenCapsule = { page = SettingsPage.Capsule },
            onOpenNotifications = { page = SettingsPage.Notifications },
            language = language, onOpenLanguage = { page = SettingsPage.Language },
            onOpenMemory = onOpenMemory,
            onOpenAdvanced = { page = SettingsPage.Advanced },
            versionName = BuildConfig.VERSION_NAME, updateVersion = updateVersion, onOpenAbout = onOpenAbout,
        )
        SettingsPage.Advanced -> AdvancedSettingsContent(
            proxy = proxy, proxyNote = proxy?.let { NetProxyCopy.subtitle(it, browserProxy) }, onOpenProxy = { editingProxy = true },
            sandbox = sandboxStatus, onOpenSandbox = { page = SettingsPage.Sandbox },
            showRecovery = status.sdkInt >= Prerequisites.MIN_SDK, onOpenRecovery = { page = SettingsPage.Recovery },
            computerAccess = mcpOn, onOpenComputerAccess = { page = SettingsPage.ComputerAccess },
            mcpServersCount = mcpServers?.servers?.value?.size, onOpenMcpServers = { page = SettingsPage.McpServers },
            showDeveloper = showDeveloper, onOpenDeveloper = { page = SettingsPage.Developer },
            onBack = toHome,
        )
        SettingsPage.AppAccess -> AppAccessScreen(onBack = toHome)
        SettingsPage.PhoneOps -> PhoneOpsScreen(
            status, androidRelease, onProblem,
            onRepair = { onFix(Fix.RepairPhoneHelper) }, onUnpair = { onFix(Fix.UnpairPhoneHelper) }, onBack = toHome,
        )
        SettingsPage.Notifications -> NotificationsScreen(status, onFix = ::fix, onBack = back)
        SettingsPage.DuringWork -> DuringWorkScreen(
            keepScreenOn, onKeepScreenOn = { flags.setKeepScreenOn(it); keepScreenOn = it },
            muteTarget, onMuteTarget = { flags.setMuteTarget(it); muteTarget = it },
            parallel, onParallelMax = { flags.setParallelMax(it); parallelMax = it }, onBack = toHome,
            autonomous = autonomous, onAutonomous = onAutonomous,
            operatingPace = operatingPace, onOperatingPace = { flags.setOperatingPace(it); operatingPace = it },
            stepLimit = stepLimit, onStepLimit = { flags.setTaskStepLimit(it); stepLimit = it },
            selfConfig = selfConfig, onSelfConfig = { flags.setSelfConfig(it); selfConfig = it },
        )
        SettingsPage.Language -> LanguageScreen(language, onLanguage = { AppLanguage.set(context, it) }, onBack = toHome)
        SettingsPage.Capsule -> CapsuleScreen(capsuleMode, onMode = { flags.setCapsuleMode(it); capsuleMode = it }, onBack = toHome)
        SettingsPage.Sandbox -> SandboxScreen(onBack = toAdvanced)
        SettingsPage.Recovery -> RecoveryScreen(onBack = toAdvanced, onOpenSelfCheck = { page = SettingsPage.SelfCheck })
        SettingsPage.SelfCheck -> SelfCheckScreen(status, onBack = back)
        SettingsPage.ComputerAccess -> McpScreen(mcp, onBack = toAdvanced)
        SettingsPage.McpServers -> mcpServers?.let { McpServersScreen(it, onBack = toAdvanced) }
        SettingsPage.Developer -> DeveloperOptionsScreen(
            speed, onSpeed = { flags.setSpeed(it); speed = it }, onBack = toAdvanced,
            grounding = visionConfig?.let { GroundingSetting(it.model, it.capabilities.groundingVerified) },
            onGrounding = { verified -> scope.launch { withContext(Dispatchers.IO) { store.setVisionGroundingVerified(verified) } } },
            onOpenRunStats = { page = SettingsPage.RunStats },
        )
        SettingsPage.RunStats -> RunStatsScreen(remember { RunStore(context).metrics }, onBack = back)
    }

    if (editingProxy) NetProxyDialog(
        initial = proxy.orEmpty(),
        onDismiss = { editingProxy = false },
        onSave = { v ->
            scope.launch {
                withContext(Dispatchers.IO) { proxies.set(v) }
                proxy = v.trim()
                editingProxy = false
            }
        },
    )

    explaining?.let { p ->
        val e = StatusCopy.explain(p, androidRelease)
        MikaDialog(
            onDismissRequest = { explaining = null },
            title = { Text(e.title) },
            text = {
                Column {
                    Text(e.body)
                    e.secondaryAction?.let { (label, destination) ->
                        TextButton(onClick = {
                            explaining = null
                            fix(destination)
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {
                if (e.action != null && e.fix != null) {
                    TextButton({ explaining = null; fix(e.fix) }) { Text(e.action) }
                } else {
                    TextButton({ explaining = null }) { Text(stringResource(R.string.ok_got_it)) }
                }
            },
            dismissButton = if (e.fix != null) ({ TextButton({ explaining = null }) { Text(stringResource(R.string.cancel)) } }) else null,
        )
    }
}

/**
 * は、テストを容易にするためにステータスなしのホームページを設定します。オプションの入口 ([usageTokens]、[onOpenMemory]、[webSitesCount]、[grantsCount]、[routes]、[sandbox])
 * が空の場合、対応する行は表示されません。 [showDeveloper] が false (リリース バージョン) の場合、開発者向けオプションはありません。
 */
@Composable
fun SettingsContent(
    status: SystemStatus,
    androidRelease: String,
    /** 現在のメインモデルの名前。利用可能なメイン モデルがない場合は空です。 */
    mainModel: String?,
    onBack: () -> Unit,
    onProblem: (Problem) -> Unit,
    onOpenModels: () -> Unit,
    onOpenPhoneOps: () -> Unit = {},
    onOpenAppAccess: () -> Unit = {},
    onOpenDuringWork: () -> Unit = {},
    /** 現在選択されているサスペンション カプセルは、「サスペンション カプセル」行の右側に表示されます。 */
    capsuleMode: CapsuleMode = CapsuleMode.DEFAULT,
    onOpenCapsule: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    /** 言語と外観の行に、現在の表示言語を示します。 */
    language: AppLanguage = AppLanguage.System,
    onOpenLanguage: () -> Unit = {},
    onOpenMemory: (() -> Unit)? = null,
    onOpenAdvanced: () -> Unit = {},
    versionName: String = "",
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
) {
    val check = Prerequisites.check(status, hasMainModel = mainModel != null)
    PageScaffold(stringResource(R.string.settings_title), onBack) {
        StatusSummary(check, onProblem)

        GroupLabel(stringResource(R.string.settings_basic))
        GroupCard {
            ListRow(MikaIcons.Spark, stringResource(R.string.settings_model), stringResource(R.string.settings_model_sub), first = true, onClick = onOpenModels) {
                RowValue(mainModel ?: stringResource(R.string.settings_not_set))
                RowChevron()
            }
            ListRow(MikaIcons.Phone, stringResource(R.string.settings_phone_ops), onClick = onOpenPhoneOps) {
                PhoneOpsPill(status)
                RowChevron()
            }
            ListRow(MikaIcons.Moon, stringResource(R.string.settings_during_work), stringResource(R.string.settings_during_work_sub), onClick = onOpenDuringWork) { RowChevron() }
            ListRow(MikaIcons.Message, CapsuleSettingsCopy.TITLE, CapsuleSettingsCopy.SUBTITLE, onClick = onOpenCapsule) {
                RowValue(CapsuleSettingsCopy.label(capsuleMode))
                RowChevron()
            }
            ListRow(MikaIcons.Globe, stringResource(R.string.settings_language_appearance), onClick = onOpenLanguage) {
                RowValue(languageLabel(language))
                RowChevron()
            }
            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            ListRow(MikaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_sub), onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
        }

        GroupLabel(stringResource(R.string.settings_privacy))
        GroupCard {
            onOpenMemory?.let {
                ListRow(MikaIcons.Heart, stringResource(R.string.settings_memory_data), stringResource(R.string.settings_memory_sub), first = true, onClick = it) { RowChevron() }
            }
            ListRow(MikaIcons.Shield, stringResource(R.string.appaccess_title), stringResource(R.string.appaccess_row_sub), first = onOpenMemory == null, onClick = onOpenAppAccess) { RowChevron() }
        }

        GroupLabel(stringResource(R.string.settings_other))
        GroupCard {
            ListRow(MikaIcons.Wrench, stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_sub), first = true, onClick = onOpenAdvanced) { RowChevron() }
        }
        GroupCard {
            ListRow(MikaIcons.Info, stringResource(R.string.settings_about), first = true, onClick = onOpenAbout) {
                TrailingValue(AboutCopy.settingsValue(updateVersion, versionName))
                RowChevron()
            }
        }
    }
}

/** 接続・保守・開発の入口をまとめ、各機能は既存の子ページで設定します。 */
@Composable
fun AdvancedSettingsContent(
    onBack: () -> Unit,
    proxy: String? = null,
    proxyNote: String? = null,
    onOpenProxy: () -> Unit = {},
    sandbox: SandboxStatus? = null,
    onOpenSandbox: () -> Unit = {},
    showRecovery: Boolean = true,
    onOpenRecovery: () -> Unit = {},
    computerAccess: Boolean? = null,
    onOpenComputerAccess: () -> Unit = {},
    mcpServersCount: Int? = null,
    onOpenMcpServers: () -> Unit = {},
    showDeveloper: Boolean = false,
    onOpenDeveloper: () -> Unit = {},
) {
    PageScaffold(stringResource(R.string.settings_advanced), onBack) {
        val sandboxRow = sandbox?.takeIf { it !is SandboxStatus.Unsupported }
        if (proxy != null || sandboxRow != null || computerAccess != null || mcpServersCount != null) {
            GroupLabel(stringResource(R.string.settings_connections))
            GroupCard {
                var first = true
                fun take(): Boolean = first.also { first = false }
                proxy?.let {
                    ListRow(MikaIcons.Link, NetProxyCopy.TITLE, proxyNote, first = take(), onClick = onOpenProxy) {
                        RowValue(NetProxyCopy.value(it))
                        RowChevron()
                    }
                }
                if (sandboxRow != null) {
                    ListRow(MikaIcons.Terminal, stringResource(R.string.settings_sandbox), first = take(), onClick = onOpenSandbox) {
                        RowValue(sandboxValue(sandboxRow)); RowChevron()
                    }
                }
                computerAccess?.let { on ->
                    ListRow(MikaIcons.Desktop, stringResource(R.string.mcp_title), first = take(), onClick = onOpenComputerAccess) {
                        RowValue(stringResource(if (on) R.string.state_on else R.string.state_off)); RowChevron()
                    }
                }
                mcpServersCount?.let { count ->
                    ListRow(MikaIcons.Link, stringResource(R.string.mcp_servers_title), first = take(), onClick = onOpenMcpServers) {
                        RowValue(stringResource(R.string.mcp_servers_count, count)); RowChevron()
                    }
                }
            }
        }
        if (showRecovery) {
            GroupLabel(stringResource(R.string.settings_maintenance))
            GroupCard {
                ListRow(MikaIcons.Wrench, stringResource(R.string.settings_recovery), first = true, onClick = onOpenRecovery) { RowChevron() }
            }
        }
        if (showDeveloper) {
            GroupLabel(stringResource(R.string.settings_developer_group))
            GroupCard {
                ListRow(MikaIcons.Code, stringResource(R.string.settings_developer), first = true, onClick = onOpenDeveloper) { RowChevron() }
            }
        }
    }
}

/** 「モバイル操作」行の末尾にあるステータス カプセル: アクセシビリティと組み込みアシスタントの準備ができている場合にのみ「利用可能」。 */
@Composable
private fun PhoneOpsPill(status: SystemStatus) {
    when {
        status.sdkInt < Prerequisites.MIN_SDK -> StatusPill(stringResource(R.string.pill_unsupported), PillTone.Neutral)
        status.builtinShell == BuiltinShellStatus.Running && status.accessibility == AccessibilityState.On -> StatusPill(stringResource(R.string.pill_available), PillTone.Ok)
        else -> StatusPill(stringResource(R.string.pill_needs_attention), PillTone.Warn)
    }
}

private fun sandboxValue(s: SandboxStatus): String = when (s) {
    is SandboxStatus.Ready -> tr(R.string.sandbox_ready)
    is SandboxStatus.Installing -> tr(R.string.sandbox_preparing)
    is SandboxStatus.Broken -> tr(R.string.pill_needs_attention)
    else -> tr(R.string.sandbox_not_prepared)
}

/**
 * ページ上部の全体的なステータス: すべてが正常な場合は「準備完了」。それ以外の場合は、修復ボタンを含む最初の問題プロンプトになります。
 * フォーカスリマインダーは変更できません。これは、携帯電話の操作ページの「システム バージョン」行でのみ要求され、このスペースを占有しません。
 */
@Composable
private fun StatusSummary(check: Prerequisites.Check, onProblem: (Problem) -> Unit) {
    val problem = check.problems.firstOrNull { it != Problem.FocusMayLeak }
    if (problem == null) {
        StateCard(MikaIcons.Check, stringResource(R.string.all_ready), stringResource(R.string.settings_all_ready_detail), TileTone.Ok, Modifier.testTag("settings-status"))
    } else {
        val copy = StatusCopy.banner(problem)
        StateCard(
            MikaIcons.Exclaim, copy.title, copy.detail,
            if (problem in check.blockers) TileTone.Bad else TileTone.Warn,
            Modifier.testTag("settings-status"),
        ) {
            MikaButton(copy.link, { onProblem(problem) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
        }
    }
}
