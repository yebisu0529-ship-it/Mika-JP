package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.shell.PaymentPauseRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * AgentLoop は、画面を変更するすべてのツールの前に [pass] を呼び出します。順番に確認してください:
 * 1. モバイル操作アシスタントが利用できない → 一時停止し、再開するまで待ち、ユーザーが「続行」をクリックします。
 * 2. タスクがホーム画面に移動され (ユーザーがホーム画面でアプリを開いた) → 一時停止され、ユーザーは仮想画面に戻す前に「続行」をクリックします。
 *    ミカはユーザーが使い終わったかどうかを判断せず、ユーザーの目の前でアプリを取り上げることを避けます。
 * 3. 画面が消えるかロックされる → 定時タスクは本人の設定に従って起動・解除を試し、ほかのタスクは解除を待ちます。
 * 最初の 2 つは、このステップで携帯電話が使用されている場合にのみチェックされます (電話は true)。Web ページおよびサンドボックス上のステップはアシスタントを必要とせず、ホーム画面には移動されません。
 * また、ブラウザページでタスクが貸し出されているページ([onUserOnPage])をタッチ→どの画面を使用しても最初に停止し、「続行」をクリックして続行します。
 * インターフェースに触れないステップ（モデル推論、画面読み取り）はここを経由せず、通常通りに進みます。
 * 支払いのための一時停止は、これらの処理より先に確認する。
 */
