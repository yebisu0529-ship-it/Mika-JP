package com.wangzan101.mika.about

import com.wangzan101.mika.core.adb.ConnectCandidates
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.A11yLossRecord
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.HelperRestoreReport
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.SetupInputs
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.ChatGptSignInFailureRecord
import java.net.URI

/**
 * 「診断情報のコピー」時に読み取られたステータス。トラブルシューティングに必要なもののみを含めます: バージョン、モデル、携帯電話操作の各ステップのステータス、モデルのインターフェイスのタイプとアドレス、
 * の最近の実行結果。キー、ログイン、チャットの内容、タスクの目的、画面の内容、IP、ポート、Wi-Fi 名は含まれません。
 * ブート ページ上のいくつかの項目は、ブート ページ自体 [setup] (同じステート マシンの計算ステップ) の直接の入力であり、追加の計算は必要ありません。
 */
data class DiagnosticsSnapshot(
    val generatedAtMs: Long,
    val app: App,
    val device: Device,
    val setup: SetupInputs,
    val phone: Phone,
    val recovery: Recovery,
    val models: Models,
    val runs: List<Run>,
    val update: Update,
    /** 音声からテキストへ: 選択された内容と携帯電話の認識ステータス。古いコールセンターがそれを提供しない場合、私はこの段落を書きません。 */
    val speech: Speech? = null,
) {
    data class App(val versionName: String, val build: String, val installer: String?, val language: String)

    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val abis: List<String>,
        /** サンドボックス (実行環境) ステータス: サポートされていない場合の理由を書き込みます。 */
        val sandbox: String,
    )

    data class Phone(
        val accessibility: AccessibilityState,
        /** システム設定の「ワイヤレス デバッグ」項目。この項目は読み取ることができず、null です。 */
        val wirelessDebuggingSetting: Boolean?,
        val connect: ConnectCandidates?,
        /** There are existing pairing code notification channels in the system (id to the most important level);どれも空ではありません。利用可能かどうかは[SetupInputs.pairingChannel]によります。 */
        val pairingChannels: Map<String, Int>,
        val attentionChannelImportance: Int?,
        val wifiConnected: Boolean?,
        /** 最新のペアリングまたは接続失敗の元のテキスト (レンダリング時に感度が解除されます)。 */
        val lastError: String?,
        /**
         * 別のユーザーのアプリ (クローン、作業データ): 組み込みアシスタントは、各コピーのユーザー (番号、タグ、タイプ、データ グループ、名前を除く) と開くことができる他のコピーの数を決定します。 アシスタントが接続されていない場合、またはアシスタントを要求できない場合、
         * は null になります。
         */
        val appCopies: String? = null,
        /** このプロセスのペアになったキーのステータス (未使用、リードバック、新規、読み取り不能、およびその理由)。読み取られない場合は null です。 */
        val adbKey: String? = null,
        /** このプロセスの最新のタスクの終了時に、Mika の画面にいくつかのアプリが残されて閉じられました (パッケージ名ではなく番号のみ)。渡されていないものは null です。 ミカは */
        val appsAfterTask: String? = null,
        /** su の有無、2つのスイッチ、su の応答。コマンド内容は含めず、旧呼び出し元が渡さなければ null。 */
        val root: String? = null,
        /** 最近启动助手到达的固定阶段;这次进程还没启动过为 null。 */
        val helperLaunchStage: String? = null,
        /** 最近一次 Binder 交接拒绝的固定枚举;没有被拒过为 null。 */
        val helperHandoffRejection: String? = null,
    )

    data class Recovery(
        val consent: RecoveryConsent,
        val writeSecureSettings: Boolean,
        /** システムのバッテリー最適化で制限されませんか?読み込まれない場合は null になります。 */
        val ignoringBatteryOptimizations: Boolean?,
        val lastRestore: String?,
        /** 内蔵アシスタントが殺されたミカを引き上げる記録（アシスタント引き継ぎ時間到来）; nullではありません。 */
        val helperRestores: HelperRestoreReport? = null,
        /** 最後に Mega を起動したとき、アクセシビリティ サービスが削除されているか、バインドされていないことが判明しました。そうでない場合は null です。 */
        val lastA11yLoss: A11yLossRecord? = null,
    )

    data class Models(
        val providers: List<Provider>,
        val main: RoleModel?,
        val vision: RoleModel?,
        val light: RoleModel?,
        val chatGptSignedIn: Boolean,
        /** この実行以降、ChatGPT を使用した最新のログインが失敗しました (感度が解除されていることを示します)。そうでない場合は null です。 */
        val chatGptLastFailure: ChatGptSignInFailureRecord? = null,
        /** アカウント ログイン (xAI) 最新のログインに失敗しました。そうでない場合は null です。 */
        val accountLastFailure: ChatGptSignInFailureRecord? = null,
    )

    /** サービス プロバイダー: インターフェイス タイプ、アドレス (ホスト名のみが取得されます)、リストされたいくつかのモデル (リストされていない場合は null)。 */
    data class Provider(val kind: String, val baseUrl: String, val listedModels: Int?)

    data class RoleModel(val model: String, val baseUrl: String)

    /** 1 回の実行: 開始時間、終了時間、失敗カテゴリ (元の単語ではありません)、実行されたステップ数、使用されたモデル。 */
    data class Run(val startedAtMs: Long, val outcome: String, val failKind: String?, val judged: String?, val steps: Int, val model: String?)

    data class Update(val status: String, val latest: String?, val available: String?)

    /**
     * [choice] はユーザーの選択です (自動、システム、オフ、またはサービス プロバイダー モデル「モデル @ アドレス」)。 [systemAvailable] および [onDevice] はシステムの機能です。
     * [silent] は応答なしとして記録されます。 [remembered] は点呼テストに合格した識別サービスです。 [services] は携帯電話に登録されている識別サービスで、1 行につき 1 行です。
     */
    data class Speech(
        val choice: String,
        val systemAvailable: Boolean,
        val onDevice: Boolean,
        val silent: Boolean,
        val remembered: String?,
        val services: List<String>,
    )
}

