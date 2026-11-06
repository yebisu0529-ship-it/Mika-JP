package com.wangzan101.mika.settings

import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.PairingChannelHealth

enum class AccessibilityState {
    Off,
    /** システム設定でサービスがオンになっていますが、サービスが実行されていません（アプリの強制停止後によく発生します）。オフにしてから再度オンにする必要があります。 */
    EnabledNotRunning,
    On,
}

data class SystemStatus(
    val sdkInt: Int,
    /** 組み込みアシスタントのステータス。 */
    val builtinShell: BuiltinShellStatus,
    val accessibility: AccessibilityState,
    val notificationsAllowed: Boolean,
    /** 「Need You to Handle」通知チャンネルは、ユーザーまたはシステムによって高優先度以下に調整されました (ポップアップせず、音も鳴らない場合があります)。 同じことが、 */
    val attentionLowered: Boolean = false,
    /** 「タスク結果」通知チャネルにも当てはまります。 */
    val resultLowered: Boolean = false,
    /** 「ペアリング」通知チャネル: ここにペアリング コードを入力すると、電源がオフになっている場合、またはポップアップしないように調整されている場合にブート ページにその旨が表示されます。 */
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    /** 3 つのタスクは、チャネルの現在の重要性を通知します。空の場合は、チャネルがまだ確立されていないことを意味します。それに応じて通知ページが表示されます。 */
    val attentionImportance: Int? = null,
    val resultImportance: Int? = null,
    val progressImportance: Int? = null,
    /** システムの「開発者向けオプション」がオンになっています。 */
    val developerOptions: Boolean = false,
    /** システムの「ワイヤレス デバッグ」がオンになっています。 */
    val wirelessDebugging: Boolean = false,
    /** システムには「ワイヤレス デバッグ」項目がありません (この機能はありません)。したがって、ブート ページには「代わりにコンピューターの使用を開始します」と表示されます。 */
    val wirelessDebuggingMissing: Boolean = false,
    /** システムでは「開発者向けオプション」ページを開くことができません: [wirelessDebuggingMissing] と一緒に確立された場合、この環境は携帯電話操作用にセットアップできません。 */
    val developerSettingsMissing: Boolean = false,
    /** システムの「USB デバッグ」がオンになっています: Wi-Fi アシスタントはオンになっても停止しません。 */
    val usbDebugging: Boolean = true,
    /** root でのヘルパー起動が明示的にオンで、現在利用可能。ウォッチドッグは Wi-Fi やワイヤレスデバッグに依存せず su で起動する。 */
    val rootStart: Boolean = false,
    /** Android 13 以降では、ストア以外から入れたアプリは制限付き設定の許可が必要になり、ユーザー補助を有効にできない場合がある。 */
    val restrictedSettings: Boolean = false,
    /** アシスタントの停止後に再び起動するために必要なもの: USB ブート コマンドのみが使用され、ワイヤレス デバッグはどこでもブートされません。コマンドを再度実行するよう明確に指示してください。 */
    val helperRestart: HelperRestart = HelperRestart.None,
    /** ユーザーは起動後に一度もロックを解除していません ([android.os.UserManager.isUserUnlocked] が false): 現時点ではアクセシビリティ サービスをバインドできません。 */
    val userLocked: Boolean = false,
)

fun SystemStatus.notificationOverview() = NotificationOverview.of(
    notificationsAllowed, attentionImportance, resultImportance, progressImportance,
)

sealed interface Problem {
    /** Android 12 以下では、Mika の携帯電話操作機能は有効になりません。 */
    data object SystemTooOld : Problem
    /** 内蔵アシスタントが実行されていません (設定なし、ワイヤレス デバッグがオフ、接続中、エラー)。 */
    data class PhoneHelper(val status: BuiltinShellStatus) : Problem
    /** [userLocked] 为真时没在运行是因为开机后还没解锁过;[restrictedSettings] 为真时系统可能阻止绑定。 */
    data class Accessibility(val state: AccessibilityState, val userLocked: Boolean = false, val restrictedSettings: Boolean = false) : Problem
    data object NoModel : Problem
    /** はタスクをブロックしませんが、進行状況と「必要です」リマインダーが表示されません。 */
    data object NotificationsOff : Problem
    /** 「処理が必要」(引き継ぎ、質問、確認) または「タスクの結果」通知が拒否され、ポップアップせず、表示されない場合があります。 [attention] と [result] がどちらであるかを説明します。 */
    data class AttentionLowered(val attention: Boolean, val result: Boolean) : Problem
    /** はタスクをブロックしません。Android 12 および 13 の仮想画面には独立したフォーカスがなく、バックグラウンド操作によりメイン画面の入力フォーカスが一時的に失われる可能性があります。 */
    data object FocusMayLeak : Problem
}

/** タスクを開始する前に確認してください。インターフェースはそれに応じてプロンプトを表示し、タスクの入り口はそれに応じてブロックされます。 */
object Prerequisites {
    /** モバイル アプリ操作のための最小バージョン。チャット、Web、サンドボックスにはこの機能は必要ありません。 */
    const val MIN_SDK = 31
    /** Android 14 以降、仮想画面は独自のフォーカスを持つことができ、メイン画面には影響しなくなりました。 */
    const val OWN_FOCUS_SDK = 34
    private fun isWarning(p: Problem) = p == Problem.NotificationsOff || p == Problem.FocusMayLeak || p is Problem.AttentionLowered

    data class Check(val problems: List<Problem>) {
        /** タスクのブロックに関する問題。携帯電話が動作するシステムのバージョン、内蔵アシスタント、アクセシビリティは、モバイル アプリを操作する場合にのみ必要です。 */
        val blockers: List<Problem> get() = problems.filter { !isWarning(it) && !phoneOnly(it) }
        val canStartTask: Boolean get() = blockers.isEmpty()
        /** はモバイルアプリを操作できます。 */
        val phoneReady: Boolean get() = problems.none(::phoneOnly)
    }

    private fun phoneOnly(p: Problem) = p is Problem.SystemTooOld || p is Problem.PhoneHelper || p is Problem.Accessibility

    fun check(status: SystemStatus, hasMainModel: Boolean): Check = Check(
        buildList {
            if (status.sdkInt < MIN_SDK) add(Problem.SystemTooOld)
            // Android 12 以下の携帯電話機能は利用できません。これは SystemTooOld によって説明されています
            if (status.sdkInt >= MIN_SDK && status.builtinShell != BuiltinShellStatus.Running) {
                add(Problem.PhoneHelper(status.builtinShell))
            }
            if (status.sdkInt >= MIN_SDK && status.accessibility != AccessibilityState.On) {
                add(Problem.Accessibility(status.accessibility, status.userLocked, status.restrictedSettings))
            }
            if (!hasMainModel) add(Problem.NoModel)
            if (!status.notificationsAllowed) add(Problem.NotificationsOff)
            else if (status.attentionLowered || status.resultLowered) add(Problem.AttentionLowered(status.attentionLowered, status.resultLowered))
            if (status.sdkInt in MIN_SDK until OWN_FOCUS_SDK) add(Problem.FocusMayLeak)
        },
    )
}
