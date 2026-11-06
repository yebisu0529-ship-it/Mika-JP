package com.wangzan101.mika.task

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * デバイスのステータス: ホーム画面が操作不能かどうか (画面がオフまたはロックされているかどうか)、およびエージェントのアプリ タスクがユーザーによってホーム画面に移動されたかどうか。
 * 画面がオフになった後、システムは挿入されたタッチを破棄するため、画面がオフになっているときも画面は「ロック画面」として扱われます。
 */
class DeviceStateWatcher(
    context: Context,
    private val topTaskId: (displayId: Int) -> Int,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = LockState({ LockState.Reading(power.isInteractive, keyguard.isKeyguardLocked) }, scope)
    val locked: StateFlow<Boolean> = lock.locked

    // はブロードキャストを「もう一度見てみましょう」の信号としてのみ使用します。ステータス自体は毎回システムから読み取られ、ブロードキャスト シーケンスに依存せずに推測されます。
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lock.refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        lock.refresh() // 登録前後の変更点
    }

    /**
     * は 2 つの画面の一番上のタスクをポーリングします。[taskId] は仮想画面の一番上ではなく、メイン画面の一番上にあります。つまり、ユーザーによって移動されました。
     * は、「移動」されるたびに 1 回だけ送信され、元に戻された後に再度送信されます。クエリが失敗した場合 (シェルが切断された場合)、このラウンドはスキップされます。
     */
    fun taskMovedAway(displayId: Int, taskId: Int): Flow<Unit> = flow {
        var away = false
        while (true) {
            val now = runCatching {
                isMovedAway(topTaskId(displayId), topTaskId(Display.DEFAULT_DISPLAY), taskId)
            }.getOrNull()
            if (now != null) {
                if (now && !away) emit(Unit)
                away = now
            }
            delay(POLL_MS)
        }
    }

    /** 本进程刚完成唤醒/解锁后立刻重读，不等下一条系统广播。 */
    fun refresh() = lock.refresh()

    fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
        scope.cancel()
    }

    companion object {
        const val POLL_MS = 500L

        internal fun isMovedAway(agentTop: Int, mainTop: Int, taskId: Int) =
            taskId >= 0 && agentTop != taskId && mainTop == taskId
    }
}

/**
 * メイン画面が操作不能（画面オフまたはロック）になっていませんか？ブロードキャストが来て再び読み取られます。ただし、ロックを解除するとき、システムは最初に USER_PRESENT を送信します。
 * [android.app.KeyguardManager.isKeyguardLocked] ロック画面インターフェイスが実際に後退して変更されるまで待つ必要があります。この時点ではまだ「ロックされました」と表示されます。
 * 以降の放送はありません。したがって、「オンだがロックされている」を読み取るときは、誰かがこのステータスを見ている限り、[DeviceStateWatcher.POLL_MS] ごとにもう一度読み取ります。 ロックを解除するか画面をオフにするために読み込むまで、
 * 。画面がオフのときは読み込まず、SCREEN_ON になるまで待ちます。
 */
internal class LockState(private val read: () -> Reading, scope: CoroutineScope) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean) {
        val locked get() = !interactive || keyguardLocked
    }

    @Volatile private var last = read()
    private val _locked = MutableStateFlow(last.locked)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun refresh() {
        last = read()
        _locked.value = last.locked
    }

    init {
        scope.launch {
            _locked.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                while (watched) {
                    delay(DeviceStateWatcher.POLL_MS)
                    if (last.interactive && last.keyguardLocked) refresh()
                }
            }
        }
    }
}