/** [DiagnosticsSnapshot] をプレーン テキストとして書き込みます。セグメントの順序は固定されており、英語キーが使用され、WeChat または GitHub に送信するときに読みやすくなります。最後に、感度を下げるためにセクション全体をもう一度実行します。 */
object Diagnostics {
    const val HEADER = "Mika JP 診断情報"

    fun render(s: DiagnosticsSnapshot): String = sanitize(buildString {
        appendLine(HEADER)
        appendLine("generated: ${java.time.Instant.ofEpochMilli(s.generatedAtMs).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)}")

        section("App")
        line("version", "${s.app.versionName} (build ${s.app.build})")
        line("installer", s.app.installer ?: "unknown")
        line("language", s.app.language)

        section("Device")
        val d = s.device
        line("model", if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}")
        line("android", "${d.androidRelease} (SDK ${d.sdk})")
        line("abi", d.abis.joinToString(", ").ifEmpty { "unknown" })
        line("arm64 sandbox", if ("arm64-v8a" in d.abis) "supported (${d.sandbox})" else "not supported (${d.sandbox})")

        section("Phone control")
        phone(s)

        section("Recovery")
        val r = s.recovery
        for (c in RecoveryCapability.entries) line(capability(c), consent(r.consent, c))
        line("write secure settings", if (r.writeSecureSettings) "granted" else "not granted")
        line("battery optimization", when (r.ignoringBatteryOptimizations) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" })
        line("last reboot restore", r.lastRestore ?: "none")
        line("restored by helper", r.helperRestores?.let { h ->
            (if (h.lastRestoredAtMs > 0) "${ago(s.generatedAtMs - h.lastRestoredAtMs)}, ${h.recent} recently" else "never") +
                (if (h.gaveUpAtMs > 0) ", backed off ${ago(s.generatedAtMs - h.gaveUpAtMs)}" else "")
        } ?: "never")
        line("accessibility on start", r.lastA11yLoss?.let { "${it.loss.name.lowercase()} ${ago(s.generatedAtMs - it.atMs)}" } ?: "ok")

        section("Models")
        val m = s.models
        line("providers", if (m.providers.isEmpty()) "none" else "${m.providers.size}")
        m.providers.forEach { p -> appendLine("- ${p.kind} ${host(p.baseUrl)}" + (p.listedModels?.let { " ($it models listed)" } ?: "")) }
        line("main", m.main?.let(::role) ?: "not set")
        line("vision", m.vision?.let(::role) ?: "off")
        line("light", m.light?.let(::role) ?: "off")
        line("ChatGPT sign-in", yes(m.chatGptSignedIn))
        m.chatGptLastFailure?.let { f ->
            line("ChatGPT last sign-in failure", "${ago(s.generatedAtMs - f.atMs)}: ${f.kind}" +
                (f.code?.let { " (code $it)" } ?: "") + f.detail.takeIf { it.isNotBlank() }?.let { " — ${it.take(MAX_ERROR)}" }.orEmpty())
        }
        m.accountLastFailure?.let { f ->
            line("account last sign-in failure", "${ago(s.generatedAtMs - f.atMs)}: ${f.kind}" +
                (f.code?.let { " (code $it)" } ?: "") + f.detail.takeIf { it.isNotBlank() }?.let { " — ${it.take(MAX_ERROR)}" }.orEmpty())
        }

        s.speech?.let { v ->
            section("Speech")
            line("speech to text", v.choice)
            line("built-in recognition", if (!v.systemAvailable) "none" else "present" + (if (v.onDevice) ", on-device" else "") + (if (v.silent) ", marked no response" else ""))
            v.remembered?.let { line("recognition service used", it) }
            line("recognition services", if (v.services.isEmpty()) "none" else "${v.services.size}")
            v.services.forEach { appendLine("- $it") }
        }

        section("Recent runs")
        if (s.runs.isEmpty()) appendLine("none")
        s.runs.forEach { run ->
            appendLine(
                "- ${ago(s.generatedAtMs - run.startedAtMs)}: ${run.outcome}" +
                    (run.failKind?.let { " ($it)" } ?: "") + (run.judged?.let { ", judged $it" } ?: "") +
                    ", ${run.steps} steps" + (run.model?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""),
            )
        }

        section("Update")
        line("last check", s.update.status)
        line("latest seen", s.update.latest ?: "none")
        line("update available", s.update.available ?: "no")
    }.trimEnd() + "\n")

    private fun StringBuilder.phone(s: DiagnosticsSnapshot) {
        val i = s.setup
        val p = s.phone
        val view = PhoneSetupFlow.view(i)
        line("helper", shell(i.status))
        line("helper restart", when (i.restart) {
            HelperRestart.Wireless -> "wireless debugging (paired)"
            HelperRestart.UsbCommand -> "USB start command"
            HelperRestart.Root -> "root (su)"
            HelperRestart.None -> "not set up"
        })
        line("setup", "phase=${view.phase}" + (view.current?.let { ", current=${step(it)}" } ?: ""))
        line("steps", SetupStep.entries.joinToString(", ") { "${it.ordinal + 1} ${view.marks[it]}" })
        view.suspect?.let { line("suspect", step(it.step) + if (it.unreadable) " (switch unreadable)" else "") }
        line("confirmed by user", i.confirmed.sortedBy { it.ordinal }.joinToString(", ") { step(it) }.ifEmpty { "none" })
        if (i.accessibilitySkipped) line("accessibility step", "skipped")
        if (i.repair) line("repair", "yes")
        line("developer options", onOff(i.developerOptions))
        if (i.developerSettingsMissing) line("developer options page", "missing")
        if (i.wirelessMissing) line("wireless debugging setting", "missing")
        line("USB debugging", onOff(i.usbDebugging))
        line("wireless debugging", p.wirelessDebuggingSetting?.let(::onOff) ?: "unreadable")
        line("accessibility", when (p.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        })
        line("restricted settings", if (i.restrictedSettings) "may block" else "no")
        line("pairing service", when (i.discovery) {
            is PairingDiscovery.Found -> "found"
            PairingDiscovery.Searching -> "searching"
            PairingDiscovery.Idle -> "not found / not searching"
        })
        line("connect service", p.connect?.let { c ->
            "cached ${c.cache}, property ${c.property}, mDNS ${c.mdns} candidates; " +
                (if (c.scanned) "scan: ${c.scanOpen} open, ${c.scanAdb} adb; " else "scan: not run; ") +
                "manual: " + (if (c.manual) "yes" else "no") + "; " +
                if (c.connected) "connected" else "none connected"
        } ?: "not tried in this session")
        line("notifications", if (i.notificationsAllowed) "allowed" else "blocked")
        line("pairing channel", "${i.pairingChannel} (" +
            (if (p.pairingChannels.isEmpty()) "not created" else p.pairingChannels.entries.joinToString(", ") { "${it.key}=${importance(it.value)}" }) + ")")
        view.notifyIssue?.let { line("pairing notification issue", it.name) }
        line("attention channel", importance(p.attentionChannelImportance))
        line("Wi-Fi", when (p.wifiConnected) { true -> "connected"; false -> "not connected"; null -> "unknown" })
        line("last error", p.lastError?.takeIf { it.isNotBlank() }?.take(MAX_ERROR) ?: "none")
        line("helper launch stage", p.helperLaunchStage ?: "not tried in this session")
        line("helper handoff rejection", p.helperHandoffRejection ?: "none")
        line("adb key status", p.adbKey ?: "unknown")
        p.root?.let { line("root", it) }
        line("app copies", p.appCopies ?: "unknown")
        p.appsAfterTask?.let { line("apps after task", it) }
    }

    private fun StringBuilder.section(name: String) {
        appendLine()
        appendLine("[$name]")
    }

    private fun StringBuilder.line(key: String, value: String) {
        appendLine("$key: $value")
    }

    private fun step(s: SetupStep) = "${s.ordinal + 1} ${s.name}"

    private fun shell(s: BuiltinShellStatus): String = when (s) {
        is BuiltinShellStatus.Failed -> "Failed (reason=${s.reason}, fix=${s.fix})"
        else -> s.toString()
    }

    private fun capability(c: RecoveryCapability) = when (c) {
        RecoveryCapability.Accessibility -> "auto accessibility"
        RecoveryCapability.BatteryWhitelist -> "battery whitelist"
        RecoveryCapability.RestoreAfterReboot -> "restore after reboot"
        RecoveryCapability.AutoWirelessDebugging -> "auto wireless debugging"
    }

    private fun consent(r: RecoveryConsent, c: RecoveryCapability) =
        (if (r.wants(c)) "on" else "off by user") + ", " + (if (r.isApplied(c)) "applied" else "not applied")

    private fun role(r: DiagnosticsSnapshot.RoleModel) = "${r.model} @ ${host(r.baseUrl)}"

    /** アドレスにはホスト名のみが含まれます (パスとクエリには他のものが含まれる場合があります)。解析できない場合は書き込まれません。 */
    fun host(url: String): String = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(unparsed host)"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /**
     * root の診断行。su がなければその旨だけを表示し、あれば起動・コマンドの各スイッチと前回の応答を表示する。
     * 許可応答の列挙値は診断用の識別子として保持する。
     */
    fun rootLine(suPresent: Boolean, start: Boolean, startGrant: String, commands: Boolean, commandsGrant: String): String =
        if (!suPresent && !start && !commands) "su が見つかりません"
        else (if (suPresent) "su を検出" else "su が見つかりません") +
            "; ヘルパー起動 ${if (start) "オン" else "オフ"} ($startGrant); root コマンド ${if (commands) "オン" else "オフ"} ($commandsGrant)"

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    /** システム値に従った通知チャネルの重要度: 0 オフ、1 最小、2 低、3 デフォルト、4 高。 */
    private fun importance(i: Int?) = when (i) {
        null -> "not created"
        0 -> "off"
        1 -> "min"
        2 -> "low"
        3 -> "default"
        4, 5 -> "high"
        else -> "$i"
    }

    fun ago(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 48 * 60 -> "${m / 60}h ago"
            else -> "${m / (24 * 60)}d ago"
        }
    }

