package com.wangzan101.mika.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 最後に接続されたワイヤレス デバッグ ポート (アプリのプライベート ストレージ)。 */
interface PortCache {
    fun load(): Int?
    fun save(port: Int)
}

/**
 * は一度接続ポートを検索し、各ソースからいくつかの候補を与えました (診断情報に使用されるポート自体を除く)。
 * [scanned] が true の場合、マシンをスキャンします。[scanOpen] ポートが開いており、そのうちの [scanAdb] は adbd に似ています。 [manual] は今回ユーザーが入力したポートです。
 */
data class ConnectCandidates(
    val property: Int,
    val mdns: Int,
    val cache: Int,
    val connected: Boolean,
    val scanned: Boolean = false,
    val scanOpen: Int = 0,
    val scanAdb: Int = 0,
    val manual: Boolean = false,
)

/**
 * 本機の無線デバッグ接続ポートを見つけて接続します。最後に接続されたポート、システム プロパティ (読み取られた後にのみ使用される)、mDNS によって検出された各プロパティなど、接続されている接続で停止して、順番に試してください。
 * ローカル ポート ([scan]、以前のポートが失敗した場合のみスキャン) と、ブート ページでユーザーが入力したポート ([manual]) をスキャンします。
 * mDNS のみに依存することは機能しません。adbd は、ワイヤレス デバッグがオンになっているときにサービスを 1 回だけ登録します。 mDNS デーモンが再起動されると、登録は削除されます。一部のネットワークではマルチキャストもブロックされます。
 * mDNS は、有効期限が切れた古いポートも同時に検出する可能性があるため、見つかった数だけ試してください。キャッシュされたポートは有効期限が切れるとすぐに拒否されるため、最初にランクされます。
 * ユーザーはポートに入力した後、スキャンせずに (以前はスキャンできなかったため入力するように求められていました)、直接試してみます。
 * TLS の拒否 (UNAUTHORIZED、TLS_FAILED) は、必ずしもキーが正しくないことを意味するわけではありません。一部のシステムでは、別のサービスが同じポートの最初の TLS ハンドシェイクを最初に受信することを許可しています。
 * は誰の証明書も拒否し、その後再び adbd に落ちました。そのため、同じポートで [tlsRetryDelayMs] の間隔で再試行してください。それでもうまくいかない場合は、他の候補を試してください。
 * は、何度試しても接続に失敗した場合、およびこのように拒否するポートがある場合にのみ、UNAUTHORIZED (再ペアリングが必要) を報告します。ポートがすでに拒否されている場合、ローカル ポートはスキャンされません。
 * adbd が見つかり、スキャンのみが行われます。
 */
class AdbConnectPort(
    private val property: () -> Int?,
    private val finder: AdbServiceFinder,
    private val cache: PortCache,
    private val mdnsTimeoutMs: Long = 3_000,
    /** は、接続が終了するたび (接続または失敗)、各ソースからの候補の数を報告します。 */
    private val report: (ConnectCandidates) -> Unit = {},
    private val scan: LocalPortScan? = null,
    /** 今回ユーザーが入力したポート。入力されていない場合は null になります。 */
    private val manual: () -> Int? = { null },
    /** は、ローカル ポートのスキャンの開始時と終了時に 1 回レポートします (インターフェイスには「このマシンを検索しています...」と表示されます)。 */
    private val scanning: (Boolean) -> Unit = {},
    private val localHost: String = LOCALHOST,
    private val tlsRetryDelayMs: Long = 300,
) {
    /** は、[open] を使用して候補を順番に試し、最初に接続された結果を返します。いずれも接続できない場合は、ポートが拒否したことを示す UNAUTHORIZED をスローします。そうでない場合は、最後のエラーをスローします。 */
    suspend fun <T> connect(open: (host: String, port: Int) -> T): T {
        val tried = HashSet<AdbEndpoint>()
        val triedPorts = HashSet<Int>()
        var last: AdbConnectionException? = null
        var rejected: AdbConnectionException? = null
        val manualPort = manual()?.takeIf { it in 1..65535 }
        var scanResult: ScanResult? = null
        fun local(vararg ports: Int?) = ports.filterNotNull().map { AdbEndpoint(localHost, it) }
        val sources: List<suspend () -> List<AdbEndpoint>> = listOf(
            { local(cache.load()) },
            { local(property()) },
            { finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs).map { AdbEndpoint(localHost, it) } },
            {
                if (scan == null || manualPort != null || rejected != null) emptyList() else {
                    scanning(true)
                    try {
                        scan.scan(triedPorts).also { scanResult = it }.endpoints
                    } finally {
                        scanning(false)
                    }
                }
            },
            { local(manualPort) },
        )
        val counts = IntArray(sources.size)
        var connected = false
        try {
            for ((i, source) in sources.withIndex()) {
                // 読み取ることができないソース (属性がブロックされ、mDNS を開始できない) は、なしとして扱われ、失敗とはみなされません。
                val endpoints = try {
                    source()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                counts[i] = endpoints.size
                for (ep in endpoints) {
                    if (ep.port !in 1..65535 || !tried.add(ep)) continue
                    triedPorts += ep.port
                    var retried = false
                    while (true) {
                        try {
                            return open(ep.host, ep.port).also { cache.save(ep.port); connected = true }
                        } catch (e: AdbConnectionException) {
                            // 地址策略失败不是这个端口暂时不可用:不能换端口继续,更不能稍后自动重试。
                            if (e.reason == AdbConnectionException.Reason.NOT_LOCAL) throw e
                            val tls = e.reason == AdbConnectionException.Reason.UNAUTHORIZED ||
                                e.reason == AdbConnectionException.Reason.TLS_FAILED
                            if (tls && !retried) {
                                retried = true
                                delay(tlsRetryDelayMs)
                                continue
                            }
                            if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) rejected = e
                            last = e
                            break
                        }
                    }
                }
            }
            throw rejected ?: last ?: AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "no wireless debugging port found")
        } finally {
            val s = scanResult
            report(
                ConnectCandidates(
                    property = counts[1], mdns = counts[2], cache = counts[0], connected = connected,
                    scanned = s != null, scanOpen = s?.open ?: 0, scanAdb = s?.adb ?: 0, manual = manualPort != null,
                ),
            )
        }
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
