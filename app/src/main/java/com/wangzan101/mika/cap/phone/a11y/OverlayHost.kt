package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.i18n.AppLanguage
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.view.KeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * フローティング レイヤー ウィンドウが配置される場所: [gravity] は [android.view.Gravity] の組み合わせ、[x]/[y] はそのエッジから離れたピクセルです。
 * 座標は、画面全体 (ステータス バー、ノッチ、下部のジェスチャ バーを含む) に基づいて計算されます。回避する場所は、発信者が [OverlayArea] を押して決定します。
 */
data class OverlayPlacement(val gravity: Int, val x: Int = 0, val y: Int = 0)

/** 現時点での画面の幅と高さ (現在の向きによる)、およびステータス バー、ノッチ、ナビゲーション バー、またはジェスチャ バーが占める 4 辺のピクセル数。 */
data class OverlayArea(
    val width: Int, val height: Int,
    val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0,
)

/**
 * ウィンドウのドラッグ: 指を押して、ドラッグする前にシステムのタッチしきい値を超えて移動します。このとき、ウィンドウ内で押されているボタンはキャンセルされ、クリックとみなされなくなります。
 * では、ウィンドウの一部 (ハンドル、[OverlayWindow.draggable] を参照) のみをドラッグでき、他の場所にスライドしても内部のコンテンツ (スクロールなど) に引き継がれます。
 * [move]/[end] は、指を押してから画面上で指が移動したピクセル数を示します (画面座標に基づいて計算され、指が動いてもウィンドウは揺れません)。
 */
interface OverlayDrag {
    fun start()
    fun move(dx: Float, dy: Float)
    fun end(dx: Float, dy: Float)
}

/**
 * 長押し: 押した後、ホールドがいっぱいになるまで指は動きません (タッチしきい値を超えて動きません)。長押しとみなされます。最初にしきい値を超えた場合はドラッグされ、長押しとはみなされなくなります ([HoldGesture] を参照)。
 * 長押しして開始すると、ウィンドウはドラッグされず、画面上の指の位置 (画面座標) がここに渡されます。この時点でウィンドウ内で押されているボタンはキャンセルされ、クリックは確立されなくなります。
 */
interface OverlayHold {
    fun start(rawX: Float, rawY: Float)
    fun move(rawX: Float, rawY: Float)
    fun end(rawX: Float, rawY: Float)
    /** システムはこの一連のタッチを取り除きました (たとえば、ウィンドウが削除された、電話がかかってきたなど)。この長押しはカウントされません。 */
    fun cancel()
}

/**
 * 一連のタッチは、クリック、ドラッグ、または長押しとしてカウントされます (純粋な論理、クロックは発信側によって管理されます): [down]、その後に [Kind.Tap] (クリックの場合もあります)。
 * 指が押圧点から [slopPx] 以上離れている場合は、[Kind.Drag] となります。または、指がタップ点 ([timeout]) に達すると、[Kind.Hold] になります。ドラッグしても長押ししても変化しません。次回は[down]になります。
 */
class HoldGesture(private val slopPx: Float) {
    enum class Kind { Tap, Drag, Hold }

    var kind = Kind.Tap
        private set

    fun down(): Kind {
        kind = Kind.Tap
        return kind
    }

    /** 指は押された位置から [dist] ピクセル離れています。 */
    fun move(dist: Float): Kind {
        if (kind == Kind.Tap && dist > slopPx) kind = Kind.Drag
        return kind
    }

    fun timeout(): Kind {
        if (kind == Kind.Tap) kind = Kind.Hold
        return kind
    }
}

/**
 * ユーザー補助サービスを使用して、ユーザーの画面 (ホーム画面) にフローティング レイヤーを描画します。ウィンドウ タイプはアクセス可能なフローティング レイヤーであり、「他のアプリケーションの上に表示」権限は必要ありません。
 * は、ウィンドウの追加、位置の変更、ドラッグ、キーボードを受け入れるかどうかの切り替え、ウィンドウの破棄などのメカニズムのみを制御します。何が描画されるかは、呼び出し元によって指定された Compose コンテンツによって決まります。
 * のウィンドウはコンテンツと同じ大きさしかなく、コンテンツ以外の領域は通常通り次のアプリに任せられます。
 * サービスが切断されると、[closeAll] が [ScreenAccessibility] によって呼び出され、それに応じてウィンドウが削除されます。メインスレッドでのみ使用されます。
 */
