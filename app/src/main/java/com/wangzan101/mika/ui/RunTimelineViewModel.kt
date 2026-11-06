package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import com.wangzan101.mika.core.assembly.knownTools

enum class TagTone { Ok, Warn, Bad }

/** タイムラインのタグ: ガード、検証、確認、乗っ取り、障害の結論。 */
data class Tag(val text: String, val tone: TagTone)

/** は、ポータルによって報告されたアクティビティを実際に実行します。連続した読み取りを 1 行に結合でき、アクションは 1 つずつ表示されたままになります。 */
data class TimelineActivity(
    val kind: String,
    val label: String,
    val target: String?,
    val effect: String?,
    val count: Int = 1,
    val obsIds: List<Int> = emptyList(),
)

/** 記録された経過時間。バッチ/スクリプトのサブアクションは外部ツールの消費時間に含まれ、1 回だけカウントされます。 */
data class StepTiming(
    val modelMs: Long? = null,
    val toolMs: Long? = null,
    val retryMs: Long = 0,
) {
    val totalMs: Long? get() = if (modelMs != null && toolMs != null) modelMs + toolMs else null
}

/**
 * タイムライン内の行。 [said] はモデル ステップでの発言内容 (最大 80 ワード)、[tool] はツールの中国語用語です。
 * [detail] はオブジェクトを最もよく表すパラメータ (レコードは感度が下げられ、そのまま表示されています)、[screenshotId] はこのステップで保存された画像です。
 */
data class TimelineStep(
    val step: Int,
    val said: String,
    val tool: String?,
    val detail: String?,
    val result: String?,
    val tags: List<Tag>,
    val screenshotId: Int?,
    val activities: List<TimelineActivity> = emptyList(),
    val timing: StepTiming = StepTiming(),
)

data class TimelineHeader(
    val goal: String,
    val criteria: List<String>,
    val outcome: String,
    /** この実行はまだ終了していません。終了、検証、統計はまだ書き込まれておらず、ページは毎秒読み取られ続けます ([RunTimelineScreen] を参照)。 */
    val running: Boolean = false,
    val verdict: String,
    val duration: String?,
    val modelCalls: Int,
    /** この実行のステップ数の上限。制限なし、または古いレコードの場合は null。 */
    val stepLimit: Int? = null,
    /** は何歩歩きましたか ([stepLimit] と一緒に表示されます)。ステップ数を使い切って終了した最終ラウンドは、ステップ数をカウントせず、上限値＋1ステップとして記録されるため、上限を超えることはありません。 */
    val stepsUsed: Int = 0,
    val usedMemory: List<String> = emptyList(),
    /** この実行の概要。レコードに開始イベントがない場合は null になります (時間のみが表示されます)。 */
    val metrics: RunMetrics? = null,
)

sealed interface TimelineUi {
    data object Loading : TimelineUi
    /** レコードがクリアされているか（過去 30 回のみ保持）、数値が間違っています。 */
    data object Expired : TimelineUi
    data class Ready(val header: TimelineHeader, val steps: List<TimelineStep>) : TimelineUi

    companion object {
        val EXPIRED_TEXT: String get() = tr(R.string.timeline_expired)
    }
}