class TaskGate(
    private val locked: StateFlow<Boolean>,
    private val shellAvailable: StateFlow<Boolean>,
    private val paymentPause: StateFlow<PaymentPauseRecord?> = MutableStateFlow(null),
    /** 操作直前にロック状態を再確認し、消灯通知の遅延中も操作を通さない。 */
    private val refreshLocked: (() -> Unit)? = null,
    /** 無人実行中のロック時に呼び出し、戻る前にシステムの状態から [locked] を更新する。 */
    private val unattendedUnlock: (suspend () -> Unit)? = null,
    private val moveBack: suspend () -> Boolean,
) {
    private val movedAway = MutableStateFlow(false)
    private val userOnPage = MutableStateFlow(false)
    private val resumes = MutableStateFlow(0)
    /** が移動されたことが判明した時点のクリック数。エージェントがまだゲートに到達していない場合でも、その後の「続行」クリックはすべてカウントされます。 */
    @Volatile private var resumesAtMove = 0

    fun onTaskMovedAway() {
        if (!movedAway.value) resumesAtMove = resumes.value
        movedAway.value = true
    }

    /** は移動されたことが判明しましたが、元に戻されていません。これに応じて、タスク サービスは、次のツールによる画面の変更を待たずに、直ちに一時停止されます。 */
    val movedAwayPending: Boolean get() = movedAway.value

    /**
     * ユーザーがブラウザ ページでタスクに割り当てられたページをタッチしました (ユーザーは操作していましたが、ミカは同時に移動できませんでした): 画面を変更する次のツールの前に停止しました。
     * ユーザーが「続行」をクリックするまで待ちます。タスク サービスはそれに応じて一時停止をすぐに表示します。
     */
    fun onUserOnPage() { userOnPage.value = true }

    /** ユーザーは貸出ページを操作していますが、「続行」をクリックしていません。 */
    val userOnPagePending: Boolean get() = userOnPage.value

    /** ユーザーは「続行」をクリックしました。これは、クリック後に開始された一時停止または進行中の一時停止にのみ有効です。ユーザーがページ上で操作した一時停止はその場で解除されます。 */
    fun resume() {
        resumes.update { it + 1 }
        userOnPage.value = false
    }

    suspend fun pass(phone: () -> Boolean = { true }, setWaiting: (TaskState?) -> Unit) {
        while (true) {
            refreshLocked?.invoke()
            val onPhone = phone()
            when {
                paymentPause.value != null -> {
                    setWaiting(TaskState.Held(GateReason("payment_paused", tr(R.string.payment_paused_body))))
                    paymentPause.first { it == null }
                }
                userOnPage.value -> {
                    setWaiting(TaskState.Held(paused(USER_ON_PAGE)))
                    userOnPage.first { !it }
                }
                onPhone && !shellAvailable.value -> {
                    pauseUntilResume(SHELL_DOWN, setWaiting)
                }
                onPhone && movedAway.value -> {
                    pauseUntilResume(MOVED_AWAY, setWaiting, seen = resumesAtMove)
                    while (shellAvailable.value && !moveBack()) pauseUntilResume(MOVE_BACK_FAILED, setWaiting)
                    if (shellAvailable.value) movedAway.value = false
                }
                locked.value -> {
                    setWaiting(TaskState.Held(LOCKED))
                    if (unattendedUnlock == null) {
                        locked.first { !it }
                    } else {
                        unattendedUnlock.invoke()
                        // 无人值守时不能无限等用户来解锁；只认刷新后的系统状态，不认流程自称成功。
                        if (locked.value) throw UnattendedLockException(tr(R.string.trigger_unlock_failed))
                    }
                }
                else -> {
                    setWaiting(null)
                    return
                }
            }
        }
    }

    /** では、ユーザーが携帯電話で 1 つのステップを手動で処理する必要があります。処理後、「続行」をクリックします（インターフェイスは「処理してほしい」としてカウントされます）。 */
    suspend fun pauseForUser(why: String, setWaiting: (TaskState?) -> Unit) {
        val seen = resumes.value
        setWaiting(TaskState.Held(GateReason("paused", why, resumable = true, needsYou = true)))
        resumes.first { it != seen }
    }

    /** [seen]: このクリック数の後の「続行」がカウントされます。デフォルト値は現在値です（最初にそれを書き留めてから一時停止を表示し、表示直後にクリックすることもカウントされます）。 */
    private suspend fun pauseUntilResume(why: String, setWaiting: (TaskState?) -> Unit, seen: Int = resumes.value) {
        setWaiting(TaskState.Held(paused(why)))
        resumes.first { it != seen }
    }

    companion object {
        /** 画面をロックするか画面をオフにする: ロック解除後、自動的に続行します (実行記録に wait_unlock として記録されます)。 */
        val LOCKED = GateReason("waiting_unlock")

        /** は一時停止し、ユーザーは続行する前に「続行」をクリックします (実行中のレコードには理由とともに一時停止として記録されます)。 */
        fun paused(why: String) = GateReason("paused", why, resumable = true)

        /** ユーザーが電話機上で手を動かすまで待機し (たとえば、システム選択ボックスで手を選択する)、移動後に自動的に続行します (実行レコードに wait_user として記録されます)。 */
        fun userStep(why: String) = GateReason("waiting_user", why, needsYou = true)

        /** システムは、ユーザー画面で [app] のどれを開くかをユーザーに選択するよう求めます。名前が読めない場合は名前を言いません。 */
        fun pickCopy(app: String?) = app?.let { tr(R.string.gate_pick_app, it) } ?: tr(R.string.gate_pick_system)

        /** 選択済み [app] ユーザーが手で扱う必要がある場合の文。 */
        fun finishCopy(app: String?) = app?.let { tr(R.string.gate_finish_app, it) } ?: tr(R.string.gate_finish_unknown)

        val MOVED_AWAY: String get() = tr(R.string.gate_moved_away)
        val MOVE_BACK_FAILED: String get() = tr(R.string.gate_move_back_failed)
        val SHELL_DOWN: String get() = tr(R.string.gate_shell_down)
        /** タスクに貸し出されたページを手動で操作するときに、ユーザーがブラウザ ページで一時停止する理由 (インターフェイスはこの一時停止を認識します)。 */
        val USER_ON_PAGE: String get() = tr(R.string.gate_user_on_page)
    }
}

/** 无人值守任务无法通过系统锁屏；由任务服务转成有结果通知的失败终态。 */
internal class UnattendedLockException(override val message: String) : Exception(message)
