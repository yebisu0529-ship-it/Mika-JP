package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.shell.AutoOnBlock
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RepairOutcome
import com.wangzan101.mika.core.shell.RestoreOutcome
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 設定 › 回復と修復 (簡単なテストのための純粋な機能) にある携帯電話操作用のいくつかの認証スイッチのテキスト。紹介ページ同様、わかりやすい言葉で、
 * 各項目には、電源をオンにした後のミカの動作とオフの方法が明確に記載されています。指示は常にスイッチの下に書かれています。これらはデフォルトですべてオンになっており、携帯電話を操作できるように設定した後、ミカがそれを補います。
 */
object RecoveryCopy {
    data class SwitchCopy(val title: String, val subtitle: String, val explain: String)

    fun switch(c: RecoveryCapability): SwitchCopy = when (c) {
        RecoveryCapability.Accessibility -> SwitchCopy(
            tr(R.string.rec_a11y),
            tr(R.string.rec_default_on),
            tr(R.string.rec_a11y_body),
        )
        RecoveryCapability.BatteryWhitelist -> SwitchCopy(
            tr(R.string.rec_battery),
            tr(R.string.rec_default_on),
            tr(R.string.rec_battery_body),
        )
        RecoveryCapability.RestoreAfterReboot -> SwitchCopy(
            tr(R.string.rec_boot),
            tr(R.string.rec_default_on),
            tr(R.string.rec_boot_body),
        )
        RecoveryCapability.AutoWirelessDebugging -> SwitchCopy(
            tr(R.string.rec_auto_wireless),
            tr(R.string.rec_default_on),
            tr(R.string.rec_auto_wireless_body),
        )
    }

    val NEED_SETUP: String get() = tr(R.string.rec_need_setup)
    val FAILED: String get() = tr(R.string.rec_failed)
    val WORKING: String get() = tr(R.string.rec_working)

    /** スイッチが切り替わった後の一連のフィードバック。成功したら言うことはあまりありません。 */
    fun result(r: PermissionResult): String? = when (r) {
        PermissionResult.Done -> null
        PermissionResult.NoRunner -> NEED_SETUP
        is PermissionResult.Failed -> FAILED
    }

    /** が機能するには、ペアリングされたワイヤレス デバッグが必要です。USB 起動コマンドのみを使用してアシスタントを起動する場合は、これを使用できません。 */
    private val NEEDS_PAIRING = setOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)

    /**
     * 既定でオンでも未適用の項目には説明を添える。スマホ操作が未設定なら、設定後に自動適用されることを案内する。
     * 自動適用が失敗した場合はその旨を表示し、適用済みやユーザーがオフにした場合は説明を省く。
     * USB 起動のみ（[HelperRestart.UsbCommand]）なら、無線で復帰する2項目は使えず、再起動後はコマンドの再実行が必要と案内する。
     * root 起動（[HelperRestart.Root]）でその2項目が未適用なら、不要であり再起動後は su 経由で復帰することを案内する。
     */
    fun pending(
        c: RecoveryCapability, consent: RecoveryConsent, paired: Boolean, autoFailed: Set<RecoveryCapability>,
        restart: HelperRestart = HelperRestart.None,
    ): String? = when {
        !consent.wants(c) -> null
        restart == HelperRestart.UsbCommand && c in NEEDS_PAIRING -> tr(R.string.rec_needs_pairing)
        restart == HelperRestart.Root && c in NEEDS_PAIRING && !consent.isApplied(c) -> tr(R.string.rec_root_not_needed)
        consent.isApplied(c) -> null
        !paired -> NEED_SETUP
        c in autoFailed -> FAILED
        else -> null
    }

    /** 「ユーザー補助を自動で開く」の下に、前回ユーザー補助サービスの自動再起動に成功した時刻を表示する; 成功していなければ表示せず、画面上部のヒントは従来どおり手動での対処法を示す。 */
    fun restarted(o: RepairOutcome?, zone: ZoneId = ZoneId.systemDefault()): String? =
        o?.takeIf { it.restarted }?.let { tr(R.string.rec_restarted_at, HH_MM.format(Instant.ofEpochMilli(it.atMillis).atZone(zone))) }

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

    /** 「ワイヤレスデバッグを自動で開く」の下に、自動で開けずに一旦止まった理由を表示する; 止まっていなければ余計なことは言わない。 */
    fun autoOnBlocked(b: AutoOnBlock?): String? = when (b) {
        null -> null
        AutoOnBlock.NotAllowedHere -> tr(R.string.rec_not_allowed_here)
        AutoOnBlock.Refused -> tr(R.string.rec_refused)
        AutoOnBlock.GaveUp -> tr(R.string.rec_gave_up)
    }

    /** 以下の「再起動後の自動回復」、最後の起動の結果。通常の回復については言うことはありません。 実際の状態では、 */
    fun lastRestore(o: RestoreOutcome?): String? = when (o) {
        null, RestoreOutcome.Restored -> null
        RestoreOutcome.PermissionMissing -> tr(R.string.rec_permission_missing)
        RestoreOutcome.SettingUnavailable -> tr(R.string.rec_setting_unavailable)
        RestoreOutcome.NotRecovered -> tr(R.string.rec_not_recovered)
    }
}

