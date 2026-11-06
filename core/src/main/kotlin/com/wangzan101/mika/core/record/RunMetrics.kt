package com.wangzan101.mika.core.record

import kotlinx.serialization.Serializable

/**
 * 実行の概要はすべて、実行レコード内のイベントから計算され (純粋関数、[of] を参照)、配置後のイベント クリーニングによって失われることはありません ([RunMetricsStore] を参照)。
 * 時間は [RunEvent.TaskStarted] から計算されます。事前の準備 (知識のロード、携帯電話への接続) は含まれません。
 *
 * - [totalMs]: [RunEvent.TaskEnded] に始まります。最後のイベントまで終了しません ([ended] が false)。
 * - [firstFeedbackMs]: 最初から、ユーザーがこの実行の実際の進行状況を初めて確認するまで、実際に実行された最も古いモデルが返されます ([RunEvent.ModelReply.repliedAtMs]、
 *   (古いレコードは [RunEvent.atMs] を使用します): この時点で、インターフェイスには進行状況の説明、やるべきこと、または回答が表示されます。または最初のアクティビティ (モデルを経由せずに再生する場合、最初のステップは完了します)。
 *   再割り当てされた軽量モデルの応答はカウントされません (ユーザーはそれを見ることができません)。返信を段落ごとに表示する場合は、最初の段落の方が早くなり、記録されないためカウントされません。どちらも null ではありません。
 * - [modelCalls]: モデル応答イベントの数、つまりメイン ループ内のリクエストの数 (ディスパッチされた軽量モデルからの応答を含みます。そのうち [lightCalls] は軽量モデルです)。
 *   [callsWithoutUsage] は、サービスプロバイダーが使用状況を報告しなかったものです。トークンは、これらの応答の使用量の合計です ([inputTokens] には [cachedTokens] が含まれます)。
 * - [allCalls] など: 実行中のすべての従量制リクエスト ([RunEvent.ModelCalls]: および各バイパス呼び出しと失敗した再試行)。古いレコードは null です。
 * - [modelMs]: 各応答リクエストに必要な時間の合計 (最後に送信されたリクエストから受信されるまで)。以前に失敗したリクエストとバックオフは、[retryWaitMs] に個別に記録されます。
 * - [observeMs] / [actMs]: トップレベルのツール呼び出し (モデルの直接調整と再生) の実行時間は、実行エントリによって指定されたアクティビティのタイプに応じて分割されます: リスクは読み取り専用です
 *   (画面を読み取る、Web ページを読み取る、要素をリストする、スクリーンショットを撮る、情報を確認する...)、残りは操作 (クリック、入力、スライド、アプリを開く...) です。アクションとページ スクリプトのバッチの外部呼び出しでは、
 *   サブステップの読み取りは読み取りとしてカウントされ、残りは操作としてカウントされ、繰り返しカウントされません。アクティビティのないコントロール クラスの呼び出し (待機している、ユーザーに確認してください、ユーザーに渡して、実行できないと言う) は、読み取りまたは操作としてカウントされません。
 *   [uiWaitMs] は、インターフェイスを待機する時間です。単一ツールのポートなどのインターフェイスの安定した部分 (ポートがレポートできる場合のみ、その読み取りまたは操作でカウントされます)。
 *   に、アクションのバッチの各ステップが完了した後、条件に従ってインターフェイスを待機する時間を加えます。後者は、待機ツールを個別に調整するなどの操作としてカウントされず、[otherMs] に分類されます。
 * - [otherMs]: 合計時間からモデル、読み取り、操作を差し引いた時間で、残りの時間はユーザーの待機、バックオフの再試行、制御クラスの呼び出し、検証、その他のオーバーヘッドです。
 * - [steps]: メイン ループが進むラウンド (実際に実行される応答内の最大ステップ番号)。 [observeCalls] / [actCalls] は、読み取りと操作が実行された回数 (サブステップを含む) を指します。
 * - [outcome]: [RunEvent.TaskEnded.outcome]、未完了は未完了です。 [failKind] は障害カテゴリです。 [judged]は答えを提出した後に判定されるエンディングです。
 */
