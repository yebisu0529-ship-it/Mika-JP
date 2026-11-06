package com.wangzan101.mika.settings

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.ui.AddForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    /** アドレスと名前は繰り返されません (デフォルトのアドレスに従って)。すべてのクラウド サーバーは https を使用し、プレーン テキストの http は自分のマシンでのみ実行されます。 */
    @Test fun urlsAreUniqueAndOnlySelfHostedUsePlainHttp() {
        val withUrl = ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }
        assertEquals(withUrl.size, withUrl.map { it.baseUrl }.distinct().size)
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.label }.distinct().size)
        withUrl.forEach { p ->
            if (p.selfHosted) assertTrue(p.label, p.baseUrl.startsWith("http://127.0.0.1:"))
            else assertTrue(p.label, p.baseUrl.startsWith("https://"))
            assertEquals(p, ProviderPresets.forBaseUrl(p.baseUrl))
        }
    }

    /** 自己実行サービス: アドレスは変更でき、キーは空白のままにすることができ、名前は引き続きデフォルト名を使用します。デフォルトのクラウド アドレスは変更できず、キーが必要です。 */
    @Test fun selfHostedPresetsHaveEditableUrlAndOptionalKey() {
        val ollama = AddForm.of(preset("Ollama")).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(ollama.urlEditable)
        assertFalse(ollama.keyRequired)
        assertTrue("Key を入力せずにテストできる", ollama.canTest)
        assertEquals("Ollama", ollama.providerName)
        val openAi = AddForm.of(preset("OpenAI"))
        assertFalse(openAi.urlEditable)
        assertTrue(openAi.keyRequired)
        assertFalse(openAi.canTest)
        assertTrue(openAi.copy(apiKey = "sk").canTest)
    }

    /** ホスト名が一致する場合は、(パスに関係なく) ホスト名を識別できます。自分で実行するサービスや認識できないアドレスはカウントされません。 */
    @Test fun presetByHost() {
        assertEquals("Gemini", ProviderPresets.forHost("https://generativelanguage.googleapis.com")?.label)
        assertEquals("Gemini", ProviderPresets.forHost(" https://generativelanguage.googleapis.com/v1beta/openai/ ")?.label)
        assertEquals("Anthropic", ProviderPresets.forHost("https://api.anthropic.com/v1")?.label)
        assertEquals("OpenAI", ProviderPresets.forHost("https://api.openai.com")?.label)
        assertEquals("DeepSeek", ProviderPresets.forHost("https://api.deepseek.com/v1")?.label)
        assertEquals("智谱", ProviderPresets.forHost("https://open.bigmodel.cn/api/paas/v4")?.label)
        assertEquals("智谱 Coding Plan", ProviderPresets.forHost("https://open.bigmodel.cn/api/coding/paas/v4")?.label)
        assertNull(ProviderPresets.forHost("https://models.example.com/v1"))
        assertNull(ProviderPresets.forHost("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.forHost("not a url"))
        // アドレスがあり、単独で実行されないすべてのプリセットは、それ自体を認識します。
        ProviderPresets.all.filter { it.baseUrl.isNotEmpty() && !it.selfHosted }.forEach {
            assertEquals(it.label, ProviderPresets.forHost(it.baseUrl)?.label)
        }
    }

    @Test fun explicitChinaOrLocalConnectionsAllowTheirOwnModelChoices() {
        ProviderPresets.otherRegions.forEach { assertTrue(it.label, ProviderPresets.allowsChineseModels(it.baseUrl)) }
        listOf("http://localhost:11434/v1", "http://192.168.1.20:1234/v1", "http://models.local:11434/v1").forEach {
            assertTrue(it, ProviderPresets.allowsChineseModels(it))
        }
        ProviderPresets.recommended.filter { !it.selfHosted && it.baseUrl.isNotEmpty() }.forEach {
            assertFalse(it.label, ProviderPresets.allowsChineseModels(it.baseUrl))
        }
        assertFalse(ProviderPresets.allowsChineseModels("https://api.siliconflow.cn.evil.test/v1"))
    }

    /** コーディング プランのキーと標準 API は汎用ではありません。アドレスは、公式の別個の OpenAI 互換エンドポイントに該当する必要があります。 */
    @Test fun codingPlanUsesItsOwnDocumentedEndpoint() {
        val standard = preset("智谱")
        val coding = preset("智谱 Coding Plan")
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", coding.baseUrl)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, coding.kind)
        assertNotEquals(standard.baseUrl, coding.baseUrl)
    }
}
