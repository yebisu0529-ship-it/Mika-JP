package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.AppCopies.User
import com.wangzan101.mika.core.uistate.AppCopies.Verdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCopiesTest {
    private val secondary = "android.os.usertype.full.SECONDARY"

    // 本物の Android 14 マシン `dumpsys user` 3 人のユーザー (名前は省略)
    private val dump = """
        Users:
          UserInfo{0:Owner:4c13} isPrimary=true
            Type: android.os.usertype.full.SYSTEM
            Flags: 19475 (ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM)
            State: RUNNING_UNLOCKED
          UserInfo{10:Space:2000410}
            Type: android.os.usertype.full.SECONDARY
            Flags: 33555472 (FULL|HW_HIDDENSPACE|INITIALIZED)
            State: -1
          UserInfo{128:Clones:4000410} parentId=0
            Type: android.os.usertype.full.SECONDARY
            Flags: 67109904 (CLONED_PROFILE|FULL|INITIALIZED)
            State: RUNNING_UNLOCKED
    """.trimIndent()

    private val owner = User(0, 0x4c13, "android.os.usertype.full.SYSTEM", AppCopies.NO_PROFILE_GROUP, running = true,
        flagNames = listOf("ADMIN", "FULL", "INITIALIZED", "MAIN", "PRIMARY", "SYSTEM"))
    private val privateSpace = User(10, 0x2000410, secondary, AppCopies.NO_PROFILE_GROUP, running = false,
        flagNames = listOf("FULL", "HW_HIDDENSPACE", "INITIALIZED"))
    private val appClones = User(128, 0x4000410, secondary, profileGroupId = 0, running = true, parentId = 0,
        flagNames = listOf("CLONED_PROFILE", "FULL", "INITIALIZED"))

    @Test fun parsesFlagNamesAndParentFromDumpsys() {
        val d = AppCopies.parseDumpsys(dump)
        assertEquals(listOf("ADMIN", "FULL", "INITIALIZED", "MAIN", "PRIMARY", "SYSTEM"), d[0]?.flagNames)
        assertEquals(null, d[0]?.parentId)
        assertEquals(AppCopies.Dumped(listOf("FULL", "HW_HIDDENSPACE", "INITIALIZED"), null), d[10])
        assertEquals(AppCopies.Dumped(listOf("CLONED_PROFILE", "FULL", "INITIALIZED"), 0), d[128])
    }

    @Test fun theCloneProfileIsUsableAndThePrivateSpaceNeverIs() {
        assertEquals(listOf(128), AppCopies.usable(0, listOf(owner, privateSpace, appClones)))
        assertEquals(Verdict.Copy, AppCopies.verdict(appClones, 0, 0))
        // は上位ユーザーのみが接続していると見なされます (データグループを読み取れない場合)
        assertEquals(Verdict.Copy, AppCopies.verdict(appClones.copy(profileGroupId = AppCopies.NO_PROFILE_GROUP), 0, 0))
        // プライベート スペースは、ロックが解除され、実行され、所有者に接続され、クローンとしてマークされている場合でも含まれません。
        val unlocked = privateSpace.copy(running = true, profileGroupId = 0, parentId = 0, mediaShared = true)
        assertEquals(Verdict.Private, AppCopies.verdict(unlocked, 0, 0))
        assertEquals(listOf(128), AppCopies.usable(0, listOf(owner, unlocked, appClones)))
        // タグ名は PRIVATE、タイプはプライベート、システム マーキングは他のアプリから非表示: どれもリストされません
        val linkedClone = appClones.copy(id = 20)
        assertEquals(Verdict.Private, AppCopies.verdict(linkedClone.copy(flagNames = listOf("CLONED_PROFILE", "PRIVATE_SPACE")), 0, 0))
        assertEquals(Verdict.Private, AppCopies.verdict(linkedClone.copy(type = "android.os.usertype.profile.PRIVATE"), 0, 0))
        assertEquals(Verdict.Private, AppCopies.verdict(linkedClone.copy(hidden = true), 0, 0))
    }

    @Test fun onlyRunningUnlockedUnpausedLinkedProfilesCount() {
        assertEquals(Verdict.Self, AppCopies.verdict(owner, 0, 0))
        assertEquals(Verdict.Stopped, AppCopies.verdict(appClones.copy(running = false), 0, 0))
        assertEquals(Verdict.Locked, AppCopies.verdict(appClones.copy(unlocked = false), 0, 0))
        // 元のクローン タイプ、作業データ タイプ。中断された作業データ (静音モード) はカウントされません
        assertEquals(Verdict.Copy, AppCopies.verdict(User(30, 0x1030, "android.os.usertype.profile.CLONE", 0, running = true), 0, 0))
        val work = User(11, 0x1030, AppCopies.TYPE_MANAGED, profileGroupId = 0, running = true)
        assertEquals(Verdict.Copy, AppCopies.verdict(work, 0, 0))
        assertEquals(Verdict.Quiet, AppCopies.verdict(work.copy(quiet = true), 0, 0))
        assertEquals(Verdict.Quiet, AppCopies.verdict(work.copy(flags = work.flags or AppCopies.FLAG_QUIET_MODE), 0, 0))
        // ゲスト (タグ名を押し、タグ名が読み取れない場合はタグの位置を押します)、別の完全なユーザー (所有者に接続されていない): リストされていません
        val guest = User(12, 0x414 or AppCopies.FLAG_GUEST, "android.os.usertype.full.GUEST", 0, running = true, mediaShared = true)
        assertEquals(Verdict.Flags, AppCopies.verdict(guest, 0, 0))
        assertEquals(Verdict.Flags, AppCopies.verdict(guest.copy(flags = 0x410, flagNames = listOf("FULL", "GUEST")), 0, 0))
        val other = User(13, 0x410, secondary, AppCopies.NO_PROFILE_GROUP, running = true, mediaShared = true)
        assertEquals(Verdict.NotLinked, AppCopies.verdict(other, 0, 0))
        // 別のユーザー自身のクローン
        assertEquals(Verdict.NotLinked, AppCopies.verdict(appClones.copy(profileGroupId = 13, parentId = 13), 0, 0))
        // は接続されていますが、クローンであることや作品情報を見ることはできません。
        assertEquals(Verdict.NotCopy, AppCopies.verdict(appClones.copy(flagNames = listOf("FULL", "INITIALIZED")), 0, 0))
        // タイプやタグ名は見えませんが、メディアは上位と共有されています。
        assertEquals(Verdict.Copy, AppCopies.verdict(User(15, 0x1010, null, 0, running = true, mediaShared = true), 0, 0))
        assertEquals(emptyList<Int>(), AppCopies.usable(0, listOf(owner, guest, other)))
    }

    @Test fun summaryHasIdsFlagNamesAndVerdictsButNoNames() {
        val s = AppCopies.summary(0, listOf(owner, privateSpace, appClones))
        assertEquals(
            "2 other, 1 usable: 10 flags=0x2000410 (FULL|HW_HIDDENSPACE|INITIALIZED) type=$secondary group=-10000 stopped -> private; " +
                "128 flags=0x4000410 (CLONED_PROFILE|FULL|INITIALIZED) type=$secondary group=0 parent=0 unlocked -> copy",
            s,
        )
        assertEquals("no other users", AppCopies.summary(0, listOf(owner)))
    }

    @Test fun copiesFollowTheAppListAndOnlyCountAppsTheCurrentUserHas() {
        val apps = listOf("com.example.chat" to "Chat", "com.example.notes" to "Notes")
        val users = listOf(UserProfile(128, "分身应用", true), UserProfile(11, "", true))
        val packages = mapOf(128 to setOf("com.example.chat", "com.example.only.there"), 11 to setOf("com.example.chat", "com.example.notes"))
        assertEquals(
            listOf(AppCopy("com.example.chat", 128, "分身应用"), AppCopy("com.example.chat", 11, ""), AppCopy("com.example.notes", 11, "")),
            AppCopies.copies(apps, users, packages),
        )
        assertEquals("Chat (another copy: 分身应用)", AppCopy("com.example.chat", 128, "分身应用").label("Chat"))
        assertEquals("Chat (another copy: user 11)", AppCopy("com.example.chat", 11, "").label("Chat"))
    }

    private val chatCopy = AppCopy("com.example.chat", 128, "分身应用")

    private inner class CopyScreen(private val copies: List<AppCopy> = listOf(chatCopy)) : ScreenPort by FakeScreen() {
        val opened = mutableListOf<String>()
        override suspend fun listApps() = listOf("com.example.chat" to "Chat", "com.example.notes" to "Notes")
        override suspend fun appCopies() = copies
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { opened += packageName; return ActionResult.Ok() }
        override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
            opened += "$packageName@$userId${if (cold) " cold" else ""}"
            return ActionResult.Ok()
        }
    }

    @Test fun listAppsAddsOneEntryPerCopyWithItsUser() = runTest {
        val r = toolDispatcher(CopyScreen()).executeRead(Part.ToolCall("a", "phone_list_apps", "{}"))
        val apps = r.content["apps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Chat", "Chat (another copy: 分身应用)", "Notes"), apps.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("com.example.chat", "com.example.chat", "com.example.notes"), apps.map { it["package"]!!.jsonPrimitive.content })
        assertEquals(128, apps[1]["user"]!!.jsonPrimitive.int)
        assertFalse("user" in apps[0])
        assertFalse("user" in apps[2])
        // 另一份用户的条目也只写在 apps 里,不再额外复制一份 packages 数组。
        assertFalse("packages" in r.content)
    }

    @Test fun singleCopyAppsListAndLaunchAsBefore() = runTest {
        val screen = CopyScreen(copies = emptyList())
        val list = toolDispatcher(screen).executeRead(Part.ToolCall("a", "phone_list_apps", "{}"))
        assertEquals(2, list.content["apps"]!!.jsonArray.size)
        assertTrue(list.content["apps"]!!.jsonArray.none { "user" in it.jsonObject })
        val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", """{"package":"com.example.chat"}"""))
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.example.chat"), screen.opened)
    }

    @Test fun launchWithUserOpensThatCopy() = runTest {
        val screen = CopyScreen()
        val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", """{"package":"com.example.chat","user":128,"cold":true}"""))
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.example.chat@128 cold"), screen.opened)
    }

    @Test fun launchWithAnUnlistedUserIsRefusedWithoutLaunching() = runTest {
        val screen = CopyScreen()
        // リストされていないユーザー (プライベート スペース 10 など) およびリストされているユーザーに含まれていないアプリ: 開かないでください。
        for (args in listOf("""{"package":"com.example.chat","user":10}""", """{"package":"com.example.notes","user":128}""")) {
            val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))
            assertEquals(args, "false", r.content["ok"]!!.jsonPrimitive.content)
            assertEquals(args, "copy_not_found", r.content["error"]?.jsonPrimitive?.content ?: r.content["reason"]?.jsonPrimitive?.content)
        }
        // は整数ではなく、ユーザー番号の範囲を超えています: パラメーターが間違っています
        for (args in listOf("""{"package":"com.example.chat","user":"abc"}""", """{"package":"com.example.chat","user":-1}""", """{"package":"com.example.chat","user":1.5}""")) {
            val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))
            assertEquals(args, "false", r.content["ok"]!!.jsonPrimitive.content)
        }
        assertEquals(emptyList<String>(), screen.opened)
    }

    private inner class ShownScreen(private val result: ActionResult) : ScreenPort by FakeScreen() {
        override suspend fun listApps() = listOf("com.example.chat" to "Chat")
        override suspend fun appCopies() = listOf(chatCopy)
        override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean) = result
    }

    private suspend fun launchCopy(result: ActionResult) =
        toolDispatcher(ShownScreen(result)).execute(Part.ToolCall("l", "phone_launch_app", """{"package":"com.example.chat","user":128}"""))

    @Test fun theResultSaysWhichCopyIsShowing() = runTest {
        // あなたが要求したコピーは発行されました。どのコピーであるかを正確に持参してください。「これはもう 1 つのコピーです」と念を押す必要はもうありません。
        val right = launchCopy(ActionResult.Ok(inProfile = OpenedElsewhere.OTHER_PROFILE, profileUser = 128, requestedUser = 128))
        assertEquals(128, right.content["profileUser"]!!.jsonPrimitive.int)
        assertEquals("true", right.content["requestedCopy"]!!.jsonPrimitive.content)
        assertFalse(right.content["hint"]?.jsonPrimitive?.content.orEmpty().contains("メインのものではない"))
        // にはオリジナルのコピーが渡されましたが、それは明らかに私が欲しかったものではありませんでした。
        val wrong = launchCopy(ActionResult.Ok(inProfile = OpenedElsewhere.MAIN_PROFILE, profileUser = 0, requestedUser = 128))
        assertEquals("false", wrong.content["requestedCopy"]!!.jsonPrimitive.content)
        assertTrue(wrong.content["hint"]!!.jsonPrimitive.content.contains("これはメインのアプリであり、依頼された複製ではない。"))
        // システムは他のコピーを開き続けます: 開くことができませんでした
        val failed = launchCopy(ActionResult.Failed("copy_not_opened", "The main app opened instead (user 0). Requested: the copy in user 128."))
        assertEquals("copy_not_opened", failed.content["error"]!!.jsonPrimitive.content)
        assertTrue(failed.content["message"]!!.jsonPrimitive.content.contains("user 128"))
    }

    @Test fun chooserOptionIndicesFollowTheOptions() {
        fun el(i: Int, name: String, clickable: Boolean = true) =
            UiElement("@e$i", "LinearLayout", name, 0, 0, listOf(100, 300 + i * 100, 900, 380 + i * 100), null, clickable, false, false)
        // 2 つの名前はまったく同じです (角のマークによってのみ区別されます)。両方をクリックして取得します。
        val window = listOf(el(0, "使用以下方式打开", clickable = false), el(1, "Chat"), el(2, "Chat"), el(3, "取消"))
        assertEquals(listOf(1, 2), LaunchChooser.optionIndices("Chat", window))
        assertEquals(null, LaunchChooser.optionIndices("Chat", listOf(el(0, "取消"))))
    }
}
