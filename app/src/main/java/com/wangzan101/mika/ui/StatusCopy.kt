package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.ChannelLevel
import com.wangzan101.mika.settings.ChannelRow
import com.wangzan101.mika.settings.NotificationSummary
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.ui.components.ValueTone

/** 設定ページで直接実行できます。 */
enum class Fix {
    OpenAccessibility, AllowNotifications, AddModel,
    /** 「Need You to Handle」/「Task Result」/「Task Progress」チャンネルの設定ページを直接開き、「Pop up on the screen」をオンにします。アプリケーション通知の一般ページに戻ることができません。 */
    OpenAttentionChannel, OpenResultChannel, OpenProgressChannel,
    /** 「ペアリング」通知チャネル設定ページ: このタイプの通知にはペアリング コードを入力する必要があります。 */
    OpenPairingChannel,
    /** このアプリの「アプリケーション情報」ページ: システムがアクセシビリティをブロックする場合、ここでは制限された設定が許可されます。 */
    OpenAppInfo,
    // 内蔵アシスタント
    OpenPhoneSetup, RepairPhoneHelper, RetryPhoneHelper, UnpairPhoneHelper,
    OpenAboutPhone, OpenDeveloperOptions, OpenUsbDebugging, OpenWirelessDebugging, OpenWifi,
}

/** 上部のバナーのテキスト行と右側のリンク。 */
data class BannerCopy(val title: String, val detail: String, val link: String)

/** 問題をクリックするときの手順: 平易な言葉を使用して、問題が何であるか、およびその方法を明確に説明します。 [fix] は空です。これは、ワンクリック修復がないことを意味します。 */
data class Explanation(val title: String, val body: String, val action: String?, val fix: Fix?)

object StatusCopy {
    /** モデルを経由せずにいくつかのステップを実行するときにカードと通知に表示される内容を言います。 */
    fun progress(p: RunProgress): String = when (p) {
        is RunProgress.Replay -> tr(R.string.progress_replay, p.step, p.total)
        is RunProgress.Batch -> trPlural(R.plurals.progress_batch, p.steps)
        // は別のタスクを待っているだけであり、ユーザーが何もする必要はありません。
        is RunProgress.WaitingForApp -> tr(R.string.progress_waiting_app, p.app)
    }

