package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.payment.PaymentPauseCopy
import com.wangzan101.mika.payment.PaymentPauseNotifications
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaIcons

/** 通知を消した場合や通知が許可されていない場合の停止・再開。 */
@Composable
fun PaymentPauseControls() {
    val app = LocalContext.current.applicationContext as MikaApp
    val status by app.paymentPause.status.collectAsState()
    val record by app.phone.recoverySettings.paymentPause.collectAsState()
    val busy = status in PaymentPauseNotifications.BUSY
    val action = if (record == null) PaymentPauseNotifications.ACTION_PAUSE else PaymentPauseNotifications.ACTION_RESUME
    GroupCard {
        ListRow(MikaIcons.Hand, tr(if (record == null) R.string.payment_pause else R.string.payment_resume),
            subtitle = PaymentPauseCopy.body(status), first = true,
            onClick = if (busy) null else ({ app.paymentNotifications.request(action) }))
    }
}
