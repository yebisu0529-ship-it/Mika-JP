package com.wangzan101.mika.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskService

/**
 * コンピューター接続のオープン中のフォアグラウンド サービス: バックグラウンドでプロセスを存続させ、ポートを常に開いたままにするためだけです。受信したタスクは引き続き [TaskService] に渡されて実行されます。
 * タスクサービスはタスクが存在しない場合は自動的に終了するため、監視は配置されません。永続的な通知でコンピュータ接続を直接オフにすることができます (両方のタイプがオフになります)。
 * は、システムによってリサイクルされた後、再構築されます (START_STICKY)。スイッチがオフの場合はその場で退出してください。
 *
 * コンピューターは、USB デバッグを通じて USB をオンまたはオフにするときにもこのサービスを使用します。このサービスはリスト内で外部に公開されていますが、呼び出し元は次の権限を持っている必要があります。
 * INTERACT_ACROSS_USERS_FULL。シェルにはこの権限がありますが、通常のアプリはそれを取得できません（システム署名とインストーラーにのみ与えられ、adb はそれを付与できません）。
 * なので、`adb shell am start-foreground-service` のようなシェルとして開始されたものだけがそれを利用できます。ミカは自分でそれを買う余裕があります。
 * フォアグラウンド サービス方式から起動する: Android 12 以降、バックグラウンドで実行されるアプリは通常、単独でフォアグラウンド サービスを開くことができませんが、シェルによって開始される起動はこれに限定されません。
 */
class McpService : Service() {
    private val runtime get() = (application as MikaApp).mcp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        val applied = !com.wangzan101.mika.quit.MikaQuit.blocksWork(this) && runCatching { McpModes.apply(intent?.action, runtime.settings, runtime.usb, runtime.relaySettings) }
            .onFailure { Log.w("MikaMcp", "could not update computer access settings", it) }.isSuccess
        // startForegroundService の後は、終了しようとしている場合でも、できるだけ早くフォアグラウンドに入る必要があります。プロセスがクラッシュするのを防ぐために、システムがフォアグラウンドに入ることができない場合に終了します。
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w("MikaMcp", "computer access service could not enter the foreground", it) }.isSuccess
        val ready = foreground && applied && runCatching { runtime.sync() }
            .onFailure { Log.w("MikaMcp", "could not start computer access", it) }.getOrDefault(false)
        if (!ready) {
            runtime.stopServer()
            runtime.usb.unpublish()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.stopServer()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val port = runtime.settings.port
        val body = when {
            runtime.relaySettings.config.value.enabled -> R.string.relay_notif_body
            runtime.settings.enabled.value && runtime.usb.enabled.value -> R.string.mcp_notif_body_both
            runtime.usb.enabled.value -> R.string.mcp_notif_body_usb
            else -> R.string.mcp_notif_body
        }
        val open = TaskService.openApp(this)
        val off = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(McpModes.ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.mcp_notif_title))
            .setContentText(tr(body, port))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr(R.string.mcp_turn_off), off).build())
            .build()
    }

    companion object {
        /** はタスク通知 (スロット番号、結果番号セクション) と交互に表示されます。 */
        private const val NOTIFICATION_ID = 30_001
    }
}
