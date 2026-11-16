package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import java.net.URI
import java.net.URLDecoder
import com.wangzan101.mika.core.net.UrlHost

/** ページはアプリのリンクにジャンプしたいと考えています。 [packageName] はリンクからのみ解析でき、正当なパッケージ名です。 */
data class ExternalLink(val url: String, val packageName: String?)

/** URL ルール: 開くことができるもの、アプリにジャンプするためのリンク、および 2 つのアドレスが同じサイトであるかどうか。 */
object WebUrlPolicy {
    /** ブラウザー独自のプロトコル: ナビゲーションもアプリジャンプもありません。 */
    private val BROWSER_SCHEMES = setOf("http", "https", "file", "content", "javascript", "data", "about", "blob")

    private val SCHEME_RE = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    /** アプリ ストアの Web アドレス (ホスト、パス プレフィックス): これらを開く目的は、アプリをインストールまたは開くことです。 */
    private val STORE_PAGES = listOf(
        "play.google.com" to "/store/apps",
        "market.android.com" to "/details",
        "apps.apple.com" to "/",
        "itunes.apple.com" to "/",
    )

    /** null = 開くことができます。それ以外の場合は、モデルに指定された無効な引数の説明になります。 */
    fun checkNavigable(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "the URL is empty"
        val scheme = scheme(u) ?: return "the URL must start with http:// or https://"
        if (external(u) != null) return "this is a link that opens an app, so it is not opened in the browser; when the task needs that app, open it on the phone"
        if (scheme != "http" && scheme != "https") return "only http/https URLs can be opened, not $scheme:"
        if (host(u) == null) return "the URL has no domain"
        return null
    }

    /** インテント:、カスタム プロトコル、アプリ ストア リンク (ストア Web ページを含む) → アプリにジャンプするリンク。通常の Web ページとブラウザ自体の間のプロトコルは null です。 */
    fun external(url: String): ExternalLink? {
        val u = url.trim()
        val scheme = scheme(u) ?: return null
        when (scheme) {
            "intent" -> {
                val pkg = u.substringAfter("#Intent;", "").split(';')
                    .firstOrNull { it.startsWith("package=") }?.removePrefix("package=")
                return ExternalLink(u, validPackage(pkg))
            }
            "market" -> return ExternalLink(u, validPackage(queryParam(u, "id")))
            "http", "https" -> {
                val h = host(u) ?: return null
                val path = runCatching { URI(u).rawPath }.getOrNull().orEmpty().ifEmpty { "/" }
                if (STORE_PAGES.none { (sh, prefix) -> h == sh && path.startsWith(prefix) }) return null
                return ExternalLink(u, validPackage(queryParam(u, "id")))
            }
        }
        return if (scheme in BROWSER_SCHEMES) null else ExternalLink(u, null)
    }

    /** 小文字の ASCII ドメイン名 (xn-- 形式に変換された国際化ドメイン名)、ポートと www を削除します。初めに。解析されない場合は null になります。 */
    fun host(url: String): String? = UrlHost.of(url)

    /** 2 つのアドレスが同じサイト上にあるかどうかについては、[UrlHost.sameSite] を参照してください。 */
    fun sameSite(a: String, b: String): Boolean = UrlHost.sameSite(a, b)

    /** はドメイン名を登録できます。[UrlHost.registrable] を参照してください。 */
    fun registrable(host: String): String? = UrlHost.registrable(host)

    /** は同じソースから来ています。プロトコル、ホスト名、ポートはすべて同じです (デフォルトのポートはプロトコルに従って完成します)。 */
    fun sameOrigin(a: String, b: String): Boolean {
        val oa = origin(a) ?: return false
        return oa == origin(b)
    }

    private fun origin(url: String): Triple<String, String, Int>? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val h = UrlHost.raw(url) ?: return null
        val port = u.port.takeIf { it >= 0 } ?: portOf(u.rawAuthority) ?: when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
        return Triple(scheme, h, port)
    }

    private fun portOf(authority: String?): Int? =
        authority?.substringAfterLast('@')?.substringAfterLast(']')?.substringAfter(':', "")?.toIntOrNull()

    private fun validPackage(raw: String?): String? {
        val p = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim() ?: return null
        return p.takeIf(PACKAGE_NAME_RE::matches)
    }

    /** プロトコル名 (小文字)。プロトコルを持たない「example.com:8080」や「localhost:3000」はカウントされません。 */
    private fun scheme(url: String): String? {
        val i = url.indexOf(':')
        if (i <= 0) return null
        val s = url.substring(0, i)
        if (!SCHEME_RE.matches(s) || !s.all { it.code < 128 }) return null
        val rest = url.substring(i + 1)
        if ('.' in s || (rest.isNotEmpty() && rest.takeWhile { it != '/' }.all(Char::isDigit) && rest.first().isDigit())) return null
        return s.lowercase()
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringBefore('#').substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")?.takeIf { it.isNotBlank() }
}
