package com.wangzan101.mika.core.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** adbd mDNS 経由でアドバタイズされるサービス タイプ。 */
object AdbServiceTypes {
    /** 「ペアリングコードを使用してペアリング」ダイアログが開いている場合のみアナウンスされます。 ワイヤレス デバッグがオンになると、 */
    const val PAIRING = "_adb-tls-pairing._tcp"
    /** がアナウンスされます。ポートは、ワイヤレス デバッグがオンまたはオフになるたびに変更されます。 */
    const val CONNECT = "_adb-tls-connect._tcp"
}

/** は、ローカル ネットワーク上で adbd によって公開されたサービス ポートを探します。見つからない場合 (タイムアウト)、null が返されます。 */
/**
 * このマシンの adbd によって公開されたサービス ポートを見つけます。同じサービスに対して複数のエントリが見つかる可能性があります (ワイヤレス デバッグをオフにしてから再度オンにした後、古いエントリが mDNS に残り、新しいエントリの名前がシリアル番号で変更される可能性があります)。
 * すべてを渡します。最初に見つかったものが最初になり、発信者はそれらを 1 つずつ試します。
 */
fun interface AdbServiceFinder {
    suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int>
}

class AdbPairingException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** 目标不是本机自己的地址:建 Socket 前拒绝,不能按网络故障重试。 */
        NOT_LOCAL,
        CONNECT_FAILED, TLS_FAILED, WRONG_CODE, PROTOCOL,
    }
}

/** ペアリングが成功したときにデバイスによって報告される ID (通常は type=1 のデバイス GUID)。 */
class AdbPeerInfo(val type: Int, val data: String)

/**
 * ワイヤレス デバッグ (クライアント) のペアリング コード。プロセス:
 * TLS 1.3 → 64 バイトのキー (「adb-label\0」) をエクスポート → パスワード = ペアリング コード + エクスポート キー → SPAKE2 メッセージを交換 →
 * は、ネゴシエートされた AES-GCM を使用して 8192 バイトの PeerInfo を交換します (adb 公開キーを送信し、デバイスは GUID を返します)。
 * 各パケットの前には 6 バイトのヘッダーがあります: バージョン (1)、タイプ (0=SPAKE2、1=PEER_INFO)、ペイロード長 (ビッグエンディアン uint32)。
 */
class AdbPairing(private val tls: AdbTls) {
    /** 与本机设备配对;校验结果同时就是实际连接地址,不再解析原 host。 */
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000): AdbPeerInfo {
        val target = AdbHosts.ownSocketAddress(host, port) ?: throw AdbPairingException(
            AdbPairingException.Reason.NOT_LOCAL,
            "refusing to pair with $host: adb is only reachable on this device",
        )
        val socket = try {
            tls.connect(target, timeoutMs)
        } catch (e: SSLException) {
            throw AdbPairingException(AdbPairingException.Reason.TLS_FAILED, "TLS handshake failed", e)
        } catch (e: IOException) {
            throw AdbPairingException(AdbPairingException.Reason.CONNECT_FAILED, "cannot connect to $host:$port", e)
        }
        socket.use { return exchange(it, code) }
    }

    private fun exchange(socket: SSLSocket, code: String): AdbPeerInfo {
        val exported = tls.exportKeyingMaterial(socket, EXPORT_LABEL, EXPORT_LENGTH)
        val auth = PairingAuth.client(code.toByteArray(Charsets.UTF_8) + exported)
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        writePacket(output, TYPE_SPAKE2, auth.message)
        val theirMsg = readPacket(input, TYPE_SPAKE2) ?: throw protocol("connection closed before key exchange")
        try {
            auth.initCipher(theirMsg)
        } catch (e: IllegalArgumentException) {
            throw protocol("bad key exchange message: ${e.message}")
        }

        val info = ByteArray(PEER_INFO_SIZE)
        info[0] = PEER_RSA_PUB_KEY
        val key = tls.identity.adbPublicKey.toByteArray(Charsets.UTF_8)
        check(key.size < PEER_INFO_SIZE - 1)
        System.arraycopy(key, 0, info, 1, key.size)
        writePacket(output, TYPE_PEER_INFO, auth.encrypt(info))

        // ペアリング コードが正しくない場合、デバイスは PeerInfo のロックを解除できず、直接切断されます。
        val sealed = try {
            readPacket(input, TYPE_PEER_INFO)
        } catch (e: IOException) {
            if (e is AdbPairingException) throw e
            null
        } ?: throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "device closed the connection (wrong pairing code?)")
        val plain = try {
            auth.decrypt(sealed)
        } catch (e: AEADBadTagException) {
            throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "cannot decrypt device info (wrong pairing code?)", e)
        }
        if (plain.size != PEER_INFO_SIZE) throw protocol("peer info has ${plain.size} bytes")
        var end = 1
        while (end < plain.size && plain[end] != 0.toByte()) end++
        return AdbPeerInfo(plain[0].toInt() and 0xff, String(plain, 1, end - 1, Charsets.UTF_8))
    }

    private fun protocol(msg: String) = AdbPairingException(AdbPairingException.Reason.PROTOCOL, msg)

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_LENGTH = 64
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1

        fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).put(VERSION).put(type).putInt(payload.size).array()
            out.write(header + payload)
            out.flush()
        }

        /** パケットを読み取り、タイプを確認します。相手はパケット ヘッダーの前に切断し、null を返します。 */
        fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray? {
            val header = ByteArray(6)
            try {
                input.readFully(header)
            } catch (e: EOFException) {
                return null
            }
            val bb = ByteBuffer.wrap(header)
            val version = bb.get()
            val type = bb.get()
            val len = bb.getInt()
            if (version != VERSION) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unsupported pairing version $version")
            if (type != expectedType) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unexpected packet type $type")
            if (len <= 0 || len > MAX_PAYLOAD) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "bad payload size $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}
