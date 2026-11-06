package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * アシスタントプロセスがバインダーをミカに渡すときに双方が共有する合意: プロバイダーのメソッド名とバンドルキー、アシスタントのプロセス名、および起動コマンド。
 * とミカ側は受け入れ前に検査する必要があります。
 */
object HelperHandoff {
    const val SHELL_UID = 2000
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "com.wangzan101.mika.shell.HelperMain"

    // アシスタント→ミカ
    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"
    /** アシスタントがミカを引き上げた記録 ([HelperRestoreReport] を参照)。引き上げないと取得されません。 */
    const val KEY_RESTORED_AT = "restoredAt"
    const val KEY_RESTORES = "restores"
    const val KEY_GAVE_UP_AT = "restoreGaveUpAt"

    // ミカ → アシスタント
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"

    fun authority(packageName: String) = "$packageName.helper"

    /** アシスタントのプロセス名 (app_process の --nice-name)。古いアシスタント インスタンスを識別するためにも使用されます。 */
    fun processName(packageName: String) = "$packageName:helper"

    /**
     * は、adbd の exec: サービスによって実行されるコマンドです (adbd は `sh -c` を使用して実行します)。ストリームがオフになると、adbd はすべての sh グループのプロセスを強制終了します。
     * したがって、アシスタントは setid を使用して新しいセッションを開始し、その後 `read` はアプリがハンドオーバーを受信して​​フローを閉じるまで待機します。
     * は、setsid が有効になる前に sh が終了し、アシスタントが同時に強制終了されるのを防ぎます。
     *
     * パスワードはコマンド ラインに入力されません (同じシェル プロセスは /proc/<pid>/cmdline を読み取ることができます): アプリがストリームを開いた後、[stdinLine] という行を書き込みます。
     * sh は変数を読み取り、組み込みのエコーを使用してパイプ経由でアシスタントに変数を渡します。アシスタントの標準入力は、adb ストリームではなく、このパイプです。
     * [certPin] は、ミカの署名証明書の指紋です。これは秘密ではなく、パラメータに設定されます。
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        launch(apkPath, packageName, appUid, certPin, launcher = "setsid")

    /** [command] と [rootCommand] に共通する起動部分。[launcher] はapp_processの前に置くコマンド。 */
    private fun launch(apkPath: String, packageName: String, appUid: Int, certPin: String, launcher: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} $launcher app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /**
     * su 2000 -cへ渡すコマンド。最初にidを返し、アプリがUID/GID 2000を確認してから合言葉を標準入力へ送る。
     * 合言葉はコマンド行に含めず、引継ぎ後に入力を閉じる。suが応答しなければ合言葉を送らない。
     * USB起動と同じくnohupを使い、su終了時のハングアップでヘルパーが巻き込まれないようにする。
     */
    fun rootCommand(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "id; " + launch(apkPath, packageName, appUid, certPin, launcher = "nohup setsid")

    /** 「コンピューターで起動」というスクリプト ファイル名がアプリの外部ファイル ディレクトリに配置されます (コンピューターは USB デバッグを通じてシェルとしてそれを読み取ります)。 */
    const val USB_SCRIPT = "usb-start.sh"

    /** メイン ユーザーの外部ストレージは、シェルでは /sdcard とも呼ばれます。ユーザーに表示されるコマンドでは、この省略表現が使用されます。他のユーザーは実際のパスに従って書き込みます。 */
    private const val PRIMARY_STORAGE = "/storage/emulated/0/"

    /** コンピューター上で実行されるコマンド。 [scriptPath] は、電話機上のスクリプトの実際のパスです。 USB デバッグを通じて */
    fun usbCommand(scriptPath: String): String =
        "adb shell sh " + (if (scriptPath.startsWith(PRIMARY_STORAGE)) "/sdcard/" + scriptPath.removePrefix(PRIMARY_STORAGE) else scriptPath)

    /**
     * コンピューターによって実行される起動スクリプト: [command] と同じアシスタントと同じパラメーターで起動され、パスワードが [nonce] に変更されます (スクリプトに記述されています。[HandoffNonces.usbNonce] を参照)。
     * adb シェル セッションが終了すると (コマンドが実行され、接続が切断されると)、adbd はそのプロセスのセットを強制終了します。アシスタントは nohup を使用してハングアップ信号を無視し、setsid を使用して新しいセッションを開始し、バックグラウンドのサブシェルに入れます。
     * (サブシェルの終了後、init によって管理されます)、標準出力とエラーは失われ、adb ストリームに接続されなくなります。スクリプトは、setsid が有効になるまでしばらく待機します。
     * アシスタント プロセスがまだある場合は、「開始中」と言ってください。そこにない場合は (パスワードの有効期限が切れているか、Mika がパスワードを認識しないことが主な理由です)、Mika を開いて再度実行するようにユーザーに依頼します。
     * Android 標準の sh と toybox にあるコマンドだけを使う。shell の身分でなければ（adb 経由で実行されていなければ）起動しない: Mika は shell から引き渡されたヘルパーだけを受け取る。
     * 端末によっては抜線後に停止する。その場合はケーブルをつなぎ直し、同じコマンドを実行する。
     */
    fun usbScript(apkPath: String, packageName: String, appUid: Int, certPin: String, nonce: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        val again = usbCommand(scriptPath)
        return """
            |#!/system/bin/sh
            |# Mikaが生成しました。PCからスマートフォン操作アシスタントを起動します: $again
            |if [ "${'$'}(id -u)" != "$SHELL_UID" ]; then
            |  echo "PCからUSB接続で実行してください: $again"
            |  exit 1
            |fi
            |if [ ! -f ${quote(apkPath)} ]; then
            |  echo "Mikaが更新または再インストールされました。Mikaを一度開いてから、このコマンドを再実行してください。"
            |  exit 1
            |fi
            |n=${quote(nonce)}
            |(echo "${'$'}n" | CLASSPATH=${quote(apkPath)} nohup setsid app_process /system/bin --nice-name=$name $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} >/dev/null 2>&1 &)
            |sleep 2
            |if pidof $name >/dev/null 2>&1; then
            |  echo "Mikaの操作アシスタントを起動しています。Mikaを開き、スマートフォン操作が利用できるか確認してください。"
            |  echo "多くの端末ではケーブルを抜いても動作を続けます。再起動後や抜線後に操作できなくなった場合は、ケーブルをつないでこのコマンドを再実行してください。"
            |else
            |  echo "Mikaの操作アシスタントを起動できませんでした。Mikaを一度開いてから、このコマンドを再実行してください。"
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /**
     * 「パソコンで起動」スクリプトをいつ書き直すか: ヘルパーが来たときと去ったとき、およびパスコードがまとめて失効したとき（[HandoffNonces.resets]、ペアリング解除など）;
     * 出される値は、その時点でヘルパーがいるかどうか。後者の場合ヘルパーはずっと不在かもしれず、それに合わせて書き直さなければ、スクリプトには失効したパスコードが残ってしまう。
     */
    fun usbScriptWrites(helper: Flow<Any?>, resets: Flow<Int>): Flow<Boolean> = combine(helper, resets) { h, _ -> h != null }

    /**
     * この時点で書くべきスクリプト: ヘルパーが接続していれば（[connected]）パスコードなしの [usbStub] を書き、パスコードも要求しない; そうでなければ新しいパスコード付きの [usbScript] を書く。
     * [certPin] および [nonce] は、パスワードを使用してスクリプトを作成する場合にのみ使用されます。
     */
    fun usbFile(
        connected: Boolean, apkPath: String, packageName: String, appUid: Int, certPin: () -> String, nonce: () -> String, scriptPath: String,
    ): String =
        if (connected) usbStub(packageName, scriptPath)
        else usbScript(apkPath, packageName, appUid, certPin(), nonce(), scriptPath)

    /** アシスタントの接続時に置き換えられるスクリプト: パスワードなし、一言言うだけです。プロセスが存在しない場合 (たとえば、電話が再起動され、ミカがまだ開かれていない場合)、最初にミカを開くようにユーザーに依頼してください。 */
    fun usbStub(packageName: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        return """
            |#!/system/bin/sh
            |# Mikaが生成しました。操作アシスタントは接続済みのため、このファイルに起動処理は含まれていません。
            |if pidof $name >/dev/null 2>&1; then
            |  echo "Mikaの操作アシスタントは起動済みです。追加の操作は不要です。"
            |else
            |  echo "Mikaの操作アシスタントは停止しています。Mikaを一度開いてから、このコマンドを再実行してください: ${usbCommand(scriptPath)}"
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /** 署名証明書 (DER) の SHA-256 フィンガープリント。署名者が複数いる場合は、署名者を並べ替えてカンマで接続します。証明書がない場合は null を返します。 */
    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) } }
            .sorted().joinToString(",")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    enum class Reject {
        /** 呼び出し元はシェル (uid 2000) ではありません。 */
        NotShell,
        /** アシスタントによって報告された uid/pid が、Binder によって認識された呼び出し元と一致しません。 */
        IdMismatch,
        /** アシスタントは、現在インストールされている APK (Mika によって更新) を実行していません。アシスタントを終了して、新しい APK を開始してください。 */
        StaleApk,
        NoBinder,
        BadNonce,
        /** バインダーが配置されているプロセスは、ハンドオーバーのためにここにあるプロセスではありません。 */
        BinderElsewhere,
    }

    /** バインダーに触れずにできるチェック; null を返すことによって。パスワードは[HandoffNonces]によってチェックされます。 */
    fun check(
        callingUid: Int, callingPid: Int, reportedUid: Int, reportedPid: Int,
        helperApk: String?, appApk: String, hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

/**
 * いくつかの永続的なパスワード: [resume] は次のハンドオーバーに使用する実行中のアシスタント用、[launch] は adbd から開始したばかりの新しいアシスタント用、
 * [launchAt] は、[launch] の発行時間 (起動してからのミリ秒数) です。 「コンピュータで始める」のスクリプトには[usb]と書かれており、
 * [usbAt] は、その発行時間 (壁時計のミリ秒数: スクリプトは再起動後も動作します) です。
 */
data class NonceSlots(
    val resume: String? = null, val launch: String? = null, val launchAt: Long = 0,
    val usb: String? = null, val usbAt: Long = 0,
)

interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

/**
 * ミカは有効なワンタイム通信コマンドを生成します。いずれかのコインが使用されると、いくつかのコインが無効になり、それを受け入れたアシスタントに新しい [NonceSlots.resume] が発行されます。
 * は、Mika プロセスが強制終了された後、再度渡されたときにリビング アシスタントがそれを認識できるように保存されます。
 * [clock] は起動パスワード (起動以降) の経過時間を測定し、[wallClock] は「コンピューターで起動」パスワードの経過時間を測定します。
 */
class HandoffNonces(
    private val store: NonceStore,
    private val clock: () -> Long,
    private val wallClock: () -> Long = clock,
    private val random: () -> String = ::randomNonce,
) {
    @Synchronized
    fun issueLaunch(): String = random().also { store.save(store.load().copy(launch = it, launchAt = clock())) }

    /**
     * 「Start with Computer」スクリプトに書き込まれるパスワード。コンピュータがスクリプトを実行している間は交換しないように、まだ新しいうち (有効期限が半分以上残っている状態) は使い続けてください。
     * それ以外の場合は、新しいものと交換してください。他のパスワードと同様、一度使用すると無効になります ([accept] を参照)。
     */
    @Synchronized
    fun usbNonce(): String {
        val s = store.load()
        val age = wallClock() - s.usbAt
        if (s.usb != null && age in 0..USB_TTL_MS / 2) return s.usb
        return random().also { store.save(s.copy(usb = it, usbAt = wallClock())) }
    }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val age = clock() - s.launchAt
        // 起動パスワードはハンドオーバー待機期間中にのみ有効です。未使用のものは有効期限が切れると無効になります。
        val launch = s.launch?.takeIf { age in 0..LAUNCH_TTL_MS }
        // スクリプト内のパスワードは、ユーザーがプラグインしてコマンドを実行するのを待機しています。パスワードは長くする必要があります。有効期限が切れたり、時計が戻ったりすると認識されなくなります。
        val usb = s.usb?.takeIf { wallClock() - s.usbAt in 0..USB_TTL_MS }
        return same(nonce, s.resume) or same(nonce, launch) or same(nonce, usb)
    }

    /** パスワードが正しい場合は、それを使用し、新しいパスワードを返します。そうでない場合は、null を返します。 */
    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    private val _resets = MutableStateFlow(0)
    /** パスワード全体が無効になった回数([clear]): 変化した場合は、書き込まれたパスワード(スクリプト内など)が使用できないことを意味します。 */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    @Synchronized
    fun clear() {
        store.save(NonceSlots())
        _resets.value++
    }

    private fun same(a: String, b: String?) =
        b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        /** は、アプリが開いていて、ストリームがハンドオーバーを待機し、エンジンが別のラウンドのハンドオーバーを待機する合計時間をカバーします。 */
        const val LAUNCH_TTL_MS = 30_000L
        /**
         * 「コンピューターで開始」パスワードのエージング: スクリプトの作成後、ユーザーがコマンドをプラグインして実行する (または再起動後に再度実行する) までに時間がかかる場合があります。
         * ミカは、スクリプトを開くたびにスクリプトを書き直し、アシスタントが表示されなくなったり、ブート ページを開くたびにスクリプトを書き換え、半分使用した後は新しいものに置き換えます。
         */
        const val USB_TTL_MS = 24 * 60 * 60_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