    private const val MAX_ERROR = 200

    private val IP = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b(:\d{1,5})?""")
    /** は完全に (少なくとも 5 段落) 書かれるか、「::」で省略されます。 12:30:05のタイムはカウントされません。 */
    private val IPV6 = Regex("""\[?(?:\b(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}\b|[0-9A-Fa-f]{0,4}::[0-9A-Fa-f:]*[0-9A-Fa-f])(?:%\w+)?\]?(:\d{1,5})?""")
    private val PORT = Regex("""(?i)\bport\s*[=:]?\s*\d{2,5}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val SECRET_PAIR = Regex("""(?i)\b(api[_-]?key|key|token|secret|password|passwd|authorization|cookie)\b(\s*[=:]\s*)\S+""")

    /** 前回: IP (ポート付き)、IPv6、ポート番号、電子メール、「名前=値」などの資格情報が、[Redactor] (キー、JWT、長くて複雑な文字列) に渡されます。 */
    fun sanitize(text: String): String {
        var out = IP.replace(text, "<ip>")
        out = IPV6.replace(out, "<ip>")
        out = PORT.replace(out, "port <n>")
        out = EMAIL.replace(out, "<email>")
        out = SECRET_PAIR.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + Redactor.MASK }
        return Redactor.pageData(out)
    }
}