/** 1 回の実行の読み取り専用タイムライン。 [load] ディスク読み取りはバックグラウンド スレッドで調整する必要があります。 */
class RunTimelineViewModel(
    private val store: RunStore, val runId: String,
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    private val resolveMemory: (List<String>) -> List<String> = { emptyList() },
    /**
     * 今回実行したタスクは現時点でもまだ実行中です。実行中の計算はレコードを終了せずに終了し、タスクは存在しません (プロセスが強制終了され、レコードが途中で書き込まれました)。
     * ページは毎秒再読み込みされません。
     */
    private val alive: (runId: String) -> Boolean = { false },
) {
    fun load(): TimelineUi {
        val summary = store.list().firstOrNull { it.id == runId }
        val events = store.events(runId)
        if (summary == null && events.isEmpty()) return TimelineUi.Expired
        val started = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val startMs = summary?.startedAtMs ?: events.first().atMs
        val endMs = summary?.endedAtMs ?: ended?.atMs
        val metrics = RunMetrics.of(events, runId)
        val header = TimelineHeader(
            goal = summary?.goal ?: started?.goal.orEmpty(),
            criteria = summary?.criteria?.takeIf { it.isNotEmpty() }
                ?: events.filterIsInstance<RunEvent.Criteria>().lastOrNull()?.criteria.orEmpty(),
            outcome = outcomeLabel(summary?.outcome ?: ended?.outcome,
                summary?.judged ?: events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome),
            // 終了イベントまたは終了時刻が 1 つある限り、実行は完了します。終了イベントも終了時刻もない場合、タスクはまだ実行中であり、インターフェイスはこれに基づいて秒単位で再読み取りするかどうかを決定します。
            running = ended == null && summary?.endedAtMs == null && alive(runId),
            verdict = verdictLabel(summary?.verdict ?: events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict),
            duration = endMs?.let { duration(it - startMs) },
            modelCalls = events.count { it is RunEvent.ModelReply },
            stepLimit = started?.stepLimit,
            stepsUsed = metrics?.steps?.let { n -> started?.stepLimit?.let { minOf(n, it) } ?: n } ?: 0,
            usedMemory = resolveMemory(events.filterIsInstance<RunEvent.MemoryUsed>().lastOrNull()?.ids.orEmpty()),
            metrics = metrics,
        )
        return TimelineUi.Ready(header, steps(events, toolLabels::label))
    }

    fun screen(screenshotId: Int): File? = store.screen(runId, screenshotId)

    fun usageLog(): String = store.usageLog(runId)

    companion object {
        const val SAID_MAX = 80

        /** はステップ番号ごとにグループ化されています。ステップ番号のないイベント (検証、確認、引き継ぎ、障害) は、その前の最新のステップにハングされます。 */
        internal fun steps(events: List<RunEvent>, toolLabel: (String) -> String = { it }): List<TimelineStep> {
            val rows = LinkedHashMap<Int, TimelineStep>()
            val modelTimes = mutableMapOf<Int, Long>()
            val retryTimes = mutableMapOf<Int, Long>()
            val toolTimes = mutableMapOf<Int, MutableList<RunEvent.ToolResult>>()
            var current: Int? = null
            fun at(step: Int) = rows.getOrPut(step) { TimelineStep(step, "", null, null, null, emptyList(), null) }.also { current = step }
            fun tag(t: Tag) { current?.let { s -> rows[s] = rows.getValue(s).let { it.copy(tags = it.tags + t) } } }
            for (e in events) when (e) {
                // ディスパッチされた軽量モデルの応答は実行されず、ステップには入りません。通話数は引き続きカウントされます。
                is RunEvent.ModelReply -> {
                    val elapsed = e.latencyMs.coerceAtLeast(0) + e.retryWaitMs.coerceAtLeast(0)
                    if (elapsed > 0) modelTimes[e.step] = modelTimes.getOrDefault(e.step, 0) + elapsed
                    if (e.retryWaitMs > 0) retryTimes[e.step] = retryTimes.getOrDefault(e.step, 0) + e.retryWaitMs
                    if (e.acted) {
                        val row = at(e.step)
                        val said = listOf(row.said, e.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                        rows[e.step] = row.copy(
                            said = said,
                            tool = e.tool?.let(toolLabel) ?: row.tool,
                            detail = e.argsJson?.let(::detailOf) ?: row.detail,
                        )
                    }
                }
                is RunEvent.ToolResult -> {
                    toolTimes.getOrPut(e.step) { mutableListOf() } += e
                    val row = at(e.step)
                    rows[e.step] = row.copy(
                        result = resultLabel(e.resultJson, e.changed),
                        tags = row.tags + guardTags(e.resultJson),
                        screenshotId = row.screenshotId ?: e.screenshotId,
                    )
                }
                is RunEvent.Media -> at(e.step).let { rows[e.step] = it.copy(screenshotId = it.screenshotId ?: e.mediaId) }
                is RunEvent.Activity -> {
                    val row = at(e.step)
                    val next = TimelineActivity(e.kind, toolLabel(e.label), e.target, e.effect, obsIds = e.obsIds)
                    val last = row.activities.lastOrNull()
                    val activities = if (e.kind == "observe" && last?.kind == "observe" && last.target == e.target) {
                        row.activities.dropLast(1) + last.copy(count = last.count + 1, obsIds = (last.obsIds + e.obsIds).distinct())
                    } else row.activities + next
                    rows[e.step] = row.copy(activities = activities)
                }
                is RunEvent.Verifier -> tag(verifierTag(e.verdict))
                is RunEvent.Confirmation -> tag(
                    when (e.decision) {
                        "approved" -> Tag(tr(R.string.tag_approved), TagTone.Ok)
                        "declined", "declined_before" -> Tag(tr(R.string.tag_declined), TagTone.Bad)
                        else -> Tag(tr(R.string.tag_needs_ok), TagTone.Warn)
                    },
                )
                is RunEvent.Takeover -> tag(Tag(tr(R.string.tag_takeover), TagTone.Warn))
                is RunEvent.Barrier -> tag(Tag(tr(R.string.tag_barrier), TagTone.Warn))
                else -> Unit
            }
            return rows.values.map { row ->
                val results = toolTimes[row.step].orEmpty()
                // do_steps および複合ツールはサブアクションも記憶します。外側のdurationMsにはすでにそれらが含まれています。
                val outer = results.filter { it.source == "model" && it.durationMs > 0 }
                val measured = (outer.ifEmpty { results.filter { it.durationMs > 0 } }).sumOf { it.durationMs }
                row.copy(
                    said = clipSaid(row.said),
                    timing = StepTiming(modelTimes[row.step], measured.takeIf { it > 0 }, retryTimes[row.step] ?: 0),
                )
            }
        }

        internal fun timingLabel(t: StepTiming): String {
            if (t.modelMs == null && t.toolMs == null) return tr(R.string.timing_unrecorded)
            return buildList {
                t.totalMs?.let { add(tr(R.string.timing_total, shortDuration(it))) }
                t.modelMs?.let { add(tr(R.string.timing_model, shortDuration(it))) }
                t.toolMs?.let { add(tr(R.string.timing_tool, shortDuration(it))) }
                if (t.retryMs > 0) add(tr(R.string.timing_retry, shortDuration(t.retryMs)))
            }.joinToString(" · ")
        }

        private fun shortDuration(ms: Long): String = when {
            ms < 1_000 -> tr(R.string.duration_ms, ms)
            ms < 60_000 -> tr(R.string.duration_seconds_decimal, "%.1f".format(java.util.Locale.ROOT, ms / 1_000.0))
            else -> duration(ms)
        }

        private fun clipSaid(s: String) = if (s.length <= SAID_MAX) s else s.take(SAID_MAX) + "…"

        private val DETAIL_KEYS = listOf("text", "package", "query", "key", "direction", "ref", "answer", "reason")

        private fun detailOf(argsJson: String): String? {
            val o = parse(argsJson) ?: return null
            return DETAIL_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }?.let(::clipSaid)
        }

        // 結果が不正な JSON に切り詰められる可能性があります: ok / error はトップレベルのキーの記述方法に従ってのみ検出されます (要素テキスト内の引用符はエスケープされており、誤ってヒットすることはありません)
        private val NOT_OK = Regex(""""ok"\s*:\s*false""")
        private val ERROR = Regex(""""error"\s*:\s*"([a-z_]+)"""")

        private fun resultLabel(json: String, changed: Boolean?): String = when {
            NOT_OK.containsMatchIn(json) -> ERROR.find(json)?.groupValues?.get(1)?.let { ERROR_LABELS[it] }?.let { tr(R.string.result_error_detail, it) } ?: tr(R.string.result_error)
            changed == true -> tr(R.string.result_effective)
            changed == false -> tr(R.string.result_unchanged)
            else -> tr(R.string.result_completed)
        }

        private fun guardTags(json: String): List<Tag> = guardObjects(json).mapNotNull { o ->
            val severity = (o["severity"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val what = (o["code"] as? JsonPrimitive)?.contentOrNull?.let { GUARD_LABELS[it] }
            val head = if (severity == "stop") tr(R.string.guard_stop) else tr(R.string.guard_warn)
            Tag(if (what == null) head else tr(R.string.guard_with_what, head, what), if (severity == "stop") TagTone.Bad else TagTone.Warn)
        }

        /**
         * 結果のguard配列の各要素。完全なJSONはそのまま解析し、途中で切れている場合は `"guard":[` 以降から完全なオブジェクトを順に取り出す。
         * 末尾で切れている不完全なオブジェクトは破棄する。
         */
        private fun guardObjects(json: String): List<JsonObject> {
            parse(json)?.let { o -> return (o["guard"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() }
            val start = GUARD_START.findAll(json).lastOrNull()?.range?.last?.plus(1) ?: return emptyList()
            val out = mutableListOf<JsonObject>()
            var depth = 0
            var inString = false
            var escaped = false
            var from = -1
            for (i in start until json.length) {
                val c = json[i]
                when {
                    escaped -> escaped = false
                    inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' -> { if (depth == 0) from = i; depth++ }
                    c == '}' -> { depth--; if (depth == 0) parse(json.substring(from, i + 1))?.let(out::add) }
                    c == ']' && depth == 0 -> break
                }
            }
            return out
        }

        private val GUARD_START = Regex(""""guard"\s*:\s*\[""")

        private fun parse(s: String): JsonObject? = try {
            Json.parseToJsonElement(s) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

        private val GUARD_LABELS get() = mapOf(
            "repeating_action" to tr(R.string.guard_repeating),
            "no_progress" to tr(R.string.guard_no_progress),
            "too_many_failures" to tr(R.string.guard_failures),
        )

        private val ERROR_LABELS get() = mapOf(
            "ref_expired" to tr(R.string.err_ref_expired),
            "no_app_open" to tr(R.string.err_no_app),
            "invalid_arguments" to tr(R.string.err_invalid_args),
            "operation_failed" to tr(R.string.err_op_failed),
            "unknown_tool" to tr(R.string.err_unknown_tool),
            "protected_content" to tr(R.string.err_protected),
            "confirmation_required" to tr(R.string.err_confirm_required),
            "confirmation_mismatch" to tr(R.string.err_confirm_mismatch),
            "confirmation_declined" to tr(R.string.err_confirm_declined),
            "action_not_applied" to tr(R.string.err_not_applied),
            "verification_failed" to tr(R.string.verify_failed),
            "parallel_tool_call_skipped" to tr(R.string.err_parallel_skipped),
            "screenshot_unavailable" to tr(R.string.err_no_screenshot),
            "display_capture_unavailable" to tr(R.string.err_no_screenshot),
            "vision_unavailable" to tr(R.string.err_vision),
            "payment_requires_takeover" to tr(R.string.err_payment),
            "finish_missing_evidence" to tr(R.string.err_no_evidence),
            "double_click_unsupported" to tr(R.string.err_double_click),
            "app_blocked" to tr(R.string.err_app_blocked),
            "app_access_unverified" to tr(R.string.err_app_unverified),
            "app_access_unavailable" to tr(R.string.err_app_unavailable),
            "app_access_protected" to tr(R.string.err_app_protected),
        )

        private fun verifierTag(verdict: String) = when (verdict) {
            "pass" -> Tag(tr(R.string.verify_passed), TagTone.Ok)
            "fail" -> Tag(tr(R.string.verify_failed), TagTone.Bad)
            else -> Tag(tr(R.string.verify_unverified), TagTone.Warn)
        }

        private fun verdictLabel(verdict: String?) = if (verdict == null) tr(R.string.verify_none) else verifierTag(verdict).text

        /** [judged]: 結果は回答を提出した後に判断されます。回答がまだ完了していないか、部分的にしか完了していないと正直に述べている場合は、それを書いてください。 */
        private fun outcomeLabel(outcome: String?, judged: String?) = when {
            outcome != "succeeded" && outcome != "unverified" -> outcomeLabel(outcome)
            judged == "partial" -> tr(R.string.outcome_partial)
            judged == "not_possible" -> tr(R.string.outcome_not_possible)
            else -> outcomeLabel(outcome)
        }

        private fun outcomeLabel(outcome: String?) = when (outcome) {
            "succeeded" -> tr(R.string.outcome_succeeded)
            "unverified" -> tr(R.string.outcome_unverified)
            "failed" -> tr(R.string.outcome_failed)
            "cancelled" -> tr(R.string.steer_cancelled)
            "error" -> tr(R.string.result_error)
            null -> tr(R.string.outcome_unfinished)
            else -> outcome
        }

        internal fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s < 60) tr(R.string.duration_s, s) else tr(R.string.duration_m_s, s / 60, s % 60)
        }
    }
}
