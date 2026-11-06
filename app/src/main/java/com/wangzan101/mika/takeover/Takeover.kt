package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** は、使用されるデバイスの機能とエージェントのアクションを引き継ぎます。これは、現在のタスクに従ってタスク サービスによって実装されます。タスク番号が無効な場合は-1。 */
interface TakeoverPort {
    /** エージェント 仮想画面上の最上位のタスク。 */
    suspend fun agentTopTask(): Int
    /** launch_app によって開かれたタスクとパッケージ。 */
    suspend fun targetTask(): Int
    fun targetPackage(): String?
    suspend fun packageOf(taskId: Int): String?
    /** タスクが配置されている画面。タスクが存在しなくなった (タスクの終了後にページが閉じられた) は -1 です。 */
    suspend fun displayOf(taskId: Int): Int
    suspend fun moveToMain(taskId: Int): Boolean
    suspend fun moveBack(taskId: Int): Boolean
    /** アプリのサウンドをオンまたはオフにします。 */
    fun setSound(packageName: String, on: Boolean)
    /** 用户明确恢复声音;实现要连同本次任务的自动静音规则一起更新。 */
    fun unmute(packageName: String) = setSound(packageName, true)
    /** は、ホーム画面上のパッケージに対するユーザーの操作のカウントを開始します。アプリがユーザーに引き渡されるときに呼び出されます。 */
    fun watchInput(packageName: String)
    /** 現時点でカウントされている操作とページ。カウントしない場合は null。 */
    suspend fun handoverActivity(): HandoverActivity?
    fun stopWatching()
    /** テイクオーバー終了をエージェント ループに返します。 */
    suspend fun resume(result: HandBack)
    fun cancel()

    /**
     * 現在のインターフェイスが Web ページの場合、現在のラベル (同じページ) をホーム画面に取得してユーザーに与えます。成功した場合は true、失敗した場合は false。 Web インターフェイスが null になる代わりに、タスクを移動する古いパスに従います。
     * [key] は今回待機している番号で、Web ページ引き継ぎインターフェイスのボタンに付いています。
     */
    suspend fun showWeb(reason: String, key: Int): Boolean? = null

    /** は、Web ページのタグをバックグラウンドに戻し、テイクオーバー中に操作数とページ タイトルを返します。 [loggedIn] は、現在の Web サイトを「ユーザーがログイン中」として記録します。 */
    suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? = null

    /** ホーム画面の Web インターフェイスは現在占有されています (設定ページのログイン ページが開いています): [showWeb] 失敗すると、ユーザーは「移動が失敗しました」ではなく「最初にその側を終了する」ように指示されます。 */
    fun webBusy(): Boolean = false
}

/**
 * ホーム画面では、一度に 1 つのアプリのみがユーザーに配信されます。複数のタスクが同時に引き継がれるように要求された場合、それらは順番にキューに入れられ、一番上にあるタスクだけが「自分で処理」できます。
 * はタスクごとにキューに入れられます。 [Takeover]; [changes] はキューに出入りするたびに変更され、タスク サービスはそれに応じて通知を再描画します。
 * 待ち時間制限は後列の場合にはカウントされず、キューが前に到着したときに計時が開始されます ([Takeover.reachedFront] を参照)。
 */
class TakeoverQueue {
    private val waiting = mutableListOf<Takeover>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Synchronized fun join(owner: Takeover) {
        if (waiting.none { it === owner }) { waiting += owner; _changes.value++ }
    }

    fun leave(owner: Takeover) {
        val front = synchronized(this) {
            val wasFirst = waiting.firstOrNull() === owner
            if (!waiting.removeAll { it === owner }) return
            _changes.value++
            waiting.firstOrNull()?.takeIf { wasFirst }
        }
        front?.reachedFront()
    }

    /** チーム内にない場合 (引き継ぎを待っていない場合)、それもフロントとみなされます。他の人をブロックすることはなく、他の人にブロックされることもありません。 */
    @Synchronized fun isFirst(owner: Any): Boolean = waiting.indexOfFirst { it === owner } <= 0

    /** [owner] ユーザーの処理を待っているタスクがまだいくつかあります。キューにない場合は -1 です。 */
    @Synchronized fun ahead(owner: Any): Int = waiting.indexOfFirst { it === owner }

    /** は [owner] の後ろにランクされており、ユーザーの処理を待っているタスクがいくつかあります。 */
    @Synchronized fun behind(owner: Any): Int {
        val i = waiting.indexOfFirst { it === owner }
        return if (i < 0) 0 else waiting.size - 1 - i
    }
}

/**
 * テイクオーバー用ステートマシン: エージェントがテイクオーバーを要求 → 待機 → 「処理します」 仮想画面の最上位タスクをメイン画面に移動 →
 * 「処理されました」/「自分で完了しました」 元に戻してエージェントに返します。 「スキップ」して処理せずに直接返します（アプリは最初にホーム画面に戻ります）。
 * いつでも「タスクをキャンセル」でき、キャンセルしてもアプリはそのまま残ります。
 * 誰も「処理します」をクリックしないと、[TAKEOVER_WAIT_TIMEOUT_MS] 後にハンドオーバーがタイムアウトになり、アプリは移動しません。引き継ぎ後もタイマーは停止しません。
 * タイミングは [scope] で実行され、[now] は継続時間を測定します。テストは仮想時間に置き換えられます。
 * が [queue] に与えられると、他のタスクの引き継ぎとともにキューに入れられます。後ろの人は「私が扱う」ことはできません。
 */
