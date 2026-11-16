package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.util.Log
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * は、アプリの開始時、Mika のアップグレード後、リセット後、タスクが使用されるがまだ準備ができていないときに、バックグラウンドで実行環境を準備します。
 * はユーザーには表示されず、準備中に優先度の低い中立通知 ([SandboxForeground]) のみが表示されます。インターネットに接続されていません。
 */
object RuntimePreparer {
    private const val TAG = "RuntimePreparer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** このプロセスで準備が失敗しました: アプリは起動時に自動的に再試行されなくなり、タスクが再試行されるまでに時間がかかります ([ensure] の retryFailed)。 */
    @Volatile private var failed = false

    /** 準備中です。 nullではありません。 */
    val current: Job? get() = job?.takeIf { it.isActive }

    @Synchronized fun stop() { job?.cancel() }

    /** 準備が必要な場合は、この準備を開始して返します (すでに準備されている場合は、準備を返します)。 null を返す必要はありません。 */
    @Synchronized
    fun ensure(context: Context, retryFailed: Boolean = false): Job? {
        if (com.wangzan101.mika.quit.MikaQuit.blocksWork(context)) return null
        current?.let { return it }
        val app = context.applicationContext
        val state = SandboxState.get(app)
        if (!needsWork(state.status.value, state.installedVersion, BundledRuntime.VERSION, skipBroken = failed && !retryFailed)) return null
        val j = scope.launch {
            try {
                RootfsInstaller.create(app).install { _, _ -> }
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "runtime preparation failed", e)
            }
        }
        job = j
        // フォアグラウンド サービスは、準備中にプロセスがリサイクルされるのを防ぎ、中立的な通知を表示します。バックグラウンドでは、システムはフォアグラウンド サービスの開始を許可せず、プロセス内でのみフォアグラウンド サービスの準備を行います。
        try {
            SandboxForeground.start(app)
        } catch (e: Exception) {
            Log.i(TAG, "preparing without the foreground notification: $e")
        }
        return j
    }

    /** 準備する必要がありますか: インストールされていない、古いバージョンがインストールされている (Mika がアップグレードされている)、エラー ([skipBroken] を除く)。サポートを受けられない、または継続的に動けない状態。 */
    internal fun needsWork(status: SandboxStatus, installed: String?, bundled: String, skipBroken: Boolean): Boolean = when (status) {
        is SandboxStatus.Unsupported, is SandboxStatus.Installing -> false
        is SandboxStatus.Ready -> installed != bundled
        SandboxStatus.NotInstalled -> true
        is SandboxStatus.Broken -> !skipBroken
    }
}
