package com.wangzan101.mika.core.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 再開時に戻すユーザー補助の状態。存在している間は自動復旧を抑止する。 */
data class PaymentPauseRecord(val restoreAccessibility: Boolean)

interface PaymentPauseStore {
    val paymentPause: StateFlow<PaymentPauseRecord?>
    fun savePaymentPause(record: PaymentPauseRecord?)
}

enum class PaymentPauseStatus { Active, Pausing, Paused, Resuming, PauseFailed, ResumeFailed }

/** 通知の連打とプロセス再起動を含め、明示的な再開だけが一時停止を解除する。 */
class PaymentPauseController(
    private val store: PaymentPauseStore,
    private val shouldRestoreAccessibility: () -> Boolean,
    private val disableAccessibility: suspend () -> Boolean,
    private val enableAccessibility: suspend () -> Boolean,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(if (store.paymentPause.value == null) PaymentPauseStatus.Active else PaymentPauseStatus.Pausing)
    val status: StateFlow<PaymentPauseStatus> = _status.asStateFlow()

    suspend fun pause() = lock.withLock { pauseLocked() }

    /** 起動時の再確認。明示的な再開が先に完了していたら、新たに停止しない。 */
    suspend fun reconcile() = lock.withLock {
        if (store.paymentPause.value != null) pauseLocked()
    }

    private suspend fun pauseLocked() {
        try {
            _status.value = PaymentPauseStatus.Pausing
            if (store.paymentPause.value == null) store.savePaymentPause(PaymentPauseRecord(shouldRestoreAccessibility()))
            _status.value = if (disableAccessibility()) PaymentPauseStatus.Paused else PaymentPauseStatus.PauseFailed
        } catch (e: CancellationException) {
            _status.value = PaymentPauseStatus.PauseFailed
            throw e
        } catch (_: Exception) {
            _status.value = PaymentPauseStatus.PauseFailed
        }
    }

    suspend fun resume() = lock.withLock {
        val record = store.paymentPause.value ?: return@withLock
        _status.value = PaymentPauseStatus.Resuming
        try {
            val restored = if (record.restoreAccessibility) enableAccessibility() else disableAccessibility()
            if (!restored) {
                // 途中まで有効化された場合も、停止を維持して再試行を待つ。
                _status.value = if (disableAccessibility()) PaymentPauseStatus.ResumeFailed else PaymentPauseStatus.PauseFailed
                return@withLock
            }
            store.savePaymentPause(null)
            _status.value = PaymentPauseStatus.Active
        } catch (e: CancellationException) {
            val stopped = withContext(NonCancellable) { withTimeoutOrNull(1_500L) { disableAccessibility() } == true }
            _status.value = if (stopped) PaymentPauseStatus.ResumeFailed else PaymentPauseStatus.PauseFailed
            throw e
        } catch (_: Exception) {
            val stopped = runCatching { disableAccessibility() }.getOrDefault(false)
            _status.value = if (stopped) PaymentPauseStatus.ResumeFailed else PaymentPauseStatus.PauseFailed
        }
    }
}
