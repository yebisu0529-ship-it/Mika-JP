package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Selector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.observation.InputHints
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.Trust

/** はランニング レコード内のイベントです。ディスクは events.jsonl の行であり、`type` フィールドによってタイプが区別されます。 */
@Serializable
sealed interface RunEvent {
    val atMs: Long

    /** [origin] この実行を開始した人 (`RunOrigin` の小文字の名前)。携帯電話で開始された場合は書き込まないでください。 [stepLimit] は、この実行のステップ数の上限であり、null に制限されません。 */
    @Serializable @SerialName("task_started")
    data class TaskStarted(
        override val atMs: Long, val goal: String, val model: String,
        val origin: String? = null, val stepLimit: Int? = null,
    ) : RunEvent

    /** このタスク ([com.wangzan101.mika.core.prompt.PromptVersion]) でモデルに送信されるシステム プロンプト ワードとツール定義の概要。 */
    @Serializable @SerialName("prompt_version")
    data class PromptVersion(override val atMs: Long, val hash: String) : RunEvent

    /** [fallback] は標準をリストできないことを意味し、[criteria] はユーザーのオリジナルの言葉です。 */
    @Serializable @SerialName("criteria")
    data class Criteria(
        override val atMs: Long,
        val criteria: List<String>,
        val ambiguities: List<String>,
        val kind: String = "",
        val fallback: Boolean = false,
    ) : RunEvent

    /** [tool]/[argsJson] は、このラウンドで実際に実行されたコールです。パラメータは鈍化されています。 */
    @Serializable @SerialName("model_reply")
    data class ModelReply(
        override val atMs: Long,
        val step: Int,
        val text: String,
        val tool: String?,
        val argsJson: String?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cachedTokens: Int?,
        /** が最後にリクエストを送信し、応答を受け取りました。以前に失敗したリクエストとバックオフは、[retryWaitMs] に個別に記録されます。 */
        val latencyMs: Long = 0,
        /** この呼び出しで使用される実際のモデル。 null = メインモデル。 */
        val model: String? = null,
        /** 大規模モデルと小規模モデル間の分業が有効な場合: main / light / light_rejected (軽量モデルの応答は実行されず、代わりにメイン モデルが要求されます)。それ以外の場合は null。 */
        val role: String? = null,
        /** 再試行までに費やした時間: 失敗したリクエストとバックオフ待機時間。 */
        val retryWaitMs: Long = 0,
        /**
         * 応答を受信した瞬間: インターフェイス上のこのステップの指示、何をすべきか、または応答がこの時点から表示されます (通話による応答は実行されるまで記録されません。[atMs] は後で表示されます)。
         * 再発送した軽量モデルは返信および古い記録に含まれません。
         */
        val repliedAtMs: Long? = null,
    ) : RunEvent {
        /** この返信がこのステップを実際に推進しました (再割り当てされた軽量モデルの返信はカウントされません)。 */
        val acted: Boolean get() = role != "light_rejected"
    }

    /** [resultJson] には保護された結論が含まれており、その全体を書き留めます。 [MAX_RESULT] を超える単語のみが切り捨てられ、マークされます。 */
    @Serializable @SerialName("tool_result")
    data class ToolResult(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val resultJson: String,
        val changed: Boolean?,
        val screenshotId: Int?,
        /** ポート内の安定したインターフェイスを含め、ツールの実行には時間がかかります。ユーザーの待機（確認、質問、引き継ぎ）を除きます。 */
        val durationMs: Long = 0,
        /** インターフェイスの中央部分は安定しており (ポートがレポートできる場合にのみ価値があります)、リズムが意図的に停止している時間です (ページを十分に読み込まずにページを変更するときに一時停止します)。 */
        val waitMs: Long = 0,
        /** モデル |バッチ |リプレイ |スクリプト (web_script 内のアクション) */
        val source: String = "model",
        /** 今回呼び出されるモデルのID。ルートはそれに応じてアクションとその結果を組み合わせます。 */
        val callId: String? = null,
    ) : RunEvent

    /** 再生ルートはステップ [step]/[total] に到達します。 [matched] は、ここでモデルに返されることを示す false です。 */
    @Serializable @SerialName("replay")
    data class Replay(override val atMs: Long, val routeId: String, val step: Int, val total: Int, val matched: Boolean, val detail: String) : RunEvent

