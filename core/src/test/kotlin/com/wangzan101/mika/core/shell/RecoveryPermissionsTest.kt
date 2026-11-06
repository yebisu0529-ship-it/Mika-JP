package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPermissionsTest {
    // パッケージ名は呼び出し元によって指定され、ハードコーディングされません。
    private val pkg = "org.example.app"
    private val mika = PermissionCommands.accessibilityService(pkg)
    private val other = "com.example.reader/.ReaderService"

    private class Store(initial: RecoveryConsent = RecoveryConsent()) : RecoveryConsentStore {
        override val consent = MutableStateFlow(initial)
        override fun save(consent: RecoveryConsent) { this.consent.value = consent }
    }

    /** 偽の実行者: 実行されたコマンドを記録します (終了コードのパッケージを削除します)。[a11y] はシステム内のアクセシビリティ リストです。 [failAt] は、失敗したコマンド (1 から数えます) です。 */
    private class FakeShell(var a11y: String? = null, val failOn: String? = null, val failAt: Int? = null) : ShellCommandRunner {
        private val PUT_LIST = "settings put secure enabled_accessibility_services "
        private val DELETE_LIST = "settings delete secure enabled_accessibility_services"
        val ran = mutableListOf<String>()
        override suspend fun exec(command: String): String {
            if (command == PermissionCommands.READ_ACCESSIBILITY) return (a11y ?: "null") + "\n"
            val bare = command.substringBefore(" 2>&1;")
            ran += bare
            val code = if (failOn != null && bare.contains(failOn) || ran.size == failAt) 255 else 0
            // 書き込みに成功したリストはそのまま残り、後で読み込むと新しいリストになります。
            if (code == 0 && bare.startsWith(PUT_LIST)) a11y = bare.removePrefix(PUT_LIST).trim('\'')
            if (code == 0 && bare == DELETE_LIST) a11y = null
            return (if (code == 0) "" else "Exception occurred\n") + PermissionCommands.EXIT_MARK + code + "\n"
        }
    }

    @Test fun paymentPauseBlocksManualEnableAndAutomaticRepair() = runTest {
        val store = Store(RecoveryConsent(suspended = setOf(RecoveryCapability.Accessibility)))
        val shell = FakeShell(a11y = other)
        val permissions = RecoveryPermissions({ shell }, store, pkg)
        assertTrue(permissions.set(RecoveryCapability.Accessibility, true) is PermissionResult.Failed)
        permissions.restartAccessibility(0)
        assertEquals(other, shell.a11y)
        assertTrue(shell.ran.isEmpty())
    }

    @Test fun aPauseDuringRepairPreventsTheServiceFromBeingAddedBack() = runTest {
        val store = Store()
        val shell = FakeShell(a11y = "$other:$mika")
        val permissions = RecoveryPermissions({ shell }, store, pkg)
        launch { permissions.restartAccessibility(1_000) }
        runCurrent()
        store.save(store.consent.value.copy(suspended = setOf(RecoveryCapability.Accessibility)))
        testScheduler.advanceUntilIdle()
        assertEquals(other, shell.a11y)
    }

    @Test fun paymentStopAndExplicitResumePreserveOtherServicesAndTheOriginalChoice() = runTest {
        val original = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility),
            suspended = setOf(RecoveryCapability.Accessibility))
        val store = Store(original)
        val shell = FakeShell(a11y = "$other:$mika")
        val permissions = RecoveryPermissions({ shell }, store, pkg)
        assertEquals(PermissionResult.Done, permissions.setAccessibilityForPayment(false))
        assertEquals(other, shell.a11y)
        assertEquals(original.declined, store.consent.value.declined)
        assertEquals(PermissionResult.Done, permissions.setAccessibilityForPayment(true))
        assertEquals("$other:$mika", shell.a11y)
        assertEquals(original.declined, store.consent.value.declined)
        assertEquals(original.suspended, store.consent.value.suspended)
    }

    @Test
    fun accessibilityComponentKeepsTheClassNamespaceWithAnApplicationIdSuffix() {
        assertEquals("com.wangzan101.mika/.a11y.MikaAccessibilityService", PermissionCommands.accessibilityService("com.wangzan101.mika"))
        assertEquals(
            "com.wangzan101.mika.compat/com.wangzan101.mika.a11y.MikaAccessibilityService",
            PermissionCommands.accessibilityService("com.wangzan101.mika.compat"),
        )
    }

    @Test
    fun commandsAreTheExpectedShellCommands() {
        assertEquals("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS", PermissionCommands.grantWriteSecureSettings(pkg))
        assertEquals("pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS", PermissionCommands.revokeWriteSecureSettings(pkg))
        assertEquals("cmd deviceidle whitelist +$pkg", PermissionCommands.whitelistAdd(pkg))
        assertEquals("cmd deviceidle whitelist -$pkg", PermissionCommands.whitelistRemove(pkg))
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$other:$mika'", "settings put secure accessibility_enabled 1"),
            PermissionCommands.writeAccessibility("$other:$mika"),
        )
        // リストは空です: この項目を削除し、空の文字列を残さずにメイン スイッチをオフにしてください
        assertEquals(
            listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0"),
            PermissionCommands.writeAccessibility(""),
        )
    }

    @Test
    fun quotingSurvivesOddCharacters() {
        assertEquals("'a\$b'", PermissionCommands.quote("a\$b"))
        assertEquals("'it'\\''s'", PermissionCommands.quote("it's"))
    }

    @Test
    fun exitCodeParsing() {
        assertEquals(0, PermissionCommands.exitCode("Added: x\n${PermissionCommands.EXIT_MARK}0\n"))
        assertEquals(255, PermissionCommands.exitCode("boom\n${PermissionCommands.EXIT_MARK}255"))
        assertEquals(null, PermissionCommands.exitCode("no marker at all"))
        assertTrue(PermissionCommands.checked("pm grant x y").endsWith("2>&1; echo ${PermissionCommands.EXIT_MARK}\$?"))
    }

    private val all = RecoveryCapability.entries.toSet()
    private val a11y = RecoveryCapability.Accessibility
    private val battery = RecoveryCapability.BatteryWhitelist
    private val restore = RecoveryCapability.RestoreAfterReboot
    private val autoOn = RecoveryCapability.AutoWirelessDebugging

    @Test
    fun turningOnRunsItsCommandsAndRecordsIt() = runTest {
        val shell = FakeShell(a11y = other)
        val store = Store(RecoveryConsent(declined = all))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.set(a11y, true))
        assertEquals(PermissionCommands.writeAccessibility("$other:$mika"), shell.ran)
        assertEquals(PermissionResult.Done, p.set(battery, true))
        assertEquals(PermissionResult.Done, p.set(restore, true))
        assertEquals(RecoveryConsent(applied = setOf(a11y, battery, restore), declined = setOf(autoOn)), store.consent.value)
        assertTrue(PermissionCommands.whitelistAdd(pkg) in shell.ran)
        assertTrue(PermissionCommands.grantWriteSecureSettings(pkg) in shell.ran)
    }

    @Test
    fun switchOffRevertsOnlyThatOneThingAndRemembersTheChoice() = runTest {
        val shell = FakeShell(a11y = "$other:$mika")
        val store = Store(RecoveryConsent(applied = setOf(a11y, battery, restore), declined = setOf(autoOn)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.set(battery, false))
        assertEquals(listOf(PermissionCommands.whitelistRemove(pkg)), shell.ran)
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore), declined = setOf(battery, autoOn)), store.consent.value)

        shell.ran.clear()
        p.set(restore, false)
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)

        shell.ran.clear()
        p.set(a11y, false)
        assertEquals(PermissionCommands.writeAccessibility(other), shell.ran)
        assertEquals(RecoveryConsent(declined = all), store.consent.value)
    }

    @Test
    fun withoutRunnerChoicesAreRecordedButNothingRuns() = runTest {
        val store = Store(RecoveryConsent(declined = setOf(a11y)))
        val p = RecoveryPermissions({ null }, store, pkg)
        // 開く: 最初に要件を書き留め、最初に設定を完了するように求めるプロンプトを表示し、接続が利用可能になるまで待ちます。
        assertEquals(PermissionResult.NoRunner, p.set(a11y, true))
        assertTrue(store.consent.value.wants(a11y))
        assertEquals(listOf(a11y, battery, restore, autoOn), store.consent.value.pending)
        // 有効になっていない項目を閉じます。取り消すものは何もありません。書き留めるだけで完了します。
        assertEquals(PermissionResult.Done, p.set(battery, false))
        assertEquals(RecoveryConsent(declined = setOf(battery)), store.consent.value)
    }

    @Test
    fun withoutRunnerAnAppliedOneCannotBeTurnedOff() = runTest {
        val store = Store(RecoveryConsent(applied = setOf(battery)))
        assertEquals(PermissionResult.NoRunner, RecoveryPermissions({ null }, store, pkg).set(battery, false))
        assertEquals(RecoveryConsent(applied = setOf(battery)), store.consent.value)
    }

    @Test
    fun failedCommandKeepsTheOldState() = runTest {
        val store = Store(RecoveryConsent(declined = setOf(restore)))
        val p = RecoveryPermissions({ FakeShell(failOn = "pm grant") }, store, pkg)
        val r = p.set(restore, true)
        assertTrue(r is PermissionResult.Failed)
        assertEquals(RecoveryConsent(declined = setOf(restore)), store.consent.value)
    }

    @Test
    fun runnerThatThrowsIsAFailureNotACrash() = runTest {
        val store = Store()
        val p = RecoveryPermissions({ ShellCommandRunner { error("connection closed") } }, store, pkg)
        assertTrue(p.set(battery, true) is PermissionResult.Failed)
        assertEquals(RecoveryConsent(), store.consent.value)
    }

    @Test
    fun applyPendingTurnsOnUntouchedOnesOnly() = runTest {
        val shell = FakeShell(a11y = other)
        // バッテリーのホワイトリストがユーザーによってオフにされました。システム設定を変更するための 2 つの承認項目はすでに有効になっています。
        val store = Store(RecoveryConsent(applied = setOf(restore, autoOn), declined = setOf(battery)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.applyPending())
        assertEquals(PermissionCommands.writeAccessibility("$other:$mika"), shell.ran)
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore, autoOn), declined = setOf(battery)), store.consent.value)
        assertTrue(p.autoFailed.value.isEmpty())

        // が有効になりました: 再度実行されるものは何もありません
        shell.ran.clear()
        assertEquals(PermissionResult.Done, p.applyPending())
        assertTrue(shell.ran.isEmpty())
    }

    @Test
    fun applyPendingWithoutRunnerDoesNothing() = runTest {
        val store = Store()
        val p = RecoveryPermissions({ null }, store, pkg)
        assertEquals(PermissionResult.NoRunner, p.applyPending())
        assertEquals(RecoveryConsent(), store.consent.value)
        assertTrue(p.autoFailed.value.isEmpty())
    }

    @Test
    fun applyPendingRecordsFailuresQuietlyAndClearsThemLater() = runTest {
        var shell = FakeShell(failOn = "deviceidle")
        val store = Store()
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertTrue(p.applyPending() is PermissionResult.Failed)
        // 1 つの障害が他の障害をブロックしない
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore, autoOn)), store.consent.value)
        assertEquals(setOf(battery), p.autoFailed.value)

        shell = FakeShell()
        assertEquals(PermissionResult.Done, p.applyPending())
        assertEquals(listOf(PermissionCommands.whitelistAdd(pkg)), shell.ran)
        assertTrue(p.autoFailed.value.isEmpty())
    }

    @Test
    fun appliesEachTimeTheHelperBecomesRunning() = runTest {
        val shell = FakeShell()
        val status = MutableStateFlow<BuiltinShellStatus>(BuiltinShellStatus.NotPaired)
        val store = Store(RecoveryConsent(declined = setOf(a11y, restore, autoOn)))
        val p = RecoveryPermissions({ shell }, store, pkg)
        val job = launch { p.applyWhenRunning(status) }

        runCurrent()
        assertTrue(shell.ran.isEmpty())
        status.value = BuiltinShellStatus.Connecting
        runCurrent()
        assertTrue(shell.ran.isEmpty())

        status.value = BuiltinShellStatus.Running
        runCurrent()
        assertEquals(listOf(PermissionCommands.whitelistAdd(pkg)), shell.ran)

        // が切断され、再起動されました: この期間中に取得されました (ペアリング解除とペアリングなど)。もう一度修復してください。
        status.value = BuiltinShellStatus.Connecting
        runCurrent()
        store.save(store.consent.value.copy(applied = emptySet()))
        status.value = BuiltinShellStatus.Running
        runCurrent()
        assertEquals(listOf(PermissionCommands.whitelistAdd(pkg), PermissionCommands.whitelistAdd(pkg)), shell.ran)
        job.cancel()
    }

    @Test
    fun unpairRevokesEverythingThatWasApplied() = runTest {
        val shell = FakeShell(a11y = "$mika:$other")
        val store = Store(RecoveryConsent(applied = all))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.revokeAll())
        assertEquals(RecoveryConsent(), store.consent.value)
        assertTrue(PermissionCommands.revokeWriteSecureSettings(pkg) in shell.ran)
        assertTrue(PermissionCommands.whitelistRemove(pkg) in shell.ran)
        assertTrue(shell.ran.containsAll(PermissionCommands.writeAccessibility(other)))
    }

    @Test
    fun theTwoSwitchesThatTurnOnWirelessDebuggingShareOnePermission() = runTest {
        val shell = FakeShell()
        val store = Store(RecoveryConsent(applied = setOf(restore, autoOn)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        // 別の項目では、システム設定を変更するための承認が必要です。この項目をオフにして、メモするだけにして、元に戻さないようにしてください。
        assertEquals(PermissionResult.Done, p.set(restore, false))
        assertEquals(emptyList<String>(), shell.ran)
        assertEquals(RecoveryConsent(applied = setOf(autoOn), declined = setOf(restore)), store.consent.value)
        // 取り戻す前に両方のアイテムをオフにしてください
        assertEquals(PermissionResult.Done, p.set(autoOn, false))
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)
        assertEquals(RecoveryConsent(declined = setOf(restore, autoOn)), store.consent.value)
        // この承認はアイテムを開くために必要です
        shell.ran.clear()
        assertEquals(PermissionResult.Done, p.set(autoOn, true))
        assertEquals(listOf(PermissionCommands.grantWriteSecureSettings(pkg)), shell.ran)
    }

    @Test
    fun unpairRevokesTheSharedPermissionOnce() = runTest {
        val shell = FakeShell()
        val p = RecoveryPermissions({ shell }, Store(RecoveryConsent(applied = setOf(restore, autoOn))), pkg)
        assertEquals(PermissionResult.Done, p.revokeAll())
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)
    }

    @Test
    fun unpairLeavesAloneWhatMikaNeverTurnedOn() = runTest {
        // ユーザーが自分でバリアフリーシステムを開放し、バッテリーホワイトリストを追加しました。ここではそれは与えられません。ペアリング解除は行われません。
        val shell = FakeShell(a11y = "$mika:$other")
        val p = RecoveryPermissions({ shell }, Store(), pkg)
        p.revokeAll()
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)
    }

    @Test
    fun unpairThenRePairKeepsTheUsersChoices() = runTest {
        val shell = FakeShell(a11y = other)
        val store = Store(RecoveryConsent(applied = setOf(a11y, restore), declined = setOf(battery)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        p.revokeAll()
        // 取り消されるのは有効な状態であり、ユーザーの選択ではありません。オフにされていないものは「手動でオフ」にはなりません。
        assertEquals(RecoveryConsent(declined = setOf(battery)), store.consent.value)

        shell.ran.clear()
        shell.a11y = other
        assertEquals(PermissionResult.Done, p.applyPending())
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore, autoOn), declined = setOf(battery)), store.consent.value)
        assertFalse(PermissionCommands.whitelistAdd(pkg) in shell.ran)
    }

    @Test
    fun unpairClearsAppliedEvenWithoutRunnerOrOnFailure() = runTest {
        val store = Store(RecoveryConsent(applied = setOf(a11y, restore)))
        assertEquals(PermissionResult.NoRunner, RecoveryPermissions({ null }, store, pkg).revokeAll())
        assertEquals(RecoveryConsent(), store.consent.value)

        val store2 = Store(RecoveryConsent(applied = setOf(battery, restore)))
        val shell = FakeShell(failOn = "deviceidle")
        assertTrue(RecoveryPermissions({ shell }, store2, pkg).revokeAll() is PermissionResult.Failed)
        assertEquals(RecoveryConsent(), store2.consent.value)
        // 1 つの障害が他の障害をブロックしない
        assertTrue(PermissionCommands.revokeWriteSecureSettings(pkg) in shell.ran)
    }

    @Test
    fun consentHelpers() {
        // 何も記憶されません: デフォルトではすべてが必須ですが、まだ有効になっていません。
        val fresh = RecoveryConsent()
        assertTrue(RecoveryCapability.entries.all(fresh::wants))
        assertEquals(RecoveryCapability.entries, fresh.pending)

        val c = fresh.chose(battery, false).withApplied(a11y, true)
        assertFalse(c.wants(battery))
        assertTrue(c.isApplied(a11y))
        assertEquals(listOf(restore, autoOn), c.pending)
        assertEquals(fresh, c.chose(battery, true).withApplied(a11y, false))
    }

    @Test
    fun restartAccessibilityRemovesThenAddsBackKeepingOthers() = runTest {
        val shell = FakeShell(a11y = "$other:$mika:com.example.b/.B")
        val store = Store(RecoveryConsent(applied = setOf(a11y)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.restartAccessibility(gapMs = 1_000))
        assertEquals(
            PermissionCommands.writeAccessibility("$other:com.example.b/.B") +
                PermissionCommands.writeAccessibility("$other:com.example.b/.B:$mika"),
            shell.ran,
        )
        // は開くことも格納することもできません。レコードは変更されません。
        assertEquals(RecoveryConsent(applied = setOf(a11y)), store.consent.value)
    }

    @Test
    fun restartAccessibilityWhenMikaIsTheOnlyService() = runTest {
        val shell = FakeShell(a11y = mika)
        assertEquals(PermissionResult.Done, RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0))
        assertEquals(PermissionCommands.writeAccessibility("") + PermissionCommands.writeAccessibility(mika), shell.ran)
    }

    @Test
    fun restartAccessibilityAddsItBackWhenMissing() = runTest {
        val shell = FakeShell(a11y = other)
        assertEquals(PermissionResult.Done, RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0))
        assertEquals(PermissionCommands.writeAccessibility("$other:$mika"), shell.ran)
        assertEquals(PermissionResult.NoRunner, RecoveryPermissions({ null }, Store(), pkg).restartAccessibility(0))
    }

    @Test
    fun restartAccessibilityStopsWhenRemovalFails() = runTest {
        val shell = FakeShell(a11y = "$other:$mika", failOn = "enabled_accessibility_services")
        assertTrue(RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0) is PermissionResult.Failed)
        assertEquals(1, shell.ran.size)
    }

    @Test
    fun restartAccessibilityRestoresTheListWhenAddingBackFails() = runTest {
        // は正常に削除されました (項目 1 および 2) が、項目 3 を再度追加すると失敗しました。元のリストとメイン スイッチは書き戻されましたが、結果は依然として失敗しました。
        val shell = FakeShell(a11y = "$other:$mika", failAt = 3)
        assertTrue(RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0) is PermissionResult.Failed)
        assertEquals(
            PermissionCommands.writeAccessibility(other) + PermissionCommands.writeAccessibility("$other:$mika").first() +
                PermissionCommands.writeAccessibility("$other:$mika"),
            shell.ran,
        )
        assertEquals("$other:$mika", shell.a11y)
    }
}
