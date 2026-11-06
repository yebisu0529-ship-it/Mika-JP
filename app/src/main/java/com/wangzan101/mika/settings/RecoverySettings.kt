package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.A11yLoss
import com.wangzan101.mika.core.shell.A11yLossRecord
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RecoveryConsentStore
import com.wangzan101.mika.core.shell.RestoreOutcome
import com.wangzan101.mika.core.shell.PaymentPauseRecord
import com.wangzan101.mika.core.shell.PaymentPauseStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「携帯電話操作」のいくつかの機能の記録と、最後の起動からの自動回復の結果。ブート ブロードキャストと設定ページは同じプロセスで表示されます。
 *
 * には、項目ごとに 2 つのブール値があります。元のキー (`accessibility` など) は「有効」としてマークされ、意味は以前と同じです (以前は、コマンドが成功した後にのみ true が書き込まれました)。
 * `<キー>_declined` 「ユーザーが個人的にオフにした」ことを思い出してください。古いバージョンには前のキーしかありません。false のキーは、それが触れられていないのか、オフになっているのかわかりません。
 * は常にタッチされていないものとして扱われます。新しいキーが存在しない場合はオフになっていないため、アップグレード時に追加の移行を実行する必要はありません。これらの項目は、接続が利用可能な場合、デフォルトで開きます。
 */
class RecoverySettings(private val prefs: SharedPreferences) : RecoveryConsentStore, PaymentPauseStore {
    private val _consent = MutableStateFlow(read())
    override val consent: StateFlow<RecoveryConsent> = _consent.asStateFlow()

    private val _paymentPause = MutableStateFlow(readPaymentPause())
    override val paymentPause: StateFlow<PaymentPauseRecord?> = _paymentPause.asStateFlow()

    @Synchronized override fun savePaymentPause(record: PaymentPauseRecord?) {
        check(prefs.edit().putBoolean(PAYMENT_PAUSED, record != null)
            .putBoolean(PAYMENT_RESTORE_A11Y, record?.restoreAccessibility == true).commit())
        // 自動復旧を先に抑止してから、画面操作や通知へ停止を通知する。
        _consent.value = read()
        _paymentPause.value = record
    }

    private fun readPaymentPause() = if (prefs.getBoolean(PAYMENT_PAUSED, false))
        PaymentPauseRecord(prefs.getBoolean(PAYMENT_RESTORE_A11Y, false)) else null

    private val _lastRestore = MutableStateFlow(readOutcome())
    val lastRestore: StateFlow<RestoreOutcome?> = _lastRestore.asStateFlow()

    private val _lastA11yLoss = MutableStateFlow(readLoss())
    /** 最後に Mega を起動したとき、アクセシビリティ サービスが削除されているか、バインドされていないことがわかりました (診断情報のため)。 */
    val lastA11yLoss: StateFlow<A11yLossRecord?> = _lastA11yLoss.asStateFlow()

    /** ミカのアクセシビリティ サービスが接続されました。後で起動するときに接続されない場合のみ問題となり、セットアップされていない場合は考慮されません。 */
    var accessibilitySeen: Boolean
        get() = prefs.getBoolean(A11Y_SEEN, false)
        set(v) { if (v != accessibilitySeen) prefs.edit().putBoolean(A11Y_SEEN, v).apply() }

    fun recordA11yLoss(record: A11yLossRecord) {
        prefs.edit().putString(A11Y_LOSS, record.loss.name).putLong(A11Y_LOSS_AT, record.atMs).apply()
        _lastA11yLoss.value = record
    }

    private fun readLoss(): A11yLossRecord? {
        val loss = prefs.getString(A11Y_LOSS, null)?.let { n -> A11yLoss.entries.firstOrNull { it.name == n } } ?: return null
        return A11yLossRecord(loss, prefs.getLong(A11Y_LOSS_AT, 0))
    }

    @Synchronized override fun save(consent: RecoveryConsent) {
        val restore = RecoveryCapability.RestoreAfterReboot
        val restoreChanged = consent.isApplied(restore) != _consent.value.isApplied(restore)
        prefs.edit().apply {
            for ((c, key) in KEYS) {
                putBoolean(key, consent.isApplied(c))
                putBoolean(key + DECLINED, c in consent.declined)
            }
            if (restoreChanged) remove(LAST_RESTORE)
        }.commit()
        _consent.value = read()
        // 再起動またはシャットダウン後、古い起動結果は問題を示さなくなりました。
        if (restoreChanged) _lastRestore.value = null
    }

    fun recordRestore(outcome: RestoreOutcome) {
        prefs.edit().putString(LAST_RESTORE, outcome.name).commit()
        _lastRestore.value = outcome
    }

    private fun read() = RecoveryConsent(
        applied = KEYS.filter { (_, key) -> prefs.getBoolean(key, false) }.keys,
        declined = KEYS.filter { (_, key) -> prefs.getBoolean(key + DECLINED, false) }.keys,
        suspended = if (prefs.getBoolean(PAYMENT_PAUSED, false)) setOf(RecoveryCapability.Accessibility) else emptySet(),
    )

    private fun readOutcome(): RestoreOutcome? =
        prefs.getString(LAST_RESTORE, null)?.let { n -> RestoreOutcome.entries.firstOrNull { it.name == n } }

    companion object {
        private const val PREFS = "builtin_shell_recovery"
        private val KEYS = mapOf(
            RecoveryCapability.Accessibility to "accessibility",
            RecoveryCapability.BatteryWhitelist to "battery_whitelist",
            RecoveryCapability.RestoreAfterReboot to "restore_after_reboot",
            RecoveryCapability.AutoWirelessDebugging to "auto_wireless_debugging",
        )
        private const val DECLINED = "_declined"
        private const val LAST_RESTORE = "last_restore"
        private const val A11Y_SEEN = "a11y_seen"
        private const val A11Y_LOSS = "a11y_loss"
        private const val A11Y_LOSS_AT = "a11y_loss_at"
        private const val PAYMENT_PAUSED = "payment_paused"
        private const val PAYMENT_RESTORE_A11Y = "payment_restore_accessibility"

        /** 製品の読み取りおよび書き込み用の設定ファイル ([com.wangzan101.mika.cap.phone.PhoneServices] によって作成)。 */
        fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
