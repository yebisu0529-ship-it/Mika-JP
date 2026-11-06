package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.HelperRestorePolicy
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.RestoreCommands
import com.wangzan101.mika.core.shell.RestoreWish
import java.io.File
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * 内蔵ヘルパープロセスの入口。端末のワイヤレスデバッグ（adbd の exec: サービス）から shell ユーザーとして起動する。
 * `CLASSPATH=<base.apk> app_process /system/bin --nice-name=<パッケージ名>:helper com.wangzan101.mika.shell.HelperMain <パッケージ名> <Mika の UID> <署名のフィンガープリント>`,
 * ワンタイムパスワードは標準入力の1行から読む（[HelperHandoff.command]）。USB デバッグの起動スクリプトも同じコマンドと引数を使う
 * （[HelperHandoff.usbScript]）。root 化済み端末でも `su 2000 -c` により shell ユーザーとして同じ入口の起動を要求する（[HelperHandoff.rootCommand]）。
 *
 * の特権ロジックはすべて [ShellService] にあります。ここでは、バインダーをミカに渡すことのみを担当し、ミカのプロセスが終了した後に終了することはありません。
 * ミカが残した変更を元に戻し、ミカが再び立ち上がったときに再送信します。美香が死んでしばらく戻らず、彼女が与えた願い([RestoreWish])が引き上げられようとしたとき、
 * は、アクセシビリティ サービスをシステム リストに書き込み、ハンドオーバー プロバイダーを通じてそのプロセスをプルアップします ([HelperRestorePolicy] を参照)。
 */
