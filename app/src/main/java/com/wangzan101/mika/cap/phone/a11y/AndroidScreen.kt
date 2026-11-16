package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.cap.phone.GesturePacing
import com.wangzan101.mika.core.cap.phone.TypingPacing
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.uistate.AppWait
import com.wangzan101.mika.core.uistate.AppCopy
import com.wangzan101.mika.core.uistate.AppProfiles
import com.wangzan101.mika.core.uistate.LaunchChoice
import com.wangzan101.mika.core.uistate.LaunchChooser
import com.wangzan101.mika.core.uistate.OpenedElsewhere
import com.wangzan101.mika.core.uistate.UnseenField
import com.wangzan101.mika.core.uistate.normalize
import com.wangzan101.mika.core.uistate.Relocation
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenEvent
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.SystemCommandResult
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.WheelOutcome
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import com.wangzan101.mika.core.visual.FrameGrid
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.uistate.TextField
import com.wangzan101.mika.core.uistate.typeAndVerify
import com.wangzan101.mika.core.uistate.relocate
import com.wangzan101.mika.core.uistate.RELOCATE_MAX_SHIFT
import com.wangzan101.mika.cap.phone.display.FrameMath
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [ScreenPort] の Android 実装: エージェント仮想画面のバリアフリー ウィンドウを読み取り、この画面上で操作します。
 * 各アクションの後でインターフェイスが静まるのを待ち、コントロール ツリーのフィンガープリント (画面で補足) を使用してそれが有効になったかどうかを確認し、フロント デスクが他のアプリによって切り離されていないかどうかを確認します。
 * 仮想画面は、使用されるとき (最初の launch_app、またはシステム コマンド、イベント ツール) にのみ構築されます。構築される前は、画面の読み取りは空白の画面であり、他のアクションは失敗します。 [NO_APP_OPEN]、これらのアクションは単独で画面を構築しません。
 * 画面が作成された後は、アプリが開かれたことを意味するものではありません。システム コマンドのみを実行するタスクの画面は空です。
 */