class Takeover(
    private val port: TakeoverPort,
    private val scope: CoroutineScope,
    private val queue: TakeoverQueue? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Stage { None, Waiting, HandedOver }
    enum class Problem { MoveToMainFailed, MoveBackFailed, WebBusy }

    private val _stage = MutableStateFlow(Stage.None)
    val stage: StateFlow<Stage> = _stage.asStateFlow()
    private val _problem = MutableStateFlow<Problem?>(null)
    /** 最新の移動が失敗した理由。次の成功でクリアされます。 */
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    /** アプリはユーザーの手元 (ホーム画面) にあります: 現時点では、意図的に仮想画面上にあるわけではないため、「ユーザーによって持ち去られた」とは見なされません。 */
    val handedOver: Boolean get() = _stage.value == Stage.HandedOver

    private val mutex = Mutex()
    /** 現在の待機時間は、返された待機時間と同じです。サイクルがダウンする前に再度解除すると、同じ状態に戻ることはできません。 */
    @Volatile private var current: TaskState.WaitingTakeover? = null
    @Volatile private var answered: TaskState.WaitingTakeover? = null
    /** 待機する回数。通知ボタンにはそれが含まれており、クリックされたものが最後に待機した後に残っている場合はカウントされません。 */
    @Volatile var waitKey = 0
        private set
    /** は、ユーザーに Web ページのタブを提供します (ホーム画面に移動するタスクではありません)。 */
    private var handedWeb = false
    /** は、タスクとそのパッケージをホーム画面に移動します。 */
    private var handed = -1
    private var handedPackage: String? = null
    private var handedAt = 0L
    private var timer: Job? = null

    fun onState(state: TaskState) {
        when (state) {
            is TaskState.WaitingTakeover -> if (state !== answered && _stage.value == Stage.None) {
                current = state
                queue?.join(this)
                waitKey++
                _problem.value = null
                _stage.value = Stage.Waiting
                // は、他のタスクの引き継ぎの背後でキューに入れられている場合、タイミングが計測されません。ユーザーにはまだそれを確認する時間がありません。
                if (queue == null || queue.isFirst(this)) armTimer(state)
            }
            // 引き継ぎを待機しているときにループはそこで停止し、他のステータスは解放されません。他のステータスが解放された場合は、ステータスが返されてダウンするか、タスクが終了します。
            else -> {
                stopTimer()
                if (_stage.value == Stage.HandedOver) port.stopWatching()
                current = null
                answered = null
                handedWeb = false
                queue?.leave(this)
                handed = -1
                handedPackage = null
                _stage.value = Stage.None
                _problem.value = null
            }
        }
    }

    /** 「私が対応します。」 */
    suspend fun handOver(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.Waiting || !keyMatches(key)) return false
        if (queue != null && !queue.isFirst(this)) return false
        // 最初にマークしてから移動: 「移動済み」をポーリングしているモニターには、移動の完了直後にマークが表示される場合があります。
        _stage.value = Stage.HandedOver
        // Web インターフェイス: タスクやサウンドを移動せずに、同じラベルを目の前に保持します。
        when (port.showWeb(current?.reason ?: "other", waitKey)) {
            true -> {
                handedWeb = true
                stopTimer()
                handedAt = now()
                _problem.value = null
                return true
            }
            false -> {
                _stage.value = Stage.Waiting
                _problem.value = if (port.webBusy()) Problem.WebBusy else Problem.MoveToMainFailed
                return false
            }
            null -> Unit
        }
        // 仮想画面の一番上のタスクを移動します: アプリは別のタスクでログイン ページと支払いページを開くことがよくあります
        val id = port.agentTopTask().takeIf { it >= 0 } ?: port.targetTask()
        if (id >= 0) {
            if (!port.moveToMain(id)) {
                _stage.value = Stage.Waiting
                _problem.value = Problem.MoveToMainFailed
                return false
            }
            handed = id
            handedPackage = port.packageOf(id) ?: port.targetPackage()
        }
        stopTimer()
        handedAt = now()
        _problem.value = null
        handedPackage?.let {
            port.setSound(it, true)
            port.watchInput(it)
        }
        true
    }

    /** 「処理済み」: エージェントは続行する前に画面を読み取って確認します。 */
    suspend fun handBack(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete), key)

    /** 「自分で終了しました」: エージェントは動作しなくなり、結果を確認するだけで終了します。 */
    suspend fun finishedMyself(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete, userFinished = true), key)

    /**
     * 浮层恢复声音只接管真正交到用户手上的 App;等待接管、网页接管或没有目标包都返回 false,
     * 让调用方走普通手机音频路径。返回 true 表示这条接管路径已处理,不代表 shell 写操作一定成功。
     */
    @Synchronized
    fun unmute(): Boolean {
        if (_stage.value != Stage.HandedOver || handedWeb) return false
        val packageName = handedPackage ?: return false
        port.unmute(packageName)
        return true
    }

    /** "スキップ": 処理は行われず、ハンドオーバーが発生していないことをエージェントに伝えます。アプリを最初にホーム画面に戻した場合、戻せない場合は元の状態のままになります。 */
    suspend fun skip(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.skipped), key)

    /**
     * Web ページの引き継ぎインターフェイスでシステムのリターン キーを押します = 「最初に実行」: ラベルがバックグラウンドに戻り、タスクは引き継ぎの待機状態に戻ります (通知はまだ存在します。もう一度「処理します」をクリックできます)。
     * は通常通り待機します。すでに引き継がれている場合のみ有効です。
     */
    suspend fun putAside(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.HandedOver || !handedWeb || !keyMatches(key)) return false
        port.returnWeb(loggedIn = false)
        handedWeb = false
        _stage.value = Stage.Waiting
        current?.let(::armTimer)
        true
    }

    /** 「タスクをキャンセル」: アプリがホーム画面にある場合、アプリはホーム画面に留まり、ユーザーの目から離されません。 Web ページのラベルはバックグラウンドに戻ります (タスク ラベルは閉じられます)。 */
    suspend fun cancel() = mutex.withLock {
        stopTimer()
        if (handedWeb) {
            port.returnWeb(loggedIn = false)
            handedWeb = false
        }
        if (_stage.value == Stage.HandedOver) port.stopWatching()
        handed = -1
        handedPackage = null
        _stage.value = Stage.None
        _problem.value = null
        queue?.leave(this)
        port.cancel()
    }

    /** キー (アプリケーション内ボタン) が指定されていない場合は、常に認識されます。指定されている場合は、現在の待ち時間でなければなりません。 */
    private fun keyMatches(key: Int?) = key == null || key == waitKey

    private suspend fun giveBack(result: HandBack, key: Int?): Boolean = mutex.withLock {
        if (!keyMatches(key)) false else giveBackLocked(result)
    }

    private suspend fun giveBackLocked(given: HandBack): Boolean {
        val from = _stage.value
        if (from == Stage.None) return false
        var result = given
        if (from == Stage.HandedOver && handedWeb) {
            // 提出物 (完了または自分で行ったもの) は、ユーザーがこの Web サイトにログインしたものとしてカウントされます。スキップはカウントされません。
            val done = result.outcome == HandBackOutcome.complete
            val activity = port.returnWeb(loggedIn = done)
            if (done) result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            handedWeb = false
        } else if (from == Stage.HandedOver) {
            // 戻る前に、現在どのページにいるのかを確認してください。戻ると、そのページはホーム画面に表示されなくなります。
            if (result.outcome == HandBackOutcome.complete) {
                val activity = port.handoverActivity()
                result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            }
            // 渡されたタスクは完了し、自動的に閉じられた可能性があります (存在しない場合は移動する必要はありません)。 launch_app タスクがまだホーム画面にある場合は、それも元に戻ります。
            val target = port.targetTask()
            for (id in listOf(handed, target).filter { it >= 0 }.distinct()) {
                if (port.displayOf(id) == MAIN_DISPLAY && !port.moveBack(id)) {
                    _problem.value = Problem.MoveBackFailed
                    return false
                }
            }
            port.stopWatching()
            setOfNotNull(handedPackage, port.targetPackage()).forEach { port.setSound(it, false) }
            handed = -1
            handedPackage = null
        }
        stopTimer()
        _problem.value = null
        _stage.value = Stage.None
        answered = current
        queue?.leave(this)
        port.resume(result)
        return true
    }

    /** 前回の引き継ぎが処理され、今度は今度は引き継ぎの番です。今から待機時間制限が始まります。 */
    internal fun reachedFront() {
        val waiting = current
        if (_stage.value == Stage.Waiting && waiting != null && timer == null) armTimer(waiting)
    }

    private fun armTimer(state: TaskState.WaitingTakeover) {
        timer?.cancel()
        timer = scope.launch {
            delay(TAKEOVER_WAIT_TIMEOUT_MS)
            mutex.withLock {
                // ホーム画面への移動が失敗して待機状態に戻ると、通常どおりタイムアウトになります。引き継がれたり、次の待ち時間に変更された場合はカウントされません。
                if (_stage.value == Stage.Waiting && current === state) {
                    timer = null // 返品の際、返品中のセルフをキャンセルすることはできません。
                    giveBackLocked(HandBack(HandBackOutcome.timeout))
                }
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    companion object {
        private const val MAIN_DISPLAY = 0
        /** ユーザーが「処理します」をクリックするまでの制限時間。引き継ぎ後は時間制限はありません。 */
        const val TAKEOVER_WAIT_TIMEOUT_MS = 15 * 60_000L
    }
}
