package com.wangzan101.mika.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * アドレスはローカル マシンまたは LAN 上にありますか?ドメイン名を解決せずに、アドレス自体のみを確認します。
 * - [isLocal] (厳密): ローカル マシン (localhost、127.0.0.0/8、::1)、プライベート ネットワーク セグメント (10/8、172.16/12、192.168/16、IPv6 fc00::/7)、
 *   リンク ローカル (169.254/16、fe80::/10) および `.local` 名。平文 http が安全かどうかを判断するために使用されます。
 * - [bypassesProxy] (より広い): ネットワーク プロキシを使用する場合でも直接接続されているアドレス - [isLocal]。ドットのないホスト名 (LAN 内のマシン名) もあります。
 *   は、LAN (`.lan`、`.home.arpa`、`.internal`) およびキャリア グレード NAT 100.64/10 の共有ネットワーク セグメントで一般的に使用されるサフィックスです (多くの場合、ネットワーク ツールはデバイスをこのセグメントに分割します)。
 *   ローカル マシンおよび LAN 上で実行されているモデル サービス エージェントのほとんどはアクセスできないため、直接接続されます。
 */
object LocalHosts {
    fun isLocal(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        ipv4(h)?.let { (a, b) -> return privateIpv4(a, b) }
        if (':' in h) {
            // ::1 的完整写法也是本机回环
            if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            // ::ffff:a.b.c.d 是 IPv4-mapped IPv6,按里面那段 IPv4 判断;本机模型服务常这样写
            if (h.startsWith("::ffff:")) {
                val (a, b) = ipv4(h.removePrefix("::ffff:")) ?: return false
                return privateIpv4(a, b)
            }
            val first = h.substringBefore(':').toIntOrNull(16) ?: return false
            return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
        }
        return false
    }

    private fun privateIpv4(a: Int, b: Int) =
        a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)

    /** 走网络代理时这个主机照旧直连。 */
    fun bypassesProxy(host: String): Boolean {
        if (isLocal(host)) return true
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (PROXY_SUFFIXES.any { h.endsWith(it) }) return true
        ipv4(h)?.let { (a, b) -> return a == 100 && b in 64..127 }
        if (':' in h) return false
        return '.' !in h
    }

    /** クリア テキスト http、ローカル マシンまたは LAN 上にないアドレス: キーと会話は暗号化されずにインターネット上で送信されます。認識できないアドレスはカウントされません。 */
    fun unencryptedRemote(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return !u.isHttps && !isLocal(u.host)
    }

    private val PROXY_SUFFIXES = listOf(".lan", ".home.arpa", ".internal")

    /**
     * ブラウザの直接接続ルール (ブラウザ カーネルによって認識される形式で記述された [bypassesProxy] の同じセット)。ローカル アドレス カーネルは本質的に直接接続されており、
     * ドットを含まないホスト名は「簡易ホスト名直接接続」で管理されます。
     */
    val BYPASS_RULES: List<String> = listOf(
        "localhost", "*.localhost", "*.local", "*.lan", "*.home.arpa", "*.internal",
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
        "fc00::/7", "fe80::/10",
    )

    /** no_proxy (コマンド ライン ツールの場合): カンマで区切られたホスト、サフィックス、およびネットワーク セグメント (ネットワーク セグメントを認識しないツールは、少なくとも最初の 3 つを認識します)。 */
    const val NO_PROXY = "localhost,127.0.0.1,::1,127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,100.64.0.0/10," +
        "fc00::/7,fe80::/10,.localhost,.local,.lan,.home.arpa,.internal"

    private fun normalize(host: String) = host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()

    /** は、4 セグメントの IPv4 の最初の 2 つのセグメントを指します。 IPv4 でない場合は null。 */
    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.all(Char::isDigit) && it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
