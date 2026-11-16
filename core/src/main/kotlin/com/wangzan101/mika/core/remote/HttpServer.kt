package com.wangzan101.mika.core.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** HTTP リクエスト。名は常に小文字にする必要があります。 [remote] は相手のアドレスです (現在の制限と最近の接続数はそれに基づいて計算されます)。 */
class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
    val query: String = "",
    /** 冷却に失敗した場合は、これを押して記録します。ローカル監視は相手のアドレスです。ゲートウェイによって転送されるのは、ゲートウェイが認識する発信者のアドレスであり、ローカル アドレスとは別に記録されます。 */
    val source: String = remote.hostAddress ?: "?",
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpResponse(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun json(status: Int, text: String, extra: Map<String, String> = emptyMap()) =
            HttpResponse(status, text.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/json") + extra)

        fun empty(status: Int, extra: Map<String, String> = emptyMap()) = HttpResponse(status, ByteArray(0), extra)
    }
}

/** リクエスト自体が HTTP ルールに準拠していません (大きすぎる、形式が間違っています)。[status] を直接返して切断します。 */
class HttpProtocolException(val status: Int, message: String) : IOException(message)

/**
 * は、コンピューター接続専用の最小限の HTTP/1.1 サービスです。接続ごとに 1 つのポート、1 つのスレッド、接続数の上限、および要求ヘッダーと本文のサイズの上限があります。
 * は、Content-Length とチャンク形式の 2 種類のテキストのみを認識します。 TLS は実行しません (LAN およびプライベート ネットワークでのみ使用され、アクセス トークンに依存します)。
 * [accept] 両端のアドレスに従って接続を受け入れるかどうかを決定します (受け入れない場合は、バイトを返さずにその場で閉じられます)。
 */
class MiniHttpServer(
    private val bind: InetSocketAddress,
    private val handler: (HttpRequest) -> HttpResponse,
    private val accept: (local: InetAddress, remote: InetAddress) -> Boolean = { _, _ -> true },
    private val maxConnections: Int = 16,
    private val readTimeoutMs: Int = 30_000,
    private val onError: (Throwable) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val slots = Semaphore(maxConnections)
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var server: ServerSocket? = null

    /** 実際のリスニングポート (0 が指定された場合にシステムによって選択されます)。 */
    val port: Int get() = server?.localPort ?: -1

    /** はポートをバインドし、接続の受信を開始します。バインディングが失敗すると、例外がスローされます (ポートが占有されているなど)。 */
    @Synchronized fun start() {
        check(!running.get()) { "already running" }
        val s = ServerSocket()
        s.reuseAddress = true
        try { s.bind(bind, 32) } catch (e: IOException) { s.close(); throw e }
        server = s
        running.set(true)
        Thread({ acceptLoop(s) }, "remote-accept").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val socket = try { s.accept() } catch (e: IOException) { if (running.get()) onError(e); break }
            if (!accept(socket.localAddress, socket.inetAddress)) { runCatching { socket.close() }; continue }
            if (!slots.tryAcquire()) {
                runCatching { socket.soTimeout = 2_000; write(socket.getOutputStream(), HttpResponse.empty(503), close = true); socket.close() }
                continue
            }
            open += socket
            Thread({
                try { serve(socket) } finally {
                    open -= socket
                    runCatching { socket.close() }
                    slots.release()
                }
            }, "remote-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (running.get()) {
                val request = try { readRequest(input, socket.inetAddress, output) ?: return } catch (e: HttpProtocolException) {
                    write(output, HttpResponse.empty(e.status), close = true)
                    return
                }
                val response = try { handler(request) } catch (e: Exception) { onError(e); HttpResponse.empty(500) }
                val close = request.header("connection")?.split(',')
                    ?.any { it.trim().equals("close", ignoreCase = true) } == true
                write(output, response, close, headOnly = request.method == "HEAD")
                if (close) return
            }
        } catch (_: SocketException) {
        } catch (_: java.net.SocketTimeoutException) {
        } catch (e: IOException) {
            onError(e)
        }
    }

    companion object {
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 256 * 1024

        /**
         * リクエストを読み取ります。リクエストが開始される前に相手が接続を閉じた場合は、null が返されます。相手は最初にテキスト (`Expect: 100-continue`、
         * (これは、コマンド ライン ツールが少し大きなテキストを送信するときに発生します) および [output] が指定された場合は、待機しないように最初に「続行」と応答します。 同じ名前の
         */
        fun readRequest(input: InputStream, remote: InetAddress, output: OutputStream? = null): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpProtocolException(400, "bad request line")
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) throw HttpProtocolException(400, "bad header")
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                // ヘッダーはマージされます。 Content-Length が繰り返され、不正なリクエストと同時に押されることはありません
                headers[name] = headers[name]?.let { old ->
                    if (name == "content-length" && old != value) throw HttpProtocolException(400, "conflicting length")
                    if (name == "content-length") old else "$old, $value"
                } ?: value
            }
            if (output != null && headers["expect"].equals("100-continue", ignoreCase = true)) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            val body = when {
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
                headers["content-length"] != null -> {
                    val n = headers["content-length"]!!.toLongOrNull() ?: throw HttpProtocolException(400, "bad length")
                    if (n < 0) throw HttpProtocolException(400, "bad length")
                    if (n > MAX_BODY) throw HttpProtocolException(413, "body too large")
                    readExactly(input, n.toInt())
                }
                else -> ByteArray(0)
            }
            val target = parts[1]
            return HttpRequest(parts[0], target.substringBefore('?'), headers, body, remote, target.substringAfter('?', ""))
        }

        private fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < 4) {
                val b = input.read()
                if (b < 0) {
                    if (buf.size() == 0) return null
                    throw HttpProtocolException(400, "truncated head")
                }
                // 2 つのリクエスト間の余分な空白行は無視されます。
                if (buf.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
                buf.write(b)
                if (buf.size() > MAX_HEAD) throw HttpProtocolException(431, "head too large")
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            }
            return String(buf.toByteArray(), 0, buf.size() - 4, Charsets.ISO_8859_1)
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(out, off, n - off)
                if (r < 0) throw HttpProtocolException(400, "truncated body")
                off += r
            }
            return out
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw HttpProtocolException(400, "truncated chunk")
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 1024) throw HttpProtocolException(400, "chunk line too long")
            }
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw HttpProtocolException(400, "bad chunk")
                // 加算の桁あふれを避け、残り容量と比較して大きすぎるチャンクの確保を拒否します。
                if (size < 0 || size > MAX_BODY - out.size()) throw HttpProtocolException(413, "body too large")
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) Unit
                    return out.toByteArray()
                }
                out.write(readExactly(input, size))
                readLine(input)
            }
        }

        private val REASONS = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 406 to "Not Acceptable", 409 to "Conflict", 413 to "Payload Too Large",
            415 to "Unsupported Media Type", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
            500 to "Internal Server Error", 503 to "Service Unavailable",
        )

        fun write(output: OutputStream, r: HttpResponse, close: Boolean, headOnly: Boolean = false) {
            val head = StringBuilder("HTTP/1.1 ${r.status} ${REASONS[r.status] ?: "Status"}\r\n")
            r.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("Content-Length: ").append(r.body.size).append("\r\n")
            head.append("Cache-Control: no-store\r\n")
            if (close) head.append("Connection: close\r\n")
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (!headOnly) output.write(r.body)
            output.flush()
        }
    }
}
