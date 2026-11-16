package com.wangzan101.mika.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.graphics.Path
import android.app.Notification
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.uistate.ScreenEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** ミカのアクセシビリティ サービス: 操作とイベントは現在接続されているインスタンスに転送され、古いインスタンスを保持しているタスクも再接続後に復元できます。 */
open class ScreenAccessibility : AccessibilityService() {

    private class DisplayEvent(val displayId: Int, val event: AccessibilityEvent)


    override fun onServiceConnected() {
        super.onServiceConnected()
        if (com.wangzan101.mika.quit.MikaQuit.requested(this)) {
            disableSelf()
            return
        }
        // 多くのアプリは、結果を表示するテキスト コントロールを「アクセシビリティにとって重要ではない」としてマークします。読まないと結果はわかりません。設定ファイルに次のように記述されています
        // システムが古いサービス構成を使用しないように、実行時に再度入力します。
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        _instance.value = this
        // ペアリングの読取りと案内画面が参照する状態です。接続と切断を記録して診断に使います。
        Log.i(TAG, "ユーザー補助サービスが接続されました")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "ユーザー補助サービスの接続が解除されました")
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
        overlayHost?.closeAll()
        overlayHost = null
    }

    private var overlayHost: OverlayHost? = null

    /** は、ユーザー画面上にフローティング レイヤーを描画するために使用されるホストです (メイン スレッドでのみ使用されます)。サービスが切断されると、追加されたウィンドウも一緒に削除されます。 */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (instance.value !== this) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowChanges.tryEmit(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowGeneration.incrementAndGet()
        }
        val access = (application as com.wangzan101.mika.MikaApp).appAccess.current()
        if (access.denial(event.packageName?.toString()) != null) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && notifications.subscriptionCount.value > 0) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = listOfNotNull(title, body).joinToString(" ").ifBlank { event.text.joinToString(" ") }
            notifications.tryEmit(ScreenEvent("notification", event.packageName?.toString(), text.take(2_000)))
        }
        // 誰も購読しない場合、画面番号のコピーや確認は行われません。このイベントは極秘です。
        if (events.subscriptionCount.value == 0) return
        val displayId = displayOf(event)
        if (displayId == Display.INVALID_DISPLAY) return
        // API 33 次のシステムは、コールバックが返された後にイベント オブジェクトをリサイクルし、サブスクライバーは非同期で読み取る前にコピーを作成します。
        events.tryEmit(DisplayEvent(displayId, AccessibilityEvent(event)))
    }

    override fun onInterrupt() = Unit

    /** は画面上のウィンドウを指定します。画面上にウィンドウがない (または存在しない) 場合、これは空のリストです。 */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        instance.value?.windowsOnAllDisplays?.get(displayId).orEmpty()

    /** 指定された画面上で [path] に沿って 1 本指のジェスチャを実行します。完了した場合は true を返し、キャンセルされた場合または送信できない場合は false を返します。 */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        return gesture(displayId, listOf(path), durationMs)
    }

    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        dispatch(displayId, paths.map { GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1)) })

    /** ドット [count] を同じ点に接続します (1 回のジェスチャで数ストローク): 2 つのドットの間に [gapMs] を入れて、毎回 [holdMs] を押し続けます。 */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean {
        val at = Path().apply { moveTo(x, y) }
        return dispatch(displayId, (0 until count).map { i -> GestureDescription.StrokeDescription(at, i * (holdMs + gapMs), holdMs.coerceAtLeast(1)) })
    }

    private suspend fun dispatch(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val service = instance.value ?: return false
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .apply { strokes.forEach(::addStroke) }
            .build()
        suspend fun send(): Boolean = suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
        // 注入したタップ・ダブルタップ・ドラッグは他のアプリへ届け、Mika の浮動レイヤーから隔離します。
        return service.overlayHost?.passInjectedGesturesThrough { send() } ?: send()
    }

    /** ユーザーの画面 (ホーム画面) のスクリーンショットを撮った結果: 画面上にスクリーンショット防止ウィンドウがあるか、スクリーンショットを撮ることができません (エラー コード)。 */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * ホーム画面のスクリーンショットを撮ります (ユーザーが見ている画面には、ミカ自身のフローティング レイヤーが含まれています。それを非表示にするかどうかは呼び出し元が決定します)。システムでは、2 つのスクリーンショットを少なくとも一定の間隔をあけて撮影するように制限されています。
     * 速すぎる場合は、1 秒待ってから再度カットしてください。画像はソフトウェア ビットマップです (ピクセルとコードを読み取ることができます)。
     */
    suspend fun takeMainScreenshot(): MainShot {
        val first = screenshotOnce()
        if (first !is MainShot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        kotlinx.coroutines.delay(SCREENSHOT_INTERVAL_MS)
        return screenshotOnce()
    }

    private suspend fun screenshotOnce(): MainShot = suspendCancellableCoroutine { cont ->
        val service = instance.value
        if (service == null) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(MainShot::Ok) ?: MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                // 画面にはスクリーンショット防止ウィンドウがあります。Android 14 からは特別なエラー コードが発生します。以前のシステムのスクリーンショットは黒であり、発信者は画面を見て判断できます。
                val secure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                if (cont.isActive) cont.resume(if (secure) MainShot.Secure else MainShot.Failed(errorCode))
            }
        })
    }

    /**
     * 指定された画面でアクセシビリティ イベントが発生しました。チャネルはインスタンス間で保持され、現在接続されているサービスのみがイベントを発行します。
     */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent> =
        events.filter { it.displayId == displayId }.map { it.event }

    fun notificationEvents(): Flow<ScreenEvent> = notifications

    /** ウィンドウが変更されたことを知らせます ([windowChanges] を参照)。チャネルはサービス インスタンス間で保持されます。 */
    fun windowChanges(): Flow<Unit> = windowChanges

    /**
     * イベントが配置されている画面。 API 33 以降、イベントには独自の画面番号が付属します。以前のシステムでは、イベントのウィンドウ番号に従って、各画面ウィンドウでイベントが検索されます。
     * が見つからない場合 (たとえば、ウィンドウ番号の通知イベントがない場合) は破棄されます。
     */
    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1) return Display.INVALID_DISPLAY
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        }
        return Display.INVALID_DISPLAY
    }

    companion object {
        private const val EVENT_BUFFER = 64
        /** システムが必要とする 2 つのスクリーンショットの間隔 (元の Android バージョンは 1 秒)。 */
        private const val SCREENSHOT_INTERVAL_MS = 1_000L

        // ホット フローを維持する: 呼び出し元は UNDISPATCHED を使用して、最初にサブスクライブしてから操作します。サービスの切り替えによる非同期のサブスクリプション ウィンドウを導入しないでください。
        // イベントは観察のみを目的としています。サブスクライバがそれを処理できない場合、最も古いものは破棄され、システム コールバックはブロックされません。
        private val events = MutableSharedFlow<DisplayEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        /** ウィンドウが変更されました (追加、削除、移動、入力メソッドのポップアップと折りたたみを含む)。これは単なる信号であり、加入者は自分でウィンドウを読み取ることができます。 */
        private val windowChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val windowGeneration = java.util.concurrent.atomic.AtomicLong()

        /** は、ウィンドウが追加、削除、またはページングされるたびに 1 ずつ増加します (画面上のすべてがカウントされます)。これは、ウィンドウが前回から変更されたかどうかを判断するためにのみ使用されます。値自体には意味がありません。 */
        fun windowGeneration(): Long = windowGeneration.get()
        private val notifications = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private const val TAG = "MikaA11y"
        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** 接続されたサービス インスタンス。サービスが開始されていない場合、または切断されている場合は null。 */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
