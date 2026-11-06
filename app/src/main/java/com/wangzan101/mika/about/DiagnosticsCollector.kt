package com.wangzan101.mika.about

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingChannel
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.SetupInputs
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SystemSpeech

/** ブート ページの入力: システム ステータスと、ブート ページに記録されたいくつかの項目 (ユーザーによって確認、スキップ、およびやり直し) で構成されます。ブート ページと診断情報はこれを共有します。 */
fun SystemStatus.setupInputs(
    discovery: PairingDiscovery,
    repair: Boolean,
    accessibilitySkipped: Boolean = false,
    confirmed: Set<SetupStep> = emptySet(),
    revisit: SetupStep? = null,
    stalledMs: Long = 0L,
    usbAsked: Boolean = false,
    rootAvailable: Boolean = false,
) = SetupInputs(
    developerOptions = developerOptions, wirelessDebugging = wirelessDebugging,
    status = builtinShell, discovery = discovery, notificationsAllowed = notificationsAllowed,
    pairingChannel = pairingChannel, repair = repair,
    usbDebugging = usbDebugging, accessibility = accessibility == AccessibilityState.On,
    accessibilitySkipped = accessibilitySkipped, restrictedSettings = restrictedSettings,
    confirmed = confirmed, revisit = revisit,
    wirelessMissing = wirelessDebuggingMissing, stalledMs = stalledMs, usbAsked = usbAsked,
    restart = helperRestart, developerSettingsMissing = developerSettingsMissing,
    rootAvailable = rootAvailable,
)

/**
 * は、アプリ [DiagnosticsSnapshot] の既製ステータスのコピーを収集します: システム ステータス、組み込みアシスタント、自動回復、モデル構成、実行記録、更新のチェック。
 * はディスク (実行中のレコード) を読み取り、バックグラウンド スレッドで呼び出す必要があります。 [setup] はブート ページの現在の入力です。ブート ページが使用できない場合は、システム ステータスに従って新しいコピーを作成します (ユーザーによって確認される手順はありません)。
 */
object DiagnosticsCollector {
    private const val RECENT_RUNS = 3

