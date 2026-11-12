package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import android.graphics.Rect
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import java.util.UUID
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.OverlayDrag
import com.wangzan101.mika.cap.phone.a11y.OverlayHold
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.DeviceStateWatcher
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.takeover.TakeoverQueue
import com.wangzan101.mika.ui.ChatCopy
import com.wangzan101.mika.ui.VoiceCopy
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.screenThumbnail
import com.wangzan101.mika.ui.ToolLabels
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * タスク状態・前面アプリ・画面状態に応じてフローティングカプセルとカードを更新します。
 * 同じ入力元・近い位置のダブルタップで画面を入力欄にプレビューし、利用者が送信するまで会話へ渡しません。
 * キーボード表示中の位置とドラッグ位置を管理し、実際の操作は既存のタスクサービスへ渡します。
 * カプセルとカードの切り替え時はウィンドウを明示的に隠し、古い描画を画面に残さない。
 */
class CapsuleController(
    private val context: Context,
    private val tasks: TaskCenterState,
    private val foreground: AppForeground,
    /** いくつかのタスクによって共有される引き継ぎキュー: カードとそのカードがここから順番に読み取られます。 */
    private val takeoverQueue: TakeoverQueue,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = CapsuleModel(::reasonLabel)
    private val flags = FeatureFlags.get(context)
    /** タスクが完了した後、ページがバックグラウンドで実行されたままになります (タスクがないときにカプセルがそれを表示し、画像をカードに表示してオフにすることができます)。 */
    private val keeper get() = (context.applicationContext as MikaApp).pageKeeper
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()
    /** 各タスクの質問と回答は現時点ではこんな感じで、ダイアログページと同じです（手順、求められる内容、結果）。 */
    private val exchanges = HashMap<String, Exchange>()

    private var view by mutableStateOf<CapsuleView.Shown?>(null)
    private var card by mutableStateOf<CardView?>(null)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var zoomed by mutableStateOf(false)
    private var dock by mutableStateOf(flags.capsuleDock())
    private var inputUi by mutableStateOf(CardInputUi())
    /** スクリーンショットを撮るときに 1 つ追加します。1 つは点滅します。 */
    private var flash by mutableIntStateOf(0)
    private val taps = DoubleTap(
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    private data class Touch(val at: Long, val x: Float, val y: Float, val source: Int)
    /** 直前の ACTION_DOWN を保持し、Compose の onClick に含まれない入力元と位置を補います。 */
    private var capsuleTouch: Touch? = null
    private var tapJob: Job? = null

    /** 通常の2回目のタップ以外は保留中の判定を破棄し、別の操作をダブルタップに混ぜません。 */
    private fun resetTaps() {
        tapJob?.cancel()
        taps.reset()
        capsuleTouch = null
    }

        /** はスクリーンショットを撮っています (フローティング レイヤーに隠れています): この時点でクリックしてスクリーンショットを撮ってもカウントされません。 */
    private var capturing = false
    /** がカードに送信した会話: 展開されているタスクに従って、答えを確認します。 */
    private var follow: String? = null
    /** アイドル カードに続く最新の会話 (アイドル アイコンをクリックすると 1 回読み取られます)。 */
    private var idle: CardView.Chat? = null

    /** 押し続けると話します。 [talkIdle] は、アイドル アイコンで話し始めたときに読み取られた (話し終わった後に送信された) 最後の会話です。 */
    private val talk = CapsuleTalk(context, { (context.applicationContext as MikaApp).voice }, scope, ::spoken)
    private var talkIdle: Deferred<CardView.Chat>? = null
    /** カプセル上で文章が一時的に点滅します (送信されましたが、はっきりと聞こえず、使用できません)。それは自然に消えます。 */
    private var note by mutableStateOf<CapsuleNote?>(null)
    private var noteJob: Job? = null
    private var noteCount = 0

    /**
     * 対話カードの往復: [threadOf] その対話に記録されたすべてのターン ([past]、バックグラウンドで [ConversationStore] から読み取られます。対話ページと同じです。読み取られない場合は null)、
     * は [shownTurns] ラウンドで最後にランクされ、まだ記録されていないタスクを引き継ぎます。このときインターフェースには[thread]が付与されます。 [threadMark] は、私が最後に会話を読んだときにこの会話に含まれていたタスクと、会話が終了したかどうかです。
     * 変更された場合 (ミッションが終了し、削除され、この時点で会話がそのラウンドに記録されます)、もう一度読んでください。 [placesOf] は、そのラウンドで操作したアプリや Web サイトを走行記録番号に応じて記録します。
     */
    private var thread by mutableStateOf<ChatThread?>(null)
    private var threadOf: String? = null
    private var past: List<PastTurn>? = null
    /** この会話に適したタイトルです (カードがない場合は、最初の文を使用してください)。 */
    private var pastTitle: String? = null
    private var shownTurns = CapsuleThreads.PAGE
    private var threadMark: Any? = null
    private var threadJob: Job? = null
    private val placesOf = HashMap<String, List<String>>()
    private val runStore by lazy { RunStore(context) }

    /** カードの表示可能な最大の高さ (画面と入力ルールに基づくピクセル)。 0はまだ設定されていません。 [cardFraction] はカード上の比率で、[FeatureFlags] として記録されます。 */
    private var cardHeight by mutableIntStateOf(0)
    private var cardFraction = flags.capsuleCardFraction()
    /** ウィンドウ内のカード タイトル バーの位置: カードをドラッグするには、カードを押してのみ可能です。 */
    private var cardHandle: Rect? = null
    /** ユーザー画面の入力方法の上部。ポップアップしない場合は null です。カプセルやカードが邪魔になりません。 */
    private var imeTop: Int? = null
    private var imeJob: Job? = null

    /** は、現時点でのカプセルの透明度です (タッチしないと数秒後に消えます)。 [lastActive] は、最後にタッチされた時刻、またはステータスが変更された時刻です。 */
    private var alpha by mutableStateOf(1f)
    private var lastActive = 0L
    private var look: Any? = null
    private var fadeJob: Job? = null

    /** カードは展開中です。 [closing] はカプセルを格納しています (アニメーションが完了するまで実際には格納されません)。 */
    private var expanded = false
    private var closing by mutableStateOf(false)
    /** カードが本当に出始めました。そのとき初めて 1 枚が邪魔になりませんでした (カードのレイアウト、出てくる前に 1 枚はまだ同じ場所にあり、空ではありませんでした)。カードが後退し始めると、1枚が戻ってきました。 */
    private var cardOut by mutableStateOf(false)
    /** 展開前のカードウィンドウの中心（画面の垂直座標）とカードウィンドウの上部：カードはその位置から伸びたり縮んだりします。 */
    private var anchorY = 0
    private var cardTop by mutableIntStateOf(0)
    private var previewKey: String? = null
    private var previewJob: Job? = null

    /** 「あなたがそれを処理します」身長比（この実行でのみ記憶されています）。 */
    private var barFraction = 0f
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var placedY = 0
    /** カードウィンドウの上部。 */
    private var cardY = 0
    private var snap: ValueAnimator? = null

    /**
     * [window] は画面端のカプセル、[cardWindow] は中央のカードを表示する。
     * 切り替えでウィンドウを移動するとシステムの移動アニメーションが出るため、位置は固定し、
     * 使わないウィンドウを明示的に非表示にしてから内容を空にする。
     */
    private var window: OverlayWindow? = null
    private var cardWindow: OverlayWindow? = null
    /** 話すときは、カプセルの窓の下にあるパネルを押し続けます (暗いですが、カプセルは覆われていません)。通常は空で、サイズはありません。追加できない場合は、長押しして話すことはできません。 */
    private var panelWindow: OverlayWindow? = null
    /** ウィンドウはどのサービス インスタンスに追加されますか?サービスが変更され、古いウィンドウが古いインスタンスとともに削除されました。 */
    private var windowOn: ScreenAccessibility? = null
    /** このサービス インスタンスにウィンドウを追加できませんでした。再試行せず、サービスが再接続するまで待ちます。 */
    private var failedOn: ScreenAccessibility? = null
    private var tick: Job? = null
    private val ticks = MutableStateFlow(0)
    private var last: Snapshot? = null

    private val _showing = MutableStateFlow(false)
    /** カプセルは、現時点では実際に照明された画面に表示されています。それに応じて、タスク サービスは「あなたが必要です」通知をより静かに送信します。 */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    fun start() {
        val screen = DeviceStateWatcher(context) { -1 }
        scope.launch {
            combine(
                combine(runs(), flags.capsuleModeChanges(), foreground.visible, screen.locked, ScreenAccessibility.instance) { r, m, f, l, a -> Snapshot(r, m, f, l, a) },
                ticks,
                keeper.page,
            ) { s, _, kept -> s.copy(kept = kept) }.collect(::render)
        }
        // 発言パネルは、引き出すと画面全体を覆い（奥は暗くなります）、収納すると元の大きさに戻ります。
        scope.launch { snapshotFlow { talk.active }.collect { panelWindow?.setFullScreen(it) } }
        // 停止後の説明が書かれた場合（または書かれなかった場合）の順番を改善しました。ダイアログ: 記録されたターンを読み直します。
        scope.launch { tasks.summarizing.collect { threadOf?.let(::loadPast) } }
        // ダイアログが削除されました: [forget] を参照してください。
        scope.launch { ConversationStore.shared(context).deletions.collect(::forget) }
        // カード上の会話のタイトルが変更されました (ユーザーがサイドバーで名前を変更し、タイトルがバックグラウンドで設定されました): カード上部の「「...」でチャットを続ける」が変更されました。
        scope.launch {
            val store = ConversationStore.shared(context)
            store.changes.collect { id ->
                if (id != threadOf) return@collect
                val title = withContext(Dispatchers.IO) { store.title(id) }
                if (id == threadOf && title != pastTitle) {
                    pastTitle = title
                    rebuildThread()
                }
            }
        }
        // 画面の回転: 指定された辺と比率に従って再配置します。システムは少し後に新しいサイズを提供する場合があるので、しばらくしてから再度調整できます。
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                resetTaps()
                place()
                scope.launch {
                    delay(ROTATE_SETTLE_MS)
                    place()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    private data class Snapshot(
        val runs: List<CapsuleRun>, val mode: CapsuleMode, val mikaInForeground: Boolean, val locked: Boolean,
        val service: ScreenAccessibility?,
        /** タスクの完了後、ページがバックグラウンドで実行されたままになります。 */
        val kept: KeptPage? = null,
    )

    /**
     * 現時点での各タスクの様子。タスクが完了すると、[TaskCenterState.runs] から削除されます。削除されると、その状態はすでに最終状態になります。
     * を持ち運んでいる場合にのみ (タスク リストが再び変更されるまで)、モデルは「完了」を書き留めてしばらく停止できます。一時停止後にモデルとしてリストすることはカウントされません。
     * 最終状態 (サービスが破棄される) に達する前に削除された場合、直接カウントされません。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runs(): Flow<List<CapsuleRun>> {
        var previous = emptyList<TaskRun>()
        return tasks.runs.flatMapLatest { current ->
            val gone = previous.filter { r -> current.none { it === r } && !r.state.value.isActive() }
            previous = current
            val all = current + gone
            exchanges.keys.retainAll(all.map { it.key }.toSet())
            if (all.isEmpty()) flowOf(emptyList())
            else combine(all.map { r ->
                // は現在操作画面上にあります。独自の仮想画面があるか、内蔵ブラウザを使用しています (カードはそれに応じてタスク カードまたは会話カードを選択します)。
                combine(r.state, r.takeoverStage, r.activities, r.displayId, r.webOpen) { s, st, acts, display, web ->
                    CapsuleRun(
                        r.key, r.conversationId, s, st, r.goal, exchange(r, s, acts),
                        runId = r.runId.value, onScreen = display >= 0 || web,
                    )
                }.combine(r.steers) { c, steers -> c.copy(steers = steers) }
            }) { it.toList() }.combine(takeoverQueue.changes) { list, _ ->
                // キュー内の注文を引き継ぎます: キューが変更された場合 (前のアイテムが返された場合)、再度読み込むと、次のアイテムがすぐに「Need You」になります。
                list.map { c ->
                    val owner = all.firstOrNull { it.key == c.key }?.takeover ?: return@map c
                    val ahead = takeoverQueue.ahead(owner)
                    if (ahead < 0) c else c.copy(queueAhead = ahead, queueBehind = takeoverQueue.behind(owner))
                }
            }
        }
    }

    private fun exchange(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchanges[r.key] ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchanges[r.key] = it }
    }

    private fun render(s: Snapshot) {
        last = s
        val service = s.service
        if (windowOn != null && windowOn !== service) {
            talk.abort()
            window = null
            cardWindow = null
            panelWindow = null
            windowOn = null
        }
        val inputs = CapsuleInputs(
            s.runs, s.mode, s.mikaInForeground, screenInteractive = !s.locked,
            serviceAvailable = service != null && service !== failedOn, cardOpen = expanded, kept = s.kept,
        )
        val shown = model.update(inputs, System.currentTimeMillis()) as? CapsuleView.Shown
        schedule(model.nextChangeAt)
        if (shown == null || service == null) {
            hide()
            return
        }
        // アプリがあなたに引き渡されました: 一番上のアプリがカードを置き換えます
        if (shown.kind == CapsuleKind.HandedOver) resetCard()
        view = shown
        card = if (expanded) CapsuleCards.of(model.cardRuns(), follow, idle, s.kept) else null
        if (expanded && card == null) resetCard()
        updateThread(card as? CardView.Chat, s.runs)
        // は入力ボックスのないコンテンツに置き換えられます (たとえば、ユーザーの同意が必要です): 入力ボックスが閉じられ、フォーカスが次のアプリに戻ります。
        if (inputUi.open && card?.let(CapsuleCards::input) == null) closeInput()
        val detail = card as? CardView.Detail
        val keptCard = card as? CardView.Kept
        if (keptCard != null) watchKept(keptCard.page.id)
        else watchPreview(detail?.takeIf { CapsuleCards.showsScreen(it.content) }?.run?.run?.key)
        val w = window ?: openWindows(service)
        if (w == null) {
            view = null
            card = null
            _showing.value = false
            return
        }
        applyDrag()
        place()
        // はステータス、ピース数、展開または縮小を変更しました。ただちに復元して時間を再スタートします。 1 つの文だけを変更した場合はカウントされません (その文は表示されません)
        val nowLook = listOf(shown.kind, shown.pill, shown.count, shown.needCount)
        if (nowLook != look) {
            look = nowLook
            resetTaps()
            wake()
        } else fade()
        _showing.value = true
    }

    /** は 2 つのウィンドウを追加します (最初の 1 つ、次にカード 1 つ、カードが上になります)。どちらかを追加できない場合、どちらも必要ない場合は、返品の通知が届きます。 */
    private fun openWindows(service: ScreenAccessibility): OverlayWindow? {
        val overlays = service.overlays()
        // パネルは最初にカプセルの下に追加されます。カプセルを覆わないように暗いレイヤーを押します。
        val panel = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) { VoicePanelOverlay() }
        val ball = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT)) { CapsuleOverlay() }
        val cardW = ball?.let { overlays.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL), ::outside) { CardOverlay() } }
        if (ball == null || cardW == null) {
            ball?.close()
            panel?.close()
            failedOn = service
            Log.w(TAG, "capsule overlay unavailable, falling back to notifications")
            return null
        }
        window = ball
        cardWindow = cardW
        panelWindow = panel
        windowOn = service
        syncWindowVisibility()
        for (it in listOf(ball, cardW)) it.onResize(::place)
        ball.onTouch { e ->
            wake()
            capsuleTouch = Touch(e.eventTime, e.rawX, e.rawY, e.source)
        }
        cardW.onTouch { wake() }
        cardW.onBack(::back)
        watchIme(service)
        return ball
    }

    private fun refresh() {
        last?.let(::render)
    }

    /**
     * ダイアログ [id] 削除済み: 終了したタスクはすぐにカプセルとカードから削除され、カードはそれを継続しません (メッセージはカードに送信されたばかりで、アイドル状態のカードは継続されます)。
     * (展開時に新しい会話を開くカードに置き換えます)、[CapsuleCards.of] を押して展開されたカードを再選択します (何もない場合は、しまっておきます)。会話を削除するには、まずタスクを停止します。
     * はレコードを削除する前に完了するまで (最大 15 秒) 待機するため、そのタスクは通常この時点で終了しています。終了を待つことができないか、終了後もレコードを削除する前にカード内で続行されます。
     * (この会話で新しいタスクが開きます)、その後の保存ラウンドにより会話が再作成され、カプセルには通常どおり表示されます。
     */
    private fun forget(id: String) {
        model.forget(id)
        if (follow == id) follow = null
        if (idle?.conversationId == id) idle = if (expanded) CardView.Chat(null) else null
        refresh()
    }

    internal fun stopNow() {
        scope.cancel()
        hide()
    }

    private fun hide() {
        resetTaps()
        snap?.cancel()
        fadeJob?.cancel()
        look = null
        imeJob?.cancel()
        imeJob = null
        imeTop = null
        dragging = false
        talk.abort()
        talkIdle = null
        noteJob?.cancel()
        note = null
        window?.close()
        window = null
        cardWindow?.close()
        cardWindow = null
        panelWindow?.close()
        panelWindow = null
        windowOn = null
        view = null
        resetCard()
        card = null
        _showing.value = false
    }

    private fun resetCard() {
        expanded = false
        closing = false
        cardOut = false
        syncWindowVisibility()
        follow = null
        zoomed = false
        cardHandle = null
        closeInput()
        inputUi = inputUi.collapsed()
        watchPreview(null)
        updateThread(null, emptyList())
    }

    /** 两个窗口交替时显式显隐;空内容只负责排版,不承担把系统窗口旧帧撤下屏幕的职责。 */
    private fun syncWindowVisibility() {
        val visible = capsuleWindowVisibility(expanded, cardOut, closing)
        window?.setVisible(visible.capsule)
        cardWindow?.setVisible(visible.card)
    }

    // ---------- 输入 ----------

    /** が入力ボックスをクリックすると、フローティング レイヤーがキーボードを受け取ることができ、入力メソッドがそのレイヤーに対してポップアップ表示されます。 */
    private fun openInput() {
        if (inputUi.open) return
        cardWindow?.setFocusable(true)
        inputUi = inputUi.copy(open = true)
    }

    /** 入力ボックスを閉じて、すぐに次のアプリにフォーカスを戻します。 */
    private fun closeInput() {
        if (!inputUi.open) return
        inputUi = inputUi.copy(open = false)
        cardWindow?.setFocusable(false)
    }

    /** リターン キー: 最初に入力ボックスを閉じ、次に拡大画面を閉じ、最後にカードを閉じます。 */
    private fun back() {
        when {
            inputUi.open -> closeInput()
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    /** 画面を拡大または閉じます。拡大時にカードをドラッグすることはできません（タイトルバーはありません）。 */
    private fun zoom(on: Boolean) {
        zoomed = on
        applyDrag()
    }

    /**
     * 入力欄の発言を送ると、会話ページで送るのと同じ経路を通る（どちらもその会話に記録される）: 実行中・条件待ちのときは割り込み発言として渡し、
     * Mika から尋ねられているときは回答として、完了後は同じ会話で続けて話し、アイドル時は直近の会話を継ぐか新しい会話を開く; 後二者の後はカードはその会話のタスクに追随して回答を見る。
     */
    private fun send() {
        val text = inputUi.draft.trim()
        val shot = inputUi.screen?.shot
        val c = card ?: return
        val kind = CapsuleCards.input(c)?.kind ?: return
        if (text.isEmpty() && shot == null) return
        inputUi = CardInputUi()
        cardWindow?.setFocusable(false)
        if (shot != null) deliver(c, kind, shot, text) else submit(c, kind, text, CapsuleCards.conversationOf(c))
        refresh()
    }

    /** 入力欄が現在誰に渡すか（[kind]）に応じて [text] を送る; 話し始めるときに [conversationId] に入る（null のときは新規に開く）。 */
    private fun submit(c: CardView, kind: CardInputKind, text: String, conversationId: String?) {
        val run = when (c) {
            is CardView.Detail -> c.run.run
            is CardView.Chat -> c.run?.run
            is CardView.Kept -> null
        }
        when (kind) {
            CardInputKind.Steer -> run?.let { TaskService.start(context, text, it.conversationId, messageId = UUID.randomUUID().toString()) }
            CardInputKind.Answer -> run?.let { TaskService.answer(context, text, it.key) }
            CardInputKind.Continue -> run?.let { startIn(it.conversationId, text) }
            CardInputKind.Start -> startIn(conversationId ?: ConversationStore.shared(context).newConversation(), text)
        }
    }

    /**
     * 切り取った画面を添え書き [words] と一緒に送る: まずこの発言の送り先の会話へ保存し（新しい会話ならこの時点で番号が決まる）、
     * は画面付きの文 ([UserScreenMessage]) に書き換えられ、通常どおり送信されます。保存できない場合は送信されません。
     */
    private fun deliver(c: CardView, kind: CardInputKind, shot: CapturedScreen, words: String) {
        val access = (context.applicationContext as MikaApp).appAccess.current()
        if (shot.sourcePackages.isEmpty() || shot.sourcePackages.any { !access.canShare(it) }) {
            inputUi = inputUi.copy(screen = null)
            android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val conversationId = CapsuleCards.conversationOf(c) ?: ConversationStore.shared(context).newConversation()
        scope.launch {
            val saved = runCatching { (context.applicationContext as MikaApp).userScreens.save(conversationId, shot) }
                .onFailure { Log.w(TAG, "could not save the screen", it) }.getOrNull() ?: return@launch
            val latest = (context.applicationContext as MikaApp).appAccess.current()
            if (shot.sourcePackages.any { !latest.canShare(it) }) {
                inputUi = inputUi.copy(screen = null)
                android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            submit(c, kind, UserScreenMessage.compose(saved.id, words), conversationId)
            refresh()
        }
    }

    /**
     * ダブルタップまたは読上げの画面取得で、カードの入力欄に [PendingScreen] を追加します。
     * 会話への保存・送信は行わず、利用者の確認後に [send] が [deliver] を呼びます。
     */
    private fun previewScreen() {
        val v = view ?: return
        if (capturing) return
        scope.launch {
            if (v.kind == CapsuleKind.Idle) idle = loadIdle()
            val target = CapsuleCards.of(model.cardRuns(), follow, idle)
            val kind = target?.let(CapsuleCards::input)?.kind
            if (target == null || kind == null) {
                expand()
                return@launch
            }
            CapsuleCards.conversationOf(target)?.let { follow = it }
            open()
            refresh()
            val shot = captureScreen() ?: return@launch
            val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
            if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
        }
    }

    /** カードのスクリーンショット ボタン: スクリーンショットを撮って入力ボックスに貼り付けると、文を追加するかどうかを選択できます。 */
    private fun captureForCard() {
        if (capturing) return
        scope.launch {
            val shot = captureScreen() ?: return@launch
            val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
            if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
        }
    }

    /**
     * は、ユーザーの現在の画面のスクリーンショットを取得します。入力ボックスが最初に開いて閉じられ (入力メソッドが削除されるのを待機します)、フローティング レイヤーは非表示になり、スクリーンショットを撮る前に実際に画面から削除されます。カットオフ直後に出て、アイコンが上に点滅します。 次に、
     * がバックグラウンドで画面上の単語を整理します。フロントは美香本人が担当しており、期限までに間に合わない場合は無効となります。
     */
    private suspend fun captureScreen(): CapturedScreen? {
        val service = windowOn ?: return null
        val w = window ?: return null
        val c = cardWindow ?: return null
        capturing = true
        try {
            if (inputUi.open) {
                closeInput()
                withTimeoutOrNull(IME_GONE_MS) { while (imeTop != null) delay(IME_POLL_MS) }
            }
            // 空のウィンドウ (展開されている場合は箇条書きがあるウィンドウ、折りたたまれている場合はカードがあるウィンドウ) には何も描画しないので、待つ必要はありません。
            suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
            val store = (context.applicationContext as MikaApp).appAccess
            val sources = MainScreenCapture.sourcePackages(service) ?: return null
            if (context.packageName in sources || !store.current().available) return null
            for (pkg in sources) {
                if (!store.current().canShare(pkg) && !com.wangzan101.mika.cap.phone.a11y.requestScreenShare(service, store, pkg, appLabel(pkg))) return null
            }
            val capture = w.hidden { c.hidden {
                if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) null
                else MainScreenCapture.shoot(service)
            } } ?: return null
            val (shot, read) = capture
            if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) {
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return null
            }
            flash++
            wake()
            val area = service.overlays().area()
            val r = MainScreenCapture.build(service, shot, read, area.insetTop, area.insetBottom) { ConfirmationNotifier.appLabel(context, it) }
            if (sources.any { !store.current().canShare(it) } || MainScreenCapture.sourcePackages(service) != sources) return null
            val captured = (r as? MainScreenCapture.Result.Captured)?.shot ?: return null
            return CapturedScreen(captured.jpeg, captured.screen, sources)
        } finally {
            capturing = false
        }
    }

    /**
     * は、会話 [conversationId] で [text] と言い始めます。他のタスクは実行中にキューに入れられ、失われることはありません。次に、カードはこれに話しかけます。
     * この会話を継続するためにアイドル カードも変更されました (新しい会話のタイトルはこの文です)。
     */
    private fun startIn(conversationId: String, text: String) {
        TaskService.start(context, text, conversationId)
        follow = conversationId
        idle = idle?.let { CardView.Chat(conversationId, title = if (it.target == conversationId) it.title else ScreenCopy.plain(text)) }
    }

    /** アイドル アイコンをクリックします。まず、ユーザーが現在どの会話に参加しているかを読み取り ([loadIdle])、次に展開します ([updateThread] によって前後に読み取ります)。 */
    private fun openIdle() {
        scope.launch {
            idle = loadIdle()
            open()
            refresh()
        }
    }

    /**
     * アイドル カードに続く会話、つまりユーザーがアプリ内にいる会話。レコードがない場合は、最新のレコードが続きます ([CapsuleIdle] を参照)。記録がない場合は、新しい会話が開始されます。
     * 会話ファイルの時刻を使用しない推測: 新しく開かれた会話にはまだファイルがありません。推測は前の会話です。
     */
    private suspend fun loadIdle(): CardView.Chat = withContext(Dispatchers.IO) {
        runCatching {
            val store = ConversationStore.shared(context)
            val recent = store.list().firstOrNull()?.let { CardView.Chat(it.id, title = it.title?.let(ScreenCopy::plain)) }
            CapsuleIdle.chat(store.currentChat(), recent) { id ->
                // サイドバーと同じ: ユーザーが指定した名前または最初の文
                (store.title(id) ?: store.turns(id).firstOrNull()?.userText)?.let(ScreenCopy::plain)
            }
        }.getOrElse { CardView.Chat(null) }
    }

    // ---------- 対話カードの前後 ----------

    /**
     * ダイアログ カードを前後に移動: ダイアログを変更した後、最初から読み取ります (最新の [CapsuleThreads.PAGE] ラウンドを最初に読み取ります)。このダイアログ内のタスクが表示され、終了し、削除されたときに、記録されたターンを再読します。
     * (ダイアログはタスク終了後のラウンドでのみ記録されます);また、現在のタスクに応じて再配置されるだけの場合もあります (答えは長くなります)。会話カードでない場合はクリアしてください。
     */
    private fun updateThread(chat: CardView.Chat?, runs: List<CapsuleRun>) {
        val id = chat?.target
        if (id == null) {
            threadJob?.cancel()
            threadOf = null
            past = null
            threadMark = null
            shownTurns = CapsuleThreads.PAGE
            // アイドル カードの新しいダイアログ: 前後が空です
            thread = if (chat != null) ChatThread(emptyList()) else null
            return
        }
        val mark = runs.filter { it.conversationId == id }.map { it.key to it.state.isActive() }
        if (id != threadOf) {
            threadOf = id
            past = null
            shownTurns = CapsuleThreads.PAGE
            thread = null
        }
        if (mark != threadMark) {
            threadMark = mark
            loadPast(id)
        }
        rebuildThread()
    }

    /** 現時点でカードに記載されているラウンドとタスクに従って前後に配置します。メモは読まれるまで空白のままにしておきます。 */
    private fun rebuildThread() {
        val id = threadOf ?: return
        val p = past ?: return
        val live = CapsuleThreads.liveTail(model.cardRuns(), id, p.mapNotNull { it.turn.runId }.toSet())
        val built = CapsuleThreads.build(p, shownTurns, live, tasks.summarizing.value) { CapsuleThreads.places(it.run.exchange?.activities.orEmpty(), ::appLabel) }
        thread = pastTitle?.let { built.copy(title = it) } ?: built
    }

    /** バックグラウンド読み取りダイアログ [id] 記録されたラウンドと、そのラウンドで操作したアプリやウェブサイトを整理（走行記録から読み取って、読み取ったものを記憶）し、読み込んだ後に並べ替えます。 */
    private fun loadPast(id: String) {
        threadJob?.cancel()
        val known = HashMap(placesOf)
        val n = shownTurns
        threadJob = scope.launch {
            val (turns, found) = withContext(Dispatchers.IO) {
                val turns = runCatching { ConversationStore.shared(context).turns(id) }.getOrDefault(emptyList())
                turns to turns.takeLast(n).mapNotNull { it.runId }.filter { it !in known }.associateWith(::placesIn)
            }
            val title = withContext(Dispatchers.IO) { ConversationStore.shared(context).title(id) }
            placesOf += found
            if (threadOf != id) return@launch
            pastTitle = title
            past = turns.map { PastTurn(it, it.runId?.let(placesOf::get).orEmpty()) }
            rebuildThread()
        }
    }

    /** その時点で運営されていたアプリやウェブサイト。レコードがクリアされている場合、または読み取ることができない場合は空になります (純粋なチャットとして扱われます)。バックグラウンドスレッドで調整しました。 */
    private fun placesIn(runId: String): List<String> = runCatching {
        CapsuleThreads.places(runStore.events(runId).filterIsInstance<RunEvent.Activity>()) { ConfirmationNotifier.appLabel(context, it) }
    }.getOrDefault(emptyList())

    /** は一番上までスクロールしました。[CapsuleThreads.PAGE] ホイールを数行前に移動します (まず、操作したアプリを読み上げます)。 */
    private fun loadEarlier() {
        val id = threadOf ?: return
        val p = past ?: return
        if (p.size <= shownTurns) return
        shownTurns += CapsuleThreads.PAGE
        loadPast(id)
    }

    /** は、ユーザーの画面上の入力方法に従い、ポップアップ、格納、高くなったらリセットします。 */
    private fun watchIme(service: ScreenAccessibility) {
        imeJob?.cancel()
        imeJob = scope.launch {
            service.overlays().imeTop().collect {
                imeTop = it
                place()
            }
        }
    }

    /** はモデルで指定された時間に再度カウントされます (つまり、完了後に縮小して消えます)。 */
    private fun schedule(at: Long?) {
        tick?.cancel()
        tick = at?.let { t ->
            scope.launch {
                delay((t - System.currentTimeMillis()).coerceAtLeast(0L))
                ticks.value++
            }
        }
    }

    // ---------- 半透明 ----------

    /** が発生しました: すぐに復元し、これから時間を再開します。 */
    private fun wake() {
        lastActive = System.currentTimeMillis()
        fade()
    }

    private fun fade() {
        fadeJob?.cancel()
        val v = view ?: return
        val idleFor = System.currentTimeMillis() - lastActive
        alpha = if (expanded) 1f else CapsuleFade.alpha(v.kind, v.needCount, v.pill, idleFor)
        if (!expanded && idleFor < CapsuleFade.IDLE_MS) fadeJob = scope.launch {
            delay(CapsuleFade.IDLE_MS - idleFor)
            fade()
        }
    }

    // ---------- 画面が小さい ----------

    /** は展開されたものの画面を閉じるだけです。何かが変更されたり折りたたまれたりすると停止します。 */
    private fun watchPreview(key: String?) {
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        previewJob = null
        preview = null
        zoomed = false
        val run = key?.let(::runFor) ?: return
        previewJob = scope.launch {
            launch { run.frames.collect { if (it == null) preview = null } }
            run.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect {
                preview = it.takeIf { run.frames.value != null }
            }
        }
    }

    /** ページ上のカードがバックグラウンドで実行されたままになっています: 表示されている画面を閉じます (タスク カードの小さい画面と同じ)。 */
    private fun watchKept(id: Long) {
        val key = "kept:$id"
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        preview = null
        zoomed = false
        previewJob = scope.launch {
            keeper.frames().filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { preview = it }
        }
    }

    // ---------- 位置とドラッグ ----------

    /** がスイングできる範囲; [ime] が true の場合、入力メソッドの邪魔をしないようにします。 */
    private fun area(ime: Boolean = true): DockArea? = windowOn?.overlays()?.area()?.let {
        val room = room()
        DockArea.of(it, margin = dp(EDGE_MARGIN_DP), gap = dp(TOP_GAP_DP), room = room)
            .let { a -> if (ime) a.above(imeTop, dp(TOP_GAP_DP), room) else a }
    }

    private fun room() = dp(CapsuleShadowRoom.value.toInt())

    private fun isBar() = view?.kind == CapsuleKind.HandedOver

    /**
     * メモした位置に従ってウィンドウを配置します。カプセルはメモした側に取り付けられ、「You handle it」の行は上部の中央に、カードは左右の中央に配置され、上部はメモした比率に従って配置されます。
     * を配置できないか、入力メソッド カバーに到達した場合は上に移動します (同時にカードの最大高さが短くなります)。カードの高さが変更されると (カードが変更され、コンテンツが変更されます)、ウィンドウもそれに応じて変更され、再配置されますが、上部は移動しません。
     */
    private fun place() {
        val w = window ?: return
        if (dragging || snap?.isRunning == true) return
        val a = area() ?: return
        placeCard(a)
        val h = w.height.coerceAtLeast(dp(PILL_DP))
        if (isBar()) {
            placedY = CapsuleDocking.fitY(CapsuleDocking.y(barFraction, a, dp(BAR_DP)), a, h)
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, placedY))
            return
        }
        // カードを広げると、このウィンドウは空になりますが、端で止まります。折りたたむと、同じ場所から 1 枚のカードが出てきます。
        val d = dock
        // インプットメソッドカバーを元の位置に移動します。カプセルとカードをそこに移動し、収納して元の位置に戻します。
        val base = area(ime = false) ?: a
        val natural = CapsuleDocking.fitY(CapsuleDocking.y(d.fraction, base, dp(PILL_DP)), base, h)
        placedY = CapsuleDocking.fitY(natural, a, h)
        val side = if (d.side == DockSide.Left) Gravity.LEFT else Gravity.RIGHT
        w.place(OverlayPlacement(Gravity.TOP or side, CapsuleDocking.edgeOffset(d.side, a), placedY))
    }

    /**
     * カードウィンドウを左右中央に配置し、上部にメモした比率を押します。カードの最大高さは、画面と入力規則によって決まります。コンテンツが変更されると、再配置され、ウィンドウのサイズと位置が変更されます。
     * ウィンドウを展開したばかりで、まだウィンドウが空の場合は、カードの高さに合わせて配置します（対話カードは固定高に固定されています。タスクカードは内容に応じて高さが異なり、短いものが最初に計算されます）。内容が出てきたときに移動する必要はありません。
     */
    private fun placeCard(a: DockArea) {
        val w = cardWindow ?: return
        val c = card ?: return
        windowOn?.overlays()?.area()?.let { cardHeight = CardDocking.maxHeight(it.height, a, room()) }
        val base = area(ime = false) ?: a
        val expected = if (c is CardView.Chat) cardHeight + 2 * room() else dp(PILL_DP)
        cardY = CardDocking.y(cardFraction, base, a, cardFull(base), maxOf(w.height, expected))
        cardTop = cardY
        w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cardY))
    }

    /** 最も背の高いカード (対話カード) が入力メソッドの範囲内にあります。 [base] のウィンドウの高さ (影付きのエッジを含む): カードの上部の比率はそれに応じて計算されます。 */
    private fun cardFull(base: DockArea): Int =
        CardDocking.maxHeight(windowOn?.overlays()?.area()?.height ?: 0, base, room()) + 2 * room()

    /**
     * どこにドラッグできますか: カプセルとカプセル全体をドラッグできます。カードのドラッグはタイトルバーを押すことでのみ可能（中央の上下スワイプはスクロール）で、画面を拡大するとドラッグできません。
     * カプセルの長押しでは、指を離したときに完全停止の確認を開く。
     */
    private fun applyDrag() {
        window?.draggable(drag)
        val talkable = canTalk()
        // もう何も言えません（たとえば、アプリがあなたに渡され、カプセルがどのカプセルに変更された場合）：このセクションは破棄され、送信されません - ユーザーは選択したドロップポイントを解放する機会がありません。
        if (!talkable && talk.active) talk.abort()
        window?.holdable(stopHold.takeUnless { expanded || capturing }, ViewConfiguration.getLongPressTimeout().toLong())
        cardWindow?.draggable(cardDrag, cardHandle.takeIf { !zoomed } ?: Rect())
    }

    /** カードはタイトル バーの位置を報告します。 */
    private fun onHandle(r: Rect) {
        if (r == cardHandle) return
        cardHandle = r
        applyDrag()
    }

    /** タイトル バーを押して、カードを上下にドラッグします (動かさずに中央の左右にドラッグします)。手を放して上記の比率を確認し、次に押すと、カードを展開したり、カードを変更したり、画面を回転したりできます。 */
    private val cardDrag = object : OverlayDrag {
        override fun start() {
            dragging = true
            dragStartY = cardY
        }

        override fun move(dx: Float, dy: Float) {
            val w = cardWindow ?: return
            val a = area() ?: return
            if (!dragging) return
            val base = area(ime = false) ?: a
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, CardDocking.dragY(dragStartY, dy, base, a, cardFull(base), w.height)))
        }

        override fun end(dx: Float, dy: Float) {
            val w = cardWindow
            val a = area()
            val was = dragging
            dragging = false
            if (!was || w == null || a == null) return
            val base = area(ime = false) ?: a
            val full = cardFull(base)
            cardFraction = CardDocking.release(CardDocking.dragY(dragStartY, dy, base, a, full, w.height), base, full)
            flags.setCapsuleCardFraction(cardFraction)
            place()
        }
    }

    /** カプセル (左右上下) または「あなたが操作します」カプセル (上下のみ) をドラッグします。カードについては、[cardDrag] を参照してください。 */
    private val drag = object : OverlayDrag {
        override fun start() {
            val w = window ?: return
            val a = area() ?: return
            resetTaps()
            snap?.cancel()
            wake()
            dragging = true
            dragStartX = CapsuleDocking.dockedX(dock.side, a, w.width)
            dragStartY = placedY
        }

        override fun move(dx: Float, dy: Float) {
            val w = window ?: return
            val a = area() ?: return
            if (!dragging) return
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, y))
            else w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width), y))
        }

        override fun end(dx: Float, dy: Float) {
            val w = window
            val a = area()
            if (!dragging || w == null || a == null) {
                dragging = false
                return
            }
            dragging = false
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) {
                barFraction = CapsuleDocking.fraction(y, a, dp(BAR_DP))
                place()
                return
            }
            val x = CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width)
            val base = area(ime = false) ?: a
            dock = CapsuleDocking.release(x, y, w.width, w.height, a, base)
            flags.setCapsuleDock(dock)
            snapTo(x, y, CapsuleDocking.dockedX(dock.side, a, w.width))
        }
    }

    /** 手を放した後、横にスライドします。アニメーションがオフのときに真上にスイングします。 */
    private fun snapTo(fromX: Int, y: Int, toX: Int) {
        val w = window ?: return
        placedY = y
        if (animationsOff(context) || fromX == toX) {
            place()
            return
        }
        snap = ValueAnimator.ofInt(fromX, toX).setDuration(SNAP_MS).apply {
            addUpdateListener { w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, it.animatedValue as Int, y)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snap = null
                    place()
                }
            })
            start()
        }
    }

    /** 画面を読み取るときにドラッグする代わりに、反対側に切り替えます。 */
    private fun swapSide() {
        resetTaps()
        dock = dock.copy(side = if (dock.side == DockSide.Left) DockSide.Right else DockSide.Left)
        flags.setCapsuleDock(dock)
        place()
    }

    // ---------- 長押しして発言してください ----------

    /** この時点でカプセル上で話せますか: パネル ウィンドウがありますが、「あなたが操作します」ウィンドウではなく、音声入力が無効に設定されていません。 */
    private fun canTalk(): Boolean = panelWindow != null && !isBar() &&
        (context.applicationContext as MikaApp).speech.choice.value != SpeechChoice.Off

    private val stopHold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) = resetTaps()
        override fun move(rawX: Float, rawY: Float) = Unit
        override fun end(rawX: Float, rawY: Float) = openCompleteStop()
        override fun cancel() = Unit
    }

    private fun openCompleteStop() = com.wangzan101.mika.quit.CompleteStopActivity.open(context)

    /** 画面読み上げの「話す」: 話していないときに開始し (指がなく、話すことしかできません)、話しているときに話します。 */
    private fun toggleTalk() {
        if (talk.active && !talk.byTouch && !talk.released) talk.finish(CapsuleVoice.Drop.Send)
        else beginTalk(byTouch = false)
    }

    /**
     * は次のように言い始めました: シングルクリックやダブルクリックを待ってもカウントされません。展開されるカードをクリックする人を指定します (アイドル状態のアイコンで、最初に最新の会話を読み、読み終わったらパネルの上部にある文に変更します)。
     * には録音許可がありません (カプセルは許可ボックスをポップアップできません)。許可が利用できない場合、音声入力は録音されません。カプセル上で何かを言い、クリックしてミカを開きます。
     */
    private fun beginTalk(byTouch: Boolean) {
        val v = view ?: return
        // は、放した後も回転しているときにもう一度押すことができます。前の段落は talk.begin によって破棄されます。
        if (capturing || (talk.active && !talk.released) || !canTalk()) return
        resetTaps()
        wake()
        clearNote()
        val loading = if (v.kind == CapsuleKind.Idle) scope.async { loadIdle() } else null
        when (val b = talk.begin(CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, idle)), byTouch)) {
            CapsuleTalk.Begin.Started -> {
                talkIdle?.cancel()
                talkIdle = loading
                loading?.let {
                    scope.launch {
                        val chat = it.await()
                        if (talk.active && talkIdle === it) talk.title = CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, chat))
                    }
                }
            }
            CapsuleTalk.Begin.NoPermission -> {
                loading?.cancel()
                showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
            }
            is CapsuleTalk.Begin.Unavailable -> {
                loading?.cancel()
                showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
            }
        }
    }

    /** 完了: リリースポイントを押してカードを送信または開きます。はっきりと聞こえなかったり、間違いをしたりすると、カプセルが何かを言います (その言葉は入力フィールドと同じです)。 */
    private fun spoken(drop: CapsuleVoice.Drop, state: HoldToTalk.State) {
        val idleJob = talkIdle
        talkIdle = null
        when (state) {
            is HoldToTalk.State.Done -> scope.launch {
                idleJob?.let { idle = it.await() }
                if (drop == CapsuleVoice.Drop.Edit) editSpoken(state.text) else sendSpoken(state.text)
            }
            is HoldToTalk.State.Problem -> {
                idleJob?.cancel()
                when (val why = state.why) {
                    null -> showNote(VoiceCopy.NO_MATCH, CapsuleKind.Failed, opensMika = false, NOTE_SHORT_MS)
                    SpeechFailure.NoPermission -> showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                    is SpeechFailure.Unavailable -> showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                    else -> showNote(VoiceCopy.failure(why), CapsuleKind.Failed, opensMika = false, NOTE_LONG_MS)
                }
            }
            else -> idleJob?.cancel()
        }
    }

    /**
     * リリースして直接送信: クリックすると展開されるカード内のダイアログを送信します (ダブルクリック送信画面と同じ設定)。カプセルは「中断/応答/送信:...」と点滅します。
     * カードに入力ボックス (同意と承認が必要) がない場合、カードは送信されません。カードを展開し、まず入力ボックスに単語を入力します。
     */
    private fun sendSpoken(text: String) {
        val target = CapsuleCards.of(model.cardRuns(), follow, idle)
        val kind = target?.let(CapsuleCards::input)?.kind
        if (target == null || kind == null) {
            open()
            inputUi = inputUi.withSpoken(text)
            refresh()
            return
        }
        submit(target, kind, text, CapsuleCards.conversationOf(target))
        showNote(CapsuleVoice.sent(kind, text), CapsuleKind.Done, opensMika = false, NOTE_SENT_MS)
        refresh()
    }

    /** [変更] までドラッグして放します。カードを展開すると、単語が入力ボックスに表示され、キーボードがポップアップし、変更を加えた後に送信します。 */
    private fun editSpoken(text: String) {
        open()
        inputUi = inputUi.withSpoken(text)
        refresh()
        if (card?.let(CapsuleCards::input) != null) openInput()
    }

    private fun showNote(text: String, kind: CapsuleKind, opensMika: Boolean, ms: Long) {
        resetTaps()
        note = CapsuleNote(text, kind, opensMika, ++noteCount)
        wake()
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(ms)
            note = null
        }
    }

    private fun clearNote() {
        noteJob?.cancel()
        note = null
    }

    /** カプセル上で文が点滅したら、それをクリックします。「Mika を開く」と表示されている場合は、開きます。それ以外の場合は、文を閉じて通常どおりクリックします。 */
    private fun tapCapsule() {
        val n = note
        clearNote()
        if (n != null) {
            resetTaps()
            if (n.opensMika) open(null)
            return
        }
        val touch = capsuleTouch
        capsuleTouch = null
        val age = touch?.let { SystemClock.uptimeMillis() - it.at }
        if (touch == null || age == null || age !in 0..taps.timeoutMs) {
            // 読上げの onClick は MotionEvent を持たない明示的な単押しとして扱います。
            resetTaps()
            expand()
            return
        }
        tap(touch)
    }

    // ---------- タップとカード ----------

    /** 同じ入力元が近い位置を続けて触れた場合だけダブルタップとして扱います。 */
    private fun tap(touch: Touch) {
        if (capturing) return
        when (taps.tap(touch.at, touch.x, touch.y, touch.source)) {
            DoubleTap.Tap.Double -> {
                tapJob?.cancel()
                previewScreen()
            }
            DoubleTap.Tap.First -> {
                tapJob?.cancel()
                tapJob = scope.launch {
                    delay(taps.timeoutMs)
                    if (taps.settle(SystemClock.uptimeMillis())) expand()
                }
            }
        }
    }

    /** カードを展開します (アイドル アイコンはアイドル カードです)。拡張されるたびに、アイドル カードは、最後のコピーを取得せずに、ユーザーが現在どの会話に参加しているかに応じて再読み取りされます。 */
    private fun expand() {
        val v = view ?: return
        if (v.kind == CapsuleKind.Idle) {
            openIdle()
            return
        }
        open()
        refresh()
        scope.launch {
            val fresh = loadIdle()
            if (fresh != idle) {
                idle = fresh
                refresh()
            }
        }
    }

    /** カードを展開します。まず、現時点でのカードの位置に注目してください。カードはそこから成長します。 */
    private fun open() {
        if (!expanded) anchorY = placedY + (window?.height ?: 0) / 2
        expanded = true
        closing = false
        syncWindowVisibility()
    }

    /** ウィンドウの外側をクリックしてください。拡大された画面を閉じてから、カードを収納してください。 */
    private fun outside() {
        when {
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    private fun freshChat(fresh: Boolean) {
        idle = idle?.copy(fresh = fresh)
        refresh()
    }

    /** カードを回収します: 入力ボックスはすぐに閉じられます (フォーカスは下のアプリに戻ります)。カードは、その位置 ([closed]) に格納されるまで格納されません。アニメーションがオフになると、直接撤回されます。 */
    private fun collapse() {
        if (!expanded) return
        closeInput()
        if (closing) return
        if (animationsOff(context) || card == null) {
            resetCard()
            refresh()
            return
        }
        closing = true
        syncWindowVisibility()
    }

    private fun closed() {
        if (!closing) return
        resetCard()
        refresh()
    }

    /** カードのスケーリングの原点：カードがドッキングされている側、高さはカード上のカードの中心の位置です（カードを超える場合は上下の辺を取ります）。 通知上の */
    private fun cardOrigin(size: Size): TransformOrigin {
        val y = if (size.height > 0f) ((anchorY - cardTop) / size.height).coerceIn(0f, 1f) else 0f
        return TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, y)
    }

    private fun runFor(key: String): TaskRun? = tasks.runs.value.firstOrNull { it.key == key }

    private val actions = object : CardActions {
        override fun collapse() = this@CapsuleController.collapse()
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) {
            // と「処理します」には、この待機の数が記載されています。置き換えられた古い待機をクリックしてもカウントされません。
            TaskService.takeOver(context, waitKey = runFor(runKey)?.takeover?.waitKey, runKey = runKey)
            collapse()
        }
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMika(conversationId: String) {
            open(conversationId)
            collapse()
        }
        override fun zoom(on: Boolean) = this@CapsuleController.zoom(on)
        override fun openInput() = this@CapsuleController.openInput()
        override fun draft(text: String) {
            inputUi = inputUi.copy(draft = text)
        }
        override fun send() = this@CapsuleController.send()
        override fun freshChat(fresh: Boolean) = this@CapsuleController.freshChat(fresh)
        override fun loadEarlier() = this@CapsuleController.loadEarlier()
        override fun captureScreen() = captureForCard()
        override fun dropScreen() {
            inputUi = inputUi.copy(screen = null)
        }
        override fun closeKeptPage() {
            keeper.close(KeptPageClose.User)
            collapse()
        }
        override fun hideCapsule() {
            // 設定と同じスイッチをオフにし、CapsuleModel に従って閉じます。タスクと通知は変更しません。
            flags.setCapsuleMode(CapsuleMode.Off)
            android.widget.Toast.makeText(context, CardCopy.hiddenHint(), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** カード ウィンドウ: 展開するとカードが表示されますが、折りたたむと何も表示されません (ウィンドウにはサイズがありません)。 */
    @Composable
    private fun CardOverlay() {
        val c = card ?: return
        MikaTheme(reduceMotion = animationsOff(context)) {
            val height = if (cardHeight > 0) with(LocalDensity.current) { cardHeight.toDp() } else Dp.Unspecified
            OverlayMotion(open = !closing, origin = ::cardOrigin, onClosed = ::closed, onOpened = {
                cardOut = true
                syncWindowVisibility()
            }) {
                CapsuleCard(c, preview, zoomed, actions, { elapsed(it) }, input = inputUi, thread = thread, height = height, onHandle = ::onHandle)
            }
        }
    }

    /** パネル ウィンドウ: 話すときに画面全体を覆うレイヤーを押し、この時点の星の位置に固定します。通常は何もありません。 */
    @Composable
    private fun VoicePanelOverlay() {
        if (!talk.active) return
        val a = windowOn?.overlays()?.area()
        val top = placedY + room()
        val bottom = placedY + (window?.height ?: 0) - room()
        MikaTheme(reduceMotion = animationsOff(context)) {
            CapsuleVoicePanel(talk, top, bottom.coerceAtLeast(top), a?.insetTop ?: 0, a?.insetBottom ?: 0)
        }
    }

    /** 横の窓には錠剤、カプセル、またはストリップが入っています。カードが出てくると空になり、カードが引っ込むと同時に横から錠剤が出てきます。カプセル上で文が点滅しているときにその文を表示します。 */
    @Composable
    private fun CapsuleOverlay() {
        val shown = view ?: return
        if (card != null && cardOut && !closing) return
        val n = note
        val v = if (n == null || shown.kind == CapsuleKind.HandedOver) shown
            else shown.copy(kind = n.kind, text = n.text, detail = null, count = 0, needCount = 0, pill = true, widthEpoch = -n.id)
        val talking = talk.active && !talk.released
        MikaTheme(reduceMotion = animationsOff(context)) {
            OverlayMotion(open = true, origin = { TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, 0.5f) }, onClosed = {}) {
                CapsuleContent(
                    v,
                    onTap = ::tapCapsule,
                    onSendScreen = ::previewScreen,
                    flash = flash,
                    onStop = { v.runKey?.let { TaskService.cancel(context, it) } },
                    onHandBack = { v.runKey?.let { TaskService.handBack(context, runKey = it) } },
                    side = dock.side,
                    tapLabel = when {
                        n?.opensMika == true -> tr(R.string.capsule_voice_open)
                        v.kind == CapsuleKind.Idle -> CapsuleCopy.TALK
                        else -> CapsuleCopy.EXPAND
                    },
                    onSwapSide = if (v.kind == CapsuleKind.HandedOver) null else ::swapSide,
                    alpha = alpha,
                    holding = talking && talk.byTouch,
                    onTalk = if (canTalk()) ::toggleTalk else null,
                    talkLabel = tr(if (talking && !talk.byTouch) R.string.capsule_voice_send else R.string.capsule_voice_talk),
                    onCompleteStop = ::openCompleteStop,
                )
            }
        }
    }

    /** 経過時間。1 秒ごとに更新されます。経過時間を表示しない場合はnull。 */
    @Composable
    private fun elapsed(startedAt: Long?): String? {
        if (startedAt == null) return null
        val now by produceState(System.currentTimeMillis(), startedAt) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000L - value % 1_000L)
            }
        }
        return ChatCopy.clock(now - startedAt)
    }

    /** 美香の会話（通知と同じ入り口）に戻ります。 */
    private fun open(conversationId: String?) {
        runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
            .onFailure { Log.w(TAG, "could not open Mika from capsule", it) }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    private fun reasonLabel(reason: String): String? = when (reason) {
        "captcha" -> R.string.takeover_reason_captcha
        "login" -> R.string.takeover_reason_login
        "access_blocked" -> R.string.takeover_reason_access_blocked
        "payment" -> R.string.takeover_reason_payment
        // モデルに記述されている場合、「I need you:」の後に「I need you to決定」が繰り返されます。
        else -> null
    }?.let(context::getString)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "MikaCapsule"
        /** 表示されるカプセルは、画面の左右、ステータス バー、および下部から離れた位置にあります。 */
        const val EDGE_MARGIN_DP = 12
        const val TOP_GAP_DP = 8
        /** カプセル、シャドウ エッジに沿ったウィンドウの高さ (高さを比例的に設定する場合に使用)。 */
        const val PILL_DP = 40 + 16
        const val BAR_DP = 48 + 16
        const val SNAP_MS = 220L

        const val ROTATE_SETTLE_MS = 300L
        /** 話すとき、2つの着地点がある程度拡張されています。 */
        /** カプセル上のメッセージの点滅が止まるまでにどれくらい時間がかかりますか: 送信済み、はっきりと聞こえない、エラーまたは利用不可。 */
        const val NOTE_SENT_MS = 1_500L
        const val NOTE_SHORT_MS = 2_000L
        const val NOTE_LONG_MS = 3_500L
        /** スクリーンショットを撮る前に、カード内のインプットメソッドをしまってください。長くてもこのくらい待ちます。 */
        const val IME_GONE_MS = 700L
        const val IME_POLL_MS = 30L
    }
}

/** 一颗和卡片两个窗口此刻该不该显示;卡片收尾完成后无论动画停在哪一帧都不能再显示。 */
internal data class CapsuleWindowVisibility(val capsule: Boolean, val card: Boolean)

internal fun capsuleWindowVisibility(expanded: Boolean, cardOut: Boolean, closing: Boolean): CapsuleWindowVisibility =
    CapsuleWindowVisibility(capsule = !expanded || !cardOut || closing, card = expanded)

/** カプセル上で文が一時的に点滅します。 [kind] は設定の前のマークです (設定が完了している場合はチェック マーク、未完了の場合は感嘆符です)。 [opensMika] クリックしてミカを開きます。 [id] 各文は異なります (クリックするとカプセルの幅がリセットされます)。 */
private data class CapsuleNote(val text: String, val kind: CapsuleKind, val opensMika: Boolean, val id: Int)
