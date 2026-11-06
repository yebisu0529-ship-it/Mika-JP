package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ReasoningSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 100_000, reasoning = true)
    private val setups = mutableListOf<ClientSetup>()
    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, setup ->
        setups += setup
        object : ChatModel {
            override val name = cfg.model
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
        }
    })
    private fun connected(s: ProviderStore) = s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, "https://example.test/v1", "k",
        listOf(ListedModel("m", reasoningEfforts = listOf("low", "medium", "high")), ListedModel("other", reasoningEfforts = emptyList())))

    @Test fun sameModelCanHaveDifferentEffortPerRoleAndSurvivesReload() {
        val s = store()
        val p = connected(s)
        ModelRole.entries.forEach { assertTrue(s.use(it, p.id, "m", caps)) }
        assertEquals(1, s.list().size)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(s.setReasoningEffort(ModelRole.Vision, "medium"))
        assertTrue(s.setReasoningEffort(ModelRole.Light, "low"))
        val reloaded = store()
        ModelRole.entries.forEach { reloaded.modelFor(it) }
        assertEquals(listOf("high", "medium", "low"), setups.map { it.reasoningEffort })
        assertEquals(listOf("low", "medium", "high"), reloaded.provider(p.id)!!.models!!.first().reasoningEfforts)
        assertEquals(emptyList<String>(), reloaded.provider(p.id)!!.models!![1].reasoningEfforts)
        assertTrue(reloaded.setReasoningEffort(ModelRole.Light, null))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Light))
        assertEquals("high", store().roles.value.reasoningEffort(ModelRole.Main))
    }

    @Test fun followingMainInheritsEffortAndChangingModelClearsOnlyItsOwnChoice() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        assertFalse(s.setReasoningEffort(ModelRole.Light, "low"))
        s.modelFor(ModelRole.Light)
        assertEquals("high", setups.last().reasoningEffort)
        s.use(ModelRole.Light, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Light, "low")
        s.use(ModelRole.Main, p.id, "other", caps)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Main))
        assertEquals("low", s.roles.value.reasoningEffort(ModelRole.Light))
        s.choose(ModelRole.Light, RoleChoice.SameAsMain)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Light))
        assertFalse(s.roles.value.reasoningEfforts.containsKey(ModelRole.Light))
    }

    @Test fun unsupportedLevelsAreRejectedAndOldSettingsKeepServerDefault() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "max"))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Main))
        val saved = Json.parseToJsonElement(tmp.root.resolve("providers.json").readText()).jsonObject
        assertFalse(saved["uses"]!!.jsonObject["Main"]!!.jsonObject.containsKey("reasoningEffort"))
        store().modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
        s.use(ModelRole.Main, p.id, "other", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "low"))
        assertTrue(s.setReasoningEffort(ModelRole.Main, null))
    }

    @Test fun providerReportedLevelsWorkForEveryProtocol() {
        val cfg = ProviderConfig("c", ProviderConfig.Kind.OpenAiCompat, "https://example.test", "m", caps)
        assertNull(ReasoningOptions.of(cfg, null))
        val chat = ListedModel(
            "m", endpoint = ModelEndpoint(ModelProtocol.ChatCompletions, "https://example.test"),
            reasoningEfforts = listOf("low", "high"),
        )
        listOf(ModelProtocol.ChatCompletions, ModelProtocol.Messages, ModelProtocol.GenerateContent, ModelProtocol.Responses)
            .forEach { protocol ->
                val listed = chat.copy(endpoint = ModelEndpoint(protocol, "https://example.test"))
                assertEquals(listOf("low", "high"), ReasoningOptions.of(cfg, listed)!!.levels)
                assertTrue(ReasoningOptions.of(cfg, listed)!!.fromProvider)
            }
    }

    @Test fun manualLevelsFollowTheProtocolAndNeedReasoningSupport() {
        val direct = ProviderConfig("c", ProviderConfig.Kind.OpenAiResponses, "https://example.test", "m", caps)
        assertFalse(ReasoningOptions.of(direct, null)!!.fromProvider)
        assertEquals(emptyList<String>(), ReasoningOptions.of(direct, ListedModel("m", reasoning = false))!!.levels)
        val xai = ProviderConfig("x", ProviderConfig.Kind.Xai, "https://example.test", "m", caps)
        assertFalse(ReasoningOptions.of(xai, null)!!.fromProvider)
        // のサブスクリプション ログインはリストに記載されている他のプロトコルに従いません。ギアも実際の応答に従って与えられます (モデルの構築と同じ判断)。
        val viaMessages = ListedModel("m", endpoint = ModelEndpoint(ModelProtocol.Messages, "https://example.test"))
        assertFalse(ReasoningOptions.of(xai, viaMessages)!!.fromProvider)
        assertEquals(ProviderConfig.Kind.Xai, ProviderStore.adapterFor(xai, viaMessages.endpoint).first)
        // のリストはまだロードされていません。または、互換性のあるインターフェイス、メッセージ、generateContent などのレベルは示されておらず、推測することもできません。
        val thinking = ListedModel("m", reasoning = true)
        for (kind in listOf(ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini)) {
            assertNull(ReasoningOptions.of(direct.copy(kind = kind), null))
            assertNull(ReasoningOptions.of(direct.copy(kind = kind), thinking))
        }
    }

    // Anthropic リストに記載されているギアは受け入れられます。予算を考慮して、オプションのギアがなく、ギアを受け入れないモデルのみを検討できます。
    @Test fun anthropicOffersExactlyTheCatalogLevels() {
        val cfg = ProviderConfig("c", ProviderConfig.Kind.Anthropic, "https://api.anthropic.example", "m", caps)
        val adaptive = ListedModel("m", reasoning = true, reasoningEfforts = listOf("low", "medium", "high"),
            thinkingTypes = listOf("adaptive", "enabled"))
        assertEquals(listOf("low", "medium", "high"), ReasoningOptions.of(cfg, adaptive)!!.levels)
        // は無効を受け入れないため、リストには何も表示されません。
        assertFalse("none" in ReasoningOptions.of(cfg, adaptive)!!.levels)
        val budgetOnly = ListedModel("m", reasoning = true, reasoningEfforts = emptyList(), thinkingTypes = listOf("enabled", "disabled"))
        assertEquals(emptyList<String>(), ReasoningOptions.of(cfg, budgetOnly)!!.levels)

        val s = store()
        val p = s.connect("A", ProviderConfig.Kind.Anthropic, "https://api.anthropic.example", "k", listOf(budgetOnly))
        s.use(ModelRole.Main, p.id, "m", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "high"))
        assertFalse(s.setReasoningEffort(ModelRole.Main, "none"))
    }

    // リストで指定された思考方法はリストとともに保存され、モデルの構築時にアダプターに渡されます。古いバージョンで保存されたリストにはこの項目はなく、歯車も指定されておらず、エラーも発生していません
    @Test fun thinkingTypesSurviveReloadAndOldListsGetNoOptions() {
        val s = store()
        val listed = ListedModel("m", reasoning = true, reasoningEfforts = listOf("none", "high"), thinkingTypes = listOf("adaptive", "disabled"))
        val p = s.connect("A", ProviderConfig.Kind.Anthropic, "https://api.anthropic.example", "k", listOf(listed))
        s.use(ModelRole.Main, p.id, "m", caps)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val reloaded = store()
        assertEquals(listed, reloaded.provider(p.id)!!.models!!.single())
        reloaded.modelFor(ModelRole.Main)
        assertEquals(listOf("adaptive", "disabled"), setups.last().thinkingTypes)
        assertEquals("high", setups.last().reasoningEffort)

        // は新しいフィールドを削除し、古いバージョンで保存されたリストをシミュレートします
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace(Regex(""","reasoningEfforts":\[[^]]*]"""), "")
            .replace(Regex(""","thinkingTypes":\[[^]]*]"""), ""))
        val old = store()
        val oldListed = old.provider(p.id)!!.models!!.single()
        assertNull(oldListed.reasoningEfforts)
        assertNull(oldListed.thinkingTypes)
        assertNull(ReasoningOptions.of(old.configFor(ModelRole.Main)!!, oldListed))
    }

    @Test fun metadataRefreshDropsAnUnavailableChoiceAndUsesServerDefault() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoning = false)))
        val reloaded = store()
        assertNull(reloaded.roles.value.reasoningEffort(ModelRole.Main))
        assertFalse(reloaded.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(reloaded.setReasoningEffort(ModelRole.Main, null))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun chatGptCatalogCannotIntroduceUltraAndAnOldUltraChoiceFallsBackToDefault() {
        val s = store()
        val p = s.connect("ChatGPT", ProviderConfig.Kind.ChatGpt, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoningEfforts = listOf("low", "ultra", "max"))))
        s.use(ModelRole.Main, p.id, "m", caps)
        val listed = s.provider(p.id)!!.models!!.single()
        assertEquals(listOf("low", "max"), ReasoningOptions.of(s.configFor(ModelRole.Main)!!, listed)!!.levels)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "ultra"))
        assertTrue(s.setReasoningEffort(ModelRole.Main, "low"))
        // 模拟旧版本已经落盘的 ultra:读入时清掉,不能继续发出去让每次调用都 HTTP 400。
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace("\"reasoningEffort\":\"low\"", "\"reasoningEffort\":\"ultra\""))
        val reloaded = store()
        assertNull(reloaded.roles.value.reasoningEffort(ModelRole.Main))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun savedRoleEffortReachesRealResponsesAdapter() = runTest {
        MockWebServer().use { server ->
            server.start()
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, server.url("/v1").toString(), "k",
                listOf(ListedModel("m", reasoningEfforts = listOf("high"))))
            s.use(ModelRole.Main, p.id, "m", caps)
            s.setReasoningEffort(ModelRole.Main, "high")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"))
            s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test fun savedRoleEffortReachesEveryAdapter() = runTest {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            for (protocol in listOf(ModelProtocol.ChatCompletions, ModelProtocol.Messages, ModelProtocol.GenerateContent)) {
                val s = ProviderStore(tmp.newFolder("p-${protocol.wire}"), AesGcmKeyCipher { key })
                val p = s.connect("P", ProviderConfig.Kind.of(protocol), base, "k",
                    listOf(ListedModel("m", endpoint = ModelEndpoint(protocol, base), reasoningEfforts = listOf("high"))))
                s.use(ModelRole.Main, p.id, "m", caps)
                assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
                val reply = when (protocol) {
                    ModelProtocol.ChatCompletions -> """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
                    ModelProtocol.Messages -> """{"content":[],"stop_reason":"end_turn"}"""
                    else -> """{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"""
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(reply))
                s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val sent = when (protocol) {
                    ModelProtocol.ChatCompletions -> body["reasoning_effort"]
                    ModelProtocol.Messages -> body["output_config"]!!.jsonObject["effort"]
                    else -> body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]
                }
                assertEquals("high", sent!!.jsonPrimitive.content.lowercase())
            }
        }
    }
}
