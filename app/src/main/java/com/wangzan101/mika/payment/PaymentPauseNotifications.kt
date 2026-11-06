package com.wangzan101.mika.payment

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.shell.PaymentPauseStatus
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** サービス停止後も通知を残す。ボタンの PendingIntent が必要なときだけアプリを起動する。 */
class PaymentPauseNotifications(private val app: MikaApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val control get() = app.paymentPause
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    fun start() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.payment_channel), NotificationManager.IMPORTANCE_LOW))
        scope.launch { control.status.collect(::show) }
        // 再起動後も停止を維持する。再開は通知または設定画面の操作からだけ受け付ける。
        if (app.phone.recoverySettings.paymentPause.value != null) reconcile()
        scope.launch {
            app.phone.recovery.accessibility.collect { observed ->
                if (observed.enabled && app.phone.recoverySettings.paymentPause.value != null &&
                    control.status.value !in BUSY) reconcile()
            }
        }
    }

    private fun reconcile() {
        scope.launch {
            try {
                withTimeout(7_000L) { control.reconcile() }
            } catch (_: Exception) {
                Log.w("MikaPaymentPause", "Could not verify the saved payment pause")
            }
        }
    }

    fun refresh() = show(control.status.value)

    fun request(action: String, completed: () -> Unit = {}) {
        scope.launch {
            try {
                withTimeout(7_000L) {
                    when (action) {
                        ACTION_PAUSE -> control.pause()
                        ACTION_RESUME -> control.resume()
                    }
                }
            } catch (_: Exception) {
                // 失敗やタイムアウトでも保存済みの停止状態は解除しない。
                Log.w("MikaPaymentPause", "Payment pause action did not finish")
            } finally {
                completed()
            }
        }
    }

    private fun show(status: PaymentPauseStatus) {
        val b = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(if (status == PaymentPauseStatus.Active) R.string.app_name else R.string.payment_title))
            .setContentText(PaymentPauseCopy.body(status))
            .setStyle(Notification.BigTextStyle().bigText(PaymentPauseCopy.body(status)))
            .setContentIntent(TaskService.openApp(app))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (status !in BUSY) {
            if (status == PaymentPauseStatus.Active || status == PaymentPauseStatus.PauseFailed) {
                b.addAction(action(R.string.payment_pause, ACTION_PAUSE, 0))
            }
            if (app.phone.recoverySettings.paymentPause.value != null) {
                b.addAction(action(R.string.payment_resume, ACTION_RESUME, 1))
            }
        }
        try {
            manager.notify(ID, b.build())
        } catch (_: SecurityException) {
            // 通知が許可されていなくても設定画面の停止・再開は利用できる。
            Log.w("MikaPaymentPause", "Notifications are not permitted")
        }
    }

    private fun action(label: Int, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(app, PaymentPauseReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(app, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, tr(label), pending).apply {
            if (Build.VERSION.SDK_INT >= 31 && action == ACTION_RESUME) setAuthenticationRequired(true)
        }.build()
    }

    companion object {
        const val ACTION_PAUSE = "jp.studioyebisu.mika.payment.PAUSE"
        const val ACTION_RESUME = "jp.studioyebisu.mika.payment.RESUME"
        const val CHANNEL = "payment_pause"
        const val ID = 41_008
        val BUSY = setOf(PaymentPauseStatus.Pausing, PaymentPauseStatus.Resuming)
    }
}

internal object PaymentPauseCopy {
    fun body(status: PaymentPauseStatus): String = tr(when (status) {
        PaymentPauseStatus.Active -> R.string.payment_available_body
        PaymentPauseStatus.Pausing -> R.string.payment_pausing_body
        PaymentPauseStatus.Paused -> R.string.payment_paused_body
        PaymentPauseStatus.Resuming -> R.string.payment_resuming_body
        PaymentPauseStatus.PauseFailed -> R.string.payment_pause_failed
        PaymentPauseStatus.ResumeFailed -> R.string.payment_resume_failed
    })
}
