package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.StateFlow

/**
 * 内蔵ヘルパーの現在の状態。端末のワイヤレスデバッグ、PC、suからshellとしての起動を要求する。
 * 画面・設定・タスク入口はこの状態を参照し、各接続経路と監視処理が結果を書き込む。
 */
sealed interface BuiltinShellStatus {
    /** はペアリングされていません (またはペアリングが解除されています)。ブート プロセスを実行する必要があります。 */
    data object NotPaired : BuiltinShellStatus

    /** はペアリングされていますが、ワイヤレス デバッグがオフになっています。オンにすると自動的に接続されます。 */
    data object WirelessDebuggingOff : BuiltinShellStatus

    /** はアシスタントをペアリング、接続、または起動しています。 */
    data object Connecting : BuiltinShellStatus

    data object Running : BuiltinShellStatus

    /** エラー: [reason] それがどのカテゴリであるかを説明します。[fix] はワンクリック修復で、どのパスを使用する必要があります。 */
    data class Failed(val reason: BuiltinShellFailure, val fix: BuiltinShellFix = reason.defaultFix) : BuiltinShellStatus
}

/** 失敗したカテゴリ。コピーライティングはインターフェースレベルで行われます。ここでは、「ユーザーが行うべきこと」のさまざまな状況のみが区別されます。 */
enum class BuiltinShellFailure(val defaultFix: BuiltinShellFix, val duringPairing: Boolean) {
    /** ペアリング コードが間違っているか、有効期限が切れています。 */
    WrongCode(BuiltinShellFix.RePair, duringPairing = true),
    /** コードを入力する前にペアリング ウィンドウが閉じられました (ペアリング ポートが見つかりませんでした)。 */
    PairingWindowClosed(BuiltinShellFix.RePair, duringPairing = true),
    /** は Wi-Fi に接続されていません: ワイヤレス デバッグをオンにできないか、接続できません。 */
    NoWifi(BuiltinShellFix.OpenWifi, duringPairing = false),
    /** ミカはユーザーのワイヤレス デバッグをオンにしましたが、システムはすぐに再びオフにしました。この Wi-Fi はまだワイヤレス デバッグに許可されていないため、ユーザーはオンにして許可する必要があります。 */
    WifiNotAllowed(BuiltinShellFix.OpenWirelessDebugging, duringPairing = false),
    /** は本機の無線デバッグに接続できません。 */
    ConnectFailed(BuiltinShellFix.Retry, duringPairing = false),
    /** ADB 目标不是本机自己的地址:调用方错误,不是会自己恢复的网络故障。 */
    NotLocal(BuiltinShellFix.Retry, duringPairing = false),
    /** ペアの承認が認識されません (長期間使用されなかった場合、またはユーザーがシステムで削除した場合にシステムによって取り消されました)。 */
    AuthorizationRevoked(BuiltinShellFix.RePair, duringPairing = false),
    /** ペアリングに使用されたキーは保存されていますが、読み取ることができません。デバイスはそのキーのみを認識し、静かに変更することはできません。再度ペアリングする必要があります（ペアリング時には新しいキーが必要です）。 */
    KeyUnreadable(BuiltinShellFix.RePair, duringPairing = false),
    /** は接続されていますが、アシスタントの起動またはハンドオーバーに失敗します。 */
    HelperStartFailed(BuiltinShellFix.Retry, duringPairing = false),
    /**
     * USB 起動コマンドによって起動されたアシスタントが停止しました (主に電話機が再起動されたため)、ワイヤレス デバッグ用にペアリングされていませんでした。ミカは自力で起き上がることができませんでした。
     * 起動コマンドを再度実行します。ワイヤレス デバッグについては言及しない: 一部の携帯電話では、ワイヤレス デバッグがまったく利用できません。
     */
    UsbHelperStopped(BuiltinShellFix.RunUsbCommand, duringPairing = false),
    /** root経由の起動がsuに拒否された。自動で繰り返さず、ユーザーの再試行を待つ。 */
    RootDenied(BuiltinShellFix.Retry, duringPairing = false),
    /** suから応答がなかった。ユーザーが再試行するまで待つ。 */
    RootNoAnswer(BuiltinShellFix.Retry, duringPairing = false),
    /** root起動を有効にしたがsuが見つからない。設定を切るか、別の起動方法を使う。 */
    RootUnavailable(BuiltinShellFix.RePair, duringPairing = false),
    /** suで必要なshellのUID/GIDに切り替わらない場合。ワイヤレスデバッグかPCを使う。 */
    RootNoShellUid(BuiltinShellFix.RePair, duringPairing = false),
    Unknown(BuiltinShellFix.Retry, duringPairing = false),
}

