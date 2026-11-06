package com.wangzan101.mika.payment

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.MikaApp

/** 通知の明示的な操作だけを受け付ける非公開の受信先。 */
class PaymentPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != PaymentPauseNotifications.ACTION_PAUSE && action != PaymentPauseNotifications.ACTION_RESUME) return
        if (action == PaymentPauseNotifications.ACTION_RESUME &&
            context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val pending = goAsync()
        (context.applicationContext as MikaApp).paymentNotifications.request(action, pending::finish)
    }
}
