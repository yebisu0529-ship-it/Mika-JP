package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.shell.HelperHandoff.Reject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HelperHandoffTest {
    private class MemStore : NonceStore {
        var slots = NonceSlots()
        override fun load() = slots
        override fun save(slots: NonceSlots) { this.slots = slots }
    }

    private var now = 1_000L
    private var wall = 1_700_000_000_000L

    private fun nonces(store: NonceStore = MemStore()): HandoffNonces {
        var n = 0
        return HandoffNonces(store, clock = { now }, wallClock = { wall }) { "n${++n}" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun usbScriptIsRewrittenWithAFreshNonceWhenNoncesAreClearedWhileNoHelperIsConnected() = runTest {
        var n = 0
        val gate = HandoffNonces(MemStore(), clock = { now }, wallClock = { wall }) { "f00d%04d".format(++n) }
        val helper = MutableStateFlow<Any?>(null)
        val written = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            HelperHandoff.usbScriptWrites(helper, gate.resets).collect { connected ->
                written += HelperHandoff.usbFile(connected, "/a.apk", "p", 1, { "pin" }, gate::usbNonce, "/s.sh")
            }
        }
        assertEquals(1, written.size)
        val stale = gate.usbNonce()
        assertTrue(written[0].contains(stale))
        // ペアリング解除: アシスタントが不在のため、再書き込みする必要があります。新しいパスワードは新しいスクリプト内にあり、古いパスワードは認識されなくなります。 使用後、
        gate.clear()
        assertEquals(2, written.size)
        assertFalse(written[1].contains(stale))
        assertFalse(gate.matches(stale))
        assertTrue(gate.matches(gate.usbNonce()))
        assertTrue(written[1].contains(gate.usbNonce()))
    }

    @Test
    fun onlyShellWithMatchingIdsAndCurrentApkPasses() {
        fun check(uid: Int = 2000, pid: Int = 77, rUid: Int = uid, rPid: Int = pid, apk: String? = "/a.apk", binder: Boolean = true) =
            HelperHandoff.check(uid, pid, rUid, rPid, apk, "/a.apk", binder)
        assertNull(check())
        assertEquals(Reject.NotShell, check(uid = 10123))
        assertEquals(Reject.NotShell, check(uid = 0))
        assertEquals(Reject.IdMismatch, check(rPid = 78))
        assertEquals(Reject.IdMismatch, check(rUid = 0))
        assertEquals(Reject.StaleApk, check(apk = "/old.apk"))
        assertEquals(Reject.StaleApk, check(apk = null))
        assertEquals(Reject.NoBinder, check(binder = false))
    }

    @Test
    fun launchNonceIsSingleUseAndRotates() {
        val g = nonces()
        val launch = g.issueLaunch()
        assertFalse(g.matches("nope"))
        assertFalse(g.matches(null))
        assertTrue(g.matches(launch))
        val next = g.accept(launch)
        assertNotNull(next)
        assertNotEquals(launch, next)
        // は認識されなくなります。
        assertNull(g.accept(launch))
        // 変更したパスワードは 1 回のみ使用できます。
        val after = g.accept(next)
        assertNotNull(after)
        assertNull(g.accept(next))
    }

    @Test
    fun acceptingEitherSlotVoidsTheOther() {
        val g = nonces()
        val resume = g.accept(g.issueLaunch())!!
        val launch = g.issueLaunch()
        // 古いアシスタントが最初に引き継ぎます。新しいパスワードは無効です。拒否され、自動的に終了します。
        assertNotNull(g.accept(resume))
        assertNull(g.accept(launch))

        val resume2 = g.accept(g.issueLaunch())!!
        val launch2 = g.issueLaunch()
        assertNotNull(g.accept(launch2))
        assertNull(g.accept(resume2))
    }

    @Test
    fun resumeNonceSurvivesProcessRestart() {
        val store = MemStore()
        val resume = nonces(store).accept(nonces(store).issueLaunch())
        // 新しいプロセス: 新しいオブジェクトは同じストレージを読み取ります
        assertNotNull(nonces(store).accept(resume))
    }

    @Test
    fun clearVoidsEverything() {
        val g = nonces()
        val launch = g.issueLaunch()
        g.clear()
        assertNull(g.accept(launch))
    }

    @Test
    fun randomNoncesAreLongAndDistinct() {
        val a = HandoffNonces.randomNonce()
        assertEquals(64, a.length)
        assertNotEquals(a, HandoffNonces.randomNonce())
    }

    @Test
    fun unusedLaunchNonceExpires() {
        val g = nonces()
        val launch = g.issueLaunch()
        now += HandoffNonces.LAUNCH_TTL_MS
        assertTrue(g.matches(launch))
        now += 1
        assertFalse(g.matches(launch))
        assertNull(g.accept(launch))
    }

    @Test
    fun launchNonceFromTheFutureIsNotTrusted() {
        // レコードの発行時刻が現在より遅い (たとえば、再起動後に時計が 0 から始まる): 期限切れとして扱われます。
        val store = MemStore()
        val launch = nonces(store).issueLaunch()
        now -= 1
        assertFalse(nonces(store).matches(launch))
    }

    @Test
    fun resumeNonceDoesNotExpire() {
        val g = nonces()
        val resume = g.accept(g.issueLaunch())
        now += 10 * HandoffNonces.LAUNCH_TTL_MS
        assertNotNull(g.accept(resume))
    }

    @Test
    fun commandRunsHelperDetachedAndFeedsNonceThroughStdin() {
        val cmd = HelperHandoff.command("/data/app/~~x/com.wangzan101.mika-y/base.apk", "com.wangzan101.mika", 10234, "ab'cd")
        assertEquals(
            "read n; (echo \"\$n\" | CLASSPATH='/data/app/~~x/com.wangzan101.mika-y/base.apk' setsid app_process /system/bin " +
                "--nice-name='com.wangzan101.mika:helper' com.wangzan101.mika.shell.HelperMain " +
                "'com.wangzan101.mika' 10234 'ab'\\''cd' >/dev/null 2>&1 &); read _",
            cmd,
        )
        assertEquals("com.wangzan101.mika.helper", HelperHandoff.authority("com.wangzan101.mika"))
    }

    @Test
    fun nonceGoesOnItsOwnLine() {
        assertEquals("abc\n", String(HelperHandoff.stdinLine("abc"), Charsets.UTF_8))
    }

    @Test
    fun certPinIsSha256HexAndIgnoresSignerOrder() {
        val a = "abc".toByteArray()
        val b = "xyz".toByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HelperHandoff.certPin(listOf(a)))
        assertEquals(HelperHandoff.certPin(listOf(a, b)), HelperHandoff.certPin(listOf(b, a)))
        assertNotEquals(HelperHandoff.certPin(listOf(a)), HelperHandoff.certPin(listOf(a, b)))
        assertNull(HelperHandoff.certPin(emptyList()))
        assertNull(HelperHandoff.certPin(null))
    }

    // ---------- USB 経由でコンピュータから起動 ----------

    private val apk = "/data/app/~~x/com.wangzan101.mika-y/base.apk"
    private val scriptPath = "/storage/emulated/0/Android/data/com.wangzan101.mika/files/usb-start.sh"

    @Test
    fun usbCommandUsesTheShortSdcardPathForTheMainUser() {
        assertEquals("adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh", HelperHandoff.usbCommand(scriptPath))
        // 他のユーザーのストレージは実際のパスに従って書き込まれます。
        assertEquals(
            "adb shell sh /storage/emulated/10/Android/data/com.wangzan101.mika/files/usb-start.sh",
            HelperHandoff.usbCommand("/storage/emulated/10/Android/data/com.wangzan101.mika/files/usb-start.sh"),
        )
    }

    @Test
    fun usbScriptStartsTheSameHelperDetachedWithTheNonceOnStdin() {
        val script = HelperHandoff.usbScript(apk, "com.wangzan101.mika", 10234, "ab'cd", "f00d", scriptPath)
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        // には、ワイヤレス デバッグと同じ入り口とパラメータがあります。フィンガープリント内の単一引用符は適切にエスケープされます。ハングアップ信号は無視され、新しいセッションが開始されてバックグラウンドに配置され、出力が失われます。
        assertTrue(
            script,
            script.contains(
                "(echo \"\$n\" | CLASSPATH='$apk' nohup setsid app_process /system/bin --nice-name='com.wangzan101.mika:helper' " +
                    "com.wangzan101.mika.shell.HelperMain 'com.wangzan101.mika' 10234 'ab'\\''cd' >/dev/null 2>&1 &)",
            ),
        )
        // パスワードは変数内にのみあります。コマンド ライン パラメーターを入力せずに、組み込みのエコーを通じてパイプ処理されます。
        assertTrue(script.contains("\nn='f00d'\n"))
        assertEquals(1, script.split("f00d").size - 1)
        // はシェル ID でのみ機能します。 APK が存在しない (更新された) 場合は、まずミカを開いてください
        assertTrue(script.contains("if [ \"\$(id -u)\" != \"2000\" ]; then"))
        assertTrue(script.contains("if [ ! -f '$apk' ]; then"))
        // setsid が有効になるのを待ってから戻り、結果とその確認方法を説明します スクリプトを書き直すと、
        assertTrue(script.contains("\nsleep 2\n"))
        assertTrue(script.contains("if pidof 'com.wangzan101.mika:helper' >/dev/null 2>&1; then"))
        assertTrue(script.contains("Mikaの操作アシスタントを起動しています。Mikaを開き"))
        assertTrue(script.contains("adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh"))
        assertTrue(script.endsWith("fi\n"))
    }

    @Test
    fun usbScriptDoesNotPromiseThatTheHelperSurvivesUnplugging() {
        val script = HelperHandoff.usbScript(apk, "com.wangzan101.mika", 10234, "pin", "f00d", scriptPath)
        assertTrue(script.contains("多くの端末ではケーブルを抜いても動作を続けます"))
        assertTrue(script.contains("再起動後や抜線後に操作できなくなった場合"))
        assertTrue(script.contains("ケーブルをつないでこのコマンドを再実行"))
        assertFalse(script.contains("ケーブルを抜いても必ず"))
        // 新提示不换启动机制,口令仍只出现一次
        assertTrue(script.contains("nohup setsid app_process"))
        assertEquals(1, script.split("f00d").size - 1)
    }

    @Test
    fun usbScriptQuotesEverythingItInterpolates() {
        val script = HelperHandoff.usbScript("/a b/it's.apk", "p", 1, "pin", "n'; rm -rf /; '", scriptPath)
        assertTrue(script.contains("CLASSPATH='/a b/it'\\''s.apk'"))
        assertTrue(script.contains("n='n'\\''; rm -rf /; '\\'''"))
        assertFalse(script.contains("n='n'; rm"))
    }

    @Test
    fun connectedGetsAStubWithoutANonce() {
        var asked = 0
        val stub = HelperHandoff.usbFile(true, apk, "com.wangzan101.mika", 10234, { asked++; "pin" }, { asked++; "secret" }, scriptPath)
        assertEquals(0, asked)
        assertFalse(stub.contains("secret"))
        assertFalse(stub.contains("app_process"))
        assertTrue(stub.contains("起動済み"))
        assertTrue(stub.contains("pidof 'com.wangzan101.mika:helper'"))
        val live = HelperHandoff.usbFile(false, apk, "com.wangzan101.mika", 10234, { "pin" }, { "secret" }, scriptPath)
        assertTrue(live.contains("n='secret'"))
        assertTrue(live.contains("app_process"))
    }

    @Test
    fun usbNonceIsReusedWhileFreshAndHandsOffOnce() {
        val g = nonces()
        val usb = g.usbNonce()
        // はまだ新しいです。引き続き使用してください。現在コンピューターで実行されているスクリプトは無効になりません。
        wall += HandoffNonces.USB_TTL_MS / 2
        assertEquals(usb, g.usbNonce())
        assertTrue(g.matches(usb))
        assertNotNull(g.accept(usb))
        // は使用後は無効になります。次のスクリプトは新しいパスワードです
        assertNull(g.accept(usb))
        assertNotEquals(usb, g.usbNonce())
    }

    @Test
    fun usbNonceRotatesWhenHalfUsedAndExpires() {
        val g = nonces()
        val usb = g.usbNonce()
        wall += HandoffNonces.USB_TTL_MS / 2 + 1
        val fresh = g.usbNonce()
        assertNotEquals(usb, fresh)
        assertFalse(g.matches(usb))
        wall += HandoffNonces.USB_TTL_MS
        assertTrue(g.matches(fresh))
        wall += 1
        assertFalse(g.matches(fresh))
        assertNull(g.accept(fresh))
    }

    @Test
    fun usbNonceFromTheFutureIsNotTrusted() {
        val g = nonces()
        val usb = g.usbNonce()
        wall -= 1
        assertFalse(g.matches(usb))
    }

    @Test
    fun usbNonceSurvivesARebootButNotAnotherHandoff() {
        val store = MemStore()
        val usb = nonces(store).usbNonce()
        // 再起動後、電源オン タイマーはゼロに戻り、壁の時計はまだ動き続けています。スクリプトは引き続き使用できます。
        now = 0
        val g = nonces(store)
        assertTrue(g.matches(usb))
        // ワイヤレス デバッグ側からのアシスタントが最初に受け入れられました: スクリプト内のパスワードが無効です
        assertNotNull(g.accept(g.issueLaunch()))
        assertFalse(g.matches(usb))
        assertNull(store.slots.usb)
    }

    @Test
    fun clearVoidsTheUsbNonce() {
        val g = nonces()
        val usb = g.usbNonce()
        g.clear()
        assertFalse(g.matches(usb))
    }
}
