package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.wangzan101.mika.core.shell.DevtoolsSocket
import com.wangzan101.mika.core.uistate.AppCopies
import com.wangzan101.mika.core.uistate.PressKeys
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import com.wangzan101.mika.shell.IMikaShell

/**
 * はシェル (uid 2000) として常駐し、エージェントの仮想画面を保持し、組み込みアシスタント プロセス ([HelperMain]) で実行されます。
 * プロセスが終了すると、仮想画面とその上のアプリが一緒に破棄されるため、これは 1 回限りのコマンドではなく永続的である必要があります。
 *
 * 非表示のインターフェイスは常にリフレクションによって呼び出されます。リフレクションが失敗すると、シェル コマンドに戻り、同じ効果が得られます。
 */
class ShellService(
    /** は、この uid (Mika) からの呼び出しのみを受け入れます。 */
    private val allowedUid: Int,
    /** サイレント レコードが配置されるディレクトリ。シェル ID が書き込みできる場所。 */
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    private var orientationWatch: DisplayOrientationWatch? = null
    private val requestedOrientations = ConcurrentHashMap<Int, Int>()
    /** ミュートされたパッケージ → ミュート前の PLAY_AUDIO モード (「デフォルト」は元々設定されていないことを意味します)、[stateDir] に該当すると、プロセスは強制終了され、次回の起動時に再開されます。 */
    private val muted = AudioMuteLedger(File(stateDir, "mika_audio_mute_$allowedUid"))
    /** ミカがいるユーザー: オリジナルのアプリはここにインストールされています。他のユーザーは別のコピー（利用可能なアバター、作品情報）を持っています。 */
    private val myUser = allowedUid / PER_USER_RANGE

    // は getSystemService を使用できません。この方法で取得された DisplayManager はシステム コンテキストにバインドされており、パッケージ名はシェル UID と一致しません。
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // クリップボードには、シェル パッケージ名を使用してアクセスする必要があります (システムは、シェルからクリップボードの「コピーされたフローティング レイヤーをポップしない」マークのみを認識します)。 ClipboardManager でシェル プロセスをバイパスする設定クエリを読み取ります。
    private val clipboard = ShellClipboard(context)
    private val clipLock = Any()
    /** 占有チェックと起動の間に別のタスクの起動を挿入することはできません。2 つのタスクが同じアプリを同時に開いた場合、成功できるのは 1 つだけです。 */
    private val launchLock = Any()
    /** setClipText 以前のユーザーのクリップボード (null は元々空であったことを意味し、読み取り不能なクリップボードはスワップアウトされません)。 [clipSaved] は false です。これは、復元するものが何もないことを意味します。 */
    private var savedClip: ClipData? = null
    private var clipSaved = false
    /** ミカが最後に入力したテキスト。これを使用して、復元する前にクリップボードがミカのものであるかどうかを判断します (ユーザーが途中で何かをコピーしても移動しません)。 */
    private var ourText: String? = null

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // シェルに ADD_TRUSTED_DISPLAY がない場合 (一部のシステムでは ADD_TRUSTED_DISPLAY が提供されていません): 信頼された画面でのみ使用できるいくつかの項目を削除して、再度ビルドします。
            // が返された後、この画面には独自のフォーカスがありません。セルフテスト ページでロゴを読み取ると、「独自のフォーカスがありません」と報告され、メイン画面のフォーカスが影響を受ける可能性があるというプロンプトが表示されます (Advice.FocusShared)。
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t: Throwable) {
                Log.e(TAG, "createDisplay failed", t)
                -1
            }
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("mika-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        ensureOrientationWatch()
        setImePolicy(id)
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    /** は、スリープしない仮想画面の自己完結型グループです。[DisplayAwake] を参照してください。 */
    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mika-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager とその画面に従ってユーザー操作を報告する方法。利用できない場合は null (一度だけ記憶され、画面は一般的に使用されますが、誰も更新しません)。 */
    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    /** この画面が配置されている表示グループ (DisplayInfo.displayGroupId、隠しフィールド)。読み取られない場合は null です。 */
    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    /** はサービス自体が作成した仮想画面上でのみ起動します。発信者が間違った画面番号 (ホーム画面など) を渡した場合でも、発信者はユーザーの邪魔をしません。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        // ロック内でのみ外し、モニター（バインダー）の電源を切り、ロックの外でスクリーンを装着してください。
        val (vd, watch) = synchronized(displays) {
            displays.remove(displayId) to (if (displays.isEmpty()) orientationWatch.also { orientationWatch = null } else null)
        }
        watch?.close()
        if (watch != null) requestedOrientations.clear()
        vd?.release()
    }

    /**
     * STEAL_TOP_FOCUS_DISABLED はメイン画面のフォーカスを維持しますが、一部のシステムはバックグラウンド アクティビティからの画面転送リクエストを受け付けなくなります。
     * にミカの画面がある場合は、一方向モニタリングをオンにし、自分の画面の方向のみを修正し、元のフォーカス分離を保持します。
     * モニターはロックの外側に構築されています (バインダーを介して登録する必要があります)。 2 つの画面が同時に構築されると、1 つだけが残り、余分な画面はオフになります。
     */
    private fun ensureOrientationWatch() {
        if (Build.VERSION.SDK_INT < 34 || synchronized(displays) { orientationWatch != null }) return
        val watch = runCatching {
            DisplayOrientationWatch(
                taskManager,
                active = { synchronized(displays) { displays.isNotEmpty() } },
                onRequestedOrientation = { taskId, orientation -> requestedOrientations[taskId] = orientation },
                onTaskRemoved = { taskId -> requestedOrientations.remove(taskId) },
                sync = ::syncDisplayOrientations,
            )
        }.onFailure { Log.w(TAG, "display orientation watcher unavailable", it) }.getOrNull() ?: return
        val spare = synchronized(displays) {
            if (orientationWatch == null && displays.isNotEmpty()) { orientationWatch = watch; null } else watch
        }
        spare?.close()
    }

    /**
     * 各画面の最上位タスクのアプリケーションウィンドウ比率に応じて画面の向きを修正します。ロック内の画面のスナップショットのみを撮影してください。
     * クエリ タスク、フィールドの読み取り、freezeDisplayRotation、および返された `wm` コマンドはすべてロックの外側にあり、画面の作成、画面の表示、および起動をブロックしません。
     */
    @SuppressLint("PrivateApi")
    private fun syncDisplayOrientations() {
        val ours = synchronized(displays) { displays.toMap() }
        if (ours.isEmpty()) return
        val top = allTasks().groupBy { TASK_INFO.getField("displayId").getInt(it) }
        for ((id, vd) in ours) runCatching {
            val task = top[id]?.firstOrNull() ?: return@runCatching
            val taskId = TASK_INFO.getField("taskId").getInt(task)
            val compat = runCatching { TASK_INFO.getField("appCompatTaskInfo").get(task) }.getOrNull() ?: task
            val cls = compat.javaClass
            // Android 14 ではアプリケーション ウィンドウの幅と高さのみが指定され、15 以降では明確なレターボックス ロゴが表示されます。
            if (runCatching { cls.getField("topActivityBoundsLetterboxed").getBoolean(compat) }.getOrDefault(true).not()) return@runCatching
            val width = runCatching { cls.getField("topActivityLetterboxWidth").getInt(compat) }.getOrDefault(0)
            val height = runCatching { cls.getField("topActivityLetterboxHeight").getInt(compat) }.getOrDefault(0)
            val display = vd.display
            val mode = display.mode
            val rotation = DisplayOrientation.rotation(
                mode.physicalWidth, mode.physicalHeight, display.rotation, width, height,
                requestedOrientations[taskId] ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ) ?: return@runCatching
            // この画面はスナップショット後に削除された可能性があります。
            if (!ownDisplay(id)) return@runCatching
            val ok = runCatching {
                val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
                val method = wm.javaClass.methods.first { it.name == "freezeDisplayRotation" }
                if (method.parameterTypes.size == 3) method.invoke(wm, id, rotation, "mika") else method.invoke(wm, id, rotation)
            }.isSuccess
            if (!ok) exec("wm", "user-rotation", "-d", "$id", "lock", "$rotation")
        }.onFailure { Log.w(TAG, "orientation sync failed on display $id", it) }
    }

    /**
     * まず、ユーザーがホーム画面でこのアプリを使用していないことを確認します。ホーム画面にタスクが表示されている場合、アプリは起動されません ([START_IN_USE_ON_MAIN] に戻ります)。
     * そうしないと、システムはユーザーが表示しているインターフェイスを削除または中断します。バックグラウンドタスクを仮想画面上にプルすることができます。 次に、
     * は起動エントリを解析します (パッケージ内の一部のアクティビティは出発点であり、直接起動するとすぐに飛び去り、仮想画面には空のタスクだけが残ります)。
     * は、ActivityOptions.setLaunchDisplayId を使用して、シェル パッケージ名を使用して IActivityTaskManager.startActivityAsUser を呼び出します。
     * は元の共有 (Mika が配置されているユーザー内の共有) のみを表示します。ユーザーがホーム画面で別のユーザーにインストールされた共有を使用する場合、この共有はブロックされません。
     */
    override fun startApp(displayId: Int, packageName: String): Int = start(displayId, packageName, myUser)

    /**
     * は、[userId] のユーザー共有で [packageName] を開始します ([startApp] を参照)。この共有 (パッケージ名 + ユーザー) のみが占有されます。
     * [userId] が Mega 自身のユーザーの場合は、元の方法 (USER_CURRENT、パッケージ マネージャーによって与えられたエントリ) で起動します。他のユーザーは、まずそのユーザーのスタートアップ エントリを確認します。
     * は再びそのユーザーで開始され、インターフェイスが使用不可になると `am start --user` に戻ります。
     */
    private fun start(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName, userId)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            // ホーム画面のステータスがはっきりと確認できないときは、ユーザーの邪魔になるリスクを避けるために、起動したくないと思います。
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        // 最後のタスクによって残されたタスクがまだメイン画面の背後にあります。すべてを元に戻し、新しいページを開いて最初から開始することなく、そのページを続行します。
        if (resumeKept(displayId, packageName, userId)) return START_OK
        val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        // ユーザーの別のコピーでは、最初にアイコンを開くためにデスクトップ パスが使用されます。一部のシステムでは、プログラムが別のコピーで起動されるときに「どれで開くか」というプロンプトが表示されますが、デスクトップ パスは表示されません。
        val component = intent.component
        if (userId != myUser && component != null && startAsLauncher(component, userId, options)) return START_OK
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
            //  requestCode, flags, profilerInfo, options, userId)
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, if (userId == myUser) USER_CURRENT else userId,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($packageName, user $userId) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            val user = if (userId == myUser) emptyArray() else arrayOf("--user", "$userId")
            exec("am", "start", *user, "--display", "$displayId", "-n", component) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /** ILauncherApps (LauncherApps の背後にあるシステム サービス);利用できない場合は null。 */
    private val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps service unavailable", it) }.getOrNull()
    }

    /**
     * デスクトップのように別のユーザー [userId] で起動ページを開きます [component]:ILauncherApps.startActivityAsUser(LauncherApps.startMainActivity
     * の背面にあるインターフェイス)、同じ [options] (Mika の画面で起動)。システム側では、発信者を押して、そのユーザーがアクセスできるかどうかを確認します。シェルにはユーザー間のアクセス許可があります。
     * パッケージ名もその uid と一致します。インターフェイスが存在しないか、拒否された (エラーがスローされた) 場合は、false が返され、呼び出し元は startActivityAsUser に変わります。
     */
    private fun startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean = try {
        val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
        val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        // Android 11 以降: (caller、callingPackage、callingFeatureId、component、sourceBounds、opts、user);以前はcallingFeatureIdがありませんでした
        val m = la.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size in 6..7 && ComponentName::class.java in it.parameterTypes }
        val args: Array<Any?> = if (m.parameterTypes.size == 7) arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
        else arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
        m.invoke(la, *args)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launcher-style start of ${component.flattenToShortString()} in user $userId failed", t)
        false
    }

    /**
     * が [packageName] を開くために使用するインテント: ミカ自身のユーザーは、パッケージ マネージャーによって与えられた入り口です。他のユーザーがユーザーの起動ページを確認する
     * (隠しインターフェイス queryIntentActivitiesAsUser、不可能な場合は、クエリを `cmd package query-activities --user` に変更します)。見つからない場合は null です。
     */
    @SuppressLint("PrivateApi")
    private fun launchIntent(packageName: String, userId: Int): Intent? {
        if (userId == myUser) return context.packageManager.getLaunchIntentForPackage(packageName)
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val found = try {
            val pm = context.packageManager
            val m = pm.javaClass.getMethod("queryIntentActivitiesAsUser", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            (m.invoke(pm, query, 0, userId) as List<*>).filterIsInstance<ResolveInfo>().map { it.activityInfo.packageName to it.activityInfo.name }
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivitiesAsUser failed, asking the package service", t)
            val args = ActivityQuery.args(Intent.ACTION_MAIN, category = Intent.CATEGORY_LAUNCHER, packageName = packageName, user = "$userId")
            sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
        }
        val (pkg, cls) = found?.firstOrNull { it.first == packageName } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
    }

    /** これらのパッケージには、Mika の別の画面 ([displayId] ではありません) のタスクが含まれています。これは、他のタスクで使用されているアプリです。 [userId] が空でない場合は、そのユーザーのみを確認します。 */
    private fun onOtherOwnDisplay(packages: Collection<String>, displayId: Int, userId: Int? = null): Boolean {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) return false
        val displayField = TASK_INFO.getField("displayId")
        return allTasks().any { t -> displayField.getInt(t) in others && packages.any { isCopy(t, it, userId) } }
    }

    /** タスクは、[userId] のユーザー間での [packageName] の共有です (ルールについては、[sameCopy] を参照)。 */
    private fun isCopy(task: Any, packageName: String, userId: Int?): Boolean =
        sameCopy(packagesOf(task), runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull(), packageName, userId)

    /**
     * このアプリのすべてのタスクがミカの画面に表示されている場合にのみ、プロセスが終了し、再開されます（最初のページに戻ります）。
     * 他の画面にタスク(ユーザーステータス)があると終了しません。正常に起動し、[START_OK_COLD_IGNORED] に戻ります。
     * ユーザーがホーム画面で使用している場合は起動しません。
     */
    override fun restartApp(displayId: Int, packageName: String): Int = restart(displayId, packageName, myUser)

    /** [restartApp] は、ユーザー [userId] のコピーに影響します。このコピーのタスクのみを確認し、このユーザーのプロセスのみを終了します。他のユーザーにインストールされている他のコピーは移動しません。 */
    private fun restart(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        // 自分で構築したもの以外の画面（メイン画面を含む）は移動することはなく、ましてやプロセスを先に終了することはできません。
        if (!ownDisplay(displayId)) return START_ERROR
        val decision = try {
            // 他のタスクが別の画面でミカを使用しています: プロセスを終了しないでください
            if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
            // 最初から始めるには: 最後のタスクでホーム画面の後ろに残されたコピーは、ミカ自身によって残されました。最初にオフにすると、ユーザーのステータスとしてカウントされなくなります。
            dropKept(packageName, userId)
            val displayField = TASK_INFO.getField("displayId")
            val taskDisplays = allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }
            ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName, userId))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect tasks", t)
            return START_ERROR
        }
        if (launchIntent(packageName, userId) == null) return START_NOT_FOUND
        when (decision) {
            ColdStart.IN_USE -> START_IN_USE_ON_MAIN
            ColdStart.WARM -> start(displayId, packageName, userId).let { if (it == START_OK) START_OK_COLD_IGNORED else it }
            ColdStart.RESTART -> {
                val user = if (userId == myUser) "current" else "$userId"
                if (exec("am", "force-stop", "--user", user, packageName) != 0) return START_ERROR
                start(displayId, packageName, userId)
            }
        }
    }

    /** は別のユーザーで開かれます。[appCopyUsers] によって使用可能と判断されたコピーのみが認識されます (プライベート スペースや他のユーザーは触れられません)。ルールについては、[AppCopies] を参照してください。 */
    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int {
        if (userId == myUser) return if (cold) restartApp(displayId, packageName) else startApp(displayId, packageName)
        if (!ownDisplay(displayId)) return START_ERROR
        val usable = try {
            users()?.let { AppCopies.usable(myUser, it) }
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read users", t)
            null
        } ?: return START_ERROR
        if (userId !in usable) {
            Log.w(TAG, "refused to start $packageName in user $userId")
            return START_USER_NOT_ALLOWED
        }
        return if (cold) restart(displayId, packageName, userId) else start(displayId, packageName, userId)
    }

    override fun appCopyUsers(): Bundle? = try {
        users()?.let { users ->
            Bundle().apply {
                putIntArray(KEY_COPY_USERS, AppCopies.usable(myUser, users).toIntArray())
                putString(KEY_COPY_SUMMARY, AppCopies.summary(myUser, users))
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "appCopyUsers failed", t)
        null
    }

    /**
     * この携帯電話のユーザーは、元の Android UserInfo / UserProperties (インターフェイスの反映を非表示) に従って読み取ることができます: タグ、タイプ、データ グループ、実行中かどうか、静かモード、
     * メディアを現在のユーザーと共有し、他のアプリから非表示にするかどうか。読み取れないユーザーリストは null です。読み取れない 1 つの項目は空白のままになります (不満足として計算されます。[AppCopies] を参照)。
     */
    @SuppressLint("PrivateApi")
    private fun users(): List<AppCopies.User>? {
        val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
            ?: UserManager::class.java.getMethod("get", Context::class.java).invoke(null, context) as UserManager
        val list = (runCatching { um.javaClass.getMethod("getAliveUsers").invoke(um) }.getOrNull()
            ?: runCatching { um.javaClass.getMethod("getUsers").invoke(um) }.getOrNull()) as? List<*> ?: return null
        val info = Class.forName("android.content.pm.UserInfo")
        // システム自体によってフラグに付けられた名前 (システム定義のフラグを含む、dumpsys ユーザーによって使用される同じ UserInfo.flagsToString)。リフレクションが失敗したときに dumpsys ユーザーを読み取る
        val flagsToString = runCatching { info.getMethod("flagsToString", Int::class.javaPrimitiveType) }.getOrNull()
        val dumped by lazy { sh("dumpsys", "user").takeIf { it.first == 0 }?.second?.let(AppCopies::parseDumpsys).orEmpty() }
        return list.filterNotNull().map { u ->
            val id = info.getField("id").getInt(u)
            val flags = info.getField("flags").getInt(u)
            val handle = UserHandle.getUserHandleForUid(id * PER_USER_RANGE)
            val props = runCatching { um.javaClass.getMethod("getUserProperties", UserHandle::class.java).invoke(um, handle) }.getOrNull()
            fun prop(name: String): Any? = props?.let { p -> runCatching { p.javaClass.getMethod(name).invoke(p) }.getOrNull() }
            val names = flagsToString?.let { m -> runCatching { (m.invoke(null, flags) as? String)?.let(::flagNames) }.getOrNull() }
                ?: dumped[id]?.flagNames
            val group = runCatching { info.getField("profileGroupId").getInt(u) }.getOrDefault(AppCopies.NO_PROFILE_GROUP)
            val running = runCatching { um.isUserRunning(handle) }.getOrDefault(false)
            AppCopies.User(
                id = id,
                flags = flags,
                type = runCatching { info.getField("userType").get(u) as? String }.getOrNull(),
                profileGroupId = group,
                running = running,
                unlocked = running && runCatching { um.isUserUnlocked(handle) }.getOrDefault(false),
                quiet = runCatching { um.isQuietModeEnabled(handle) }.getOrDefault(false),
                mediaShared = prop("isMediaSharedWithParent") as? Boolean,
                hidden = (prop("getProfileApiVisibility") as? Int)?.let { it == PROFILE_API_VISIBILITY_HIDDEN },
                flagNames = names,
                // dumpsys のparentId はデータ グループです (自分自身ではない場合、または「グループに属していない」場合にのみ書き込んでください)。読んで持っていきましょう
                parentId = group.takeIf { it != id && it != AppCopies.NO_PROFILE_GROUP } ?: if (flagsToString == null) dumped[id]?.parentId else null,
            )
        }
    }

    /** flagsToString の結果 (「CLONED_PROFILE|FULL|INITIALIZED」など、一部のバージョンには中括弧が付いています) は名前に分割されます。 */
    private fun flagNames(s: String): List<String> = s.trim('{', '}', ' ').split('|', ' ', ',').map(String::trim).filter(String::isNotEmpty)

    /**
     * リンクまたは設定ページを開きます。シェル側で [ShellLinks] を押してリンクを再度確認します。解析できない場合は、[START_UNRESOLVED] が返されます。 ホーム画面にタスクがある場合、
     * で開くことができるアプリは起動しません。NEW_TASK とのリンクがそのタスクに分類され、ユーザーの目の前に表示される可能性があります。
     * アプリが 1 つしかない場合は、ページを開いたときにそのページを直接指定できるため、システムが別のアプリを選択する必要がなくなります。
     */
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        ShellLinks.refuse(action, uri, packageName)?.let { return it }
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        packageName?.let { intent.setPackage(it) }
        // Web スタイルのリンクは、リンクから開くことが宣言されているページにのみ与えられ、アプリの内部ページには影響しません。
        if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = activitiesFor(intent, null) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(link) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
                ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                    (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
            exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * このパッケージにのみ、EXTRA_TEXT を使用して [packageName]:ACTION_SEND text/plain にテキストを共有します。テキスト共有ページを受信せず、[START_UNRESOLVED] を返します。
     * は、[startApp] と同様に、ユーザーがホーム画面で使用しているときに起動しません。共有ページが 1 つだけの場合は、直接指定できます。複数のページがある場合、システムはどのページを使用するかを尋ねます。
     */
    override fun startShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val intent = ShareIntent.of(packageName, text)
        val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, ShareIntent.TYPE, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * システムアクション ([SystemIntents] を参照): それらのタイプのインテントのみを解放します。ページがない場合は、[START_UNRESOLVED] を返します。 [startView]と同じ、
     * に引き継がれたアプリは、ホーム画面にタスクがあると起動しません。まずシェルとして開始します。失敗した場合 (引き継がれるページには、目覚まし時計の設定など、シェルにはない権限が必要です)、Mika が発行されます。
     * [asMika]：Mikaとして起動し（Mikaをクリックして権限を確認）、発行元側（シェル）に合わせて画面とバックグラウンド起動を確認してもやはりこの画面に落ちます。
     */
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: android.app.PendingIntent?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        // ミカ自身の互換性セルフテスト ページ: この画面をシェルとして直接開きます
        if (intent.component?.className == SystemIntents.OWN_PAGE_CLASS) return startOwnPage(displayId, intent)
        if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val asShell = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, intent.type, null, null,
                0, 0, null, options.toBundle(), USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(${intent.action}) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
            false
        }
        if (asShell) return START_OK
        if (asMika == null) return START_ERROR
        // Android 14 以降、PendingIntent の送信者は、バックグラウンドからのページの起動が許可されていることを明示的に示す必要があります。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return try {
            asMika.send(context, 0, null, null, null, null, options.toBundle())
            START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "sending ${intent.action} as Mika failed", t)
            START_ERROR
        }
    }

    /**
     * は、ミカの互換性セルフテスト ページ ([SystemIntents.OWN_PAGE_CLASS]) をシェルとしてこの画面に開きます。これは、[startApp] と同じパスです。
     * (setLaunchDisplayId を使用した startActivityAsUser、不可能な場合は `am start --display` を返します)。このページは INTERACT_ACROSS_USERS_FULL を持つユーザーのみを対象としています
     * 呼び出し元は開いています (シェルにはありますが、通常のアプリにはありません)。シェルの起動は、バックグラウンド起動ページによって制限されません。パッケージ名はミカ自身のものでなければならず、他のコンポーネントは開かれません。
     */
    private fun startOwnPage(displayId: Int, intent: Intent): Int {
        val component = intent.component ?: return START_INVALID_LINK
        val mine = runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toList() }.getOrNull().orEmpty()
        // このサービスを調整できるのはミカだけです。パッケージ名を再度確認してください（Mikaのパッケージ名が見つからない場合はクラス名のみが認識されます）
        if (component.className != SystemIntents.OWN_PAGE_CLASS || (mine.isNotEmpty() && component.packageName !in mine)) return START_INVALID_LINK
        // 毎回最初からページを開きます。古いページとそのタスクはクリアされ、新しいページはミカ画面の上部に表示される必要があります。
        val page = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, page, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(own page) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec("am", "start", "--display", "$displayId", "--activity-clear-task", "-n", component.flattenToShortString()) == 0
        }
        return if (ok) START_OK else START_ERROR
    }

    /**
     * は [intent] (パッケージ名、完全なクラス名) のページにアクセスできます。こちら側でシステム インターフェイスが見つからない場合は、代わりにシステム サービスに問い合わせてください ([ActivityQuery] を参照)。どちらも不可能な場合は null になります。
     * [type] は、インテントのデータ型です (コマンドで別途指定します)。
     */
    private fun activitiesFor(intent: Intent, type: String?): List<Pair<String, String>>? = try {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName to it.activityInfo.name }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentActivities failed, asking the package service", t)
        val args = ActivityQuery.args(intent.action!!, type, intent.dataString, intent.categories?.firstOrNull(), intent.`package`)
        sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
            .also { if (it == null) Log.e(TAG, "cannot resolve ${intent.action}") }
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    /** このパッケージには、ホーム画面に表示されるタスクがあるか、ホーム画面の最上位のタスクがあります。 [userId] が空でない場合は、そのユーザーのみを確認します ([sameCopy] を参照)。 */
    private fun inUseOnMainScreen(packageName: String, userId: Int? = null): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            isCopy(task, packageName, userId) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    /**
     * IActivityTaskManager.getTasks: 最近の使用順に並べ替えられ、シェルは REAL_GET_TASKS を保持し、すべてのアプリ タスクを表示できます。
     * API 33 以降には、displayId によるオーバーロードがあります。最初にそれを使用してください。以前のバージョンでは、すべてを取得し、TaskInfo.displayId でフィルターします。
     */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    /** すべての画面のタスク: 画面番号 INVALID_DISPLAY で送信をリロードします。つまり、画面フィルタリングはありません。 */
    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun task(taskId: Int): Any? {
        val idField = TASK_INFO.getField("taskId")
        return allTasks().firstOrNull { idField.getInt(it) == taskId }
    }

    override fun displayOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    override fun displayOfPackage(packageName: String): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackage failed", t)
        -1
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackageInUser failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        task(taskId)?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        null
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId)。これは、`am display move-stack` の背後にある呼び出しです。
     * 実機 (Android 14) 手動検証: タスクは仮想画面とメイン画面の間で両方向に移動できます。ミカによって開始されたタスクはすべて root タスクです。
     */
    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    /**
     * タスク終了時にメイン画面の後ろに残るタスク: タスク番号 → キー ([AppsAfterTask.keyOf])。次回ミカが同じアプリ ([resumeKept]) を開いたときに、元の位置に移動します。
     * はメモリ内にのみ存在します。再起動後、アシスタントはそれを認識せず、開くと引き続き起動入り口に移動します (システムはおそらくこのバックグラウンド タスクをプルオーバーします)。
     */
    private val kept = HashMap<Int, String>()

    /**
     * タスクが完了しました: [AppsAfterTask] を押して、この画面の各タスクを残すかどうかを決定します。残りのタスクをメイン画面の奥に移動して、この画面を放します（
     * FLAG_DESTROY_CONTENT_ON_REMOVAL、移動しない場合は画面とともにオフにします)。シーケンス: 留まるか去るかを決定する → 残された者を移動する → 騒いでいる者をもう一度見る → 見えなくなるまで待つ ([SettleBehind])
     * → 表示されたまま画面を後ろに移動 → 放します。この後、ミュートはミカによって復元され、復元中に残りのアプリが突然音を出すことはありません。
     *
     * を最後に移動する方法 (すべてオリジナルの Android インターフェイス): まず、WindowContainerTransaction.setHidden を使用してタスクを非表示にします。
     * 次に moveRootTaskToDisplay でメイン画面へ移す（システムは常に最上部に置くため、隠したままでは表示されず、フォーカスも取らず、ユーザーが使っているページも一時停止しない）、
     * 続けて同じトランザクションで最後尾へ reorder し、非表示を解除する。非表示や移動自体が失敗した場合は、従来どおり画面と一緒に閉じる。
     *
     * 移動と後片付けは [launchLock] 内で行う; 途中で遷移を待つ間（最大 [SettleBehind.TIMEOUT_MS]）はロックを保持せず、他のタスクは通常どおり App を開ける。
     * この間に他のタスクに画面上で取得されたものはもう関与しない; 残されたタスクも後片付けの時に初めて [kept] に記録され、待っている間は [resumeKept] がそれを取得しない。
     */
    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        val sorting = synchronized(launchLock) {
            if (!ownDisplay(displayId)) return null
            sortAndMove(displayId, keep, close)
        }
        val settled = settleBehind(sorting.moved.mapTo(LinkedHashSet()) { it.task.id })
        return synchronized(launchLock) { closeOut(displayId, sorting, settled) }
    }

    /** [finishDisplay] の最初の段落: 残りの 1 段落 ([moved]) はホーム画面に移動されました。 [hidden] は、そこに移動された後、非表示になりました。 */
    private class Sorting(
        val moved: MutableList<AppsAfterTask.Fate>,
        val closed: MutableList<String>,
        val notes: MutableSet<String>,
        val hidden: Set<Int>,
    )

    private fun sortAndMove(displayId: Int, keep: Array<String>?, close: Array<String>?): Sorting {
        val closed = ArrayList<String>()
        val notes = LinkedHashSet<String>()
        // はメイン画面の後ろに移動されました。プロセスで間違いを犯した場合でも、それを覚えていれば、次回から取り戻すことができます。
        val moved = ArrayList<AppsAfterTask.Fate>()
        val hidden = HashSet<Int>()
        try {
            val playing = playingUids()
            if (playing == null) notes += AppsAfterTask.NOTE_PLAYBACK_UNKNOWN
            val rows = tasksOn(displayId)
            val idField = TASK_INFO.getField("taskId")
            val byId = rows.associateBy { idField.getInt(it) }
            val fates = AppsAfterTask.decide(rows.map { afterTaskRow(it, playing) },
                AppsAfterTask.normalize(keep, myUser), AppsAfterTask.normalize(close, myUser))
            var supported = true
            for (f in fates) {
                if (!f.keep) { closed += AppsAfterTask.closedEntry(f); continue }
                if (!supported) { closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported)); continue }
                when (keepBehind(byId.getValue(f.task.id))) {
                    Behind.OK -> moved += f
                    Behind.HIDDEN -> { moved += f; hidden += f.task.id }
                    Behind.UNSUPPORTED -> {
                        supported = false
                        notes += AppsAfterTask.NOTE_MOVE_UNSUPPORTED
                        closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported))
                    }
                }
            }
            // を移動すると音が鳴ります (キープマークが付いていません): この画面を元に戻し、画面と一緒にオフにします。元に戻せない場合は、直接オフにしてください
            val playingNow = if (moved.isEmpty()) null else playingUids()
            val nowLoud = moved.map { f -> AppsAfterTask.recheck(f, task(f.task.id)?.let { playingIn(it, playingNow) }) }.filter { !it.keep }
            for (f in nowLoud) {
                sendBackToClose(f.task.id, displayId)
                moved.removeAll { it.task.id == f.task.id }
                closed += AppsAfterTask.closedEntry(f)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot inspect tasks, closing the rest with the display", t)
            notes += "error ${t.javaClass.simpleName}"
        }
        return Sorting(moved, closed, notes, hidden)
    }

    /**
     * [finishDisplay] の最後の段落はロックされています。再度設定するには、[SettleBehind.closeOut] を押してください。タイムアウト後も画面が表示される場合は、画面を元に戻してオフにします。
     * (移動して再び音を立てたパスと同じ、ユーザーの目の前に残されていない)、残されていない ([AppsAfterTask.Why.StillInFront]) として記録されます。ホーム画面に表示されなくなったものは記録されません。
     * 残っている内容をメモし、画面を放します。待てない場合 (タスク リストを読み取れず、[settled] が null の場合) はそのままにしておくと、結果はチェックされなかったことが示されます。
     */
    private fun closeOut(displayId: Int, sorting: Sorting, settled: SettleBehind.Plan.Done?): Bundle {
        val (moved, closed, notes) = Triple(sorting.moved, sorting.closed, sorting.notes)
        if (settled == null) notes += AppsAfterTask.NOTE_SETTLE_UNKNOWN
        else try {
            // は待機中にロックを占有しません。ロックに戻ってもう一度見てください。それを奪った、または解決した他のタスクは移動しません。
            val ids = moved.mapTo(LinkedHashSet()) { it.task.id }
            val final = SettleBehind.closeOut(ids, settled.fallback, seenOnMain(ids))
            for (f in moved.filter { it.task.id in final.fallback }) {
                Log.w(TAG, "task ${f.task.id} is still in front on the main screen, closing it with display $displayId")
                sendBackToClose(f.task.id, displayId)
                closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.StillInFront))
            }
            if (final.fallback.isNotEmpty()) notes += AppsAfterTask.NOTE_STILL_IN_FRONT
            if (final.gone.isNotEmpty()) Log.i(TAG, "tasks ${final.gone} left the main screen while settling; not keeping them")
            moved.removeAll { it.task.id !in final.kept }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot recheck the main screen", t)
            notes += "error ${t.javaClass.simpleName}"
        }
        // 再表示に失敗しました。ホーム画面には残りますが、最近のタスクには表示されません。次回同じタスクを開くと、resumeKept は再表示を補うためにタスクを元に戻します。
        val hidden = moved.count { it.task.id in sorting.hidden }
        if (hidden > 0) notes += "$hidden ${AppsAfterTask.NOTE_LEFT_HIDDEN}"
        synchronized(kept) { moved.forEach { kept[it.task.id] = it.key } }
        releaseDisplay(displayId)
        return Bundle().apply {
            putStringArray(AppsAfterTask.KEY_KEPT, moved.map { it.key }.toTypedArray())
            putStringArray(AppsAfterTask.KEY_CLOSED, closed.toTypedArray())
            putString(AppsAfterTask.KEY_NOTE, notes.joinToString("; ").ifEmpty { null })
        }
    }

    /** 残っていないタスクをミカの画面 [displayId] に移動し、画面とともにオフにします。元に戻せない場合は、オフにしてください。 */
    private fun sendBackToClose(taskId: Int, displayId: Int) {
        if (!moveRoot(taskId, displayId)) removeTask(taskId)
    }

    /** OK: メイン画面の後ろに移動されました。 HIDDEN: 移動されましたが、再表示が完了しておらず、まだ非表示になっています。 UNSUPPORTED: このシステムは移動できません。 */
    private enum class Behind { OK, HIDDEN, UNSUPPORTED }

    /** タスクの行は [AppsAfterTask.decide]: キーを与えます。これは通常のアプリのページですか、ミカ自身のものですか、そして現時点では沈黙しています。 */
    private fun afterTaskRow(t: Any, playing: Map<Int, Set<String>>?): AppsAfterTask.Task {
        val user = runCatching { TASK_INFO.getField("userId").getInt(t) }.getOrDefault(myUser)
        val pkgs = packagesOf(t).distinct()
        val type = runCatching { TASK_INFO.getField("topActivityType").getInt(t) }.getOrNull()
        return AppsAfterTask.Task(
            id = TASK_INFO.getField("taskId").getInt(t),
            keys = pkgs.map { AppsAfterTask.keyOf(it, user, myUser) },
            standard = type == null || type == ACTIVITY_TYPE_UNDEFINED || type == ACTIVITY_TYPE_STANDARD,
            own = pkgs.any { it in ownPackages },
            playing = playingIn(t, playing),
            muted = synchronized(muted) { pkgs.any { muted.contains(AppsAfterTask.keyOf(it, user, myUser)) } },
        )
    }

    private val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    /** このタスクのアプリ (同じユーザー内) は現在音を鳴らしています。読み取れない場合は null になります。 */
    private fun playingIn(task: Any, playing: Map<Int, Set<String>>?): Boolean? {
        playing ?: return null
        val user = runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull()
        val pkgs = packagesOf(task)
        return playing.any { (uid, owners) -> (user == null || uid / PER_USER_RANGE == user) && pkgs.any { it in owners } }
    }

    /**
     * 現在再生中のuid → このuidのパッケージ。最初にオーディオ サービスに問い合わせます (AudioManager.getActivePlaybackConfigurations, uid は、
     * に MODIFY_AUDIO_ROUTING が装備されている場合に与えられます)。取得したuidが非表示の場合は、`dumpsys audio`として読み取られます。プレーヤーのステータスを開始済みとしてのみ認識します
     * (ミカによってミュートされたものは引き続き再生されます。ミュートすると音が出なくなり、元に戻すと音が鳴ります)。どれも null として読み取ることはできません。
     */
    private fun playingUids(): Map<Int, Set<String>>? {
        val uids = runCatching {
            val configs = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            val uidOf = AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
            val stateOf = AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
            val rows = configs.map { (uidOf.invoke(it) as Int) to (stateOf.invoke(it) as Int) }
            if (rows.any { it.first < 0 }) null
            else rows.filter { it.second == AppsAfterTask.PLAYER_STATE_STARTED }.map { it.first }.toSet()
        }.onFailure { Log.w(TAG, "playback configurations unavailable", it) }.getOrNull()
            ?: sh("dumpsys", "audio").takeIf { it.first == 0 }?.second?.let(AppsAfterTask::playingUids)
            ?: return null
        return uids.associateWith { uid ->
            runCatching { context.packageManager.getPackagesForUid(uid)?.toSet() }.getOrNull().orEmpty()
        }
    }

    /**
     * ミカの画面上のタスク [row] をメイン画面の背面に移動します。移動が成功したら、タスクを並べ替え/再表示します。タスクが完了していない場合は削除しないでください。まだ表示されているタスクは、[settleBehind] やその他の遷移に引き渡すことができます。
     * は最後尾のキューに入れられます。非表示になったものは [Behind.HIDDEN] に戻り、結果は、次回 [resumeKept] が戻されたときに非表示が解除されることを示します。
     */
    private fun keepBehind(row: Any): Behind {
        val id = TASK_INFO.getField("taskId").getInt(row)
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: return Behind.UNSUPPORTED
        if (!transact { wct -> wctCall(wct, "setHidden", token, true) }) return Behind.UNSUPPORTED
        if (!moveRoot(id, Display.DEFAULT_DISPLAY)) {
            transact { wct -> wctCall(wct, "setHidden", token, false) }
            return Behind.UNSUPPORTED
        }
        // は再注文を非表示にしてから再表示します。そうしないと、システムは再注文を移動しません。 2 つのステップは同じトランザクション内に配置されます。
        val placed = transact { wct ->
            wctCall(wct, "reorder", token, false)
            wctCall(wct, "setHidden", token, false)
        }
        if (placed) return Behind.OK
        // 非表示状態のままにしないでください。再表示した後に一番上にある場合は、seltleBehind にしてから後ろに移動します。
        if (transact { wct -> wctCall(wct, "setHidden", token, false) }) return Behind.OK
        Log.w(TAG, "task $id is still hidden on the main screen; resumeKept will unhide it")
        return Behind.HIDDEN
    }

    /** [ids] のタスクは現時点ではまだホーム画面上にあり、表示されません。 isVisible が読み取れない場合は、「ホーム画面の一番上のタスクですか」をクリックして確認してください (そこにあるタスクは、最近使用した順に並べられていますが、単なる後退です)。 */
    private fun seenOnMain(ids: Set<Int>): List<SettleBehind.Seen> {
        if (ids.isEmpty()) return emptyList()
        val idField = TASK_INFO.getField("taskId")
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().mapNotNull { (index, row) ->
            val id = idField.getInt(row)
            if (id !in ids) null else SettleBehind.Seen(id, onMain = true, visible = visibleField?.getBoolean(row) ?: (index == 0))
        }
    }

    /**
     * およびその他のタスクはホーム画面に移動し、[moved] はユーザーには見えない場所に退避します。ルールについては、[SettleBehind] を参照してください。 [launchLock] にはありません: 最大でも [SettleBehind.TIMEOUT_MS] まで待つ必要があります。
     * は、タスク リストを読み取れない場合に null を返します (呼び出し元がタスク リストを放置し、結果はチェックされなかったことを示します)。
     */
    private fun settleBehind(moved: Set<Int>): SettleBehind.Plan.Done? = try {
        val start = SystemClock.uptimeMillis()
        var lastReorder: Long? = null
        var watching = moved
        val gone = LinkedHashSet<Int>()
        var done: SettleBehind.Plan.Done? = null
        while (done == null) {
            val now = SystemClock.uptimeMillis()
            when (val plan = SettleBehind.plan(watching, seenOnMain(watching), now - start, lastReorder?.let { now - it })) {
                is SettleBehind.Plan.Done -> done = plan.copy(gone = plan.gone + gone)
                is SettleBehind.Plan.Wait -> {
                    gone += plan.gone
                    watching = watching - plan.gone
                    if (plan.reorder.isNotEmpty()) {
                        lastReorder = now
                        sendToBack(plan.reorder)
                    }
                    Thread.sleep(SettleBehind.POLL_MS)
                }
            }
        }
        if (done.fallback.isNotEmpty()) Log.w(TAG, "moved tasks ${done.fallback} are still visible on the main screen after ${SettleBehind.TIMEOUT_MS}ms")
        done
    } catch (t: Throwable) {
        Log.e(TAG, "cannot watch moved tasks settle on the main screen", t)
        null
    }

    /** これらのタスクをホーム画面に最後まで配置します（遷移が完了していない場合は失敗とみなされず、次のラウンドで再確認されます）。 */
    private fun sendToBack(ids: Set<Int>) {
        val idField = TASK_INFO.getField("taskId")
        for (row in tasksOn(Display.DEFAULT_DISPLAY)) {
            val id = idField.getInt(row)
            if (id !in ids) continue
            Log.w(TAG, "moved task $id is still visible on the main screen, sending it back")
            val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: continue
            transact { wct -> wctCall(wct, "reorder", token, false) }
        }
    }

    /** IActivityTaskManager.moveRootTaskToDisplay;インターフェイスが使用できない場合は、`am display move-stack` (同じ呼び出し) を返します。 */
    private fun moveRoot(taskId: Int, displayId: Int): Boolean = moveTask(taskId, displayId)

    /** IActivityTaskManager.removeTask (タスクをオフにします。これは、最近のタスクに取り消し線を引くのと同じです);不可能な場合は、`am stack remove` に戻ります。 */
    private fun removeTask(taskId: Int): Boolean = try {
        taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "removeTask unavailable, falling back to am", t)
        exec("am", "stack", "remove", "$taskId") == 0
    }

    /** WindowContainerTransaction を作成し、IWindowOrganizerController.applyTransaction 経由でシステムに渡します。失敗したステップは false を返します。 */
    @SuppressLint("PrivateApi")
    private fun transact(build: (Any) -> Unit): Boolean = try {
        val wct = WCT.getConstructor().newInstance()
        build(wct)
        val controller = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
        controller.javaClass.methods.first { it.name == "applyTransaction" && it.parameterTypes.size == 1 }.invoke(controller, wct)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "window container transaction failed", t)
        false
    }

    @SuppressLint("PrivateApi")
    private fun wctCall(wct: Any, method: String, token: Any, flag: Boolean) {
        WCT.getMethod(method, Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType).invoke(wct, token, flag)
    }

    /** ホーム画面上 (ユーザーの目の前ではない) にあり、最後のタスクから離れたこのアプリのタスク。 */
    private fun keptOnMain(packageName: String, userId: Int): List<Any> {
        val ids = synchronized(kept) { kept.keys.toSet() }
        if (ids.isEmpty()) return emptyList()
        val idField = TASK_INFO.getField("taskId")
        val displayField = TASK_INFO.getField("displayId")
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        val all = allTasks()
        // なくなったタスクをもう覚えていません
        synchronized(kept) { kept.keys.retainAll(all.map { idField.getInt(it) }.toSet()) }
        return all.filter { t ->
            idField.getInt(t) in ids && displayField.getInt(t) == Display.DEFAULT_DISPLAY && isCopy(t, packageName, userId) &&
                visibleField?.getBoolean(t) != true
        }
    }

    /** メイン画面の後ろに残ったタスクをミカの画面に戻します。利用できない場合、または移動できない場合は、 false を返します (呼び出し元は通常どおり開始します)。 */
    private fun resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
        val row = runCatching { keptOnMain(packageName, userId).firstOrNull() }.getOrNull() ?: return false
        val id = TASK_INFO.getField("taskId").getInt(row)
        if (!moveRoot(id, displayId)) return false
        // 最後に再表示する時間がなかったので、それを補うために元に戻しました。
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull()
        if (token != null && !transact { wct -> wctCall(wct, "setHidden", token, false) }) {
            Log.w(TAG, "task $id is still hidden after moving it back to display $displayId")
        }
        synchronized(kept) { kept.remove(id) }
        return true
    }

    /** コールド スタートの前に、最後のタスクでホーム画面の後ろに残されたコピー (ユーザーのステータスではなく、Mika 自身が残したもの) を閉じます。 */
    private fun dropKept(packageName: String, userId: Int) {
        keptOnMain(packageName, userId).forEach { t ->
            val id = TASK_INFO.getField("taskId").getInt(t)
            if (removeTask(id)) synchronized(kept) { kept.remove(id) }
        }
    }

    /** メイン画面タスクの記録: エージェントはアクションの前に凝視を開始します。[MainScreenWatch] を参照してください。初めて使用するときにのみビルド (スレッド、タスク スタック監視) されます。 */
    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    override fun userOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("userId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "userOfTask failed", t)
        -1
    }

    /** 画面上のすべてのタスクは [MainScreenLog.Task] に置き換えられます。ページ タイプを読み取れない場合は、通常のアプリ ページとして扱われます。 */
    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
            )
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // には press_key と同じホワイトリストがあります。発信者がエラーを犯した場合でも、ボリューム、電源、ホーム、その他のマシンのボタンはここから発行されません。
        if (!PressKeys.isAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    /** Ctrl+A / Ctrl+V: 修飾キーはキー イベントのメタステートに書き込まれ (アプリはそれを押すことでキーの組み合わせを認識します)、これら 2 つだけが解放され、ミカ自身の画面にのみ送信されます (ペーストはユーザーの画面に落ちることはできません)。 */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
            Log.w(TAG, "refused ctrl key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { false }
    }

    override fun listUsers(): String? = sh("pm", "list", "users").takeIf { it.first == 0 }?.second

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * 1 本の指のストローク: 押す、押す [times] 点ごとに移動し、最後の点で持ち上げます。各イベントはその瞬間に挿入されます (イベント時間はその瞬間です)。
     * アプリで表示される速度は、Mika が調整した速度です。ミカ自身の画面にのみ送信されます。インジェクションが成功しなかった場合は false が返されます (呼び出し元は別のルートを選択します)。
     * 押した後、途中で噴射に失敗しても下がり続けます。最後に、指で押し続けないように、再発行して持ち上げる必要があります。
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
        sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

    /**
     * `appops set <pkg> PLAY_AUDIO ignore`;初回ミュート時に元のモード（ファイルドロップ中）を記録し、ミュート解除時に復元します。 以前に設定されていなかった
     * は、デフォルトを書き込まなくても許可されるように復元されます ([AudioMuteLedger.restoreMode] を参照)。
     * コマンドが失敗すると false が返され、元のモードは記録されません (元の値が読み取れない場合は変更されません)。ミカがいるユーザー (元のユーザー) に作用します。
     */
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = audioMuted(packageName, null, muted)

    /** は [setAudioMuted] と同じで、[userId] のユーザーの共有 (利用可能、作業情報) に基づいて動作します。レコードは [AppAudioKey] に従って個別に記録されます。 */
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean {
        if (userId == myUser) return audioMuted(packageName, null, muted)
        return audioMuted(packageName, otherUser(userId) ?: return false, muted)
    }

    private fun audioMuted(packageName: String, user: Int?, muted: Boolean): Boolean = synchronized(this.muted) {
        val key = AppAudioKey.of(packageName, user)
        if (muted) {
            if (!this.muted.contains(key)) {
                val (code, out) = appops("get", packageName, user)
                if (code != 0) return false
                this.muted.put(key, parsePlayAudioMode(out))
            }
            appops("set", packageName, user, "ignore").first == 0
        } else {
            val ok = appops("set", packageName, user, AudioMuteLedger.restoreMode(this.muted[key])).first == 0
            if (ok) this.muted.remove(key)
            ok
        }
    }

    /** 起動時に、前回復元されなかったパッケージを復元します（レコード内のパッケージのみが移動されます）。 */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll(::writeRecorded) }
    }

    /** 記録されたキーを押して項目を書き戻します。キーがその形式にない場合は、永久に保持されないように処理済みとして扱われます。 */
    private fun writeRecorded(key: String, mode: String): Boolean {
        val (pkg, user) = AppAudioKey.parse(key) ?: return true
        return appops("set", pkg, user, mode).first == 0
    }

    /** [user] 空の場合、それはミカがいるユーザーです。 */
    private fun appops(verb: String, packageName: String, user: Int?, mode: String? = null): Pair<Int, String> {
        val u = (user ?: myUser).toString()
        return if (mode == null) sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO")
        else sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO", mode)
    }

    /** 他のユーザー番号はそのまま与えられます。それがMikaがいるユーザーの場合はnull（元どおりに処理されます）。不正な場合は null となり、呼び出し元はそれに応じて拒否します。 */
    private fun otherUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != myUser }

    override fun getAudioMode(packageName: String): String? = audioMode(packageName, null)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        if (userId == myUser) return audioMode(packageName, null)
        return audioMode(packageName, otherUser(userId) ?: return null)
    }

    private fun audioMode(packageName: String, user: Int?): String? {
        val (code, out) = appops("get", packageName, user)
        return if (code == 0) parsePlayAudioMode(out) else null
    }

    /** の「デフォルト」（未設定）は許可と書かれていますが、理由は [AudioMuteLedger.restoreMode] と同じです。 */
    override fun setAudioMode(packageName: String, mode: String): Boolean = audioMode(packageName, null, mode)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        if (userId == myUser) return audioMode(packageName, null, mode)
        return audioMode(packageName, otherUser(userId) ?: return false, mode)
    }

    private fun audioMode(packageName: String, user: Int?, mode: String): Boolean {
        if (!MODE_RE.matches(mode)) return false
        val ok = appops("set", packageName, user, AudioMuteLedger.restoreMode(mode)).first == 0
        if (ok) synchronized(muted) { muted.remove(AppAudioKey.of(packageName, user)) }
        return ok
    }

    /**
     * このパッケージ ([userId] のユーザー共有内) には、ホーム画面に表示されるタスクがあります。ユーザーはそれをホーム画面で使用しています。ミュート機能はパッケージに応じて動作します。このとき、ユーザーの手には音は鳴りません。
     * ミカはしたがって、そのまま放置しました。ホーム画面がはっきりと見えない場合は、「アクティブ」を押してください。ユーザーが使用しているものをミュートするよりも、画面をミュートしない方が良いでしょう。
     */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        try {
            inUseOnMainScreen(packageName, userId)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            true
        }

    /**
     * ユーザーの元のクリップボードは、読み取り可能な場合にのみタッチしてください。読み取りできない場合は、「空だった」と「内容はあるが表示できない」の違いがわかりません。
     * で上書きされると復元できません。このとき、[CLIP_UNREADABLE]に戻り、クリップボードはそのまま残ります。
     */
    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            if (!clipSaved) {
                if (!canReadClipboard()) return CLIP_UNREADABLE
                val prior = clipboard.primaryClip
                // 空の読み取りは必ずしも空を意味するわけではありません。システムは読み取りを密かにブロックする場合があります。このとき、説明文にも内容があると記載されます。
                when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                    ShellClip.Read.Unreadable -> {
                        Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                        return CLIP_UNREADABLE
                    }
                    ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                    ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
                }
                savedClip = prior
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            clipboard.setPrimaryClip(clip)
            ourText = text
            CLIP_SET
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip() = synchronized(clipLock) {
        if (!clipSaved) return
        runCatching {
            val prior = savedClip
            val now = nowState(ourText)
            val action = ShellClip.restore(prior == null, now)
            Log.i(TAG, "restoreClip: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(prior!!))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }
        savedClip = null
        clipSaved = false
        ourText = null
    }

    override fun savedClipText(): String? = synchronized(clipLock) {
        if (!clipSaved) return null
        runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
    }

    override fun restoreClipText(original: String, ours: String) = synchronized(clipLock) {
        runCatching {
            val now = nowState(ours)
            val action = ShellClip.restore(original.isEmpty(), now)
            Log.i(TAG, "restoreClipText: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(ClipData.newPlainText("", original)))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
        }.onFailure { Log.w(TAG, "restoreClipText failed", it) }
        Unit
    }

    /** クリップボードは今ミカが置いたものですか？ [ours];読み取れない場合（コンテンツはあるが取得できない場合）、空であってはなりません。 */
    private fun nowState(ours: String?): ShellClip.Now {
        val now = clipboard.primaryClip
        return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
            ShellClip.Read.Empty -> ShellClip.Now.Empty
            ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
            ShellClip.Read.Has -> {
                val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                    now.getItemAt(0).text?.toString() == ours
                if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
            }
        }
    }

    /** シェルは、バックグラウンドでクリップボードを読み取る権限があり、クリップボードを読み取る権限操作がオフになっていない場合にのみ読み取ることができます。 */
    private fun canReadClipboard(): Boolean = runCatching {
        val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
        val ops = context.getSystemService(AppOpsManager::class.java)
        granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun suppressOverlay(clip: ClipData): ClipData {
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    /**
     * みか 貼り付けられたコンテンツ (住所、携帯電話番号など) は機密としてマークされています。クリップボードのプレビューとシステムおよび入力方法の履歴には、元のテキストが表示されません。
     * は、Mika が自分自身に入力したもののみをマークし、ユーザーのクリップボードを復元するときにそのまま残ります。
     */
    private fun markSensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        return clip
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // 組み込みアシスタントのプロセスは、Mika だけで終了するわけではありません。Binder は他のユーザーによっても取得される可能性があります。Mika のみがインターフェイス メソッドを呼び出すことができます。
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        if (!ownDisplay(displayId)) return commandResult(-1, "No task display is active.")
        return exec(command, displayId)
    }

    override fun runCommand(command: String): Bundle = exec(command, null)

    /** アシスタント プロセスは、Mika によって渡された願いを調べます ([setRestoreWish] を参照)。ミカが死亡した後、それに応じてそれを引き上げるかどうかを決定します。 */
    @Volatile var onRestoreWish: ((Int) -> Unit)? = null

    override fun setRestoreWish(flags: Int) {
        onRestoreWish?.invoke(flags)
    }

    private val deviceUnlock by lazy { DeviceUnlockInput(context,
        inject = { inject(it, Display.DEFAULT_DISPLAY, waitForFinish = true) },
        injectTo = { event, uid -> injectTo(event, Display.DEFAULT_DISPLAY, uid) },
        command = { exec(*it.toTypedArray()) == 0 }) }

    override fun wakeDevice(): Boolean = deviceUnlock.wake()
    override fun dismissDeviceKeyguard(): Boolean = deviceUnlock.dismiss()
    override fun revealDeviceCredential(): Boolean = deviceUnlock.reveal()
    override fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean = deviceUnlock.enter(kind, credential)

    private fun commandResult(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }

    /** は、`sh -c` をシェルとして実行し、出力をマージし、時間制限があります。 [displayId]が空でない場合はMIKA_DISPLAY_IDを入れます。 */
    private fun exec(command: String, displayId: Int?): Bundle {
        fun result(code: Int, output: String) = commandResult(code, output)
        if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                .apply { if (displayId != null) environment()["MIKA_DISPLAY_ID"] = displayId.toString() }.start()
        } catch (t: Throwable) {
            return result(-1, t.message ?: "Command could not start.")
        }
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < COMMAND_OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, COMMAND_OUTPUT_MAX - bytes.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val output = bytes.toString(Charsets.UTF_8.name()) + if (bytes.size() >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
        return result(if (finished) process.exitValue() else -1, output)
    }

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? {
        val pid = DevtoolsSocket.pidOf(name) ?: return null
        // ミカ自身のプロセスのデバッグ ソケットのみに接続します。他のアプリの Web ページはここを経由しません。
        val uid = runCatching { DevtoolsSocket.procUid(File("/proc/$pid/status").readText()) }.getOrNull()
        if (uid != allowedUid) {
            Log.w(TAG, "devtools socket refused: pid $pid is not Mika's (uid $uid)")
            return null
        }
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        } catch (e: Exception) {
            Log.w(TAG, "devtools socket $name: ${e.message}")
            null
        } finally {
            // がコピーした fd を渡しましたので、このコピーをオフにします。
            runCatching { socket.close() }
        }
    }

    override fun releaseAll() {
        restoreClip()
        synchronized(muted) { muted.packages() }.forEach { key ->
            AppAudioKey.parse(key)?.let { (pkg, user) -> audioMuted(pkg, user, false) } ?: synchronized(muted) { muted.remove(key) }
        }
        awake.stopAll()
        val (all, watch) = synchronized(displays) {
            displays.values.toList().also { displays.clear() } to orientationWatch.also { orientationWatch = null }
        }
        watch?.close()
        all.forEach { it.release() }
    }

    override fun destroy() {
        try {
            releaseAll()
        } finally {
            exitProcess(0)
        }
    }

    /**
     * は新しく構築されたミカ画面を設定するだけで、メイン画面は変更されません。一部のシステムでは、新しく作成された外部/仮想画面がデフォルトでフリーフォームに設定されます。 したがって、
     * アプリは小さなウィンドウに縮小され、アクセシビリティ座標もウィンドウのスケーリングの影響を受けます。画面を作成した後、ページを起動する前に、明示的に全画面モードを使用してください。
     * その後のアプリの起動、ページジャンプ、ホーム画面から戻るタスクはすべてこの画面の戦略に従います。
     */
    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            // WindowConfiguration.WINDOWING_MODE_FULLSCREEN = 1 (隠し定数)
            wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java).invoke(wm, displayId, 1)
        }.onFailure {
            Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
            if (exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1") != 0) {
                Log.w(TAG, "could not set fullscreen windowing mode on display $displayId")
            }
        }
    }

    /** IWindowManager.setDisplayImePolicy: 入力メソッドがユーザーのホーム画面にポップアップしません。どのポリシーを選択するかについては、「IME_POLICY」を参照してください。 */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    /**
     * は、[targetUid] が所有するフォーカス ウィンドウにのみキャストし、処理後に戻ります。フォーカスがない場合、システムはそれを拒否し、イベントは他のウィンドウに渡されません。
     * は、Android 13 以降、隠しオーバーロード `injectInputEvent(event, mode, targetUid)` を使用します。このオーバーロードがなければ挿入されません。
     * 失敗については例外タイプのみが記録されます。拒否の理由にはキーストロークは含まれませんが、ロック画面の資格情報はこのパスに入力され、冗長な情報は残りません。
     */
    @SuppressLint("PrivateApi")
    private fun injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }

    /** 注入エントリは、API 34 以降、InputManagerGlobal にあり、それ以前は InputManager にあります。 */
    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /**
     * はシェル コマンドを実行し、(終了コード、出力) を返します。タイムアウトは -1 を返します。
     * 出力は別のスレッドで読み取られ、スタックしたコマンドによってバインダー スレッドが永久にブロックされることはありません。
     */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    companion object {
        private const val TAG = "MikaShell"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        // injectStroke ストロークの上限: 押してから持ち上げるまでのポイント、ミリ秒
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 10L
        private const val COMMAND_OUTPUT_MAX = 64 * 1024
        private const val READER_JOIN_MS = 1_000L
        private val MODE_RE = Regex("[a-z_]+")
        private const val PER_USER_RANGE = 100_000

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        /** コールド スタートアップが通常のスタートアップに変更されました: アプリにはミカの画面の外にタスクがあります。 */
        const val START_OK_COLD_IGNORED = 4
        /** startView: このリンクを開けるアプリはありません。 */
        const val START_UNRESOLVED = 5
        /** startView: リンクを開くことは許可されていません ([ShellLinks] を参照)。 */
        const val START_INVALID_LINK = 6
        /** は、Mika の別の画面で他のタスクに使用されます。 */
        const val START_IN_USE_BY_OTHER = 7
        /** startAppInUser: そのユーザーは利用できません ([AppCopies] を参照)。 */
        const val START_USER_NOT_ALLOWED = 8

        // appCopyUsers のバンドル
        const val KEY_COPY_USERS = "users"
        const val KEY_COPY_SUMMARY = "summary"
        // UserProperties.PROFILE_API_VISIBILITY_HIDDEN
        private const val PROFILE_API_VISIBILITY_HIDDEN = 1

        // setClipText の結果
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        private const val CLIP_LABEL = "Mika"
        // この名前は AppOpsManager では非表示になります
        private const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        private const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        private val WCT: Class<*> by lazy { Class.forName("android.window.WindowContainerTransaction") }
        private const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        private const val INJECT_MODE_WAIT_FOR_RESULT = 1 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        private const val INJECT_MODE_WAIT_FOR_FINISH = 2 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        // WindowConfiguration.ACTIVITY_TYPE_*: デスクトップ、最近のタスク、アシスタントなどは他のタイプです
        private const val ACTIVITY_TYPE_UNDEFINED = 0
        private const val ACTIVITY_TYPE_STANDARD = 1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (一部は非表示の定数)
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /**
         * 仮想画面の作成に使用されるフラグ。 [trusted] が false の場合、信頼された画面に依存する項目を削除します。一部のシステムにはシェルがありません。
         * ADD_TRUSTED_DISPLAY、TRUSTED を指定すると、SecurityException が発生してシステムによって拒否されます。 OWN_DISPLAY_GROUP、常にロック解除
         * および OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED も、信頼された画面でのみ使用できます。残りの項目は、例によって信頼できるかどうかとは関係ありません。
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // に OWN_FOCUS のみがある場合、仮想画面でアプリを起動するか、アプリ自体で新しいページを開くと、それが「トップ フォーカス画面」になります。
            // メイン画面のフォーカス ウィンドウがクリアされます。 STEAL_TOP_FOCUS_DISABLED (OWN_FOCUS と TRUSTED を同時に持つ必要がある) は、メイン画面のフォーカスを取得できないようにします。
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        /**
         * の注入方法。キーはシステムが誰に投票するかを決定するまで待機します (WAIT_FOR_RESULT)。キーを受け取ることができるウィンドウが画面上にない場合、注入は失敗し、呼び出し元はシェル コマンドに戻ります。
         * またはレポート アクションが失敗し、「送信すれば完了」ではなくなります。アプリが処理を完了するのではなく、配布を待つだけで、せいぜいシステム自体の注入がタイムアウトになるまで待ちます。
         * タッチはまだ待機しています (ASYNC): ストロークは時間に従ってポイントごとに配信される必要があり、配信を待機すると速度が中断されます。 [waitForFinish] (ホーム画面のロック解除入力) は、アプリが処理されるのを待ちます。
         */
        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
            waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
            key -> INJECT_MODE_WAIT_FOR_RESULT
            else -> INJECT_MODE_ASYNC
        }

        /**
         * プレス/リフトのペア: [fallback] (シェル コマンド) を押したときに注入が成功しなかった場合にのみ実行されます。配信後に戻ると、余分な完全なクリック/キー押下が発生します。
         * のみが再発行され、発生します (失敗した場合は 1 回再試行します)。
         */
        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
            if (!down()) return fallback()
            return up() || up()
        }

        /** 権限が不足している場合 (シェルに ADD_TRUSTED_DISPLAY がないなど、SecurityException) のみ、トラスト マークを削除して再試行する価値があります。他の障害でもエラーが報告されます。 */
        internal fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, _FALLBACK_DISPLAY = 1, _HIDE = 2。
        // 実機（Android 14）での実測 LOCAL：仮想画面上の入力ボックスにフォーカスを当てた後、仮想画面上にインプットメソッドウィンドウが表示され、メイン画面のフォーカスはHIDEなしで変化しません。
        private const val IME_POLICY = 0

        // SystemUI 規約: シェルからクリップボードが取り込まれた場合、「コピーされた」フローティング レイヤーは表示されません (そうでない場合は、ユーザーのホーム画面にポップアップ表示されます)。
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        /**
         * タスク (ページはパッケージ [taskPackages] 内にあり、ユーザー [taskUser] に属します。読み取れない場合は null)。 [packageName] はユーザー [userId] のシェアですか?
         * [userId] 空の場合、どのコピーであるかは関係ありません。タスクのユーザーがそれを読めない場合でも、そのタスクはカウントされます (ユーザーの邪魔になるリスクがあるよりは、タスクが占有されていると見なされるほうがよいでしょう)。
         */
        internal fun sameCopy(taskPackages: Collection<String>, taskUser: Int?, packageName: String, userId: Int?): Boolean =
            packageName in taskPackages && (userId == null || taskUser == null || taskUser == userId)

        /** 分析 `appops get <pkg> PLAY_AUDIO`: "PLAY_AUDIO: 無視; time=..." → "無視";未設定→「デフォルト」。 */
        internal fun parsePlayAudioMode(output: String): String = AudioMuteLedger.parseSetMode(output) ?: "default"
    }
}
