package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.vision.FRAME_A
import com.wangzan101.mika.core.vision.FRAME_B
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.LocScreen
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.says
import com.wangzan101.mika.core.vision.uiEl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.uistate.ScreenDiagnosis

class ToolDispatcherVisionTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("v${++seq}", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val wlan = uiEl("@e1", "WLAN", listOf(0, 100, 999, 160))

    private fun dispatcher(screen: LocScreen, vararg replies: Any) =
        toolDispatcher(screen, vision = VisionService(says(*replies), JvmTestImageOps))

    private suspend fun ToolDispatcher.locateWlan(): Pair<String, String> {
        val r = execute(call("phone_find_visually", """{"target":"WLAN","text":"WLAN"}""")).content
        assertEquals(r.toString(), "true", r.s("found"))
        return r.s("ref") to r.s("snapshotId")
    }

    private suspend fun ToolDispatcher.tapRef(ref: Pair<String, String>) =
        execute(call("phone_tap", """{"ref":"${ref.first}","snapshotId":"${ref.second}"}""")).content

    @Test fun toolsHiddenWithoutVision() = runTest {
        val plain = toolDispatcher(LocScreen()).definitions().map { it.name }
        assertFalse("phone_find_visually" in plain); assertFalse("phone_describe" in plain)
        assertEquals("unknown_tool", toolDispatcher(LocScreen()).execute(call("phone_find_visually", """{"target":"x"}""")).content.s("error"))
        val withVision = dispatcher(LocScreen()).definitions().map { it.name }
        assertTrue("phone_find_visually" in withVision); assertTrue("phone_describe" in withVision)
        // 写真を表示するためのモデルと、メイン モデルが写真を表示できるかどうかは 2 つの別のことです。スクリーンショットは依然としてメイン モデルにのみ従います。
        assertFalse("phone_screenshot" in withVision)
    }

    @Test fun locateResultShape() = runTest {
        val r = dispatcher(LocScreen(listOf(wlan))).execute(call("phone_find_visually", """{"target":"WLAN","text":"WLAN"}""")).content
        assertEquals("true", r.s("ok")); assertEquals("elements", r.s("source"))
        assertEquals("@v1", r.s("ref")); assertTrue(r.s("snapshotId").startsWith("loc_"))
        assertEquals(listOf(500, 130), listOf(r.s("x").toInt(), r.s("y").toInt()))
        assertEquals(4, r["box"]!!.jsonArray.size)
        val bad = dispatcher(LocScreen()).execute(call("phone_find_visually", """{"target":"x","text":"${"字".repeat(61)}"}""")).content
        assertEquals("invalid_arguments", bad.s("error"))
        val none = dispatcher(LocScreen(), "null").execute(call("phone_find_visually", """{"target":"某个图标"}""")).content
        assertEquals("true", none.s("ok")); assertEquals("false", none.s("found")); assertEquals("not_found", none.s("reason"))
    }

    @Test fun tapVRefWorksWhenScreenSame() = runTest {
        val s = LocScreen(listOf(wlan))
        val d = dispatcher(s)
        val r = d.tapRef(d.locateWlan())
        assertEquals(r.toString(), "true", r.s("ok"))
        // は、画面のピクセルに従って 0 ～ 999 の座標を登録します (アクション後に M1 の座標をクリックして確認します)。
        assertEquals(listOf("540,312"), s.taps)
        assertEquals("true", r.s("changed"))
        assertEquals("WLAN", r.s("located"))
    }

    @Test fun unbackedPointOnAnEmptyTreeIsTappableAndSaysSo() = runTest {
        // 映像視聴モデルの測位精度は未検証です。ツリーには何もありませんが、まだクリックできます。結果は、裏付けとなる証拠はなく、効果に依存していることを示しています。
        val model = com.wangzan101.mika.core.agent.FakeModel(
            *listOf("""{"bbox_2d":[0,880,1000,940],"seen":"输入框"}""", "是").map {
                com.wangzan101.mika.core.model.ModelReply(listOf(Part.Text(it)), com.wangzan101.mika.core.model.StopReason.EndTurn, null)
            }.toTypedArray(), groundingVerified = false)
        val s = LocScreen()
        val d = toolDispatcher(s, vision = VisionService(model, JvmTestImageOps))
        val found = d.execute(call("phone_find_visually", """{"target":"底部的输入框"}""")).content
        assertEquals(found.toString(), "true", found.s("unverified"))
        val r = d.tapRef(found.s("ref") to found.s("snapshotId"))
        assertEquals(r.toString(), "true", r.s("unverifiedPoint"))
        assertTrue(r.s("hint"), com.wangzan101.mika.core.prompt.Feedback["located.unverified"] in r.s("hint"))
        assertEquals(1, s.taps.size)
    }

    @Test fun tapVRefStaleWhenScreenChanged() = runTest {
        val s = LocScreen(listOf(wlan))
        val d = dispatcher(s)
        val ref = d.locateWlan()
        s.show(FRAME_B)
        assertEquals("ref_expired", d.tapRef(ref).s("error"))
        s.show(null)
        assertEquals("ref_expired", d.tapRef(ref).s("error"))
        s.show(FRAME_A)
        assertEquals("ref_expired", d.tapRef(ref.first to "loc_other").s("error"))
        assertTrue(s.taps.isEmpty())
        // 画面が @v に変更され、その点で入力することができなくなりました。
        s.show(FRAME_B)
        assertEquals("ref_expired", d.execute(call("phone_type_text", """{"text":"a","ref":"${ref.first}","snapshotId":"${ref.second}"}""")).content.s("error"))
        assertTrue(s.taps.isEmpty())
    }

    @Test fun writeClearsRegistry() = runTest {
        val s = LocScreen(listOf(wlan))
        val d = dispatcher(s)
        val ref = d.locateWlan()
        d.execute(call("phone_list_elements"))
        assertEquals("画面の読み取りでは内容を消去しません", "true", d.tapRef(ref).s("ok"))
        val again = d.locateWlan()
        d.execute(call("phone_back"))
        assertEquals("ref_expired", d.tapRef(again).s("error"))
        assertEquals(listOf("540,312", "phone_back"), s.taps)
    }

    // レビュー項目: モデルの最後に読み取られたものではなく、現在の画面に従ってピクセルを変換します。
    @Test fun tapVRefUsesFreshScreenSize() = runTest {
        val s = LocScreen(listOf(wlan))
        val d = dispatcher(s)
        val ref = d.locateWlan()
        d.execute(call("phone_list_elements"))
        s.width = 720; s.height = 1600
        assertEquals("true", d.tapRef(ref).s("ok"))
        assertEquals(listOf("360,208"), s.taps)
    }

    @Test fun coordinateTapAfterDescriptionNeedsTarget() = runTest {
        val s = LocScreen(listOf(wlan))
        val d = dispatcher(s, "[屏幕] 设置")
        d.execute(call("phone_describe", """{"task":"打开 WLAN","reason":"看看"}"""))
        val r = d.execute(call("phone_tap", """{"x":500,"y":130}""")).content
        assertEquals("invalid_arguments", r.s("error")); assertTrue(r.s("message"), "target" in r.s("message"))
        assertEquals("true", d.execute(call("phone_tap", """{"x":500,"y":130,"target":"WLAN"}""")).content.s("ok"))
        // は書き込み操作後に通常の座標クリックを復元します
        assertEquals("true", d.execute(call("phone_tap", """{"x":500,"y":130}""")).content.s("ok"))
        val vs = Json.parseToJsonElement("""{"x":1,"y":1,"target":"x"}""").jsonObject
        assertFalse(d.interfaces.actionTarget(d.declared("phone_tap"), vs).bySight)
    }

    @Test fun describeReturnsModelText() = runTest {
        val r = dispatcher(LocScreen(), "[屏幕] 设置首页\n[可交互元素]\n- WLAN | 列表项 | (500,130)")
            .execute(call("phone_describe", """{"task":"打开 WLAN","reason":"列表里没有"}""")).content
        assertEquals("true", r.s("ok"))
        assertEquals("[屏幕] 设置首页\n[可交互元素]\n- WLAN | 列表项 | (500,130)", r.s("description"))
        assertEquals("invalid_arguments", dispatcher(LocScreen()).execute(call("phone_describe", """{"task":"x"}""")).content.s("error"))
        val prot = dispatcher(LocScreen(secure = true)).execute(call("phone_describe", """{"task":"x","reason":"y"}""")).content
        assertEquals("protected_content", prot.s("error"))
        val empty = dispatcher(LocScreen(emptyList(), null, hasWindow = false)).execute(call("phone_describe", """{"task":"x","reason":"y"}""")).content
        assertEquals("no_app_open", empty.s("error"))
        val broken = dispatcher(LocScreen(), com.wangzan101.mika.core.model.ModelError.Network()).execute(call("phone_describe", """{"task":"x","reason":"y"}""")).content
        assertEquals("vision_unavailable", broken.s("error"))
    }

    @Test fun describeRejectsUnavailableCaptureWithoutCallingVision() = runTest {
        val model = says()
        val screen = LocScreen(captureError = ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE)
        val d = toolDispatcher(screen, vision = VisionService(model, JvmTestImageOps))
        val r = d.execute(call("phone_describe", """{"task":"看看画面","reason":"读屏为空"}""")).content
        assertEquals(ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE, r.s("error"))
        assertEquals("false", r.s("recoverable"))
        assertTrue(model.requests.isEmpty())
    }

    @Test fun gateArgsResolveLocatedPoint() = runTest {
        val d = dispatcher(LocScreen(listOf(wlan)))
        val ref = d.locateWlan()
        val args = Json.parseToJsonElement("""{"ref":"${ref.first}","snapshotId":"${ref.second}"}""").jsonObject
        val g = d.interfaces.actionTarget(d.declared("phone_tap"), args).args
        assertEquals(listOf("500", "130", "WLAN", "WLAN"), listOf(g.s("x"), g.s("y"), g.s("target"), g.s("text")))
        assertTrue(d.interfaces.actionTarget(d.declared("phone_tap"), args).bySight)
        // その他のクリック、不明なもの @v そのまま返す
        val e = Json.parseToJsonElement("""{"ref":"@e1","snapshotId":"s"}""").jsonObject
        assertEquals(e, d.interfaces.actionTarget(d.declared("phone_tap"), e).args)
        val gone = Json.parseToJsonElement("""{"ref":"@v9","snapshotId":"loc_x"}""").jsonObject
        assertEquals(gone, d.interfaces.actionTarget(d.declared("phone_tap"), gone).args)
    }
}