@Serializable
data class RunMetrics(
    val runId: String = "",
    val goal: String = "",
    val startedAtMs: Long = 0,
    val totalMs: Long = 0,
    val ended: Boolean = false,
    val firstFeedbackMs: Long? = null,
    val modelCalls: Int = 0,
    val lightCalls: Int = 0,
    val callsWithoutUsage: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val allCalls: Int? = null,
    val allHttpCalls: Int? = null,
    val allInputTokens: Long? = null,
    val allOutputTokens: Long? = null,
    val modelMs: Long = 0,
    val retryWaitMs: Long = 0,
    val observeMs: Long = 0,
    val actMs: Long = 0,
    val uiWaitMs: Long = 0,
    val observeCalls: Int = 0,
    val actCalls: Int = 0,
    val steps: Int = 0,
    val outcome: String = UNFINISHED,
    val failKind: String? = null,
    val judged: String? = null,
) {
    val otherMs: Long get() = (totalMs - modelMs - observeMs - actMs).coerceAtLeast(0)

    /** 入力と出力 (キャッシュ ヒットは入力に含まれます)。 */
    val tokens: Long get() = inputTokens + outputTokens

    /** Completed: 申請は成功しました(検証できなかったものも含む)。回答を提出した後、一部しか完了していないか、完了できなかったと判断されませんでした。 */
    val succeeded: Boolean get() = outcome in SUCCESS && judged !in NOT_DONE

    /**
     * はイベントのシーケンスに従って蓄積されます。 [build] はいつでも調整できます。レコードを実行する書き込みスレッドは、最後にレコード全体を再読み込みすることなく、書き込み時にそれを使用して計算します。
     * は各ツールの結果の一部のみを保持し、元のテキストは保持しません。
     */
    class Builder(private val runId: String = "") {
        private class Tool(val step: Int, val source: String, val callId: String?, val durationMs: Long, val waitMs: Long)

        private var goal = ""
        private var started: Long? = null
        private var last = 0L
        private var ended: RunEvent.TaskEnded? = null
        private var firstFeedback: Long? = null
        private var calls = 0
        private var light = 0
        private var noUsage = 0
        private var input = 0L
        private var output = 0L
        private var cached = 0L
        private var all: RunEvent.ModelCalls? = null
        private var modelMs = 0L
        private var retryMs = 0L
        private var steps = 0
        private var judged: String? = null
        private val tools = mutableListOf<Tool>()
        /** (ソース、ステップ、コール ID) → アクティビティ タイプ (観察 / 動作)。 開始イベントがない場合 (記録は開始されているが実行されていない場合)、 */
        private val kinds = HashMap<Triple<String, Int, String?>, String>()

        fun add(e: RunEvent) {
            last = maxOf(last, e.atMs)
            when (e) {
                is RunEvent.TaskStarted -> if (started == null) { started = e.atMs; goal = e.goal.take(GOAL_MAX) }
                is RunEvent.ModelReply -> {
                    calls++
                    if (e.role == ROLE_LIGHT || !e.acted) light++
                    if (e.inputTokens == null && e.outputTokens == null) noUsage++
                    input += e.inputTokens ?: 0
                    output += e.outputTokens ?: 0
                    cached += e.cachedTokens ?: 0
                    modelMs += e.latencyMs.coerceAtLeast(0)
                    retryMs += e.retryWaitMs.coerceAtLeast(0)
                    if (e.acted) {
                        steps = maxOf(steps, e.step)
                        feedback(e.repliedAtMs ?: e.atMs)
                    }
                }
                is RunEvent.ToolResult -> tools += Tool(e.step, e.source, e.callId, e.durationMs.coerceAtLeast(0), e.waitMs.coerceAtLeast(0))
                is RunEvent.Activity -> {
                    kinds[Triple(e.source, e.step, e.callId)] = e.kind
                    feedback(e.atMs)
                }
                is RunEvent.TaskEnded -> if (ended == null) ended = e
                is RunEvent.ModelCalls -> all = e
                is RunEvent.OutcomeJudged -> judged = e.outcome
                else -> Unit
            }
        }

        private fun feedback(at: Long) {
            if (firstFeedback == null || at < firstFeedback!!) firstFeedback = at
        }

        /** は null になります。 */
        fun build(): RunMetrics? {
            val start = started ?: return null
            val end = ended
            val kindOf = { t: Tool -> kinds[Triple(t.source, t.step, t.callId)] }
            val (top, nested) = tools.partition { it.source !in NESTED }
            var observe = 0L
            var act = 0L
            for (t in top) {
                val kind = kindOf(t)
                val inner = nested.filter { it.step == t.step }
                // 外部呼び出しの消費時間にはすでにサブステップが含まれています。サブステップの読み取りは読み取りに含まれており、残りの計算操作も含まれています。
                val innerObserve = inner.filter { kindOf(it) == OBSERVE }.sumOf { it.durationMs }.coerceAtMost(t.durationMs)
                when {
                    kind == OBSERVE -> observe += t.durationMs
                    // 外層呼び出しに記録される waitMs は、サブステップ後の条件に従ってインターフェイスを待機する時間です。これはサブステップに含まれないため、操作としてカウントされません。
                    inner.isNotEmpty() -> { observe += innerObserve; act += (t.durationMs - innerObserve - t.waitMs).coerceAtLeast(0) }
                    // リズムに追加されたポーズ（waitMs）は操作ではなく待機としてカウントされます
                    kind != null -> act += (t.durationMs - t.waitMs).coerceAtLeast(0)
                    else -> Unit
                }
            }
            val counted = kinds.values
            return RunMetrics(
                runId = runId,
                goal = goal,
                startedAtMs = start,
                totalMs = ((end?.atMs ?: last) - start).coerceAtLeast(0),
                ended = end != null,
                firstFeedbackMs = firstFeedback?.let { (it - start).coerceAtLeast(0) },
                modelCalls = calls,
                lightCalls = light,
                callsWithoutUsage = noUsage,
                inputTokens = input,
                outputTokens = output,
                cachedTokens = cached,
                allCalls = all?.calls,
                allHttpCalls = all?.httpCalls,
                allInputTokens = all?.inputTokens,
                allOutputTokens = all?.outputTokens,
                modelMs = modelMs,
                retryWaitMs = retryMs,
                observeMs = observe,
                actMs = act,
                uiWaitMs = top.sumOf { it.waitMs },
                observeCalls = counted.count { it == OBSERVE },
                actCalls = counted.count { it == ACT },
                steps = steps,
                outcome = end?.outcome ?: UNFINISHED,
                failKind = end?.kind,
                judged = judged,
            )
        }
    }

    companion object {
        const val UNFINISHED = "unfinished"
        const val GOAL_MAX = 80
        private const val OBSERVE = "observe"
        private const val ACT = "act"
        private const val ROLE_LIGHT = "light"
        /** 外部呼び出しに含まれるサブステップ: アクションのバッチ内のステップ、ページ スクリプト内のアクション。 */
        private val NESTED = setOf("batch", "script")
        private val SUCCESS = setOf("succeeded", "unverified")
        private val NOT_DONE = setOf("partial", "not_possible")

        /** 実行の概要。開始イベントがない場合は null。 */
        fun of(events: List<RunEvent>, runId: String = ""): RunMetrics? = Builder(runId).apply { events.forEach(::add) }.build()
    }
}

