package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.adb.AdbPairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinShellEngineTest {
    private val helper = MutableStateFlow<Any?>(null)

    private inner class FakePlatform : BuiltinShellPlatform {
        var pairedFlag = false
        var wireless: Boolean? = true
        var wifi = true
        var pairingPort: Int? = 37000
        var pairError: AdbPairingException.Reason? = null
        /** 毎回アシスタントを開始した結果: null = 成功とハンドオーバー、残りは対応する例外をスローします。使用後は常に成功します。 */
        val launchResults = ArrayDeque<AdbConnectionException.Reason?>()
        var deliverOnLaunch = true
        var keyUnreadable = false
        val calls = mutableListOf<String>()

        override fun paired() = pairedFlag
        override fun setPaired(paired: Boolean) { pairedFlag = paired; calls += "paired=$paired" }
        override fun wirelessDebugging() = wireless
        override fun wifiConnected() = wifi
        /** ペアリング ウィンドウが開いておらず ([pairingPort] が null)、見つからない前に検索時間制限を超えています。 */
        override suspend fun findPairingPort(timeoutMs: Long) = pairingPort ?: run { delay(timeoutMs); null }
        override fun pair(port: Int, code: String) {
            calls += "pair $port $code"
            pairError?.let { throw AdbPairingException(it, "x") }
        }
        override suspend fun launchHelper() {
            calls += "launch"
            if (keyUnreadable) throw AdbKeyUnreadableException("cannot unwrap adbkey.bin")
            launchResults.removeFirstOrNull()?.let { throw AdbConnectionException(it, "x") }
            if (deliverOnLaunch) helper.value = "binder"
        }
        override fun stopHelper() { calls += "stop"; helper.value = null }
        override fun forget() { calls += "forget" }
        var usb = false
        override fun usbStarted() = usb
        override fun setUsbStarted(on: Boolean) { usb = on }

        /** root起動のスイッチ、suの有無、保存済み応答。[suAnswers] が空になれば以後は承認済みとして返す。 */
        var rootOn = false
        var suPresent = true
        var answered: SuAnswer? = null
        val suAnswers = ArrayDeque<SuAnswer>()
        override fun rootStart() = RootStart.of(rootOn, suPresent, answered)
        override suspend fun launchHelperAsRoot(): SuAnswer? {
            calls += "su"
            if (!suPresent) return null
            val a = suAnswers.removeFirstOrNull() ?: SuAnswer.Granted
            if (a == SuAnswer.Granted && deliverOnLaunch) helper.value = "root"
            return a
        }
        override fun setRootAnswer(answer: SuAnswer?) { answered = answer }
    }

    private val platform = FakePlatform()

    private fun TestScope.engine(
        backoff: Backoff = Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3),
        autoOn: WirelessDebuggingAutoOn? = null,
    ) =
        BuiltinShellEngine(
            // backgroundScope:advanceUntilIdle がない場合、ウォッチドッグの再試行はバックグラウンド タスクを待たずにスキップされます。
            platform, helper, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler), backoff,
            graceMs = 3_000, handoffTimeoutMs = 10_000, autoOn = autoOn,
        )

    /** は、ユーザーのワイヤレス デバッグを有効にします。書き込みは偽のプラットフォーム スイッチです。 [revert] が true の場合、Wi-Fi が許可されていない場合と同様に、書き込み直後にシステムによってダイヤルバックされます。 */
    private var wants = true
    private var revert = false
    private var switchWrites = 0

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { wants }, canWrite = { true },
        read = { platform.wireless.also { if (revert) platform.wireless = false } },
        write = { switchWrites++; platform.wireless = true; true },
        now = { testScheduler.currentTime },
    )

    private fun launches() = platform.calls.count { it == "launch" }
    private fun suCalls() = platform.calls.count { it == "su" }

    @Test
    fun backoffDoublesAndStops() {
        val b = Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 4)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, null), (1..4).map { b.delayAfter(it) })
        assertEquals(5_000L, Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 10).delayAfter(6))
    }

    @Test
    fun failureReasonsMapToWhatTheUserShouldDo() {
        assertEquals(BuiltinShellFailure.WrongCode, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.WRONG_CODE))
        assertEquals(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.Unknown, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.NotLocal, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.NOT_LOCAL))
        assertEquals(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.UNAUTHORIZED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.NotLocal, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.NOT_LOCAL))
        assertEquals(false, BuiltinShellFailures.retryable(BuiltinShellFailure.NotLocal))
    }

    @Test
    fun notPairedNeverLaunches() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(0, launches())
    }

    @Test
    fun pairingThenLaunchEndsRunning() = runTest {
        val e = engine()
        e.startPairing("123456", null)
        assertEquals(listOf("pair 37000 123456", "paired=true", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun pairingFailuresAreReported() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), e.status.value)
        assertEquals(false, platform.pairedFlag)
        platform.pairingPort = null
        e.startPairing("123456", null)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), e.status.value)
    }

    /** 診断情報に使用される最新のエラー: 例外カテゴリ、理由、および説明。接続後にクリアされます。 */
    @Test
    fun lastErrorRecordedAndClearedOnSuccess() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals("AdbPairingException(WRONG_CODE): x", e.lastError)
        platform.pairError = null
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        e.startPairing("123456", 40000)
        assertEquals("AdbConnectionException(UNAUTHORIZED): x", e.lastError)
        e.retry()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(null, e.lastError)
    }

    @Test
    fun startupWaitsForRedeliveryBeforeLaunching() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        // まだ生きているアシスタントは猶予期間内に引き渡されました: もう adbd を経由する必要はありません
        helper.value = "old"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun startupLaunchesAfterGraceWhenNobodyDelivers() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceTimeBy(2_999)
        assertEquals(0, launches())
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun deathIsFollowedByRestartWithBackoff() = runTest {
        platform.pairedFlag = true
        helper.value = "first"
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        platform.launchResults += listOf(AdbConnectionException.Reason.CONNECT_FAILED, AdbConnectionException.Reason.CONNECT_FAILED)
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(1_001)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        // 最初の失敗後 1 秒待機、2 回目の失敗後 2 秒待機
        advanceTimeBy(1_001)
        assertEquals(2, launches())
        advanceTimeBy(1_000)
        assertEquals(2, launches())
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun retriesStopAfterMaxAttemptsUntilAskedAgain() = runTest {
        platform.pairedFlag = true
        platform.launchResults += List(10) { AdbConnectionException.Reason.CONNECT_FAILED }
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        e.retry()
        assertEquals(4, launches())
    }

    @Test
    fun unreadableKeyAsksToPairAgainAndIsNotRetried() = runTest {
        platform.pairedFlag = true
        platform.keyUnreadable = true
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.KeyUnreadable, BuiltinShellFix.RePair), e.status.value)
        assertTrue(e.lastError!!.startsWith("AdbKeyUnreadableException"))
    }

    @Test
    fun notLocalIsNotRetriedAsANetworkFailure() = runTest {
        platform.pairedFlag = true
        platform.wifi = false // 也不能被二次归成 NoWifi
        platform.launchResults += AdbConnectionException.Reason.NOT_LOCAL
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NotLocal), e.status.value)
    }

    @Test
    fun revokedKeyAndMissingWifiAreNotRetriedAutomatically() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)

        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        e.retry()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)
    }

    @Test
    fun wifiComingBackRetriesAfterNoWifi() = runTest {
        platform.pairedFlag = true
        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wifiComingBackIgnoredUnlessWaitingForWifi() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)
    }

    @Test
    fun wirelessDebuggingOffWaitsForTheSwitch() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
        assertEquals(0, launches())
        platform.wireless = true
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wirelessDebuggingOffIsTurnedOnWhenTheHelperIsNeeded() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun revertedOnThisWifiStopsAndSaysSoUntilWifiChanges() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)
        assertEquals(1, switchWrites)
        assertEquals(0, launches())

        // アシスタントを使用したい場合、スイッチが再び変わります。書き込みが行われなくなります (書き込みを行うたびにシステムがボックスをポップアップ表示します)。
        e.need()
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)

        // が許可された Wi-Fi に変更されました: 再度書き込み、今度はオープンします
        revert = false
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun userRetryWritesAgainAfterARevert() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.retry()
        assertEquals(1, switchWrites)
        // ユーザーがシステム ポップアップ ボックスで許可したため、[再試行] をクリックします。
        revert = false
        e.retry()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun switchOffInMikaNeverWrites() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        wants = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        e.wifiAvailable()
        e.need()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
    }

    @Test
    fun withoutWifiWaitsAndTurnsOnWhenWifiComesBack() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        platform.wifi = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun launchedButNeverHandedOffIsHelperStartFailed() = runTest {
        platform.pairedFlag = true
        platform.deliverOnLaunch = false
        val e = engine(Backoff(maxAttempts = 1))
        e.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), e.status.value)
    }

    @Test
    fun needLaunchesOnceEvenIfCalledRepeatedly() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.need()
        e.need()
        advanceUntilIdle()
        assertEquals(1, launches())
    }

    @Test
    fun unpairStopsHelperAndForgets() = runTest {
        platform.pairedFlag = true
        helper.value = "h"
        val e = engine()
        e.start()
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertTrue(platform.calls.containsAll(listOf("stop", "forget", "paired=false")))
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(0, launches())
    }

    /** コンピューターからの USB ベースのアシスタントは、ペアリングされていない場合でも受け入れられます。ステータスは実行中であり、「コンピューターからのものである」ことに注意してください。携帯電話の操作はペアリングされているものと同じです。 */
    @Test
    fun usbStartedHelperRunsWithoutPairing() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        helper.value = "usb"
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertTrue(platform.usb)
        assertEquals(false, e.paired)
        assertEquals(0, launches())
    }

    /** がペアリングされていない場合、コンピューター上で起動されたアシスタントは停止します。アシスタントは以前にセットアップされたとは見なされず、停止したとみなされます。ミカが勝手に起きない（起き上がれない）。 */
    @Test
    fun usbHelperStoppingSaysSoAndDoesNotLaunch() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        e.need()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        assertEquals(0, launches())
        // ワイヤレス デバッグのペアリングではなく、コマンドを再度実行します。
        assertEquals(BuiltinShellFix.RunUsbCommand, (e.status.value as BuiltinShellStatus.Failed).fix)
        assertEquals(HelperRestart.UsbCommand, e.restart)
    }

    /** は使用中に切断されました (たとえば、アシスタントが引き継ぐために新しいものに変更していました)。最初は接続中であることが示されましたが、しばらくして戻ってこないため停止したと表示されました。 */
    @Test
    fun usbHelperDropMidRunGetsAGraceBeforeStopped() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        // 引き継ぎが完了しました: 新しいアシスタントが引き継ぎに来ますが、「停止」が点滅しません。
        helper.value = "usb2"
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    /** アシスタントが停止した後に再び起動するために依存するもの: 設定なし、USB コマンドでのみ開始、ワイヤレス デバッグとペアリング (前回コマンドで開始された場合でも優先順位とペアリング)。 */
    @Test
    fun restartRouteFollowsHowTheHelperCameUp() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(HelperRestart.None, e.restart)
        helper.value = "usb"
        runCurrent()
        assertEquals(HelperRestart.UsbCommand, e.restart)
        platform.pairedFlag = true
        assertEquals(HelperRestart.Wireless, e.restart)
    }

    /** 再起動（新しいプロセス）後、アシスタントが引き継ぎに来ませんでした。しばらく待ってから（まだ生きているものは自動的にやって来ます）、その後、停止したと表示されます。 */
    @Test
    fun afterRestartUsbHelperGetsAGraceThenStopped() = runTest {
        platform.usb = true
        val e = engine()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        e.start()
        advanceTimeBy(2_000)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun usbHelperComingBackWithinGraceIsRunning() = runTest {
        platform.usb = true
        val e = engine()
        e.start()
        advanceTimeBy(1_000)
        helper.value = "usb"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** がペアリングされた後も、コマンドの実行を求めるプロンプトが表示されることなく、ワイヤレス デバッグを通じてウォッチドッグによって再起動されます。 */
    @Test
    fun pairedUsbHelperIsRestartedWireless() = runTest {
        platform.usb = true
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, launches())
    }

    /** ペアリングを解除して「コンピューターを起動した」ことを忘れる: 「停止」ではなく、その後の設定はありません。 */
    @Test
    fun unpairForgetsUsbStart() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertEquals(false, platform.usb)
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
    }

    @Test
    fun discoveryReportsFoundPort() = runTest {
        val e = engine()
        e.startDiscovery()
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceUntilIdle()
        assertEquals(PairingDiscovery.Found(37000), e.pairingDiscovery.value)
        e.stopDiscovery()
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }

    /** 再度検索を開始すると（ペアリングに戻ると）、その時点から検索時間が再計算され、最初の開始では作業は終了しません。 */
    @Test
    fun startingDiscoveryAgainRestartsTheSearchTime() = runTest {
        val e = engine()
        platform.pairingPort = null
        e.startDiscovery()
        advanceTimeBy(9 * 60_000L)
        e.startDiscovery()
        advanceTimeBy(9 * 60_000L)
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceTimeBy(2 * 60_000L)
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }

    /** root起動が明示許可されていれば、ペアリングやワイヤレスデバッグを使わず起動する。PC起動には数えない。 */
    @Test
    fun rootStartLaunchesWithoutPairing() = runTest {
        platform.rootOn = true
        platform.wireless = false
        val e = engine()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        assertTrue(e.rootStart)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, suCalls())
        assertEquals(0, launches())
        assertEquals(false, platform.usb)
    }

    /** ヘルパー停止後や端末再起動後もsu経由で復旧する。 */
    @Test
    fun rootRestartsTheHelperAfterItDies() = runTest {
        platform.rootOn = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(2, suCalls())
    }

    /** rootを先に試し、起動失敗や引継ぎタイムアウトならペアリング済みワイヤレスへ切り替える。 */
    @Test
    fun rootIsPreferredAndWirelessIsTheFallback() = runTest {
        platform.rootOn = true
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(listOf("su"), platform.calls)
        helper.value = null
        platform.deliverOnLaunch = false
        advanceTimeBy(1_000)
        runCurrent()
        platform.deliverOnLaunch = true
        advanceUntilIdle()
        // 2回目はsuが承認しても引継ぎが届かず、ワイヤレスへ切り替える。
        assertEquals(listOf("su", "su", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** suの拒否は保存し、ユーザーの再試行まで自動で再要求しない。 */
    @Test
    fun rootDenialIsRecordedAndNotRetriedUntilTheUserAsks() = runTest {
        platform.rootOn = true
        platform.suAnswers += SuAnswer.Denied
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied), e.status.value)
        assertEquals(SuAnswer.Denied, platform.answered)
        assertEquals(false, e.rootStart)
        e.need()
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertEquals(1, suCalls())
        assertEquals(BuiltinShellFix.Retry, (e.status.value as BuiltinShellStatus.Failed).fix)
        // ユーザーの再試行時だけ改めてsuへ確認する。
        e.retry()
        advanceUntilIdle()
        assertEquals(2, suCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** 無応答やshellのUID/GID不一致でも再要求を繰り返さず、それぞれの原因を返す。 */
    @Test
    fun rootNoAnswerAndWrongUidStopAndSaySo() = runTest {
        platform.rootOn = true
        platform.suAnswers += SuAnswer.NoAnswer
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootNoAnswer), e.status.value)
        platform.suAnswers += SuAnswer.WrongUid
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootNoShellUid), e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(2, suCalls())
    }

    /** ペアリング済みならsuの拒否後にワイヤレス経由で起動できる。 */
    @Test
    fun rootDeniedButPairedRunsOverWireless() = runTest {
        platform.rootOn = true
        platform.pairedFlag = true
        platform.suAnswers += SuAnswer.Denied
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(listOf("su", "launch"), platform.calls)
        helper.value = null
        advanceUntilIdle()
        // 拒否後はsuへ自動で再要求しない。
        assertEquals(listOf("su", "launch", "launch"), platform.calls)
    }

    /** 自力で起動できないときの引継ぎだけをPC起動として記録する。rootが使える場合は記録しない。 */
    @Test
    fun helperArrivingWhileRootCannotStartIsRecordedAsUsb() = runTest {
        platform.rootOn = true
        platform.answered = SuAnswer.Denied
        val e = engine()
        e.start()
        helper.value = Any()
        advanceUntilIdle()
        assertEquals(true, platform.usb)
        assertEquals(HelperRestart.UsbCommand, e.restart)
        platform.usb = false
        platform.answered = null
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(false, platform.usb)
        assertEquals(HelperRestart.Root, e.restart)
    }

    /** 有効化後にsuがなくなり、未ペアリングならrootを利用できないと表示する。 */
    @Test
    fun rootGoneSaysSo() = runTest {
        platform.rootOn = true
        platform.suPresent = false
        val e = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable), e.status.value)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable), e.status.value)
        assertEquals(0, suCalls())
    }

    /** ユーザーによるroot起動スイッチの変更を、ヘルパー不在時の状態へ直ちに反映する。 */
    @Test
    fun rootSwitchChangesTakeEffectRightAway() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        platform.rootOn = true
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, suCalls())
        platform.rootOn = false
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(1, suCalls())
    }
}
