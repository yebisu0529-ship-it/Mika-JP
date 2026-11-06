package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機テスト: ミカ画面上のページは、別のプロセスのサービスを通じて別のアプリを開き (バックグラウンドから開くのと同じ)、システムはそれをホーム画面に配置します。
 * をクリックした結果は、開いたことが報告され、Mika の画面に戻り、メイン画面の上部が元の画面に戻ります。
 * どのアプリを開くかは、機器パラメータ SpilTarget (デフォルトのシステム設定) によって指定されます。すでにタスクがある場合は実行されません（タスクを上に移動して画面で破棄すると、そのタスクが失われます）。
 * インストールされているアプリが 2 つある場合、どちらを最初に開くかを尋ねられます。質問は移動されず、ホーム画面に残り、ユーザーはピッカー、onUserScreen、タスクなどを選択できます。
 * 機器パラメータ pickOption が指定されている場合、ユーザーがメイン画面で選択したとき (または元の画面に戻ったとき) をテストし、どれが Mega 画面に戻り、どれがレポートされるかを確認します。
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSpillDeviceTest {
    // はタスク内と同じで、画面のサイズと密度はメイン画面と同じです。移動されたページはサイズ変更により再構築する必要はありません (システム選択ボックスは再構築時に自動的に閉じられます)。
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.wangzan101.mika.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()
    private val target = InstrumentationRegistry.getArguments().getString("spillTarget") ?: SETTINGS

    @After
    fun tearDown() {
        // が戻らない場合は、メイン画面に残っているものをミカの画面に移動し、画面ごと破壊します。
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        // が途中で失敗し、問題がホーム画面に残ります。Return キーを押して閉じます。
        screen.userPick.value?.let { p -> if (main >= 0 && shell.packageOfTask(main) == p.packageName) sh("input keyevent 4") }
        if (main >= 0 && shell.packageOfTask(main) == target) shell.moveTask(main, agent.display.displayId)
        agent.release()
    }

    /** は、機器パラメーターallowExisting=true の場合にも実行されます (そのタスクは、最近のタスクに取り消し線を引くのと同じように、画面上に移動して閉じられます)。 */
    private fun noTaskYet() {
        if (InstrumentationRegistry.getArguments().getString("allowExisting") == "true") return
        assumeFalse("$target already has a task",
            sh("dumpsys activity recents").lines().any { val l = it.trim(); l.startsWith("* Recent") && " A=" in l && l.substringAfter(" A=").substringAfter(':').startsWith(target) })
    }

    private suspend fun spillPage(): com.wangzan101.mika.core.uistate.ScreenSnapshot {
        screen.ensureDisplay()
        sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${SpillTestActivity::class.java.name} --es ${SpillTestActivity.EXTRA_TARGET} $target")
        val s = pollUntil(5_000) { runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open elsewhere" } } }
        assertNotNull("spill page did not come up", s)
        return s!!
    }

    /** コントロール: ミカのアクションなしで、ミカの画面に直接クリックを入力すると、結果がホーム画面に落ちます (これは修復が必要な現象です)。 */
    @Test
    fun withoutTheWatchTheLaunchLandsOnTheMainScreen() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val b = s.elements.first { it.name == "open elsewhere" }
        val vdBefore = shell.topTaskId(vd)
        assertTrue(shell.injectTap(vd, b.centerX, b.centerY))
        val landed = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != mainBefore } }
        println("before: main top $mainBefore -> $landed (${landed?.let { shell.packageOfTask(it) }}), Mika screen top $vdBefore -> ${shell.topTaskId(vd)}")
        assertNotNull("launch did not land on the main screen", landed)
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
    }

    @Test
    fun appOpenedOnTheMainScreenIsPulledBack() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val button = s.elements.first { it.name == "open elsewhere" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        val opened = (r as ActionResult.Ok).opened
        assertNotNull("tap did not report opened: $r", opened)
        val now = screen.snapshot()
        println("spill result: $r; on Mika's screen (${now.packageName}): ${now.elements.filter { it.clickable }.map { it.name }}")
        if (!opened!!.picker) {
            assertTrue("not pulled back: $opened", opened.pulledBack)
            assertEquals(target, shell.packageOfTask(shell.topTaskId(vd)))
            assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
            return@runBlocking
        }
        // システムは、ユーザー画面でどれを開くかを尋ねます。移動したり再度開いたりせず、ホーム画面に留まり、ユーザーが選択するまで待ちます。
        assertTrue("question was moved: $opened", !opened.pulledBack && opened.onUserScreen)
        val chooserTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        assertEquals("question is not on the main screen", opened.packageName, shell.packageOfTask(chooserTask))
        assertNotNull("run is not waiting for the user", screen.userPick.value)
        assertEquals("Mika's screen changed", SpillTestActivity::class.java.name, shell.topActivity(vd))
        // 機器パラメータ pickOption=<数値、0 から始まる> または戻る: ユーザーとしてテストする場合、メイン画面でそのオプションをクリックします (または Return キーを押して質問を閉じます)
        val pick = InstrumentationRegistry.getArguments().getString("pickOption") ?: return@runBlocking
        if (pick == "back") sh("input keyevent 4")
        else {
            val label = r.chooser?.options?.firstOrNull()?.label
            val nodes = ScreenReader.contentWindows(DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY))
                .filter { it.root?.packageName?.toString() == opened.packageName }
                .flatMap { w -> ScreenReader.read(listOf(w), agent.display.width, agent.display.height).entries.map { it.element } }
                .filter { it.clickable && it.name == label }
            val option = nodes.getOrNull(pick.toInt())
            assertNotNull("no option $pick: ${nodes.map { it.name }}", option)
            sh("input tap ${option!!.centerX} ${option.centerY}")
        }
        val settled = screen.awaitUserPick()
        // 選択した項目はユーザーが処理する必要があります: この時点で、ユーザーが [続行] をクリックすると、テストが実行されます。
        if (settled is AndroidScreen.PickResult.NeedsUser) screen.afterUserStep(settled)
        val after = screen.snapshot()
        val top = shell.topTaskId(vd)
        println("after the user ($pick): $settled; opened=${after.opened}; Mika's screen top ${shell.packageOfTask(top)} user ${shell.userOfTask(top)}; " +
            "main top ${shell.topTaskId(Display.DEFAULT_DISPLAY)} (before $mainBefore)")
        if (pick == "back") assertTrue("not reported closed: ${after.opened}", after.opened?.closed == true)
        else {
            assertTrue("not pulled back: ${after.opened}", after.opened?.pulledBack == true && (after.opened?.inProfile != null || after.opened?.byUser == true))
            assertEquals(target, shell.packageOfTask(top))
        }
        assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
    }

    /** 真实任务栈里先回桌面、再打开 App：即使没有语义点击，这条用户导航也不能判成 Agent 外溢。 */
    @Test
    fun returningHomeBeforeOpeningAnAppIsUserNavigation() = runBlocking<Unit> {
        noTaskYet()
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val homeApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        assertTrue("no home handler", homeApps.isNotEmpty())
        val ownPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        sh("am start --display 0 -n $ownPackage/com.wangzan101.mika.ui.MainActivity")
        assertNotNull("Mika did not come to the main screen", pollUntil(3_000) {
            shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) == ownPackage }
        })
        val topBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val since = SystemClock.uptimeMillis()
        shell.watchMainScreen(6_000)
        sh("am start --display 0 -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}")
        assertNotNull("home did not come to the front", pollUntil(3_000) {
            shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) in homeApps }
        })
        val launch = pm.getLaunchIntentForPackage(target)
        assertNotNull("no launcher activity for $target", launch)
        sh("am start --display 0 -n ${launch!!.component!!.flattenToShortString()}")
        assertNotNull("target did not come to the front", pollUntil(3_000) {
            shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) == target }
        })

        val arrivals = arrivalsSince(since)
        assertTrue("home navigation was not recorded", arrivals.any { it.packageName in homeApps })
        val verdicts = MainScreenSpill.decide(arrivals, since, 6_000, topBefore,
            homeApps + InstrumentationRegistry.getInstrumentation().targetContext.packageName, emptyList(), homeApps)
        assertTrue("user-opened app was judged an agent spill: $verdicts", verdicts.none { it.arrival.packageName == target })
    }

    private fun arrivalsSince(since: Long): List<MainScreenSpill.Arrival> {
        val b = shell.mainArrivals(since)
        assertNotNull("no main-screen arrivals", b)
        val tasks = b!!.getIntArray(MainScreenWatch.KEY_TASK)!!
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)!!
        val users = b.getIntArray(MainScreenWatch.KEY_USER)!!
        val at = b.getLongArray(MainScreenWatch.KEY_AT)!!
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)!!
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)!!
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)!!
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)!!
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i])
        }
    }
}
