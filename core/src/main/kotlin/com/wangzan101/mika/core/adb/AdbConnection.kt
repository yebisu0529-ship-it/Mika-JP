package com.wangzan101.mika.core.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

class AdbConnectionException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** 目标不是本机自己的地址:建 Socket 前拒绝,不能按网络故障重试。 */
        NOT_LOCAL,
        CONNECT_FAILED,
        TLS_FAILED,
        /** デバイスはこのキーを認識しない (ペアリングされていない、取り消されている、または認証の有効期限が切れている) ため、再度ペアリングする必要があります。 */
        UNAUTHORIZED,
        PROTOCOL,
    }
}

/**
 * adbd への接続。ハンドシェイク: CNXN を送信 → デバイスが STLS に応答 → STLS に応答し、ペアの証明書を使用して TLS 1.3 を実行 →
 * デバイスは TLS 経由で CNXN (バナー) を送信します。次に、ストリームを多重化します: OPEN → OKAY、WRTE。各パケットは相手の OKAY を待つ必要があります。
 * は機能を宣言していない (遅延 ACK は使用されていない) ため、OPEN の arg1 は 0 です。
 */
class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    /** デバイス バナー (`device::ro.product.name=…;features=…` など)。 */
    val banner: String,
    private val peerVersion: Int,
    private val peerMaxData: Int,
    private val timeoutMs: Int,
) : Closeable {
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closed = false

    private val reader = thread(isDaemon = true, name = "adb-reader") { readLoop() }

    val isClosed: Boolean get() = closed

    /** は、コマンドの元の標準出力 (pty なし) である `exec:` サービスを実行し、書き込まれたデータはその標準入力に入ります。 */
    fun exec(command: String): AdbStream = open("exec:$command")

    fun open(destination: String): AdbStream {
        if (closed) throw IOException("adb connection closed")
        val s = AdbStream(nextId.getAndIncrement())
        streams[s.localId] = s
        send(AdbMessage(AdbMessage.OPEN, s.localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8)))
        val ok = try {
            s.opened.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            streams.remove(s.localId)
            throw IOException("open '$destination' timed out", e)
        }
        if (!ok) {
            streams.remove(s.localId)
            throw IOException("device refused '$destination'")
        }
        return s
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        streams.values.forEach { it.remoteClosed() }
        streams.clear()
    }

    private fun send(m: AdbMessage) {
        val bytes = m.encode()
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD, peerVersion < AdbMessage.VERSION_SKIP_CHECKSUM) ?: break
                val s = streams[m.arg1]
                when (m.command) {
                    AdbMessage.OKAY -> if (s != null) {
                        if (s.remoteId == 0) {
                            s.remoteId = m.arg0
                            s.opened.complete(true)
                        } else {
                            s.acks.release()
                        }
                    }
                    AdbMessage.WRTE -> if (s != null) {
                        s.incoming.put(m.data)
                        send(AdbMessage(AdbMessage.OKAY, s.localId, s.remoteId))
                    }
                    AdbMessage.CLSE -> if (s != null) {
                        streams.remove(s.localId)
                        s.opened.complete(false)
                        s.remoteClosed()
                    }
                    else -> Unit // 接続確立後はCNXN/AUTH等は処理されません。
                }
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    /** ストリーム。 [read] はデータが存在するまでブロックし、ストリームが終了すると null を返します。 相手が閉じるまで */
    inner class AdbStream internal constructor(val localId: Int) : Closeable {
        @Volatile internal var remoteId = 0
        internal val opened = CompletableFuture<Boolean>()
        internal val acks = Semaphore(0)
        internal val incoming = LinkedBlockingQueue<ByteArray>()
        @Volatile private var remoteGone = false
        @Volatile private var localClosed = false

        fun read(): ByteArray? {
            val b = incoming.take()
            if (b === EOF) {
                incoming.put(EOF)
                return null
            }
            return b
        }

        /** 。 */
        fun readAll(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) out.write(read() ?: return out.toByteArray())
        }

        fun readText(): String = readAll().toString(Charsets.UTF_8)

        fun write(data: ByteArray) {
            var pos = 0
            while (pos < data.size) {
                if (remoteGone || localClosed) throw IOException("stream closed")
                val n = minOf(peerMaxData, data.size - pos)
                send(AdbMessage(AdbMessage.WRTE, localId, remoteId, data.copyOfRange(pos, pos + n)))
                if (!acks.tryAcquire(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) throw IOException("write not acknowledged")
                if (remoteGone) throw IOException("stream closed")
                pos += n
            }
        }

        val inputStream: InputStream get() = object : InputStream() {
            private var buf = ByteArray(0)
            private var off = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, o: Int, len: Int): Int {
                if (len == 0) return 0
                while (off >= buf.size) {
                    buf = this@AdbStream.read() ?: return -1
                    off = 0
                }
                val n = minOf(len, buf.size - off)
                System.arraycopy(buf, off, b, o, n)
                off += n
                return n
            }
        }

        internal fun remoteClosed() {
            remoteGone = true
            opened.complete(false)
            acks.release()
            incoming.put(EOF)
        }

        override fun close() {
            if (localClosed) return
            localClosed = true
            if (!remoteGone && !closed) runCatching { send(AdbMessage(AdbMessage.CLSE, localId, remoteId)) }
            streams.remove(localId)
            remoteClosed()
        }
    }

    companion object {
        private val EOF = ByteArray(0)
        private val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)

        /** は、[host]:[port] の adbd に接続されています (通常は、127.0.0.1 + mDNS によって検出されるワイヤレス デバッグ ポート)。 */
        fun open(host: String, port: Int, tls: AdbTls, timeoutMs: Int = 10_000): AdbConnection {
            val target = AdbHosts.ownSocketAddress(host, port) ?: throw AdbConnectionException(
                AdbConnectionException.Reason.NOT_LOCAL,
                "refusing to connect to $host: adb is only reachable on this device",
            )
            val plain = Socket()
            try {
                plain.tcpNoDelay = true
                plain.connect(target, timeoutMs)
                plain.soTimeout = timeoutMs
            } catch (e: IOException) {
                plain.close()
                throw AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "cannot connect to $host:$port", e)
            }
            try {
                return handshake(plain, target, tls, timeoutMs)
            } catch (e: Throwable) {
                runCatching { plain.close() }
                throw e
            }
        }

        private fun handshake(plain: Socket, target: InetSocketAddress, tls: AdbTls, timeoutMs: Int): AdbConnection {
            val out = plain.getOutputStream()
            out.write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
            out.flush()
            val first = AdbMessage.read(DataInputStream(plain.getInputStream()))
                ?: throw AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "device closed the connection")
            return when (first.command) {
                AdbMessage.CNXN -> finish(plain, DataInputStream(plain.getInputStream()), out, first, timeoutMs)
                AdbMessage.STLS -> {
                    out.write(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode())
                    out.flush()
                    val ssl = try {
                        tls.wrap(plain, target, client = true)
                    } catch (e: IOException) {
                        throw AdbConnectionException(AdbConnectionException.Reason.TLS_FAILED, "TLS handshake failed", e)
                    }
                    val input = DataInputStream(ssl.inputStream)
                    // TLS 1.3 では、デバイスはハンドシェイク後にクライアント証明書を拒否し、最初のパケットの読み取り時にエラーまたは切断として現れます。
                    // クライアントは、adbd がキーを認識しないのか、それとも別のサービスがハンドシェイクを受信したのかを判断できません。再試行するかどうかは呼び出し側次第です (AdbConnectPort を参照)
                    val cnxn = try {
                        AdbMessage.read(input)
                    } catch (e: SSLException) {
                        throw AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "TLS rejected our certificate (key not paired, or another service took the handshake)", e)
                    } catch (e: IOException) {
                        if (e is java.net.SocketTimeoutException) throw AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "no CNXN from device", e)
                        null
                    } ?: throw AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "device closed the connection after TLS (key not paired?)")
                    if (cnxn.command != AdbMessage.CNXN) throw AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "expected CNXN, got $cnxn")
                    finish(ssl, input, ssl.outputStream, cnxn, timeoutMs)
                }
                AdbMessage.AUTH -> throw AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "device asks for legacy RSA auth (not a wireless-debugging port?)")
                else -> throw AdbConnectionException(AdbConnectionException.Reason.PROTOCOL, "unexpected $first")
            }
        }

        private fun finish(socket: Socket, input: DataInputStream, out: OutputStream, cnxn: AdbMessage, timeoutMs: Int): AdbConnection {
            socket.soTimeout = 0
            val banner = cnxn.data.toString(Charsets.UTF_8).trimEnd('\u0000')
            val maxData = if (cnxn.arg1 in 1..AdbMessage.MAX_PAYLOAD) cnxn.arg1 else 4096
            return AdbConnection(socket, input, out, banner, cnxn.arg0, maxData, timeoutMs)
        }
    }
}
