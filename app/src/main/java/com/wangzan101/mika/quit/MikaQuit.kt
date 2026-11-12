package com.wangzan101.mika.quit

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.core.shell.PaymentPauseStatus
import com.wangzan101.mika.core.shell.RestoreWish
import com.wangzan101.mika.mcp.McpService
import com.wangzan101.mika.sandbox.SandboxService
import com.wangzan101.mika.settings.ChatGptSignInService
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 完全停止を明示したときだけ、ユーザー補助を外してMikaの処理を終了する。 */
internal object MikaQuit {
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    @Volatile private var stopping = false

    fun requested(context: Context): Boolean = FeatureFlags.get(context).quitRequested()
    fun blocksWork(context: Context): Boolean = stopping || requested(context)

    fun request(app: MikaApp) {
        if (blocksWork(app)) return
        stopping = true
        scope.launch {
            val paused = withTimeoutOrNull(8_000L) {
                app.paymentPause.pause()
                app.paymentPause.status.value == PaymentPauseStatus.Paused
            } == true
            if (!paused) {
                stopping = false
                toast(app, R.string.quit_accessibility_failed)
                return@launch
            }
            if (!FeatureFlags.get(app).setQuitRequested(true)) {
                stopping = false
                toast(app, R.string.quit_save_failed)
                return@launch
            }

            // 停止を保存してから入口を閉じる。終了中の通知操作でユーザー補助を戻さない。
            try {
                withTimeoutOrNull(1_500L) { app.stopRuntimeForQuit() }
                TriggerRuntime.suspendAlarms(app)
                RuntimePreparer.stop()
                app.chatGptSignIn.cancel()
                for (service in listOf(TaskService::class.java, McpService::class.java,
                    SandboxService::class.java, ChatGptSignInService::class.java)) {
                    app.stopService(Intent(app, service))
                }
                withTimeoutOrNull(1_000L) { app.taskState.busy.first { !it } }
            } catch (e: Exception) {
                Log.w(TAG, "Could not finish application cleanup", e)
            }

            try {
                stopHelper(app)
                val manager = app.getSystemService(ActivityManager::class.java)
                for (task in manager.appTasks) runCatching { task.finishAndRemoveTask() }
                app.getSystemService(NotificationManager::class.java).cancelAll()
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private suspend fun stopHelper(app: MikaApp) {
        val hub = app.phone.hub
        val shell = hub.shell.value ?: return
        withContext(Dispatchers.IO) {
            runCatching { shell.setRestoreWish(RestoreWish.NONE.flags) }
                .onFailure { Log.w(TAG, "Could not clear helper recovery request", it) }
            // destroyはヘルパー自身を終了するので、Binder切断も終了の結果になる。
            runCatching { shell.destroy() }
        }
        val ended = withTimeoutOrNull(1_500L) { hub.shell.first { it == null }; true } == true
        if (!ended) toast(app, R.string.quit_helper_failed)
    }

    /** ホーム画面から開いたときに起動する。ユーザー補助は既存の「Mikaを再開」で戻す。 */
    fun reopen(app: MikaApp, intent: Intent): Boolean {
        if (stopping) return false
        val flags = FeatureFlags.get(app)
        if (!flags.quitRequested()) return true
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return false
        if (!flags.setQuitRequested(false)) {
            toast(app, R.string.quit_save_failed)
            return false
        }
        app.startRuntime()
        return true
    }

    private fun toast(app: MikaApp, text: Int) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    private const val TAG = "MikaQuit"
}
