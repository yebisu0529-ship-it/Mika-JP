package com.wangzan101.mika.device

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.DeviceAccessPort
import com.wangzan101.mika.core.device.DeviceCredential
import com.wangzan101.mika.core.device.DeviceState
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** アプリレベルのみのインスタンス: 設定ページは、モデル、画面ツール、または実行ログを経由せずに、認定された MCP と共有されます。 */
class AndroidDeviceAccess(context: Context, helper: () -> IMikaShell?) {
    val settings = DeviceAccessSettings(File(context.filesDir, "settings"), KeystoreKeyCipher("mika-device-access"))
    val access = DeviceAccess(AndroidDeviceAccessPort(context, helper), settings)
}

internal class AndroidDeviceAccessPort(context: Context, private val helper: () -> IMikaShell?) : DeviceAccessPort {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val user = context.getSystemService(UserManager::class.java)

    override suspend fun state() = withContext(Dispatchers.IO) {
        DeviceState(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked,
            keyguard.isDeviceSecure, helper() != null, user.isUserUnlocked)
    }

    override suspend fun wake(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.wakeDevice() == true }.getOrDefault(false) }
    override suspend fun dismiss(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.dismissDeviceKeyguard() == true }.getOrDefault(false) }
    override suspend fun revealCredential(): Boolean = withContext(Dispatchers.IO) {
        runCatching { helper()?.revealDeviceCredential() == true }.getOrDefault(false)
    }

    /**
     * は、メイン画面の SystemUI でタイプが一致する一意のパスワード ボックスのみを認識します。 SIM PIN/認識されないメーカーページは試行されません。
     * 認証情報ボタンは、Android 13 以降のディレクショナル インジェクションに依存して、SystemUI ウィンドウにのみキャストされる必要があります。以前のシステムでは、スペースを占有する前にサポートされていないことが報告され、一時停止が発生しませんでした。
     * 入力ボックスにフォーカスが設定されていない場合は、まず入力ボックスにフォーカスを設定します。動作しない場合は、もう一度クリックしてください。たとえ動作しなくても、それが配置されている SystemUI ウィンドウがシステムの入力フォーカス ウィンドウになります。
     * も準備完了とみなされます (ボタンは元々そのウィンドウにのみ割り当てられており、ロック画面ページがそれを単独で使用します)。認識できない場合は、画面上のテキストを表示せずに、ログに理由を書き留めてください。
     */
    override suspend fun credentialReady(kind: CredentialKind): Boolean = withContext(Dispatchers.IO) {
        val fields = credentialFields().filter { it.kind == kind }
        val target = fields.singleOrNull() ?: return@withContext false.also {
            Log.i(TAG, "ロック画面: $kind に一致する認証欄は ${fields.size} 件です")
        }
        val (field, window) = target
        val ready = field.isFocused || field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK) || window.isFocused
        if (!ready) Log.i(TAG, "ロック画面: 認証欄にもウィンドウにもフォーカスを確認できません")
        ready
    }

    /** 一意に識別できるシステム認証欄だけを読み取り、フォーカスや入力、コンテナー名からの推測は行いません。 */
    override suspend fun detectedCredentialKind(): CredentialKind? = withContext(Dispatchers.IO) {
        credentialFields().singleOrNull()?.kind
    }

    private data class CredentialField(val node: AccessibilityNodeInfo, val window: AccessibilityWindowInfo, val kind: CredentialKind)

    private fun credentialFields(): List<CredentialField> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return emptyList()
        val service = ScreenAccessibility.instance.value ?: return emptyList<CredentialField>().also { Log.i(TAG, "ロック画面: ユーザー補助が無効です") }
        val fields = mutableListOf<CredentialField>()
        val systemUi = service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            w.root?.takeIf { it.packageName?.toString() == SYSTEM_UI }?.let { w to it }
        }
        for ((window, root) in systemUi) {
            val digits = mutableSetOf<Char>()
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo, depth: Int, sim: Boolean = false) {
                if (depth > 40) return
                val id = n.viewIdResourceName.orEmpty()
                val simChallenge = sim || "sim" in id.lowercase()
                if (n.isVisibleToUser && !simChallenge) {
                    if (n.isPassword) candidates += n
                    else KeyguardFields.digitKey(n.text?.toString(), n.contentDescription?.toString())?.let { digits += it }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1, simChallenge) }
            }
            visit(root, 0)
            val pad = KeyguardFields.isDigitPad(digits)
            for (n in candidates) {
                val cls = n.inputType and InputType.TYPE_MASK_CLASS
                val kind = CredentialKind.entries.singleOrNull { kind ->
                    KeyguardFields.matches(kind, n.viewIdResourceName.orEmpty(), n.className?.toString().orEmpty(), n.isPassword,
                        n.isEditable, numeric = cls == InputType.TYPE_CLASS_NUMBER, untyped = n.inputType == InputType.TYPE_NULL, digitPad = pad)
                } ?: continue
                fields += CredentialField(n, window, kind)
            }
        }
        return fields
    }

    override suspend fun enter(credential: DeviceCredential): Boolean {
        if (!credentialReady(credential.kind)) return false
        return withContext(Dispatchers.IO) {
            val bytes = ByteArray(credential.value.size) { credential.value[it].code.toByte() }
            try { runCatching { helper()?.enterDeviceCredential(credential.kind.ordinal, bytes) == true }.getOrDefault(false) }
            finally { bytes.fill(0) }
        }
    }

    private companion object {
        const val TAG = "MikaDeviceAccess"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
