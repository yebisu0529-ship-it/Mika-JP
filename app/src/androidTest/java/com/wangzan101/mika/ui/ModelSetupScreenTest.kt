package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder

/** ようこそページ、モデル設定ページ (開始とセットアップに共通)、使用状況ページの選択、サービスプロバイダーの詳細、すべてのモデル、および「使用」パネル (偽のデータのみ、インターネットには接続されていません)。 */
@RunWith(AndroidJUnit4::class)
class ModelSetupScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val calls = mutableListOf<String>()
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)

    private val actions = ModelSetupActions(
        selectPreset = { calls += "preset $it" }, testAndSave = { calls += "test" }, openForm = { calls += "open form" },
        saveUntested = { calls += "save untested" },
        openProvider = { calls += "provider $it" }, pickRole = { calls += "role $it" }, next = { calls += "next" },
        openUsage = { calls += "usage" }, openSignIn = { calls += "signin $it" },
    )

    private val form = AddForm.of(ProviderPresets.all.first()).copy(apiKey = "sk")
    private val deepseek = Provider("p1", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com",
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-coder", toolCalls = false)))
    private val qwen = Provider("p2", "通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-vl-max", "qwen-long").map { ListedModel(it) })
    private val main = ProviderConfig("c1", deepseek.kind, deepseek.baseUrl, "deepseek-chat", caps, deepseek.name, deepseek.id)
    private val vision = ProviderConfig("c2", qwen.kind, qwen.baseUrl, "qwen-vl-max", caps, qwen.name, qwen.id)

    private fun connected(roles: RoleChoices = RoleChoices(main = "c1")) = ModelSetupUi(
        form, providers = listOf(deepseek, qwen), configs = listOf(main, vision), roles = roles, keyed = setOf(deepseek.id, qwen.id),
    )

    private fun show(ui: ModelSetupUi, onboarding: Boolean = true) =
        compose.setContent { MikaTheme { ModelSetupContent(ui, onboarding = onboarding, actions = actions, onBack = {}) } }

    @Test fun welcomeStarts() {
        compose.setContent { MikaTheme { WelcomeScreen { calls += "start" } } }
        compose.onNodeWithText(WelcomeCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("welcome-start").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test fun firstVisitShowsFormAndNextIsDisabled() {
        show(ModelSetupUi(form))
        compose.onNodeWithTag("setup-next").assertIsNotEnabled()
        // 上に「アカウントでログイン」グループがあります。ボタンが画面外にある場合があります。まずボタンまでスクロールしてからクリックしてください。
        compose.onNodeWithTag("setup-test").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("preset-Kimi").performScrollTo().performClick()
        assertEquals(listOf("test", "preset Kimi"), calls)
    }

    /** "テストせずに直接保存してください。"まず質問してください。結果を明確に説明してください。キャンセルした場合は何もしません。保存する前に確認してください。 */
    @Test fun saveWithoutTestAsksBeforeSaving() {
        show(ModelSetupUi(form))
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        compose.onNodeWithText(ModelSetupCopy.SAVE_UNTESTED_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModelSetupCopy.SAVE_UNTESTED_BODY).assertIsDisplayed()
        // キャンセル: 何も保存されませんでした
        compose.onNodeWithText(appString(R.string.cancel)).performClick()
        assertTrue(calls.isEmpty())
        // 確認: 保存
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        compose.onNodeWithText(ModelSetupCopy.SAVE_UNTESTED_CONFIRM).performClick()
        assertEquals(listOf("save untested"), calls)
    }

    @Test fun connectedProvidersAreListedAndNextEnables() {
        show(connected())
        compose.onNodeWithTag("context-main").assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.PROVIDERS).assertIsDisplayed()
        compose.onNodeWithText(listOf(appString(R.string.phone_connected), appPlural(R.plurals.models_count, 2), appString(R.string.models_used_by, appString(R.string.role_main) + appString(R.string.list_sep) + appString(R.string.role_light))).joinToString(" · ")).assertIsDisplayed()
        // 軽量カードには、「メイン モデルを使用」の下にメイン モデルの名前が記載されているため、メイン モデル カードでのみ見つけることができます。
        compose.onNodeWithTag("role-main").assertTextContains("deepseek-chat", substring = true)
        // フォームは閉じられており、入り口が残っています
        compose.onNodeWithText(ModelsCopy.ADD_PROVIDER).performClick()
        compose.onNodeWithTag("provider-通义千问").performClick()
        compose.onNodeWithTag("role-vision").performScrollTo().performClick()
        compose.onNodeWithTag("setup-next").assertIsEnabled().performClick()
        assertEquals(listOf("open form", "provider p2", "role Vision", "next"), calls)
    }

    @Test fun settingsPageShowsAllThreeRolesWithoutBottomButton() {
        show(connected(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain)), onboarding = false)
        compose.onNodeWithText(ModelsCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.LEAD).assertDoesNotExist()
        // 変更はすぐに有効になり、左上隅に戻るだけです。
        compose.onNodeWithTag("setup-next").assertDoesNotExist()
        ModelRole.entries.forEach { compose.onNodeWithText(ModelsCopy.roleLabel(it)).assertIsDisplayed() }
        compose.onNodeWithText(ModelsCopy.OFF).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.OFF_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SAME_AS_MAIN).assertIsDisplayed()
        compose.onNodeWithTag("role-light").performClick()
        assertEquals(listOf("role Light"), calls)
    }

    @Test fun settingsPageOpensTokenUsageButOnboardingDoesNotShowIt() {
        show(connected(), onboarding = false)
        compose.onNodeWithText(UsageCopy.TITLE).performScrollTo().performClick()
        assertEquals(listOf("usage"), calls)
        calls.clear()
        show(connected(), onboarding = true)
        compose.onNodeWithText(UsageCopy.TITLE).assertDoesNotExist()
    }

    @Test fun contextFormValidatesPreviewsAndSavesTheModelUsedForNewTasks() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("Fixture has no API key") })
        val provider = store.connect("Test", deepseek.kind, "https://example.test/v1", null, listOf(ListedModel("m")))
        store.use(ModelRole.Main, provider.id, "m", caps.copy(contextTokens = 32_000))
        compose.setContent { MikaTheme { ModelSetupScreen(store, onboarding = false, onBack = {}, onDone = {}) } }
        val open = compose.onNodeWithTag("context-main")
        val window = compose.onNodeWithTag("context-window")
        val trigger = compose.onNodeWithTag("context-trigger")
        val readTimeout = compose.onNodeWithTag("context-read-timeout")
        val save = compose.onNodeWithTag("context-save")
        val preview = compose.onNodeWithTag("context-effective")
        compose.onNodeWithTag("context-light").assertDoesNotExist()
        open.performScrollTo().performClick()
        preview.assertTextContains("18000", substring = true)
        for (invalid in listOf("0", "-1", "1e6", "2147483648")) {
            window.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        window.performTextReplacement("1000000")
        preview.assertTextContains("967000", substring = true)
        for (invalid in listOf("499999", "980001", "+600000")) {
            trigger.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        trigger.performTextReplacement("600000")
        for (invalid in listOf("0", "-1", "1e6", "2147483648")) {
            readTimeout.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        readTimeout.performTextReplacement("600")
        save.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1_000_000, store.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
            assertEquals(600_000, store.modelFor(ModelRole.Light)!!.capabilities.compactionTriggerTokens)
        }
        open.performScrollTo().performClick()
        trigger.assertTextContains("600000")
        readTimeout.assertTextContains("600")
        compose.onNodeWithText(appString(R.string.reset)).performScrollTo().performClick()
        preview.assertTextContains("18000", substring = true)
        compose.onNodeWithText(appString(R.string.cancel)).performClick()
        assertEquals(ModelContextSettings(1_000_000, 600_000, 600), store.provider(provider.id)!!.contextSettings["m"])
        open.performScrollTo().performClick()
        compose.onNodeWithText(appString(R.string.reset)).performScrollTo().performClick()
        save.performClick()
        compose.runOnIdle { assertEquals(null, store.provider(provider.id)!!.contextSettings["m"]) }
    }

    @Test fun visionPickerOffersSameAsMainAndOffAndListsEveryModel() {
        val picked = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                RolePickerContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Off)), ModelRole.Vision,
                    RolePickerActions(pick = { p, m -> picked += "$p $m" }, choose = { picked += "$it" }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.VISION_SAME_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SAME_AS_MAIN).performClick()
        compose.onNodeWithText(ModelsCopy.OFF).performClick()
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        // は、機能に基づいていない場合はグレー表示されます
        compose.onNodeWithText("deepseek-coder").assertIsEnabled().performClick()
        compose.onNodeWithText("qwen-long").performScrollTo().performClick()
        assertEquals(listOf("${RoleChoice.SameAsMain}", "${RoleChoice.Off}", "p1 deepseek-coder", "p2 qwen-long"), picked)
    }

    @Test fun lightPickerOffersOnlySameAsMain() {
        compose.setContent { MikaTheme { RolePickerContent(connected(), ModelRole.Light, RolePickerActions(), onBack = {}) } }
        compose.onNodeWithText(ModelsCopy.LIGHT_SAME_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.OFF).assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.roleHint(ModelRole.Light)).assertIsDisplayed()
    }

    @Test fun pickerFiltersWhenThereAreManyModels() {
        val many = qwen.copy(models = (1..9).map { ListedModel("qwen-$it") })
        val ui = ModelSetupUi(form, providers = listOf(deepseek, many), configs = listOf(main), roles = RoleChoices(main = "c1"))
        compose.setContent { MikaTheme { RolePickerContent(ui, ModelRole.Main, RolePickerActions(), onBack = {}) } }
        compose.onNodeWithTag("model-filter").performTextInput("qwen-9")
        // フィルター ボックスにはこれらの単語もあります。入力ボックスにない行を見てください。
        compose.onNode(hasText("qwen-9") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("qwen-1").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
    }

    @Test fun providerDetailPreviewsInUseModelsFirstAndLinksToAll() {
        val got = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"))), qwen,
                    ProviderDetailActions(showAll = { got += "all" }), onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.CONNECTED).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.KEY_SAVED).assertIsDisplayed()
        // 中古が先に表示されており、3つだけ配置されています。
        compose.onNodeWithTag("model-qwen-vl-max").assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.roleName(ModelRole.Vision)).assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-max").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-plus").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-turbo").assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.viewAll(5)).performScrollTo().performClick()
        assertEquals(listOf("all"), got)
        // 削除する前に、どのような目的に影響するかを説明してください。
        compose.onNodeWithText(ModelsCopy.DELETE).performScrollTo().performClick()
        compose.awaitOne(ModelsCopy.deleteBody(listOf(ModelRole.Vision)), inDialog = true).assertIsDisplayed()
    }

    @Test fun tappingAModelOpensUseAsSheetAndAssigns() {
        val used = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(connected(), deepseek, ProviderDetailActions(use = { role, m -> used += "$role $m" }), onBack = {})
            }
        }
        compose.onNodeWithTag("model-deepseek-coder").performClick()
        compose.awaitOne(ModelsCopy.useAsSubtitle("DeepSeek")).assertIsDisplayed()
        compose.awaitOne(ModelsCopy.roleName(ModelRole.Vision)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Vision deepseek-coder"), used)
    }

    @Test fun useAsSheetMarksTheCurrentRole() {
        compose.setContent { MikaTheme { ProviderDetailContent(connected(), deepseek, ProviderDetailActions(), onBack = {}) } }
        compose.onNodeWithTag("model-deepseek-chat").performClick()
        compose.awaitOne(ModelsCopy.IN_USE).assertIsDisplayed()
        compose.awaitOne(ModelSetupCopy.CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ModelsCopy.IN_USE).assertDoesNotExist()
    }

    @Test fun allModelsSearchesAndRefreshes() {
        val got = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                AllModelsContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"))), qwen,
                    ProviderDetailActions(refresh = { got += "refresh" }), onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.allModelsTitle("通义千问")).assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-long").assertIsDisplayed()
        compose.onNodeWithTag("all-models-filter").performTextInput("plus")
        compose.onNodeWithTag("model-qwen-plus").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-long").assertDoesNotExist()
        compose.onNodeWithContentDescription(ModelsCopy.REFRESH).performClick()
        assertEquals(listOf("refresh"), got)
    }

    @Test fun allModelsShowsRefreshInProgress() {
        val running = connected().copy(listing = mapOf(qwen.id to ListState.Running))
        compose.setContent { MikaTheme { AllModelsContent(running, qwen, ProviderDetailActions(), onBack = {}) } }
        compose.onNodeWithText(ModelsCopy.LISTING).assertIsDisplayed()
        compose.onNodeWithContentDescription(ModelsCopy.REFRESH).assertIsNotEnabled()
    }

    // ---------- アカウントでログイン ----------

    private val grok = Provider("p3", "xAI / Grok", ProviderConfig.Kind.Xai, "https://api.x.ai/v1", listOf(ListedModel("grok-4"), ListedModel("grok-4-fast")))

    @Test fun addFormListsAccountSignInsAboveApiKeys() {
        show(ModelSetupUi(form))
        compose.onNodeWithText(ModelsCopy.SIGN_IN_GROUP).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.KEY_GROUP).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("signin-chatgpt").performScrollTo().performClick()
        compose.onNodeWithTag("signin-xai").performScrollTo().performClick()
        compose.onNodeWithTag("signin-openrouter").performScrollTo().performClick()
        assertEquals(listOf("signin ChatGpt", "signin Xai", "signin OpenRouter"), calls)
    }

    @Test fun signedInAccountRowOpensTheProvider() {
        show(ModelSetupUi(form, formOpen = true, providers = listOf(grok), xaiAccount = SubscriptionAccount("me@example.com", signedIn = true)))
        compose.onNodeWithTag("signin-xai-signed-in", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("signin-openrouter-signed-in", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("signin-xai").performScrollTo().performClick()
        assertEquals(listOf("provider p3"), calls)
    }

    @Test fun xaiSheetWarnsFirstThenShowsTheCode() {
        val started = mutableListOf<String>()
        val actions = SignInSheetActions(start = { started += "start $it" }, cancel = { started += "cancel $it" }, useApiKey = { started += "key $it" })
        var ui by mutableStateOf(ModelSetupUi(form))
        compose.setContent { MikaTheme { SignInSheetBody(ui, SignInKind.Xai, actions) } }
        compose.onNodeWithText(ModelsCopy.signInTitle(SignInKind.Xai)).assertIsDisplayed()
        compose.onNodeWithTag("signin-risk").assertIsDisplayed()
        compose.onNodeWithTag("signin-start").performClick()
        compose.onNodeWithTag("signin-use-key").performClick()
        ui = ui.copy(accountSignIn = AccountSignInUi(AccountProvider.Xai, running = true, userCode = "KQ7M-P2XD", openUrl = "https://accounts.x.ai/device",
            expiresAtMs = System.currentTimeMillis() + 600_000))
        compose.onNodeWithTag("signin-code").assertTextContains("KQ7M-P2XD")
        compose.onNodeWithText(ModelsCopy.XAI_CODE_COPY_REOPEN).assertIsDisplayed()
        compose.onNodeWithTag("signin-cancel").performClick()
        assertEquals(listOf("start Xai", "key Xai", "cancel Xai"), started)
    }

    @Test fun openRouterSheetShowsFailureAndCanRetry() {
        val started = mutableListOf<String>()
        val ui = ModelSetupUi(form, accountSignIn = AccountSignInUi(AccountProvider.OpenRouter, failure = appString(R.string.account_fail_denied)))
        compose.setContent { MikaTheme { SignInSheetBody(ui, SignInKind.OpenRouter, SignInSheetActions(start = { started += "$it" })) } }
        compose.onNodeWithText(ModelsCopy.signInStart(SignInKind.OpenRouter)).assertIsDisplayed()
        compose.onNodeWithTag("signin-risk").assertDoesNotExist()
        compose.onNodeWithTag("signin-failed").assertTextContains(appString(R.string.account_fail_denied))
        compose.onNodeWithTag("signin-start").performClick()
        assertEquals(listOf("OpenRouter"), started)
    }

    @Test fun signedInXaiDetailShowsAccountSetMainAndThe403Note() {
        val got = mutableListOf<String>()
        val ui = ModelSetupUi(form, providers = listOf(deepseek, grok), configs = listOf(main), roles = RoleChoices(main = "c1"),
            xaiAccount = SubscriptionAccount("me@example.com", signedIn = true))
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(ui, grok, ProviderDetailActions(useAsMain = { got += "main" }, signOut = { got += "out" }), onBack = {})
            }
        }
        compose.onNodeWithText("me@example.com").assertIsDisplayed()
        compose.onNodeWithTag("account-403-note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SET_MAIN).performScrollTo().performClick()
        compose.onNodeWithText(ModelsCopy.CHATGPT_SIGN_OUT).performScrollTo().performClick()
        assertEquals(listOf("main", "out"), got)
    }
}
