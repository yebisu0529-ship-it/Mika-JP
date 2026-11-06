package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

const val DO_STEPS_TOOL = "do_steps"
private const val MIN_STEPS = 1
/** は 1 回の呼び出しのリソース消費を制限するだけで、適切な長さの連続操作をタスク ルールとして記述しません。 */
private const val MAX_STEPS = 20
private const val EXPECT_MAX = 200
/** 2ステップ目からは、判定・実行前のインターフェース安定性の上限が決まります（アクション付属の画面と同じ）。 */
private const val SETTLE_MS = 1500L
/** ワンステップ待機条件 (指定する必要はありません); Expect は古い書き方で、意味は「終わってから見てください」から「表示されるまで待つ」に変わりました。 */
private const val EXPECT = "expect"
private const val EXPECT_GONE = "expect_gone"
private const val EXPECT_APP = "expect_app"
private const val WAIT_STABLE = "wait_stable"
private const val TIMEOUT_S = "timeout_s"
private val STEP_FIELDS = setOf("tool", "args", EXPECT, EXPECT_GONE, EXPECT_APP, WAIT_STABLE, TIMEOUT_S)

/** アプリケーション アクセスがブロックされています: バッチを停止し、実行エントリの命令をそのままモデルに取り込む必要があります。 */
private fun appAccessDenied(code: String) = com.wangzan101.mika.core.access.AppAccessDenied.isAppAccessCode(code)

/** はバッチ内のステップです。 [until] は、終了後続行する前に待つ必要があるものです。そうでない場合は null です。 */
data class BatchStep(val tool: String, val argsJson: String, val until: Until? = null)

/**
 * は、複数の連続動作を一度に実行し、段階的に確認し、事故が発生した場合に停止します。各ステップ (2 番目のステップから開始してインターフェースが安定するまで待機) は [ActionExecutor] によって実行されます。
 * のシーケンスは単一ステップと同じです。バッチでは使用されず、いかなる同意も想定されません。不可逆的なステップヒットはその前で停止し、実行されません。
 * は検証に失敗しました。ツールが失敗し、別のアプリに切り替えられ、入力リードバックに一貫性がありませんでした。 1ステップに待ち条件([Until])が含まれる場合は、まず成立するまで待ちます。
 * ([ToolDispatcher.waitUntil]: 画面読み取り率、モデルを問わず)、制限時間に達した場合、ポップアップ ボックスが表示された場合、またはアプリが変更された場合に停止します。どれくらい待つ必要があるかは、このステップの結果に書き込まれます。
 * 各ステップは、ガードと実行レコード (ソース = 実行レコードの「バッチ」) のアクションです。
 */