    fun collect(context: Context, status: SystemStatus, setup: SetupInputs? = null): DiagnosticsSnapshot {
        val app = context.applicationContext as MikaApp
        val phone = PhoneServices.of(app)
        val inputs = setup ?: status.setupInputs(
            phone.shellControl.pairingDiscovery.value, phone.pairing.repairing.value,
            rootAvailable = phone.shellManager.root.state.value.suPresent,
        )
        return DiagnosticsSnapshot(
            generatedAtMs = System.currentTimeMillis(),
            app = DiagnosticsSnapshot.App(BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH, installer(app), language(app)),
            device = DiagnosticsSnapshot.Device(
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                sandbox(SandboxState.get(app).status.value),
            ),
            setup = inputs,
            phone = DiagnosticsSnapshot.Phone(
                accessibility = status.accessibility,
                wirelessDebuggingSetting = runCatching {
                    Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }
                }.getOrNull(),
                connect = phone.shellManager.lastConnectCandidates,
                pairingChannels = PairingChannel.IDS.mapNotNull { id -> channel(app, id)?.let { id to it } }.toMap(),
                attentionChannelImportance = channel(app, TaskService.CHANNEL_ATTENTION),
                wifiConnected = wifi(app),
                lastError = phone.shellManager.engine.lastError,
                appCopies = appCopies(app, phone),
                adbKey = adbKey(app),
                appsAfterTask = com.wangzan101.mika.task.AppsAfterTaskEnd.lastLine,
                root = phone.shellManager.root.state.value.let { r ->
                    Diagnostics.rootLine(r.suPresent, r.start, r.startGrant.name, r.commands, r.commandsGrant.name)
                },
                helperLaunchStage = phone.shellManager.lastHelperLaunchStage,
                helperHandoffRejection = phone.hub.lastHandoffRejection,
            ),
            recovery = DiagnosticsSnapshot.Recovery(
                consent = phone.recoverySettings.consent.value,
                writeSecureSettings = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
                ignoringBatteryOptimizations = runCatching {
                    app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
                }.getOrNull(),
                lastRestore = phone.recoverySettings.lastRestore.value?.name,
                helperRestores = phone.hub.restores.value,
                lastA11yLoss = phone.recoverySettings.lastA11yLoss.value,
            ),
            models = models(app),
            runs = runs(app),
            update = app.updates.state.value.let { u ->
                DiagnosticsSnapshot.Update(u.status.name, u.latest?.version, u.update?.version)
            },
            speech = speech(app),
        )
    }

    /** の組み込みアシスタントは、各コピーに対するユーザーの判断を継続的に求め、他のコピーをいくつ開くことができるかを数えます (パッケージ名のリストはなく、数えるだけです)。要求されない場合は null になります。 */
    private fun appCopies(app: MikaApp, phone: PhoneServices): String? = runCatching {
        val shell = phone.hub.shell.value ?: return null
        val summary = shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY) ?: return null
        val copies = AppCopyReader.read(shell, launchableApps(app.packageManager, app.packageName))
        "$summary; ${copies?.size ?: "?"} app copies"
    }.getOrNull()

    private fun installer(context: Context): String? = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()

    private fun language(context: Context): String {
        val shown = context.resources.configuration.locales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag() ?: "?"
        return "${AppLanguage.current(context).name} ($shown)"
    }

    private fun sandbox(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Unsupported -> "Unsupported: ${s.reason}"
        is SandboxStatus.Installing -> "Installing ${s.percent}%"
        is SandboxStatus.Ready -> "Ready"
        is SandboxStatus.Broken -> "Broken: ${s.reason}"
        SandboxStatus.NotInstalled -> "NotInstalled"
    }

    private fun channel(context: Context, id: String): Int? =
        runCatching { context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance }.getOrNull()

    /** は状態を確認するだけで、診断のためのキーを読み取りません。 */
    private fun adbKey(context: Context): String? = runCatching {
        val src = AndroidAdb.identities(context)
        src.state.name + (src.problem?.let { " ($it)" } ?: "")
    }.getOrNull()

    private fun wifi(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrNull()

    private fun models(app: MikaApp): DiagnosticsSnapshot.Models {
        val store = app.providers
        fun role(r: ModelRole) = store.configFor(r)?.let { DiagnosticsSnapshot.RoleModel(it.model, it.baseUrl) }
        val chatGpt = store.chatGptProvider()
        return DiagnosticsSnapshot.Models(
            providers = store.providers.value.map { DiagnosticsSnapshot.Provider(it.kind.name, it.baseUrl, it.models?.size) },
            main = role(ModelRole.Main),
            vision = role(ModelRole.Vision),
            light = role(ModelRole.Light),
            chatGptSignedIn = chatGpt != null && store.hasKey(chatGpt.id),
            chatGptLastFailure = app.chatGptSignIn.lastFailure,
            accountLastFailure = app.chatGptSignIn.lastAccountFailure,
        )
    }

    /** 音声テキスト変換: 選択、携帯電話の内蔵認識機能とマーク、携帯電話に登録されている認識サービス (コンポーネント名、開くかどうか、必要な権限)。 */
    private fun speech(app: MikaApp): DiagnosticsSnapshot.Speech? = runCatching {
        val settings = app.speech
        val cap = SystemSpeech.capability(app, settings.systemSilent.value)
        val choice = when (val c = settings.choice.value) {
            SpeechChoice.Auto -> "auto"
            SpeechChoice.System -> "system"
            SpeechChoice.Off -> "off"
            is SpeechChoice.Doubao -> "doubao-asr-2.0 / ${c.resourceId}"
            is SpeechChoice.Provider -> c.model + (app.providers.providers.value.firstOrNull { it.id == c.providerId }?.let { " @ ${Diagnostics.host(it.baseUrl)}" } ?: " (provider removed)")
        }
        DiagnosticsSnapshot.Speech(
            choice = choice,
            systemAvailable = cap.available,
            onDevice = cap.onDevice,
            silent = settings.systemSilent.value,
            remembered = settings.systemService,
            services = SystemSpeech.services(app).map { svc ->
                svc.component + (if (svc.open) "" else " (not exported)") + (svc.permission?.let { " needs $it" } ?: "")
            },
        )
    }.getOrNull()

    /** の最後の数回の実行: 終了とステップ数が要約され、モデルが要約されます。目標、答え、失敗の元の言葉は取り上げられません。 */
    private fun runs(app: MikaApp): List<DiagnosticsSnapshot.Run> = runCatching {
        val store = RunStore(app)
        val models = store.list().take(RECENT_RUNS * 2).associate { it.id to it.model }
        store.metrics.recent(RECENT_RUNS).reversed().map { m ->
            DiagnosticsSnapshot.Run(m.startedAtMs, m.outcome, m.failKind, m.judged, m.steps, models[m.runId])
        }
    }.getOrDefault(emptyList())
}
