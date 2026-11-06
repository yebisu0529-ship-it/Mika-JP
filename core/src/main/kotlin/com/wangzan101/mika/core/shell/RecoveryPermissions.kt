package com.wangzan101.mika.core.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ペアリングされたワイヤレス デバッグ接続を使用して、シェルとしてコマンドを実行し、その出力を返します。接続が切断されると例外がスローされます。 */
fun interface ShellCommandRunner {
    suspend fun exec(command: String): String
}

/**
 * 携帯電話操作のいくつかの機能: デフォルトでオンになっています (接続が利用可能な場合はミカが補います)。設定でオフにすることができ、ペアリングを解除すると元に戻すことができます。
 * [RestoreAfterReboot] と [AutoWirelessDebugging] は、同じ認証を使用してシステム設定を変更します (どちらもワイヤレス デバッグを有効にするためにのみ使用されます)。
 * は、両方の項目をオフにした後に回復されました。
 */
enum class RecoveryCapability {
    Accessibility, BatteryWhitelist, RestoreAfterReboot,
    /** 内蔵アシスタントを再起動するには、ワイヤレス デバッグがオフになっているときに、ユーザーに対して内蔵アシスタントをオンにします ([WirelessDebuggingAutoOn] を参照)。 */
    AutoWirelessDebugging;

    /** には、システム設定を変更するための権限が必要です。 */
    val writesSecureSettings: Boolean get() = this == RestoreAfterReboot || this == AutoWirelessDebugging
}

/**
 * 各能力について 2 つのことが個別に記録されます。[applied] は、ミカがユーザーのためにシステム内で開いたもので、現時点では有効です。
 * [declined] はユーザー自身によってオフになり、その後自動的にはオンになりません。両側に何もない場合は「未処理」を意味し、デフォルトでオンになります。
 */
