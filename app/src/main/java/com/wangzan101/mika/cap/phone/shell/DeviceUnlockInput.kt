package com.wangzan101.mika.cap.phone.shell

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceCredential

/**
 * は、認定を受けたミカ バインダーによってのみ呼び出されます。ホーム画面のみ。資格情報がコマンド ライン、クリップボード、スクリーンショット、またはログに入力されることはありません。
 * 証明書の各キーストロークは [injectTo] を介して渡され、SystemUI が所有するフォーカス ウィンドウにのみキャストされます。ロック画面の上には他のウィンドウがあります (画面がロックされているときに表示されるアプリ、着信、
 * 全画面通知）またはロックが解除されてアプリに戻された場合、システムは挿入を拒否し、キーが他の場所に落ちることはありません。 [inject] は、シークレットのない明るい画面キーにのみ使用されます。
 */
internal class DeviceUnlockInput(private val context: Context, private val inject: (InputEvent) -> Boolean,
    private val injectTo: (InputEvent, Int) -> Boolean, private val command: (List<String>) -> Boolean) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    /** ロック画面インターフェイスは SystemUI に属します。 uid が見つからない場合、認証情報は入力されません。 */
    private val systemUiUid: Int? by lazy { runCatching { context.packageManager.getPackageUid(SYSTEM_UI, 0) }.getOrNull() }

    fun wake(): Boolean = key(KeyEvent.KEYCODE_WAKEUP, send = inject) || command(listOf("input", "keyevent", "224"))

    fun dismiss(): Boolean = command(listOf("wm", "dismiss-keyguard"))

    /** 安全锁屏仍停在通知/指纹页时做一次通用的上滑。事件定向给 SystemUI,解锁中途完成也不会落进前台 App。 */
    fun reveal(): Boolean {
        val target = systemUiUid ?: return false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
        val bounds = runCatching { context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds }.getOrNull() ?: return false
        val x = bounds.exactCenterX()
        val startY = bounds.bottom - bounds.height() * 0.15f
        val endY = bounds.top + bounds.height() * 0.45f
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, y: Float): Boolean {
            val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                injectTo(event, target)
            } finally {
                event.recycle()
            }
        }
        if (!send(MotionEvent.ACTION_DOWN, downAt, startY)) return false
        var completed = false
        try {
            repeat(6) { step ->
                if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
                SystemClock.sleep(24)
                val progress = (step + 1) / 6f
                if (!send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), startY + (endY - startY) * progress)) return false
            }
            if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            completed = send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endY)
            return completed
        } finally {
            if (!completed) send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), endY)
        }
    }

    fun enter(kind: Int, bytes: ByteArray): Boolean {
        var chars = CharArray(0)
        var sent = false
        val target = systemUiUid
        val deadline = SystemClock.uptimeMillis() + 8_000
        fun toKeyguard(event: InputEvent): Boolean { sent = true; return injectTo(event, target!!) }
        try {
            if (target == null || !power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            val type = CredentialKind.entries.getOrNull(kind) ?: return false
            if (bytes.any { it.toInt() !in 32..126 }) return false
            chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
            if (!DeviceCredential.valid(type, chars)) return false
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = if (type == CredentialKind.Password) map.getEvents(chars) ?: return false else null
            // SystemUI の資格情報入力ボックスが発信者によって確認されました。残りの半分のエントリをすべてクリアし、完全な認証情報を 1 回だけ試してください。
            if (type == CredentialKind.Pin) {
                repeat(16) { if (!key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false }
            } else if (!key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, ::toKeyguard) ||
                !key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false
            if (type == CredentialKind.Pin) {
                for (c in chars) {
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive) return false
                    if (!key(KeyEvent.KEYCODE_0 + (c - '0'), send = ::toKeyguard)) return false
                }
            } else {
                for (event in events!!) {
                    event.source = InputDevice.SOURCE_KEYBOARD
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive || !toKeyguard(event)) return false
                }
            }
            // 一部のシステムは、最後の桁で自動的にコミットします。 Enter も SystemUI にのみスローされます。この期間中はロックが解除され、アプリに返されます。システムによって拒否され、アプリには送信されません。
            SystemClock.sleep(200)
            return !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || key(KeyEvent.KEYCODE_ENTER, send = ::toKeyguard)
        } finally {
            bytes.fill(0); chars.fill('\u0000')
            // パスワードの記号では修飾キーを使用することができます。キーが押された後にのみ解放されます。また、これらは SystemUI にのみ送信され、フォアグラウンド アプリにはイベントを送信しません。
            if (sent && target != null) for (code in MODIFIERS) {
                val now = SystemClock.uptimeMillis()
                injectTo(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD), target)
            }
        }
    }

    private fun key(code: Int, meta: Int = 0, send: (InputEvent) -> Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD)
        return send(down) && send(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        val MODIFIERS = intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)
    }
}