class OverlayHost internal constructor(private val service: ScreenAccessibility) {
    private val windows = mutableListOf<OverlayWindow>()
    private val injectedGestures = InjectedGestures { isolate ->
        forEachGestureWindow(windows.toList()) { it.isolateInjectedGestures(isolate) }
    }

    /**
     * コンテンツのサイズをカバーするフローティング レイヤー ウィンドウを追加します。通常フォーカスを取得せず、クリックできない場所は、通常どおり次のアプリに任せられます。
     * [onOutside] 与えられた場合、ウィンドウの外側を押すと通知されます（カードを閉じるために使用されます）。 [touchable] が false の場合、ウィンドウは描画のみを行い、タッチを受け取りません (上部を押すことは、下のアプリを押すことと同等です)。
     * を追加できない場合 (サービスが切断されており、システムが追加を許可していない場合)、null が返され、エラーはスローされません。
     */
    fun open(
        placement: OverlayPlacement,
        onOutside: (() -> Unit)? = null,
        touchable: Boolean = true,
        content: @Composable () -> Unit,
    ): OverlayWindow? {
        val owners = OverlayOwners()
        val frame = OverlayFrame(service).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            addView(
                ComposeView(AppLanguage.wrap(service)).apply { setContent(content) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            this.onOutside = onOutside
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (touchable && !injectedGestures.isolated) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 画面全体を押して、システムがウィンドウをステータス バーとナビゲーション バーに押し込むのを防ぎます。避けるべき場所は、area() を押すことによって呼び出し側によって決定されます。
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            place(placement)
        }
        val window = OverlayWindow(this, frame, params, owners, touchable)
        owners.start()
        return try {
            wm().addView(frame, params)
            windows += window
            window
        } catch (e: RuntimeException) {
            // サービスが切断されたばかりのときにウィンドウ トークンの有効期限が切れているか、システムがこのタイプのウィンドウを許可していません。フローティング レイヤーが存在しないものとして扱われます。
            Log.w(TAG, "overlay unavailable", e)
            owners.destroy()
            null
        }
    }

    /** この時点で、画面のサイズと、システムバーと前髪が占める四辺の部分（画面を回転すると変わります）。読み取れない場合は、ステータスバーの上下を共通の高さのまま、表示サイズに戻します。 */
    fun area(): OverlayArea = runCatching {
        val m = wm().currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        OverlayArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
    }.getOrElse {
        val d = service.resources.displayMetrics
        val bar = (FALLBACK_INSET_DP * d.density).toInt()
        OverlayArea(d.widthPixels, d.heightPixels, insetTop = bar, insetBottom = bar)
    }

    /**
     * ユーザー画面上の入力メソッドの上端 (画面座標)。ポップアップが発生しない場合は null。入力メソッドがどのアプリに対してプレイされるかに関係なく (ミカ自身のフローティング レイヤーを含む)、
     * カプセルとカードはそれに応じて邪魔になりません。ウィンドウが変わるとすぐに再読み込みされます。ウィンドウが読み取れない場合は、ポップアップしなかったものとみなされます。
     */
    fun imeTop(): Flow<Int?> = service.windowChanges().onStart { emit(Unit) }
        .map { readImeTop() }
        .distinctUntilChanged()

    private fun readImeTop(): Int? = runCatching {
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also(w::getBoundsInScreen) }
            ?.takeIf { !it.isEmpty }?.top
    }.getOrNull()

    /** 注入中はすべての浮動レイヤーをタッチ対象から外し、終了後は各ウィンドウの元の設定へ戻します。ネスト時は最外側で復元し、キャンセル後もシステムに渡したジェスチャーの終了を待ちます。 */
    suspend fun <T> passInjectedGesturesThrough(block: suspend () -> T): T = injectedGestures.passThrough(block)

