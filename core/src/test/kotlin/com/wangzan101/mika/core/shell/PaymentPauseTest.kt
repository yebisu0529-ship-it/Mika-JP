package com.wangzan101.mika.core.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentPauseTest {
    private class Store : PaymentPauseStore {
        override val paymentPause = MutableStateFlow<PaymentPauseRecord?>(null)
        override fun savePaymentPause(record: PaymentPauseRecord?) { paymentPause.value = record }
    }

    @Test fun pauseIsSavedBeforeDisablingAndRepeatedPausePreservesTheOriginalState() = runTest {
        val store = Store()
        var enabled = true
        val controller = PaymentPauseController(store, { enabled }, {
            assertNotNull(store.paymentPause.value)
            enabled = false
            true
        }, { enabled = true; true })
        controller.pause()
        controller.pause()
        assertEquals(PaymentPauseRecord(true), store.paymentPause.value)
        assertEquals(PaymentPauseStatus.Paused, controller.status.value)
        controller.resume()
        assertTrue(enabled)
        assertNull(store.paymentPause.value)
    }

    @Test fun aNewControllerKeepsAPersistedPauseWithoutEnablingAccessibility() = runTest {
        val store = Store().apply { savePaymentPause(PaymentPauseRecord(true)) }
        var enables = 0
        val controller = PaymentPauseController(store, { false }, { true }, { enables++; true })
        controller.pause()
        assertEquals(0, enables)
        assertEquals(PaymentPauseRecord(true), store.paymentPause.value)
        assertEquals(PaymentPauseStatus.Paused, controller.status.value)
    }

    @Test fun failedResumeKeepsPauseAndDisablesAPartiallyEnabledService() = runTest {
        val store = Store()
        var disables = 0
        val controller = PaymentPauseController(store, { true }, { disables++; true }, { false })
        controller.pause()
        controller.resume()
        assertEquals(2, disables)
        assertNotNull(store.paymentPause.value)
        assertEquals(PaymentPauseStatus.ResumeFailed, controller.status.value)
    }

    @Test fun aQueuedResumeCannotUndoCompleteStop() = runTest {
        val store = Store()
        val allowDisableToFinish = CompletableDeferred<Unit>()
        var resumeAllowed = true
        var enables = 0
        val controller = PaymentPauseController(store, { true }, {
            allowDisableToFinish.await()
            true
        }, { enables++; true }, resumeAllowed = { resumeAllowed })

        launch { controller.pause() }
        runCurrent()
        launch { controller.resume() }
        runCurrent()
        resumeAllowed = false
        allowDisableToFinish.complete(Unit)
        runCurrent()

        assertEquals(0, enables)
        assertEquals(PaymentPauseRecord(true), store.paymentPause.value)
        assertEquals(PaymentPauseStatus.Paused, controller.status.value)

        resumeAllowed = true
        controller.resume()
        assertEquals(1, enables)
        assertNull(store.paymentPause.value)
    }

    @Test fun resumingDoesNotEnableAServiceThatWasAlreadyTurnedOff() = runTest {
        val store = Store()
        var enables = 0
        val controller = PaymentPauseController(store, { false }, { true }, { enables++; true })
        controller.pause()
        controller.resume()
        assertEquals(0, enables)
        assertNull(store.paymentPause.value)
        assertEquals(PaymentPauseStatus.Active, controller.status.value)
    }

    @Test fun rapidResumeWaitsForThePauseToFinish() = runTest {
        val store = Store()
        val finishDisable = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = PaymentPauseController(store, { true }, {
            events += "disable"
            finishDisable.await()
            true
        }, { events += "enable"; true })
        launch { controller.pause() }
        runCurrent()
        launch { controller.resume() }
        runCurrent()
        assertEquals(listOf("disable"), events)
        finishDisable.complete(Unit)
        runCurrent()
        assertEquals(listOf("disable", "enable"), events)
        assertNull(store.paymentPause.value)
    }

    @Test fun failedDisableNeverReportsThatPaymentIsReady() = runTest {
        val store = Store()
        val controller = PaymentPauseController(store, { true }, { false }, { true })
        controller.pause()
        assertEquals(PaymentPauseStatus.PauseFailed, controller.status.value)
        assertNotNull(store.paymentPause.value)
    }

    @Test fun lateStartupReconciliationCannotUndoAnExplicitResume() = runTest {
        val store = Store()
        var disables = 0
        val controller = PaymentPauseController(store, { true }, { disables++; true }, { true })
        controller.pause()
        controller.resume()
        controller.reconcile()
        assertEquals(1, disables)
        assertNull(store.paymentPause.value)
        assertEquals(PaymentPauseStatus.Active, controller.status.value)
    }

    @Test fun failedResumeCleanupDoesNotClaimThatAccessibilityIsStopped() = runTest {
        val store = Store()
        var canDisable = true
        val controller = PaymentPauseController(store, { true }, { canDisable }, { false })
        controller.pause()
        canDisable = false
        controller.resume()
        assertNotNull(store.paymentPause.value)
        assertEquals(PaymentPauseStatus.PauseFailed, controller.status.value)
    }
}