    /** アクションのバッチがステップ [step]/[total] に到達します。 [stopped]、[reason] がここで停止する理由を説明します。 */
    @Serializable @SerialName("batch")
    data class Batch(override val atMs: Long, val step: Int, val total: Int, val stopped: Boolean, val reason: String?) : RunEvent

    /**
     * 書き込み操作時のターゲット要素と前後のアンカー ポイント (ツールの結果、切り捨て後の要素リストは不完全となり、ルートはそれに依存します)。 [argsJson] は感度が解除されています。
     * [effective]:ok であり、インターフェイスが変更されているか、入力リードバックが一貫しているか、または起動が成功しています。 [callId] は、同じ呼び出しからの [ToolResult.callId] と同じです。
     * [protected]: アクション前の画面は、スクリーンショットからシステムによって保護されています。
     */
    @Serializable @SerialName("action_target")
    data class ActionTarget(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val argsJson: String,
        val target: Selector?,
        val before: Anchors,
        val after: Anchors,
        val effective: Boolean,
        val callId: String? = null,
        val protected: Boolean = false,
        /** model | batch */
        val source: String = "model",
        /** このステップ (バッチ内のサブステップ) のゴールとアンカー ポイントを取得できません。ルートに欠落ステップがあってはならず、ルート全体が記録されるわけではありません。 */
        val incomplete: Boolean = false,
    ) : RunEvent

    /**
     * はフレーム (視覚化の使用: レビュー、ソース内のスクリーンショット): ステップ [step] の [mediaId] を保存しました。ここで、[place] は当時 (パッケージ名または「ドメイン名」) でした。
     * 古いレコードは screen_saved です (読み取り時にこのレコードに変更されます)。
     */
    @Serializable @SerialName("media")
    data class Media(override val atMs: Long, val mediaId: Int, val step: Int, val place: String?) : RunEvent

    /**
     * 証拠台帳に記録された観察 ([com.wangzan101.mika.core.observation.Observation] を参照): 元のテキストは感度が下げられ、切り詰められており、実行時台帳のものと同じです。
     * 確認 それに応じて台帳を再構築します。 [id] は台帳内の番号です (回答のソースはそれを参照しています)。 [kind] は観察の種類、[source] は観察を行う能力です。
     * [inputs] および [focused] は、その時点の入力ボックス内の単語です (入力ボックスのエコーを決定するために使用されます。ページのコンテンツを読み取る場合は null)。
     * の古いレコードは screen_text です (読み取りインターフェイスとページ全体のテキストのみが記録され、読み取り時にこれに変更されます。[id] は 0)。
     */
    @Serializable @SerialName("observed")
    data class Observed(
        override val atMs: Long, val step: Int, val text: String, val id: Int = 0, val kind: String = ObsKind.Read.wire,
        val source: String? = null, val place: Place? = null, val title: String? = null,
        val inputs: List<String>? = null, val focused: String? = null, val untrusted: Boolean = false,
    ) : RunEvent {
        /** 観察に戻ります。 */
        fun observation() = Observation(ObsKind.ofWire(kind) ?: ObsKind.Read, text, source, place, title,
            trust = if (untrusted) Trust.Untrusted else Trust.Trusted, inputs = inputs?.let { InputHints(it, focused) }, step = step, atMs = atMs, id = id)

        companion object {
            /** 台帳に記録された観察。 */
            fun of(o: Observation) = Observed(o.atMs, o.step, o.text, o.id, o.kind.wire, o.source, o.place, o.title,
                o.inputs?.names, o.inputs?.focused, o.trust == Trust.Untrusted)
        }
    }

    /** 実際のツール呼び出しの進行状況。読書カテゴリをまとめて表示したり、アクション カテゴリを 1 つずつ表示したりできます。 [obsIds] はこのステップで読み取った元のテキストを指し、[title] は読み取ったページのタイトルです。 */
    @Serializable @SerialName("activity")
    data class Activity(
        override val atMs: Long,
        val step: Int,
        val kind: String,
        val label: String,
        val target: String? = null,
        val effect: String? = null,
        val obsIds: List<Int> = emptyList(),
        val source: String = "model",
        val callId: String? = null,
        val title: String? = null,
        /** この呼び出しは失敗しました ([effect] は開発専用のエラー コードです)。 */
        val failed: Boolean = false,
        /** 不可逆的な判断は、これが外部アクション (送信、送信) であることを認識します。停止した後、何が行われたかを説明するときに、それが何回送信されたかを数えます。 */
        val outward: Boolean = false,
    ) : RunEvent