class BatchRunner(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    private val executor: ActionExecutor,
    /** 各サブステップのガード結論 (実行前に 1 回、実行後に 1 回) がループに返され、スタックされたリマインダーと反復入力が含まれます。モデルに添付される提案が返されます。 */
    private val noteVerdicts: (step: BatchStep, exec: ToolExecution?, verdicts: List<GuardVerdict>) -> String? = { _, _, _ -> null },
    /** ユーザーは現在新しいコメントを待っていますか (メモリ内の受信トレイを見てください)。そうであれば、2 つのサブステップの間で停止し、ループに送信を任せます。 */
    private val steerWaiting: () -> Boolean = { false },
) {
    private class Stop(
        val index: Int, val reason: String, val entry: JsonObject, val executed: Boolean, val extra: JsonObject = JsonObject(emptyMap()),
        /** 停止後に実行されなかったサブステップにも結果が与えられ (新たな割り込みが発生した場合)、サブステップと結果は 1 つずつペアになります。 */
        val rest: List<JsonElement> = emptyList(),
    )

    /** すべてのサブステップに表示されるガード結論。ループはこれに基づいて、このステップにガード リマインダーがあるかどうかを判断します。 */
    val verdicts = mutableListOf<GuardVerdict>()
    private var advice: String? = null

    /** 条件に従ってインターフェイスを待機する各ステップに費やされた時間の合計。実行レコードでは、操作ではなく待機としてカウントされます。 */
    var waitedMs = 0L
        private set

    suspend fun run(steps: List<BatchStep>, callId: String, step: Int = 0): ToolExecution {
        val entries = mutableListOf<JsonElement>()
        var last: ToolExecution? = null
        // 各サブステップで読み取られた観察結果 (台帳に記録)。結果のバッチ全体にそれらが含まれ、トリミング時にその形状に応じて短縮されます。
        val seen = mutableListOf<Observation>()
        var stop: Stop? = null
        fun skipFrom(i: Int): Stop {
            val skipped = steps.drop(i).map { entry(it.tool) { put("ok", false); put("error", NEW_MESSAGE); put("executed", false) } }
            return Stop(i, NEW_MESSAGE, skipped.first(), executed = false, rest = skipped.drop(1))
        }
        for ((i, s) in steps.withIndex()) {
            if (i > 0 && steerWaiting()) {
                stop = skipFrom(i)
                break
            }
            // 前ステップのインターフェースが変化中である場合、遷移画面が読み取られ、expectも誤判定されます。
            if (i > 0) tools.settle(SETTLE_MS)
            val out = executor.run(Part.ToolCall("$callId.${i + 1}", s.tool, s.argsJson), Origin.Batch(step))
            val before = out.before
            note(s, null, before)
            fun notRun(reason: String, extra: JsonObject = JsonObject(emptyMap()), message: String? = null) = Stop(i, reason, entry(s.tool) {
                put("ok", false); put("error", reason); put("executed", false)
                message?.let { put("message", it) }
                if (before.isNotEmpty()) put("guard", JsonArray(before.map { it.toJson() }))
            }, executed = false, extra = extra)
            val exec = when (out) {
                is ActionOutcome.Ran -> out.exec
                is ActionOutcome.Refused -> {
                    val hit = out.hit
                    // アプリケーションのアクセス許可が拒否されました: 実行エントリで指定された次のステップの指示をモデルに与えます。モデルにエラー コードのみを表示させないでください。
                    val message = (out.content["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { appAccessDenied(out.code) }
                    stop = if (hit == null) notRun(out.code, message = message)
                    else notRun(out.code, buildJsonObject { put("action", hit.action.name); put("matched", hit.matched) }, message)
                    break
                }
                is ActionOutcome.Cancelled -> { stop = notRun("cancelled"); break }
            }
            // このステップがユーザーの介入によって中断されるまでの滞在、間隔、または待機: このステップは何もせず、次のステップでスキップし、その文を送信するループに任せました。
            if (exec.content["error"] == JsonPrimitive(NEW_MESSAGE)) {
                waitedMs += exec.waitMs
                stop = skipFrom(i)
                break
            }
            seen += exec.observations
            // サブステップでリズムを構成するストップも、このバッチの待機としてカウントされます。
            waitedMs += exec.waitMs
            val afterVerdicts = out.after
            note(s, exec, afterVerdicts)
            val all = before + afterVerdicts
            last = exec
            val c = exec.content
            val screen = c["screen"] as? JsonObject
            val failed = c["ok"] != JsonPrimitive(true)
            val hard = when {
                failed -> (c["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
                c["verification"] == JsonPrimitive("mismatch") -> "action_not_applied"
                c["app_switched"] != null -> "app_switched"
                // がユーザーのホーム画面で開かれます (別の場所に戻されます)。次の手順は元の画面に従って配置されています。
                c["opened"] != null -> "opened_elsewhere"
                // ウェブサイト、アプリ 上限について言えば、次の手順はもう実行しません。
                c["limitReached"] != null -> "limit_reached"
                else -> null
            }
            val review = i < steps.lastIndex && screen != null &&
                (screen["imageReason"] != null || screen["protected"] == JsonPrimitive(true) ||
                    screen["captureError"] != null || screen["readable"] == JsonPrimitive(false) || screen["note"] != null)
            // アクションに付属する画面は、まだロード中のように見える場合があります。表示される単語が表示されるまで待ちます。再度画像を見る必要はありません。
            val until = s.until?.takeIf { hard == null && (!review || it.appears != null) }
            val waited = until?.let { tools.waitUntil(it, executor.recorder::now, steerWaiting) }
            waited?.let { waitedMs += it.waitedMs }
            val reason = hard ?: when {
                waited != null && !waited.met -> when (waited.stopped) {
                    UntilOutcome.TIMEOUT -> "expectation_unmet"
                    UntilOutcome.NEW_MESSAGE -> null
                    else -> waited.stopped
                }
                review && waited?.met != true -> "screen_needs_review"
                else -> null
            }
            val e = entry(s.tool) {
                put("ok", reason == null)
                exec.changed?.let { put("changed", it) }
                effectKind(c)?.let { put("effect", it) }
                (c["verification"] as? JsonPrimitive)?.let { put("verification", it) }
                reason?.let { put("error", it) }
                if (waited != null && !waited.met) s.until?.let { untilFields(it).forEach { (k, v) -> put(k, v) } }
                waited?.let { w ->
                    put("wait", buildJsonObject { put("met", w.met); w.stopped?.let { put("stopped", it) }; put("waited_ms", w.waitedMs) })
                }
                (c["app_switched"] as? JsonPrimitive)?.let { put("app_switched", it) }
                (c["opened"] as? JsonObject)?.let { put("opened", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                (c["chooser"] as? JsonObject)?.let { put("chooser", it) }
                (c["limitReached"] as? JsonPrimitive)?.let { put("limitReached", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                if (all.isNotEmpty()) put("guard", JsonArray(all.map { it.toJson() }))
            }
            if (reason != null) { stop = Stop(i, reason, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break }
            // が待機している間に、ユーザーが中断しました。次のサブステップは通常どおり、次のラウンドの開始時にスキップされます。これが最後のステップになると、ここで停止します。
            if (waited?.stopped == UntilOutcome.NEW_MESSAGE && i == steps.lastIndex) {
                stop = Stop(i, NEW_MESSAGE, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break
            }
            entries += e
            if (guards.stop != null && i < steps.size - 1) {
                stop = Stop(i + 1, "guard_stopped", entry(steps[i + 1].tool) { put("ok", false); put("error", "guard_stopped"); put("executed", false) }, executed = false)
                break
            }
        }
        return finish(steps, entries, stop, last, callId, step, seen)
    }

    private fun finish(
        steps: List<BatchStep>, done: List<JsonElement>, stop: Stop?, last: ToolExecution?, callId: String, step: Int, seen: List<Observation>,
    ): ToolExecution {
        val completed = stop?.index ?: steps.size
        executor.recorder.event(RunEvent.Batch(executor.recorder.now(), completed, steps.size, stop != null, stop?.reason))
        val screen = last?.content?.get("screen")
        val content = buildJsonObject {
            put("ok", stop == null)
            when (stop?.reason) {
                null -> Unit
                "confirmation_required" -> {
                    put("error", "confirmation_required")
                    stop.extra.forEach { (k, v) -> put(k, v) }
                    val what = stop.extra["matched"]?.let { (it as JsonPrimitive).content }
                    put("message", "Step ${stop.index + 1} of ${steps.size} spends money ($what). " +
                        "It was not run, and the steps after it were not run either. Payment steps never go in do_steps: " +
                        if (executor.confirms) "follow the step's message (request_confirmation, or hand_to_user for a payment), then do that step on its own as a single tool call."
                        else "do it on its own as a single tool call, not as part of a batch.")
                    put("counts_as_failure", false)
                }
                NEW_MESSAGE -> {
                    put("error", "batch_stopped")
                    put("reason", NEW_MESSAGE)
                    put("message", (if (stop.executed) "Step ${stop.index + 1} of ${steps.size} was run, but waiting for its condition was cut short"
                        else "Steps ${stop.index + 1}-${steps.size} of ${steps.size} were skipped") + " to handle a new message from the user. " +
                        "Read the message first, then decide what to do next.")
                    put("counts_as_failure", false)
                }
                else -> {
                    put("error", "batch_stopped")
                    put("reason", stop.reason)
                    val n = "Step ${stop.index + 1} of ${steps.size}"
                    if (appAccessDenied(stop.reason)) {
                        // 権限の拒否は通常の操作の失敗ではありません。「バッチを変更して続行する」という提案はせず、次に何をすべきかを明確に説明してください。
                        val detail = (stop.entry["message"] as? JsonPrimitive)?.contentOrNull
                            ?: "Step ${stop.index + 1} of ${steps.size} was not run (${stop.reason})."
                        put("message", "$detail The remaining steps in this batch were not run.")
                        put("retryable", false)
                    } else {
                        put("message", (if (stop.executed) "$n was run but did not pass its check (${stop.reason})" else "$n was not run (${stop.reason})") +
                            "; the steps after it were not run. Check the screen, then continue one step at a time or with a new batch.")
                    }
                }
            }
            advice?.let { put("advice", it) }
            put("completed", completed); put("total", steps.size)
            put("steps", JsonArray(done + listOfNotNull(stop?.entry) + stop?.rest.orEmpty()))
            screen?.let { put("screen", it) }
            if (stop != null) put("recoverable", true)
        }
        // のサブステップ画像が 1 枚ずつ保存されました
        // モデルに送信された最後のサブステップ (画像を表示できるメイン モデルでのみ利用可能) はループに残され、その後に結果のバッチ全体が続きます。
        return ToolExecution(content, changed = last?.changed, frame = if (!executor.recorder.savesScreens) last?.frame ?: last?.image else null,
            screenElements = last?.screenElements, images = last?.images.orEmpty(), imageIds = last?.imageIds.orEmpty(), place = last?.place,
            observations = seen)
    }

    private fun note(s: BatchStep, exec: ToolExecution?, v: List<GuardVerdict>) {
        verdicts += v
        noteVerdicts(s, exec, v)?.let { advice = it }
    }

    private fun entry(tool: String, b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("tool", tool); b() }

    companion object {
        /** ユーザーによる新たな中断によりサブステップが停止された理由。 */
        const val NEW_MESSAGE = SKIPPED_FOR_NEW_MESSAGE

        /**
         * は do_steps のパラメーターを検証します。 [available] はこの時にモデルに付与されているツール名で、[batchable] はバッチに入れることができるツールです（ツール宣言によると、
         * には画面アクションと待機のみがあります。観察、質問、引き継ぎ、確認は機能しません）。
         * ステップに戻るか、どのステップのエラー説明に名前を付けます。
         */
        fun parse(argsJson: String, available: Set<String>, batchable: List<String>): Pair<List<BatchStep>?, String?> {
            val o = try { Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject } catch (e: SerializationException) { null }
                ?: return null to "arguments: must be a JSON object"
            o.keys.firstOrNull { it != "steps" && it != "reason" }?.let { return null to "$it: unknown argument for do_steps" }
            val arr = o["steps"] as? JsonArray ?: return null to "steps: required, an array of $MIN_STEPS-$MAX_STEPS steps"
            if (arr.size !in MIN_STEPS..MAX_STEPS) return null to "steps: must have $MIN_STEPS-$MAX_STEPS items"
            val out = arr.mapIndexed { i, e ->
                val n = i + 1
                val s = e as? JsonObject ?: return null to "steps[$n]: must be an object with tool, args and optional expect"
                s.keys.firstOrNull { it !in STEP_FIELDS }?.let { return null to "steps[$n].$it: unknown field" }
                val tool = (s["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null to "steps[$n].tool: required"
                if (tool !in batchable || tool !in available) {
                    return null to "steps[$n].tool: $tool cannot be batched; only ${batchable.filter { it in available }.joinToString()}"
                }
                val args = when (val a = s["args"]) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> a
                    else -> return null to "steps[$n].args: must be an object"
                }
                val texts = listOf(EXPECT, EXPECT_GONE, EXPECT_APP).associateWith { k ->
                    when (val x = s[k]) {
                        null -> null
                        is JsonPrimitive -> x.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                            ?: return null to "steps[$n].$k: must be a non-blank string"
                        else -> return null to "steps[$n].$k: must be a string"
                    }?.also { if (it.length > EXPECT_MAX) return null to "steps[$n].$k: must be at most $EXPECT_MAX characters" }
                }
                val stable = when (val x = s[WAIT_STABLE]) {
                    null -> false
                    is JsonPrimitive -> x.takeIf { !it.isString }?.booleanOrNull ?: return null to "steps[$n].$WAIT_STABLE: must be true or false"
                    else -> return null to "steps[$n].$WAIT_STABLE: must be true or false"
                }
                val max = Until.MAX_MS / 1000
                val seconds = when (val x = s[TIMEOUT_S]) {
                    null -> null
                    is JsonPrimitive -> x.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in 1.0..max.toDouble() }
                        ?: return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                    else -> return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                }
                val waits = texts.values.any { it != null } || stable
                if (seconds != null && !waits) return null to "steps[$n].$TIMEOUT_S: only goes with $EXPECT, $EXPECT_GONE, $EXPECT_APP or $WAIT_STABLE"
                val until = if (!waits) null else Until(texts[EXPECT], texts[EXPECT_GONE], texts[EXPECT_APP], stable,
                    seconds?.let { (it * 1000).toLong() } ?: Until.DEFAULT_MS)
                BatchStep(tool, args.toString(), until)
            }
            return out to null
        }

        /** モデルの記述方法に応じて待ち条件を返します（待ちなしの場合はそのステップの結果に付加されます）。 */
        private fun untilFields(u: Until): JsonObject = buildJsonObject {
            u.appears?.let { put(EXPECT, it) }
            u.gone?.let { put(EXPECT_GONE, it) }
            u.place?.let { put(EXPECT_APP, it) }
            if (u.stable) put(WAIT_STABLE, true)
            put(TIMEOUT_S, u.timeoutMs / 1000.0)
        }

        /**
         * バッチ内のステップが 3 つ以上の画面に適用される場合のモデルの命令 ([surfaceOf] はツールが適用される画面であり、移動していない画面の待機は null)。
         * は同じ画面上では null です。
         */
        fun mixedSurface(steps: List<BatchStep>, surfaceOf: (String) -> String?): String? {
            val surfaces = steps.mapNotNull { surfaceOf(it.tool) }.distinct()
            if (surfaces.size < 2) return null
            return Feedback.format("batch.mixed_surface", "surfaces" to surfaces.joinToString(", "))
        }

        /** 実行中のレコードの場合: バッチに入力されたすべてのテキストがコード化されます (ポートはパスワード ボックスであるかどうかを示しません)。 */
        fun redactArgs(argsJson: String): String {
            val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return Redactor.text(argsJson)
            val steps = o["steps"] as? JsonArray ?: return Redactor.text(argsJson)
            val masked = JsonArray(steps.map { s ->
                val so = s as? JsonObject ?: return@map s
                val a = so["args"] as? JsonObject
                if (a == null || "text" !in a) s else JsonObject(so + ("args" to JsonObject(a + ("text" to JsonPrimitive("***")))))
            })
            return Redactor.text(JsonObject(o + ("steps" to masked)).toString())
        }

        /** do_steps の定義。 [batchable] は、バッチに含めることができるツールの名前です。 */
        fun spec(batchable: List<String>) = ToolSpec(
            DO_STEPS_TOOL,
            Prompts.tool("do_steps"),
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("steps", buildJsonObject {
                        put("type", "array"); put("minItems", MIN_STEPS); put("maxItems", MAX_STEPS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("tool", buildJsonObject { put("type", "string"); put("enum", JsonArray(batchable.map(::JsonPrimitive))) })
                                put("args", buildJsonObject { put("type", "object"); put("description", "The arguments that tool takes on its own.") })
                                put(EXPECT, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: after this step, wait until an element's name contains this text. Text already on screen before the step also matches, so pick text the step brings up.")
                                })
                                put(EXPECT_GONE, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until no element's name contains this text (a loading or progress label).")
                                })
                                put(EXPECT_APP, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until the app in front (package name) or the page address contains this.")
                                })
                                put(WAIT_STABLE, buildJsonObject { put("type", "boolean"); put("description", "Optional: wait until the screen stops changing.") })
                                put(TIMEOUT_S, buildJsonObject {
                                    put("type", "number"); put("minimum", 1); put("maximum", Until.MAX_MS / 1000)
                                    put("description", "Optional: longest wait for this step's conditions, in seconds (default ${Until.DEFAULT_MS / 1000}).")
                                })
                            })
                            put("required", JsonArray(listOf(JsonPrimitive("tool"), JsonPrimitive("args"))))
                            put("additionalProperties", false)
                        })
                    })
                    put("reason", buildJsonObject { put("type", "string"); put("description", com.wangzan101.mika.core.tool.REASON_NOTE) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("steps"))))
                put("additionalProperties", false)
            }.toString(),
        )
    }
}

/** 結果のエフェクトのタイプ。アクションが報告されない場合、その効果は無効です。 */
/** アクションによって報告される実際の効果のタイプ (能力は結果の効果に書き込まれます)。報告されない場合は null です。 */
private fun effectKind(c: JsonObject): String? = ((c["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