object HelperProcess {
    private const val TAG = "MikaHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (!acceptsIdentity(Process.myUid(), Os.getgid())) {
            Log.e(TAG, "ヘルパーは UID/GID 2000 の shell ユーザーで起動してください")
            exitProcess(2)
        }
        if (args.size < 3) {
            Log.e(TAG, "usage: HelperMain <package> <uid> <cert-sha256> (nonce on stdin)")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certPin = args[2]
        // 読み取り後に閉じる: 標準入力はパスワードを渡すためにのみ使用されます
        val launchNonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        runCatching { System.`in`.close() }
        if (launchNonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            exitProcess(2)
        }
        // ShellContext は、独自の ActivityThread を構築するときにメイン スレッド Looper を必要とします
        Looper.prepareMainLooper()
        val service = ShellService(allowedUid = appUid)
        // 最後にアプリがミュート モードで終了し、復元できなかったときは、まずサウンドを戻してください。
        service.recoverMuted()
        val helper = Helper(service, packageName, appUid, certPin)
        helper.start(launchNonce)
        Looper.loop()
    }

    /** 通常ヘルパーへ root の UID/GID を持ち込まない。補助グループや SELinux の同等性は判定しない。 */
    internal fun acceptsIdentity(uid: Int, gid: Int): Boolean =
        uid == HelperHandoff.SHELL_UID && gid == HelperHandoff.SHELL_UID

    private class Helper(
        private val service: ShellService,
        private val packageName: String,
        private val appUid: Int,
        private val certPin: String,
    ) {
        private val context: Context = ShellContext.create()
        private val authority = HelperHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        /** の引き継ぎとミカの死亡処理は、相互に干渉したりメインスレッドをブロックしたりすることなく、すべてこのスレッド上に配置されます。 */
        private val worker = Handler(HandlerThread("helper-handoff").apply { start() }.looper)
        /** 次回引き継ぎ用のパスワード（前回美香が承諾した際に再発行）。 */
        private var nonce: String? = null
        /** ミカプロセスによって渡されたトークン。 null は、ミカが今ここにいないことを意味します。 */
        private var appToken: IBinder? = null
        private var retrying = false
        /** 美香が託した最後の願い。助手は最初に立ち上がったとき何もしなかった。 */
        private var wish = RestoreWish.NONE
        private val restorePolicy = HelperRestorePolicy()
        /** ミカが死ぬたびに 1 つ追加します。待っている間にミカは戻ってきて再び死に、前の待機は無効になります。 */
        private var deaths = 0

        init {
            service.onRestoreWish = { flags ->
                worker.post {
                    val next = RestoreWish.fromFlags(flags)
                    if (next != wish) Log.i(TAG, "restore wish: $next")
                    wish = next
                }
            }
        }

        fun start(launchNonce: String) = worker.post {
            when (deliver(launchNonce)) {
                Outcome.Accepted -> {
                    // ミカはこれを受け入れました: 以前の (または残りの) ヘルパー インスタンスはすべて削除されるべきです
                    killOtherInstances()
                    watchUid()
                }
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> exitProcess(1)
            }
        }

        private enum class Outcome { Accepted, Rejected, Failed }

        /** は、ActivityManager (`content call` と同じ) を通じてミカのプロバイダーを取得し、バインダーと自己報告の uid/pid を渡します。 */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?): Outcome {
            if (n == null) return Outcome.Rejected
            // パッケージ名と uid は、再インストールされた他のアプリによって占有されている可能性があります。署名が一致しない場合は、Binder に送信せず、変更を元に戻して終了してください。
            if (!signedAsPinned()) {
                Log.e(TAG, "$packageName is missing or not signed with the pinned certificate; exiting")
                runCatching { service.releaseAll() }
                exitProcess(0)
            }
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                    IBinder::class.java, String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed
            try {
                // と同じ名前の権限は 1 つのパッケージにのみ所属できます。確認して、Mika 以外のアプリにバインダーを渡さないほうがよいでしょう。
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, n)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putString(HelperHandoff.KEY_APK, System.getProperty("java.class.path"))
                    restorePolicy.report(System.currentTimeMillis()).takeUnless { it.empty }?.let { r ->
                        putLong(HelperHandoff.KEY_RESTORED_AT, r.lastRestoredAtMs)
                        putInt(HelperHandoff.KEY_RESTORES, r.recent)
                        putLong(HelperHandoff.KEY_GAVE_UP_AT, r.gaveUpAtMs)
                    }
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(provider, caller, authority, HelperHandoff.METHOD_ATTACH, null, extras) as Bundle?
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) {
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_TOKEN) ?: return Outcome.Rejected
                nonce = result.getString(HelperHandoff.KEY_NEXT)
                appToken = appBinder
                try {
                    appBinder.linkToDeath({ worker.post { onAppDied(appBinder) } }, 0)
                } catch (t: Throwable) {
                    worker.post { onAppDied(appBinder) }
                }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        /** Mega プロセスはなくなりました。作成された画面、ミュート、およびクリップボードの変更は削除され (サービス終了時と同じ)、アシスタント自体は残ります。 */
        private fun onAppDied(token: IBinder) {
            if (appToken !== token) return
            appToken = null
            runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "cleanup failed", it) }
            if (!installed()) {
                Log.i(TAG, "$packageName is gone; exiting")
                exitProcess(0)
            }
            Log.i(TAG, "$packageName died; waiting for it to come back")
            val death = ++deaths
            worker.postDelayed({ if (death == deaths) maybeRestore() }, HelperRestorePolicy.GRACE_MS)
        }

        /**
         * ミカはしばらく死んでまだ戻ってきません。最後の願いに従ってうんちをするかどうかを決定します。プルした場合は、最初にアクセシビリティ サービスに書き戻します (システムが強制的に停止すると削除されます)。
         * 再度ハンドオーバー: ミカのプロバイダーを使用すると、システムはそのプロセスを開始します (プロバイダーはバックグラウンド起動の制限を受けず、インターフェイスは表示されません)。
         * アプリケーションが通常どおり作成され、カプセルやその他のサービスが続きます。シェルは、任意のアプリのプロバイダーを取得できます (`content` コマンドで使用されるのと同じパス)。
         */
        private fun maybeRestore() {
            val now = System.currentTimeMillis()
            when (val d = restorePolicy.decide(wish, appBack = appToken != null || retrying, now, forceStopped = forceStopped())) {
                HelperRestorePolicy.Decision.Restore -> Unit
                HelperRestorePolicy.Decision.AppBack -> return
                else -> {
                    Log.i(TAG, "not restoring $packageName: $d")
                    return
                }
            }
            Log.i(TAG, "restoring $packageName (${restorePolicy.report(now).recent} in the last window)")
            if (wish.accessibility) restoreAccessibility()
            retrying = true
            redeliver(0)
        }

        /** システムの強制停止フラグを確認する。取得できない場合は従来の復帰判断を保つ。 */
        private fun forceStopped(): Boolean = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
        }.getOrElse { t ->
            Log.w(TAG, "cannot read stopped state of $packageName", t)
            false
        }

        /** ミカのアクセシビリティ サービスをシステム リストに追加し、メイン スイッチをオンにします。他のサービスは変更ありません。失敗した場合は、ログを記録して、通常どおりミカをプルします (立ち上がると自動的に修復されます)。 */
        private fun restoreAccessibility() {
            val me = PermissionCommands.accessibilityService(packageName)
            val current = service.runCommand(PermissionCommands.READ_ACCESSIBILITY).getString("output")
            for (cmd in RestoreCommands.accessibility(current, me)) {
                val r = service.runCommand(cmd)
                if (r.getInt("exitCode", -1) != 0) {
                    Log.w(TAG, "restore accessibility: '$cmd' failed: ${r.getString("output")?.trim()}")
                    return
                }
            }
            Log.i(TAG, "accessibility service of $packageName written back")
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun installed(): Boolean = runCatching {
            context.packageManager.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(context.packageManager, packageName, 0, userId)
            true
        }.getOrElse { t ->
            // パッケージが利用できなくなった場合にのみ終了します。反射が利用できない場合、はっきりと見ることができず、それがまだそこにあるかのように扱うことができます。
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            cause !is android.content.pm.PackageManager.NameNotFoundException
        }

        /**
         * ミカの uid を見つめます: プロセスが再起動されると (存在しない状態から任意の状態に)、システムは一度報告し、その後再びバインダーを渡します。
         * は、ActivityManager の uid 重要度監視を使用し (PACKAGE_USAGE_STATS が必要、シェルにはそれがあります)、コールバックはバインダー スレッド内にあります。
         * の登録に失敗した場合は、Mika が対応します。ハンドオーバーの前に、adbd を通じて別のアシスタントが作成され、新しいアシスタントが受け入れられた後に終了します。
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun watchUid() {
            runCatching {
                // シェル パッケージ名のコンテキストを使用して、ActivityManager を作成します。登録時に、システムは呼び出し元のパッケージ名に基づいて権限をチェックします。
                val manager = ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                    .apply { isAccessible = true }.newInstance(context, worker)
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val listener = Proxy.newProxyInstance(HelperProcess::class.java.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
                    when (method.name) {
                        "onUidImportance" -> { onUidImportance(a[0] as Int, a[1] as Int); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === a?.getOrNull(0)
                        "toString" -> "MikaHelperUidListener"
                        else -> null
                    }
                }
                ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
                    .invoke(manager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            }.onFailure { Log.w(TAG, "cannot watch $packageName; relying on the app to restart the helper", it) }
        }

        private fun onUidImportance(uid: Int, importance: Int) {
            if (uid != appUid || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) return
            worker.post { if (appToken == null && !retrying) redeliver(0) }
        }

        /** 再ハンドオーバー;プロバイダーが一時的に利用できない場合は、しばらくしてからもう一度試してください。それでもダメなら、次にミカが起きるまで待ってください。 */
        private fun redeliver(attempt: Int) {
            if (appToken != null) return
            when (deliver(nonce)) {
                Outcome.Accepted -> retrying = false
                // ミカはこのアシスタントを認識しません (再インストールされているか、ペアリングが解除されているか、新しいアシスタントに置き換えられています): 終了 現在
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> {
                    retrying = attempt + 1 < RETRY_DELAYS_MS.size
                    if (retrying) worker.postDelayed({ redeliver(attempt + 1) }, RETRY_DELAYS_MS[attempt + 1])
                }
            }
        }

        /** にインストールされている [packageName] には、起動時にミカによって与えられたものと同じ uid と署名証明書のフィンガープリントがまだあります。はっきり見えないと矛盾しているとみなされてしまいます。 */
        @SuppressLint("DiscouragedPrivateApi")
        private fun signedAsPinned(): Boolean = runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (userId == 0) pm.getPackageInfo(packageName, flags)
            else pm.javaClass.getMethod("getPackageInfoAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, packageName, flags, userId) as PackageInfo
            val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            info.applicationInfo?.uid == appUid && HelperHandoff.certPin(signers) == certPin
        }.getOrElse { t ->
            Log.w(TAG, "cannot read signing info of $packageName", t)
            false
        }

        /** は、同じプロセス名と同じ uid を持つプロセスのみを認識します。他の app_processes が誤って破損することはありません。 */
        private fun killOtherInstances() {
            val me = Process.myPid()
            val name = HelperHandoff.processName(packageName)
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@forEach
                val argv0 = String(cmdline.takeWhile { it != 0.toByte() }.toByteArray())
                if (argv0 == name) {
                    Log.i(TAG, "stopping older helper $pid")
                    Process.killProcess(pid)
                }
            }
        }

        companion object {
            private const val PER_USER_RANGE = 100_000
            private val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 3_000, 10_000)
        }
    }
}
