package com.wangzan101.mika.cap.phone.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.shell.HandoffNonces
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.HelperRestoreReport
import com.wangzan101.mika.core.shell.NonceSlots
import com.wangzan101.mika.core.shell.NonceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.ParcelFileDescriptor
import java.io.IOException
import com.wangzan101.mika.shell.IMikaShell

/**
 * Mega プロセスの組み込みアシスタント Binder の場所。アシスタントは [HandoffProvider] 経由でバインダーを送信し、ここをチェックした後にそれを受け入れます。
 * は [com.wangzan101.mika.cap.phone.PhoneServices] から作成されます (アシスタントは 1 つのプロセスで 1 つのドロップ ポイントのみを認識します)。
 * [BuiltinShellConnection] とウォッチドッグは両方とも [shell] を参照します。
 */
class HelperHub(context: Context) {
    private val app = context.applicationContext

    private val _shell = MutableStateFlow<IMikaShell?>(null)
    /** アシスタント インターフェイスが手元にあります。アシスタントプロセスが終了すると null に戻ります。 */
    val shell: StateFlow<IMikaShell?> = _shell.asStateFlow()

    private val _restores = MutableStateFlow<HelperRestoreReport?>(null)
    /** アシスタント引き継ぎ時間からの「ミカが殺された後に引き上げられた」記録（診断情報用）。プルされない場合は null です。 */
    val restores: StateFlow<HelperRestoreReport?> = _restores.asStateFlow()

    /** 最近一次交接拒绝的固定枚举;不含口令、Binder、包路径或调用方数据。 */
    @Volatile var lastHandoffRejection: String? = null
        private set

    /** は、アシスタントを通じてこのプロセスの WebView デバッグ ソケットに接続します。アシスタントが利用できない場合は IOException がスローされ、アシスタントが拒否するか接続できない場合は null がスローされます。 */
    fun openDevtoolsSocket(endpoint: String): ParcelFileDescriptor? {
        val sh = shell.value ?: throw IOException("phone helper is not running")
        return sh.openDevtoolsSocket(endpoint)
    }

    /** アシスタントに与えられたプロセス トークン: ミカ プロセスが終了するとすぐに、アシスタントはその終了通知からそれを知り、ミカが残した変更を削除します。 */
    private val token = Binder()
    private val nonces: HandoffNonces by lazy { HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime, System::currentTimeMillis) }

    /** adbd を介した新しいアシスタント プレフィックス: このワンタイム パスワードは、実行ストリームの標準入力を介してアシスタントに与えられます。 */
    fun issueLaunchNonce(): String = nonces.issueLaunch()

    /** は、「Start with Computer」スクリプトに書き込まれるワンタイム パスワードです ([HandoffNonces.usbNonce] を参照)。同じ [offer] がハンドオーバーに使用されます。 */
    fun usbNonce(): String = nonces.usbNonce()

    /** パスワードが全体として無効になった回数 ([HandoffNonces.resets] を参照): 「コンピューターで開始」スクリプトがそれに応じて書き換えられます。 */
    val nonceResets: StateFlow<Int> get() = nonces.resets

    /** ペアリング解除: すべてのパスワードが無効になり (まだ生きているアシスタントは拒否され、次回終了します)、手元のインターフェイスも解放されます。 */
    fun forget() {
        nonces.clear()
        _shell.value = null
    }

    /**
     * アシスタントを引き継ぎます。シェル (uid 2000) のみを受け入れ、自己報告された uid/pid が呼び出し元と一致し、現在の APK が実行され、パスワードが正しい。
     * バインダーを再度調整して、実際に引き継ぎ中であることを確認し、最後にパスワードを使用して次のパスワードに変更します。
     */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        HelperHandoff.check(
            callingUid, callingPid,
            reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(HelperHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(it) }
        val gate = nonces
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        // パスワードが間違っている場合は、相手から渡されたバインダーに触れないでください。
        if (!gate.matches(nonce)) return reject(HelperHandoff.Reject.BadNonce)
        val shell = IMikaShell.Stub.asInterface(binder!!)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject(HelperHandoff.Reject.BinderElsewhere)
        }
        val next = gate.accept(nonce) ?: return reject(HelperHandoff.Reject.BadNonce)
        try {
            binder.linkToDeath({ _shell.compareAndSet(shell, null) }, 0)
        } catch (e: Exception) {
            return reject(HelperHandoff.Reject.NoBinder)
        }
        // は新しいアシスタントに変更されました。古いアシスタントは新しいアシスタントによって終了され、現在のアシスタントではないため、その終了通知は無視されます。
        _shell.value = shell
        if (extras?.containsKey(HelperHandoff.KEY_RESTORED_AT) == true) {
            _restores.value = HelperRestoreReport(
                extras.getLong(HelperHandoff.KEY_RESTORED_AT), extras.getInt(HelperHandoff.KEY_RESTORES), extras.getLong(HelperHandoff.KEY_GAVE_UP_AT),
            )
        }
        Log.i(TAG, "helper pid $callingPid handed off")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    private fun reject(why: HelperHandoff.Reject): Bundle {
        lastHandoffRejection = why.name
        Log.w(TAG, "helper handoff rejected: $why")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, false)
            putString(HelperHandoff.KEY_REASON, why.name)
        }
    }

    private companion object {
        const val TAG = "MikaHelperHub"
        const val PREFS = "builtin_helper"
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun load() = NonceSlots(
            prefs.getString("resume", null), prefs.getString("launch", null), prefs.getLong("launchAt", 0),
            prefs.getString("usb", null), prefs.getLong("usbAt", 0),
        )
        // 同期書き込み: アシスタントが新しいパスワードを取得した直後にミカが強制終了される可能性があり、新しいプロセスはそれを認識する必要があります
        override fun save(slots: NonceSlots) {
            prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch).putLong("launchAt", slots.launchAt)
                .putString("usb", slots.usb).putLong("usbAt", slots.usbAt).commit()
        }
    }
}

/**
 * 内蔵アシスタント引き渡しバインダー入口。シェルに対してオープンする必要があるため、エクスポートされます。 INTERACT_ACROSS_USERS_FULL もリストに含める必要があります。
 * (シェルにはありますが、通常のアプリにはありません)、通常のアプリはプロバイダーを取得することさえできません。実際の小切手は [HelperHub.offer] です。
 * はアプリ ([PhoneServices.of]) の一部です。アプリ オブジェクトはプロバイダーの前に構築されますが、ハンドオーバー呼び出しは Application.onCreate よりも前に、または Application.onCreate と同時に到着する可能性があります。
 * は、MikaApp.phone と [HelperHub] のパスワードが同期されており、遅延しているため安全です (最初に来た人がビルドし、一度だけビルドされます)。 [HelperHub.offer] 自体もロックされており、最初に実行する onCreate に依存しません。
 */
open class HandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        if (com.wangzan101.mika.quit.MikaQuit.requested(ctx)) return Bundle()
        return PhoneServices.of(ctx).hub.offer(extras, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
