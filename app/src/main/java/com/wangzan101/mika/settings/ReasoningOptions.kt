package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel

/**
 * 歯車について考える。サービス プロバイダーのリストはストールのモデルを指定し、4 つのプロトコル (チャット完了、メッセージ、生成コンテンツ、および応答) はすべてそれに応じて指定されます。
 * (人間リストは、各モデルでサポートされているギアと思考方法を項目ごとに指定します。ModelCatalog を参照)。
 * 指定しない場合、Responses ファミリのみがユニバーサル マニュアル ギアを提供します (プロンプト付き。実際のサポート状況はサーバーによって異なります)。
 * 他のプロトコルについては推測しないでください。各モデルは異なるギアと書き込み方法を認識します。推測が間違っている場合、すべてのリクエストは拒否されます。
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // ギア リストは、ギアを手動で選択できることを示すものではなく、すべてのモデルがギアをサポートしているとも主張しません。インターフェイスで説明されますが、デフォルトが指定されることはありません。
        private val manualLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            listed?.reasoningEfforts?.let { declared ->
                // ChatGPTのモデル一覧に、サービスが受け付けないultraが含まれたことがある。
                // この接続ではリクエスト側が認識する値だけを表示し、HTTP 400を防ぐ。
                val levels = if (config.kind == ProviderConfig.Kind.ChatGpt) declared.filter(manualLevels::contains) else declared
                return ReasoningOptions(levels, fromProvider = true)
            }
            // 空の選択肢を返し、保存済みの無効な値を既定へ戻せるようにする。
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return when (ProviderStore.adapterFor(config, listed?.endpoint).first) {
                ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    ReasoningOptions(manualLevels, fromProvider = false)
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini -> null
            }
        }
    }
}