    /** 支払い受領書の商品、金額、住所。1 行に 1 項目。何もない場合は null になります。カードと通知は共有されます。 */
    fun paymentLines(item: String?, amount: String?, address: String?): String? =
        listOfNotNull(
            item?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_item, it) },
            amount?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_amount, it) },
            address?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }

    /** 任务开工时仍连不上无障碍:已启用和未启用是两种处理,不能都说成没开。 */
    fun accessibilityUnavailable(enabled: Boolean): String =
        tr(if (enabled) R.string.task_a11y_not_running else R.string.task_no_a11y)

    fun accessibilityValue(s: AccessibilityState): Pair<String, ValueTone> = when (s) {
        AccessibilityState.On -> tr(R.string.state_on) to ValueTone.Ok
        AccessibilityState.Off -> tr(R.string.state_off) to ValueTone.Bad
        AccessibilityState.EnabledNotRunning -> tr(R.string.state_not_running) to ValueTone.Bad
    }

    /** 説明する必要はありません。クリックして問題を直接修正してください。 */
    fun directFix(p: Problem): Fix? = when (p) {
        Problem.NotificationsOff -> Fix.AllowNotifications
        is Problem.AttentionLowered -> headsUpFix(p)
        Problem.NoModel -> Fix.AddModel
        // ガイド ページでは各ステップを明確に説明しており、これ以上の説明はありません。
        is Problem.PhoneHelper -> when {
            PhoneSetupCopy.canReconnect(p.status) -> Fix.RetryPhoneHelper
            // この Wi-Fi ではワイヤレス デバッグがまだ許可されていません。自分で開いてください (許可するかどうかを尋ねるメッセージがシステムに表示されます)
            (p.status as? BuiltinShellStatus.Failed)?.fix == BuiltinShellFix.OpenWirelessDebugging -> Fix.OpenWirelessDebugging
            else -> Fix.OpenPhoneSetup
        }
        else -> null
    }

    // ---- 設定 › 通知 ----
    /** 停止後、説明の前に書かれた短い言葉の行です（会話ページはフローティングカードと同じです）。 */
    val STOP_SUMMARIZING: String get() = tr(R.string.stop_summarizing)
    val NOTIFICATIONS: String get() = tr(R.string.notifications_title)
    val PROGRESS_CHANNEL: String get() = tr(R.string.channel_progress)

    fun notificationSummary(s: NotificationSummary): Pair<String, ValueTone> = when (s) {
        NotificationSummary.Normal -> tr(R.string.notif_normal) to ValueTone.Ok
        is NotificationSummary.Lowered -> trPlural(R.plurals.notif_lowered, s.count) to ValueTone.Warn
        NotificationSummary.AllOff -> tr(R.string.state_turned_off) to ValueTone.Bad
    }

    fun channelLevelValue(row: ChannelRow): Pair<String, ValueTone> = when (row.level) {
        ChannelLevel.Off -> tr(R.string.state_turned_off) to if (row.warn) ValueTone.Warn else ValueTone.Plain
        ChannelLevel.HeadsUp -> tr(R.string.notif_heads_up) to ValueTone.Ok
        ChannelLevel.Quiet -> tr(R.string.notif_quiet) to if (row.warn) ValueTone.Warn else ValueTone.Plain
    }

    val NOTIF_APP_SUBTITLE_ON: String get() = tr(R.string.notif_app_on)
    val NOTIF_APP_SUBTITLE_OFF: String get() = tr(R.string.notif_app_off)
    val NOTIF_ATTENTION_WHEN: String get() = tr(R.string.notif_attention_when)
    val NOTIF_RESULT_WHEN: String get() = tr(R.string.notif_result_when)
    val NOTIF_PROGRESS_WHEN: String get() = tr(R.string.notif_progress_when)
    val NOTIF_FOOTER: String get() = tr(R.string.notif_footer)

    val ATTENTION_CHANNEL: String get() = tr(R.string.channel_attention)
    val RESULT_CHANNEL: String get() = tr(R.string.channel_result)
    val HEADS_UP_ACTION: String get() = tr(R.string.heads_up_action)

    /** 拒否された 2 つのチャンネルのうち、「Need You to Handle」が最初に処理されます。 */
    fun headsUpFix(p: Problem.AttentionLowered): Fix = if (p.attention) Fix.OpenAttentionChannel else Fix.OpenResultChannel

    private fun headsUpTitle(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_title, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    private fun headsUpBody(p: Problem.AttentionLowered): String {
        val lead = if (p.attention) tr(R.string.heads_up_lead_attention) else tr(R.string.heads_up_lead_result)
        val also = if (p.attention && p.result) tr(R.string.heads_up_also, RESULT_CHANNEL) else ""
        return tr(R.string.heads_up_body, lead, also, HEADS_UP_ACTION)
    }

    private fun headsUpPath(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_path, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    fun banner(p: Problem): BannerCopy = when (p) {
        Problem.SystemTooOld -> BannerCopy(tr(R.string.banner_too_old), tr(R.string.banner_too_old_detail), tr(R.string.learn_more))
        is Problem.PhoneHelper -> when (val st = p.status) {
            BuiltinShellStatus.NotPaired -> BannerCopy(tr(R.string.banner_not_paired), tr(R.string.banner_not_paired_detail), tr(R.string.chat_go_settings))
            BuiltinShellStatus.WirelessDebuggingOff -> BannerCopy(tr(R.string.banner_wireless_off), tr(R.string.banner_wireless_off_detail), tr(R.string.heads_up_action))
            BuiltinShellStatus.Connecting -> BannerCopy(tr(R.string.banner_connecting), tr(R.string.banner_connecting_detail), tr(R.string.view))
            is BuiltinShellStatus.Failed ->
                if (st.reason == BuiltinShellFailure.NoWifi) BannerCopy(tr(R.string.banner_paused), PhoneSetupCopy.NO_WIFI_LINE, tr(R.string.view))
                // コンピューター起動アシスタントが停止しました: タイトルは真実を物語っており、次の文は再起動する方法を示しています
                else if (st.reason == BuiltinShellFailure.UsbHelperStopped) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.failure(st.reason).body, tr(R.string.view),
                )
                // root 起動の失敗は原因と対処を示し、拒否・応答なしの場合は再試行を提示する。
                else if (st.reason in PhoneSetupCopy.ROOT_FAILURES) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.failure(st.reason).body,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.view),
                )
                else if (st.reason == BuiltinShellFailure.WifiNotAllowed) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.WIFI_NOT_ALLOWED_LINE, tr(R.string.heads_up_action),
                )
                else BannerCopy(
                    tr(R.string.banner_failed), PhoneSetupCopy.failure(st.reason).title,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.fix_it),
                )
            BuiltinShellStatus.Running -> error("not a problem")
        }
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> BannerCopy(tr(R.string.banner_a11y_off), tr(R.string.banner_a11y_off_detail), tr(R.string.turn_on_go))
            // 古いサービスは、起動後にロック解除されるまでバインドできません。ロック解除後に自動的に復元され、ユーザーはサービスをオフにして再度オンにすることはできません。
            AccessibilityState.EnabledNotRunning -> when {
                p.userLocked -> BannerCopy(tr(R.string.banner_a11y_locked), tr(R.string.banner_a11y_locked_detail), tr(R.string.view))
                p.restrictedSettings -> BannerCopy(
                    tr(R.string.banner_a11y_not_running), tr(R.string.banner_a11y_restricted_detail), tr(R.string.fix_it),
                )
                else -> BannerCopy(tr(R.string.banner_a11y_not_running), tr(R.string.banner_a11y_not_running_detail), tr(R.string.fix_it))
            }
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> BannerCopy(tr(R.string.banner_no_model), tr(R.string.banner_no_model_detail), tr(R.string.add))
        Problem.NotificationsOff -> BannerCopy(tr(R.string.banner_notif_off), tr(R.string.banner_notif_off_detail), tr(R.string.allow))
        is Problem.AttentionLowered -> BannerCopy(headsUpTitle(p), headsUpBody(p) + "\n" + headsUpPath(p), HEADS_UP_ACTION)
        Problem.FocusMayLeak -> BannerCopy(tr(R.string.banner_focus), tr(R.string.banner_focus_detail), tr(R.string.learn_more))
    }

    fun explain(p: Problem, androidRelease: String): Explanation = when (p) {
        Problem.SystemTooOld -> Explanation(
            tr(R.string.explain_too_old),
            tr(R.string.explain_too_old_body, androidRelease),
            null, null,
        )
        is Problem.PhoneHelper -> Explanation(
            PhoneSetupCopy.TITLE, PhoneSetupCopy.INTRO, PhoneSetupCopy.DEGRADED_ACTION, Fix.OpenPhoneSetup,
        )
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> Explanation(
                tr(R.string.explain_a11y_off),
                tr(R.string.explain_a11y_off_body),
                tr(R.string.chat_go_settings), Fix.OpenAccessibility,
            )
            AccessibilityState.EnabledNotRunning -> when {
                p.userLocked -> Explanation(
                    tr(R.string.banner_a11y_locked), tr(R.string.banner_a11y_locked_detail), null, null,
                )
                p.restrictedSettings -> Explanation(
                    tr(R.string.banner_a11y_not_running), tr(R.string.explain_a11y_restricted_body),
                    PhoneSetupCopy.APP_INFO, Fix.OpenAppInfo,
                )
                else -> Explanation(
                    tr(R.string.banner_a11y_not_running), tr(R.string.explain_a11y_not_running_body),
                    tr(R.string.chat_go_settings), Fix.OpenAccessibility,
                )
            }
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> Explanation(
            tr(R.string.explain_no_model), tr(R.string.explain_no_model_body),
            tr(R.string.add), Fix.AddModel,
        )
        Problem.FocusMayLeak -> Explanation(
            tr(R.string.explain_focus),
            tr(R.string.explain_focus_body, androidRelease),
            null, null,
        )
        is Problem.AttentionLowered -> Explanation(
            headsUpTitle(p), headsUpBody(p) + "\n\n" + headsUpPath(p), HEADS_UP_ACTION, headsUpFix(p),
        )
        Problem.NotificationsOff -> Explanation(
            tr(R.string.explain_notif), tr(R.string.explain_notif_body),
            tr(R.string.allow), Fix.AllowNotifications,
        )
    }

    /**
     * タスク カード上のコマンドを実行するステップの説明: 「処理中のファイル: <コマンドの最初の 40 ワード>」、およびインターネットに接続されている場合は「インターネットに接続できます」。
     * の実行環境はユーザーには見えず、文字通りのサンドボックスではありません。
     */
    fun sandboxCommand(command: String, network: Boolean): String = sandboxLine(tr(R.string.sandbox_command_prefix), command, 40, network)

    /** スキルスクリプト実行時：「スクリプト実行中」。 */
    fun sandboxScript(network: Boolean): String = tr(R.string.sandbox_script) + if (network) " · $SANDBOX_ONLINE" else ""

    /** コマンドの `mika progress` によって報告される進行状況。 */
    fun sandboxProgress(text: String, network: Boolean): String = sandboxLine("", text, 60, network)

    val SANDBOX_ONLINE: String get() = tr(R.string.sandbox_online)

    /** ツールは、実行環境の準備ができるのを待っています。 */
    val RUNTIME_PREPARING: String get() = tr(R.string.runtime_preparing)

    private fun sandboxLine(prefix: String, text: String, max: Int, network: Boolean): String {
        // は `mika progress` と同じですが、制御文字、ゼロ幅、書き換え方向文字が削除されています。カードに表示されるのは実際のコマンドです。
        val one = com.wangzan101.mika.cap.sandbox.BridgeProtocol.cleanProgress(text.replace(Regex("\\s+"), " "))
        val cut = if (one.codePointCount(0, one.length) > max) one.substring(0, one.offsetByCodePoints(0, max)) + "…" else one
        return prefix + cut + if (network) " · $SANDBOX_ONLINE" else ""
    }
}
