package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.mcp.McpRpc
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RelayClient
import com.wangzan101.mika.core.remote.RelayGateway
import com.wangzan101.mika.settings.RelaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.device.AndroidDeviceAccess
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** コンピューター接続の現在のステータス: 接続が閉じられているか、どのポートが監視されているか、または開くことができません (ポートが占有されているなど)。 */
sealed interface McpServerState {
    data object Off : McpServerState
    data class Listening(val port: Int) : McpServerState
    data class Failed(val message: String) : McpServerState
}

/**
 * コンピューター接続 (MCP) スイッチとサービス: プロセス全体の 1 つのコピー。 LAN ([settings]) および USB ([usb]) の監視範囲については、[McpModes] を参照してください。
 * リモート ゲートウェイ ([relaySettings]) は、独立した送信チャネルを使用します。 [McpService]の電源を入れると始まるフロントサービスがあり、
 * は、それぞれのスイッ​​チによるローカル監視とアウトバウンド接続を決定します。両方の電源をオフにすると停止します。 3 つのモードのタスクは、[tasks] と同じプロトコル エントリを共有します。
 */
class McpRuntime(
    private val context: Context, val settings: McpSettings, val usb: McpUsbSettings, private val tasks: RemoteTasks,
    val device: AndroidDeviceAccess,
    val relaySettings: RelaySettings,
) {
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Off)
    val state: StateFlow<McpServerState> = _state.asStateFlow()
    @Volatile private var host: RemoteHost? = null
    private var hostListen = McpModes.Listen.Off
    private val endpoints = RemoteEndpoints(tasks, BuildConfig.VERSION_NAME, device = device.access)
    private val relayAccess = RemoteAccess(token = { if (relaySettings.config.value.enabled) settings.token() else null })
    // ゲートウェイ経由で受信するリクエストの場合、画面の機能を確認するには、「ゲートウェイ経由のウェイクとロック解除を許可」をオンにする必要があります。携帯電話のリモート画面の明るさとロック解除の許可をオンにする必要があります。
    private val relay = RelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), { req ->
        relayAccess.handle(req) { endpoints.handle(it, deviceAccess = relaySettings.config.value.deviceAccess) }
    })
    private val relayNetwork = RelayNetwork(context, relay::networkChanged)
    val relayState get() = relay.state
    private val active get() = listen() != McpModes.Listen.Off || relaySettings.config.value.enabled

    /** 最後に接続したクライアントと最後のリクエスト。聞いていないときは空です。 */
    val activity: RemoteActivity get() = if (active) endpoints.activity.value else RemoteActivity()

    /** はペアリング用の資格情報のみを保存するため、ユーザーはリモート アクセスを個別に有効にする必要があります。 */
    suspend fun pairRelay(gateway: String, code: String) {
        check(!relaySettings.config.value.paired) { "Unpair the previous gateway first" }
        val remote = RelayGateway(RelayGateway.parse(gateway), RelayGateway.http())
        val paired = remote.pair(code)
        try { relaySettings.save(paired) }
        catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { remote.unpair(paired.deviceToken) } }
            throw e
        }
    }

    fun setRelayDeviceAccess(on: Boolean) = relaySettings.setDeviceAccess(on)

    fun setRelayEnabled(on: Boolean) {
        if (!on) { relayNetwork.stop(); relay.stop() }
        try { relaySettings.setEnabled(on) } finally { if (!on) apply() }
        if (on) apply()
    }

    suspend fun unpairRelay() {
        setRelayEnabled(false)
        relaySettings.pairing()?.let { RelayGateway(RelayGateway.parse(it.gateway), RelayGateway.http()).unpair(it.deviceToken) }
        relaySettings.clear()
    }

    fun listen(): McpModes.Listen = McpModes.listen(settings.enabled.value, usb.enabled.value)

    /** 設定ページの LAN タイプ スイッチ。 */
    fun setEnabled(on: Boolean) {
        settings.setEnabled(on)
        apply()
    }

    /** 設定ページの USB スイッチ (コンピューターは adb スイッチを使用して、[McpService] と同じアクションを実行します)。 */
    fun setUsbEnabled(on: Boolean) {
        if (on) usb.enable() else usb.disable()
        apply()
    }

    /** スイッチが変更されました: オンのときに新しいスイッチを押すことでサービスを再監視し、オフにするとサービスを停止する方法もあります。 */
    private fun apply() {
        if (active) ensureRunning() else {
            context.stopService(Intent(context, McpService::class.java))
            _state.value = McpServerState.Off
        }
    }

    /**
     * サービスがオンになっているときにサービスを開始する方法があります (すでに実行されている場合は、スイッチを押して再調整するだけです)。フォアグラウンド サービスはバックグラウンドからシステムによって拒否される場合があり、その場合は開始されていないと記録されます。
     * 次回、Mika インターフェースが開かれるまで待ちます。
     */
    fun ensureRunning() {
        if (!active || com.wangzan101.mika.quit.MikaQuit.blocksWork(context)) return
        runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
            .onFailure { e ->
                Log.w(TAG, "could not start computer access service", e)
                _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
    }

    /**
     * は [McpService] によって調整されます。ローカル モニタリングとリモート チャネルを同期します。リスニング範囲が変わらない場合はサービスが再利用され、USB がオンになったときに接続情報が公開されます。
     * ローカル リスニングもリモート チャネルも有効になっていない場合、または開始に失敗した場合は false を返します。
     */
    @Synchronized internal fun sync(): Boolean {
        val want = listen()
        if (host != null && hostListen != want) stopListener()
        if (want == McpModes.Listen.Off) _state.value = McpServerState.Off
        val ok = want != McpModes.Listen.Off && (host != null || startServer(want))
        if (ok && usb.enabled.value) runCatching { usb.publish(host!!.boundPort) }.onFailure { Log.w(TAG, "cannot write USB connection info", it) }
        else usb.unpublish()
        val remoteOn = relaySettings.config.value.enabled
        if (remoteOn) {
            val pairing = relaySettings.pairing()
            if (pairing != null) { relayNetwork.start(); relay.start(pairing) }
            else { relayNetwork.stop(); relay.stop(); relaySettings.setEnabled(false) }
        } else { relayNetwork.stop(); relay.stop() }
        return ok || remoteOn && relaySettings.config.value.enabled
    }

    private fun startServer(want: McpModes.Listen): Boolean {
        val network = want == McpModes.Listen.PrivateNetworks
        val h = RemoteHost(tasks, { if (settings.enabled.value) settings.token() else null }, settings.port, allowAllInterfaces = network,
            serverVersion = BuildConfig.VERSION_NAME, onError = { Log.w(TAG, "computer access server error", it) },
            loopbackToken = usb::token, device = device.access, endpoints = endpoints)
        return try {
            h.start()
            host = h
            hostListen = want
            _state.value = McpServerState.Listening(h.boundPort)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on port ${settings.port}", e)
            _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    @Synchronized internal fun stopServer() {
        relayNetwork.stop()
        relay.stop()
        stopListener()
    }

    private fun stopListener() {
        host?.stop()
        host = null
        hostListen = McpModes.Listen.Off
        if (_state.value is McpServerState.Listening) _state.value = McpServerState.Off
    }

    /** コンピューター用の「USB 経由で接続」スクリプト: 秘密はありません。アプリの起動時に 1 回書き込まれます (外部ストレージが利用できない場合はスキップします)。 */
    fun writeUsbScript() {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, McpUsb.SCRIPT)
        val text = McpUsb.script(
            scriptPath = shortPath(f.absolutePath),
            helperScript = File(dir, HelperHandoff.USB_SCRIPT).absolutePath,
            infoPath = File(dir, McpUsb.INFO_FILE).absolutePath,
            component = "${context.packageName}/${McpService::class.java.name}",
            onAction = McpModes.ACTION_USB_ON, offAction = McpModes.ACTION_USB_OFF, port = settings.port,
        )
        if (runCatching { f.readText() }.getOrNull() == text) return
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** は、コンピューター上で実行するコマンドです (設定ページに表示されるものです)。 */
    fun usbCommand(): String {
        val path = context.getExternalFilesDir(null)?.let { File(it, McpUsb.SCRIPT).absolutePath }
            ?: "/storage/emulated/0/Android/data/${context.packageName}/files/${McpUsb.SCRIPT}"
        return "adb shell sh " + shortPath(path)
    }

    /** トークンの交換: 古いトークンはその場で無効になります。 */
    fun regenerateToken(): String = settings.regenerate()

    companion object {
        private const val TAG = "MikaMcp"

        /** メイン ユーザーの外部ストレージは、シェルでは /sdcard とも呼ばれます。人々に示されるコマンドは短縮形で書かれています (「コンピューターで開始」スクリプトと同じです)。 */
        private fun shortPath(path: String) = HelperHandoff.usbCommand(path).removePrefix("adb shell sh ")

        /** コンピューターが接続できるアドレス: 有効なネットワーク カード上の LAN およびプライベート ネットワークの IPv4 アドレス。 */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && RemoteAccess.privateAddress(it) }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        fun url(address: String, port: Int) = "http://$address:$port${McpRpc.PATH}"

        /** コンピューター上の MCP クライアントの構成フラグメント: アドレスと認証ヘッダー。 */
        fun clientConfig(url: String, token: String): String = """
            {
              "mcpServers": {
                "mika": {
                  "type": "http",
                  "url": "$url",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
    }
}
