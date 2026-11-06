package com.wangzan101.mika.payment

import android.app.KeyguardManager
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.PaymentPauseStatus
import com.wangzan101.mika.settings.RecoverySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 接続中の本人の端末向け。通常の実機テストでは実行せず、明示引数で選択する。 */
class PaymentPauseDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp

    private fun selected() {
        assumeTrue("Explicit payment-pause verification is required",
            InstrumentationRegistry.getArguments().getString("mikaPaymentVerification") == "true")
    }

    private fun unlocked() {
        assumeTrue("Unlock Galaxy before testing the resume notification",
            !app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(12_000L) {
        while (!condition()) delay(100)
    }

    private suspend fun press(label: Int) {
        val expected = app.getString(label)
        val manager = app.getSystemService(NotificationManager::class.java)
        await { manager.activeNotifications.any { n -> n.id == PaymentPauseNotifications.ID &&
            n.notification.actions?.any { it.title.toString() == expected } == true } }
        val notification = manager.activeNotifications.single { it.id == PaymentPauseNotifications.ID }.notification
        notification.actions.single { it.title.toString() == expected }.actionIntent.send()
    }

    @Test fun configureUsbRecoveryWithoutAdditionalSecureSettingsPermission() = runBlocking {
        selected()
        assumeTrue("USB-only setup must be explicitly selected",
            InstrumentationRegistry.getArguments().getString("mikaPrepareUsbOnly") == "true")
        assertFalse(app.phone.shellControl.paired)
        for (cap in listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)) {
            assertEquals(PermissionResult.Done, app.phone.recovery.permissions.set(cap, false))
        }
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"))
    }

    @Test fun notificationActionsStopAndResumeTheActualAccessibilityService() = runBlocking {
        selected()
        unlocked()
        await { app.phone.recovery.accessibility.value.let { it.enabled && it.connected } }
        app.paymentNotifications.refresh()
        press(R.string.payment_pause)
        await { app.paymentPause.status.value == PaymentPauseStatus.Paused }
        assertFalse(app.phone.recovery.accessibility.value.enabled)
        assertFalse(app.phone.recovery.accessibility.value.connected)
        delay(4_000L)
        assertFalse("Automatic repair must not undo payment pause", app.phone.recovery.accessibility.value.enabled)
        val persisted = RecoverySettings(RecoverySettings.prefs(app))
        assertNotNull(persisted.paymentPause.value)
        assertFalse(persisted.consent.value.wants(RecoveryCapability.Accessibility))
        press(R.string.payment_resume)
        await { app.paymentPause.status.value == PaymentPauseStatus.Active }
        assertTrue(app.phone.recovery.accessibility.value.enabled)
        assertTrue(app.phone.recovery.accessibility.value.connected)
        assertNull(app.phone.recoverySettings.paymentPause.value)
    }

    @Test fun pauseBeforeProcessRestart() = runBlocking {
        selected()
        app.paymentNotifications.refresh()
        press(R.string.payment_pause)
        await { app.paymentPause.status.value == PaymentPauseStatus.Paused }
        assertNotNull(app.phone.recoverySettings.paymentPause.value)
        assertFalse(app.phone.recovery.accessibility.value.enabled)
    }

    @Test fun resumeAfterProcessRestart() = runBlocking {
        selected()
        unlocked()
        assertNotNull(app.phone.recoverySettings.paymentPause.value)
        await { app.paymentPause.status.value == PaymentPauseStatus.Paused }
        delay(4_000L)
        assertFalse(app.phone.recovery.accessibility.value.enabled)
        press(R.string.payment_resume)
        await { app.paymentPause.status.value == PaymentPauseStatus.Active }
        assertTrue(app.phone.recovery.accessibility.value.enabled)
        assertTrue(app.phone.recovery.accessibility.value.connected)
    }
}