    @Serializable @SerialName("state")
    data class State(override val atMs: Long, val name: String, val detail: String) : RunEvent

    @Serializable @SerialName("confirmation")
    data class Confirmation(
        override val atMs: Long,
        val action: String,
        val target: String?,
        val content: String?,
        val amount: String?,
        val decision: String?,
    ) : RunEvent

    /**
     * 外部アクション、取り消し不可能なアクション、またはコストのかかるアクションに対する承認の結論 (モデルの Readme ではなくコードによって決定されます)。 [source] (モデル / バッチ / スクリプト / リプレイ) を開始した人。
     * [payment] が true の場合、このステップには費用がかかります (要求された場合にのみユーザーの同意が必要です)。 [decision] 許可/必須/不一致/拒否。
     */
    @Serializable @SerialName("approval")
    data class Approval(
        override val atMs: Long, val step: Int, val source: String, val tool: String, val action: String, val matched: String,
        val risk: String, val payment: Boolean = false, val decision: String,
    ) : RunEvent

    @Serializable @SerialName("takeover")
    data class Takeover(override val atMs: Long, val reason: String, val outcome: String?, val durationMs: Long?, val inputCount: Int?) : RunEvent

    @Serializable @SerialName("barrier")
    data class Barrier(override val atMs: Long, val kind: String) : RunEvent

    /**
     * 最終回答の文字通りの検証 (ステップ [step] の回答): [claims] は回答で検証できる事実の数であり、[unmatched] は読み取られた資料で見つからない少数の事実です。
     * [outward] は、このタスクが外部または元に戻せない何かを実行したことを意味します (したがって、別の検証モデルが要求されました)。
     */
    @Serializable @SerialName("answer_check")
    data class AnswerCheck(override val atMs: Long, val step: Int, val claims: Int, val unmatched: List<String>, val outward: Boolean = false) : RunEvent

    /** から提出された回答には、一度返送した後も不正確な点があり、未確認としてマークされていました。 */
    @Serializable @SerialName("unconfirmed")
    data class Unconfirmed(override val atMs: Long, val step: Int, val items: List<String>) : RunEvent

    /** コードは、証拠台帳 (アプリまたは Web ページ、どのステップ、そのステップのスクリーンショット) から答えのソースを見つけます。インターフェース上の「ソース」として使用します。 */
    @Serializable @SerialName("sources")
    data class Sources(override val atMs: Long, val items: List<com.wangzan101.mika.core.verify.AnswerSource>) : RunEvent

    /** [step]: どのステップがチェックされているかの最終応答 (古いレコードでは終了呼び出しです。このイベントは応答の前後に記録される可能性があります)。古いレコードにはありません。 */
    @Serializable @SerialName("verifier")
    data class Verifier(override val atMs: Long, val verdict: String, val detailJson: String, val step: Int? = null) : RunEvent

    /** このタスクのメモリ ブロックにリストされている実際の項目 (モデルの Readme ではなくコード レコード)。 */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(override val atMs: Long, val ids: List<String>) : RunEvent

    /** エージェントは、memory_save を使用してメモリを保存します。 [status] はアクティブ/保留中です。 */
    @Serializable @SerialName("memory_saved")
    data class MemorySaved(override val atMs: Long, val id: String, val kind: String, val status: String) : RunEvent

    /** が使用する個人スキル: [how] = 表示 (エージェントは skill_read で表示);古いレコードにも挿入されます (フルテキストは前のタスクから挿入されました)。これは読み取り時にも使用されます。 */
    @Serializable @SerialName("skill_used")
    data class SkillUsed(override val atMs: Long, val name: String, val how: String) : RunEvent

    @Serializable @SerialName("compressed")
    data class Compressed(override val atMs: Long, val foldedSteps: Int) : RunEvent

    /** [outcome]: 成功 / 未検証 / 失敗 / キャンセル / エラー; [kind] は失敗したカテゴリ (FailKind の小文字の名前) で、残りは null で、古いレコードはありません。 */
    @Serializable @SerialName("task_ended")
    data class TaskEnded(override val atMs: Long, val outcome: String, val answerOrReason: String, val kind: String? = null) : RunEvent