        /** すべてのフローティング レイヤーを削除します (サービスが切断されている場合)。 */
    fun closeAll() {
        windows.toList().forEach(OverlayWindow::close)
    }

    internal fun wm(): WindowManager = service.getSystemService(WindowManager::class.java)

    internal fun forget(window: OverlayWindow) {
        windows.remove(window)
    }

    private companion object {
        const val TAG = "MikaOverlay"
        const val FALLBACK_INSET_DP = 24
    }
}

/** [OverlayHost.open] ウィンドウが追加されました。それを破棄して再度呼び出しても、何も起こりません。 */
class OverlayWindow internal constructor(
    private val host: OverlayHost,
    private val frame: OverlayFrame,
    private val params: WindowManager.LayoutParams,
    private val owners: OverlayOwners,
    private val touchable: Boolean,
) {
    var isOpen = true
        private set

    /** ウィンドウの現在の幅と高さ（ピクセル）; まだレイアウトされていなければ 0。 */
    val width: Int get() = frame.width
    val height: Int get() = frame.height

    /** 位置を変える。 */
    fun place(placement: OverlayPlacement) {
        if (!isOpen || params.gravity == placement.gravity && params.x == placement.x && params.y == placement.y) return
        params.place(placement)
        update()
    }

    /**
     * ドラッグできる場合は [drag] を渡し、できない場合は null を渡す。[handle] はその領域を押したときだけドラッグする（ウィンドウ内のピクセル座標。たとえばカードのタイトルバー）、
     * null の場合はどこを押してもドラッグできる; 空の場合はどこを押してもドラッグできない。
     */
    fun draggable(drag: OverlayDrag?, handle: Rect? = null) {
        if (!isOpen) return
        frame.drag = drag
        frame.handle = handle?.let(::Rect)
    }

    /**
     * を長押しすると、[hold] を与えることができますが、null を与えることはできません。押して移動しない場合、[holdMs] ミリ秒が長押しされます ([draggable] のドラッグとは相互に排他的で、最初の移動はドラッグです)。
     * 長押しすると軽い振動とともに起動し、ウィンドウ内のボタンがキャンセルされます。
     */
    fun holdable(hold: OverlayHold?, holdMs: Long = 400) {
        if (!isOpen) return
        frame.setHold(hold, holdMs)
    }

    /**
     * 画面全体をカバーする（話すときに長押しして後ろのレイヤーを暗くするなど）、またはコンテンツを包み込むサイズに戻ります。通常、空のウィンドウがコンテンツを囲む場合、そのウィンドウにはサイズがありません。
     * は全画面キャンバスを占有しません。いっぱいになるといっぱいになります (コンテンツをラップするウィンドウ システムは、まずダイアログ ボックスの幅に基づいて決定され、コンテンツは画面全体を埋めません)。
     */
    fun setFullScreen(full: Boolean) {
        if (!isOpen) return
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        update()
    }

    /** がキーボードを受信できる場合 ([setFocusable] を参照)、ユーザーはリターン キーを押します。インプット メソッドが開いている場合、Return キーは最初にインプット メソッドを閉じますが、ここで停止することはありません。 */
    fun onBack(onBack: (() -> Unit)?) {
        if (isOpen) frame.onBack = onBack
    }

    /** ウィンドウへのタッチを [onTouch] へ渡し、透明度の復元と入力元・位置の確認に使います。 */
    fun onTouch(onTouch: ((MotionEvent) -> Unit)?) {
        if (isOpen) frame.onTouch = onTouch
    }

    /** 注入中だけタッチを無効にし、終了後は作成時の [touchable] へ戻します。 */
    internal fun isolateInjectedGestures(isolate: Boolean) {
        if (!isOpen) return
        val flags = if (touchable && !isolate) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == params.flags) return
        params.flags = flags
        update()
    }

    /** コンテンツ サイズが変更されると (カプセルがカードに置き換えられ、画面回転後に再配置されます)、[onResize] が調整され、それに応じて発信者の位置が変更されます。 */
    fun onResize(onResize: (() -> Unit)?) {
        if (isOpen) frame.onResize = onResize
    }

    /**
     * ウィンドウ全体を明示的に表示・非表示にする。内容を空にするだけでは旧フレームが残るため、
     * ウィンドウの切り替え時には可視性も切り替える。
     */
    fun setVisible(visible: Boolean) {
        if (!isOpen) return
        frame.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * はキーボードを受け取ることができますか: フローティング レイヤーに入力するときにキーボードを開き (キーボードに面する入力メソッドがポップアップします)、閉じるとすぐに閉じ、フォーカスが下のアプリに戻る必要があります。
     */
    fun setFocusable(focusable: Boolean) {
        if (!isOpen) return
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == params.flags) return
        params.flags = flags
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        update()
    }

    /**
     * まずウィンドウ内の内容を非表示にし、実際に画面から消えるまで待ってから、[block] (ユーザーの画面のスクリーンショットを撮るなど) を実行し、完了後に表示します。
     * 「本当に下がっています」: 非表示のフレームが描画されてシステムに渡された後 (フレーム送信コールバック)、シンセサイザーがこのフレームを置き換える前に [GONE_FRAMES] の更新が必要です。
     * フレーム送信コールバックが来ていません (この時点ではウィンドウは描画されていません)。最大待機時間は [GONE_WAIT_MS] です。ウィンドウ自体は残ります（位置と状態は変わりません）が、この間は透明になります。
     */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val content = frame.getChildAt(0)
        if (!isOpen || content == null) return block()
        content.visibility = View.INVISIBLE
        try {
            withTimeoutOrNull(GONE_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    frame.viewTreeObserver.registerFrameCommitCallback { if (cont.isActive) cont.resume(Unit) }
                    frame.invalidate()
                }
            }
            repeat(GONE_FRAMES) {
                suspendCancellableCoroutine { cont -> Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) } }
            }
            return block()
        } finally {
            if (isOpen) content.visibility = View.VISIBLE
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        frame.release()
        host.forget(this)
        runCatching { host.wm().removeViewImmediate(frame) }
        owners.destroy()
    }

    private fun update() {
        runCatching { host.wm().updateViewLayout(frame, params) }.onFailure { close() }
    }

    private companion object {
        /** 隠しフレームはシステムに渡され、数回の更新を待ちます。シンセサイザーは次の更新時にそれを置き換えます。 */
        const val GONE_FRAMES = 2
        const val GONE_WAIT_MS = 300L
    }
}

