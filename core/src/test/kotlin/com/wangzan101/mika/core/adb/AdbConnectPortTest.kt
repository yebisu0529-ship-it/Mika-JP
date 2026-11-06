package com.wangzan101.mika.core.adb

import com.wangzan101.mika.core.adb.AdbConnectionException.Reason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbConnectPortTest {
    private class Cache(var port: Int? = null) : PortCache {
        override fun load() = port
        override fun save(port: Int) { this.port = port }
    }

    private val attempts = mutableListOf<Int>()
    private var mdnsAsked = 0

    private fun chain(prop: Int?, mdns: Int?, cache: Cache, propThrows: Boolean = false, mdnsAll: List<Int>? = null) = AdbConnectPort(
        property = { if (propThrows) throw SecurityException("denied") else prop },
        finder = { _, _ -> mdnsAsked++; mdnsAll ?: listOfNotNull(mdns) },
        cache = cache,
    )

    private fun opener(vararg ok: Int, unauthorized: Int? = null): (String, Int) -> String = { _, port ->
        attempts += port
        when (port) {
            unauthorized -> throw AdbConnectionException(Reason.UNAUTHORIZED, "no")
            in ok -> "conn@$port"
            else -> throw AdbConnectionException(Reason.CONNECT_FAILED, "closed")
        }
    }

    @Test
    fun cachedFirstThenPropertyAndMdnsNotAskedWhenItWorks() = runTest {
        // キャッシュされた古いポートはすぐに拒否されました。プロパティを試してください。
        val cache = Cache(1111)
        assertEquals("conn@4000", chain(4000, 5000, cache).connect(opener(4000)))
        assertEquals(listOf(1111, 4000), attempts)
        assertEquals(0, mdnsAsked)
        assertEquals(4000, cache.port)
        attempts.clear()
        assertEquals("conn@4000", chain(4000, 5000, cache).connect(opener(4000)))
        assertEquals(listOf(4000), attempts)
    }

    @Test
    fun unreadableOrZeroPropertyFallsToMdnsThenCache() = runTest {
        val cache = Cache(1111)
        assertEquals("conn@5000", chain(0, 5000, cache).connect(opener(5000)))
        assertEquals(5000, cache.port)
        attempts.clear()
        assertEquals("conn@5000", chain(null, null, Cache(5000), propThrows = true).connect(opener(5000)))
        assertEquals(listOf(5000), attempts)
    }

    @Test
    fun stalePortsAreSkippedAndDuplicatesTriedOnce() = runTest {
        // キャッシュと属性はすべて古いポートです (接続できません)。mDNS は同じ古いポートを再度提供しました。最後のポートは正しいです。
        val cache = Cache(4000)
        assertEquals("conn@6000", chain(4000, null, cache, mdnsAll = listOf(4000, 6000)).connect(opener(6000)))
        assertEquals(listOf(4000, 6000), attempts)
    }

    /** [rejectFirst] [port] への 2 回目の接続時に TLS が拒否されました ([reason])。その後、接続は成功しました。他のポートは [rest] でした。 */
    private fun flaky(port: Int, rejectFirst: Int, rest: (String, Int) -> String, reason: Reason = Reason.UNAUTHORIZED): (String, Int) -> String {
        var left = rejectFirst
        return { host, p ->
            if (p == port && left > 0) {
                attempts += p
                left--
                throw AdbConnectionException(reason, "tls")
            }
            rest(host, p)
        }
    }

    @Test
    fun tlsRejectionOnceIsRetriedOnTheSamePort() = runTest {
        // 一部のシステムでは、別のサービスが最初の TLS ハンドシェイクを最初に受信し、証明書を拒否することを許可しています。その後、次の接続は adbd に落ちます。
        val cache = Cache()
        assertEquals("conn@4000", chain(4000, 5000, cache).connect(flaky(4000, 1, opener(4000))))
        assertEquals(listOf(4000, 4000), attempts)
        assertEquals(4000, cache.port)
        attempts.clear()
        assertEquals("conn@4000", chain(4000, null, Cache()).connect(flaky(4000, 1, opener(4000), Reason.TLS_FAILED)))
        assertEquals(listOf(4000, 4000), attempts)
    }

    @Test
    fun notLocalStopsImmediatelyInsteadOfTryingOtherPorts() = runTest {
        attempts.clear()
        try {
            chain(4000, 5000, Cache()).connect { _, port ->
                attempts += port
                throw AdbConnectionException(Reason.NOT_LOCAL, "not local")
            }
            fail("expected NOT_LOCAL")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.NOT_LOCAL, e.reason)
        }
        assertEquals(listOf(4000), attempts)
    }

    @Test
    fun aCandidateThatKeepsRejectingDoesNotStopTheOthers() = runTest {
        val cache = Cache()
        assertEquals("conn@5001", chain(null, null, cache, mdnsAll = listOf(5000, 5001)).connect(opener(5001, unauthorized = 5000)))
        assertEquals(listOf(5000, 5000, 5001), attempts)
        assertEquals(5001, cache.port)
    }

    @Test
    fun rejectedEverywhereIsUnauthorized() = runTest {
        // 応答用の唯一のポートが 2 回試行した後拒否されました。残りは古いポートです。キーは実際には認識されないため、再度ペアリングする必要があります。ローカルポートをスキャンしなくなりました。
        val scanner = FakeScanner(mapOf("127.0.0.1" to listOf(41000)), setOf(AdbEndpoint("127.0.0.1", 41000)))
        val port = AdbConnectPort(
            { 4000 }, { _, _ -> listOf(5000) }, Cache(3000),
            scan = LocalPortScan(scanner, { listOf("127.0.0.1") }),
        )
        try {
            port.connect(opener(41000, unauthorized = 4000))
            fail("expected UNAUTHORIZED")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.UNAUTHORIZED, e.reason)
        }
        assertEquals(listOf(3000, 4000, 4000, 5000), attempts)
        assertTrue(scanner.swept.isEmpty())
    }

    @Test
    fun nothingFoundIsConnectFailed() = runTest {
        val cache = Cache()
        try {
            chain(null, null, cache).connect(opener())
            fail("expected CONNECT_FAILED")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertNull(cache.port)
        try {
            chain(4000, null, cache).connect(opener())
            fail("expected CONNECT_FAILED")
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertNull(cache.port)
    }

    @Test
    fun everyMdnsPortIsTriedWhenAStaleRecordComesFirst() = runTest {
        // ワイヤレス デバッグをオフにしてからオンにします。古いポートの記録はまだ存在しており、新しいポートも一緒に戻ります。
        val cache = Cache(4000)
        assertEquals("conn@5000", chain(null, null, cache, mdnsAll = listOf(4000, 5000)).connect(opener(5000)))
        assertEquals(listOf(4000, 5000), attempts)
        assertEquals(5000, cache.port)
    }

    /** 接続が終了するたびに、1 回レポートされます。各情報源はいくつかの候補を提示しており、接続されたものと失敗したものの両方が報告されています。 */
    @Test
    fun reportsCandidateCounts() = runTest {
        val seen = mutableListOf<ConnectCandidates>()
        val ok = AdbConnectPort({ null }, { _, _ -> listOf(5000, 5001) }, Cache(4999), report = { seen += it })
        assertEquals("conn@5001", ok.connect(opener(5001)))
        assertEquals(ConnectCandidates(property = 0, mdns = 2, cache = 1, connected = true), seen.single())
        seen.clear()
        val none = AdbConnectPort({ null }, { _, _ -> emptyList() }, Cache(), report = { seen += it })
        try {
            none.connect(opener())
            fail()
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertEquals(ConnectCandidates(0, 0, 0, connected = false), seen.single())
    }

    /** フェイク スキャン: [open] は各アドレスの開いているポートで、[adb] は STLS を返します。どのアドレスがスキャンされ、どのポートがスキップされたかを記録します。 */
    private class FakeScanner(val open: Map<String, List<Int>>, val adb: Set<AdbEndpoint>) : LocalPortScanner {
        val swept = mutableListOf<String>()
        var skipped: Set<Int> = emptySet()
        override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> {
            swept += host
            skipped = skip
            return open[host].orEmpty().filter { it in ports && it !in skip }
        }
        override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int) = AdbEndpoint(host, port) in adb
    }

    private val wifi = "192.0.2.10"

    @Test
    fun scanRunsOnlyAfterEarlierSourcesFailAndTheHitIsCached() = runTest {
        val scanner = FakeScanner(mapOf("127.0.0.1" to listOf(40000, 41235)), setOf(AdbEndpoint("127.0.0.1", 41235)))
        val cache = Cache(39000)
        val seen = mutableListOf<ConnectCandidates>()
        val progress = mutableListOf<Boolean>()
        val port = AdbConnectPort(
            { null }, { _, _ -> emptyList() }, cache, report = { seen += it },
            scan = LocalPortScan(scanner, { listOf("127.0.0.1", wifi) }), scanning = { progress += it },
        )
        assertEquals("conn@41235", port.connect(opener(41235)))
        assertEquals(listOf(39000, 41235), attempts)
        assertEquals(41235, cache.port)
        // 試行されたキャッシュ ポートはスキャンされなくなりました。 Wi-Fi アドレスがローカル アドレスで見つかった場合、そのアドレスはスキャンされなくなりました。
        assertTrue(39000 in scanner.skipped)
        assertEquals(listOf("127.0.0.1"), scanner.swept)
        assertEquals(listOf(true, false), progress)
        assertEquals(ConnectCandidates(0, 0, 1, connected = true, scanned = true, scanOpen = 2, scanAdb = 1), seen.single())
        // 次回はキャッシュが直接接続され、それ以上のスキャンは行われません。
        attempts.clear()
        assertEquals("conn@41235", port.connect(opener(41235)))
        assertEquals(listOf(41235), attempts)
        assertEquals(listOf("127.0.0.1"), scanner.swept)
    }

    @Test
    fun scanNotRunWhenAnEarlierSourceConnects() = runTest {
        val scanner = FakeScanner(emptyMap(), emptySet())
        val port = AdbConnectPort({ 4000 }, { _, _ -> emptyList() }, Cache(), scan = LocalPortScan(scanner, { listOf("127.0.0.1") }))
        assertEquals("conn@4000", port.connect(opener(4000)))
        assertTrue(scanner.swept.isEmpty())
    }

    @Test
    fun scanFallsToWifiAddressAndConnectsThere() = runTest {
        val scanner = FakeScanner(mapOf("127.0.0.1" to listOf(40000), wifi to listOf(40000, 45000)), setOf(AdbEndpoint(wifi, 45000)))
        val hosts = mutableListOf<String>()
        val port = AdbConnectPort({ null }, { _, _ -> emptyList() }, Cache(), scan = LocalPortScan(scanner, { listOf("127.0.0.1", wifi) }))
        assertEquals("conn@45000", port.connect { host, p -> hosts += host; opener(45000)(host, p) })
        assertEquals(listOf("127.0.0.1", wifi), scanner.swept)
        assertEquals(listOf(wifi), hosts)
    }

    @Test
    fun firstVerifiedScanHitWinsAndLaterOnesAreNotTried() = runTest {
        val hits = setOf(AdbEndpoint("127.0.0.1", 41000), AdbEndpoint("127.0.0.1", 42000))
        val scanner = FakeScanner(mapOf("127.0.0.1" to listOf(41000, 42000)), hits)
        val port = AdbConnectPort({ null }, { _, _ -> emptyList() }, Cache(), scan = LocalPortScan(scanner, { listOf("127.0.0.1") }))
        assertEquals("conn@41000", port.connect(opener(41000, 42000)))
        assertEquals(listOf(41000), attempts)
        // 最初のハンドシェイクが失敗した場合は、次のハンドシェイクを試してください。
        attempts.clear()
        assertEquals("conn@42000", port.connect(opener(42000)))
        assertEquals(listOf(41000, 42000), attempts)
    }

    @Test
    fun manualPortIsTriedLastWithoutScanningAndCachedOnSuccess() = runTest {
        val scanner = FakeScanner(mapOf("127.0.0.1" to listOf(41235)), setOf(AdbEndpoint("127.0.0.1", 41235)))
        val cache = Cache()
        val seen = mutableListOf<ConnectCandidates>()
        val port = AdbConnectPort(
            { null }, { _, _ -> emptyList() }, cache, report = { seen += it },
            scan = LocalPortScan(scanner, { listOf("127.0.0.1") }), manual = { 43000 },
        )
        assertEquals("conn@43000", port.connect(opener(43000)))
        assertTrue(scanner.swept.isEmpty())
        assertEquals(43000, cache.port)
        assertEquals(ConnectCandidates(0, 0, 0, connected = true, manual = true), seen.single())
    }

    @Test
    fun scanAndManualBothFailingIsConnectFailed() = runTest {
        val scanner = FakeScanner(mapOf("127.0.0.1" to listOf(40000)), emptySet())
        val seen = mutableListOf<ConnectCandidates>()
        val port = AdbConnectPort({ null }, { _, _ -> emptyList() }, Cache(), report = { seen += it }, scan = LocalPortScan(scanner, { listOf("127.0.0.1") }))
        try {
            port.connect(opener())
            fail()
        } catch (e: AdbConnectionException) {
            assertEquals(Reason.CONNECT_FAILED, e.reason)
        }
        assertEquals(ConnectCandidates(0, 0, 0, connected = false, scanned = true, scanOpen = 1, scanAdb = 0), seen.single())
    }
}