class AndroidScreen(
    private val a11y: ScreenAccessibility,
    private val shell: IMikaShell,
    private val display: LazyAgentDisplay,
    /** は、クリップボードが一時的にミカのテキストに変更された場合は true、復元後は false になります。タスク サービスはこれを記録し、プロセスが予期せず終了したときに次回復元を開始します。 */
    private val clipHeld: (Boolean) -> Unit = {},
    /** クリップボードはグローバルです。複数のタスクが同時に実行されている場合、ペースト ポケットは同じロックを共有します。 */
    private val clipLock: Lock = ReentrantLock(),
    /** は並列タスクに当てはまります。アプリを開く前に、Mika の別の画面でアプリが他のタスクに使用されているかどうかを確認してください。 */
    private val checkOtherTasks: Boolean = false,
    /** 並列タスクが発生すると、このパッケージはこのタスクのために占有されます。他のタスクがすでにそれを占有している (そしてまだ使用している) 場合、false が返されます。 */
    private val reserve: (String) -> Boolean = { true },
    /** は [reserve] と同じです。別のユーザー (ユーザー番号) にインストールされた共有を占有します。元の共有とは別に占有されます。 2 つのタスクはそれぞれ 1 つの共有を使用できます。 */
    private val reserveCopy: (String, Int) -> Boolean = { _, _ -> true },
    /** クリップボードの変更に成功したら、ユーザーの元のテキストと、暗号化と保持のためにミカによって入力されたテキストを渡します (プロセスが強制終了された後に、それに応じて復元します)。 */
    private val clipSaved: (original: String, ours: String) -> Unit = { _, _ -> },
    /** アプリは現在ユーザーの手にあります (管理下にあります)。この時点でホーム画面で開かれているのはユーザーの業務であり、移動することはできません。 */
    private val handedOver: () -> Boolean = { false },
    /** ジェスチャーのリズム: 指を落とす前の一時停止、保持時間、着地点の偏差、スライドの弧と速度 ([GesturePacing] を参照)。 */
    private val pacing: GesturePacing = GesturePacing.human(),
    /** このパッケージ名のアプリでの入力リズム ([TypingPacing] を参照)。デフォルトでは、全文を一度に書き込みます。 */
    private val typing: (String?) -> TypingPacing = { TypingPacing.Instant },
    /** が起動を発行する前に: [packageName] が開かれます ([user] のユーザー コピーにインストールされます。null は元のコピーです)。したがって、タスク サービスは静かに開始され、最初は静かです。 */
    private val beforeOpen: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    /** は起動を拒否され、開きませんでした。事前に作成した [beforeOpen] を撤回してください。 */
    private val notOpened: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    override val appAccess: com.wangzan101.mika.core.access.AppAccessPolicy = com.wangzan101.mika.core.access.AppAccessPolicy.ALLOW,
    /** 並列タスク: 占有登録に従って、このタスクが現時点でこのパッケージを占有できるかどうか ([user] の共有。null は元の共有)。チェックだけで会計はしません。 */
    private val appFree: (packageName: String, user: Int?) -> Boolean = { _, _ -> true },
    /**
     * 並列タスク中: 他のタスクがこのパッケージをリリースするまで、その時間まで待ちます。 [free] は完全な判定です (他の画面には存在せず、登録では誰も占有していません)。
     * タスクサービスは「誰が誰を待っているか」を登録し、お互いを認識します。待機中、インターフェイスには「別のタスクがアプリを終了するのを待機しています」と表示されます。
     */
    private val awaitApp: suspend (packageName: String, user: Int?, timeoutMs: Long, free: suspend () -> Boolean) -> AppWait =
        { _, _, _, _ -> AppWait.Unsupported },
) : ScreenPort {
    /** -1 画面が構築されていない場合。画面が構築されたことを確認してから使用してください。 */
    private val displayId get() = display.current?.displayId ?: -1
    /** 画面が構築されました（アプリが開いたわけではありません）。 */
    private val hasDisplay get() = display.current != null
    private val screenWidth get() = display.current?.width ?: display.width
    private val screenHeight get() = display.current?.height ?: display.height
    private val ids = NodeIds()

    /** 最新の launch_app によって正常に開かれたパッケージは、削除されたかどうかを判断するために使用されます。 */
    @Volatile private var targetPackage: String? = null

    /** 最後にアプリを開いたとき、システムは「どれを開きますか?」と尋ねました。 */
    @Volatile private var chooserPackage: String? = null

    /**
     * エージェントはアプリとそのタスク番号 (開いたときに先頭にない場合は -1) を使用しています。それに応じてタスク サービスがミュートされ、移動されたかどうかを監視します。
     * [userId] は、タスクが属するユーザーです (2 つのコピーがインストールされている場合、どちらがインストールされているかがわかります)。読み取れない場合は -1 です。
     */
    data class TargetApp(val packageName: String, val taskId: Int, val userId: Int = -1)

    private val _target = MutableStateFlow<TargetApp?>(null)
    val target: StateFlow<TargetApp?> = _target.asStateFlow()

    private val systemPickers: Set<String> by lazy { SystemPickers.resolve(a11y.packageManager) }

    /** システム設定が配置されているパッケージ (Settings.ACTION_SETTINGS に従って解析): ターゲット アプリによって自身のタスク内で開かれた設定ページは、切り取られたとは見なされません。 */
    private val settingsPackage: String? by lazy { SystemPickers.settingsPackage(a11y.packageManager) }

    private fun windows() = a11y.windowsOn(displayId)
    /**
     * アプリケーション アクセス用のウィンドウ プローブ: 画面上の各アプリ ウィンドウの所有権。ルート ノードのパッケージ名のみを読み取ることができ、コンテンツを読み取ることはできません。ルート ノードを読み取れない項目は null (所有権が不明) です。
     * アプリウィンドウがない場合、それは最上位のタスクを持つアプリであり、アプリウィンドウがない場合、画面は空（空のリスト）です。このルールは、画面読み取り結果のウィンドウの所有権と一致します。
     */
    suspend fun accessPackages(): List<String?> = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext emptyList()
        val packages = windows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { it.root?.packageName?.toString() }
        packages.ifEmpty { listOfNotNull(foregroundTaskPackage()) }
    }

    private fun read() = ScreenReader.read(windows(), screenWidth, screenHeight)
    private fun treeHash() = ScreenReader.hash(windows())

    /** は、システム コマンドまたはイベント ツールを明示的に使用するときにオンデマンドで画面を作成できます。共通タスクは、アプリを初めて開くまで作成できません。 */
    suspend fun ensureDisplay() { display.get() }

    // ---------- 画面の読み取り ----------

    override suspend fun snapshot(): ScreenSnapshot = capture(register = true)

    // はシリアル番号登録リストに含まれていません: モデルが所有している最新のリファレンスはまだ有効です
    override suspend fun peek(): ScreenSnapshot = capture(register = false)

    private suspend fun capture(register: Boolean): ScreenSnapshot = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext ScreenSnapshot(snapshotId(), null, emptyList(), screenWidth, screenHeight, hasWindow = false)
        // 最後のアクションがユーザーのホーム画面に表示されるまでに時間がかかりました。最初に移動してから読み取ります（効果を判定する画面ではなく、モデルを読み取る画面でのみ実行してください）
        val late = if (register) lateSpill() else null
        val r = read()
        val after = treeHash()
        val foreground = if (r.topPackage == null) foregroundTaskPackage() else null
        val snap = NodeSnapshot(snapshotId(), r.entries)
        if (register) ids.put(snap)
        ScreenSnapshot(
            snapshotId = snap.id,
            packageName = r.topPackage,
            elements = r.entries.map { it.element },
            width = screenWidth,
            height = screenHeight,
            changedDuringRead = after != r.hash,
            hasWindow = r.anyAppWindow,
            unreadableApp = foreground,
            windows = r.windows,
            // はまだホーム画面でユーザーが選択するのを待っています: とにかく言ってください
            opened = late?.opened ?: _userPick.value?.let { OpenedElsewhere(it.packageName, it.app, pulledBack = false, picker = true) },
            // 黑帧只说明画面不可用,不能据此声称窗口防截屏;读屏元素仍可作为降级路径。
            captureError = if ((r.anyAppWindow || foreground != null) && frameServable() && frameBlack() == true)
                ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE else null,
        )
    }

    /**
     * ウィンドウの内容を読み取れない場合 (セキュリティ ウィンドウ、起動画面、他の場所に表示されている場合)、エージェント画面の一番上のタスクを押して、どのアプリがフォアグラウンドにあるかを確認します。
     * 画面がまだ構築されていない場合、タスクがない場合、または質問できない場合、値は null です。
     */
    private fun foregroundTaskPackage(): String? {
        if (!hasDisplay) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        return runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Mika 画面上の一番上のアプリ (パッケージ名、タスク、ユーザー) は、変更するたびに 1 回表示されます。最初はサブスクリプション時に、次にウィンドウが変更されます (新しいページが開き、
     * 別のアプリから別のアプリをクリックし、別のコピーをアップロードして) 再読み込みしました。画面が構築されておらず、タスクがない場合は付与されません。呼び出し元のスレッド上の組み込みヘルパーに問い合わせます。
     */
    fun foregroundApps(): Flow<TargetApp> = flow {
        emit(Unit)
        emitAll(a11y.windowChanges())
    }.conflate().mapNotNull {
        if (!hasDisplay) return@mapNotNull null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return@mapNotNull null
        val pkg = runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TargetApp(pkg, task, userOf(task))
    }.distinctUntilChanged { a, b -> a.packageName == b.packageName && a.userId == b.userId }

    override suspend fun screenshotPng(): ByteArray? = withContext(Dispatchers.IO) {
        // 黑图没有可用画面信息,也不能凭它区分安全窗口和合成失败;不要交给模型或 OCR。
        if (!frameServable() || frameBlack() == true) return@withContext null
        display.current?.latestFramePng()
    }

    /** 最近一次启动开始的单调时钟时刻（纳秒），与虚拟屏生产者的帧时间戳比较。 */
    @Volatile private var frameGuardSince: Long? = null

    /** 此刻能不能把 Agent 屏的最新帧交给模型:启动后还没收到新帧时不给,直到新帧到达才解除。 */
    private fun frameServable(): Boolean = when (FrameFreshness.serve({ display.current?.lastFrameAt() }, frameGuardSince)) {
        FrameFreshness.Serve.Guarded -> false
        FrameFreshness.Serve.Fresh -> {
            frameGuardSince = null
            true
        }
    }

    /** 等这次画面变化之后的首帧;等不到也继续保持保护,不能把旧帧放行。 */
    private suspend fun awaitFrameAfter(startedAt: Long) {
        frameGuardSince = startedAt
        if (FrameFreshness.awaitNewFrame(
                { SystemClock.uptimeMillis() },
                ::delay,
                { display.current?.lastFrameAt() },
                startedAt,
            )
        ) frameGuardSince = null
    }

    /**
     * は画面上のテキストを認識します。自己描画インターフェイスではアクセシビリティの問題はほとんどありません。インシデントが静かな場合は、画面がまだ遷移している可能性があります。画面が停止するまで待ってから認識してください。認識プロセス中にページ全体が変更された場合は、新しいページを押して、再度認識します。
     * (ルールについては [StillFrameRead] を参照)。部分的に動くパーツ (アニメーション化されたアバター、カーソル) は単語の位置に影響を与えないため、単語を完全に破棄することなく、そのまま使用できます。
     */
    override suspend fun readVisibleText(): List<VisibleTextLine>? = withContext(Dispatchers.IO) {
        if (!hasDisplay || !frameServable() || frameBlack() == true) return@withContext null
        StillFrameRead.read(SystemClock::uptimeMillis, { delay(it) }, ::frameSignature) {
            display.current?.latestFrameBitmap()?.let { PhoneOcr.read(it) }
        }
    }

    override suspend fun frameGrid(): FrameGrid? = withContext(Dispatchers.IO) {
        if (!frameServable()) return@withContext null
        val bmp = display.current?.frames?.value ?: return@withContext null
        runCatching { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    override suspend fun checkScreen(): ScreenCheck = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        // まず、インターフェイスが静かになるかどうかを確認します。変更は常にありますが、まだ読み込み中です。
        val quiet = withEventClock { clock -> clock.awaitQuiet(QUIET_MS, CHECK_CAP_MS) }
        // 静かなときに画面を2回撮ります。両方とも黒の場合は、スクリーンショット防止の可能性があります。
        val black = mutableListOf<Boolean>()
        frameBlack()?.let(black::add)
        if (quiet) {
            withEventClock { clock -> clock.awaitQuiet(SECOND_LOOK_QUIET_MS, SECOND_LOOK_CAP_MS) }
            frameBlack()?.let(black::add)
        }
        val r = read()
        ScreenDiagnosis.classify(
            ScreenFacts(
                currentApp = r.topPackage,
                hasWindow = r.hasAppWindow,
                anyWindow = r.anyAppWindow,
                readableElements = r.entries.count { it.element.meaningful },
                changing = !quiet,
                blackFrames = black,
                texts = r.texts,
                passwordField = r.passwordField,
                foregroundApp = if (r.topPackage == null) foregroundTaskPackage() else null,
            ),
        )
    }

    override suspend fun waitStable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext true
        withEventClock { clock -> clock.awaitQuiet(QUIET_MS, timeoutMs) }
    }

    /** この仮想画面の次のインターフェイス イベント (ホバリングはカウントされません)。制限時間が経過するまで待ってから画面を開きます。 */
    override suspend fun awaitChange(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) { delay(timeoutMs.coerceAtLeast(0)); return@withContext false }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { a11y.eventsFor(displayId).first { it.eventType and IGNORED_EVENTS == 0 } } != null
    }

    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        if (condition == "stable") return@withContext waitStable(timeoutMs)
        if (condition !in setOf("appear", "disappear", "activity") || value.isNullOrBlank()) return@withContext false
        coroutineScope {
            val changed = Channel<Boolean>(Channel.CONFLATED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(displayId).collect { event ->
                    if (condition == "activity" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.className?.toString()?.contains(value, true) == true) changed.trySend(true)
                    else if (condition != "activity") changed.trySend(false)
                }
            }
            try {
                fun matched(): Boolean = when (condition) {
                    "activity" -> runCatching { shell.topActivity(displayId)?.contains(value, true) == true }.getOrDefault(false)
                    else -> {
                        val r = read()
                        val exists = r.windows.any { w ->
                            w.title?.contains(value, true) == true || w.nodes.any { n -> containsText(n, value) }
                        } || r.texts.any { it.contains(value, true) }
                        exists == (condition == "appear")
                    }
                }
                if (matched()) return@coroutineScope true
                withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
                    while (true) {
                        val activityEvent = changed.receive()
                        if (condition == "activity") {
                            if (activityEvent) return@withTimeoutOrNull true
                        } else if (matched()) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
            } finally { job.cancel(); changed.close() }
        }
    }

    private fun containsText(node: com.wangzan101.mika.core.uistate.UiTreeNode, text: String): Boolean =
        (!node.secret && (node.text?.contains(text, true) == true || node.description?.contains(text, true) == true)) ||
            node.children.any { containsText(it, text) }

    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = withContext(Dispatchers.IO) {
        if (kind == "notification") return@withContext withTimeoutOrNull(timeoutMs) {
            a11y.notificationEvents().first { packageName == null || it.packageName == packageName }
        }
        if (!hasDisplay) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            val event = a11y.eventsFor(displayId).first { e ->
                (packageName == null || e.packageName?.toString() == packageName) && when (kind) {
                    "window" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(), event.text.joinToString(" ").take(2_000))
        }
    }

    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = withContext(Dispatchers.IO) {
        if (kind != "notification" && !hasDisplay) return@withContext emptyList()
        val collected = mutableListOf<ScreenEvent>()
        val stream = if (kind == "notification") a11y.notificationEvents().filter { packageName == null || it.packageName == packageName }
        else a11y.eventsFor(displayId)
            .filter { event ->
                (packageName == null || event.packageName?.toString() == packageName) && when (kind) {
                    "window" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            .map { event -> ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(),
                event.text.joinToString(" ").ifBlank { event.className?.toString().orEmpty() }.take(2_000)) }
        withTimeoutOrNull(durationMs) { stream.take(maxEvents).collect { collected += it } }
        collected
    }

    override suspend fun systemCommand(command: String): SystemCommandResult? = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext null
        val result = shell.runSystemCommand(displayId, command)
        SystemCommandResult(result.getInt("exitCode", -1), result.getString("output").orEmpty())
    }

    // ---------- アクション ----------

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        when (val target = resolve(ref, snapshotId)) {
            null -> ActionResult.Failed("ref_expired")
            else -> verified(relocatedTo = target.second) { click(target.first) }
        }
    }

    override suspend fun tapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { tapAt(x, y) }
    }

    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val target = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val rect = android.graphics.Rect().also { target.first.getBoundsInScreen(it) }
        verified(relocatedTo = target.second) { doubleTapAt(pacing.within(rect.left, rect.top, rect.right, rect.bottom)) }
    }

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { doubleTapAt(pacing.near(x, y)) }
    }

    // ダブルクリックは 1 つのジェスチャで 2 ストロークのみです。ダブルクリックは 2 回に分けて送信され、途中のコールバック遅延がシステムがダブルクリックを認識する制限時間を超える可能性があります。
    private suspend fun doubleTapAt(at: Pair<Int, Int>): Boolean {
        delay(pacing.approach())
        return a11y.taps(displayId, at.first.toFloat(), at.second.toFloat(), 2, pacing.doublePress(), pacing.doubleGap())
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { drag(listOf(x1 to y1, x2 to y2), durationMs, bow = true) }
    }

    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        if (paths.isEmpty() || paths.size > 2 || paths.any { it.size < 2 || it.size > 32 }) return@withContext ActionResult.Failed("invalid_gesture")
        verified {
            // 描かれたパスを指1本でたどり、リズムに合わせて速度を調整します。数本の指を一緒に動かしても、持続時間はわずかに変動するだけです。
            if (paths.size == 1) return@verified drag(paths.single(), durationMs, bow = false)
            delay(pacing.approach())
            a11y.gesture(displayId, paths.map(::pathOf), pacing.duration(durationMs))
        }
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        var relocatedTo: String? = null
        val start = if (ref != null && snapshotId != null) {
            val target = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
            relocatedTo = target.second
            target.first
        } else {
            read().entries.firstOrNull { it.element.scrollable }?.node
        }
        val node = start?.let { ancestorOrSelf(it) { n -> n.isScrollable } }
        if (node == null) {
            // スクロール可能なコントロールがない場合は、代わりに画面中央のスライドを使用します
            val cx = screenWidth / 2
            val (from, to) = if (down) screenHeight * 3 / 4 to screenHeight / 4 else screenHeight / 4 to screenHeight * 3 / 4
            val r = swipe(cx, from, cx, to)
            return@withContext if (r is ActionResult.Ok && relocatedTo != null) r.copy(relocated = true, relocatedTo = relocatedTo) else r
        }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        verified(relocatedTo = relocatedTo) { node.performAction(action) }
    }

    /**
     * 滞在している場合は、ゆっくりとスクロールします。画面の半分以上を占めるスクロール可能なコンテンツ (情報フロー、テキスト) の中央で [fraction] 画面の高さをゆっくりとスワイプし、停止してから、震えずに手を上げます。
     * は、入力メソッドの再生時にスクロールせず、入力ボックスにフォーカスが保持され、他のウィンドウ (ポップアップ ボックス、フローティング レイヤー) で覆われており、十分な大きさのスクロール可能なコンテンツがありません。
     */
    override suspend fun idleScroll(fraction: Double): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay || imeBox() != null || focusedEditable() != null) return@withContext false
        if (ScreenReader.contentWindows(windows()).count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1) return@withContext false
        val area = read().entries.filter { it.element.scrollable }
            .map { e -> android.graphics.Rect().also { e.node.getBoundsInScreen(it) } }
            .maxByOrNull { it.width().toLong() * it.height() } ?: return@withContext false
        if (area.width().toLong() * area.height() < screenWidth.toLong() * screenHeight * IDLE_MIN_AREA) return@withContext false
        val dist = (screenHeight * kotlin.math.abs(fraction)).roundToInt().coerceAtMost(area.height() / 2)
        if (dist < IDLE_MIN_PX) return@withContext false
        val cx = area.centerX()
        val mid = area.centerY()
        // 下を向いて (分数は正)、指を上にスライドさせます
        val (from, to) = if (fraction > 0) mid + dist / 2 to mid - dist / 2 else mid - dist / 2 to mid + dist / 2
        drag(listOf(cx to from, cx to to), IDLE_SCROLL_MS, bow = true)
    }

    override suspend fun back(): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }
    }

    override suspend fun pressKey(keyCode: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { shell.injectKey(displayId, keyCode) }
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val found = when (val f = findField(ref, snapshotId)) {
            is Field.Found -> f
            is Field.Missing -> return@withContext when {
                f.reason != NO_FOCUS -> ActionResult.Failed(f.reason)
                // 画面上には入力フォーカスを保持するものはなく、入力方法もありません。ペーストを保持するフレームがないため、ペーストされません（インターフェイスが他の場所に移動された場合は、「入力された可能性がある」とみなされます）
                !somethingHoldsInputFocus() -> ActionResult.Failed(NO_FOCUS)
                else -> typeUnseen(text)
            }
        }
        var field = found.node
        val original = FieldShape.of(field)
        var focusUncertain = false
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.refresh()
            // フォーカスの結果と状態フラグを確認できない場合は、通常の入力欄としてタップし直します。
            if (!field.isFocused) {
                withEventClock { clock ->
                    clock.mark()
                    click(field).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
                }
                focusedEditable()?.takeIf { sameField(original, it) }?.let { field = it }
            }
            // SET_TEXT はフォーカスなしでも試し、読戻し結果と未確認のフォーカス状態を呼出し側へ返します。
            focusUncertain = !field.isFocused
        }
        val web = inWebView(field)
        // Web ページ編集領域では、入力を受け取るために貼り付ける必要があります。パスワードボックスはクリップボードを通過しません。
        var pasteUnavailable = web && field.isPassword
        val placeholder = ScreenReader.placeholderOf(field)
        val io = object : TextField {
            override fun read(): String? {
                if (field.refresh()) return ScreenReader.fieldText(field, placeholder, text)
                // 一部のアプリは、コンテンツが変更されると入力コントロールを置き換えます。現在入力フォーカスを保持しているものが元のタイプ、リソース ID、および場所と一致する場合にのみ、入力コントロールを読み取ります。
                // そうしないと、読み戻されず、他のボックスに書き込まれません。
                val now = focusedEditable()?.takeIf { sameField(original, it) } ?: return null
                field = now
                return ScreenReader.fieldText(now, placeholder, text)
            }
            override fun write(text: String): Boolean {
                if (!web || pasteUnavailable) return setText(field, text)
                return when (val r = inputText(field, text)) {
                    null -> { pasteUnavailable = true; setText(field, text) }
                    else -> r
                }
            }
            override fun retry(text: String): Boolean {
                if (web || pasteUnavailable) return write(text)
                // ネイティブ入力欄の SET_TEXT が反映されなければ、選択と貼付けの経路で再試行します。
                return when (val r = inputText(field, text)) {
                    null -> { pasteUnavailable = true; setText(field, text) }
                    else -> r
                }
            }
        }
        val textsBefore = read().texts.toSet()
        // は人間のように少しずつ入力されます (TypingPacing を参照)。受信するために貼り付ける必要があるパスワード ボックスと Web ページ ボックスは、依然として一度に書き込まれます。
        // 書き込みに失敗し、ボックスが半文字を受け付けない場合に停止します。次に、段落全体を書き、確認のために読み返します。
        typing(field.packageName?.toString()).forField(web, field.isPassword).leadIn(io, text)
        var frozen = false
        val verification = typeAndVerify(
            io, text, TYPE_ATTEMPTS,
            settle = { withEventClock { clock -> clock.awaitQuiet(READBACK_QUIET_MS, READBACK_CAP_MS) } },
            // リードバックが変わらない場合は、入力したテキスト（連想語など）を含む新しいテキストがインターフェイス上に表示されるかどうかを確認します。
            reacted = {
                val typed = text.filterNot { it.isWhitespace() }
                (read().texts.toSet() - textsBefore).any { typed in it.filterNot { c -> c.isWhitespace() } }.also { frozen = it }
            },
            secret = field.isPassword,
        )
        val hint = listOfNotNull(
            HINT_TAPPED_TO_FOCUS.takeIf { found.tapped },
            HINT_FOCUS_UNCONFIRMED.takeIf { focusUncertain },
            HINT_READBACK_FROZEN.takeIf { frozen },
            HINT_PASTE_UNAVAILABLE.takeIf { pasteUnavailable },
        ).joinToString(" ").ifEmpty { null }
        // という単語は表示されるだけで、アプリでは受信されません。「一貫性」をレポートできません。
        val reported = if (pasteUnavailable && verification != Verification.MISMATCH) Verification.UNKNOWN else verification
        ok(verification = reported, hint = hint, secret = field.isPassword, relocatedTo = found.relocatedTo)
    }

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val found = when (val f = findField(ref, snapshotId, tapToFocus = false)) {
            is Field.Found -> f
            is Field.Missing -> return@withContext when {
                f.reason != NO_FOCUS -> ActionResult.Failed(f.reason)
                !somethingHoldsInputFocus() -> ActionResult.Failed(NO_FOCUS)
                else -> clearUnseen()
            }
        }
        val field = found.node
        verified(relocatedTo = found.relocatedTo) { if (inWebView(field)) inputText(field, "") == true else setText(field, "") }
    }

    // ---------- 入力ボックスがツリーに表示されません ----------

    /**
     * この時点で入力ボックスに入力します。まずそれをクリックし、インターフェースが静まるまで待ちます。ツリー内に表示されフォーカスされている入力ボックスをクリックし、通常どおり入力ボックスに入力します。
     * それ以外の場合は、[typeUnseen] を押して貼り付けて確認します。貼り付ける前の画面上のテキストは、クリック後に読み上げられます（クリックされたカーソルやキーボードは入力された単語としてカウントされません）。
     * 他のアプリに切り替えるとき (リンクまたはボタンをクリックするとき)、入力する必要はありません。
     */
    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val tapped = verified { tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // がクリックして別のアプリを開きました (同じものがユーザーのホーム画面で開かれ、その後戻ってきました): 入力せず、このクリックの結果を正直に報告してください
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        if (focusedEditable() != null) return@withContext typeText(null, null, text)
        typeUnseen(text, atPoint = true)
    }

    /** はこの時点で入力ボックスをクリアします。方法は [typeAt] と同じです。 */
    override suspend fun clearAt(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val tapped = verified { tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // がクリックして別のアプリを開きました (同じものをユーザーのホーム画面で開いてから元に戻しました): これはクリアされず、このクリックの結果は真実に報告されます。
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        if (focusedEditable() != null) return@withContext clearText(null, null)
        clearUnseen(atPoint = true)
    }

    /**
     * ツリー内にフォーカスのある入力ボックスがありません。入力ボックスはアプリ自体によって描画されたインターフェイスにある可能性があります。木はそれを読むことができず、焦点がそこにあります。
     * まずすべてを選択し (ノードの単語を設定するのと同じように、元の単語を置き換えます)、クリップボードに貼り付けます。キーはミカの画面にのみ送信されます。次に、入力前と入力後に画面上で読み取られたテキストが比較されます。
     * ([UnseenField.typed]) をチェックします。入力メソッドは必須ではありません。キーはこの画面上のフォーカスのあるウィンドウに送信され、入力メソッドを経由せずにフレームを受信できます。
     * 入力方法はなく、何も変更されていません。まずモデルに入力ボックスをクリックするよう依頼してください。入力メソッドを再生するとき、キーボード上の単語は画面上の単語としてカウントされません。
     */
    private suspend fun typeUnseen(text: String, atPoint: Boolean = false): ActionResult {
        val ime = imeBox()
        val before = readVisibleText()?.let { UnseenField.outside(it, ime) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        var pasted: Boolean? = null
        val changed = withEventClock { clock ->
            clock.mark()
            pasted = pasteUnseen(text, beforeFrame)
            pasted == true && awaitChange(clock, beforeTree, beforeFrame)
        }
        when (pasted) {
            null -> return ActionResult.Failed("paste_unavailable", HINT_PASTE_BLOCKED)
            false -> return ActionResult.Failed("action_failed")
            else -> Unit
        }
        val after = readVisibleText()?.let { UnseenField.outside(it, imeBox() ?: ime) }
        return when (val r = UnseenField.typed(text, before, after, changed, keyboardUp = ime != null, atPoint = atPoint)) {
            is ActionResult.Ok -> ok(changed = r.changed, verification = r.verification, hint = r.hint)
            else -> r
        }
    }

    /**
     * のクリップボードを [text] に変更し、すべてを選択し、Ctrl+V を押して、アプリが読み取る (画面が変更される) かタイムアウトするまで待ってから復元します。 null を返す: クリップボードは安全に使用できません (ユーザーの元のコンテンツを読み取ることができません)。
     * 何も動かない。 false: ペーストキーを送信できません。クリップボードの保存、復元、暗号化は[inputText]と同様です。
     */
    private fun pasteUnseen(text: String, beforeFrame: IntArray?): Boolean? {
        clipLock.lock()
        clipHeld(true)
        try {
            if (runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED) != ShellService.CLIP_SET) return null
            runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
            ctrlKey(KeyEvent.KEYCODE_A)
            if (!ctrlKey(KeyEvent.KEYCODE_V)) return false
            // アプリは貼り付けキーを処理するときにクリップボードを読み取ります。画面が変更されてから復元されます。最大でしばらく時間がかかります。
            val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline && !FrameMath.differs(beforeFrame, frameSignature())) Thread.sleep(POLL_MS)
            return true
        } finally {
            runCatching { shell.restoreClip() }
            clipHeld(false)
            clipLock.unlock()
        }
    }

    /** と [typeUnseen] は同じ前提条件を持っています。すべて選択した後、削除キーを押してください。入力方法がポップアップせず、インターフェースがまったく変わらない場合は、まずモデル上の入力ボックス ([UnseenField.cleared]) をクリックしてください。 */
    private suspend fun clearUnseen(atPoint: Boolean = false): ActionResult {
        val keyboardUp = imeBox() != null
        val r = verified { ctrlKey(KeyEvent.KEYCODE_A) && shell.injectKey(displayId, KeyEvent.KEYCODE_DEL) }
        return UnseenField.cleared(r, keyboardUp, atPoint)
    }

    private fun ctrlKey(keyCode: Int): Boolean = runCatching { shell.injectCtrlKey(displayId, keyCode) }.getOrDefault(false)

    /** この画面には入力メソッドがポップアップ表示されます (この時点で return を押します。Android は最初に入力メソッドを閉じます)。 */
    fun keyboardUp(): Boolean = hasDisplay && imeBox() != null

    /** この画面の入力方法ウィンドウの範囲 ([l,t,r,b] 0 ～ 999)。入力メソッドが存在しない場合、入力メソッドは null になります。プレイ時の文字数は画面上の文字数としてカウントされません。 */
    private fun imeBox(): List<Int>? {
        val w = windows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val r = android.graphics.Rect().also(w::getBoundsInScreen)
        if (r.isEmpty) return null
        return listOf(normalize(r.left, screenWidth), normalize(r.top, screenHeight), normalize(r.right, screenWidth), normalize(r.bottom, screenHeight))
    }

    /**
     * の場合は、仮想画面を起動します。 [cold] の場合は、まずプロセスを終了し、アプリの最初のページに戻ります。ユーザーがホーム画面でアプリを使用中にアプリを起動または終了しません。
     * 待ってから再試行してください。それでも機能しない場合は、エージェントに引き渡して一時停止するか、ユーザーに引き継いでもらうように依頼してください。仮想スクリーンは、ここで初めて使用されたときに構築されました。
     */
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = open(packageName, null, cold)

    /**
     * は、別のユーザー [userId] にインストールされているコピーを開きます (発信者は最初に [appCopies] を押して確認しました): 組み込みアシスタントを開始する前に、同じルールに従ってユーザーが使用できるかどうかを再度確認します。
     * は copy_not_found として使用できません。このコピーのみが占有およびコールド スタートのために表示されます。 [userId] は、Mika 自身のユーザーの場合は [openApp] と同じになります。
     */
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        open(packageName, userId.takeIf { it != myUser }, cold)

    /**
     * 他のタスクが解放されるのを待ちます [packageName] (モデルが開くまで待ちます): 解放とは、そのタスクが他の画面上に存在せず、職業登録に他のタスクが占有していないことを意味します。
     * は並列タスクでのみ意味を持ちます。それ以外の場合、これと競合する他のタスクは存在しません。 パッケージ
     */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        if (!checkOtherTasks) return AppWait.Unsupported
        val user = userId?.takeIf { it != myUser }
        return awaitApp(packageName, user, timeoutMs) {
            withContext(Dispatchers.IO) { !onOtherDisplay(packageName, user) && appFree(packageName, user) }
        }
    }

    /** ([user] 用のパッケージ) は現在、Mika の別の画面で (他のタスクによって) 開かれています。 */
    private fun onOtherDisplay(packageName: String, user: Int?): Boolean {
        val other = runCatching { if (user == null) shell.displayOfPackage(packageName) else shell.displayOfPackageInUser(packageName, user) }.getOrDefault(-1)
        return other >= 0 && other != display.current?.displayId
    }

    /** は [openApp] / [openAppCopy] の共通部分です。 [user] が空の場合は元のパーツです。 */
    private suspend fun open(packageName: String, user: Int?, cold: Boolean): ActionResult = withContext(Dispatchers.IO) {
        // 同じアプリを同時に 2 つの画面に表示することはできません。システムはタスク全体を移動します。これは、別のタスクからタスクをスナッチするのと同じです。 2 つの部分が含まれている場合、2 つの部分はそれぞれ独自のものとしてカウントされます。
        if (checkOtherTasks) {
            if (onOtherDisplay(packageName, user)) return@withContext IN_USE_BY_OTHER
            if (!(if (user == null) reserve(packageName) else reserveCopy(packageName, user))) return@withContext IN_USE_BY_OTHER
        }
        // 開く前の上部ウィンドウ: 後でポップアップする選択ボックスは、今回開いたことによって生じた選択ボックスである可能性があります (前回は選択されておらず、再度開いても同じ選択ボックスです)
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        // 画面新鲜度从这次启动开始算:之后的截图、认字只认这之后到的帧(见 FrameFreshness)
        val startedAt = System.nanoTime()
        beforeOpen(packageName, user)
        fun launch() = when {
            user != null -> shell.startAppInUser(id, packageName, user, cold)
            cold -> shell.restartApp(id, packageName)
            else -> shell.startApp(id, packageName)
        }
        var code = launch()
        val retryUntil = SystemClock.uptimeMillis() + IN_USE_RETRY_MS
        while (code == ShellService.START_IN_USE_ON_MAIN && SystemClock.uptimeMillis() < retryUntil) {
            delay(IN_USE_POLL_MS)
            code = launch()
        }
        if (code != ShellService.START_OK && code != ShellService.START_OK_COLD_IGNORED) notOpened(packageName, user)
        when (code) {
            ShellService.START_OK, ShellService.START_OK_COLD_IGNORED -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_NOT_FOUND -> return@withContext ActionResult.Failed("app_not_found")
            ShellService.START_USER_NOT_ALLOWED -> return@withContext ActionResult.Failed(COPY_NOT_FOUND)
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        // 同一个 App 只是回到已有静态页时可能不会来新帧,旧帧仍就是当前画面;冷启动、另一份用户或换 App 必须等新帧。
        val needsNewFrame = cold || user != null || before != packageName
        awaitOpened(packageName, before, hint = if (code == ShellService.START_OK_COLD_IGNORED) HINT_COLD_IGNORED else null, user = user,
            launchedAt = if (needsNewFrame) startedAt else 0L)
    }

    /**
     * [packageName] にテキストを共有: [openApp] と同様に、初めて画面が作成されます。ホーム画面で使用する場合、ユーザーは開きません。こちらも開けたらフロントに来るのを待ちます。
     * 認識システムはどれを開くかを尋ねます。このアプリは、cannot_share としてテキスト共有を受け入れません。共有ページのみが開かれ、そのページでクリックしたモデルは掲載されません。
     */
    override suspend fun shareText(packageName: String, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (checkOtherTasks) {
            val other = runCatching { shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != display.current?.displayId) return@withContext IN_USE_BY_OTHER
            if (!reserve(packageName)) return@withContext IN_USE_BY_OTHER
        }
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        val startedAt = System.nanoTime()
        beforeOpen(packageName, null)
        val code = runCatching { shell.startShare(id, packageName, text) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /**
     * ミカの画面でミカの互換性セルフテスト ページを開きます ([intent] はコンポーネントを指定します。互換性セルフテストのみ)。このページは、シェルレベルの呼び出し元に対してのみ開かれます。
     * を使用すると、組み込みアシスタントをシェルとしてこの画面で直接起動できます (アシスタントはこのコンポーネントのみを認識します)。シェルの起動はバックグラウンドの起動ページによって制限されず、Mika がフォアグラウンドにあるかどうかは関係ありません。
     * の後、[openApp] のように、ミカ画面の上部に到達するまで待ちます。初めて使用するときに画面を構築します。
     */
    suspend fun openOwnPage(intent: Intent): ActionResult = withContext(Dispatchers.IO) {
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        val startedAt = System.nanoTime()
        when (runCatching { shell.startSystemAction(id, intent, null) }.getOrDefault(ShellService.START_ERROR)) {
            ShellService.START_OK -> Unit
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("own_page_not_allowed")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(a11y.packageName, before, hint = null, launchedAt = if (before != a11y.packageName) startedAt else 0L)
    }

    /**
     * が開始された後: [packageName] が Mega 画面の最上部に到達するまで待機するか、システムがどちらを最初に開くかを尋ねます ([before] は、開始前に最上部のウィンドウがあったパッケージです)。
     * [sharing]: 今回は共有についてです (アプリに複数の共有ページがある場合、システムはどれを使用するかを尋ね、アプリ名はオプションに含まれない場合があります): システム パッケージが一番上にあるウィンドウも、質問としてカウントされます。
     */
    private suspend fun awaitOpened(
        packageName: String, before: String?, hint: String?, sharing: Boolean = false, user: Int? = null, launchedAt: Long = 0L,
    ): ActionResult {
        val beforeTree = treeHash()
        targetPackage = packageName
        chooserPackage = null
        requestedUser = user
        // 从等待窗口到顶起就保护所有返回路径(包括系统选择器),不是只有最后的成功路径才拦旧帧。
        // 同 App 静态页可以不另等新帧,但不能借此清掉上一次尚未由新帧解除的保护。
        if (launchedAt > 0) frameGuardSince = launchedAt
        // 只有确实还在原来的页面,失败后才能继续用原来的静态帧;已经切走或换页时仍须等新帧。
        fun clearGuardIfUnchanged() {
            val now = foregroundTaskPackage() ?: ScreenReader.topPackage(windows())
            if (now == before && treeHash() == beforeTree) frameGuardSince = null
        }
        // が開きました: ミカ 画面の一番上のタスクはこれです (タスクを要求できない場合は、一番上のウィンドウに戻って読みやすいようにしてください)。どちらを指定するかはユーザーのタスクである必要があります
        fun launched(): Boolean = (foregroundTaskPackage()?.let { it == packageName } ?: (ScreenReader.topPackage(windows()) == packageName)) &&
            (user == null || userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).let { it < 0 || it == user })
        fun picker(): String? = if (sharing) systemPickerOf(packageName, before) else null
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        // システムが最初にどれを開くかを尋ねるとき、アプリは自らフロントデスクに行くことはなく、選択ボックスが表示されると待機を停止します。
        while (!launched() && chooserOf(packageName, before) == null && picker() == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val onTop = launched()
        val task = if (onTop) shell.topTaskId(displayId) else -1
        _target.value = TargetApp(packageName, task, if (task >= 0) userOf(task) else user ?: -1)
        if (!onTop && user != null) {
            // は別のコピーを必要としていますが、システムは最初にどれを開くかを尋ねます。オプションを 1 つずつクリックして、モデルの推測に任せることなく、どのコピーが開かれているかを識別します。
            if (chooserTargets(packageName, before) != null) return resolveCopyChooser(packageName, user, before, hint)
                .also { if (it is ActionResult.Failed) clearGuardIfUnchanged() }
            // 直接開かれているのは、このアプリの別のコピーです。このままでは失敗し、目的のコピーではありません。
            if (foregroundTaskPackage() == packageName) return copyNotOpened(user, userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)))
                .also { clearGuardIfUnchanged() }
        }
        // システムはどちらを開くかを尋ねています (アプリのコピーが 2 つインストールされているなど)。開いています。モデルが選択されるまで待ちます。アプリを選択すると、フロントデスクに来て、その後のアクションは通常どおりターゲットとして認識されます。
        // アプリが既に最上位にあるときに覆われたシステム ウィンドウ (許可ボックス) はカウントされません。
        if (!onTop) chooserOf(packageName, before)?.let { first ->
            // 初めてこれに遭遇したとき、どれがもう一方であるかをマークできるように、何枚インストールされているか尋ねました。
            if (profiles == null) otherProfiles()
            val choice = chooserOf(packageName, before) ?: first
            chooserPackage = choice.packageName
            return ok().copy(chooser = choice, appSwitched = null, switchedByTarget = false)
        }
        // 共有するとき、システムはこのアプリのどの共有ページを使用するかを尋ねます。これも開いているとみなされます。モデルが選択されるまで待ちます。
        if (!onTop) picker()?.let { pkg ->
            chooserPackage = pkg
            return ok(hint = HINT_SHARE_PICKER).copy(appSwitched = null, switchedByTarget = false)
        }
        // まだ開いていない場合は、現時点で一番上にあるものについて真実を話してください。成功報告をしない場合は、
        if (!onTop) {
            clearGuardIfUnchanged()
            val now = foregroundTaskPackage() ?: ScreenReader.topPackage(windows())
            // は別のコピーを必要としていますが、一番上のものがこのアプリの別のコピーです
            val copy = if (user != null && now == packageName) " (a different copy of the app)" else ""
            return ActionResult.Failed("app_not_in_front", hint = now?.let { "In the foreground now: $it$copy." } ?: "Nothing came to the foreground.")
        }
        // 树稳定只是窗口到了顶,画面可能还没合成:等这次启动之后的第一帧;等不到就持续拦住旧帧,
        // 不把上一屏留在缓冲里的残影当这次打开的画面交给模型(issue #253)
        if (launchedAt > 0) awaitFrameAfter(launchedAt)
        // は開いていますが、別のパッケージのウィンドウがそれを覆っています (許可ポップアップ ウィンドウ、アカウント セレクター)。正直に言って、それが誰であるかを教えてください。まだ開いています。
        val cover = ScreenReader.topPackage(windows())?.takeIf { it != packageName }
        return ok(hint = hint).let { if (cover != null) it.copy(coveredBy = cover, appSwitched = null, switchedByTarget = false) else it }
    }

    /**
     * は別のユーザー [user] からのコピーを必要としていますが、システムは、Mika 画面でどれを開くかを尋ねます (オプションの名前が同じである可能性があるため、どれがどれであるかを判断するのは困難です)。
     * オプションを 1 つずつクリックし、オプションが開くまで待ち、最上位のタスクが属しているユーザーを読み取ります。欲しいものがあれば、それを開いてください。単にReturnキーを1回押してバックグラウンドに移行させるだけではないでしょうか（それがユーザーの当面のタスクである可能性があります）
     * は終了しないでください)、再度開いて「次へ」をクリックします。すべて試しましたが、まだ機能しません。 copy_not_opened が失敗した場合は、どのコピーが開かれたかを教えてください。間違ったコピーを成功とみなさないでください。
     */
    private suspend fun resolveCopyChooser(packageName: String, user: Int, before: String?, hint: String?): ActionResult {
        fun topTask() = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        suspend fun opened(): ActionResult? {
            if (foregroundTaskPackage() != packageName) return null
            val task = topTask()
            if (userOf(task) != user) return null
            _target.value = TargetApp(packageName, task, user)
            return ok(hint = hint)
        }
        var tried = 0
        var shown: Int? = null
        while (true) {
            val targets = chooserTargets(packageName, before) ?: break
            if (tried >= targets.size) break
            val (x, y) = targets[tried++]
            if (!runCatching { shell.injectTap(displayId, x, y) }.getOrDefault(false)) break
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (foregroundTaskPackage() != packageName && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            waitStable(SETTLE_CAP_MS)
            if (foregroundTaskPackage() != packageName) break
            opened()?.let { return it }
            shown = userOf(topTask())
            Log.i(TAG, "chooser option $tried opened user $shown, not the requested $user")
            runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }
            waitStable(SETTLE_CAP_MS)
            if (tried >= targets.size) break
            if (runCatching { shell.startAppInUser(displayId, packageName, user, false) }.getOrDefault(ShellService.START_ERROR) != ShellService.START_OK) break
            val until = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (chooserTargets(packageName, before) == null && SystemClock.uptimeMillis() < until) {
                // 今回はご要望のあったものを直接発行させていただきました。
                opened()?.let { return it }
                delay(POLL_MS)
            }
        }
        return copyNotOpened(user, shown)
    }

    /** 要求されたコピー [user] は開かれていません。 [shown] は、開かれたコピーが属するユーザーです (開かれていない場合、または読み取れない場合は、null/負の数値になります)。 */
    private fun copyNotOpened(user: Int, shown: Int?): ActionResult.Failed {
        val what = when {
            shown == null || shown < 0 -> "Nothing from the app came to the front."
            shown == myUser -> "The main app opened instead (user $shown)."
            else -> "The copy in user $shown opened instead."
        }
        return ActionResult.Failed(COPY_NOT_OPENED, "$what Requested: the copy in user $user.")
    }

    /** システムが、Mika 画面上でどれを開くかを尋ねるとき、それらのオプションの中心点 (画面上のピクセル、同じオプションが次々に表示される)。要求されない場合は null です。認識方法は[chooserOf]と同様です。 */
    private fun chooserTargets(packageName: String, before: String?): List<Pair<Int, Int>>? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val entries = ScreenReader.read(listOf(w), screenWidth, screenHeight).entries
        val picked = LaunchChooser.optionIndices(label, entries.map { it.element }) ?: return null
        return picked.map { i -> android.graphics.Rect().also(entries[i].node::getBoundsInScreen).let { it.centerX() to it.centerY() } }
    }

    /**
     * リンクまたは設定ページを開きます。 [openApp] と同様に、画面は初めて作成されます。ホーム画面でアプリを使用する場合、ユーザーはアプリを開きません。
     * 設定ページは、システム設定が配置されているパッケージに含まれることが想定されており、指定されたアプリはそのアプリに含まれます。他の場所 (システム セレクター、ジャンプ ページ) に落ちても、プロンプトが表示され、成功します。
     */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = withContext(Dispatchers.IO) {
        // アプリがアプリケーション アクセス境界によって表示されることを指定します。指定されていない場合、リストが空でない場合は、引き継ぎが無効になっていないものを選択します。
        val pinned = packageName ?: allowedLinkHandler(action, uri)
        val id = display.get().displayId
        val expected = pinned ?: settingsPackage.takeIf { action != null }
        if (checkOtherTasks && expected != null && !reserve(expected)) return@withContext IN_USE_BY_OTHER
        val before = ScreenReader.topPackage(windows())
        val beforeTree = treeHash()
        val startedAt = System.nanoTime()
        // 事先知道由谁打开的(指定了 App、设置页、只有一个 App 接的链接):启动前先静上
        val early = expected ?: linkHandler(action, uri)
        early?.let { beforeOpen(it, null) }
        val code = shell.startView(id, action, uri, pinned)
        if (code != ShellService.START_OK) early?.let { notOpened(it, null) }
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("link_unresolved")
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("invalid_link")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        // 链接可能落到另一个 App、系统选择器或同 App 的新页面,同样不能把启动前的帧交出去。
        frameGuardSince = startedAt
        fun landed(): Boolean {
            val top = ScreenReader.topPackage(windows())
            return if (expected != null) top == expected else top != null && (top != before || treeHash() != beforeTree)
        }
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (!landed() && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val top = ScreenReader.topPackage(windows())
        val changed = top != before || treeHash() != beforeTree
        if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
        // リンクされたアプリのみを開きます。システム セレクターまたはその他の場所にある場合は、ターゲットとして扱いません。
        val opened = top?.takeIf { if (expected != null) it == expected else it !in systemPickers }
        if (opened != null) {
            // 誰がリンクを開いたのかが事前にわからない場合は、着陸後にそのリンクを占有することになります。占領できない場合は撤退しません（シェルの他の画面を確認しました）
            if (checkOtherTasks) reserve(opened)
            targetPackage = opened
            requestedUser = null
            val task = shell.topTaskId(displayId)
            _target.value = TargetApp(opened, task, userOf(task))
        }
        ok(hint = if (expected != null && top != expected) hintLinkElsewhere(top, expected) else null)
    }

    /**
     * 禁止リストが空ではなく、リンクにアプリが指定されていない場合、誰がそれを開くべきか: 無効になっていないシステムのデフォルトのリストを使用し、それ以外の場合は無効になっていない最初のリストを使用します。無効になっている場合は、接続できるすべての接続を拒否します。 リストが空であるか、接続できるアプリがない場合、
     * は null になります (通常どおりシステムに任せ、それ以上のチェックは必要ありません)。
     */
    private fun allowedLinkHandler(action: String?, uri: String?): String? {
        val access = appAccess.current()
        if (access.blocked.isEmpty()) return null
        val intent = Intent(action ?: Intent.ACTION_VIEW).apply {
            uri?.let { data = android.net.Uri.parse(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = a11y.packageManager
        val candidates = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val allowed = candidates.filter { access.denial(it) == null }
        if (allowed.isEmpty()) access.denial(candidates.first())?.let { throw it }
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return default?.takeIf { it in allowed } ?: allowed.first()
    }

    /** は、アプリ リンクを開く人を指定しません。これはシステムによって直接決定されます。ユーザーが選択を求められた場合 (システム セレクターに該当する場合)、または読み取れない場合は null になります。 */
    private fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in systemPickers }

    private fun hintLinkElsewhere(top: String?, expected: String) =
        "The link opened, but the screen shows ${top ?: "no app yet"} instead of $expected: a system chooser or a redirect page may have taken it. Observe before acting."

    /**
     * タスクがこの画面に戻った後、そのウィンドウがアクセス可能なウィンドウ リストに表示されるまで待ってから、エージェントに画面を読み取らせます。
     * そうしないと、次の観察では空白の画面が表示される可能性があります。待てない場合は戻りますが、エージェント自身が画面を読んで判断します。
     */
    suspend fun awaitContent(): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (ScreenReader.topPackage(windows()) == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        ScreenReader.topPackage(windows()) != null
    }

    override suspend fun listApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) { launchableApps(a11y.packageManager, a11y.packageName) }

    // ---------- システムの処置 ----------

    /**
     * は、Android 標準インテントを介してシステム アクションを実行します ([SystemIntents] を参照)。まず、ミカに誰が答えるかを確認します (能力テスト、画面を設定する必要はありません)。そうでない場合は、サポートされていません。
     * が複数ある場合は、[SystemIntents.choose]を押して直接指定してください。次に、内蔵アシスタントをミカの画面で起動させ (初めて使用するときに画面を作成します)、アクションの前後にいつものようにホーム画面を見つめます。
     * 目覚まし時計とタイマーは静かに設定し、システムの次の目覚まし時計を読んで確認する必要があります。ただし、アプリはインターフェースを開いたときに真実を報告します。目覚まし時計と新しいスケジュールのリストは、そのアプリが一番上に表示されるまで待つ必要があります。
     */
    override suspend fun systemAction(action: SystemAction): SystemActionResult = withContext(Dispatchers.IO) {
        val intent = SystemIntents.toIntent(SystemIntents.spec(action))
        val pm = a11y.packageManager
        val handlers = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
                SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
        }.getOrDefault(emptyList())
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        // に対する答えはここだけです。無効になっているものはスキップしてください。無効になっている場合は、接続できるすべての接続を拒否します。
        val access = appAccess.current()
        val allowedHandlers = if (access.blocked.isEmpty()) handlers else handlers.filter { access.denial(it.packageName) == null }
        if (handlers.isNotEmpty() && allowedHandlers.isEmpty()) access.denial(handlers.first().packageName)?.let { throw it }
        val handler = SystemIntents.choose(allowedHandlers, default)
            ?: return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
        val pkg = handler.packageName
        intent.component = android.content.ComponentName(pkg, handler.className)
        if (checkOtherTasks && !action.silent && !reserve(pkg)) return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
        val alarms = a11y.getSystemService(AlarmManager::class.java)
        val alarmBefore = runCatching { alarms?.nextAlarmClock?.triggerTime }
        val id = display.get().displayId
        val topBefore = ScreenReader.topPackage(windows())
        val beforeTree = treeHash()
        val startedAt = System.nanoTime()
        val w = armSpillWatch()
        // によって引き継がれたページには、シェルに必要な権限 (目覚まし時計の設定) がない可能性があります。Mika として開始したものを準備してください。
        val asMika = PendingIntent.getActivity(a11y, SYSTEM_ACTION_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // インターフェイスを開いたりミュートしたりせずに、静かに設定（目覚まし時計、タイマー）します。時間の終わりに時計が鳴ります。
        if (!action.silent) beforeOpen(pkg, null)
        val code = runCatching { shell.startSystemAction(id, intent, asMika) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK && !action.silent) notOpened(pkg, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext SystemActionResult(ActionResult.Failed("app_in_use_by_user"), pkg)
            ShellService.START_IN_USE_BY_OTHER -> return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
            ShellService.START_UNRESOLVED -> return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
            else -> return@withContext SystemActionResult(ActionResult.Failed("launch_failed"), pkg)
        }
        if (!action.silent) frameGuardSince = startedAt
        fun top() = ScreenReader.topPackage(windows())
        var alarmAfter = alarmBefore.getOrNull()
        if (action is SystemAction.SetAlarm && alarmBefore.isSuccess) {
            // 時計アプリは主にバックグラウンドで書き込まれてから登録されます。システムの次の目覚まし時計が変わるまで待ち、長くてもしばらく待ちます。
            val deadline = SystemClock.uptimeMillis() + ALARM_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                alarmAfter = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
                if (alarmAfter != alarmBefore.getOrNull()) break
                delay(ALARM_POLL_MS)
            }
        } else if (!action.silent) {
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (top() != pkg && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        }
        waitStable(SETTLE_CAP_MS)
        val landed = top() == pkg
        // は静かにセットアップされているはずですが、画面にはそのアプリのインターフェイスが表示されています
        val showing = action.silent && landed && topBefore != pkg
        if (!action.silent || showing) {
            val changed = top() != topBefore || treeHash() != beforeTree
            if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
        }
        if (landed && (!action.silent || showing)) {
            targetPackage = pkg
            requestedUser = null
            val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
            _target.value = TargetApp(pkg, task, userOf(task))
        }
        val hint = if (!action.silent && !landed) hintNotInFront(top(), pkg) else null
        SystemActionResult(
            ok(hint = hint, spill = takeSpill(w)), handledBy = pkg,
            nextAlarmBefore = alarmBefore.getOrNull(), nextAlarmAfter = alarmAfter, alarmReadable = alarmBefore.isSuccess, showing = showing,
        )
    }

    private fun hintNotInFront(top: String?, expected: String) =
        "$expected took the action, but the screen shows ${top ?: "no app yet"}. Observe before acting."

    // ---------- ローラー ----------

    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext WheelOutcome(false, null, null, 0, "none", error = "no_app_open")
        val (node, _) = resolve(ref, snapshotId) ?: return@withContext WheelOutcome(false, null, null, 0, "none", error = "ref_expired")
        val driver = WheelDriver(
            screenWidth, screenHeight,
            quiet = { q, cap -> withEventClock { it.awaitQuiet(q, cap) } },
            // ゆっくりドラッグ: リズムに合わせて歩くときは、頭を動かさないように速度を落とし、最後に停止して手を放します。ゆっくりドラッグしても動かない場合は、クイックストロークに切り替えてください。
            drag = { points, slow -> drag(points, if (slow) WHEEL_DRAG_MS else WHEEL_SWIPE_MS, bow = false) },
            ocr = { readVisibleText() },
            imeShown = { imeBox() != null },
            back = { runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }.getOrDefault(false) },
        )
        driver.set(node, value)
    }

    // ---------- どれを開くか ----------

    /** 上部の読み取り可能なウィンドウ (タイプに関係なく、一部のシステム選択ボックスはアプリケーション ウィンドウであり、一部はシステム ウィンドウ) が配置されているパッケージ。 */
    private fun topWindowPackage(): String? =
        ScreenReader.contentWindows(windows()).firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /**
     * [packageName] を開いた後、システムはどれを開くかを尋ねます。一番上のウィンドウは別のシステム パッケージのウィンドウに置き換えられ (開く前は [before] が一番上のウィンドウです)、ウィンドウ内のオプションにはこのアプリの名前が記載されています。
     * は null ではありません。システム パッケージのみを認識します。他のアプリ ウィンドウでこの名前を言及してもカウントされません。
     */
    private fun chooserOf(packageName: String, before: String?): LaunchChoice? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val elements = ScreenReader.read(listOf(w), screenWidth, screenHeight).entries.map { it.element }
        val options = LaunchChooser.options(label, elements, profiles?.get(packageName).orEmpty()) ?: return null
        return LaunchChoice(pkg, options)
    }

    /** 読み取り可能な上部ウィンドウは、システム パッケージ (起動前の [packageName] や [before] ではありません): そのパッケージによって置き換えられます。はヌルではありません。 システムに付属する */
    private fun systemPickerOf(packageName: String, before: String?): String? {
        val pkg = topWindowPackage() ?: return null
        return pkg.takeIf { it != packageName && it != before && isSystemPackage(it) }
    }

    private val systemPackages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** パッケージ。ミカは、組み込みアシスタントに表示できないパッケージを尋ねます (パッケージの表示は Android 11 以降)。 */
    private fun isSystemPackage(pkg: String): Boolean = pkg in systemPickers || systemPackages.getOrPut(pkg) {
        val flags = runCatching { a11y.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull()
        if (flags != null) flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        else runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false)
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = a11y.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** は、他のユーザーにもインストールされているアプリです (パッケージ名 → それらのユーザーの名前)。初めて組み込みアシスタントに問い合わせてから、これを使用してください。質問できなかったときは思い出さずに、次回もう一度質問してください。 */
    @Volatile private var profiles: Map<String, List<String>>? = null

    override suspend fun otherProfiles(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        profiles ?: readProfiles()?.also { profiles = it }.orEmpty()
    }

    private fun readProfiles(): Map<String, List<String>>? {
        val me = myUser
        val users = AppProfiles.parseUsers(runCatching { shell.listUsers() }.getOrNull() ?: return null).filter { it.id != me && it.running }
        return AppProfiles.alsoIn(users.associateWith { u ->
            AppProfiles.parsePackages(runCatching { shell.listPackages(u.id) }.getOrNull() ?: return null)
        })
    }

    /** は、開くことができる別のアプリです (ルールについては、[AppCopies] を参照してください。ユーザーがどのアプリを使用できるかは、内蔵アシスタントによって決定されます)。尋ねられるたびに、尋ねられなければ空です。 */
    override suspend fun appCopies(): List<AppCopy> = withContext(Dispatchers.IO) {
        runCatching { AppCopyReader.read(shell, listApps()) }.getOrNull().orEmpty()
            .also { copies -> copiedPackages = copiedPackages + copies.map { it.packageName } }
    }

    /** には別のパッケージがリストされています: 別のパッケージ ([switchedTo]) に変更しましたか?彼らに聞いてみてください。 */
    @Volatile private var copiedPackages: Set<String> = emptySet()

    /** タスクが属するユーザー。タスクが存在しない場合、またはタスクを要求できない場合は -1。 */
    private fun userOf(task: Int): Int = if (task < 0) -1 else runCatching { shell.userOfTask(task) }.getOrDefault(-1)

    // ---------- 検証 ----------

    /**
     * インターフェイスが静かになった後でアクションを実行し、前面と背面の指紋を比較します。フロントデスクが切り取られているかどうかを確認してください。アクションの前後でホーム画面を見つめる: アクションにより、アプリがユーザーのホーム画面で開き、その後元の画面に戻ります。
     * は結果で開かれたものとして報告されます ([takeSpill] を参照)。前のアクションでホーム画面が開くまでに時間がかかりました。このアクションを実行しない場合は、最初に画面の変化について説明しましょう。
     */
    private suspend fun verified(relocatedTo: String? = null, action: suspend () -> Boolean): ActionResult {
        lateSpill()?.let { return ActionResult.Failed("opened_elsewhere", hintLateSpill(it.opened)) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        val w = armSpillWatch()
        val changed = withEventClock { clock ->
            clock.mark()
            if (action()) awaitChange(clock, beforeTree, beforeFrame) else null
        } ?: return ActionResult.Failed("action_failed")
        // は、移動されたタスクから別のタスクを開き、それを一緒に組み込みました。
        val profile = adoptFromAdopted(w)
        val spill = takeSpill(w)
        return ok(changed = changed || spill?.opened?.pulledBack == true, relocatedTo = relocatedTo, spill = spill, inProfile = profile)
    }

    // ---------- ユーザーのホーム画面を開きます ----------

    /** アクションの追跡: [since] 当初、その時点でメイン画面のトップタスクは [topBefore] でしたが、その時点でミカの画面では [agentTopBefore] がトップタスクでした。 */
    private class Watch(val since: Long, val topBefore: Int, val agentTopBefore: Int)

    /** アプリを元に戻し、オプションを認識しました (システムがどれを開くかを尋ねたとき)。 */
    private class Spill(val opened: OpenedElsewhere, val chooser: LaunchChoice?)

    /** 最新のアクションの追跡。 [SPILL_WINDOW_MS] の後にクリアされます。 */
    @Volatile private var watch: Watch? = null

    /** 判定が記録された時刻：同じ項目は二度判定されません。 */
    @Volatile private var consumed = 0L

    /** タスクをユーザーのホーム画面からミカ画面に戻し、そこからタスクを開きます。これらのタスクは、上部の対象アプリによって開かれたものとしてカウントされ、削除されません。 */
    private val adopted: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * システムはユーザーのホーム画面でどれを開くかを尋ね、ユーザーがそれを選択するのを待ちます。 [taskId] はそのウィンドウのタスクについて尋ね、 [packageName] はそのパッケージについて尋ね、 [app] はどのアプリを尋ねるか、
     * [topBefore] 出現前のホーム画面のトップタスク、[agentTop] その時のミカの画面のトップタスク、出現時の[since]。
     * 選択ボックスは決して移動されません。画面が移動された場合は、新しい画面に従って再構築する必要があり、再構築時に閉じられる場合があります。ミカは他のアプリでそれを再度開くことはありません (開くのは元のアプリである必要があります)。
     */
    class UserPick(val taskId: Int, val packageName: String, val app: String?, val topBefore: Int, val agentTop: Int, val since: Long)

    private val _userPick = MutableStateFlow<UserPick?>(null)
    /** は、ユーザーがホーム画面で選択するのを待っています。それに応じてタスクが停止され、ユーザーはカプセル上でタスクを選択するように求められます。 */
    val userPick: StateFlow<UserPick?> = _userPick.asStateFlow()

    /** ユーザーが選択した後: 選択したアプリはユーザーが個人的に処理する必要があり ([NeedsUser]、ホーム画面に留まる)、残りは完了します (結果は次の画面の読み取りまたはアクションで報告されます)。 */
    sealed interface PickResult {
        data object Settled : PickResult
        class NeedsUser(val taskId: Int, val packageName: String, val app: String?, val userId: Int) : PickResult
    }

    /** は、ユーザーがタスクを完了した後にモデルに通知する必要があり、次回画面が読み取られるかアクションが実行されたときに報告されます。 */
    @Volatile private var pendingReport: Spill? = null

    /** ユーザーがホーム画面で何かをクリックしたときの時間と情報 (アクションの前後の短い期間のみ記録されます)。 */
    private val userTaps = java.util.concurrent.ConcurrentLinkedDeque<MainScreenSpill.Tap>()
    private val spillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var tapWatch: Job? = null
    @Volatile private var tapWatchUntil = 0L

    /** 用户回主屏桌面的导航证据；桌面 App 按 HOME 解析，不写死。 */
    private val homeApps: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching { a11y.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList()).toSet()
    }

    /** 不算开到主屏上的:Mika 自己、桌面 App。 */
    private val notSpills: Set<String> by lazy { homeApps + a11y.packageName }

    /** アクションを起こす前に: 内蔵アシスタントを使用してホーム画面を見つめ、現時点で 2 つの画面の上部にあるタスクを書き留めてください。画面がない場合、画面が引き継がれている場合、またはアシスタントがサポートしていない場合は、null (見つめられていない) になります。 */
    private fun armSpillWatch(): Watch? {
        if (!hasDisplay || handedOver()) return null
        val since = SystemClock.uptimeMillis()
        watchUserTaps(since + SPILL_WINDOW_MS)
        val top = runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }.getOrNull() ?: return null
        val agentTop = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        return Watch(since, top, agentTop).also { watch = it }
    }

    /** は、追跡中にホーム画面でのユーザーのクリックを記録します (Mika 自身のフローティング レイヤーはカウントされません)。期限が切れると停止します。 */
    private fun watchUserTaps(until: Long) {
        tapWatchUntil = maxOf(tapWatchUntil, until)
        if (tapWatch?.isActive == true) return
        tapWatch = spillScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                    val systemTouch = e.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    if (MainScreenSpill.isUserTap(e.eventType) &&
                        // Mika 浮层自己的控件点击不是用户打开了别的 App;系统触摸事件没有这种包名歧义
                        (systemTouch || e.packageName?.toString() != a11y.packageName)
                    ) {
                        // 系统触摸事件带有真实发生时刻;通知栏、桌面等不发控件点击事件的入口也不能漏掉
                        val at = if (systemTouch) e.eventTime else SystemClock.uptimeMillis()
                        userTaps.addLast(MainScreenSpill.Tap(at, e.packageName?.toString()))
                        while (userTaps.size > MAX_USER_TAPS) userTaps.pollFirst()
                    }
                }
            }
            while (true) {
                val left = tapWatchUntil - SystemClock.uptimeMillis()
                if (left <= 0) break
                delay(left)
            }
            collecting.cancel()
        }
    }

    /**
     * アクション後: アクションの開始後にホーム画面にポップアップ表示されるタスクについて内蔵アシスタントに質問し、[MainScreenSpill] を押して判断します。ホーム画面を開いてミカの画面に戻ります。 ユーザーが現在使用しているアプリで開かれた
     * は何も報告しないだけです。どれもヌルではありません。
     */
    private suspend fun takeSpill(w: Watch?): Spill? {
        if (w == null || handedOver()) return null
        val b = runCatching { shell.mainArrivals(maxOf(w.since, consumed + 1)) }.getOrNull() ?: return null
        val arrivals = parseArrivals(b)
        arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
        var last: Spill? = null
        for (v in MainScreenSpill.decide(arrivals, w.since, SPILL_WINDOW_MS, w.topBefore, notSpills, userTaps.toList(), homeApps)) {
            val a = v.arrival
            val pkg = a.packageName ?: continue
            // アプリケーションのアクセス境界からは見えないユーザーのホーム画面の画面を開きます: 無効な項目は移動または読み取りできません
            appAccess.current().denial(pkg)?.let { throw it }
            last = when (v) {
                // システムがどれを開くかを尋ねてきたら、最初に選択ボックスを押します。ユーザーが手動で処理する必要があるポップアップ ウィンドウ (ログイン認証やアカウント確認など) はホーム画面に残り、ミカの画面には移動されません。
                is MainScreenSpill.PullBack -> leaveForUser(a, pkg, w) ?: leavePopup(a, pkg) ?: pullBack(a, pkg) ?: last
                is MainScreenSpill.InUse -> Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId)), null)
            }
        }
        if (last != null) Log.i(TAG, "opened on the main screen after an action: ${last.opened}")
        return last
    }

    /**
     * 最後のアクションの追跡が期限切れになっていない場合 (そのアクションはしばらくしてから開始された場合) を再度尋ねます。有効期限が切れたらクリアします。
     * ホーム画面で選択・加工した後にユーザーが言いたいこと、まずはこれについて話しましょう。
     */
    private suspend fun lateSpill(): Spill? {
        pendingReport?.let { pendingReport = null; return it }
        val w = watch ?: return null
        val spill = takeSpill(w)
        if (SystemClock.uptimeMillis() > w.since + SPILL_WINDOW_MS) watch = null
        return spill
    }

    /**
     * ホーム画面に表示されるポップアップ ウィンドウは、ユーザーが個人的に処理する必要があります (ログイン確認、アカウント確認、支払い確認など)。ウィンドウを移動せず、ユーザー画面に留まらせてください。
     * 基準: システム アカウント ログイン サービスのページ (アカウント認証が登録されており、デスクトップ アイコンがないパッケージ) で、上部のウィンドウが画面全体ではない (ダイアログ ボックス、下部のポップアップ レイヤー)、ウィンドウにパスワード ボックスがある、またはページ自体に最近のタスクが入力されていない (excludeFromRecents)。
     * この種のウィンドウは、Mika の画面に移動するとユーザーの目から消え、ユーザーはホーム画面で確認をクリックできなくなります。ヌルではありません。
     * noHistory はカウントされません。多くのアプリの最初のページに noHistory があり、通常のアプリは含まれる場合、ポップアップ ウィンドウとして扱われます。
     */
    private suspend fun leavePopup(a: MainScreenSpill.Arrival, pkg: String): Spill? =
        if (needsUserWindow(a, pkg)) popup(a, pkg) else null

    /** ホーム画面上のこのタスクの最上位ウィンドウは、ユーザーが手動で処理する必要があるポップアップ ウィンドウです ([leavePopup] を参照)。 [POPUP_SETTLE_MS] まであと 2 回まで待ちます。 */
    private suspend fun needsUserWindow(a: MainScreenSpill.Arrival, pkg: String): Boolean {
        if (isAuthenticatorUi(pkg)) return true
        val activity = a.topActivity?.let {
            runCatching { a11y.packageManager.getActivityInfo(ComponentName(pkg, it), 0) }.getOrNull()
        }
        if (activity != null && activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0) return true
        val metrics = a11y.resources.displayMetrics
        // ポップアップしたウィンドウがまだオープニング アニメーション中である可能性があります (画面全体より小さく描画されています)。通常のアプリがポップアップ ウィンドウとして扱われないように、停止するまで待ってからもう一度確認してください。
        var window = topWindowOf(pkg)
        if (window == null) {
            delay(POPUP_SETTLE_MS)
            window = topWindowOf(pkg) ?: return false
        }
        if (isNarrow(window, metrics)) {
            delay(POPUP_SETTLE_MS)
            val settled = topWindowOf(pkg) ?: return false
            if (isNarrow(settled, metrics)) return true
            window = settled
        }
        return ScreenReader.read(listOf(window), metrics.widthPixels, metrics.heightPixels).passwordField
    }

    private fun topWindowOf(pkg: String): AccessibilityWindowInfo? =
        ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg }

    /**
     * このパッケージはシステムのアカウント ログイン サービスですか: アカウント認証子 (AccountManager.authenticatorTypes、プラットフォーム データ) を登録しましたが、デスクトップ アイコンがありません。
     * (LAUNCHER エントリなし)。この種のパッケージには独自のホームページがありません。ホーム画面に表示されるページは、アカウントの追加、ログイン、確認、コードの確認などのページのみです。これらはユーザーの仕事です。
     * は画面全体を占有し、パスワード ボックスがなく、ミカの画面に移動できません。デスクトップ アイコン付きの認証パッケージ (連絡先を同期するチャット アプリ、電子メール アプリなど) はカウントされません。
     * 彼らの会話、共有、書き込みページは通常どおりに戻されました。オーセンティケータ テーブルは毎回次のように読み取ります。新しくインストールされたオーセンティケータも認識できます。この手順は、ホーム画面にページがポップアップ表示された場合にのみ実行されます。
     */
    private fun isAuthenticatorUi(pkg: String): Boolean {
        val authenticators = runCatching { AccountManager.get(a11y).authenticatorTypes.mapNotNull { it.packageName }.toSet() }
            .getOrDefault(emptySet())
        if (pkg !in authenticators) return false
        // デスクトップ アイコンを含むパッケージは、Mika に表示される必要があります (リスト内の LAUNCHER を確認してください)。見つからない場合は、そこには存在しないことを意味します。
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val hasLauncher = runCatching { a11y.packageManager.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(true)
        return MainScreenSpill.authenticatorUi(pkg, authenticators, hasLauncher)
    }

    /** ウィンドウは、ダイアログ ボックス、下部のポップアップ、フローティング ウィンドウなど、画面全体をカバーしません。 */
    private fun isNarrow(window: AccessibilityWindowInfo, metrics: android.util.DisplayMetrics): Boolean {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return bounds.width() < metrics.widthPixels * FULL_SCREEN_SHARE || bounds.height() < metrics.heightPixels * FULL_SCREEN_SHARE
    }

    private suspend fun popup(a: MainScreenSpill.Arrival, pkg: String): Spill =
        Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId), needsUser = true), null)

    /**
     * ホーム画面に開くのは、システムが「どれで開くか」を尋ねるウィンドウです (システム セレクターまたはシステム パッケージ ウィンドウのオプションは、インストールされている 2 つのアプリと一致します。
     * 各システムの選択ボックスはオリジナルのものではない場合があります): 移動せず、ユーザーが自分の画面で選択するように任せ、書き留めてタスクを待ちます。ヌルではありません。
     */
    private suspend fun leaveForUser(a: MainScreenSpill.Arrival, pkg: String, w: Watch): Spill? {
        val system = isSystemPackage(pkg)
        val choice = if (system) mainChooser(pkg) else null
        if (MainScreenSpill.handling(system, pkg in systemPickers, choice != null) != MainScreenSpill.Handling.LeaveForUser) return null
        val app = choice?.options?.firstOrNull()?.label ?: appLabel(pkg)
        _userPick.value = UserPick(a.taskId, pkg, app, w.topBefore, w.agentTopBefore, a.at)
        return Spill(OpenedElsewhere(pkg, app, pulledBack = false, picker = true), choice)
    }

    /** ホーム画面で開いているタスクをミカの画面に移動し (移動するだけで、再度開かないでください)、タスクが一番上に到達するまで待ちます。移動できない場合は null になります。 */
    private suspend fun pullBack(a: MainScreenSpill.Arrival, pkg: String, byUser: Boolean = false): Spill? {
        if (!runCatching { shell.moveTask(a.taskId, displayId) }.getOrDefault(false)) {
            Log.w(TAG, "could not move task ${a.taskId} ($pkg) from the main screen")
            return null
        }
        adopted += a.taskId
        // どのアプリが開かれるか事前にわかりません。アプリを元に戻して占有します。占有できない場合は撤退しません。
        if (checkOtherTasks) reserve(pkg)
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (runCatching { shell.topTaskId(displayId) }.getOrDefault(-1) != a.taskId && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val profile = if (isSystemPackage(pkg)) null else inProfileOf(pkg, a.userId)
        return Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = true, inProfile = profile, byUser = byUser), null)
    }

    /**
     * ホーム画面にシステム パッケージ ウィンドウが表示され、どれを開くかを尋ねられます。どのアプリを要求しているのか事前にわからないため、インストールされている 2 つのアプリの名前を使用してウィンドウ内のオプションを確認します。
     * (これは、同じアプリのコピーを 2 つインストールした場合の状況です);このシステム ウィンドウのみを読み取ります。そうでない場合は null になります。
     */
    private suspend fun mainChooser(pkg: String): LaunchChoice? {
        val w = ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg } ?: return null
        val elements = ScreenReader.read(listOf(w), display.width, display.height).entries.map { it.element }
        for ((app, names) in otherProfiles()) {
            val label = appLabel(app) ?: continue
            LaunchChooser.options(label, elements, names)?.let { return LaunchChoice(pkg, it) }
        }
        return null
    }

    /**
     * 画面を変更する次のアクションの前にタスクが調整されます。ユーザーがホーム画面で選択を完了するまで待ちます (選択する前にホーム画面を見つめ続けてください)。選択後、ホーム画面の通常ページを開き、ミカの画面に戻ります。
     * (移動のみ。開くのは元のアプリのままです);ユーザーが手動で処理する必要があるもの (パスワードが必要、支払い、指紋などウィンドウが読み取れない) はホーム画面に留まり、[PickResult.NeedsUser] に戻ります。
     * は計算に含まれます。ユーザーは問題をオフにした後、真実を話します。待機していない場合は null です。
     */
    suspend fun awaitUserPick(): PickResult? = withContext(Dispatchers.IO) {
        val p = _userPick.value ?: return@withContext null
        try {
            while (runCatching { shell.displayOfTask(p.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY) {
                runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }
                delay(PICK_POLL_MS)
            }
            val gone = SystemClock.uptimeMillis()
            runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }
            val deadline = gone + PICK_WAIT_MS
            while (true) {
                val arrivals = runCatching { shell.mainArrivals(p.since) }.getOrNull()?.let(::parseArrivals).orEmpty()
                val opened = MainScreenSpill.picked(arrivals, p.taskId, gone, PICK_SLACK_MS, PICK_WAIT_MS, p.topBefore, notSpills, homeApps)
                arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
                if (opened != null) {
                    val pkg = opened.packageName!!
                    if (needsUser(pkg) || needsUserWindow(opened, pkg)) {
                        return@withContext PickResult.NeedsUser(opened.taskId, pkg, appLabel(pkg) ?: p.app, opened.userId)
                    }
                    pendingReport = pullBack(opened, pkg)
                    return@withContext PickResult.Settled
                }
                // 選択したアプリがミカの画面で開きます（そこから開きます）
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                if (top >= 0 && top != p.agentTop && top !in adopted) {
                    adopted += top
                    waitStable(SETTLE_CAP_MS)
                    val pkg = runCatching { shell.packageOfTask(top) }.getOrNull() ?: p.packageName
                    val profile = inProfileOf(pkg, runCatching { shell.userOfTask(top) }.getOrDefault(-1))
                    pendingReport = Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = true, inProfile = profile), null)
                    return@withContext PickResult.Settled
                }
                if (SystemClock.uptimeMillis() >= deadline) break
                delay(POLL_MS)
            }
            pendingReport = Spill(OpenedElsewhere(p.packageName, p.app, pulledBack = false, picker = true, closed = true), null)
            PickResult.Settled
        } finally {
            _userPick.value = null
        }
    }

    /** ユーザーが処理 ([PickResult.NeedsUser]) を終了した後、[続行] をクリックします。まだホーム画面にある場合は、ミカの画面に戻って続行します。すでに閉店している場合は、真実を伝えてください。 */
    suspend fun afterUserStep(r: PickResult.NeedsUser) = withContext(Dispatchers.IO) {
        val still = runCatching { shell.displayOfTask(r.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY
        val moved = if (still) pullBack(MainScreenSpill.Arrival(r.taskId, r.packageName, r.userId, 0, Display.DEFAULT_DISPLAY, false, true, Display.DEFAULT_DISPLAY), r.packageName, byUser = true) else null
        pendingReport = moved ?: Spill(OpenedElsewhere(r.packageName, r.app, pulledBack = false, byUser = true), null)
    }

    /**
     * ホーム画面上のこのアプリのウィンドウでは、ユーザーが手動で操作する必要があります。パスワード ボックスがあるか、ツリー内で何も読み取れないためにホーム画面のスクリーンショットを撮ると、システムはスクリーンショット ウィンドウを報告します (キャプチャされた画面は保持されず、すぐに破棄されます)。
     * は、そこに何が書かれているかではなく、そこにあるかどうかだけを調べます。
     */
    private suspend fun needsUser(pkg: String): Boolean {
        // はユーザーのホーム画面のウィンドウを読み取りますが、アプリケーションのアクセス境界は見えません。無効になっているものは読み取られません。
        appAccess.current().denial(pkg)?.let { throw it }
        // 表示されたウィンドウを読むにはしばらく時間がかかります。内容が読めるようになったら (またはパスワード ボックスが表示されたら)、結論を導き出します。それでも制限時間内に読めない場合は、アンチスクリーンショットかどうかを確認してください。
        val deadline = SystemClock.uptimeMillis() + PICK_READ_MS
        while (true) {
            val mine = ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).filter { it.root?.packageName?.toString() == pkg }
            val r = mine.takeIf { it.isNotEmpty() }?.let { ScreenReader.read(it, display.width, display.height) }
            val readable = r?.entries?.any { it.element.meaningful } == true
            if (r?.passwordField == true || readable) return MainScreenSpill.needsUser(r?.passwordField == true, readable, secureWindow = false)
            if (SystemClock.uptimeMillis() >= deadline) {
                val shot = runCatching { a11y.takeMainScreenshot() }.getOrNull()
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = shot is ScreenAccessibility.MainShot.Secure)
            }
            delay(POLL_MS * 2)
        }
    }

    /**
     * アクションの前、ミカの画面の一番上のタスクは、元に戻された (またはそこから開かれた) タスクでした。アクションの後、最上位のタスクは別のタスクに置き換えられました。
     * これもその道路で開通しているので計算に含まれます。アプリのコピーが 2 つインストールされている場合、どちらを開く必要がありますか?
     */
    private suspend fun adoptFromAdopted(w: Watch?): String? {
        if (w == null || w.agentTopBefore !in adopted) return null
        val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (top < 0 || top == w.agentTopBefore || top in adopted || top == _target.value?.taskId) return null
        adopted += top
        val pkg = runCatching { shell.packageOfTask(top) }.getOrNull() ?: return null
        return inProfileOf(pkg, runCatching { shell.userOfTask(top) }.getOrDefault(-1))
    }

    /** 2 つのコピーがインストールされている場合に開かれるアプリのバージョン。Mika と同じユーザーが使用するものが元のバージョンです。コピーが 1 つだけインストールされている場合、または表示できない場合は、null になります。 */
    private suspend fun inProfileOf(pkg: String, userId: Int): String? {
        if (userId < 0 || pkg !in otherProfiles()) return null
        return if (userId == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE
    }

    private fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
        val tasks = b.getIntArray(MainScreenWatch.KEY_TASK) ?: return emptyList()
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val users = b.getIntArray(MainScreenWatch.KEY_USER)
        val at = b.getLongArray(MainScreenWatch.KEY_AT)
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)
        val activity = b.getStringArray(MainScreenWatch.KEY_ACTIVITY)
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)
        if (packages == null || users == null || at == null || from == null || same == null || standard == null || now == null) return emptyList()
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i], activity?.getOrNull(i))
        }
    }

    private fun hintLateSpill(o: OpenedElsewhere): String {
        val name = "${o.app ?: o.packageName} (${o.packageName})"
        val copy = when (o.inProfile) {
            OpenedElsewhere.OTHER_PROFILE -> " It is the other copy of the app (clone or other profile): if the user did not ask for that copy, go back and pick the other option."
            OpenedElsewhere.MAIN_PROFILE -> " It is the main copy of the app."
            else -> ""
        }
        return when {
            o.closed -> "The user closed the system's question on their screen without picking, so nothing was opened. Decide what to do next; do not simply repeat the action."
            o.byUser && !o.pulledBack -> "$name needed the user; they handled it on their own screen and tapped continue, and it has closed. Observe before acting."
            o.byUser -> "$name needed the user; they handled it on their own screen and tapped continue. It is now in front on Mika's screen. Observe before acting."
            o.needsUser && o.onUserScreen -> "It was $name: a popup on the user's screen that needs them (a sign-in, verification or confirmation step). " +
                "It was left there on purpose; do not repeat the action. Call hand_to_user so they can finish it, then observe before acting."
            o.pulledBack -> "It was $name, now in front on Mika's screen.$copy Observe before acting."
            else -> "It was $name, which the user is using on their own screen, so it was left there. " +
                "Do not repeat the previous action: call hand_to_user with reason other so the user can do that step there, or wait and try again later."
        }
    }

    /** インターフェイスが安定した後にフィンガープリントを比較します (ルールについては [ChangeWait] を参照)。変更を確認したらすぐに true を返します。 */
    private suspend fun awaitChange(clock: EventClock, beforeTree: Long, beforeFrame: IntArray?): Boolean =
        ChangeWait.awaitStableChange(
            now = SystemClock::uptimeMillis,
            sleep = { delay(it) },
            frame = ::frameSignature,
            quietForMs = clock::quietForMs,
            treeChanged = { treeHash() != beforeTree },
            beforeFrame = beforeFrame,
        )

    /** フォアグラウンドがターゲット アプリではなく、それが呼び出すシステム ページやシステム セレクターでもない場合、現在のフォアグラウンド パッケージ名と、それがターゲット アプリによって開かれているかどうかを返します。 */
    private fun switchedTo(): AppSwitch.Switch? {
        val target = _target.value
        return AppSwitch.switchedTo(
            targetPackage,
            ScreenReader.topPackage(windows()),
            // ユーザーのホーム画面から戻されたもの (およびホーム画面から開かれたもの) は、この方法で開かれたターゲット アプリでもあります。
            sameTask = {
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                top >= 0 && (target != null && target.taskId == top || top in adopted)
            },
            partOfTask = chooserPackage?.let { systemPickers + it } ?: systemPickers,
            settingsPage = { it == settingsPackage },
            // 同じアプリが 2 回インストールされています。一番上のアプリは別のユーザーのアプリ (ターゲット タスクでも、元に戻されたアプリでもありません) に置き換えられ、削除されたとみなされます。
            // ターゲットが別のコピーである場合、またはこのアプリが別のコピーをリストしている場合にのみタスクを要求します (コピーを 1 つだけインストールするアプリは、各アクションに対して 2 回以上要求することはできません)
            otherCopy = {
                target != null && target.userId >= 0 && (target.userId != myUser || target.packageName in copiedPackages) && run {
                    val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                    top >= 0 && top != target.taskId && top !in adopted && userOf(top).let { it >= 0 && it != target.userId }
                }
            },
        )
    }

    /**
     * が開かれるとき、ターゲット アプリはまだフォアグラウンドに到達しておらず (システムは最初にどれを開くかを尋ね、起動が遅い)、その後トップに到達します。そのタスク番号を書き留めます。
     * タスク サービスをミュートし、移動されたかどうかを確認できます。
     */
    private suspend fun adoptTargetTask(): String? {
        val t = _target.value ?: return null
        if (t.taskId >= 0 || foregroundTaskPackage() != t.packageName) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        val user = userOf(task)
        _target.value = TargetApp(t.packageName, task, user)
        // システムはどちらを開くかを尋ねました (2 つのコピーがインストールされています)。どちらが開かれたと報告されていますか?
        return inProfileOf(t.packageName, user)
    }

    /** は「切り取られたかどうか」というアクション結果を保持します。 [spill] は、このアクションによりユーザーのホーム画面で開かれるアプリです。[inProfile] は [ActionResult.Ok.inProfile] を参照してください。 */
    private suspend fun ok(
        changed: Boolean? = null, relocatedTo: String? = null, verification: Verification? = null,
        hint: String? = null, secret: Boolean = false, spill: Spill? = null, inProfile: String? = null,
    ): ActionResult.Ok {
        val adopted = adoptTargetTask()
        val shown = shownCopy()
        val profile = adopted ?: inProfile ?: shown?.first
        val sw = switchedTo()
        return ActionResult.Ok(
            changed = changed, appSwitched = sw?.packageName, switchedByTarget = sw?.byTarget == true,
            relocated = relocatedTo != null, relocatedTo = relocatedTo, verification = verification, hint = hint, secret = secret,
            chooser = spill?.chooser, opened = spill?.opened, inProfile = profile,
            profileUser = shown?.second, requestedUser = requestedUser.takeIf { shown != null },
        )
    }

    /** アプリを開くときに要求されたユーザーのコピー (別のコピー)。要求された元のコピー、または今回アプリが開かれていない場合は null。 */
    @Volatile private var requestedUser: Int? = null

    /**
     * ターゲット アプリの 2 つのコピーがインストールされている場合 (もう 1 つのコピーがリストされている、もう 1 つのコピーが要求されている、またはもう 1 つのコピーが開かれている)、現時点ではどちらのコピーがミカ画面上にあります: (プロファイル内、ユーザーが所有)。
     * のコピーが 1 つだけインストールされている場合、それが先頭にない場合、または読み取れない場合は、null になります。アプリのコピーが 1 つだけインストールされている場合は、それ以上の質問は行われません。
     */
    private fun shownCopy(): Pair<String, Int>? {
        val t = _target.value ?: return null
        if (requestedUser == null && t.packageName !in copiedPackages && (t.userId < 0 || t.userId == myUser)) return null
        if (foregroundTaskPackage() != t.packageName) return null
        val user = userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).takeIf { it >= 0 } ?: return null
        return (if (user == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE) to user
    }

    // ---------- 数値分析 ----------

    /**
     * は数値を解析して操作可能なノードにします。再配置後の 2 番目の値は取得された要素の現在の名前になります。それ以外の場合は null になります。
     * 最新のスナップショット内のノードがまだ存在し、変更されていない場合は、それを直接使用します。スナップショットの有効期限が切れたり、ノードが変更された場合は、元の要素の特性に従って、現在のインターフェイス内で対応する一意のスナップショットを見つけます。
     */
    private fun resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
        val (entry, snapshot) = when (val l = ids.lookup(ref, snapshotId)) {
            is NodeIds.Lookup.Unknown -> return null
            is NodeIds.Lookup.Current -> {
                if (unchanged(l.entry)) return l.entry.node to null
                l.entry to l.snapshot
            }
            is NodeIds.Lookup.Old -> l.entry to l.snapshot
        }
        val now = read()
        return when (val r = relocate(entry.locator, snapshot.locators, now.entries.map { it.locator }, min(screenWidth, screenHeight))) {
            is Relocation.Found -> now.entries[r.index].let { it.node to it.element.name }
            else -> null
        }
    }

    /** ノードはまだ存在しており、タイプ、名前、リソース ID、および場所はスナップショットと同じです (リストの再利用後に同じノードの内容が変更されている可能性があります)。 */
    private fun unchanged(entry: NodeEntry): Boolean {
        val n = entry.node
        if (!n.refresh()) return false
        val now = ScreenReader.locatorOf(n, screenWidth, screenHeight) ?: return false
        return now == entry.locator.copy(context = null)
    }

    private sealed interface Field {
        /** [tapped]: 指定された要素自体は入力ボックスではありません。実際の入力ボックスは、クリックした後にのみ表示されます (またはフォーカスされます)。 */
        class Found(val node: AccessibilityNodeInfo, val tapped: Boolean = false, val relocatedTo: String? = null) : Field
        class Missing(val reason: String) : Field
    }

    /**
     * 入力するボックスを見つけます。番号を考えると、要素自体でもその中の入力ボックスでもありませんが、入力の入り口 (ホームページ上の「偽の検索ボックス」) のように見えます。
     * (ボタンではなく、プロンプトまたは検索などの名前でクリックできます)、最初にクリックします。クリックが成功し、以前は存在しなかったフォーカスのある入力ボックスが表示された場合は、そのボックスを使用します。
     * 番号なし: 現在入力フォーカスを保持しているボックス。
     */
    private suspend fun findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
        if (ref != null && snapshotId != null) {
            val (node, relocatedTo) = resolve(ref, snapshotId) ?: return Field.Missing("ref_expired")
            val editable = if (node.isEditable) node else descendant(node) { it.isEditable }
            if (editable != null) return Field.Found(editable, relocatedTo = relocatedTo)
            if (!tapToFocus || !looksLikeInputEntry(node)) return Field.Missing("not_editable")
            val focusedBefore = focusedEditable()
            val clicked = withEventClock { clock ->
                clock.mark()
                click(node).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
            }
            val now = focusedEditable()
            return if (clicked && now != null && now != focusedBefore) Field.Found(now, tapped = true, relocatedTo = relocatedTo) else Field.Missing("not_editable")
        }
        return focusedEditable()?.let { Field.Found(it) } ?: Field.Missing(NO_FOCUS)
    }

    /** 「同じ入力ボックス」を識別するために使用される特性: タイプ、リソース ID、場所のサイズ。 */
    private data class FieldShape(val cls: String?, val rid: String?, val rect: android.graphics.Rect) {
        companion object {
            fun of(n: AccessibilityNodeInfo) =
                FieldShape(n.className?.toString(), n.viewIdResourceName, android.graphics.Rect().also { n.getBoundsInScreen(it) })
        }
    }

    private fun sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
        val b = FieldShape.of(n)
        if (a.cls != b.cls || a.rid != b.rid) return false
        // のサイズはほぼ同じであるはずです。ページ全体が少し移動できる位置（入力時にページが上に移動することがよくあります）で、番号の検索にも同じ上限が使用されます。
        val short = min(screenWidth, screenHeight)
        val sizeTol = short * SAME_FIELD_SIZE
        val moveTol = short * RELOCATE_MAX_SHIFT
        return kotlin.math.abs(a.rect.width() - b.rect.width()) <= sizeTol && kotlin.math.abs(a.rect.height() - b.rect.height()) <= sizeTol &&
            kotlin.math.hypot((a.rect.centerX() - b.rect.centerX()).toDouble(), (a.rect.centerY() - b.rect.centerY()).toDouble()) <= moveTol
    }

    /** 画像入力入口: クリック可能、ボタンタイプではなく、プロンプトまたは名前画像検索付き。 */
    private fun looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(n) { it.isClickable } ?: return false
        val cls = target.className?.toString().orEmpty()
        if (cls.endsWith("Button")) return false
        if (ScreenReader.placeholderOf(n) != null || !n.hintText.isNullOrBlank()) return true
        val name = ScreenReader.ownLabel(n) ?: ScreenReader.ownLabel(target) ?: return false
        return SEARCH_WORDS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * この画面上に入力フォーカスを保持しているものがあります (ツリー内の入力ボックスでなくても、フォーカスを保持しているアプリ自体によって描画されたインターフェイス全体など)、または入力メソッドが再生されています。
     * 何もない場合は、ボタンを押す枠がありません。ツリーに表示されない入力ボックスは、フォーカスを保持したままでのみ受信して貼り付けることができます。
     */
    private fun somethingHoldsInputFocus(): Boolean =
        imeBox() != null || ScreenReader.contentWindows(windows()).any { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null }

    /** この画面には、入力フォーカスの編集可能なボックスが表示されます。フォーカスがコンテナー (Web ページなど) にある場合は、そのコンテナー内でフォーカスされている編集可能なノードを見つけます。 */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        for (w in ScreenReader.contentWindows(windows())) {
            val focused = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (focused.isEditable) return focused
            descendant(focused) { it.isFocused && it.isEditable }?.let { return it }
        }
        return null
    }

    // ---------- 低レベル操作 ----------

    /** は、ノード (または最も近いポイント可能な祖先) をクリックすることを優先します。それが不可能な場合は、その中心でジェスチャが行われ、最後にタッチが注入されます。 */
    private suspend fun click(node: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(node) { it.isClickable && it.isEnabled }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

    /** この時点でクリックします。指を下ろす前にしばらく停止し、ランダムな瞬間を押し続けます。アクセシビリティ ジェスチャが失敗した場合、組み込みアシスタントがタッチを挿入します。 */
    private suspend fun touch(at: Pair<Int, Int>): Boolean {
        val (x, y) = at
        delay(pacing.approach())
        val press = pacing.press()
        if (a11y.gesture(displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, press)) return true
        if (pacing.paced && injectStroke(listOf(GesturePacing.Point(x.toDouble(), y.toDouble(), 0), GesturePacing.Point(x.toDouble(), y.toDouble(), press)))) return true
        return shell.injectTap(displayId, x, y)
    }

    /**
     * [points] に沿って指をスライドさせます。リズムがある場合、[GesturePacing.stroke] を押して各ポイントの時間を調整すると、内蔵アシスタントがポイントごとにリズムを挿入します (アプリは最初は速く、次に遅いという実際の速度を確認します)。
     * アシスタントが注意を払うことができない場合は、バリアフリー ジェスチャに戻り、同じ円弧に沿って歩きます (システムは経路に従って一定の速度で歩きますが、速度だけが欠落しています)。リズムがなければ、これまでと同じです。バリアフリーのジェスチャーは、一定時間一定の速度で動きます。
     */
    private suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
        if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
        delay(pacing.approach())
        val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, screenWidth to screenHeight)
        if (injectStroke(stroke.points)) return true
        return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
    }

    private fun injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
        shell.injectStroke(
            displayId,
            IntArray(points.size) { points[it].x.roundToInt() },
            IntArray(points.size) { points[it].y.roundToInt() },
            LongArray(points.size) { points[it].t },
        )
    }.getOrDefault(false)

    private fun pathOf(points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(points.first().first.toFloat(), points.first().second.toFloat())
        points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
    }

    /**
     * Web ページの編集領域 (contenteditable) は実際の入力のみを受け入れます。SET_TEXT は Web ページ上の単語のみを変更し、エディターは入力イベントを受け取ることができません。
     * アプリは入力がないものとして扱います（「完了」は点灯せず、保存せずに終了します）。このタイプの入力ボックスの場合は、元のコンテンツを選択して貼り付けるか、delete キーを押します。
     */
    private fun inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

    /**
     * 元のコンテンツを選択して貼り付けます (または削除キーを押してクリアします)。 null を返すということは、クリップボードが安全に使用できず (ユーザーの元のコンテンツを読み取ることができないなど)、何も変更されていないことを意味します。
     * クリップボードは、この貼り付け期間中のみミカに属します。アプリは、読み取り（単語が変更された）またはタイムアウト後にクリップボードを復元します。
     */
    private fun inputText(node: AccessibilityNodeInfo, text: String): Boolean? {
        node.refresh()
        val before = node.text?.toString().orEmpty()
        fun selectAll() {
            if (before.isEmpty()) return
            val range = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
        }
        if (text.isEmpty()) {
            selectAll()
            return before.isEmpty() || shell.injectKey(displayId, KeyEvent.KEYCODE_DEL)
        }
        clipLock.lock()
        clipHeld(true)
        try {
            if (runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED) != ShellService.CLIP_SET) return null
            runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
            selectAll()
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
            // アプリはクリップボードを非同期的に読み取ります。単語が変更されるのを待ってから復元します。最大でしばらく時間がかかる場合があります。
            val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (node.refresh() && node.text?.toString().orEmpty() != before) break
                Thread.sleep(POLL_MS)
            }
            return true
        } finally {
            runCatching { shell.restoreClip() }
            clipHeld(false)
            clipLock.unlock()
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private inline fun ancestorOrSelf(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= MAX_ANCESTORS) {
            if (test(n)) return n
            n = n.parent
            depth++
        }
        return null
    }

    private fun descendant(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val n = queue.removeFirst()
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    // ---------- 画面 ----------

    /** プレビュー フレームの粗い輝度グリッド。画像がない場合はnull。 */
    private fun frameSignature(): IntArray? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.lumaGrid(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    /** 画面が完全に黒いかどうか (スクリーンショット防止ウィンドウは仮想画面上で黒く描画されます)。画面がない場合は null になります。 */
    private fun frameBlack(): Boolean? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.isBlack(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    // ---------- イベントクロック ----------

    /** は、この画面上の最新のインターフェイス イベントの時間を記録します。 */
    private class EventClock {
        private val last = AtomicLong(SystemClock.uptimeMillis())
        fun mark() = last.set(SystemClock.uptimeMillis())
        fun quietForMs() = SystemClock.uptimeMillis() - last.get()

        /** は、連続する [quietMs] でインターフェイス イベントがなくなるまで待機します。 [capMs] は true が返されるまで待機します。 */
        suspend fun awaitQuiet(quietMs: Long, capMs: Long): Boolean {
            val deadline = SystemClock.uptimeMillis() + capMs.coerceAtLeast(0)
            while (true) {
                val now = SystemClock.uptimeMillis()
                val quietFor = now - last.get()
                if (quietFor >= quietMs) return true
                if (now >= deadline) return false
                delay(min(quietMs - quietFor, deadline - now).coerceIn(10, 100))
            }
        }
    }

    /** は、画面アクセシビリティ イベントのサブスクリプション中に [block] を実行します。サブスクリプションはブロックの前に確立され、アクションによって発生したイベントの最初のバッチが見逃されることはありません。 */
    private suspend fun <T> withEventClock(block: suspend (EventClock) -> T): T = coroutineScope {
        val clock = EventClock()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            a11y.eventsFor(displayId).collect { if (it.eventType and IGNORED_EVENTS == 0) clock.mark() }
        }
        try {
            block(clock)
        } finally {
            job.cancel()
        }
    }

    private companion object {
        const val TAG = "MikaScreen"
        /** アクションの開始後、一定時間以内にホーム画面に表示されたタスクは、このアクションによって開始されたものとしてカウントされます。 */
        const val SPILL_WINDOW_MS = 6_000L
        /**
         * 全画面ページとみなされるには、ウィンドウの幅と高さが少なくともメイン画面と同じくらいでなければなりません。これより小さいのはダイアログ ボックスと下部のポップアップです。
         * の方が幅が広いです。ステータス バー、ナビゲーション バー、穴掘りにより、全画面アプリ ウィンドウが画面より小さくなります。画面が水平の場合でも、ナビゲーション バーは横に表示されます。
         */
        const val FULL_SCREEN_SHARE = 0.8f
        /** ポップアップしたウィンドウがまだオープニング アニメーションの途中である可能性があります。しばらく待ってからもう一度見て、それが画面全体ではないかどうかを確認してください。 */
        const val POPUP_SETTLE_MS = 300L
        const val MAX_USER_TAPS = 32
        /** ユーザーがホーム画面の選択ボックスで選択した後、選択したアプリが表示されるまでどれくらい待つ必要がありますか?選択したアプリが表示されるまでユーザーが待つ必要がある時間。 */
        const val PICK_WAIT_MS = 3_000L
        const val PICK_POLL_MS = 300L
        /** 選択ボックスが閉じるまでの時間も選択されたとみなされます (しばらくしてから選択ボックスが閉じているかどうかを確認する必要があります)。 */
        const val PICK_SLACK_MS = 1_000L
        /** 選択したアプリがアップロードされた後、そのウィンドウを読み取り、ユーザーが個人的にそれを処理する必要があるかどうかを確認するには、少なくともこの時間待つ必要があります。 */
        const val PICK_READ_MS = 2_000L
        val NO_APP_OPEN = ActionResult.Failed("no_app_open")
        const val NO_FOCUS = "no_focus"
        const val PER_USER_RANGE = 100_000
        /** のユーザー ミカ: オリジナルのアプリはこちらです。 */
        val myUser: Int get() = AppCopyReader.myUser
        const val COPY_NOT_FOUND = "copy_not_found"
        const val COPY_NOT_OPENED = "copy_not_opened"
        const val HINT_PASTE_BLOCKED = "The text could not be entered: the field is not in the element list, so it can only be pasted, " +
            "and the clipboard could not be used safely right now. Call hand_to_user so the user can type it."
        val IN_USE_BY_OTHER = ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK)

        const val QUIET_MS = 500L

        /** Stay でゆっくりスクロールするスクロール可能なコンテンツは、少なくともこれだけの画面を占めます。 */
        const val IDLE_MIN_AREA = 0.5

        /** 少なくともこの数のピクセルを取得するには、そのままにしてゆっくりスクロールします (表示されるピクセル数がどんなに少なくても)。 */
        const val IDLE_MIN_PX = 8

        /** 滞在中に軽く回転する時間: 十分にゆっくり、ゆっくりとドラッグし、停止してから手を上げます。 */
        const val IDLE_SCROLL_MS = 900L
        const val CHECK_CAP_MS = 1_500L
        const val SECOND_LOOK_QUIET_MS = 300L
        const val SECOND_LOOK_CAP_MS = 800L
        const val SETTLE_CAP_MS = 2_000L
        const val READBACK_QUIET_MS = 200L
        const val READBACK_CAP_MS = 800L
        const val LAUNCH_WAIT_MS = 5_000L
        const val IN_USE_RETRY_MS = 3_000L
        const val IN_USE_POLL_MS = 500L
        const val POLL_MS = 100L
        /** 目覚まし時計を設定した後、システムの次の目覚まし時計が変わるまで待ちます。この時間とこの時間の間、一度だけ見ることができます。 */
        const val ALARM_WAIT_MS = 3_000L
        const val ALARM_POLL_MS = 150L
        const val SYSTEM_ACTION_REQUEST = 0x5A
        /** ローラーをドラッグする時間: リズムに合わせてゆっくりドラッグする時間以上で、手を離すと停止します。 */
        const val WHEEL_DRAG_MS = 1_000L
        /** ドラッグして移動できない場合のクイック スワイプの継続時間 (人間の手でスクロール ホイールをスワイプする場合など)。 */
        const val WHEEL_SWIPE_MS = 400L
        const val TYPE_ATTEMPTS = 2
        const val WEB_VIEW = "android.webkit.WebView"
        const val PASTE_WAIT_MS = 1_500L
        const val HINT_PASTE_UNAVAILABLE = "This field only receives real input (it needs a paste), but the clipboard could not be used safely, " +
            "so the text may only be displayed and the app may not have received it. Check that the app reacted (e.g. its save button became enabled) " +
            "before relying on it; if it did not, call hand_to_user so the user can type it."
        const val HINT_TAPPED_TO_FOCUS = "The element you gave is not a text field itself, so it was tapped first and the text went into the field that got focus after the tap (often a separate search page). Check which field holds your text before submitting."
        const val HINT_FOCUS_UNCONFIRMED = "フォーカス要求とタップでもフォーカスを確認できなかったため、直接書き込みました。フォーカス状態ではなく読戻しの検証結果を確認してください。"
        const val FOCUS_WAIT_MS = 2_000L
        const val SAME_FIELD_SIZE = 0.03
        val SEARCH_WORDS = listOf("検索", "探す", "キーワード", "搜索", "搜一搜", "查找", "search")
        const val HINT_READBACK_FROZEN = "The field keeps reporting the same text, so what was typed could not be read back; " +
            "but new text containing it appeared on the screen after typing (e.g. suggestions), so the app seems to have received it. Observe to check before submitting."
        const val HINT_SHARE_PICKER = "The system is asking which of the app's share screens to use (the screen lists them). " +
            "Tap the one the task needs (for a message, the one that sends to a contact or chat); do not share again."
        const val HINT_COLD_IGNORED = "coldIgnored: app has a task outside Mika's screen, so it was not restarted (that would wipe the user's state there). It was opened as-is; navigate back to its start screen yourself if needed."
        const val MAX_ANCESTORS = 8

        // ホバー、タッチ探索、ジェスチャ認識、およびアクセシビリティ フォーカスは、インターフェイスのコンテンツの変更ではありません
        const val IGNORED_EVENTS = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
    }
}

/** デスクトップ上で開くことができるアプリ (パッケージ名、表示名) (Mika 自体を除く)。パッケージ マネージャーのみを使用し、組み込みのアシスタントやアクセシビリティは使用しません。 */
fun launchableApps(pm: android.content.pm.PackageManager, ownPackage: String): List<Pair<String, String>> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ownPackage }
        .distinctBy { it.first }
        .sortedBy { it.second }
}