    /**
     * この実行中に測定されたすべてのモデル リクエストの合計 ([com.wangzan101.mika.core.usage.RunUsageTally] を参照): メイン ループの外側、意図判断、検証、
     * は、画像表示や圧縮などのすべてのバイパス呼び出しと失敗した再試行をカウントします。 [unreported] は、サービス プロバイダーが使用状況を報告しなかった (または通話が失敗した) 回数です。
     * は、[TaskEnded] の後に最後に記録されます。終了判定や解答提出後の記憶回復は含まれません（トークン使用ページはカウントされます）。古い記録にはそうではありません。
     */
    @Serializable @SerialName("model_calls")
    data class ModelCalls(
        override val atMs: Long, val calls: Int, val unreported: Int, val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
        val httpCalls: Int? = null, val httpUnreported: Int? = null,
    ) : RunEvent

    /**
     * 答えがユーザーに渡された後、モデルはユーザーが望む結果に従ってこの実行の結果を判断するように求められます ([com.wangzan101.mika.core.agent.OutcomeJudge] を参照)。
     * [outcome] は完了しました / 部分的 / 不可能 / 不明 (未決定); [reason] は、一文に何かが欠けていることを意味します。 [TaskEnded] の後に記録されます。古い記録にはそうではありません。
     */
    @Serializable @SerialName("outcome")
    data class OutcomeJudged(override val atMs: Long, val outcome: String, val reason: String = "", val model: String? = null) : RunEvent

    companion object {
        const val MAX_FIELD = 2000

        /** ツールの結果の上限: 異常に大きい出力によるレコード ファイルとメモリのバーストを防ぐためだけに、1 画面の要素リストよりもはるかに大きくなります。 */
        const val MAX_RESULT = 100_000

        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

        fun encode(e: RunEvent): String = json.encodeToString(serializer(), e)

        /** 1 行のレコード。名前変更前のイベント（screen_text、screen_saved）は、読み込む前に現在の書き込み方法に変更する必要があります。 */
        fun decode(line: String): RunEvent = json.decodeFromJsonElement(serializer(), LegacyEvents.upgrade(json.parseToJsonElement(line)))

        /** 実行記録に書かれている箇所：アプリはパッケージ名、サイトは「Webサイトのドメイン名」です（以前の記録と同じ書き方）。 */
        fun placeLabel(p: Place?): String? = p?.let { if (it.kind == com.wangzan101.mika.core.observation.PlaceKind.Site) "ウェブサイト ${it.name}" else it.name }

        fun clip(s: String): String = if (s.length <= MAX_FIELD) s else s.take(MAX_FIELD) + "…（短縮しました）"

        fun clipResult(s: String): String =
            if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "…（短縮しました。元の長さ: ${s.length}文字）"
    }
}

/** run.json の内容: イベントから蓄積された実行の概要。 */
@Serializable
data class RunSummary(
    val id: String,
    val goal: String,
    val model: String = "",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val answerOrReason: String? = null,
    val criteria: List<String> = emptyList(),
    val verdict: String? = null,
    /** 回答提出後の判定結果 ([RunEvent.OutcomeJudged.outcome])。未判定（失敗、キャンセル、旧記録）の場合はnullとなります。 */
    val judged: String? = null,
    val steps: Int = 0,
    /** この実行のステップ数の上限 ([RunEvent.TaskStarted.stepLimit] を参照)。古いレコードまたは制限がない場合は null です。 */
    val stepLimit: Int? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** を開始した人 ([RunEvent.TaskStarted.origin] を参照)。携帯電話で開始されたものは空です。 */
    val origin: String? = null,
) {
    /** イベントをサマリーにカウントします。 */
    fun with(e: RunEvent): RunSummary = when (e) {
        is RunEvent.TaskStarted -> copy(model = e.model, origin = e.origin, stepLimit = e.stepLimit)
        is RunEvent.Criteria -> copy(criteria = e.criteria)
        is RunEvent.ModelReply -> copy(
            steps = maxOf(steps, e.step),
            inputTokens = inputTokens + (e.inputTokens ?: 0),
            outputTokens = outputTokens + (e.outputTokens ?: 0),
            cachedTokens = cachedTokens + (e.cachedTokens ?: 0),
        )
        is RunEvent.Verifier -> copy(verdict = e.verdict)
        is RunEvent.TaskEnded -> copy(endedAtMs = e.atMs, outcome = e.outcome, answerOrReason = e.answerOrReason)
        is RunEvent.OutcomeJudged -> copy(judged = e.outcome)
        else -> this
    }
}
