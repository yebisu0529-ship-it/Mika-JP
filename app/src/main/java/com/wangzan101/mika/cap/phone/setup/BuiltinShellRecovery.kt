package com.wangzan101.mika.cap.phone.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.shell.A11yLossRecord
import com.wangzan101.mika.core.shell.A11yObservation
import com.wangzan101.mika.core.shell.A11yStartCheck
import com.wangzan101.mika.core.shell.AccessibilityList
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.shell.BootRestore
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryInputs
import com.wangzan101.mika.core.shell.RecoveryPermissions
import com.wangzan101.mika.core.shell.RecoveryPolicy
import com.wangzan101.mika.core.shell.RestoreOutcome
import com.wangzan101.mika.core.shell.ShellCommandRunner
import com.wangzan101.mika.settings.RecoverySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * には、アシスタントの自動回復機能といくつかの機能が組み込まれています。 [runner] を使用してシェル コマンドを実行します (アシスタントまたはペア接続経由。どちらも使用できない場合は指定されません)。
 * の機能とブート結果は [settings] に記録されます。 [control] は組み込みアシスタントそのものです。作成者: [com.wangzan101.mika.cap.phone.PhoneServices],
 * アプリを起動すると、[start] から接続が開始されます。
 */
class BuiltinShellRecovery(
    context: Context,
    private val runner: ShellCommandRunner,
    private val settings: RecoverySettings,
    private val control: BuiltinShellControl,
) {
    private val app = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val accessibilityChange = Mutex()

    val permissions: RecoveryPermissions by lazy {
        // アシスタントが実行中 (起動方法に関係なく) またはペアリングされている場合、コマンドは使用可能になります。
        RecoveryPermissions({ runner.takeIf { control.status.value == BuiltinShellStatus.Running || control.paired } }, settings, app.packageName)
    }

    /**
     * アクセシビリティ サービスは、実行時に自動的にオンになりません。結果 ([AccessibilityRepair.last]) は、設定ページに何かを言うことになります。
     * アプリ システム設定を変更する権限 (「再起動後の自動回復」によって付与) がある場合は、アシスタントを待たずに直接書き込むことができます。アシスタントを経由する必要はありません。 手順は、
     */
    val accessibilityRepair = AccessibilityRepair(
        restart = { gap -> accessibilityChange.withLock {
            if (RecoveryCapability.Accessibility in settings.consent.value.suspended) PermissionResult.Done
            else if (canWriteSecure()) restartDirectly(gap) else permissions.restartAccessibility(gap)
        } },
        consent = { settings.consent.value },
        direct = ::canWriteSecure,
    )

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** と [RecoveryPermissions.restartAccessibility] の場合と同じですが、アプリが独自にシステム設定を書き込む点が異なります。利用できない場合は追加します。バインドされていない場合は、削除してから再度追加します。 */
    private suspend fun restartDirectly(gapMs: Long): PermissionResult = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun write(list: String) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
        try {
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                write(AccessibilityList.without(current, me))
                withContext(NonCancellable) {
                    delay(gapMs)
                    if (RecoveryCapability.Accessibility in settings.consent.value.suspended) return@withContext
                    // 追加が失敗すると、Mika がリストから消えるのを防ぐために、元のリスト (メイン スイッチとともに) が書き戻されます。今回も追加の失敗が報告されています。
                    try {
                        write(AccessibilityList.with(read(), me))
                    } catch (e: Exception) {
                        runCatching { write(current.orEmpty()) }
                        throw e
                    }
                }
            } else {
                write(AccessibilityList.with(current, me))
            }
            PermissionResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 支払い通知からの操作。修復中の書き戻しが終わってから変更し、実際の接続状態も確認する。 */
    suspend fun setAccessibilityForPayment(on: Boolean): Boolean = accessibilityChange.withLock {
        if (on && accessibility.value.let { it.enabled && it.connected }) return@withLock true
        val result = if (canWriteSecure()) withContext(Dispatchers.IO) {
            runCatching {
                val cr = app.contentResolver
                val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                val me = PermissionCommands.accessibilityService(app.packageName)
                val next = if (on) AccessibilityList.with(current, me) else AccessibilityList.without(current, me)
                check(Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.ifEmpty { null }))
                check(Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (next.isEmpty()) 0 else 1))
                PermissionResult.Done
            }.getOrElse { PermissionResult.Failed(it.javaClass.simpleName) }
        } else permissions.setAccessibilityForPayment(on)
        // 自分のサービスを止めるだけなら、ヘルパーや追加の権限がなくても実行できる。
        if (!on) withContext(Dispatchers.Main.immediate) {
            ScreenAccessibility.instance.value?.disableSelf()
        }
        if (on && result != PermissionResult.Done) return@withLock false
        val confirmed = withTimeoutOrNull(2_500L) {
            accessibility.first { if (on) it.enabled && it.connected else !it.enabled && !it.connected }
        } != null
        if (confirmed) settings.save(settings.consent.value.withApplied(RecoveryCapability.Accessibility, on))
        confirmed
    }

    /**
     * アシスタントが実行されるたびに (ペアリング直後、切断後の再起動、アプリの起動時にすでにペアリングされており、起動後に接続が自動的に復元されます)、デフォルトでオンになります。
     * ユーザーがオフにしていないため、まだ有効になっていないものがいくつか追加されました。これらの道路は最終的にすべて同じ状態になるため、ここでのみ確認し、どこでも個別に調整することはありません。
     * 同時に、アクセシビリティ サービスにも注目してください。サービスが実行されておらず、システム設定を書き込む方法がある場合 (アプリ自体に承認があるか、アシスタントが実行されている場合)、自動的にオンになります。
     */
    @Synchronized
    fun stop() = scope.cancel()

    fun start() {
        if (started) return
        started = true
        scope.launch { permissions.applyWhenRunning(control.status) }
        val service = accessibility
        // 起動を見てください。強制的に停止された後、システムはサービスをリストから削除するか、リストには含まれていてもバインドされていません。診断情報として書き留めます
        val initial = service.value
        val seen = settings.accessibilitySeen
        scope.launch {
            val connected = withTimeoutOrNull(A11yStartCheck.CONNECT_WAIT_MS) { service.first { it.connected } } != null
            A11yStartCheck.loss(initial, connected, seen)?.let {
                settings.recordA11yLoss(A11yLossRecord(it, System.currentTimeMillis()))
            }
        }
        scope.launch { service.collect { if (it.connected) settings.accessibilitySeen = true } }
        scope.launch { accessibilityRepair.watch(service, control.status, userUnlocked) }
    }

    /**
     * 起動後にユーザーがロックを解除しましたか ([UserManager.isUserUnlocked]): ロックが解除されていない場合、アクセシビリティ サービスをバインドできず、自動修復は機能しません。
     * [Intent.ACTION_USER_UNLOCKED]受信時に再度読み込み、自動修復して判定させます。プロセス全体の 1 つのコピー。
     */
    private val userUnlocked: StateFlow<Boolean> by lazy {
        fun read() = runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() != false
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { trySend(read()) }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            trySend(read()) // 登録前後の変更点
            awaitClose { runCatching { app.unregisterReceiver(receiver) } }
        }.stateIn(scope, SharingStarted.Eagerly, read())
    }

    /** システム リストにミカがあるかどうか (システム設定の変更後)、およびサービス インスタンスが接続されているかどうか (接続と切断の後)。プロセス全体の 1 つのコピー。 */
    val accessibility: StateFlow<A11yObservation> by lazy { serviceObservations() }

    private fun serviceObservations(): StateFlow<A11yObservation> {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun enabled() = AccessibilityList.contains(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
        val listChanges = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            send(Unit)
            awaitClose { cr.unregisterContentObserver(observer) }
        }
        return combine(listChanges, ScreenAccessibility.instance) { _, service -> A11yObservation(enabled(), service != null) }
            .stateIn(scope, SharingStarted.Eagerly, A11yObservation(enabled(), ScreenAccessibility.instance.value != null))
    }

    /** ペアリングの解除: まず、接続がまだ存在している間に認証を取り消してから、ペアリングを削除します。 */
    suspend fun unpair() {
        val a11yApplied = settings.consent.value.isApplied(RecoveryCapability.Accessibility)
        val result = permissions.revokeAll()
        // 接続が利用できなくなった場合、ユーザー補助サービスは少なくとも単独でオフにすることができます。バッテリーのホワイトリストとシステム設定を変更する許可は、接続によってのみ取り消すことができます。
        if (result != PermissionResult.Done && a11yApplied) {
            runCatching { ScreenAccessibility.instance.value?.disableSelf() }
        }
        control.unpair()
    }

    /** をオンにした後、[再起動後の自動回復] を押して、ワイヤレス デバッグを再開し、アシスタントを再起動します。アクションが必要ない場合は null を返します。 */
    suspend fun restoreAfterBoot(): RestoreOutcome? {
        // 未ペア設定の USB 起動では Mika 自身がヘルパーを再起動できないため、ワイヤレスデバッグを変更しない。
        // root 起動ではウォッチドッグが su を使うため、ワイヤレスデバッグを変更しない。
        if (!control.paired || control.rootStart) return null
        val holds = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        when (BootRestore.gate(Build.VERSION.SDK_INT, settings.consent.value, holds)) {
            BootRestore.Gate.Skip -> return null
            BootRestore.Gate.PermissionMissing -> return RestoreOutcome.PermissionMissing.also(settings::recordRestore)
            BootRestore.Gate.Act -> Unit
        }
        val cr = app.contentResolver
        // 機能の検出: 書き込みが拒否される (例外がスローされる/false が返される)、または書き込みと読み取りが 1 ではないため、この電話機はサポートされていないと想定されます。
        val written = DeveloperSettings.turnOnWirelessDebugging(cr) && DeveloperSettings.wirelessDebugging(cr)
        if (!written) return RestoreOutcome.SettingUnavailable.also(settings::recordRestore)

        val outcome = withTimeoutOrNull(BOOT_BUDGET_MS) {
            RecoveryPolicy.recover(
                inputs = {
                    RecoveryInputs(
                        paired = control.paired,
                        // が信頼できる Wi-Fi に接続されていない場合、システムはスイッチを元に戻します。各ラウンドを再読み込みし、元に戻して停止します。
                        wirelessDebugging = DeveloperSettings.wirelessDebugging(cr),
                        status = control.status.value,
                    )
                },
                attempt = { control.retry() },
            )
        }
        val result = if (outcome?.recovered == true) RestoreOutcome.Restored else RestoreOutcome.NotRecovered
        return result.also(settings::recordRestore)
    }

    private companion object {
        /** は起動後約 1 分間 goAsync をブロードキャストします。余白を残してください。 */
        const val BOOT_BUDGET_MS = 50_000L
    }
}