enum class BuiltinShellFix {
    /** ペアリングしたキーで再度接続します。 */
    Retry,
    /** ペアリング手順を再度実行します。 */
    RePair,
    /** システムの Wi-Fi 設定に移動します。 */
    OpenWifi,
    /** 開発者向けオプションに移動し、自分でワイヤレス デバッグをオンにします。 */
    OpenWirelessDebugging,
    /** USB ブート コマンドを再度実行します (インターフェイスにコマンドとコピー ボタンが表示されます)。 */
    RunUsbCommand,
}

/**
 * アシスタントが停止後に再起動するために何を依存するかは、前回の起動方法によって異なります。すべては、停止時にユーザーをどこにリダイレクトするか、成功ページと設定ページについて何を言うかによって異なります。
 */
enum class HelperRestart {
    /** はまだ設定されていません。 */
    None,
    /** はワイヤレス デバッグとペアになっています。ミカは自分でもう一度試してみました。電話機の再起動後にワイヤレス デバッグをオンにするだけです (ユーザーに対してワイヤレス デバッグをオンにできる場合は、この手順も必要ありません)。 */
    Wireless,
    /**
     * は USB 起動コマンドでのみ起動されており、ワイヤレス デバッグ用にペアリングされていません。Mika は単独で起動できず、停止した場合 (たとえば、電話機の再起動後)、コマンドを再度実行する必要があります。
     * 一部の携帯電話ではワイヤレス デバッグが利用できず、このルートしか使用できないため、現時点ではワイヤレス デバッグを有効にするプロンプトは表示されません。
     */
    UsbCommand,
    /** 明示的に有効化されたroot起動を使って再起動する。ペアリングやPCを使わず、端末再起動後も復旧を試みる。 */
    Root,
}

/** [ペアリング コードを使用してデバイスをペアリング] ウィンドウで公開されたペアリング ポート (ローカル mDNS) の結果を見つけます。 */
sealed interface PairingDiscovery {
    data object Idle : PairingDiscovery
    data object Searching : PairingDiscovery
    data class Found(val port: Int) : PairingDiscovery
}

/**
 * インターフェイスへの組み込みアシスタント インターフェイス。ペアリング (mDNS ポート検索、6 桁のコード)、ローカル adbd への接続、アシスタントの起動と監視を担当します。
 * のすべての結果は [status] を通じて反映され、アクション自体は例外をスローしません。
 */
interface BuiltinShellControl {
    val status: StateFlow<BuiltinShellStatus>
    val pairingDiscovery: StateFlow<PairingDiscovery>

    /** はワイヤレス デバッグとペアになっています (Mika はアシスタントを起動し、アシスタントを通じてコマンドを実行できます)。アシスタントは USB 経由でコンピュータに接続されている場合もあるため、アシスタントを実行してもペアリングされたわけではありません。 */
    val paired: Boolean get() = status.value != BuiltinShellStatus.NotPaired

    /** root起動が有効で利用可能な場合、監視処理はsu経由の起動を先に試す。 */
    val rootStart: Boolean get() = false

    /** ヘルパー停止後の再起動経路（[HelperRestart]）。 */
    val restart: HelperRestart get() = when {
        rootStart -> HelperRestart.Root
        status.value == BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) -> HelperRestart.UsbCommand
        paired -> HelperRestart.Wireless
        else -> HelperRestart.None
    }

    /** はペアリング ポートの検索を開始します (ユーザーはペアリング ウィンドウを開きます)。繰り返し呼び出しても問題はありません。まだ探している場合は、この時点からタイマーを再起動してください。 */
    fun startDiscovery()
    fun stopDiscovery()

    /** は 6 桁のペアリング コードでペアリングされます。成功したら、接続してアシスタントを起動します。 [port] が空の場合、見つかったペアのポートが使用されます。 */
    suspend fun startPairing(code: String, port: Int? = null)

    /** ペアリングされたキーで再接続し、アシスタントを起動します。 */
    suspend fun retry()

    /** はペアリング キーを削除し、アシスタントを停止し、ミカに対するアシスタントの承認を取り消します。その後のステータスは [BuiltinShellStatus.NotPaired] です。 */
    suspend fun unpair()
}

/** ペアリングコード: 6 桁。ユーザーはスペース、ダッシュ、または全角の数字を含めることができますが、これらは正規化されます。数値が 6 桁でない場合は、null が返されます。 */
object PairingCode {
    const val LENGTH = 6

    /** の入力方法では、半角、全角、ハイフン、短い横線、長い横線など、さまざまな横線を入力できます。 */
    private const val DASHES = "-－‐‑–—"

    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '０'..'９') '0' + (c - '０') else c }
            .joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }

    /** 手動で入力したポート: 1 ～ 65535。 */
    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
