package com.wangzan101.mika.cap.phone.setup

import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.shell.A11yObservation
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RestoreWish
import com.wangzan101.mika.core.shell.RestoreWishSender
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.job

/**
 * Leave 「殺されたミカを引き上げますか？」 ([RestoreWish]) 内蔵アシスタントへ: カプセル モード、実行中のタスクがあるかどうか、「アクセシビリティをオンにしてください」、
 * システムリストにミカのアクセシビリティサービスはありますか?変更またはアシスタントが変更された場合は、[IMikaShell.setRestoreWish] を通じて再度送信してください。
 * アシスタントはミカの設定を読み取ることができません。ミカの死後、彼は最後に渡されたものに従ってのみ行動します。
 */
class RestoreWishSync(
    private val hub: HelperHub,
    private val capsuleMode: Flow<CapsuleMode>,
    private val taskRunning: Flow<Boolean>,
    private val consent: Flow<RecoveryConsent>,
    private val accessibility: Flow<A11yObservation>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun stop() = scope.coroutineContext.job.cancelAndJoin()
    private val sender = RestoreWishSender<IMikaShell> { shell, wish ->
        runCatching { shell.setRestoreWish(wish.flags) }
            .onFailure { Log.w(TAG, "cannot hand the restore wish to the helper", it) }
            .isSuccess
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        var lastOn: Boolean? = null
        // リストにないMikaのサービスは引き継がれるまでに時間がかかります。プロセスが強制的に停止されると、システムは最初にそのサービスを削除します。これによってあなたの希望を変更することはできません。
        // は彼に返信することはありませんでした (ユーザーがシステム設定で返信をオフにしました)
        val accessibilityOn = accessibility.map { it.enabled }.distinctUntilChanged().mapLatest { on ->
            if (!on && lastOn == true) delay(OFF_SETTLE_MS)
            lastOn = on
            on
        }
        val wishes = combine(capsuleMode, taskRunning, consent, accessibilityOn) { mode, busy, c, on ->
            RestoreWish.of(mode == CapsuleMode.Always, busy, c.wants(RecoveryCapability.Accessibility), on)
        }.distinctUntilChanged()
        scope.launch { combine(hub.shell, wishes, ::Pair).collect { (shell, wish) -> sender.update(shell, wish) } }
    }

    private companion object {
        const val TAG = "MikaRestoreWish"
        const val OFF_SETTLE_MS = 3_000L
    }
}