data class RecoveryConsent(
    val applied: Set<RecoveryCapability> = emptySet(),
    val declined: Set<RecoveryCapability> = emptySet(),
    /** 一時停止中の機能。元のユーザー設定とは分けて保持する。 */
    val suspended: Set<RecoveryCapability> = emptySet(),
) {
    /** ユーザーはこの項目を望んでいません。自分でオフにする必要はありません (スイッチに表示されているものです)。 */
    fun wants(c: RecoveryCapability): Boolean = c !in declined && c !in suspended

    fun isApplied(c: RecoveryCapability): Boolean = c in applied

    /** ユーザーが望んでいるものの、まだ有効になっていないものがいくつかあります。接続が利用可能になったらすぐに追加してください。 */
    val pending: List<RecoveryCapability> get() = RecoveryCapability.entries.filter { wants(it) && !isApplied(it) }

    fun chose(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(declined = if (on) declined - c else declined + c)

    fun withApplied(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(applied = if (on) applied + c else applied - c)
}

interface RecoveryConsentStore {
    val consent: StateFlow<RecoveryConsent>
    fun save(consent: RecoveryConsent)
}

sealed interface PermissionResult {
    data object Done : PermissionResult
    /** 携帯電話の動作がまだ設定されていないため、使用可能な接続がありません。 */
    data object NoRunner : PermissionResult
    data class Failed(val detail: String) : PermissionResult
}

/** これらの機能に対応したシェルコマンド(pm/cmd deviceidle/settingsの独自の記述方法)。 */
object PermissionCommands {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val READ_ACCESSIBILITY = "settings get secure enabled_accessibility_services"
    const val EXIT_MARK = "__mika_exit="

    fun accessibilityService(pkg: String): String {
        // パッケージ名 (applicationId) にはサフィックス (同時にインストールされたバージョン) が付いている場合がありますが、サービス クラスはまだ App 名前空間にあります。パッケージ名がクラス名のプレフィックスである場合は省略形を使用し、それ以外の場合は完全なクラス名を記述します。
        val service = "com.wangzan101.mika.a11y.MikaAccessibilityService"
        return "$pkg/${if (service.startsWith("$pkg.")) service.removePrefix(pkg) else service}"
    }
    fun grantWriteSecureSettings(pkg: String) = "pm grant $pkg $WRITE_SECURE_SETTINGS"
    fun revokeWriteSecureSettings(pkg: String) = "pm revoke $pkg $WRITE_SECURE_SETTINGS"
    fun whitelistAdd(pkg: String) = "cmd deviceidle whitelist +$pkg"
    fun whitelistRemove(pkg: String) = "cmd deviceidle whitelist -$pkg"

    /** は、アクセシビリティ サービスのリスト全体を書き戻します。空の場合は、空文字列が残らないようにこの項目を削除し、メインスイッチをオフにしてください。 */
    fun writeAccessibility(list: String): List<String> =
        if (list.isEmpty()) listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0")
        else listOf("settings put secure enabled_accessibility_services ${quote(list)}", "settings put secure accessibility_enabled 1")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** exec サービスは終了コードを取得できません。出力の最後にマーカー行を追加してください。 pm または cmd が失敗した場合、出力があるかどうかを確認するだけではなく、おそらく出力に文を書き込むだけでしょう。 */
    fun checked(command: String): String = "$command 2>&1; echo $EXIT_MARK\$?"

    fun exitCode(output: String): Int? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.toIntOrNull()
}

/** `enabled_accessibility_services` はコロンで区切られたコンポーネント名です。コンポーネント名の書き方には「パッケージ名/.クラス名」と「パッケージ名/完全なクラス名」の2通りがあります。 */
object AccessibilityList {
    fun entries(raw: String?): List<String> {
        val v = raw?.trim().orEmpty()
        if (v == "null") return emptyList() // `settings get` 未書き込み項目の場合は null を出力
        return v.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun contains(raw: String?, component: String): Boolean = entries(raw).any { same(it, component) }

    fun with(raw: String?, component: String): String {
        val list = entries(raw)
        return (if (list.any { same(it, component) }) list else list + component).joinToString(":")
    }

    fun without(raw: String?, component: String): String = entries(raw).filterNot { same(it, component) }.joinToString(":")

    private fun same(a: String, b: String) = expand(a) == expand(b)

    private fun expand(c: String): String {
        if ('/' !in c) return c
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else c
    }
}

/**
 * は、ユーザーの選択に従ってこれらの機能を開くか取り消します。システム設定を変更するには、ペアになった接続 (シェル ID) に依存する必要があります。 [runner] が null の場合、使用可能な接続がないことを意味します。
 * ユーザーの選択 ([RecoveryConsent.declined]) とそれがシステムで有効かどうか ([RecoveryConsent.applied]) は個別に記録されます。
 * が選択範囲を開いて接続がない場合は、まずそれを書き留めて、[applyPending] がそれを補うまで待ちます。コマンドが失敗しても、両方とも同じままで、インターフェイスは忠実に表示されます。
 * 複数のエントリが連続して実行され、自動入力とユーザーによるスイッチのダイヤルは重複しません。
 */
class RecoveryPermissions(
    private val runner: () -> ShellCommandRunner?,
    private val store: RecoveryConsentStore,
    private val packageName: String,
) {
    private val lock = Mutex()
    private val _autoFailed = MutableStateFlow<Set<RecoveryCapability>>(emptySet())

    /** 自動的に追加されたときに正常に追加されなかったいくつかの項目 (ユーザーの邪魔にならないように、設定ページの項目の下で 1 文を言うだけです)。成功したら削除してください。 */
    val autoFailed: StateFlow<Set<RecoveryCapability>> = _autoFailed.asStateFlow()

    /** ユーザーがスイッチをダイヤルします。スイッチをオンにし、確認せずに直接実行します。電源を切ってすぐに元に戻し、「ユーザーが電源を切ったこと」を書き留めてください。 */
    suspend fun set(capability: RecoveryCapability, on: Boolean): PermissionResult = lock.withLock {
        val now = store.consent.value
        if (on && capability in now.suspended) return@withLock PermissionResult.Failed("Capability is temporarily suspended")
        val shell = runner()
        if (shell == null) {
            return@withLock when {
                // 最初に書き留めておくと、接続が利用可能になると自動的に追加されます。インターフェイスでは、最初に設定を完了するように求められます。
                on -> PermissionResult.NoRunner.also { store.save(now.chose(capability, true)) }
                // は発効していないため、撤回する必要はありません。
                !now.isApplied(capability) -> PermissionResult.Done.also { store.save(now.chose(capability, false)) }
                else -> PermissionResult.NoRunner
            }
        }
        val result = run(shell, listOf(capability), on)
        if (result == PermissionResult.Done) {
            store.save(store.consent.value.chose(capability, on).withApplied(capability, on))
            _autoFailed.update { it - capability }
        }
        result
    }

    /**
     * 接続が利用可能になったばかりのときに調整します。ユーザーがオフにしていない、まだ有効になっていないいくつかの項目を開きます。何もプレイしないでください。失敗は [autoFailed] として記録されます。
     * 次回接続が利用可能になったときに再試行してください。接続がなければ何もしません。
     */
    suspend fun applyPending(): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        var failure: PermissionResult? = null
        for (c in store.consent.value.pending) {
            if (!store.consent.value.wants(c)) continue
            val r = run(shell, listOf(c), on = true)
            if (r == PermissionResult.Done) store.save(store.consent.value.withApplied(c, true))
            else if (failure == null) failure = r
            _autoFailed.update { if (r == PermissionResult.Done) it - c else it + c }
        }
        failure ?: PermissionResult.Done
    }

    /** 組み込みアシスタントが実行されるたびに (接続が利用可能になったばかりのとき)、[applyPending] のステータスを監視し続けます。 */
    suspend fun applyWhenRunning(status: Flow<BuiltinShellStatus>) {
        status.collect { if (it == BuiltinShellStatus.Running) applyPending() }
    }

    /**
     * ペアリングを解除するときは、次のコマンドを使用します。ミカがここで開いたすべてのアイテムと、システム設定を変更する権限を取り戻します (記録に関係なく一度受け取ってください。存在しない権限を受け取っても問題はありません)。
     * ユーザーがシステムに設定したアクセシビリティまたはホワイトリストはここでは提供されず、移動されません。 「有効」は成功または失敗に関係なくクリアされます。一致がなくなり、これらの能力は無効になります。
     * のユーザーの選択は残ります。ペアリング中にオフにされなかった項目は引き続き自動的にオンになり、手動でオフにされた項目は引き続きオフになります。
     */
    suspend fun revokeAll(): PermissionResult = lock.withLock {
        val now = store.consent.value
        // システム設定を変更するための 2 つの権限が共有されます。それらを一度収集するだけで十分です。残っているのは RestoreAfterReboot だけです。
        val granted = now.applied.filterNot { it.writesSecureSettings }.toSet() + RecoveryCapability.RestoreAfterReboot
        store.save(now.copy(applied = emptySet()))
        _autoFailed.value = emptySet()
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        run(shell, granted.toList(), on = false)
    }

    /**
     * は、他のサービスを変更せずに、Mika のアクセシビリティ サービスを再度実行します (このサービスは実行時に使用されません。[AccessibilityRepair] を参照)。
     * リストにミカがない場合は、直接追加してください。バインドされていてもバインドされていない場合は、まずそれを削除し、[gapMs] がシステムによって処理されるのを待ってから、リストを再読み取りして追加し直します。
     * は、サービスが閉じられた状態のままにならないように、途中でキャンセルされた場合でも、削除後に必ず追加されます。追加が失敗すると、元のリストに書き戻され、引き続き失敗が報告されます。変更なし [RecoveryConsent]: これはオープンまたはリトラクトの能力ではありません。
     */
    suspend fun restartAccessibility(gapMs: Long): PermissionResult = lock.withLock {
        if (RecoveryCapability.Accessibility in store.consent.value.suspended) return@withLock PermissionResult.Done
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        val me = PermissionCommands.accessibilityService(packageName)
        try {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            // はリストにありません (強制停止されるとシステムによって削除されます)。直接追加します。リストにはありますが、バインドされていません。削除して再度追加すると、システムが再バインドします。
            if (!AccessibilityList.contains(current, me)) return@withLock runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me)))
            val removed = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.without(current, me)))
            if (removed != PermissionResult.Done) return@withLock removed
            withContext(NonCancellable) {
                delay(gapMs)
                if (RecoveryCapability.Accessibility in store.consent.value.suspended) return@withContext PermissionResult.Done
                val now = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
                val back = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(now, me)))
                // 追加が失敗した場合は、元のリスト (メイン スイッチとともに) を書き戻します。Mika をリストから消すよりは、「存在するがバインドされていない」状態に戻ります。
                // 書き戻しは単なる隠蔽であり、今回はまだ書き戻しの失敗を報告します
                // (改行してそのまま読んでください。すでにミカが含まれています。きれいなリストにソートするだけで、内容は変わりません)
                if (back != PermissionResult.Done) runCatching { runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me))) }
                back
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 支払いの停止・明示的な再開専用。自動復旧の元の設定は変更しない。 */
    suspend fun setAccessibilityForPayment(on: Boolean): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        val result = run(shell, listOf(RecoveryCapability.Accessibility), on)
        if (result == PermissionResult.Done) {
            store.save(store.consent.value.withApplied(RecoveryCapability.Accessibility, on))
        }
        result
    }

    /** 各項目は独立して実行され、1 つの項目が失敗しても後続の項目はブロックされません。最初の失敗が返されます。 */
    private suspend fun run(shell: ShellCommandRunner, capabilities: List<RecoveryCapability>, on: Boolean): PermissionResult {
        var failure: PermissionResult? = null
        for (c in capabilities) {
            val r = try {
                runAll(shell, commands(shell, c, on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (r != PermissionResult.Done && failure == null) failure = r
        }
        return failure ?: PermissionResult.Done
    }

    private suspend fun commands(shell: ShellCommandRunner, c: RecoveryCapability, on: Boolean): List<String> = when (c) {
        RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging -> when {
            on -> listOf(PermissionCommands.grantWriteSecureSettings(packageName))
            // この認証は別のアイテムに使用する必要があります。元に戻すのではなく、オフにすることを忘れないでください。
            RecoveryCapability.entries.any { it != c && it.writesSecureSettings && store.consent.value.isApplied(it) } -> emptyList()
            else -> listOf(PermissionCommands.revokeWriteSecureSettings(packageName))
        }
        RecoveryCapability.BatteryWhitelist ->
            listOf(if (on) PermissionCommands.whitelistAdd(packageName) else PermissionCommands.whitelistRemove(packageName))
        RecoveryCapability.Accessibility -> {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            val me = PermissionCommands.accessibilityService(packageName)
            PermissionCommands.writeAccessibility(if (on) AccessibilityList.with(current, me) else AccessibilityList.without(current, me))
        }
    }

    private suspend fun runAll(shell: ShellCommandRunner, commands: List<String>): PermissionResult {
        for (cmd in commands) {
            val out = shell.exec(PermissionCommands.checked(cmd))
            if (PermissionCommands.exitCode(out) != 0) return PermissionResult.Failed("$cmd: ${out.trim()}")
        }
        return PermissionResult.Done
    }
}
