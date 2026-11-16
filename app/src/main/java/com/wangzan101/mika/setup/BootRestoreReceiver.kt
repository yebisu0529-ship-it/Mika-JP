package com.wangzan101.mika.setup

import com.wangzan101.mika.cap.phone.setup.BootRestoreHandler
import com.wangzan101.mika.task.TriggerRuntime
import android.content.Context
import android.content.Intent
import android.app.AlarmManager

/** ブート ブロードキャスト エントリ: フルネームがリストに登録されます。それは携帯電話の機能に実装されています。 */
class BootRestoreReceiver : BootRestoreHandler() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.wangzan101.mika.quit.MikaQuit.blocksWork(context)) return
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.rearm(context)
        // はタイム ゾーンを変更しました。デバイスのタイム ゾーンに従うローカル タイム プランは、新しいタイム ゾーンに従って直ちに再計算され (目覚まし時計は内部的に再配置されます)、他のタイム ゾーンは明示的に固定されます。
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.syncTimeZone(context)
        // は、起動時に LAN コンピュータ接続が開いている場合に監視を再開します (USB タイプは再起動しません。ブート番号を変更した後、ここでオフになり、最後の接続情報もクリアされます)。
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) (context.applicationContext as com.wangzan101.mika.MikaApp).mcp.ensureRunning()
    }
}