/**
 * は、Compose コンテンツを含むフローティング ウィンドウのルートです。ウィンドウの外側を押します ([onOutside])。ドラッグするとき ([drag])、ハンドルの内側 ([handle]、そうでない場合はウィンドウ全体) を指で押します。
 * タッチしきい値を移動すると、一連のタッチが遮断され、内部のボタンがキャンセルされます。ドラッグは画面座標に基づいて計算されます。
 * ([hold])を長押しできる場合、押すとタイマーがスタートします。移動せずに制限時間が経過した場合、一連のタッチは中断され、[hold] に渡されます (クリックとドラッグは無効になります)。
 */
internal class OverlayFrame(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null
    var onResize: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onTouch: ((MotionEvent) -> Unit)? = null
    var drag: OverlayDrag? = null
    var handle: Rect? = null
    private var hold: OverlayHold? = null
    private var holdMs = 400L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val gesture = HoldGesture(slop.toFloat())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false
    private var holding = false
    /** 長押しすると中の内容をキャンセルします。[track]を通過するとシステムキャンセルにはなりません。 */
    private var ownCancel = false
    private val holdTimer = Runnable(::holdReached)
    /** タイマーを長押しすると、メイン スレッドのハンドラーが実行されますが、View.postDelayed は実行されません。後者は、実行前にウィンドウがハングアップするまで待機する必要があります。 */
    private val timer = Handler(Looper.getMainLooper())

    init {
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onResize?.invoke()
        }
    }

    /**
     * は長押しの宛先を変更します ([hold] が null の場合、長押しはできません)。同じレシーバーが再度インストールされた場合 (再描画されるたびにインストールされます)、何も変わりません。
     * を交換または削除する場合、継続的に長押しすると、最初に古いものにキャンセルが通知され、この一連のタッチ後の持ち上げは誰にも引き継がれなくなります。そうしないと、古いものは終了を待つことはありません。
     */
    fun setHold(hold: OverlayHold?, holdMs: Long) {
        this.holdMs = holdMs
        if (hold === this.hold) return
        val old = this.hold
        this.hold = hold
        timer.removeCallbacks(holdTimer)
        if (holding) {
            holding = false
            tracking = false
            old?.cancel()
        }
    }

    fun release() {
        timer.removeCallbacks(holdTimer)
        hold = null
        holding = false
        drag = null
        handle = null
        onResize = null
        onOutside = null
        onBack = null
        onTouch = null
        tracking = false
        dragging = false
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val outside = onOutside ?: return false
            outside()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke(e)
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val back = onBack
        if (back != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e) && (dragging || holding)

    /** コンテンツ内に接続するものがなくてもドラッグできます。 */
    @SuppressLint("ClickableViewAccessibility") // ドラッグすると位置が移動するだけです。画面を読むときは、カプセルの「反対側に移動」を使用してください
    override fun onTouchEvent(e: MotionEvent): Boolean = track(e)

    /** はこの一連のタッチに従います。引き続き収集されることを示す場合は true を返します (ドラッグまたは長押しできる場合)。 */
    private fun track(e: MotionEvent): Boolean {
        if (ownCancel) return true
        val d = drag
        val h = hold
        if (d == null && h == null) {
            timer.removeCallbacks(holdTimer)
            tracking = false
            dragging = false
            holding = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastX = e.rawX
                lastY = e.rawY
                tracking = handle?.contains(e.x.toInt(), e.y.toInt()) ?: true
                dragging = false
                holding = false
                gesture.down()
                timer.removeCallbacks(holdTimer)
                if (tracking && h != null) timer.postDelayed(holdTimer, holdMs)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                lastX = e.rawX
                lastY = e.rawY
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (holding) {
                    hold?.move(e.rawX, e.rawY)
                } else if (gesture.move(hypot(dx, dy)) == HoldGesture.Kind.Drag) {
                    timer.removeCallbacks(holdTimer)
                    if (d != null) {
                        if (!dragging) {
                            dragging = true
                            d.start()
                        }
                        d.move(dx, dy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(holdTimer)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (holding) {
                    if (up) hold?.end(e.rawX, e.rawY) else hold?.cancel()
                } else if (dragging) d?.end(e.rawX - downX, e.rawY - downY)
                tracking = false
                dragging = false
                holding = false
            }
        }
        return tracking
    }

    /** 押したまま動かずに長押しすると制限時間が経過しました。内部で押されたボタンは (次のタッチ イベントを待たずに) すぐにキャンセルされ、タップもダブルクリックも無効になります。 */
    private fun holdReached() {
        val h = hold ?: return
        if (!tracking || dragging || gesture.timeout() != HoldGesture.Kind.Hold) return
        holding = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        ownCancel = true
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            ownCancel = false
            cancel.recycle()
        }
        h.start(lastX, lastY)
    }
}

private fun WindowManager.LayoutParams.place(p: OverlayPlacement) {
    gravity = p.gravity
    x = p.x
    y = p.y
}

/** Compose は、アクティビティに加えて、独自のライフサイクル、状態保存、および ViewModel ホストを構成する必要があります。ウィンドウが追加されると RESUMED に入り、ウィンドウが削除されると破棄されます。 */
internal class OverlayOwners : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        // は一度も起動されていないため (ウィンドウを追加する前に失敗しました)、CREATED に入る前に破棄する必要があります。
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
