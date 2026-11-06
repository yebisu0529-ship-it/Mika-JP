package com.wangzan101.mika.settings

import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RestoreOutcome
import com.wangzan101.mika.core.shell.PaymentPauseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySettingsTest {
    private val a11y = RecoveryCapability.Accessibility
    private val battery = RecoveryCapability.BatteryWhitelist
    private val restore = RecoveryCapability.RestoreAfterReboot

    @Test fun paymentPauseSurvivesRestartAndOtherSettingsWithoutChangingTheOriginalPreference() {
        val prefs = MemoryPrefs()
        val settings = RecoverySettings(prefs)
        settings.savePaymentPause(PaymentPauseRecord(true))
        settings.save(settings.consent.value.withApplied(battery, true))
        val restarted = RecoverySettings(prefs)
        assertEquals(PaymentPauseRecord(true), restarted.paymentPause.value)
        assertEquals(setOf(a11y), restarted.consent.value.suspended)
        assertTrue(a11y !in restarted.consent.value.declined)
        assertTrue(!restarted.consent.value.wants(a11y))
        restarted.savePaymentPause(null)
        assertTrue(restarted.consent.value.wants(a11y))
        assertTrue(restarted.consent.value.isApplied(battery))
    }

    @Test fun paymentPauseDoesNotEraseAnExistingAccessibilityOptOut() {
        val prefs = MemoryPrefs()
        val settings = RecoverySettings(prefs)
        settings.save(RecoveryConsent(declined = setOf(a11y)))
        settings.savePaymentPause(PaymentPauseRecord(false))
        RecoverySettings(prefs).savePaymentPause(null)
        assertTrue(!RecoverySettings(prefs).consent.value.wants(a11y))
    }

    @Test fun freshInstallWantsEverythingAndHasNothingApplied() {
        val c = RecoverySettings(MemoryPrefs()).consent.value
        assertEquals(RecoveryConsent(), c)
        assertEquals(RecoveryCapability.entries, c.pending)
    }

    @Test fun oldInstallWithoutChoiceKeysCountsAsUntouched() {
        // 古いバージョンでは、「有効」: false のみが書き込まれます。何も操作されていないのか、オフになっているのかわかりません。未加工のまま扱いとさせていただきます。接続が利用可能な場合、デフォルトで接続が開かれます。
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("accessibility", true).putBoolean("battery_whitelist", false)
            .putBoolean("restore_after_reboot", false).commit()
        val c = RecoverySettings(prefs).consent.value
        assertEquals(RecoveryConsent(applied = setOf(a11y)), c)
        assertEquals(listOf(battery, restore, RecoveryCapability.AutoWirelessDebugging), c.pending)
    }

    @Test fun appliedAndDeclinedSurviveARestart() {
        val prefs = MemoryPrefs()
        val saved = RecoveryConsent(applied = setOf(a11y, restore), declined = setOf(battery))
        RecoverySettings(prefs).save(saved)
        assertEquals(saved, RecoverySettings(prefs).consent.value)
        // がオフにしてから再度オンにしました: 「欲しい」に戻りました
        RecoverySettings(prefs).save(saved.chose(battery, true))
        assertTrue(RecoverySettings(prefs).consent.value.wants(battery))
    }

    @Test fun lastRestoreIsClearedOnlyWhenRestoreItselfChanges() {
        val prefs = MemoryPrefs()
        val s = RecoverySettings(prefs)
        s.save(RecoveryConsent(applied = setOf(restore)))
        s.recordRestore(RestoreOutcome.NotRecovered)
        // その他の項目が変更されました: ブート結果はまだ有効です
        s.save(RecoveryConsent(applied = setOf(restore), declined = setOf(battery)))
        assertEquals(RestoreOutcome.NotRecovered, RecoverySettings(prefs).lastRestore.value)
        s.save(RecoveryConsent(declined = setOf(battery)))
        assertNull(s.lastRestore.value)
        assertNull(RecoverySettings(prefs).lastRestore.value)
    }

    /** メモリ内の設定は、これらの項目の読み取りと書き込みにのみ十分です。 */
    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removed.forEach(values::remove)
                values.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
