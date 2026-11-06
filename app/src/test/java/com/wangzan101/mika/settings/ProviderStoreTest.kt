package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class ProviderStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val built = mutableListOf<Pair<ProviderConfig, String?>>()
    private val endpoints = mutableListOf<ModelEndpoint?>()

    private val setups = mutableListOf<ClientSetup>()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3") { cfg, apiKey, endpoint, setup ->
        built += cfg to apiKey
        setups += setup
        endpoints += endpoint
        FakeModel(cfg.capabilities)
    }

    @Test fun explicitSelfHostedIntentSurvivesReloadAndNameChanges() {
        val s = store()
        val p = s.connect("Ollama", ProviderConfig.Kind.OpenAiCompat, "https://models.example.com/v1", "test-key", emptyList(), explicitSelfHosted = true)
        assertTrue(p.explicitSelfHosted)
        // 同じ接続を再検査しても、本人が選んだ自己ホストの意図を消さない。
        assertTrue(s.connect(p.name, p.kind, p.baseUrl, null, emptyList()).explicitSelfHosted)
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("\"name\":\"Ollama\"", "\"name\":\"My renamed models\""))
        val reloaded = store().provider(p.id)!!
        assertEquals("My renamed models", reloaded.name)
        assertTrue(reloaded.explicitSelfHosted)
        assertTrue(ModelOptions.allowsChineseModels(reloaded))
    }

    @Test fun newOrdinaryProxyDoesNotBecomeSelfHostedAfterReload() {
        val s = store()
        val p = s.connect("Ollama", ProviderConfig.Kind.OpenAiCompat, "https://relay.example.com/v1", "test-key", emptyList())
        assertFalse(p.explicitSelfHosted)
        assertFalse(store().provider(p.id)!!.explicitSelfHosted)
        assertFalse(ModelOptions.allowsChineseModels(store().provider(p.id)!!))
    }

    @Test fun oldJsonInfersOnlyRecognisedSelfHostedPresetsAndKeepsProxyDefaultsFalse() {
        File(tmp.root, "providers.json").writeText(
            """{"services":[
                {"id":"old-local","name":"Ollama","kind":"OpenAiCompat","baseUrl":"https://model-host.example.com/v1"},
                {"id":"old-proxy","name":"Gateway","kind":"OpenAiCompat","baseUrl":"https://relay.example.com/v1"},
                {"id":"known-cloud","name":"Ollama","kind":"OpenAiCompat","baseUrl":"https://openrouter.ai/api/v1"},
                {"id":"explicit-false","name":"Ollama","kind":"OpenAiCompat","baseUrl":"https://other-host.example.com/v1","explicitSelfHosted":false},
                {"id":"malformed","name":"Ollama","kind":"OpenAiCompat","baseUrl":"https://third-host.example.com/v1","explicitSelfHosted":{}}
            ],"providers":[],"uses":{}}""",
        )
        val loaded = store()
        assertTrue(loaded.provider("old-local")!!.explicitSelfHosted)
        assertFalse(loaded.provider("old-proxy")!!.explicitSelfHosted)
        assertFalse(loaded.provider("known-cloud")!!.explicitSelfHosted)
        assertFalse(loaded.provider("explicit-false")!!.explicitSelfHosted)
        assertFalse(loaded.provider("malformed")!!.explicitSelfHosted)
        assertTrue(store().provider("old-local")!!.explicitSelfHosted)
        assertFalse(store().provider("old-proxy")!!.explicitSelfHosted)
    }

    private fun cfg(id: String, tools: Boolean = true, vision: Boolean = false) = ProviderConfig(
        id = id, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://example.test/v1", model = "m-$id",
        capabilities = Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false),
        name = "P $id",
    )

    private val none = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false)

    /** 構成を保存し、それをメイン モデルにします (誰も使用しない構成はクリアされます)。 */
    private fun ProviderStore.saveAsMain(c: ProviderConfig, apiKey: String?) {
        save(c, apiKey)
        assertTrue(choose(ModelRole.Main, RoleChoice.Model(c.id)))
    }

    private fun connected(s: ProviderStore, name: String = "P", key: String = "k") =
        s.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", key, listOf(ListedModel("m1"), ListedModel("m2")))

    // ---------- アプリケーションの選択 ----------

    @Test fun modelContextSurvivesPruningAndIsSharedOnlyWithinTheSameProviderAndModel() {
        val s = store()
        val first = connected(s)
        val second = connected(s, "Other")
        s.use(ModelRole.Main, first.id, "m1", none)
        s.use(ModelRole.Vision, second.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(1_000_000, 600_000)
        assertTrue(s.setModelContext(cfg.id, saved))
        for (role in listOf(ModelRole.Main, ModelRole.Light)) {
            val caps = s.modelFor(role)!!.capabilities
            assertEquals(1_000_000, caps.contextTokens)
            assertEquals(600_000, caps.compactionTriggerTokens)
        }
        assertEquals(64_000, s.modelFor(ModelRole.Vision)!!.capabilities.contextTokens)
        assertNull(s.modelFor(ModelRole.Vision)!!.capabilities.compactionTriggerTokens)
        s.use(ModelRole.Main, first.id, "m2", none)
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings()))
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        assertEquals(600_000, reloaded.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(reloaded.setModelContext(reloaded.configFor(ModelRole.Main)!!.id, ModelContextSettings()))
        assertNull(store().provider(first.id)!!.contextSettings["m1"])
    }

    @Test fun perModelReadTimeoutFlowsIntoTheClientSetupAndPersists() {
        // #295:慢速本地推理服务(例如经隧道访问)一次生成可以超过全局默认的 120 秒读超时;
        // 每模型配置的读超时随 ModelContextSettings 存取,并进到建模型用的 ClientSetup 里。
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(readTimeoutSeconds = 600)
        assertTrue(s.setModelContext(cfg.id, saved))
        s.modelFor(ModelRole.Main)
        assertEquals(600, setups.last().readTimeoutSeconds)
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        reloaded.modelFor(ModelRole.Main)
        assertEquals(600, setups.last().readTimeoutSeconds)
    }

    @Test fun configuredReadTimeoutAppliesToTheRealHttpClient() = runTest {
        MockWebServer().use { server ->
            server.start()
            // 响应头故意晚于单模型的一秒读超时;若配置没进入 OkHttp,这里不会抛 Network。
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Local", ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "k", listOf(ListedModel("m")))
            s.use(ModelRole.Main, p.id, "m", none)
            val cfg = s.configFor(ModelRole.Main)!!
            assertTrue(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 1)))
            try {
                s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
                fail("Expected the configured read timeout")
            } catch (_: ModelError.Network) {
                // 真实适配器在读取响应头时超时,而不只是 ClientSetup 里保存了一个数字。
            }
        }
    }

    @Test fun aNonPositiveReadTimeoutIsRejected() {
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 0)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = -5)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = Int.MAX_VALUE)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = ModelContextSettings.MAX_READ_TIMEOUT_SECONDS + 1)))
        assertTrue(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = ModelContextSettings.MAX_READ_TIMEOUT_SECONDS)))
        s.modelFor(ModelRole.Main)
        assertEquals(ModelContextSettings.MAX_READ_TIMEOUT_SECONDS, setups.last().readTimeoutSeconds)
    }

    @Test fun manualTriggerUsesCoreBoundsAndASmallerReselectedWindowKeepsTheStoredValue() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 1_000_000))
        val id = s.configFor(ModelRole.Main)!!.id
        for (value in listOf(500_000, 980_000, 950_000)) {
            assertTrue(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        for (value in listOf(0, 499_999, 980_001)) {
            assertFalse(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        assertFalse(s.setModelContext(id, ModelContextSettings(windowTokens = -1)))
        s.use(ModelRole.Main, p.id, "m2", none)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 32_000))
        val saved = store().provider(p.id)!!.contextSettings.getValue("m1")
        assertEquals(950_000, saved.triggerTokens)
        assertEquals(24_000, saved.limits(s.modelFor(ModelRole.Main)!!.capabilities.contextTokens).trigger)
    }

    @Test fun missingContextFieldsUseAutoAndMalformedEntriesDoNotDiscardOtherModels() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val file = File(tmp.root, "providers.json")
        val original = file.readText()
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        val entries = """{"m1":{"windowTokens":1000000},"m2":{"triggerTokens":600000},"bad":{"triggerTokens":{}}}"""
        file.writeText(original.replace("\"services\":[{", "\"services\":[{\"contextSettings\":$entries,"))
        assertEquals(mapOf("m1" to ModelContextSettings(1_000_000), "m2" to ModelContextSettings(triggerTokens = 600_000)),
            store().provider(p.id)!!.contextSettings)
    }

    @Test fun failedContextSaveLeavesBothTheStoredAndActiveModelAloneAndCanBeRetried() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val id = s.configFor(ModelRole.Main)!!.id
        val saved = ModelContextSettings(triggerTokens = 40_000)
        val blocked = File(tmp.root, "providers.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(s.setModelContext(id, saved))
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(blocked.delete())
        assertNull(store().provider(p.id)!!.contextSettings["m1"])
        assertTrue(s.setModelContext(id, saved))
        assertEquals(saved, store().provider(p.id)!!.contextSettings["m1"])
    }

    @Test
    fun freshInstallHasNoMainVisionOffAndLightSameAsMain() {
        val s = store()
        assertEquals(RoleChoices(main = null, vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.modelFor(ModelRole.Main))
        assertNull(s.modelFor(ModelRole.Vision))
        assertNull(s.modelFor(ModelRole.Light))
    }

    @Test
    fun lightSameAsMainResolvesToMainAndVisionStaysOffEvenIfMainCanSee() {
        val s = store()
        s.saveAsMain(cfg("a", vision = true), "sk-a")
        assertEquals("a", s.configFor(ModelRole.Light)?.id)
        // には、「メインモデルが画像を表示できる場合は、メインモデルも画像を表示できる」という暗黙のルールがなくなりました。
        assertNull(s.configFor(ModelRole.Vision))
        val m = s.modelFor(ModelRole.Main)
        assertEquals("使用量計測でラップしても能力を維持する", cfg("a", vision = true).capabilities, m?.capabilities)
        assertEquals("a" to "sk-a", built.single().first.id to built.single().second)
    }

    @Test
    fun visionSameAsMainUsesMainAndMainItselfSeesUntilSwitchedAway() {
        val s = store()
        s.saveAsMain(cfg("a", vision = false), "sk-a")
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        val v = s.configFor(ModelRole.Vision)!!
        assertEquals("a", v.id)
        assertTrue("メインモデルを画像対応と指定した場合、画像用の経路でもスクリーンショットを送る", v.capabilities.vision)
        s.modelFor(ModelRole.Vision)
        assertTrue(built.last().first.capabilities.vision)
        assertEquals("sk-a", built.last().second)
        assertTrue("メインモデルを画像対応と指定した場合、メインモデル自身の作業でもスクリーンショットを直接参照する", s.configFor(ModelRole.Main)!!.capabilities.vision)
        // はまだ再開しています
        assertEquals(RoleChoice.SameAsMain, store().roles.value.vision)
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Off))
        assertFalse("別のモデルへ切り替えたら保存済みのフラグに戻す", s.configFor(ModelRole.Main)!!.capabilities.vision)
    }

    @Test
    fun pickingTheMainModelAsVisionModelMakesMainSeeAndSwitchingAwayUndoesIt() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m1", none))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        s.modelFor(ModelRole.Main)
        assertTrue("生成したメインモデルには画像を添付する", built.last().first.capabilities.vision)
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        assertFalse("画像用を別モデルへ変更したら、メインモデルは一覧と初期値の能力に戻す", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertFalse("保存済みの設定を変更しない", store().list().first { it.model == "m1" }.capabilities.vision)
    }

    @Test
    fun userSaysModelCanSeeOverridesUnknownAndPersists() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertFalse("一覧に記載がなければ初期値を使う", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val reopened = store()
        assertEquals(mapOf("m1" to true), reopened.provider(p.id)!!.visionOverrides)
        assertTrue("開き直しても保持される", reopened.configFor(ModelRole.Main)!!.capabilities.vision)
        // ユーザーはこれをオフにすることもできます。メイン モデルを使用して写真を表示する場合、メイン モデルはユーザーが言ったように写真を添付せず、写真を表示するプロセスは同じままです。
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertTrue(s.setModelVision(p.id, "m1", false))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.vision)
        assertFalse(s.setModelVision("missing", "m1", true))
    }

    @Test
    fun listingSaysNoVisionStaysNoUnlessTheUserOverrides() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(ListedModel("m1", toolCalls = true, vision = false)))
        val o = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertFalse(o.canSee)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", o.capabilities))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val after = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertTrue(after.canSee)
        assertEquals(true, after.visionByUser)
    }

    @Test
    fun visionSameAsMainWithoutMainHasNoVisionModel() {
        val s = store()
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertNull(s.configFor(ModelRole.Vision))
    }

    @Test
    fun choicesTheRoleDoesNotTakeAreRejected() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertFalse(s.choose(ModelRole.Main, RoleChoice.SameAsMain))
        assertFalse(s.choose(ModelRole.Main, RoleChoice.Off))
        assertFalse(s.choose(ModelRole.Light, RoleChoice.Off))
        assertFalse("存在しない設定", s.choose(ModelRole.Main, RoleChoice.Model("zzz")))
        assertEquals(RoleChoices(main = "a"), s.roles.value)
    }

    @Test
    fun explicitChoiceIsNotBlockedByCapabilityFlagsAndMarksThem() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        val main = s.configFor(ModelRole.Main)!!
        val vision = s.configFor(ModelRole.Vision)!!
        assertEquals("m1" to "m2", main.model to vision.model)
        assertTrue("メインモデルに選んだらツール呼び出し対応として記録する", main.capabilities.toolCalls)
        assertTrue("画像用に選んだら画像対応として扱う", vision.capabilities.vision)
        // は軽量で、何の能力も必要ありません
        assertTrue(s.use(ModelRole.Light, p.id, "m2", none))
        assertEquals(vision.id, s.configFor(ModelRole.Light)?.id)
        assertFalse(s.use(ModelRole.Main, "missing", "m1", none))
    }

    @Test
    fun usingTheSameModelReusesItsConfig() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        assertEquals(1, s.list().size)
        assertEquals(s.roles.value.main, (s.roles.value.light as RoleChoice.Model).configId)
        assertEquals("k", s.apiKey(s.roles.value.main!!))
    }

    @Test
    fun lightUsersGetTheLightModelAndNotTheMainOne() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        built.clear()
        s.modelFor(ModelRole.Light)
        assertEquals("m2" to "k", built.single().first.model to built.single().second)
        built.clear()
        s.modelFor(ModelRole.Main)
        assertEquals("m1", built.single().first.model)
    }

    // ---------- 誰も使用していない設定を削除 ----------

    @Test
    fun configsNoRoleUsesAreDeletedButTheProviderKeyStays() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertEquals(listOf("m1", "m2"), s.list().map { it.model })
        // 軽量をメインモデルに戻しました: m2 はもう誰も使っていません
        assertTrue(s.choose(ModelRole.Light, RoleChoice.SameAsMain))
        assertEquals(listOf("m1"), s.list().map { it.model })
        // がメインモデルを変更しました: m1 はもう誰も使用しません
        s.use(ModelRole.Main, p.id, "m2", none)
        assertEquals(listOf("m2"), s.list().map { it.model })
        assertEquals("k", s.providerKey(p.id))
        assertEquals("k", s.apiKey(s.list().single().id))
        assertEquals(listOf("m2"), store().list().map { it.model })
    }

    @Test
    fun visionSameAsMainKeepsTheMainConfigAndNothingElse() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertEquals(listOf("m1"), s.list().map { it.model })
        assertEquals(setOf(s.roles.value.main), s.roles.value.referenced)
    }

    @Test
    fun loadDropsUnusedConfigsAndChoicesPointingNowhere() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("b"), null)
        assertEquals(2, s.list().size)
        val reopened = store()
        assertEquals(listOf("a"), reopened.list().map { it.id })
        // 手動で変更されたファイル: 画像は存在しない構成を指しています。それを返し、使用しないでください。
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace("\"Vision\":{\"use\":\"none\"}", "\"Vision\":{\"use\":\"model\",\"config\":\"gone\"}"))
        assertEquals(RoleChoice.Off, store().roles.value.vision)
    }

    // ---------- Key ----------

    @Test
    fun keyIsNotStoredInPlainPrefs() {
        val secret = "sk-very-secret-0123456789"
        store().saveAsMain(cfg("a"), secret)
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("plaintext key found in ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        assertEquals(secret, store().apiKey("a"))
    }

    @Test
    fun saveWithNullKeyKeepsExistingKey() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("a").copy(model = "other"), null)
        assertEquals("sk-a", s.apiKey("a"))
        assertEquals("other", s.list().single().model)
        assertTrue(s.hasKey(s.list().single().providerId))
    }

    @Test
    fun undecryptableKeyReadsAsMissing() {
        store().saveAsMain(cfg("a"), "sk-a")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(ProviderStore(tmp.root, AesGcmKeyCipher { otherKey }).apiKey("a"))
    }

    /** トライアル コールに合格したモデル名はサービス プロバイダーに保存されます。同じプロバイダーに再度接続するとマージされます。古いファイルにこの項目がない場合は空になります。 */
    @Test
    fun verifiedModelsPersist() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), verified = setOf("a"))
        assertEquals(setOf("a"), p.verifiedModels)
        s.markVerified(p.id, "b")
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", null, emptyList(), verified = setOf("c"))
        assertEquals(setOf("a", "b", "c"), store().provider(p.id)?.verifiedModels)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(Regex(",\"verifiedModels\":\\[[^\\]]*\\]"), ""))
        assertFalse(f.readText().contains("verifiedModels"))
        assertEquals(emptySet<String>(), store().provider(p.id)?.verifiedModels)
    }

    @Test
    fun connectedProviderWithoutModelsPersistsWithItsListAndKey() {
        val s = store()
        val listed = listOf(ListedModel("a", displayName = "A", toolCalls = true, contextTokens = 128_000), ListedModel("b", vision = false))
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "sk-d", listed)
        assertTrue("接続済みでも、まだモデルは選択されていない", s.list().isEmpty())
        assertNull(s.roles.value.main)
        val reopened = store()
        assertEquals(listOf(p), reopened.providers.value)
        assertEquals(listed, reopened.providers.value.single().models)
        assertEquals("sk-d", reopened.providerKey(p.id))
        // 同じ会社に再度接続します: キーとリストを更新します。繰り返し追加しないでください。
        val again = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "sk-new", listOf(ListedModel("c")))
        assertEquals(p.id, again.id)
        assertEquals(1, s.providers.value.size)
        assertEquals("sk-new", s.providerKey(p.id))
        assertEquals(listOf("c"), store().providers.value.single().models!!.map { it.id })
        // リストされていない場合は、null です。リストされていてもリストされていない場合、それは空のリストです。
        s.rememberModels(p.id, emptyList())
        assertEquals(emptyList<ListedModel>(), store().providers.value.single().models)
    }

    // ---------- テストをスキップして直接保存 ----------

    /** はテストをスキップし、マークを付けて保存します。ファイルの再読み込みも含まれます (古いバージョンのファイルにはこの項目はありません。テストに基づいて計算されます)。 */
    @Test
    fun untestedProviderPersistsItsFlag() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        assertTrue(p.savedUntested)
        assertTrue("再読み込み後も保持される", store().provider(p.id)!!.savedUntested)
        // テストをスキップせずに保存したものにはこのマークがつきません
        val other = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", emptyList())
        assertFalse(other.savedUntested)
        assertFalse(store().provider(other.id)!!.savedUntested)
    }

    /** モデルがリストに掲載されるか、モデルがテストされると、「未テスト」は無効になります。 */
    @Test
    fun listingOrVerifyingClearsTheUntestedFlag() {
        val s = store()
        val listed = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.rememberModels(listed.id, listOf(ListedModel("m1")))
        assertFalse("一覧を取得できたらテスト済みとする", store().provider(listed.id)!!.savedUntested)

        val verified = s.connect("Z", ProviderConfig.Kind.OpenAiCompat, "https://z.test/v1", "k", emptyList(), untested = true)
        assertTrue(s.provider(verified.id)!!.savedUntested)
        s.markVerified(verified.id, "m1")
        assertFalse("動作確認済みならテスト済みとする", s.provider(verified.id)!!.savedUntested)
        assertEquals(setOf("m1"), s.provider(verified.id)!!.verifiedModels)
    }

    /** のメインモデルは、メーカーがテストに合格していない場合（リストなし、手動入力、検証なし）に与えられます。認証されている場合、または企業がリストを持っている場合はカウントされません。 */
    @Test
    fun untestedMainProviderFollowsTheMainModel() {
        val s = store()
        assertNull("メインモデルがない", s.untestedMainProvider())
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.markTyped(p.id, "m1")
        s.use(ModelRole.Main, p.id, "m1", none)
        assertEquals(p.id, s.untestedMainProvider()?.id)
        s.markVerified(p.id, "m1")
        assertNull("テストに合格した", s.untestedMainProvider())
        val listed = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", listOf(ListedModel("m2")))
        s.use(ModelRole.Main, listed.id, "m2", none)
        assertNull("一覧から選んだものは対象外", s.untestedMainProvider())
    }

    /** テストをスキップし、すでにモデルをリストしている企業用に再度保存します。既存のリストを保持し、空のリストで上書きしないでください。それでも「テスト済み」です。 */
    @Test
    fun savingUntestedAgainKeepsAnExistingModelList() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listOf(ListedModel("m1")))
        val again = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k2", emptyList(), untested = true)
        assertEquals(p.id, again.id)
        assertEquals(listOf("m1"), again.models!!.map { it.id })
        assertFalse("一覧を取得済みなら未テストとは扱わない", again.savedUntested)
        assertEquals("k2", s.providerKey(p.id))
    }

    @Test
    fun modelsOfOneProviderShareOneKey() {
        val s = store()
        val p = connected(s, key = "k1")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.updateProviderKey(p.id, "k2")
        assertEquals(listOf("k2", "k2"), s.list().map { s.apiKey(it.id) })
        s.modelFor(ModelRole.Main)
        assertEquals("k2", built.last().second)
        // キー ファイルのコピーは 1 つだけです (サービス プロバイダーによる)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(p.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun localServiceWithoutKeyHasNoKey() {
        val s = store()
        val p = s.connect("local", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        assertFalse(s.hasKey(p.id))
    }

    @Test
    fun configsExposeCurrentList() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertEquals(s.list(), s.configs.value)
        assertEquals(1, s.configs.value.size)
    }

    // ---------- サービスプロバイダーの削除 ----------

    @Test
    fun removeProviderDropsItsModelsAndKeyAndResetsItsRoles() {
        val s = store()
        val p = connected(s, key = "k1")
        s.save(cfg("other"), "sk-o")
        s.choose(ModelRole.Main, RoleChoice.Model("other"))
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        s.removeProvider(p.id)
        assertEquals(listOf("other"), s.list().map { it.id })
        assertEquals(RoleChoices(main = "other", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.providerKey(p.id))
        assertEquals(listOf("P other"), s.providers.value.map { it.name })
        // データは再起動後も一貫しています
        val reopened = store()
        assertEquals(listOf("other"), reopened.list().map { it.id })
        assertEquals(s.roles.value, reopened.roles.value)
        assertNull(reopened.providerKey(p.id))
        assertEquals("sk-o", reopened.apiKey("other"))
    }

    @Test
    fun removingTheMainProviderLeavesMainUnsetAndKeepsOtherRoles() {
        val s = store()
        val a = connected(s, "A")
        val b = connected(s, "B")
        s.use(ModelRole.Main, a.id, "m1", none)
        s.use(ModelRole.Vision, b.id, "m1", none)
        s.removeProvider(a.id)
        assertNull(s.roles.value.main)
        assertEquals(b.id, s.configFor(ModelRole.Vision)?.providerId)
        // は再起動時に残りの1台をメインモデルとみなしません（旧バージョンのルールです）
        assertNull(store().roles.value.main)
        assertEquals(b.id, store().configFor(ModelRole.Vision)?.providerId)
    }

    // ---------- 古いバージョンのファイル ----------

    private fun enc(k: String): String = java.util.Base64.getEncoder().encodeToString(AesGcmKeyCipher { key }.encrypt(k.toByteArray()))

    private fun legacy(id: String, name: String, url: String, model: String, vision: Boolean = false) =
        """{"id":"$id","name":"$name","kind":"OpenAiCompat","baseUrl":"$url","model":"$model","toolCalls":true,"vision":$vision,""" +
            """"contextTokens":64000,"reasoning":false,"groundingVerified":false,"promptCaching":"implicit"}"""

    /** 古いバージョンのファイル: モデル設定のみ。キーは設定 ID に従って保存され、明示的に指定されたロールのみが記憶されます。 */
    private fun writeLegacyFiles(roles: String = """{"Main":"c2","Vision":"c3"}""") {
        File(tmp.root, "providers.json").writeText(
            """{"providers":[""" +
                legacy("c1", "DeepSeek", "https://api.deepseek.com", "deepseek-chat") + "," +
                legacy("c2", "DeepSeek", "https://api.deepseek.com/", "deepseek-reasoner") + "," +
                legacy("c3", "", "http://192.168.1.5:8000/v1", "local", vision = true) +
                """],"roles":$roles}""",
        )
        File(tmp.root, "provider-keys.json").writeText("""{"c1":"${enc("sk-ds")}","c2":"${enc("sk-ds")}","c3":"${enc("sk-local")}"}""")
    }

    @Test
    fun legacyConfigsAreGroupedIntoProvidersOnLoad() {
        writeLegacyFiles()
        val s = store()
        assertEquals(listOf("DeepSeek", "192.168.1.5"), s.providers.value.map { it.name })
        val ds = s.providers.value[0]
        val local = s.providers.value[1]
        assertNull("モデル一覧はまだ取得していない", ds.models)
        // キーがサービスプロバイダーに返されます
        assertEquals("sk-ds", s.providerKey(ds.id))
        assertEquals("sk-local", s.providerKey(local.id))
        // 明示的に指定されたものは通常どおり残ります。誰も使用していない c1 は削除されます。
        assertEquals(RoleChoices(main = "c2", vision = RoleChoice.Model("c3"), light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c2", "c3"), s.list().map { it.id })
        assertEquals(listOf(ds.id, local.id), s.list().map { it.providerId })
        assertEquals("deepseek-reasoner", s.configFor(ModelRole.Main)?.model)
        s.modelFor(ModelRole.Vision)
        assertEquals("c3" to "sk-local", built.last().first.id to built.last().second)
        // 構成 ID に従って保存された古いキーがクリアされました。
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(ds.id, local.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun legacyDefaultsAreWrittenOutAsTheyWorked() {
        // はメイン モデルを指定しません。その時点では最初の構成が使用されます。画像を表示できないため、画像を表示する必要はありません。メインモデルを軽くフォローしてください
        writeLegacyFiles(roles = "{}")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c1"), s.list().map { it.id })
        assertTrue(File(tmp.root, "providers.json").readText().contains("\"uses\""))
    }

    @Test
    fun legacyMainThatCanSeeKeepsServingVision() {
        writeLegacyFiles(roles = """{"Main":"c3","Light":"c1"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c3", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("c1")), s.roles.value)
        assertEquals("c3", s.configFor(ModelRole.Vision)?.id)
        assertEquals(listOf("c1", "c3"), s.list().map { it.id })
    }

    @Test
    fun legacyRolesPointingToMissingConfigsFallBackToTheOldDefaults() {
        writeLegacyFiles(roles = """{"Main":"gone","Vision":"gone"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
    }

    @Test
    fun migrationRunsOnceAndRepeatsWithoutSideEffects() {
        writeLegacyFiles(roles = """{"Main":"c3"}""")
        val first = store()
        val providers = first.providers.value
        val configs = first.list()
        val roles = first.roles.value
        val configText = File(tmp.root, "providers.json").readText()
        val keyText = File(tmp.root, "provider-keys.json").readText()
        repeat(2) {
            val again = store()
            assertEquals(providers, again.providers.value)
            assertEquals(configs, again.list())
            assertEquals(roles, again.roles.value)
            assertEquals(configText, File(tmp.root, "providers.json").readText())
            assertEquals(keyText, File(tmp.root, "provider-keys.json").readText())
        }
    }

    @Test
    fun interruptedMigrationIsRedoneWithoutLosingKeys() {
        writeLegacyFiles()
        store()
        // シミュレーションは途中で中断されます。構成ファイルは古い形式に戻り、キー ファイルの古いコピーと新しいコピーの両方が存在します。
        val migratedKeys = File(tmp.root, "provider-keys.json").readText()
        writeLegacyFiles()
        val legacyKeys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
            as kotlinx.serialization.json.JsonObject
        val newKeys = kotlinx.serialization.json.Json.parseToJsonElement(migratedKeys) as kotlinx.serialization.json.JsonObject
        File(tmp.root, "provider-keys.json").writeText(kotlinx.serialization.json.JsonObject(legacyKeys + newKeys).toString())
        val s = store()
        assertEquals(2, s.providers.value.size)
        assertEquals(listOf("sk-ds", "sk-local"), listOf("c2", "c3").map { s.apiKey(it) })
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(s.providers.value.map { it.id }.toSet(), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    // ---------- 画像の位置を確認しました ----------

    @Test
    fun groundingVerifiedFollowsTheVisionModelAndPersists() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        // はモデルが表示されていないときは動きません
        s.setVisionGroundingVerified(true)
        assertFalse(s.list().single().capabilities.groundingVerified)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
        assertTrue(store().configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        // メインモデルを使用して画像を表示する場合、メインモデルのラインが変更されました。
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertFalse(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
    }

    @Test
    fun roleChoicesResolveAndValidate() {
        val r = RoleChoices(main = "a", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("b"))
        assertEquals("a", r.resolve(ModelRole.Vision))
        assertEquals("b", r.resolve(ModelRole.Light))
        assertFalse(r.sameModel(ModelRole.Main, ModelRole.Light))
        assertTrue(r.sameModel(ModelRole.Main, ModelRole.Vision))
        assertTrue(RoleChoices(main = "a").sameModel(ModelRole.Main, ModelRole.Light))
        assertFalse(RoleChoices().sameModel(ModelRole.Main, ModelRole.Light))
        assertEquals(setOf("a", "b"), r.referenced)
        assertNull(RoleChoices().resolve(ModelRole.Light))
        assertNull(r.with(ModelRole.Light, RoleChoice.Off))
        assertNull(r.with(ModelRole.Main, RoleChoice.SameAsMain))
        assertEquals(RoleChoices(main = null, vision = RoleChoice.SameAsMain, light = RoleChoice.SameAsMain), r.keepOnly(emptySet()))
    }

    @Test fun sameConnectionWithDifferentReasoningEffortsStillAllowsLightRouting() {
        val choices = RoleChoices(main = "a", light = RoleChoice.Model("a"),
            reasoningEfforts = mapOf(ModelRole.Main to "high", ModelRole.Light to "low"))
        assertFalse(choices.sameModel(ModelRole.Main, ModelRole.Light))
        assertTrue(choices.copy(light = RoleChoice.SameAsMain).sameModel(ModelRole.Main, ModelRole.Light))
        assertTrue(choices.copy(reasoningEfforts = mapOf(ModelRole.Main to "low", ModelRole.Light to "low"))
            .sameModel(ModelRole.Main, ModelRole.Light))
    }

    // ---------- モデルで指定されたインターフェイス ----------

    private val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://x.test/openai/v1", "reasoning_content")
    private val messages = ModelEndpoint(ModelProtocol.Messages, "https://x.test/anthropic/v1")
    private val generate = ModelEndpoint(ModelProtocol.GenerateContent, "https://x.test/google/v1beta")
    private val responses = ModelEndpoint(ModelProtocol.Responses, "https://x.test/openai/v1/")

    @Test
    fun perModelEndpointsPersistWithTheList() {
        val s = store()
        val listed = listOf(
            ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("c", endpoint = generate),
            ListedModel("r", endpoint = responses), ListedModel("u", unsupportedApis = listOf("realtime")), ListedModel("plain"),
        )
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listed)
        assertEquals(listed, store().providers.value.single().models)
    }

    @Test
    fun eachModelIsBuiltWithTheEndpointItsListingNamed() = runTest {
        val s = store()
        val p = s.connect(
            "X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k",
            listOf(ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("plain")),
        )
        s.use(ModelRole.Main, p.id, "b", none)
        s.use(ModelRole.Light, p.id, "plain", none)
        store().modelFor(ModelRole.Main)
        store().modelFor(ModelRole.Light)
        assertEquals(listOf(messages, null), endpoints)
        assertEquals(listOf("k", "k"), built.map { it.second })
        // は再出品され、アドレスが変更されました。次回モデルを構築するときは、新しいものを使用してください。
        val moved = messages.copy(baseUrl = "https://y.test/anthropic/v1")
        s.rememberModels(p.id, listOf(ListedModel("b", endpoint = moved)))
        s.modelFor(ModelRole.Main)
        assertEquals(moved, endpoints.last())
    }

    @Test
    fun adapterAndBaseUrlFollowTheProtocolWithoutRepeatingTheVersionPath() {
        val c = cfg("1")
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://example.test/v1", ProviderStore.adapterFor(c, null))
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://x.test/openai/v1", ProviderStore.adapterFor(c, chat))
        assertEquals(ProviderConfig.Kind.Anthropic to "https://x.test/anthropic", ProviderStore.adapterFor(c, messages))
        assertEquals(ProviderConfig.Kind.Gemini to "https://x.test/google", ProviderStore.adapterFor(c, generate))
        assertEquals(ProviderConfig.Kind.OpenAiResponses to "https://x.test/openai/v1", ProviderStore.adapterFor(c, responses))
        val anthropicConfig = c.copy(kind = ProviderConfig.Kind.Anthropic, baseUrl = "https://api.anthropic.com")
        assertEquals(ProviderConfig.Kind.Anthropic to "https://api.anthropic.com", ProviderStore.adapterFor(anthropicConfig, null))
    }

    // ---------- リクエストヘッダ ----------

    /** は、セッション ヘッダーのサービス プロバイダーを宣言します。同じ会話で構築されたモデルは同じセッション番号を持ち、会話が変更されるとセッション番号も変更されます。それぞれに User-Agent が含まれます。 */
    @Test
    fun sessionHeaderIsStableWithinAConversationAndDiffersAcross() {
        val s = store()
        val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(ListedModel("m1")), sessionHeader = "x-test-session")
        s.use(ModelRole.Main, p.id, "m1", none)
        val a = ModelSession.of("conv-a")
        s.modelFor(ModelRole.Main, a); s.modelFor(ModelRole.Light, a); s.modelFor(ModelRole.Main, ModelSession.of("conv-b"))
        val sent = setups.map { it.headers["x-test-session"] }
        assertEquals(listOf(a.id, a.id, ModelSession.of("conv-b").id), sent)
        assertTrue(sent[0] != sent[2])
        assertTrue(setups.all { it.headers["User-Agent"] == "Mika/1.2.3" })
    }

    /** リストは、モデルが応答を指します (このプロトコルのモデルのみが同じサービス プロバイダーの下で言及されています)。実際の応答アダプターにはクライアント ID とセッション番号がまだあります。 */
    @Test
    fun responsesRoutedModelCarriesTheSessionHeader() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val responsesBase = server.url("/v1").toString()
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, responsesBase))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed), sessionHeader = "x-test-session")
            s.use(ModelRole.Main, p.id, "r-only", none)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            val session = ModelSession.of("conv-a")
            s.modelFor(ModelRole.Main, session)!!.complete("s", emptyList(), emptyList())
            val req = server.takeRequest()
            assertEquals("/v1/responses", req.path)
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertEquals(session.id, req.getHeader("x-test-session"))
            assertEquals("Bearer k", req.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun noSessionHeaderForProvidersThatDoNotDeclareIt() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.modelFor(ModelRole.Main, ModelSession.of("conv-a"))
        assertEquals(mapOf("User-Agent" to "Mika/1.2.3"), setups.single().headers)
    }

    /** セッション ヘッダーはサービス プロバイダーとともに保存されます。古いバージョン (この項目なし) で保存されている場合は、一致するデフォルトのアドレスを入力すると、デフォルトのアドレスが使用されます。 */
    @Test
    fun sessionHeaderPersistsAndIsFilledFromThePresetForOldFiles() {
        val goUrl = ProviderPresets.all.first { it.sessionHeader != null }
        val s = store()
        val p = s.connect(goUrl.label, goUrl.kind, goUrl.baseUrl, "k", emptyList())
        assertEquals(goUrl.sessionHeader, p.sessionHeader)
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(",\"sessionHeader\":\"${goUrl.sessionHeader}\"", ""))
        assertFalse(f.readText().contains("sessionHeader"))
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        assertTrue("written back", f.readText().contains("sessionHeader"))
    }

    /** 実際のアダプター (クライアントの識別と宣言を含むセッション ヘッダー、および試用版ツール) を呼び出してみます。ツール呼び出しは、ツール呼び出しが返された後でのみ渡され、サーバー側のエラー レポートはそのまま戻されます。 */
    @Test
    fun probeGoesThroughTheRealAdapterWithHeaders() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val url = server.url("/v1").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", "x-test-session", "m", null, none))
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertTrue(req.getHeader("x-test-session").orEmpty().isNotEmpty())
            assertEquals("Bearer sk", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body, body.contains("\"tools\"") && body.contains("get_time"))
            assertFalse("Chat Completions には応答上限を指定しない", body.contains("max_tokens"))

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("""{"error":{"type":"MissingSessionID"}}"""))
            val e = s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", null, "m", null, none) as ProbeResult.Failed
            assertTrue(e.error.message.orEmpty(), e.error.message.orEmpty().contains("MissingSessionID"))
            assertNull(server.takeRequest().getHeader("x-test-session"))

            // 応答には応答の上限がありません。関数呼び出しが返された場合、それは渡されます
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiResponses, url, "sk", null, "m", null, none))
            val responses = server.takeRequest()
            assertEquals("/v1/responses", responses.path)
            assertEquals("Mika/1.2.3", responses.getHeader("User-Agent"))
            val responsesBody = responses.body.readUtf8()
            assertFalse(responsesBody.contains("max_output_tokens"))
            assertTrue(responsesBody.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    /** インターフェイスは開いており、モデルはツールを呼び出さずに応答するだけです。これは失敗ではありませんが、区別する必要があります。 */
    @Test
    fun probeThatGetsOnlyTextReportsNoToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"It is noon."},"finish_reason":"stop"}]}"""))
            assertEquals(ProbeResult.NoToolCall, s.probe(ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "sk", null, "m", null, none))
        } finally {
            server.shutdown()
        }
    }

    /** トライアルコールのメッセージに対する返信の上限は、ツールコール1回分です（16トークンは収容できません）。 */
    @Test
    fun probeLeavesRoomForAToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"content":[{"type":"tool_use","id":"t1","name":"get_time","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.Anthropic, server.url("/").toString(), "sk", null, "m", null, none))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"max_tokens\":1024"))
            assertTrue(body.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    // ---------- 投与量 ----------

    @Test
    fun everyModelHandedOutIsMeteredUnderTheRoleItWasAskedFor() = runTest {
        val seen = mutableListOf<Pair<UsageSource, Usage?>>()
        val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val p = connected(s, name = "甲")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Vision)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(
            listOf(
                UsageSource(p.id, "甲", "m1", UsagePurpose.Main),
                UsageSource(p.id, "甲", "m1", UsagePurpose.Vision),
                UsageSource(p.id, "甲", "m2", UsagePurpose.Light),
            ),
            seen.map { it.first },
        )
        assertEquals(Usage(3, 2), seen.first().second)
    }

    private class FakeModel(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
