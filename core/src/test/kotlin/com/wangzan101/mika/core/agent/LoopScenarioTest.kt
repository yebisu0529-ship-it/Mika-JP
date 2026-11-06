package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.testing.FakeChatModel
import com.wangzan101.mika.core.testing.InterruptibleStream
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.LoopSnapshot
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ScriptWebPort
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.verify.Verifier
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 偽のモデルを使用して本物の AgentLoop を駆動し、典型的なシナリオを実行します。終了をアサートし、毎回モデルに送信されるメッセージ シーケンスをゴールデン サンプルと比較します。 モデルは、
 * の再構築の前後で変更されません (実行エントリ、ツール レジストリ、機能インターフェイス、メイン ループのスリム化)。
 * ゴールデン サンプルの再生成: ./gradlew :core:test --tests '*LoopScenarioTest' -PupdateSnapshots
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopScenarioTest {
    private val bundled by lazy { KnowledgeStore(com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.entries()) }
    private val catalog by lazy { com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog() }

    private fun loop(
        model: FakeChatModel, tools: ToolDispatcher, maxSteps: Int = 20,
        checker: FakeChatModel? = null,
        knowledge: KnowledgeStore? = null, skills: GuideCatalog? = null,
        installed: List<Pair<String, String>> = emptyList(), flags: SpeedFlags = SpeedFlags.OFF,
    ) = AgentLoop(
        model, tools, SystemPrompt.identity(), maxSteps, knowledge = knowledge, installedApps = { installed },
        verifier = checker?.let { Verifier(it, null) },
        flags = flags, clock = { CLOCK }, skills = skills, zone = ZoneOffset.UTC,
    )

    private fun snapshot(name: String, end: TaskState, vararg models: Pair<String, FakeChatModel>) {
        models.forEach { (role, m) -> assertEquals("$role の用意した応答が残っています", 0, m.remaining) }
        LoopSnapshot.check(name, LoopSnapshot.render(end.toString(), models.toList()))
    }

    /** このリクエストの最後の実行時ステータスの全文。 */
    private fun List<com.wangzan101.mika.core.model.Message>.runtimeState(): String =
        flatMap { it.parts }.filterIsInstance<Part.Text>().last { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }.text

    private fun FakeChatModel.lastResult(i: Int): String =
        requests[i].messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content

    // (a) 携帯電話: 画面を表示 (進行状況の説明付き) → クリック → 画面を表示 → テキストで回答;回答の引用文を読みましたが、モデルを確認しないでください。参考資料はフロントエンド アプリによって挿入されます
    @Test fun simplePhoneTask() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(SETTINGS, listOf(label("@e1", "设置", 80), button("@e2", "蓝牙", 300), button("@e3", "WLAN", 400))),
            Screen(SETTINGS, listOf(label("@e1", "蓝牙", 80), label("@e2", "已开启", 300))),
        ))
        val main = fakeModel("main") {
            reply { thinking("先看屏"); text("看一下设置首页"); call("phone_observe") }
            call("phone_tap", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_observe")
            answer("Bluetoothは有効です。Bluetoothの画面に「已开启」と表示されています。")
        }
        val checker = fakeModel("checker") { }
        val end = loop(main, toolDispatcher(screen, skills = catalog), checker = checker,
            knowledge = bundled, skills = catalog, installed = listOf(SETTINGS to "设置")).run("看看蓝牙开没开")
        end as TaskState.Succeeded
        assertEquals("Bluetoothは有効です。Bluetoothの画面に「已开启」と表示されています。", end.answer)
        assertTrue(end.verified)
        // のソースは、「有効」と表示された画面です (手順 3)
        assertEquals(listOf(3), end.sources.map { it.step })
        assertEquals(listOf("tap @e2 s1"), screen.calls)
        assertTrue(checker.requests.isEmpty())
        snapshot("a_simple_phone", end, "main" to main)
    }

    // (b) Web ページ: サイトを開いた後、ドメイン名によってサイトの知識を注入します。サンドボックスは中にあります
    @Test fun webTaskWithSiteKnowledge() = runTest {
        val web = FakeWebPort().apply {
            title = "知乎"
            page = pageText("https://www.zhihu.com/hot", "知乎", "热榜\n1. 今日话题")
        }
        val main = fakeModel("main") {
            call("web_navigate", "url" to "https://www.zhihu.com/hot")
            call("web_read_page")
            answer("知乎の人気ランキング1位は「今日话题」です。")
        }
        val tools = toolDispatcher(FakeScreen(), skills = catalog, web = web, sandbox = FakeSandbox())
        val end = loop(main, tools, knowledge = bundled, skills = catalog).run("用网页看一下知乎热榜")
        assertTrue("$end", end is TaskState.Succeeded)
        val refs = main.requests[1].messages.contextBodies(ContextKind.SiteKnowledge)
        assertEquals(1, refs.size)
        snapshot("b_web_site_knowledge", end, "main" to main)
    }

    // (c) 空の返信: 1 回だけ通知されます。最終的な答えは分割されて到着し、順番にまとめられます
    @Test fun emptyReplyIsNudged() = runTest {
        val main = fakeModel("main") {
            reply { thinking("想想") }
            call("phone_observe")
            reply { text("見つかりました,", "最初の画面に「搜索」ボタンがあります") }
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        assertEquals("見つかりました,最初の画面に「搜索」ボタンがあります", (end as TaskState.Succeeded).answer)
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(main.requests[1].messages.withoutRuntime().last()))
        snapshot("c_empty_reply_nudge", end, "main" to main)
    }

    // (d) 番号の有効期限が切れています: ポートは ref_expired を報告します。モデルを再表示して、もう一度クリックします。
    @Test fun staleRefThenReobserve() = runTest {
        val screen = ScriptedScreen(
            listOf(Screen(APP, listOf(button("@e1", "搜索", 100))), Screen(APP, listOf(label("@e1", "搜索结果", 100)))),
            results = listOf(ActionResult.Failed("ref_expired")),
        )
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s1")
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s2")
            answer("検索を開きました")
        }
        val end = loop(main, toolDispatcher(screen)).run("点搜索")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue("ref_expired" in main.lastResult(2))
        assertTrue(main.lastResult(2), "\"cause\":\"element_lost\"" in main.lastResult(2))
        snapshot("d_ref_expired", end, "main" to main)
    }

    // (e) 不可逆操作: 停止 → 申請確認 → ユーザーが同意しない → 素直に諦める
    @Test fun irreversibleDeclined() = runTest {
        // 問い合わせるときは、最初に質問するだけです。注文の送信はブロックされました、申請は承認されました、そしてユーザーは同意しませんでした。
        val screen = ScriptedScreen(listOf(
            Screen(APP, listOf(label("@e1", "耳机店", 40), label("@e2", "合计 ¥25", 800), button("@e3", "提交订单", 950))),
        ))
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("request_confirmation", "action" to "order", "target" to "耳机店", "content" to "一副耳机", "amount" to "¥25", "app" to APP)
            reportFailure("ユーザーから注文の同意が得られませんでした")
        }
        val l = loop(main, toolDispatcher(screen))
        val run = async { l.run("买一副耳机") }
        advanceUntilIdle()
        assertTrue("${l.state.value}", l.state.value is TaskState.WaitingConfirmation)
        l.answerConfirmation(false)
        val end = run.await()
        assertEquals(TaskState.Failed("ユーザーから注文の同意が得られませんでした", FailKind.GaveUp), end)
        assertFalse(screen.calls.any { it.startsWith("tap @e3") })
        assertTrue("confirmation_required" in main.lastResult(2))
        assertTrue("confirmation_declined" in main.lastResult(3))
        snapshot("e_irreversible_declined", end, "main" to main)
    }

    // (f) 一度に複数のステップ (do_steps): バッチ全体が 1 つのステップとしてカウントされ、サブステップが 1 つずつ実行されます。
    @Test fun batchSteps() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(APP, listOf(field("@e1", "搜索商品", 100), button("@e2", "搜索", 200))),
            Screen(APP, listOf(field("@e1", "咖啡", 100, hint = false), button("@e2", "搜索", 200))),
            Screen(APP, listOf(label("@e1", "咖啡豆 ¥89", 300))),
        ))
        val steps = listOf(
            mapOf("tool" to "phone_type_text", "args" to mapOf("text" to "咖啡", "ref" to "@e1", "snapshotId" to "s1")),
            mapOf("tool" to "phone_tap", "args" to mapOf("ref" to "@e2", "snapshotId" to "s1")),
        )
        val main = fakeModel("main") {
            call("phone_observe")
            call(DO_STEPS_TOOL, "steps" to steps)
            // はバッチ終了後にこの画面に戻りません (写真を見ずにモデルを作成): 読んだ後にのみ価格を知ることができます
            call("phone_observe")
            answer("咖啡豆 ¥89")
        }
        val end = loop(main, toolDispatcher(screen), flags = SpeedFlags(batchSteps = true)).run("搜咖啡看价格")
        assertTrue("$end", end is TaskState.Succeeded)
        assertEquals(listOf("type @e1 咖啡", "tap @e2 s1"), screen.calls)
        snapshot("f_batch_steps", end, "main" to main)
    }

    // (g) ページ スクリプト: 通常のサブステップが実行され、不可逆的なサブステップはブロックされ、実行されません。
    @Test fun browserScriptWithGatedSubAction() = runTest {
        val web = FakeWebPort().apply {
            elements = listOf(button("@e1", "搜索", 100).copy(resourceId = "#go"), button("@e2", "提交订单", 300).copy(resourceId = "#order"))
        }
        val port = ScriptWebPort(web) { b ->
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e1") })
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e2") })
            ScriptRun.Done("\"unreachable\"")
        }
        val main = fakeModel("main") {
            call("web_navigate", "url" to "https://shop.example.com/")
            call(WEB_SCRIPT_TOOL, "code" to "await mika.tap('@e1'); await mika.tap('@e2')")
            reportFailure("注文にはユーザーの同意が必要なため、スクリプトは実行していません")
        }
        val end = loop(main, toolDispatcher(FakeScreen(), web = port)).run("搜一下再下单")
        assertTrue("$end", end is TaskState.Failed)
        assertEquals(1, web.calls.count { it.startsWith("tapRef") })
        assertTrue("confirmation_required" in main.lastResult(2))
        snapshot("g_browser_script_gated", end, "main" to main)
    }

    // (h) サービス エラー: 電流制限、ネットワーク、および 5xx はすべて一時的な障害です。同じリクエストを再送信し (モデルに表示されるコンテンツは変更されません)、タスクを完了します。
    @Test fun providerErrors() = runTest {
        val main = fakeModel("main") {
            rateLimited(retryAfterMs = 500)
            networkError()
            call("phone_observe")
            serverError(503)
            answer("見つかりました")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        com.wangzan101.mika.core.testing.assertAnswered("見つかりました", end)
        assertEquals(5, main.requests.size)
        // が同じリクエストを再送信します
        assertEquals(main.requests[3], main.requests[4])
        snapshot("h_provider_errors", end, "main" to main)
    }

    // (i) コンテキストが長すぎます。強く圧縮して再送信し、同じ手順を続行します。
    @Test fun contextOverflowCompressesAndContinues() = runTest {
        val main = fakeModel("main") {
            for (i in 1..8) call("wait", "seconds" to 1 + i / 10.0)
            contextOverflow()
            answer("待機が完了しました")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("等一会儿")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue(main.requests[9].messages.size < main.requests[8].messages.size)
        snapshot("i_context_overflow", end, "main" to main)
    }

    // (j) ステップ数がなくなった場合：最終ラウンドでは end_not_done のみが出題され、終了時にテキストで回答します。
    @Test fun stepBudgetExhausted() = runTest {
        val main = fakeModel("main") {
            for (i in 1..3) call("wait", "seconds" to 1 + i / 10.0)
            reportFailure("最初の画面とメニューを確認しましたが、見つかりませんでした")
        }
        val end = loop(main, toolDispatcher(FakeScreen()), maxSteps = 3).run("找夜间模式")
        assertEquals(TaskState.Failed("最初の画面とメニューを確認しましたが、見つかりませんでした", FailKind.GaveUp), end)
        assertEquals(listOf("end_not_done"), main.requests.last().toolNames)
        snapshot("j_step_budget", end, "main" to main)
    }

    // (k) 出力は切り詰められており (回答やリマインダーではなく半分のテキストのみ)、パラメーターは正当な JSON 呼び出しではありません。
    @Test fun truncatedAndMalformed() = runTest {
        val main = fakeModel("main") {
            truncated("我先打开")
            malformedCall("phone_tap")
            answer("見つかりました")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue("invalid_arguments" in main.lastResult(2))
        snapshot("k_truncated_malformed", end, "main" to main)
    }

    // (l) 回答の価格を読んでいません: モデルを確認してください → 一度返却して場所を指定します → それでも一致しない場合は渡して未確認としてマークしてください
    @Test fun ungroundedValueIsNudgedOnceThenLabelled() = runTest {
        val screen = ScriptedScreen(listOf(Screen(APP, listOf(label("@e1", "咖啡豆 ¥89", 300)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer("咖啡豆 ¥79")
            answer("咖啡豆 ¥79(页面上看到的)")
        }
        val checker = fakeModel("checker") {
            verdict("fail", "屏上写的是 ¥89", "看咖啡豆的价格" to null)
            verdict("fail", "屏上写的是 ¥89", "看咖啡豆的价格" to null)
        }
        val end = loop(main, toolDispatcher(screen), checker = checker).run("看咖啡豆的价格")
        end as TaskState.Succeeded
        assertFalse(end.verified)
        assertEquals(listOf("79", "検証モデルから日本語の理由説明が得られませんでした。"), end.unconfirmed)
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(main.requests[2].messages.withoutRuntime().last()))
        assertTrue("モデルへの修正指示には検証理由の原文を保持する", main.requests[2].messages.withoutRuntime().last().parts
            .filterIsInstance<Part.Text>().any { "屏上写的是 ¥89" in it.text })
        snapshot("l_unconfirmed_labelled", end, "main" to main, "verifier" to checker)
    }

    // (m) 不可逆操作: 停止→アプリケーションの確認→ユーザーの同意→送信→「送信済み」の読み取り→応答。外部のことを行った場合は、答えのモデルを確認してください
    // 送信には費用はかかりません。最初に質問せずに直接送信し、一度読んで送信後に確認してください。
    @Test fun irreversibleDoneDirectlyIsVerified() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "发消息"), button("@e3", "+", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "明天见", hint = false), button("@e3", "发送", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), label("@e4", "明天见", 600), label("@e5", "已发送", 640), field("@e2", "发消息"))),
        ))
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_type_text", "text" to "明天见", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("phone_observe")
            answer("张三へ「明天见」を送信しました。チャットには「已发送」と表示されています。")
        }
        val checker = fakeModel("checker") { verdict("pass", "聊天里显示已发送", "给张三发明天见" to "已发送") }
        val end = loop(main, toolDispatcher(screen), checker = checker).run("给张三发明天见")
        assertTrue("$end", end is TaskState.Succeeded && end.verified)
        assertTrue(screen.calls.any { it.startsWith("tap @e3") })
        snapshot("m_irreversible_verified", end, "main" to main, "verifier" to checker)
    }

    // (n) 英語の入力でも目標は日本語。中国語の回答を1回差し戻し、日本語に直した回答を渡す。
    @Test fun answerInOtherLanguageIsNudgedOnce() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300),
            label("@e3", "已使用 250.53 GB / 512 GB", 400)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer(CHINESE_ANSWER)
            answer("Androidのバージョンは14です。「关于手机」によると、ストレージは512 GB中250.53 GBを使用しています。")
        }
        val end = loop(main, toolDispatcher(screen)).run("What Android version is this phone on, and how much storage is used?")
        end as TaskState.Succeeded
        assertTrue(end.answer, end.answer.startsWith("Androidのバージョンは14"))
        val notice = main.requests[2].messages.withoutRuntime().last()
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(notice))
        assertTrue("日本語訳（原文）" in (notice.parts.single() as Part.Text).text)
        snapshot("n_answer_language_nudge", end, "main" to main)
    }

    // 書き直し後も外国語のままなら、日本語で正直に失敗を伝える。
    @Test fun answerLanguageIsNudgedOnlyOnce() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300),
            label("@e3", "已使用 250.53 GB / 512 GB", 400)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer(CHINESE_ANSWER)
            answer(CHINESE_ANSWER)
        }
        val end = loop(main, toolDispatcher(screen)).run("What Android version is this phone on, and how much storage is used?")
        assertEquals(FailKind.Model, (end as TaskState.Failed).kind)
        assertTrue(end.reason, "日本語" in end.reason)
        assertEquals(0, main.remaining)
        assertEquals(3, main.requests.size)
    }

    // ブラウザ ページでの質問: 文はページの説明で始まります (英語で修正)。言語プロンプトと回答の言語チェックは、ユーザーが入力した単語のみを調べます。中国語の回答は返されません。
    @Test fun browserPageAnswerFollowsTheUserWords() = runTest {
        val attached = AttachedPage.Page("https://shop.example.com/s?k=降噪耳机", "降噪耳机 - 搜索结果")
        val web = FakeWebPort().apply {
            title = attached.title
            page = pageText(attached.url, attached.title, "入耳式降噪耳机 B2 Pro 649 元\n头戴式降噪耳机 C3 Max 899 元")
        }
        val answer = "このページで700元以内なのは、カナル型のB2 Pro（649元）です。ヘッドホン型のC3 Maxは899元で予算を超えるため、B2 Proを選びます。"
        val main = fakeModel("main") {
            call("web_read_page")
            answer(answer)
        }
        val end = loop(main, toolDispatcher(FakeScreen(), web = web)).run(AttachedPage.compose(attached, "这页里 700 以内的挑一个"))
        assertEquals(answer, (end as TaskState.Succeeded).answer)
        assertEquals(2, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"这页里 700 以内的挑一个\"" in hint)
    }

    // ブラウザ ページのコメントには、新しく選択された単語が含まれています。言語プロンプトはコメントに入力された単語を参照し、中国語の回答は返されません。
    @Test fun browserPageSteerFollowsTheUserWords() = runTest {
        browserPageSteer("就看选中的这个", quoted = "就看选中的这个")
    }

    // は単語を選択しただけで、挿入するときに入力しませんでした。言語プロンプトは冒頭の文に続き、中国語の答えは返されません。
    @Test fun selectionOnlySteerKeepsTheOpeningWords() = runTest {
        browserPageSteer("", quoted = "这页里 700 以内的挑一个")
    }

    /** は、英語のタイトルのページで中国語で始まり、新しく選択した単語と [words] を挿入します。モデルは中国語で直接答えます。 */
    private suspend fun browserPageSteer(words: String, quoted: String) {
        val attached = AttachedPage.Page("https://shop.example.com/s?k=headphones", "Noise Cancelling Headphones - Search Results")
        val web = FakeWebPort().apply {
            title = attached.title
            page = pageText(attached.url, attached.title, "入耳式降噪耳机 B2 Pro 649 元\n头戴式降噪耳机 C3 Max 899 元")
        }
        val answer = "選択した商品はカナル型のB2 Pro（649元）で、700元以内です。ヘッドホン型のC3 Maxは899元で予算を超えるため、B2 Proを選びます。"
        val main = fakeModel("main") { answer(answer) }
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", AttachedPage.compose(attached.copy(selection = "B2 Pro 649"), words))) }
        val end = loop(main, toolDispatcher(FakeScreen(), web = web))
            .run(RunSpec(RunId("r1"), AttachedPage.compose(attached, "这页里 700 以内的挑一个"), "thread-1"), inbox)
        assertEquals(answer, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"$quoted\"" in hint)
    }

    // は、Mika の以前の英語の回答を引用し、短い中国語の文を入力しました。文全体がカウントされる場合は、言語の変更として判断されます。引用された段落はユーザーの単語をカウントしません。言語は引用された単語を要求し、中国語の回答は返されません。
    @Test fun quotedReplyFollowsTheUserWords() = runTest {
        val goal = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, ENGLISH_REPLY), "那哪个更值")
        org.junit.Assert.assertNull(ReplyLanguage.mismatch(goal, HEADPHONES_ANSWER))
        val main = fakeModel("main") { answer(HEADPHONES_ANSWER) }
        val end = loop(main, toolDispatcher(FakeScreen())).run(goal)
        assertEquals(HEADPHONES_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"那哪个更值\"" in hint)
    }

    // は間投詞で英語の答えを引用しました: 間投詞に入力された単語を見てください。
    @Test fun quotedSteerFollowsTheUserWords() = runTest {
        val steer = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, ENGLISH_REPLY), "那哪个更值")
        org.junit.Assert.assertNull(ReplyLanguage.mismatch(steer, HEADPHONES_ANSWER))
        val main = fakeModel("main") { answer(HEADPHONES_ANSWER) }
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", steer)) }
        val end = loop(main, toolDispatcher(FakeScreen())).run(RunSpec(RunId("r1"), "帮我比较一下这两款降噪耳机", "thread-1"), inbox)
        assertEquals(HEADPHONES_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"那哪个更值\"" in hint)
    }

    // 文が非常に短く、添付ファイル名が英語で長いため、文全体をカウントすると言語変更と判断されます。添付ファイルの数行はユーザーの言葉ではないため、中国語の回答は返されません。
    @Test fun attachedFileFollowsTheUserWords() = runTest {
        val goal = AttachmentLines.compose("总结一下", listOf("/workspace/inbox/Quarterly_Financial_Report_Final_v2.pdf" to "2 KB"))
        org.junit.Assert.assertNull(ReplyLanguage.mismatch(goal, REPORT_ANSWER))
        val main = fakeModel("main") { answer(REPORT_ANSWER) }
        val end = loop(main, toolDispatcher(FakeScreen())).run(goal)
        assertEquals(REPORT_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"总结一下\"" in hint)
    }

    // (o) ツール呼び出しがテキストに漏洩しました (サーバーはそれを呼び出しに解析しませんでした): 不適切な応答が 1 回返されました。モデルはツール インターフェイスを通じて再呼び出しされた後、通常どおり完成しました ユーザーに元のマークを付与せずに、
    @Test fun toolCallLeakedAsTextIsNudgedOnce() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300)))))
        val main = fakeModel("main") {
            answer(LEAKED_OBSERVE)
            call("phone_observe")
            answer(ANDROID_14)
        }
        val end = loop(main, toolDispatcher(screen)).run("这台手机是安卓几?")
        assertEquals(ANDROID_14, (end as TaskState.Succeeded).answer)
        val notice = main.requests[1].messages.withoutRuntime().last()
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(notice))
        assertTrue("`phone_observe`" in (notice.parts.single() as Part.Text).text)
        snapshot("o_tool_call_as_text_nudge", end, "main" to main)
    }

    // が返されてテキスト: End に漏洩しました。
    @Test fun toolCallLeakedTwiceEndsTheTask() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80)))))
        val main = fakeModel("main") {
            answer(LEAKED_OBSERVE)
            answer(LEAKED_OBSERVE)
        }
        val end = loop(main, toolDispatcher(screen)).run("这台手机是安卓几?")
        end as TaskState.Failed
        assertEquals(FailKind.Model, end.kind)
        assertFalse(end.reason, "<tool_call>" in end.reason)
        assertEquals(2, main.requests.size)
    }

    // ツールを正常に調整した後、再びリークが発生しました。それでも毎回 1 回戻りました (サーバーの問題が時折あり、長いタスクでは複数回)。
    @Test fun toolCallLeakAfterARealCallIsNudgedAgain() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300)))))
        val main = fakeModel("main") {
            answer(LEAKED_OBSERVE)
            call("phone_observe")
            answer(LEAKED_OBSERVE)
            answer(ANDROID_14)
        }
        val end = loop(main, toolDispatcher(screen)).run("这台手机是安卓几?")
        assertEquals(ANDROID_14, (end as TaskState.Succeeded).answer)
        assertEquals(0, main.remaining)
    }

    // の回答はこの形式について説明していますが、今回はツールについては言及していません: 通常のハンドオーバー
    @Test fun answerAboutToolCallMarkupIsDelivered() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "设置", 80)))))
        val explanation = "モデルによってはツール呼び出しを<tool_call>で囲み、その中に<function=ツール名>と複数の<parameter=パラメーター名>を書きます。"
        val main = fakeModel("main") { answer(explanation) }
        val end = loop(main, toolDispatcher(screen)).run("模型的工具调用格式是什么样的?")
        assertEquals(explanation, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
    }

    // (p) ストリーミングされている応答の中断: ユーザーが表示している段落はテキストのみの応答として履歴に記録され、中断の後にそれが続き、モデルは両方の応答を再度尋ねます。
    @Test fun steerKeepsTheInterruptedReply() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "蓝牙", 80), label("@e2", "已开启", 300)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer("Bluetoothは有効です。Bluetoothの画面に「已开启」と表示されています。")
        }
        val model = InterruptibleStream(main, listOf("まず確認します", "まずWLANが有効か確認します"))
        val inbox = RunInbox()
        val running = async {
            AgentLoop(model, toolDispatcher(screen), SystemPrompt.identity(), clock = { CLOCK }, zone = ZoneOffset.UTC, streamReplies = true)
                .run(RunSpec(RunId("r1"), "看看 WLAN 开没开", "thread-1"), inbox)
        }
        model.entered.await()
        inbox.send(RunInput.Steer("m1", "不是 WLAN,是蓝牙"))
        val end = running.await()
        assertTrue(model.cancelled)
        assertEquals("Bluetoothは有効です。Bluetoothの画面に「已开启」と表示されています。", (end as TaskState.Succeeded).answer)
        val seen = main.requests[0].messages
        val kept = seen.indexOfFirst { it.role == Role.Assistant }
        assertEquals(listOf(Part.Text("まずWLANが有効か確認します")), seen[kept].parts)
        assertTrue((seen[kept + 1].parts.first() as Part.Text).text.startsWith(AgentLoop.STEER_MARKER))
        // 言語プロンプトは、ユーザーが割り込みマークなしで入力した単語を指します。
        val hint = seen.runtimeState()
        assertTrue(hint, "latest message: \"不是 WLAN,是蓝牙\"" in hint)
        snapshot("p_steer_keeps_interrupted_reply", end, "main" to main)
    }

    private companion object {
        const val LEAKED_OBSERVE = "<tool_call>\n<function=phone_observe>\n</function>\n</tool_call>"
        const val ANDROID_14 = "この端末のAndroidバージョンは14です。端末情報の画面で確認しました。"
        const val ENGLISH_REPLY = "The first pair costs 399 dollars and has the best noise cancelling. " +
            "The second pair costs 429 dollars and is more comfortable on long flights."
        const val HEADPHONES_ANSWER = "重視する点によります。1つ目は30ドル安く、ノイズキャンセリングも優れています。2つ目は長時間でも快適で、長距離の飛行に向いています。費用対効果なら1つ目を選びます。"
        const val REPORT_ANSWER = "四半期報告の要点は3つです。売上は前四半期から少し増え、費用も抑えられています。次の四半期は海外市場の拡大に重点を置きます。"
        const val CHINESE_ANSWER = "我打开了关于手机页面看过了：这台手机的安卓版本是 14，存储已使用 250.53 GB，总共 512 GB，两个数字都是页面上显示的。"
        const val CLOCK = 1_761_300_000_000L
        const val SETTINGS = "com.android.settings"
        const val CHAT = "com.chat"
        const val APP = "com.x"
        fun pageText(url: String, title: String, text: String) =
            PageText(url, title, text, emptyList(), emptyList(), false, null)
    }
}
