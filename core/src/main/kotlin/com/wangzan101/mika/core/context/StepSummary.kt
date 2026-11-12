package com.wangzan101.mika.core.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 折りたたまれたステップの構造化された概要: 目標/確認された事実/進捗状況/次のステップ。割引の各ラウンドは、前回の割引 ([next]) に基づいて更新されます。
 * の内容は、タスクの元の単語、ツールの結果、モデル自体の単語から決定的に取得されます。モデルに対する追加の調整は行われません。圧縮ごとに要約を再計算する必要があります。
 * では、モデルを調整するためにさらに 1 ラウンド トリップが必要で、同じ履歴でも異なるダイジェストが生成される可能性があるため、リクエスト プレフィックスは不安定です。
 * 各セグメントには上限があります (ファクトごとに [MAX_FACT_VALUES]、進行状況に [MAX_PROGRESS]、以前のエンディングに [MAX_EARLIER_KINDS]、項目ごとに [CLIP])、
 * 手順がいくらあっても、要約が無限に長くなるわけではありません。
 */
data class StepSummary(
    val goal: String,
    /** ファクト タイプ → 値 (重複を削除し、新しいものが最後になります)。 */
    val facts: Map<String, List<String>> = emptyMap(),
    val progress: List<String> = emptyList(),
    /** は、カウントの前のステップ (終了 → ステップ数) にマージされます。 折りたたみセクションの */
    val earlier: Map<String, Int> = emptyMap(),
    /** モデルの最終計画。 */
    val intent: String? = null,
) {
    /** は 1 つのラウンドに折りたたまれます。[line] はこのステップの進行状況ライン (「→ 終了」で終わる)、[results] はこのラウンドの成功したツールの結果、[said] はモデルがこのラウンドで言ったことです。 */
    fun next(line: String, results: List<JsonObject>, said: String?): StepSummary {
        val f = facts.toMutableMap()
        for (r in results) for ((key, kind) in FACT_KEYS) {
            val v = (r[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            f[kind] = ((f[kind].orEmpty() - clip(v)) + clip(v)).takeLast(MAX_FACT_VALUES)
        }
        var p = progress + line
        var e = earlier
        while (p.size > MAX_PROGRESS) {
            val kind = p.first().substringAfterLast("→ ").trim()
            // 超出种类上限的新结局都并进 OTHER,而 OTHER 自己也要占一格:先给它留好位置,不然会多出第 6 种
            val outcome = when {
                kind in e -> kind
                e.size + 1 < MAX_EARLIER_KINDS -> kind
                OTHER in e && e.size < MAX_EARLIER_KINDS -> kind
                else -> OTHER
            }
            e = e + (outcome to (e[outcome] ?: 0) + 1)
            p = p.drop(1)
        }
        return StepSummary(goal, f, p, e, said?.let(::clip)?.takeIf { it.isNotEmpty() } ?: intent)
    }

    fun render(header: String): String = buildString {
        appendLine(header)
        appendLine("Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only.")
        appendLine("Goal: $goal")
        appendLine("Confirmed facts:")
        if (facts.isEmpty()) appendLine("- (none yet)")
        FACT_KEYS.values.forEach { kind -> facts[kind]?.let { appendLine("- $kind: ${it.joinToString(", ")}") } }
        appendLine("Progress:")
        if (earlier.isNotEmpty()) appendLine("- ${earlier.values.sum()} earlier steps: " + earlier.entries.joinToString(", ") { "${it.key} ${it.value}" })
        progress.forEach { appendLine("- $it") }
        append("Next: continue from the most recent steps kept below")
        intent?.let { append(" (last stated plan before folding: $it)") }
    }

    private companion object {
        /** 結果にファクトとして記録できる短いフィールド → ファクトタイプ。 */
        val FACT_KEYS = linkedMapOf("currentApp" to "apps visited", "url" to "pages opened", "path" to "files read or written")
        const val MAX_FACT_VALUES = 4
        const val MAX_PROGRESS = 15
        const val MAX_EARLIER_KINDS = 5
        const val OTHER = "other"
        const val CLIP = 100

        fun clip(s: String): String {
            val one = s.replace('\n', ' ').trim()
            return if (one.length <= CLIP) one else one.take(CLIP) + "…"
        }
    }
}
