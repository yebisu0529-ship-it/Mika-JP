package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelErrorReasonTest {
    /** モデルが見つかりません。モデル名がわかっている場合は、それがどのモデルであるか、どこを変更するか、およびモデル名がどのようになるかを述べてください。分からない場合は、一般的な説明に戻ってください。 */
    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "このプロバイダーではモデル「gemini」が見つかりません。「設定 › モデル」で正式なモデルIDを確認するか、プロバイダーのプリセットを選び直してください。",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("モデルが見つかりません。「設定 › モデル」でモデル名とBase URLを確認してください。", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("モデル「gemini」が見つかりません", ModelError.ModelNotFound("gemini").message)
    }

    /** 404 ゲートウェイまたはリレー サービスが別のことを言っている可能性もあります。元の単語がある場合は、元の単語が使用され、モデルが存在しないとは判断されず、文末のポイントは繰り返されません。原語がない場合も同様です。 */
    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "モデル「group/rpa」の呼び出し時に、サーバーから次の応答がありました: none of group/rpa's models is ready。モデル名またはサービスの状態を確認してください。" +
                "「設定 › モデル」で正式なモデルIDを確認するか、プロバイダーのプリセットを選び直してください。",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "サーバーから次の応答がありました: The model `x` does not exist。モデル名またはサービスの状態を確認してください。「設定 › モデル」でモデル名とBase URLを確認してください。",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said.contains("見つかりません"))
    }

    /** は文中にサーバーのオリジナルの言葉を引用しています。元の単語の 3 つの失敗例では、末尾のピリオドのみが削除されています。切り取られた楕円は変更されません。 */
    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed。") && !it.contains("Not allowed.")) }
        reasons("不允许。").forEach { assertTrue(it, it.contains("不允许。") && !it.contains("不允许。。")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed。")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long…。")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait...。")) }
    }

    /** このアカウントはモデルを使用できないか、ツールをサポートしていません。モデルがどれであるか、どこを変更するかを指示するか、サービス プロバイダーのバックエンドにアクセスしてアクティブ化してください。 */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "このアカウントではモデル「qwen-max」を利用できません。「設定 › モデル」で別のモデルを選ぶか、プロバイダーの利用権限を確認してください。",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "モデル「gemma」はツール呼び出しに対応していません。Mikaの操作には必要なため、「設定 › モデル」で対応モデルを選んでください。",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    /** 思考强度被服务端拒绝时保留参数错误并指向档位设置,不再误报 Base URL 或接口类型。 */
    @Test fun rejectedReasoningEffortPointsToTheActualSetting() {
        val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
        val reason = StopRules.modelFailed(ModelError.ReasoningEffortRejected(detail)).reason
        assertTrue(reason.contains(detail.dropLast(1)))
        assertTrue(reason.contains("推論の強さを既定"))
        assertFalse(reason.contains("Base URL"))
        assertFalse(reason.contains("インターフェイスの種類"))
    }

    /** 断流有自己的说法、不提 Base URL;真的解析不了(格式对不上)才提示检查 Base URL 和接口类型。 */
    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut.contains("接続が切れ"))
        assertTrue(cut.contains("再試行でも解消しませんでした"))
        assertFalse(cut.contains("Base URL"))
        assertFalse(cut.contains("APIの種類"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("流式响应不是合法 JSON")).reason.contains("Base URLとAPIの種類が一致しているか確認してください"))
    }
}
