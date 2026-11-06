package com.wangzan101.mika.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/** 接続ポートの候補: ローカル マシンのアドレスとポート。 */
data class AdbEndpoint(val host: String, val port: Int)

/** このマシン上でミカ自身のリスニング ポートが開かれました (ログイン コールバックなど)。ポートをスキャンするときにスキップして、自分への接続を回避します。 */
object OwnListeningPorts {
    private val ports = ConcurrentHashMap.newKeySet<Int>()
    fun add(port: Int) { ports += port }
    fun remove(port: Int) { ports -= port }
    fun snapshot(): Set<Int> = ports.toSet()
}

/** は、ローカル ポートの基礎となるアクションをスキャンします。テストでは偽物に置き換えてください。 */
interface LocalPortScanner {
    /** [host] [ports] のうちどれが TCP 接続を確立できるか (昇順)、[skip] はスキップします。 [budgetMs] は使用後に停止し、見つかったものはアップロードされます。 */
    suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int>

    /** は CNXN を送信し、相手はワイヤレス デバッグ adbd とみなされるように STLS を返します (他のサービスは STLS 自体に応答することさえありません)。 */
    fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean
}

/** ローカル スキャンの結果: いくつかのポートが開いており、いくつかは adbd に似ています (診断情報には番号のみが記録されます)。 */
data class ScanResult(val endpoints: List<AdbEndpoint>, val open: Int, val adb: Int)

/**
 * 接続ポートを見つけるための最後の手段: 前のソースで接続可能なポートが提供されない場合は、マシン自体のアドレスをスキャンします。
 * ワイヤレス デバッグ adbd は、一時ポート範囲 ([range]、読み取れない場合は、[FALLBACK_RANGE]) に該当する、システムによってランダムに割り当てられたポートを監視します。
 * [hosts] の順序でアドレスごとにスキャンします (最初は 127.0.0.1、次にローカル Wi-Fi アドレス。adbd はそのうちの 1 つだけをバインドします)。
 * 開いている各ポートは CNXN を送信しますが、STLS を読み取ることはできません。アドレスに adbd のようなものがある場合、次のアドレスはスキャンされません。このマシンのみをスキャンし、他のデバイスには触れないでください。
 * のスキャンと検証の合計が [budgetMs] を超えないこと。
 */
class LocalPortScan(
    private val scanner: LocalPortScanner,
    private val hosts: () -> List<String>,
    private val range: () -> IntRange = { FALLBACK_RANGE },
    private val ownPorts: () -> Set<Int> = OwnListeningPorts::snapshot,
    private val budgetMs: Long = 10_000,
    private val probeTimeoutMs: Int = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** をスキャンします。 [skip] は、前のソースによって試行されたポートです。 */
    suspend fun scan(skip: Set<Int>): ScanResult {
        val deadline = now() + budgetMs
        val ports = range()
        val excluded = skip + ownPorts()
        val found = ArrayList<AdbEndpoint>()
        var open = 0
        for (host in hosts().distinct()) {
            val left = deadline - now()
            if (left <= 0) break
            val openPorts = scanner.openPorts(host, ports, excluded, left)
            open += openPorts.size
            for (port in openPorts) {
                if (now() >= deadline) break
                if (scanner.speaksAdbTls(host, port, probeTimeoutMs)) found += AdbEndpoint(host, port)
            }
            if (found.isNotEmpty()) break
        }
        return ScanResult(found, open, found.size)
    }

    companion object {
        /** システムの一時ポート範囲を読み取れない場合は、このセクションを参照してください (各バージョンの共通設定をカバーしています)。 */
        val FALLBACK_RANGE = 30_000..65_535

        /**
         * は、`/proc/sys/net/ipv4/ip_local_port_range` (空白で区切られた 2 つの数字) を解析します。読み込めない場合、形式が間違っている場合、範囲が無理な場合は、[FALLBACK_RANGE] を使用してください。
         */
        fun parseRange(text: String?): IntRange {
            val nums = text?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: return FALLBACK_RANGE
            if (nums.size != 2) return FALLBACK_RANGE
            val (lo, hi) = nums
            return if (lo in 1024..65_535 && hi in lo..65_535) lo..hi else FALLBACK_RANGE
        }
    }
}

/**
 * 実際のスキャン: java.nio 非ブロッキング接続、同時に最大 [concurrency] の接続、それぞれが最大 [connectTimeoutMs] 待機できます。
 * ローカル アドレスに接続できないポートはすぐに拒否されます。セクション全体を数秒間スキャンします。 IO スレッドで実行され、コルーチンがキャンセルされると停止します。
 */
class NioPortScanner(
    private val concurrency: Int = 200,
    private val connectTimeoutMs: Long = 150,
) : LocalPortScanner {
    override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> = withContext(Dispatchers.IO) {
        val address = AdbHosts.ownAddress(host) ?: return@withContext emptyList()
        val ctx = currentCoroutineContext()
        sweep(address, ports, skip, System.currentTimeMillis() + budgetMs) { ctx.isActive }
    }

    private fun sweep(addr: InetAddress, ports: IntRange, skip: Set<Int>, deadline: Long, active: () -> Boolean): List<Int> {
        val open = TreeSet<Int>()
        val next = ports.iterator()
        Selector.open().use { selector ->
            val startedAt = HashMap<SelectionKey, Long>()
            try {
                while ((next.hasNext() || startedAt.isNotEmpty()) && System.currentTimeMillis() < deadline && active()) {
                    while (next.hasNext() && startedAt.size < concurrency) {
                        val port = next.nextInt()
                        if (port in skip) continue
                        val ch = SocketChannel.open()
                        try {
                            ch.configureBlocking(false)
                            if (ch.connect(InetSocketAddress(addr, port))) {
                                if (!selfConnected(ch, port)) open += port
                                ch.close()
                            } else {
                                startedAt[ch.register(selector, SelectionKey.OP_CONNECT, port)] = System.currentTimeMillis()
                            }
                        } catch (e: IOException) {
                            ch.close()
                        }
                    }
                    selector.select(SELECT_MS)
                    for (key in selector.selectedKeys()) {
                        val ch = key.channel() as SocketChannel
                        val ok = try { ch.finishConnect() } catch (e: IOException) { false }
                        if (ok && !selfConnected(ch, key.attachment() as Int)) open += key.attachment() as Int
                        startedAt.remove(key)
                        key.cancel()
                        ch.close()
                    }
                    selector.selectedKeys().clear()
                    val t = System.currentTimeMillis()
                    val late = startedAt.filterValues { t - it > connectTimeoutMs }.keys
                    for (key in late) {
                        startedAt.remove(key)
                        key.cancel()
                        key.channel().close()
                    }
                }
            } finally {
                for (key in startedAt.keys) runCatching { key.channel().close() }
            }
        }
        return open.toList()
    }

    /** 一時的なポート範囲をスキャンするときに、システムがたまたま同じローカル ポートをこの接続に割り当てる可能性があるため、システム自体が接続します。 */
    private fun selfConnected(ch: SocketChannel, port: Int): Boolean = runCatching {
        val s = ch.socket()
        s.localPort == port && s.localAddress == s.inetAddress
    }.getOrDefault(false)

    override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean = try {
        val target = AdbHosts.ownSocketAddress(host, port) ?: return false
        Socket().use { s ->
            s.connect(target, timeoutMs)
            if (s.localPort == port && s.localAddress == s.inetAddress) return false
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
                flush()
            }
            AdbMessage.read(DataInputStream(s.getInputStream()), maxData = 4096)?.command == AdbMessage.STLS
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val SELECT_MS = 20L
        val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
    }
}
