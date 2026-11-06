package com.wangzan101.mika.core.adb

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** TLS エクスポート キー (RFC 8446 §7.5)。 JDK にはパブリック インターフェイスがなく、Android 上の `SSLSockets.exportKeyingMaterial` プラットフォームによって提供されます。 */
fun interface KeyingMaterialExporter {
    fun export(socket: SSLSocket, label: String, length: Int): ByteArray
}

/**
 * adb は TLS 1.3 を使用します: 双方が自己署名証明書を提示し、互いの証明書チェーンを検証しません。 ペアリングの際、
 * の ID は SPAKE2 によってエクスポートされたキーにバインドされます。接続時に、デバイスは証明書内の公開キーを比較します。この端末自身のアドレスにのみ接続します。
 */
class AdbTls(
    val identity: AdbIdentity,
    private val exporter: KeyingMaterialExporter,
    private val provider: Provider? = null,
) {
    val context: SSLContext by lazy {
        val ctx = if (provider == null) SSLContext.getInstance("TLS") else SSLContext.getInstance("TLS", provider)
        ctx.init(arrayOf(FixedKeyManager(identity.keyPair.private, identity.certificate)), arrayOf(TrustAll), null)
        ctx
    }

    /** 用调用方已核对的 [target] 新建 TCP 连接并完成 TLS 握手;不重新解析主机名。 */
    fun connect(target: InetSocketAddress, timeoutMs: Int): SSLSocket {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(target, timeoutMs)
            plain.soTimeout = timeoutMs
        } catch (e: Exception) {
            plain.close()
            throw e
        }
        return wrap(plain, target, client = true)
    }

    /** 在已有连接上做 TLS 握手;[client] = false 只给测试里的假服务端用。 */
    fun wrap(plain: Socket, target: InetSocketAddress, client: Boolean): SSLSocket {
        val host = target.address?.hostAddress ?: throw IllegalArgumentException("ADB address must be resolved")
        val s = context.socketFactory.createSocket(plain, host, target.port, true) as SSLSocket
        try {
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.useClientMode = client
            if (!client) s.needClientAuth = true
            s.startHandshake()
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray = exporter.export(socket, label, length)

    private class FixedKeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(cert)
        override fun getPrivateKey(alias: String?) = key
    }

    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ALIAS = "adb"
    }
}