/**
 * 複数の実行の概要 ([of]): 完了率、かかった時間と最初のフィードバックの中央値、ラウンドトリップとトークンの平均数、および時間の方向 (各項目の合計が合計時間を占めます)。
 * 最初のフィードバックは、この項目の実行の中央値のみを取得します。一度でも null になりません。
 */
data class RunStats(
    val runs: Int,
    val succeeded: Int,
    val medianTotalMs: Long,
    val medianFirstFeedbackMs: Long?,
    val avgModelCalls: Double,
    val avgTokens: Double,
    val totalMs: Long,
    val modelMs: Long,
    val observeMs: Long,
    val actMs: Long,
    val otherMs: Long,
) {
    val successRate: Double get() = if (runs == 0) 0.0 else succeeded.toDouble() / runs

    companion object {
        fun of(runs: List<RunMetrics>): RunStats = RunStats(
            runs = runs.size,
            succeeded = runs.count { it.succeeded },
            medianTotalMs = median(runs.map { it.totalMs }) ?: 0,
            medianFirstFeedbackMs = median(runs.mapNotNull { it.firstFeedbackMs }),
            avgModelCalls = if (runs.isEmpty()) 0.0 else runs.sumOf { it.modelCalls }.toDouble() / runs.size,
            avgTokens = if (runs.isEmpty()) 0.0 else runs.sumOf { it.tokens }.toDouble() / runs.size,
            totalMs = runs.sumOf { it.totalMs },
            modelMs = runs.sumOf { it.modelMs },
            observeMs = runs.sumOf { it.observeMs },
            actMs = runs.sumOf { it.actMs },
            otherMs = runs.sumOf { it.otherMs },
        )

        /** 中央値;偶数の場合は、中央の 2 つの平均値を取得します (切り捨て)。空のテーブルは null です。 */
        fun median(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
