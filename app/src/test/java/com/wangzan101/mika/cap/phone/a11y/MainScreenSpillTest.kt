package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.InUse
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val launchers = setOf("com.home")
    private val home = launchers + "com.wangzan101.mika"

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = since + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, standard: Boolean = true, now: Int = MainScreenSpill.MAIN, user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(vararg a: MainScreenSpill.Arrival, topBefore: Int = 3, taps: List<MainScreenSpill.Tap> = emptyList()) =
        MainScreenSpill.decide(a.toList(), since, window, topBefore, home, taps, launchers)

    @Test fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test fun backgroundTaskOnTheMainScreenBroughtToFrontIsPulledBackToo() {
        // システムは、新しいタスクを作成せずに、そのアプリの既存のバックグラウンド タスクをフォアグラウンドに移動します。
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test fun launcherMikaAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "com.wangzan101.mika")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test fun taskMovedFromAnotherDisplayIsATakeoverNotASpill() {
        // (このタスクまたは他のタスク) の引き継ぎとは、タスクをミカの画面からホーム画面に移動することです
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        val other = MainScreenSpill.Tap(since + 400, "com.other")
        assertTrue(decide(arrival(at = since + 900), taps = listOf(other)).isEmpty())
        // アクション前のクリックはカウントされません
        assertEquals(1, decide(arrival(at = since + 900), taps = listOf(MainScreenSpill.Tap(since - 100, "com.other"))).size)
    }

    @Test fun systemTouchWithoutASemanticClickKeepsTheUsersAppOnTheMainScreen() {
        // 系统若投递触摸事件，它是控件点击之外的补充证据
        assertTrue(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertFalse(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        val touch = MainScreenSpill.Tap(since + 400, null)
        assertTrue(decide(arrival(at = since + 900), taps = listOf(touch)).isEmpty())
    }

    @Test fun returningHomeBeforeOpeningAnAppKeepsTheUsersAppOnTheMainScreen() {
        val desktop = arrival(task = 2, pkg = "com.home", at = since + 200, from = MainScreenSpill.MAIN)
        val opened = arrival(at = since + 900)
        // 桌面和通知栏入口不发控件点击时，先有回桌面的主屏导航也足以证明是用户入口
        assertTrue(decide(desktop, opened).isEmpty())
        // Mika 自己的主屏页面不是桌面，不能把 Agent 的真实外溢当成用户操作
        val mika = arrival(task = 2, pkg = "com.wangzan101.mika", at = since + 200, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(opened)), decide(mika, opened))
    }

    @Test fun userTappingThePopupItselfLeavesItThere() {
        // ポップアップ ウィンドウが表示された後、ユーザーがそれをクリックします (画面で確認します)。ユーザーはそれを使用しており、移動しません。
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.chat"))))
        // が別のアプリのウィンドウをクリックしました。はっきりとはわからないので、通常どおり元に戻します。
        assertEquals(1, decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.other"))).size)
    }

    @Test fun previousTopComingBackIsNotASpill() {
        // 出てきたものを外すと、また上のものが露出しました。
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test fun systemQuestionIsLeftForTheUserAndNothingElseIs() {
        val leave = MainScreenSpill.Handling.LeaveForUser
        val move = MainScreenSpill.Handling.Move
        // システム セレクターまたはシステム パッケージ ウィンドウのオプションは、アプリの 2 つのコピーをインストールするのと同等です。選択はユーザーに任されており、移動したり再度開いたりすることはありません。
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = true, optionsMatch = false))
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = true))
        // 共通のシステム ページとシステム設定などの共通アプリ: 戻る
        assertEquals(move, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = false))
        assertEquals(move, MainScreenSpill.handling(systemPackage = false, systemPicker = false, optionsMatch = true))
    }

    @Test fun appPickedByTheUserIsWhatCameUpAfterTheQuestionClosed() {
        val gone = since + 20_000
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val picked = arrival(task = 9, at = gone - 200)
        val pick = { list: List<MainScreenSpill.Arrival> -> MainScreenSpill.picked(list, 8, gone, 1_000, 3_000, 3, home) }
        assertEquals(picked, pick(listOf(chooser, picked)))
        // ユーザーがオプションをクリックしただけ (その後、選択した) 場合もカウントされます。
        assertEquals(picked, MainScreenSpill.picked(listOf(picked), 8, gone, 1_000, 3_000, 3, home))
        // 選択ボックスを閉じた後に公開された元のものはカウントされません: ユーザーが問題を閉じました
        assertEquals(null, pick(listOf(chooser, arrival(task = 3, at = gone, from = MainScreenSpill.MAIN))))
        // ずっと前にポップアップした選択ボックスが選択されていませんでした。
        assertEquals(null, pick(listOf(arrival(task = 9, at = gone - 5_000))))
        // もともとホーム画面の背景にあったアプリが前面に引っ張られました: それが選択されたアプリです
        val existing = arrival(task = 12, at = gone + 400, from = MainScreenSpill.MAIN, user = 128)
        assertEquals(existing, pick(listOf(chooser, existing)))
    }

    @Test fun pickedAppNeedsTheUserForAPasswordOrASecureUnreadablePage() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        // ツリーから読み取れないアプリは、ユーザーが処理する必要はなく、通常どおり元に戻されます。
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))
    }

    @Test fun accountServiceWithoutLauncherIsAUserStep() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        // システムのアカウント ログイン サービス: 認証子は登録されており、デスクトップ アイコンはなく、アカウントの追加はホーム画面に表示され、確認コード ページが画面全体に表示され、パスワード ボックスはユーザーに残されません。
        assertTrue(MainScreenSpill.authenticatorUi("com.example.accounts", auth, hasLauncher = false))
    }

    @Test fun authenticatorAppWithLauncherIsMovedBackAsBefore() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        // チャット アプリは連絡先を同期するための認証システムを登録していますが、デスクトップ アイコンがあります。つまり、会話と共有ページがホーム画面にポップアップ表示され、認証システムに取り残されることなく、通常どおり元に戻ります。
        assertFalse(MainScreenSpill.authenticatorUi("com.example.chat", auth, hasLauncher = true))
        // は認証パッケージではないため、デスクトップ アイコンがない場合はカウントされません。
        assertFalse(MainScreenSpill.authenticatorUi("com.example.helper", auth, hasLauncher = false))
    }

    @Test fun oneVerdictPerTaskInOrder() {
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(chooser), PullBack(again)), decide(again, first, chooser))
    }
}
