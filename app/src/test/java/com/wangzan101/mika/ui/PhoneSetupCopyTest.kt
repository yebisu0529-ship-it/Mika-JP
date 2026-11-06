package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.RootGrant
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupCopyTest {
    @get:Rule val texts = ChineseTexts()

    /** これらの専門用語は、ユーザーに表示される単語には表示されません (システム設定にもともと存在するオプション名を除く、以下を参照)。 */
    private val jargon = listOf("shell", "端口", "mDNS", "TLS", "Binder", "uid", "プロセス")

    private fun allText(): List<String> = buildList {
        add(PhoneSetupCopy.INTRO)
        SetupStep.entries.forEach { val c = PhoneSetupCopy.step(it); add(c.title); add(c.body); c.action?.let(::add); add(c.done); add(c.brief) }
        BuiltinShellFailure.entries.forEach { val f = PhoneSetupCopy.failure(it); add(f.title); add(f.body) }
        BuiltinShellFix.entries.forEach { add(PhoneSetupCopy.fixLabel(it)) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it)); add(PhoneSetupCopy.statusLine(it, usbDebugging = false)); add(PhoneSetupCopy.statusValue(it).first) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it, rootStart = true)) }
        RootGrant.entries.forEach {
            add(PhoneSetupCopy.rootStartLine(it)); add(PhoneSetupCopy.rootOptionLine(it))
            add(PhoneSetupCopy.rootCommandsLine(true, it)); add(PhoneSetupCopy.rootCommandsLine(false, it))
        }
        addAll(listOf(
            PhoneSetupCopy.ROOT_START, PhoneSetupCopy.ROOT_COMMANDS, PhoneSetupCopy.ROOT_COMMANDS_TITLE, PhoneSetupCopy.ROOT_COMMANDS_WARNING,
            PhoneSetupCopy.ROOT_NOTE, PhoneSetupCopy.ROOT_OPTION, PhoneSetupCopy.ROOT_OPTION_BODY,
        ))
        addAll(
            listOf(
                PhoneSetupCopy.SUCCESS_LINE, PhoneSetupCopy.UNPAIR_BODY, PhoneSetupCopy.SKIP_NOTE, PhoneSetupCopy.DEGRADED_LINE, PhoneSetupCopy.PORT_FOUND,
                PhoneSetupCopy.NEED_NOTIFICATIONS, PhoneSetupCopy.MANUAL_HINT, PhoneSetupCopy.NOTIFY_ENTER_TEXT,
                PhoneSetupCopy.NOTIFY_ENTER_TITLE, PhoneSetupCopy.NOTIFY_ACTION, PhoneSetupCopy.NOTIFY_INPUT_LABEL,
                PhoneSetupCopy.AUTO_READ_HINT, PhoneSetupCopy.ACCESSIBILITY_SKIP, PhoneSetupCopy.NOTIFY_WINDOW_FOUND,
                PhoneSetupCopy.NOTIFY_CODE_INVALID, PhoneSetupCopy.PAIRING_CHANNEL_OFF, PhoneSetupCopy.OPEN_PAIRING_CHANNEL,
                PhoneSetupCopy.PAIRING_LOWERED, PhoneSetupCopy.NOTIFY_DONE_TITLE, PhoneSetupCopy.NOTIFY_DONE_TEXT,
                PhoneSetupCopy.ACCESSIBILITY_SKIPPED, PhoneSetupCopy.APP_INFO, PhoneSetupCopy.TURNED_ON_NEXT,
                PhoneSetupCopy.REVISIT_BACK, PhoneSetupCopy.REVISIT_NOTE, PhoneSetupCopy.CONNECT_PORT_SUBMIT,
            ),
        )
        HelperRestart.entries.forEach { add(PhoneSetupCopy.successNote(it)); add(PhoneSetupCopy.usageNote(it)) }
        suspects.forEach { add(PhoneSetupCopy.suspect(it)); add(PhoneSetupCopy.goBackTo(it.step)) }
        addAll(PhoneSetupCopy.NOTIFY_GUIDE)
        BuiltinShellFailure.entries.forEach { add(PhoneSetupCopy.notifyRetry(it)) }
    }

    private val suspects = listOf(
        SetupSuspect(SetupStep.DeveloperOptions, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = true),
    )

    private val statuses = listOf(
        BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
    ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }

    @Test
    fun connectPortInputAcceptsOnlyValidPorts() {
        assertEquals(41235, ConnectPortInput.parse("41235"))
        assertEquals(41235, ConnectPortInput.parse(" 41 235 "))
        assertEquals(41235, ConnectPortInput.parse("４１２３５"))
        // 「IPアドレスとポート」は全部貼り付けても大丈夫です
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10:41235"))
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10：41235"))
        assertEquals(1, ConnectPortInput.parse("1"))
        assertEquals(65535, ConnectPortInput.parse("65535"))
        for (bad in listOf("", " ", "0", "65536", "70000", "abc", "41a35", "-1", "192.0.2.10:")) assertNull(bad, ConnectPortInput.parse(bad))
    }

    @Test
    fun reconnectOnlyWhenRetryingIsTheFix() {
        val reconnectable = statuses.filter { PhoneSetupCopy.canReconnect(it) }
        assertEquals(
            // root が拒否または応答なしなら、再試行で su に要求し直す。
            listOf(
                BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.NotLocal, BuiltinShellFailure.HelperStartFailed,
                BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer, BuiltinShellFailure.Unknown,
            ),
            reconnectable.map { (it as BuiltinShellStatus.Failed).reason },
        )
        reconnectable.forEach { s ->
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(s)))
        }
        // がペアリングされていない場合は、起動する必要があります。ワイヤレス デバッグがオフになっている場合は、最初にオンにする必要があります。 Wi-Fi がない場合は自動的に回復します。ペアリングに失敗した場合は、再度ペアリングする必要があります。
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Running, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi),
            BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
        ).forEach { assertFalse(PhoneSetupCopy.canReconnect(it)) }
    }

    @Test
    fun bannerAndFixForPhoneHelperProblem() {
        statuses.filter { it != BuiltinShellStatus.Running }.forEach { s ->
            val p = Problem.PhoneHelper(s)
            val fix = when {
                PhoneSetupCopy.canReconnect(s) -> Fix.RetryPhoneHelper
                // この Wi-Fi ではまだワイヤレス デバッグが許可されていません。自分で開いてください。
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed) -> Fix.OpenWirelessDebugging
                else -> Fix.OpenPhoneSetup
            }
            assertEquals(fix, StatusCopy.directFix(p))
            assertEquals(Fix.OpenPhoneSetup, StatusCopy.explain(p, "14").fix)
        }
    }

    @Test
    fun copyIsPlainJapanese() {
        // 接続が失敗すると、ユーザーは「ワイヤレス デバッグ」ページに従ってポートを入力するよう求められます。「ポート」と直接書かれているのは唯一の文です。
        val portAllowed = PhoneSetupCopy.failure(BuiltinShellFailure.ConnectFailed).body
        allText().forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> if (!(j == "端口" && t == portAllowed)) assertFalse("「$j」 in: $t", t.contains(j, ignoreCase = true)) }
            // システム内のオプション名のみに adb が含まれます
            if (t.contains("adb")) assertTrue(t, t.contains("「adb 認証のタイムアウトを無効にする」"))
        }
    }

    @Test
    fun failuresAreDistinctAndActionable() {
        val titles = BuiltinShellFailure.entries.map { PhoneSetupCopy.failure(it).title }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("もう一度ペア設定", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode).fix))
        assertEquals("再試行", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed).fix))
    }

    /** USB コマンドによって開始されたアシスタントが停止しました。設定ページとバナーにはすべて停止していることが表示され、コマンドを実行します。ワイヤレス デバッグにはつながりません (携帯電話によっては、ワイヤレス デバッグがまったくできない場合もあります)。 */
    @Test
    fun usbHelperStoppedSaysHowToStartItAgain() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        assertEquals("スマホのヘルパーが停止しました", PhoneSetupCopy.failure(BuiltinShellFailure.UsbHelperStopped).title)
        val line = PhoneSetupCopy.statusLine(stopped)
        assertTrue(line, line.contains("USB の開始コマンド") && line.contains("もう一度実行"))
        assertFalse(line, line.contains("ワイヤレス デバッグ"))
        assertEquals(BuiltinShellFix.RunUsbCommand, stopped.fix)
        assertEquals("コピー", PhoneSetupCopy.fixLabel(stopped.fix))
        // はもともとコマンドで開始されました。カードには「もう一度実行してください」と表示されましたが、「ワイヤレス デバッグを開けません」とは表示されませんでした。
        val (againTitle, againBody) = PhoneSetupCopy.usbCard(HelperRestart.UsbCommand)
        assertEquals("開始コマンドをもう一度実行", againTitle)
        assertTrue(againBody.contains("再起動"))
        assertEquals(PhoneSetupCopy.USB_TITLE to PhoneSetupCopy.USB_BODY, PhoneSetupCopy.usbCard(HelperRestart.None))
        val banner = StatusCopy.banner(Problem.PhoneHelper(stopped))
        assertEquals("スマホのヘルパーが停止しました", banner.title)
        assertEquals(line, banner.detail)
        assertEquals("このスマホでワイヤレス デバッグを使えませんか？ 代わりにパソコンから開始します", PhoneSetupCopy.USB_TITLE)
        assertEquals("ワイヤレス デバッグが見つかりませんか？", PhoneSetupCopy.USB_ASK)
        assertTrue(PhoneSetupCopy.USB_BODY.contains("USB ケーブル"))
        assertTrue(PhoneSetupCopy.USB_BODY.contains("再起動"))
    }

    /** root 起動の失敗は設定画面とバナーに理由と対処を示し、拒否や応答なしなら再試行を提示する。起動中も状態を正確に表示する。 */
    @Test
    fun rootStartStatusSaysWhyAndWhatToDo() {
        val denied = BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied)
        assertEquals("root の許可が拒否されました", PhoneSetupCopy.failure(BuiltinShellFailure.RootDenied).title)
        assertTrue(PhoneSetupCopy.statusLine(denied).contains("再試行"))
        assertTrue(PhoneSetupCopy.canReconnect(denied))
        val banner = StatusCopy.banner(Problem.PhoneHelper(denied))
        assertEquals("root の許可が拒否されました", banner.title)
        assertEquals(PhoneSetupCopy.RECONNECT, banner.link)
        val gone = BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable)
        assertTrue(PhoneSetupCopy.statusLine(gone).contains("su が見つからなくなりました"))
        assertFalse(PhoneSetupCopy.canReconnect(gone))
        assertEquals("スマホのヘルパーが動いていません。root で起動しています…", PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = true))
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting), PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = false))
        // root 起動は Wi-Fi に依存しないため、USB デバッグがオフでも Wi-Fi 切断の警告を表示しない。
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Running), PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false, rootStart = true))
    }

    /** 各スイッチの説明は su の応答に合わせる。root 起動が拒否または応答なしなら、タップで再要求できる。 */
    @Test
    fun rootSwitchLines() {
        assertEquals(PhoneSetupCopy.ROOT_PENDING, PhoneSetupCopy.rootStartLine(RootGrant.Pending))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Denied).contains("もう一度確認"))
        assertTrue(PhoneSetupCopy.rootStartRetries(on = true, grant = RootGrant.Denied))
        assertTrue(PhoneSetupCopy.rootStartRetries(on = true, grant = RootGrant.NoAnswer))
        assertFalse(PhoneSetupCopy.rootStartRetries(on = false, grant = RootGrant.Denied))
        assertFalse(PhoneSetupCopy.rootStartRetries(on = true, grant = RootGrant.Granted))
        assertEquals(PhoneSetupCopy.ROOT_OPTION_BODY, PhoneSetupCopy.rootOptionLine(RootGrant.Unknown))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Denied).contains("オフのまま"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.NoAnswer).contains("応答がない"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.NoShellUid).contains("root ユーザーで実行できない"))
        assertTrue(PhoneSetupCopy.ROOT_COMMANDS_WARNING.contains("ほかのアプリのデータ"))
        assertTrue(PhoneSetupCopy.ROOT_COMMANDS_WARNING.contains("自律モードでは確認せず実行"))
        assertEquals("Mika に root コマンドの実行を許可", PhoneSetupCopy.ROOT_COMMANDS)
        assertEquals("root でスマホのヘルパーを起動", PhoneSetupCopy.ROOT_START)
        val note = PhoneSetupCopy.ROOT_NOTE
        assertTrue(note, note.contains("シェルユーザーとしての起動"))
        assertTrue(note, note.contains("root コマンドの実行許可は別の設定"))
        assertTrue(note, note.contains("root 管理環境によっては利用できない"))
    }

    /** 「用 root 启动」用不了时,设置页、横幅都直说原因和怎么办;拒绝、没回应的给重试。正在用 root 启动时照实说。 */
    @Test
    fun rootStartStatusSaysWhyAndWhatToDo() {
        val denied = BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied)
        assertEquals("root 授权被拒绝了", PhoneSetupCopy.failure(BuiltinShellFailure.RootDenied).title)
        assertTrue(PhoneSetupCopy.statusLine(denied).contains("重试"))
        assertTrue(PhoneSetupCopy.canReconnect(denied))
        val banner = StatusCopy.banner(Problem.PhoneHelper(denied))
        assertEquals("root 授权被拒绝了", banner.title)
        assertEquals(PhoneSetupCopy.RECONNECT, banner.link)
        val gone = BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable)
        assertTrue(PhoneSetupCopy.statusLine(gone).contains("找不到 su"))
        assertFalse(PhoneSetupCopy.canReconnect(gone))
        assertEquals("手机助手没在运行,正在用 root 启动…", PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = true))
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting), PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = false))
        // 经 root 起的不靠 Wi-Fi:USB 调试关着也不提醒断 Wi-Fi 会停
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Running), PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false, rootStart = true))
    }

    /** 自动启动没有开关:首次明确授权,之后自动;拒绝和没回应都能亲手重试。Root 命令仍默认关闭。 */
    @Test
    fun rootLinesFollowAuthorization() {
        assertEquals(PhoneSetupCopy.ROOT_PENDING, PhoneSetupCopy.rootStartLine(RootGrant.Pending))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Unknown).contains("点这里授权"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Granted).contains("已授权"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Denied).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.NoAnswer).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootStatusLine(RootGrant.Unknown).contains("检测到 su"))
        assertTrue(PhoneSetupCopy.rootStatusLine(RootGrant.Granted).contains("已授权"))
        assertFalse(PhoneSetupCopy.rootStatusLine(RootGrant.Denied).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Unknown).contains("默认关闭"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(true, RootGrant.Granted).contains("已开启"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Denied).contains("保持关闭"))
        assertTrue(PhoneSetupCopy.rootEntryLine(false).contains("已关闭"))
        assertTrue(PhoneSetupCopy.rootEntryLine(true).contains("已开启"))
        assertTrue(PhoneSetupCopy.ROOT_COMMANDS_WARNING.contains("其他 App 的数据"))
        assertEquals("允许 Mika 执行 root 命令", PhoneSetupCopy.ROOT_COMMANDS)
        assertEquals("用 root 启动手机助手", PhoneSetupCopy.ROOT_START)
    }

    /** USB 启动不承诺拔线后一定能用:成功页、设置页和重跑卡片都给出通用恢复办法。 */
    @Test
    fun usbCopyDoesNotPromiseUnplugSurvival() {
        listOf(
            PhoneSetupCopy.successNote(HelperRestart.UsbCommand),
            PhoneSetupCopy.usageNote(HelperRestart.UsbCommand),
            PhoneSetupCopy.usbCard(HelperRestart.UsbCommand).second,
        ).forEach { text ->
            assertTrue(text, text.contains("多数手机"))
            assertTrue(text, text.contains("拔掉数据线"))
            assertTrue(text, text.contains("再运行一次"))
            assertFalse(text, text.contains("拔掉数据线也会继续运行"))
        }
        // 无线调试路径的提示不受 USB 恢复说明影响
        assertFalse(PhoneSetupCopy.successNote(HelperRestart.Wireless).contains("拔掉数据线"))
        assertFalse(PhoneSetupCopy.usageNote(HelperRestart.Wireless).contains("拔掉数据线"))
    }
}