/** は複数のスイッチに接続されています。ペア接続を実行するには、[com.wangzan101.mika.settings.RecoverySettings] を選択します。 */
@Composable
fun RecoverySwitches() {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val settings = phone.recoverySettings
    val consent by settings.consent.collectAsState()
    val shell by phone.shellControl.status.collectAsState()
    val autoFailed by phone.recovery.permissions.autoFailed.collectAsState()
    val lastRestore by settings.lastRestore.collectAsState()
    val repaired by phone.recovery.accessibilityRepair.last.collectAsState()
    val autoOnBlocked by phone.shellManager.wirelessAutoOn.blocked.collectAsState()
    var busy by remember { mutableStateOf<RecoveryCapability?>(null) }
    var message by remember { mutableStateOf<Pair<RecoveryCapability, String>?>(null) }
    val notes = RecoveryCapability.entries.mapNotNull { cap ->
        val m = message
        (if (m?.first == cap) m.second else RecoveryCopy.pending(
            cap, consent, shell == BuiltinShellStatus.Running || phone.shellControl.paired, autoFailed, phone.shellControl.restart,
        ))
            ?.let { cap to it }
    }.toMap()
    RecoverySection(
        consent = consent, busy = busy, notes = notes, lastRestore = lastRestore, restartNote = RecoveryCopy.restarted(repaired),
        autoOnNote = RecoveryCopy.autoOnBlocked(autoOnBlocked),
        onToggle = { cap, on ->
            busy = cap
            message = null
            // はページに従っていません: ユーザーが設定ページを離れるとき、選択内容がシステムと一致する前にコマンドを完了する必要があります。
            phone.pairing.scope.launch {
                val r = phone.recovery.permissions.set(cap, on)
                message = RecoveryCopy.result(r)?.let { cap to it }
                busy = null
            }
        },
    )
}

/**
 * 簡単なテストのためのステータスのないいくつかのスイッチ。スイッチは、ユーザーがこの項目を必要とするかどうかを示します (個人的にオフにせずにオンにします)。切り替えて、確認せずに直接実行します。
 * [notes] は、特定の項目の下にあるプロンプトの行です (切り替えの結果、最初に設定を完了する必要があり、自動で開くことができませんでした)。
 * [restartNote] は、「アクセシビリティをオンにしてください」の下の説明の行です (アクセシビリティ サービスは前回自動的に再起動されました)。
 * [autoOnNote] は、「ワイヤレス デバッグを自動的にオンにする」の下の説明の行です (開くことに成功しなかったため、最初に停止しました)。
 */
@Composable
fun RecoverySection(
    consent: RecoveryConsent,
    busy: RecoveryCapability?,
    notes: Map<RecoveryCapability, String>,
    lastRestore: RestoreOutcome?,
    onToggle: (RecoveryCapability, Boolean) -> Unit,
    restartNote: String? = null,
    autoOnNote: String? = null,
) {
    GroupCard {
        RecoveryCapability.entries.forEachIndexed { i, cap ->
            val copy = RecoveryCopy.switch(cap)
            val on = consent.wants(cap)
            val icon = iconOf(cap)
            ListRow(icon, copy.title, if (busy == cap) RecoveryCopy.WORKING else copy.subtitle, first = i == 0) {
                MikaSwitch(on, { want -> onToggle(cap, want) }, Modifier.testTag("recovery-${cap.name}"), enabled = busy == null && cap !in consent.suspended)
            }
            Note(copy.explain, MikaTheme.colors.ink3)
            notes[cap]?.let { Note(it, MikaTheme.colors.bad) }
            if (cap == RecoveryCapability.Accessibility) restartNote?.let { Note(it, MikaTheme.colors.ink3) }
            if (cap == RecoveryCapability.RestoreAfterReboot && consent.isApplied(cap)) {
                RecoveryCopy.lastRestore(lastRestore)?.let { Note(it, MikaTheme.colors.warn) }
            }
            if (cap == RecoveryCapability.AutoWirelessDebugging && consent.wants(cap)) autoOnNote?.let { Note(it, MikaTheme.colors.warn) }
        }
    }
}

private fun iconOf(c: RecoveryCapability): ImageVector = when (c) {
    RecoveryCapability.Accessibility -> MikaIcons.Hand
    RecoveryCapability.BatteryWhitelist -> MikaIcons.Sun
    RecoveryCapability.RestoreAfterReboot -> MikaIcons.Wrench
    RecoveryCapability.AutoWirelessDebugging -> MikaIcons.Refresh
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MikaType.Hint, color = color)
}
