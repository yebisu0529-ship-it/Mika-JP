package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.ToolInfo
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlin.coroutines.CoroutineContext

/** インターフェイスがタスク サービスに対してできること。これは実マシン上の TaskService によって実装され、テストではレコード呼び出しに置き換えられます。 */
interface TaskActions {
    fun start(goal: String, conversationId: String)
    /** は、タスクの進行中にダイアログに単語を挿入するために使用されます。 [messageId] は、この文の指示に従うために使用されます。 */
    fun steer(conversationId: String, messageId: String, text: String) = start(text, conversationId)
    fun withdrawSteer(conversationId: String, messageId: String): Boolean = false
    fun cancel()
    fun resume()
    fun takeOver()
    fun handBack()
    fun finishedMyself()
    fun skip()
    fun answer(text: String)
    /** [key] は、カードが属する宣言です。 */
    fun approve(key: Int)
    fun decline(key: Int)
    /** 並列タスク中にサービスにこの会話のタスクを表示させます (表示するだけです: 外部ステータス フローがそれに続きます)。 */
    fun show(conversationId: String) {}

    // 並列タスク中、アプリケーションのボタンは画面上にダイアログを表示し、そのダイアログのタスクのみに作用します。タスクがなくなった場合は無効になります。デフォルトはダイアログなしバージョンと同じです。
    fun cancelIn(conversationId: String) = cancel()
    fun resumeIn(conversationId: String) = resume()
    fun takeOverIn(conversationId: String) = takeOver()
    fun handBackIn(conversationId: String) = handBack()
    fun finishedMyselfIn(conversationId: String) = finishedMyself()
    fun skipIn(conversationId: String) = skip()
    fun answerIn(conversationId: String, text: String) = answer(text)
    fun approveIn(conversationId: String, key: Int) = approve(key)
    fun declineIn(conversationId: String, key: Int) = decline(key)
    /** 恢复这个对话任务当前 App 的声音;没有任务就不动。 */
    fun unmuteIn(conversationId: String) {}
}

enum class StepState { Ok, Run, Wait, Dim }

/**
 * 1 ステップのアクション: 何が行われたか ([label]、ツール名ではなくツールのインターフェイス用語)、何が行われ、その結果はどうなったか (実行記録内の効果)。
 * このステップをクリックすると、1 つずつリストされます。タイトルの行は概要です。クリックして入力した内容を確認したい場合は、ここを参照してください。
 */
data class StepAction(val label: String, val target: String?, val effect: String?, val failed: Boolean)

/**
 * の実行プロセスの行は、実際に行われた内容 (実行中のイベントのアクティビティ) から来ています: [title] が行ったこと (読み取りクラスは「WeChat チャット リストの確認」にマージされました)、
 * [reason] は、そのステップのモデルによって書き込まれた意図 (書き込まれていない場合は空)、[subtitle] は失敗した場合の説明で、通常は空です。
 */
data class StepRow(val step: Int, val title: String, val subtitle: String, val state: StepState, val reason: String = "") {
    /** インターフェイス上のこのステップの説明: モデル自身の言葉、そうでない場合はツールの説明を使用します。 */
    val text: String get() = reason.ifEmpty { title }
}

sealed interface ExchangeStatus {
    data object Working : ExchangeStatus
    data object WaitingUnlock : ExchangeStatus
    /** [item]/[amount]/[address]は引き継ぎ時のカードに別途記載されます。 */
    data class NeedsTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : ExchangeStatus
    data class NeedsInput(val prompt: String, val options: List<String>?) : ExchangeStatus
    /**
     * と他のユーザーは、費用がかかる操作に同意します (今回のみ同意できます)。 [title] "同意が必要です: <アクション>"、[body] 1 行に 1 つのフィールド (アプリ、オブジェクト、完全なコンテンツ、金額)。
     * [key] はこの宣言の番号です。ボタンにはそれが搭載されており、古いカードをクリックしてもカウントされません。
     */
    data class NeedsConfirmation(val title: String, val body: String, val key: Int) : ExchangeStatus
    /** 停止して待ちます。 [resumable] 適切なタイミングで「続行」をクリックして続行します。そうでない場合は、条件が満たされた後に自動的に続行されます (「続行」はカードには表示されません)。 */
    data class Paused(val why: String, val resumable: Boolean = true) : ExchangeStatus
    /** はまだ開始されておらず、開始キュー内にあります。順番が来ると自動的に開始され、キャンセルのみ可能です。 */
    data class Queued(val why: String) : ExchangeStatus
    /**
     * [verified] は false です。これは、完了したが検証できなかったことを意味します。 [note] が理由です。現時点では緑色のチェックはありません。 [unconfirmed] は、確認してもソースが見つからなかった回答の一部です。
     * カードに記載されています。 [reviewed] が false の場合、何もチェックされていません (チャットまたは回答にチェック可能な値がありません)。マークは付けられません。 [evidence] には、古いレコードのモデル自体によって書き込まれた「Based on:」セクションのみがあります。新しい回答ソースは実行中のレコードから取得されます ([evidenceSource] を参照)。
     */
    data class Done(
        val answer: String, val evidence: String?, val verified: Boolean = true, val note: String? = null,
        val unconfirmed: List<String> = emptyList(), val reviewed: Boolean = true,
    ) : ExchangeStatus {
        val unverifiedLine: String? get() = when {
            unconfirmed.isNotEmpty() -> tr(R.string.chat_unconfirmed, unconfirmed.joinToString(tr(R.string.list_sep)))
            verified || !reviewed -> null
            else -> note?.let { tr(R.string.chat_unverified_because, it) } ?: tr(R.string.chat_unverified)
        }
    }
    data class Failed(val reason: String) : ExchangeStatus
    /**
     * ユーザーが停止しました。 [summary] は、停止後に追加された説明 (何が行われ、何が行われていないのか) であり、これがこのラウンドの答えとみなされます。まだ書き込まれておらず、説明するものが何もない場合は null です。
     * [summarizing] 指示はまだ作成中です (「これまでに行われたことを整理しています...」)。
     */
    data class Cancelled(val summary: String? = null, val summarizing: Boolean = false) : ExchangeStatus
}

/**
 * 一問一答: ユーザーの言葉、実行プロセス、現状または結果。 [live] は、今回アプリを開いた後に実行されるタスク (ステップ付き) を示します。
 * [runId] は、タスクの完了後に表示できる実行レコードです。実行中は null であるか、レコードがありません。
 */
data class Exchange(
    val goal: String,
    val steps: List<StepRow>,
    val status: ExchangeStatus,
    val live: Boolean,
    val runId: String? = null,
    /** モデルのリクエストが進行中の場合はステータスのみが表示され、待機は実行ステップとして偽装されません。 モデル */
    val waitingForModel: Boolean = false,
    /** このリクエストには表示テキストが指定されています。進行状況のメモまたは完了前の返信になる場合があります。 */
    val draft: String? = null,
    /** 結果カードの下にメモリのプロンプトが表示されます (記憶/更新/忘れた/今後これを実行しますか)。 */
    val memoryNotes: List<MemoryNote> = emptyList(),
    /** 今回使用したメモリ エントリ番号 (モデルの Readme ではなく、コードによって記録されます)。 */
    val usedMemory: List<String> = emptyList(),
    /** 結果カードの下のスキルプロンプト（最近機能しない場合は学習/無効化されます）。 */
    val skillNotes: List<SkillNote> = emptyList(),
    /** 今回「Download/Mika」にエクスポートしたファイルはファイルカードとして表示されます。 */
    val files: List<SharedFile> = emptyList(),
    /** 今回アプリを開いてからフォローを開始した時間（ミリ秒）。不明な場合は 0 です。 */
    val startedAt: Long = 0L,
    /** 計算された終了時刻。終了していない場合、または開始時間が不明な場合は null になります。 */
    val elapsedMs: Long? = null,
    /** がこれまでに実際に行ったこと (ランニング イベント)。そこから[steps]が発売されました。 */
    val activities: List<RunEvent.Activity> = emptyList(),
    /** モデルがステップごとに記述されている場合 (進行状況の説明または呼び出し意図)、ステップ番号を押します。 */
    val notes: Map<Int, String> = emptyMap(),
    /** 現在何をしていますか: モデル ステップである場合、またはモデルを経由せずに複数のステップが進行している場合。そうでない場合は空です。 */
    val line: String = "",
    /** 進行中のタスク ユーザーのコメントとその宛先。 */
    val steers: List<SteerNote> = emptyList(),
    /** このラウンドの後、バックグラウンドで実行されたままになるページのサイト ([Turn.keptPage] を参照)。残っていない場合は null。 */
    val keptPage: String? = null,
    /** そのページが後で閉じられた理由 ([Turn.keptPageClosed])。まだオープンしているか、不明な場合は null です。 */
    val keptPageClosed: String? = null,
    /** 各ステップで実行される実際のアクション (ステップ番号別): タイトルは概要です。ステップをクリックすると、ここで詳細が表示されます。 */
    val stepActions: Map<Int, List<StepAction>> = emptyMap(),
)

/** 実行時の進行状況のライン: 待機中に何を待つか。モデルリクエストの送信中にのみニュートラル状態を与えます。そうでなければ、このステップであれば、最近は何も行われません。 */
fun liveLine(ex: Exchange): String? {
    when (ex.status) {
        is ExchangeStatus.NeedsTakeover -> return tr(R.string.takeover_title)
        is ExchangeStatus.NeedsInput -> return tr(R.string.chat_wait_answer)
        is ExchangeStatus.NeedsConfirmation -> return tr(R.string.chat_wait_approval)
        is ExchangeStatus.Queued -> return tr(R.string.chat_queued)
        else -> Unit
    }
    if (ex.waitingForModel) return tr(R.string.chat_working)
    return ex.line.ifEmpty { null } ?: ex.steps.lastOrNull()?.text
}

/** 以下の小さな文字の行を挿入してください。まだ行ってないのに書かないでください。 */
fun steerLabel(state: SteerState): String? = when (state) {
    SteerState.Pending, SteerState.Withdrawn -> null
    SteerState.Received -> tr(R.string.steer_received)
    SteerState.Joined -> tr(R.string.steer_joined)
    SteerState.Next -> tr(R.string.steer_next)
    SteerState.Cancelled -> tr(R.string.steer_cancelled)
}

/**
 * が連絡して、編集内容を取得できるかどうかを確認しました。吊り下げられたカプセルは画面とともに挿入され、ブラウザ ページに挿入された単語はページの説明とともに挿入されます。取得されると、入力ボックスは次のように始まります。
 * `[[screen:…]]` その 1 行または複数行は、ページの元のテキストを説明します。削除すると画面やページが消えてしまいます。変更するとページが認識できなくなります。これら 2 つのタイプは編集できません。
 */
internal fun editableSteer(text: String): Boolean = !UserScreenMessage.has(text) && AttachedPage.parse(text).page == null

/** 終了後に折り​​たたまれた行: 「12 ステップを実行しました · 1 分 20 秒」;時間がわからない場合は、歩数を言います。 */
fun stepsSummary(ex: Exchange): String =
    trPlural(R.plurals.chat_steps_done, ex.steps.size) + ex.elapsedMs?.let { " · " + RunTimelineViewModel.duration(it) }.orEmpty()

/** 結果カードのメモリとスキル プロンプト ボタン。実機では、メモリ ストレージと個人のスキル (読み取りと書き込みはバックグラウンド スレッドで行われます) によって実装され、テストではレコード呼び出しに置き換えられます。 */
interface LearningActions {
    suspend fun undo(token: String): Boolean
    suspend fun approve(pendingId: String): Boolean
    /** 取り消しトークンの有効期限が切れた(再起動した)場合、承認されるメソッドを直接削除することは「しないでください」。 */
    suspend fun forget(memoryId: String): Boolean
    fun viewSkill(draftId: String)
    suspend fun restoreSkill(name: String)
    suspend fun deleteSkill(name: String)
    /** プロンプトが変更されました (取り消しと承認後にその行を削除します): 会話を保存し、インターフェイスに通知します。 */
    suspend fun save(notes: RunNotes)
}

/**
 * 次の文で送信を待機している添付ファイルを選択します: コンテンツ アドレス ([uri])、名前、サイズ。送信時に会話ワークスペースにコピーします。
 * [photo] の場合、入力ボックスの上にサムネイルがあり、残りは小さなファイル カードです。
 */
data class PendingAttachment(val uri: String, val name: String, val size: Long, val photo: Boolean = AttachmentMessage.isPhoto(name))

enum class PipMode { Running, NeedsYou, Done }

/**
 * ピクチャー・イン・ピクチャーは画像のみを表示します。ステータスはフレーム ライトにのみ依存します (緑色は機能しており、オレンジ色の呼吸には対処が必要です)。やりたいことはダイアログ カードにあります。
 * [closed]: ユーザーがこのタスクの小さな画面をオフにし、右下隅に小さなボタンだけを残しました (クリックすると再び開きます。処理する場合はオレンジ色に変わります)。
 * このタスクをオフにするだけで、次のタスクは小さな画面のままになります。
 */
data class PipUi(
    val visible: Boolean = false,
    val mode: PipMode = PipMode.Running,
    val expanded: Boolean = false,
    val closed: Boolean = false,
)

enum class ComposerMode {
    /** にはタスクがなく、送信されると新しいタスクになります。 */
    Idle,
    /** タスクは実行中です。送信されると、このタスクに渡されます。停止は別のボタンです。 */
    Busy,
    /** エージェントは回答を待っています。回答は送信されます。 */
    Answer,
}

data class ChatUi(
    val conversationId: String? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** 最後の送信がブロックされたときに欠落していた条件。空はブロックされていないことを意味します。 */
    val blocked: List<Problem>? = null,
    val pip: PipUi = PipUi(),
    val composer: ComposerMode = ComposerMode.Idle,
    val takeoverStage: Takeover.Stage = Takeover.Stage.None,
    /** サンドボックスの準備ができている場合にのみ、入力ボックスの横に添付ボタンが表示されます。 */
    val attachEnabled: Boolean = false,
    val attachments: List<PendingAttachment> = emptyList(),
    /** 添付ファイルはワークスペースにコピー中です (送信時)。 */
    val importing: Boolean = false,
    /** 没収された添付ファイル (大きすぎるか、読み取れません)。空の場合はプロンプトは表示されません。 */
    val rejectedAttachments: List<String> = emptyList(),
    /** は一度に運べる数（[AttachTray.LIMIT]）を超えているため、数は加算されません。 0 の場合、プロンプトは表示されません。 */
    val overLimit: Int = 0,
    /** 入力ボックスの上にある次の文と一緒に送信される引用 (メッセージを長押しして「引用」を選択します)。そうでない場合は null です。会話を切り替えるとクリアされます。 */
    val quote: QuoteMessage.Quote? = null,
) {
    /** タスクは現在、バックグラウンドの仮想画面で動作しています (入力ボックスの上の行がプロンプトを表示します)。画面は開いていて動作しており、待機していたり​​ロックが解除されたりしていません。 */
    val operatingPhone: Boolean
        get() = pip.visible && pip.mode == PipMode.Running && exchanges.lastOrNull()?.status == ExchangeStatus.Working
}

/**
 * チャット インターフェイスのステータス。ステップ リストは、実行中のイベントで実際に行われた内容 (アクティビティ) から取得されます。進捗行がモデル ステップの場合、
 * モデル待機中のステータスのみを表示します。実行時入力ボックスにタスクが送信された場合、タスクはこのタスクに引き渡されます (中断)。
 * プロセス全体で使用されるインスタンスは 1 つだけです。インターフェイスを閉じて再度開いても、実行中のタスクのステップはそのまま残ります。
 */
class ChatViewModel(
    private val scope: CoroutineScope,
    taskState: StateFlow<TaskState>,
    private val takeoverStage: StateFlow<Takeover.Stage>,
    private val actions: TaskActions,
    private val store: ConversationStore,
    /**
     * サービスにはまだタスクがあります (この会話の前のタスクがまだ完了中か、他の場所で開始されたタスクが実行中です)。並列がオフになっている場合、新しいタスクはサービスに引き渡される前に完了するまで待機します。
     * は、このダイアログの新しい文が前のタスクの中断とみなされるのを防ぎます。待機中のメッセージの下に、「前のタスクが完了するまで待ってから開始してください」と表示されます。
     */
    private val serviceBusy: StateFlow<Boolean> = MutableStateFlow(false),
    /** パッケージ名をアプリ表示名（カード確認用）に変更します。 */
    private val appLabel: (String) -> String = { it },
    /**
     * 現在のタスクには仮想画面がありますか?初めてアプリを開くまで画面は作成されません。画面上で作成されていないタスク (純粋な対話、携帯電話を使用しない応答) では、ピクチャー・イン・ピクチャーは生成されません。
     * Q&A と確認はすべてチャット カードにあります。
     */
    private val screenOpen: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * 終了したばかりのタスクの実行レコード番号。サービスは終了を報告する前にそれを書き込みます。終了ステータスを受信すると質疑応答にハングアップします。
     * カードには「View Process」があります。その後、終了したものには変わりません。
     */
    private val endedRunId: StateFlow<String?> = MutableStateFlow(null),
    /**
     * 会話内で実行されているタスクの実行レコード番号 (タスクの開始時に使用可能ですが、割り当てられる前は null です)。 [endedRunIds]と同様にダイアログに従って取得してください
     * サービスの外部にある「表示されたタスク」を見ないでください。並行して、別の会話が行われる可能性があります。ランニング中に掛けると、その場で「ランニングプロセス」を開くことができます。
     */
    private val liveRunId: (conversationId: String) -> String? = { null },
    /** 各会話で最後に完了したタスクの実行レコード番号。指定されている場合は、上記の会話ではなく、カードが配置されている会話に従ってください (表示されているタスクのみを指します)。 */
    private val endedRunIds: StateFlow<Map<String, String?>>? = null,
    /** スイッチParallelTask​​s: オンにすると、異なる会話のタスクを同時に実行でき、インターフェイスは画面上のこの会話のタスクのみに従います。タスクの実行中にインターフェイスはリロードされません。 */
    private val parallelFlag: () -> Boolean = { false },
    /** サービス表示のタスク: (ダイアログ、ステータス)、両方を一緒に書き込みます。 [taskState] の代わりにこれを並行して使用すると、他のダイアログのステータスは含まれません。 */
    private val shownTask: StateFlow<Pair<String, TaskState>?> = MutableStateFlow(null),
    /** 実行中またはキューに入れられているタスクが配置されているダイアログ。 */
    private val activeConversations: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    /** ダイアログが実行しているタスクの元の単語。に切り替えて表示を継続する場合に使用します。 */
    private val runGoal: (String) -> String? = { null },
    /** この会話で実行されているタスクのステータス (現時点でサービスが誰を表示しているかに関係なく)。切り取った後は、それに寄りかかり、質疑応答の手順を記録し続けます。 */
    private val runState: (String) -> StateFlow<TaskState>? = { null },
    /** 実行番号に応じて、ミッション後に追加されるメモリとスキルのヒント。 */
    private val runNotes: StateFlow<Map<String, RunNotes>> = MutableStateFlow(emptyMap()),
    private val learning: LearningActions? = null,
    /** 現時点のメモリ エントリ (暗号化されたストレージから読み取られたもの)。プロンプトのテキストと「使用された」コンテンツはここから取得され、コンテンツは会話ファイルには保存されません。 */
    private val memoryItems: StateFlow<List<MemoryItem>> = MutableStateFlow(emptyList()),
    /** サンドボックスの準備ができました。 */
    private val sandboxReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** 現在のタスクによってエクスポートされたファイル。タスクの開始時にサービスによってクリアされます。 */
    private val sharedFiles: StateFlow<List<SharedFile>> = MutableStateFlow(emptyList()),
    /** 選択したファイル (コンテンツ URI 文字列) を会話ワークスペースの inbox/ にコピーします。 */
    private val importAttachments: suspend (conversationId: String, uris: List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
    /** は、名前、サイズ、選択されたファイル (コンテンツ URI 文字列) の写真であるかどうかを読み取ります。ディスクの読み取り作業は、呼び出し元によってバックグラウンド スレッドに配置されます。 */
    private val describeAttachments: suspend (uris: List<String>) -> List<PendingAttachment> = { uris -> uris.map { PendingAttachment(it, it.substringAfterLast('/'), 0L) } },
    /** ステップラインのツールの説明。 */
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    /** 表示されているタスクが実際に行ったこと (実行中のイベントのアクティビティ)。 */
    private val activities: StateFlow<List<RunEvent.Activity>> = MutableStateFlow(emptyList()),
    /** 受信した割り込みとタスクの宛先をディスプレイに表示します。 */
    private val steers: StateFlow<List<SteerNote>> = MutableStateFlow(emptyList()),
    /** は、停止後に割り当てられた実行 (実行番号) を書き込んでいます。その実行の下に「Compiling what has been been been been...」と表示されます。 */
    private val summarizing: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** 時間を計算するためのクロック (ミリ秒)。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 会話を削除するとどうなりますか (`ConversationDeleter.impact` を参照)。 */
    private val deletionImpact: (String) -> DeleteImpact = { DeleteImpact(runningTask = false, schedules = 0) },
    /** は、会話とそれに属するすべてを削除します (`ConversationDeleter.delete` を参照)。 */
    private val removeConversation: suspend (String) -> Unit = {},
    /** タスク終了後にバックグラウンドで実行されたままのページ (グローバルに最大 1 ページ)。会話ページには、元のラウンドの下に行が表示され、一番上の列には「1 つの Web ページがバックグラウンドで維持されています」と表示されます。 */
    val keptPage: StateFlow<KeptPage?> = MutableStateFlow(null),
    /** 残りのページを閉じます (ユーザーがその行で「閉じる」をクリックしました)。 */
    private val closeKept: () -> Unit = {},
    /** が分岐するとき、ソース会話ワークスペースの受信箱/(添付ファイル)をコピーします。インジェクションを行わない場合は、ラウンドのみがコピーされます。 */
    private val branchWorkspace: BranchWorkspace? = null,
    /** ブランチ タイトル: ソース タイトルまたは最初の文の後にローカライズされたサフィックスを追加します (長すぎる場合は切り捨てられます)。デフォルトはそのままです。 */
    private val branchTitle: (String) -> String = { it },
    /** 会話レコードの読み取りと書き込み、およびブランチのコピーを行う場所。 */
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /** ユーザーが会話ページのバックグラウンドに残されたページを閉じます。 */
    fun closeKeptPage() = closeKept()

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** 削除された（または削除中の）会話: 削除が完了するまで、サイドバーにはその場で会話がリストされなくなります。 */
    private val _deleted = MutableStateFlow<Set<String>>(emptySet())
    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    /** はまだ削除されていません。 */
    private val deleting = mutableSetOf<String>()

    /** 最新のブランチ コピー ワークスペースの結果 (上限を超えている、ターゲットに既に受信トレイがあるなど)。インターフェイスには、調整が完了したことを示すプロンプト（[clearBranchCopy]）が表示されます。 */
    private val _branchCopy = MutableStateFlow<BranchCopyResult?>(null)
    val branchCopy: StateFlow<BranchCopyResult?> = _branchCopy.asStateFlow()

    /** 画面上のこのダイアログに加えて、タスクが実行中またはキューに入れられているダイアログがあります (上部に「別の n タスクが進行中」)。 */
    val otherRunning: StateFlow<List<String>> = combine(activeConversations, _ui.map { it.conversationId }.distinctUntilChanged()) { active, current ->
        active.filter { it != current }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 並列処理中に切り取られた会話とタスクはまだ実行中です。質問と回答 (元の単語、標準、ステップ、ファイル)、および画面が開いているかどうかは保持されます。
     * バックグラウンドは、自身のタスクのステータスに従って更新ステップを続行し、元の接続に戻ります。ミッションの終了時に破棄されます (そのラウンドはサービスによって会話に保存されます)。
     */
    private class Parked(val exchange: Exchange, val screenSeen: Boolean, val pipClosed: Boolean = false)
    private val parked = mutableMapOf<String, Parked>()
    private val parkedJobs = mutableMapOf<String, Job>()

    /** 画面上でこの会話が現在続いている質問と回答を一時停止します (別の会話に切り替える前に呼び出されます)。 */
    private fun parkLive() {
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val live = ui.exchanges.getOrNull(liveIndex) ?: return
        if (!live.status.active() || id !in activeConversations.value) return
        parked[id] = Parked(live, screenSeen, pipClosed)
        parkedJobs.remove(id)?.cancel()
        val flow = runState(id) ?: return
        parkedJobs[id] = scope.launch {
            flow.collect { s ->
                if (!s.active()) {
                    if (s != TaskState.Idle) { parked.remove(id); parkedJobs.remove(id)?.cancel() }
                    return@collect
                }
                parked[id]?.let { p -> parked[id] = Parked(reduce(p.exchange, s, appLabel, toolLabels::label), p.screenSeen, p.pipClosed) }
            }
        }
    }

    private fun unpark(id: String): Parked? {
        parkedJobs.remove(id)?.cancel()
        return parked.remove(id)
    }

    /** スイッチはタスクがないときに再読み取りされ、タスクの開始時や会話の変更時にも再読み取りされます ([refreshParallel])。ここでは、次のタスクの途中でスイッチを変更しても、このラウンドの動作は変わりません。 */
    private var parallelOn = parallelFlag()
    private fun parallel(): Boolean {
        if (liveIndex < 0 && activeConversations.value.isEmpty()) parallelOn = parallelFlag()
        return parallelOn
    }

    /**
     * タスクの開始時または会話の変更時にスイッチを再読み取りします (このインターフェイスはタスクに従っていません): 設定で変更したばかりのスイッチは、インターフェイスを再構築しなくても有効になります。
     * は、サービスがタスクを開始するたびに再読み取りされます。他の対話タスクはまだ実行中であり、サービス内のそれぞれの開始スイッチを押します。
     */
    private fun refreshParallel() {
        if (liveIndex < 0) parallelOn = parallelFlag()
    }

    /** 交換内で追跡されているタスクの位置。 -1 は何もないことを意味します。 */
    private var liveIndex = -1
    private var lingerJob: Job? = null
    /** はサービスの終了後にキューに入れられており、まだサービスの開始に引き渡されていません。この間に停止をクリックすると、直接無効になります。 */
    private var pendingStart: Job? = null
    /** 追跡されているタスクが画面全体に開かれています。一度開くと、ピクチャー・イン・ピクチャーはタスクが終了するまで (完了後の数秒間を含む) 表示されます。 */
    private var screenSeen = false
    /** ユーザーが追跡されているタスクの小さな画面をオフにしました (タスクを変更するときにクリアされました。[PipUi.closed] を参照)。 */
    private var pipClosed = false

    init {
        // スイッチがオフの場合でも、ステータス バンド内のダイアログは認識されます。他のダイアログのタスク (一時停止カプセル、スケジュールされたタスク) は、画面上のこのダイアログに接続されません。
        scope.launch { taskState.collect { s -> if (!parallel()) shownTask.value?.let { onShown(it.first, s) } ?: onState(s) } }
        scope.launch { shownTask.collect { t -> if (t != null && parallel()) onShown(t.first, t.second) } }
        // 誰がこのダイアログを画面上に保存しても (中断されたカプセル、スケジュールされたタスク、ここでは)、保存されたコピーに記入してください。
        scope.launch {
            store.changes.collect { id ->
                if (id == _ui.value.conversationId) syncSaved(id)
                // 誰かが削除された会話を再度保存しました: もう一度リストしてください
                if (id in _deleted.value && id !in deleting && store.turns(id).isNotEmpty()) _deleted.update { it - id }
            }
        }
        // パラレルモードでは、画面上の本ダイアログのタスクの引き継ぎ進捗状況のみを収集します。
        scope.launch {
            takeoverStage.collect { s -> if (ownTask()) _ui.update { it.copy(takeoverStage = s) } }
        }
        // タスクの進行中に新しくリストされた基準のみを認識します。サブスクリプション時の古い値は、前のタスクの値である可能性があります。
        // 画面とステータスが同じかどうかはタスクごとに異なります。並行して、画面上のダイアログのタスクのみが収集されます。
        scope.launch { screenOpen.collect { if (it && ownTask()) onScreenOpened() } }
        scope.launch { sandboxReady.collect { r -> _ui.update { it.copy(attachEnabled = r) } } }
        scope.launch { sharedFiles.collect { if (ownTask()) onSharedFiles(it) } }
        scope.launch { runNotes.collect { notes -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withNotes(it, notes) }) } } }
        scope.launch { activities.collect { if (ownTask()) onActivities(it) } }
        scope.launch { steers.collect { if (ownTask()) onSteers(it) } }
        // タスクリストへのタスク登録（元の単語が取得可能）は最初の状態より遅れる可能性があります。その時点での次の質問と回答は元の単語で補足されます
        scope.launch { activeConversations.collect { fillGoal() } }
        scope.launch { summarizing.collect { set -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withSummarizing(it, set) }) } } }
    }

    /** 終了ラウンド：説明途中の場合は「整理中」マークがつきます。書き込まれていれば、書き込めなければ削除されます。 */
    private fun withSummarizing(ex: Exchange, set: Set<String> = summarizing.value): Exchange {
        val st = ex.status as? ExchangeStatus.Cancelled ?: return ex
        val on = st.summary == null && ex.runId != null && ex.runId in set
        return if (st.summarizing == on) ex else ex.copy(status = st.copy(summarizing = on))
    }

    private fun onActivities(list: List<RunEvent.Activity>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) {
                val ex = exchanges[liveIndex].copy(activities = list)
                exchanges[liveIndex] = ex.copy(
                    steps = rows(ex, toolLabels::label, appLabel),
                    stepActions = actionsOf(list, appLabel, toolLabels::label),
                )
            }
            ui.copy(exchanges = exchanges)
        }
    }

    /**
     * 間投詞の方向は、質問と回答が配置されている番号に応じて変更されます (タスク終了後に続くものではない場合があります)。
     * フォローしているタスクの別の場所 (吊り下げられたカプセル) に挿入する場合、まだここにない場合は、フォローしている質問と回答の下にハングします。
     */
    private fun onSteers(list: List<SteerNote>) {
        val byId = list.associateBy { it.id }
        _ui.update { ui ->
            val shown = ui.exchanges.flatMap { ex -> ex.steers.map { it.id } }.toSet()
            val added = list.filter { it.id !in shown }
            ui.copy(exchanges = ui.exchanges.mapIndexed { i, ex ->
                val updated = if (ex.steers.none { it.id in byId }) ex
                    else ex.copy(steers = ex.steers.map { n -> byId[n.id]?.let { n.copy(state = it.state, interrupted = it.interrupted ?: n.interrupted, finished = it.finished ?: n.finished) } ?: n })
                if (i == liveIndex && added.isNotEmpty() && ex.status.active()) updated.copy(steers = updated.steers + added) else updated
            })
        }
    }

    /** 外部に提供される項目 (受信者の進行状況、画面の有無、エクスポートされたファイル) は、現時点では画面上のダイアログのタスクですか?同時にディスプレイに誰が表示されているかを確認します。 */
    private fun ownTask() = !parallel() || shownTask.value?.first == _ui.value.conversationId

    private fun withNotes(ex: Exchange, notes: Map<String, RunNotes>): Exchange {
        val n = ex.runId?.let(notes::get) ?: return ex
        return ex.copy(memoryNotes = n.memoryNotes, usedMemory = n.usedMemory, skillNotes = n.skillNotes)
    }

    /** は、このテキスト行の内容が現時点でメモリから取得されることを要求します。削除されている場合は「削除された記憶」と記入してください。 */
    fun noteText(note: MemoryNote): String = note.text(memoryItems.value.find { it.id == note.memoryId })

    /** このメモリ変更を元に戻します。トークンの有効期限が切れた (再起動した) 場合にのみ、「元に戻す」を削除します。 */
    fun undoNote(runId: String, note: MemoryNote) {
        val token = note.undoToken ?: return
        scope.launch {
            val ok = learning?.undo(token) ?: false
            editNotes(runId) { ex -> ex.copy(memoryNotes = if (ok) ex.memoryNotes - note else ex.memoryNotes.map { if (it == note) it.copy(undoToken = null) else it }) }
        }
    }

    /** 実践: 「良好」が承認されました。 */
    fun approveNote(runId: String, note: MemoryNote) {
        val id = note.pendingId ?: return
        scope.launch {
            learning?.approve(id)
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** メソッド: この新しい追加を元に戻すことは「しないでください」。トークンがなくなった場合は、削除してください。 */
    fun rejectNote(runId: String, note: MemoryNote) {
        scope.launch {
            val undone = note.undoToken?.let { learning?.undo(it) } ?: false
            if (!undone) note.pendingId?.let { learning?.forget(it) }
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** 「nメモリ使用」をクリックしたときの内容です。削除されたものはリストされません。 */
    fun usedMemoryTexts(ids: List<String>): List<String> {
        val byId = memoryItems.value.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.content }
    }

    fun viewSkill(note: SkillNote) { note.draftId?.let { learning?.viewSkill(it) } }

    fun restoreSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.restoreSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    fun deleteSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.deleteSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    private suspend fun editNotes(runId: String, change: (Exchange) -> Exchange) {
        var saved: Exchange? = null
        _ui.update { ui ->
            ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it).also { e -> saved = e } else it })
        }
        val ex = saved ?: return
        val conversation = _ui.value.conversationId ?: return
        learning?.save(RunNotes(conversation, runId, ex.memoryNotes, ex.usedMemory, ex.skillNotes))
    }

    private fun onSharedFiles(files: List<SharedFile>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(files = files)
            ui.copy(exchanges = exchanges)
        }
    }

    private fun canAttach(ui: ChatUi) = ui.attachEnabled && ui.composer == ComposerMode.Idle && !ui.importing

    /** カメラ、フォト アルバム、ファイル セレクターから取得したファイル: タスクが実行されておらず、サンドボックスの準備ができているときに収集されます。名前とサイズを読み上げて入力ボックスの上に貼り、次の文を添えて送信します。 */
    fun attach(uris: List<String>) {
        if (uris.isEmpty() || !canAttach(_ui.value)) return
        scope.launch {
            val items = try { describeAttachments(uris) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            _ui.update { ui ->
                if (!canAttach(ui)) return@update ui
                val (all, dropped) = AttachTray.add(ui.attachments, items)
                ui.copy(attachments = all, overLimit = dropped, rejectedAttachments = emptyList())
            }
        }
    }

    /** パネル内の最新の写真をクリックします。選択またはキャンセルします。いっぱいの場合は false を返します。 */
    fun toggleAttachment(item: PendingAttachment): Boolean {
        val ui = _ui.value
        if (!canAttach(ui)) return true
        val t = AttachTray.toggle(ui.attachments, item)
        _ui.update { it.copy(attachments = t.items) }
        return !t.full
    }

    fun removeAttachment(uri: String) = _ui.update { ui -> ui.copy(attachments = ui.attachments.filterNot { it.uri == uri }) }

    fun dismissRejected() = _ui.update { it.copy(rejectedAttachments = emptyList(), overLimit = 0) }

    private fun onScreenOpened() {
        if (liveIndex < 0 || screenSeen) return
        screenSeen = true
        _ui.update { ui ->
            val live = ui.exchanges.getOrNull(liveIndex)
            if (live != null && live.status.active()) ui.copy(pip = gated(ui.pip.copy(visible = true))) else ui
        }
    }

    /**
     * 小さい画面を表示する場合: 画面を開く必要があります (他のフィールドはそれに応じて計算され、画面が開いたときに直接使用されます)。ユーザーがこのタスクの小さな画面を閉じると、小さなボタンだけが残ります。
     * は自動的に展開されなくなりました (また、テイクオーバーの開始時にポップアップしません。対処する必要がある内容はダイアログ カードに表示され、小さなボタンがオレンジ色に変わります)。
     */
    private fun gated(p: PipUi) = when {
        !screenSeen -> p.copy(visible = false, expanded = false, closed = false)
        pipClosed -> p.copy(expanded = false, closed = true)
        else -> p.copy(closed = false)
    }


    /** メッセージを長押しして「引用」を選択します。引用された内容が入力ボックスの上に配置され、次の文とともに送信されます (新しいタスク、中断、回答が含まれます)。空のものはつながりません。 */
    fun quote(from: QuoteMessage.From, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) _ui.update { it.copy(quote = QuoteMessage.Quote(from, t)) }
    }

    fun clearQuote() = _ui.update { it.copy(quote = null) }

    /** は入力ボックス内の単語を送信します。引用がある場合、その引用は単語の前に書かれ ([QuoteMessage] を参照)、送信後に消去されます。 false を返す場合は、送信されていない (条件が満たされていないか、タスクが実行中である) ことを意味します。 */
    fun send(text: String, check: Prerequisites.Check): Boolean {
        refreshParallel()
        val ui = _ui.value
        if (ui.importing) return false
        val sent = sendQuoted(text.trim(), ui, check)
        if (sent) _ui.update { it.copy(quote = null) }
        return sent
    }

    private fun sendQuoted(words: String, ui: ChatUi, check: Prerequisites.Check): Boolean {
        // 添付ファイルの後には、新しいタスクのみが続きます。質問に回答する場合にはそれらは含まれません。
        if (ui.composer == ComposerMode.Idle && ui.attachments.isNotEmpty()) return sendWithAttachments(QuoteMessage.compose(ui.quote, words), ui.attachments, check)
        // 引用のみで言葉は掲載されません。
        if (words.isEmpty()) return false
        val goal = QuoteMessage.compose(ui.quote, words)
        when (ui.composer) {
            ComposerMode.Answer -> {
                act({ actions.answer(goal) }) { actions.answerIn(it, goal) }
                return true
            }
            ComposerMode.Busy -> return steer(goal, check)
            ComposerMode.Idle -> Unit
        }
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        begin(goal, ui.conversationId ?: store.newConversation())
        return true
    }

    /**
     * 添付ファイル付きの新しいタスクを開くには、まず添付ファイルを会話ワークスペースの受信トレイにコピーします (入力ボックスの上部に期間中の追加が表示されます)。
     * 次に、各添付ファイルを 1 行「添付ファイル: <パス>(<サイズ>)」として書き込み、その単語に従ってタスクを開始します。 「没収」の下にあるものは入力ボックスの上にリストされます。文章や添付ファイルがない場合は送信されません。
     * コピー中に会話が変更されたり、別のタスクが開始された場合、会話は送信されません (コピーされたファイルは元の会話のワークスペースに残ります)。
     */
    private fun sendWithAttachments(text: String, items: List<PendingAttachment>, check: Prerequisites.Check): Boolean {
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val id = _ui.value.conversationId ?: store.newConversation()
        _ui.update { it.copy(conversationId = id, importing = true, rejectedAttachments = emptyList(), overLimit = 0) }
        scope.launch {
            val r = try { importAttachments(id, items.map { it.uri }) } catch (e: CancellationException) { throw e } catch (e: Exception) { ImportResult(emptyList(), items.map { it.name }) }
            val goal = withAttachments(text, r.added)
            _ui.update { it.copy(importing = false, attachments = emptyList(), rejectedAttachments = r.rejected) }
            val now = _ui.value
            if (goal.isEmpty() || now.conversationId != id || now.composer != ComposerMode.Idle) return@launch
            begin(goal, id)
        }
        return true
    }

    /** は会話で新しいタスクを開始します [id] [goal]: 質問と回答をインターフェイスに追加し、サービスに渡します (サービスがまだビジー状態の場合、サービスは並行して閉じられ、エンドの後ろでキューに入れられます)。 */
    private fun begin(goal: String, id: String) {
        lingerJob?.cancel()
        // このダイアログの番号が決定されました: カプセルの後に続きます (新しく開かれたダイアログには、この時点まで番号がありません)
        markCurrent(CurrentChat.Open(id))
        // 前のタスクが終了したら、まず画面を削除してから終了をアナウンスします。ここで読む内容は、新しいタスクが開始される前の様子です。
        // 並行して実行すると、別の会話タスクの画面になり、新しいタスクにはまだ画面がありません。
        // 新しいメッセージが開始されると、最後に実行された画面を継承できません。現在の画面が実際に構築された後、screenOpen によって小さなウィンドウが開きます。
        screenSeen = false
        pipClosed = false
        _ui.update {
            liveIndex = it.exchanges.size
            it.copy(
                conversationId = id,
                exchanges = it.exchanges + Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, startedAt = clock()),
                blocked = null,
                attachments = emptyList(),
                pip = gated(PipUi(visible = true)),
                composer = ComposerMode.Busy,
            )
        }
        // サービスが並行してビジー状態になっている場合 (他のダイアログ タスクが実行中である場合)、サービスはサービスに直接引き渡されます。
        if (serviceBusy.value && !parallel()) {
            // 待機期間中は、滞っているように見えないように、何を待っているのかを明確に述べてください。サービスに渡された後、次にタスク自体のステータスが書き込まれます。
            _ui.update { ui ->
                val exchanges = ui.exchanges.toMutableList()
                exchanges[liveIndex] = exchanges[liveIndex].copy(line = tr(R.string.chat_wait_previous_task))
                ui.copy(exchanges = exchanges)
            }
            // 過去形を引き渡すとき、他の場所 (タイミング、コンピューター) がたまたま最初に過去形を開始する可能性があります。サービスは過去形をその後ろにランク付けするため、過去形が失われることはありません。
            pendingStart = scope.launch {
                serviceBusy.first { !it }
                pendingStart = null
                actions.start(goal, id)
                // 待機中の介入: 最初に元のタスクを開始してから、それに引き継ぎます。
                held.toList().also { held.clear() }.forEach { n -> actions.steer(id, n.id, n.text) }
            }
        } else {
            actions.start(goal, id)
        }
    }

    /**
     * 実行中にタスクが送信された場合: フォローしている質問と回答の下にハングし、それをこのタスクに渡します。その所在はサービス ([steers]) によって報告されます。
     * 次のタスクになる可能性があるため、新しいタスクを開くのと同じチェックが行われます。サービス ([pendingStart]) に引き渡される前にタスクを保持し、開始後に送信します。
     */
    private fun steer(text: String, check: Prerequisites.Check): Boolean {
        val id = _ui.value.conversationId ?: return false
        if (liveIndex !in _ui.value.exchanges.indices) return false
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val note = SteerNote(java.util.UUID.randomUUID().toString(), text, SteerState.Pending)
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            exchanges[liveIndex] = exchanges[liveIndex].let { it.copy(steers = it.steers + note) }
            ui.copy(exchanges = exchanges)
        }
        if (pendingStart?.isActive == true) held += note else actions.steer(id, note.id, text)
        return true
    }

    fun withdrawSteer(messageId: String): Boolean {
        val ui = _ui.value
        val id = ui.conversationId ?: return false
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return false
        if (!note.state.unconsumed || note.remote) return false
        if (!held.removeAll { it.id == messageId } && !actions.withdrawSteer(id, messageId)) return false
        _ui.update { now -> now.copy(exchanges = now.exchanges.map { ex ->
            ex.copy(steers = ex.steers.map { if (it.id == messageId) it.copy(state = SteerState.Withdrawn) else it })
        }) }
        return true
    }

    fun editSteer(messageId: String): String? {
        val ui = _ui.value
        if (ui.quote != null || ui.attachments.isNotEmpty() || ui.importing) return null
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return null
        if (!editableSteer(note.text) || !withdrawSteer(messageId)) return null
        val parsed = QuoteMessage.parse(note.text)
        _ui.update { it.copy(quote = parsed.quote) }
        return parsed.words
    }

    /** 元の単語のタスクがサービスに引き継がれていないときに挿入される単語。 */
    private val held = mutableListOf<SteerNote>()

    /**
     * は、画面上のこのダイアログのタスクのステータスのみを収集します。インターフェイスが最初に表示され、ダイアログが存在しない場合は、実行中のインターフェイスが (そのダイアログの保存されたラウンドとともに) 選択されます。
     * サービスが別の会話タスク (別の場所で開始されたばかり) を表示するように変更されたとき、途中で停止しないように、ここでフォローされているタスクがまだ実行されている間にサービス表示を元に戻します。
     */
    private fun onShown(conversationId: String, s: TaskState) {
        // 削除されたダイアログのタスクは停止中です: 画面上で受信されません
        if (conversationId in _deleted.value) return
        val current = _ui.value.conversationId
        when {
            current == null -> {
                if (!s.active()) return
                _ui.update { it.copy(conversationId = conversationId, exchanges = saved(conversationId)) }
            }
            current != conversationId -> {
                val live = _ui.value.exchanges.getOrNull(liveIndex)
                if (live != null && live.status.active() && current in activeConversations.value && pendingStart?.isActive != true) actions.show(current)
                return
            }
        }
        onState(s)
    }

    /**
     * 特定の会話に切り替えます (タスクが並行して実行されているときに切り替えることもできます): 履歴をロードします。実行中のタスクがある場合は、サービスにそのタスクを表示させ、現在のステータスを接続させます。
     * は、スイッチがオフの場合は [openConversation] と同じです。
     */
    fun switchTo(conversationId: String) {
        refreshParallel()
        if (!parallel()) return openConversation(conversationId)
        lingerJob?.cancel()
        if (conversationId != _ui.value.conversationId) parkLive()
        val past = saved(conversationId)
        liveIndex = -1
        // タスクのないダイアログは、他のタスクの進行状況を引き継ぎません。
        _ui.update { ChatUi(conversationId = conversationId, exchanges = past, takeoverStage = Takeover.Stage.None) }
        markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { unpark(conversationId); return }
        follow(conversationId, unpark(conversationId))
    }

    /**
     * 画面上のこのダイアログの実行中のタスクに接続します (どこから開始するかは関係ありません: ここでは、中断されたカプセル、時限タスク)。あなたをフォローするときに、最初に質問と回答を準備しませんでした。
     * (終了時に駐車されていた [p]、またはタスクの元の言葉に従って新しいタスクを開始する) をサービスに表示させ、現時点でのサービスの外部項目に従って完了します。
     */
    private fun follow(conversationId: String, p: Parked? = null) {
        val fresh = liveIndex < 0
        if (fresh) {
            // まずこのダイアログの質問と回答をレイアウトし、それをサービスに表示させます。サービスが表示を変更するとすぐに、ステータスがその場で送信されます (onShown)。
            // この質問と回答はその時点ですでに存在している必要があります。そうでない場合は、元の言葉なしで別の質問と回答が存在します。
            val live = p?.exchange ?: Exchange(runGoal(conversationId).orEmpty(), emptyList(), ExchangeStatus.Working, live = true, startedAt = clock())
            screenSeen = p?.screenSeen ?: false
            pipClosed = p?.pipClosed ?: false
            _ui.update {
                liveIndex = it.exchanges.size
                it.copy(exchanges = it.exchanges + live, composer = ComposerMode.Busy, pip = gated(PipUi(visible = true)))
            }
        }
        actions.show(conversationId)
        val shown = shownTask.value?.takeIf { it.first == conversationId } ?: return
        // あなたがフォローしている質問と回答は、サービスを終了するためにサービスの完了ステータスを使用していません（前のタスクである可能性があります） 現時点では、
        if (!fresh && !shown.second.active()) return
        // のこれらの外部サービスはすべてこのタスクに関連しています。クリックして完了します (ステータス フローは変更された場合にのみ送信され、切り替えられた場合には再送信されない場合があります)
        if (!screenSeen && screenOpen.value) screenSeen = true
        _ui.update { it.copy(takeoverStage = takeoverStage.value) }
        onSharedFiles(sharedFiles.value)
        onActivities(activities.value)
        onSteers(steers.value)
        onState(shown.second)
    }

    /**
     * 会話ページが再び表示されると (フロントデスクに戻り、別のページから戻ってくる): 画面上の別の場所に保存されている会話の順番が埋められます。
     * このダイアログには実行中のタスクがあります (一時停止カプセルまたはスケジュールされたタスクから開始) が、ここではフォローされていないか、サービス表示が別のタスクに置き換えられています。それを選択するだけです。
     */
    fun refresh() {
        refreshParallel()
        val id = _ui.value.conversationId ?: return
        syncSaved(id)
        if (id !in activeConversations.value || pendingStart?.isActive == true) return
        // スイッチがオフになっています。タスク中にダイアログを変更しない場合は、タスクが終了するまで待つ必要があります。その時点では、サービス内で実行されているのは画面上のダイアログのタスクだけです。
        follow(id, if (parallel()) unpark(id) else null)
    }

    /** この会話の保存されたラウンドと、各ラウンドのエクスポートされたファイル、および現時点でのメモリとスキルのヒント。 */
    private fun saved(conversationId: String): List<Exchange> =
        runCatching { store.records(conversationId) }.getOrDefault(emptyList()).map { (t, files) -> withSummarizing(withNotes(fromTurn(t).copy(files = files), runNotes.value)) }

    /**
     * ストレージ内のダイアログが変更されました（別の場所で実行した後にラウンドが保存され、特定のラウンドが変更されました）: 画面上で保存されたダイアログを押すと並べ替えられます。ここで追跡したラウンドは、実行番号によって識別されます。
     * (数字がない場合は、元の単語を参照してください)、ここにある単語 (ステップと時間付き) を使用し、繰り返さないでください。まだ保存されていないものはフォローされます。あなたがフォローしている質問と回答は最後に残ります。
     */
    private fun syncSaved(conversationId: String) {
        val stored = saved(conversationId)
        _ui.update { ui ->
            if (ui.conversationId != conversationId) return@update ui
            val live = ui.exchanges.getOrNull(liveIndex)
            val tracked = ui.exchanges.filterIndexed { i, ex -> ex.live && i != liveIndex }.toMutableList()
            // 最新のものから順に一致: 同じ元の文が複数回言われた場合、最新のものと一致します。
            val merged = stored.asReversed().map { s ->
                val mine = tracked.lastOrNull { if (s.runId != null) it.runId == s.runId else it.runId == null && it.goal == s.goal }
                    ?: return@map s
                tracked.remove(mine)
                // 停止後の説明は保存されたコピーに記録されます。
                val m = if (mine.status is ExchangeStatus.Cancelled && s.status is ExchangeStatus.Cancelled && s.status.summary != null) mine.copy(status = s.status) else mine
                // 左ページの記録（サイトの離脱と後に閉鎖された理由）は保存されたコピーにのみ残っています。
                val k = if (s.keptPage != null) m.copy(keptPage = s.keptPage, keptPageClosed = s.keptPageClosed) else m
                if (k.goal.isEmpty()) k.copy(goal = s.goal) else k
            }.asReversed()
            val exchanges = merged + tracked + listOfNotNull(live)
            if (exchanges == ui.exchanges) return@update ui
            liveIndex = if (live != null) exchanges.lastIndex else -1
            ui.copy(exchanges = exchanges)
        }
    }

    fun dismissBlocked() = _ui.update { it.copy(blocked = null) }

    /** 停止: タスクは、引き継がれていない割り込みとともに無効化されます (割り込みは次のものとはみなされません)。 */
    fun cancel() {
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].let { ex ->
                ex.copy(steers = ex.steers.map { if (it.state.unconsumed) it.copy(state = SteerState.Cancelled) else it })
            }
            ui.copy(exchanges = exchanges)
        }
        val pending = pendingStart
        if (pending == null || !pending.isActive) {
            // この会話にサービス内のタスク (実行中またはキューに入っている) がある場合は、それを停止してください。数字のない停止は、サービス表示でタスクとしてカウントされます。
            // このタスクが他の場所で開始されたタスクの後ろでまだキューに入れられている場合、そのタスクは停止されます。
            val id = _ui.value.conversationId
            if (id != null && id in activeConversations.value) actions.cancelIn(id) else act({ actions.cancel() }) { actions.cancelIn(it) }
            return
        }
        // タスクはまだサービスに引き渡されていません: 今回はキャンセルします。サービス内で完了している最後のタスクには触れません。
        pending.cancel()
        pendingStart = null
        held.clear()
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(status = ExchangeStatus.Cancelled())
            ui.copy(exchanges = exchanges, pip = PipUi(), composer = ComposerMode.Idle)
        }
        liveIndex = -1
    }
    fun resume() = act({ actions.resume() }) { actions.resumeIn(it) }
    fun takeOver() = act({ actions.takeOver() }) { actions.takeOverIn(it) }
    fun handBack() = act({ actions.handBack() }) { actions.handBackIn(it) }
    fun finishedMyself() = act({ actions.finishedMyself() }) { actions.finishedMyselfIn(it) }
    fun skip() = act({ actions.skip() }) { actions.skipIn(it) }
    fun answer(text: String) = act({ actions.answer(text) }) { actions.answerIn(it, text) }
    fun approve(key: Int) = act({ actions.approve(key) }) { actions.approveIn(it, key) }
    fun decline(key: Int) = act({ actions.decline(key) }) { actions.declineIn(it, key) }

    /**
     * アプリケーションのボタン: スイッチがオフになると、サービス ディスプレイのタスク (M1) に作用します。同時に、ボタンが押された瞬間にダイアログを画面上に表示し、そのタスクのみを実行します。
     * タスクがない場合、この会話は投稿されません。
     */
    private inline fun act(serial: () -> Unit, scoped: (String) -> Unit) {
        if (!parallel()) return serial()
        val id = _ui.value.conversationId ?: return
        if (id in activeConversations.value) scoped(id)
    }

    fun expandPip() = _ui.update { if (it.pip.visible) it.copy(pip = it.pip.copy(expanded = true)) else it }
    /** が対処してほしい場合は、「ピクチャー・イン・ピクチャー: 片付けて」をクリックし、インターフェースから対処したいカードまでスクロールします。 */
    fun attendPip() = collapsePip()
    fun collapsePip() = _ui.update { it.copy(pip = it.pip.copy(expanded = false)) }

    /** ユーザーがこのタスクの小さな画面を閉じます。右下隅の小さなボタンだけが残り、タスクが終了する前にタスクは表示されなくなります。 */
    fun closePip() {
        if (liveIndex < 0) return
        pipClosed = true
        _ui.update { it.copy(pip = gated(it.pip)) }
    }

    /** 小さなボタンをクリックします: このタスクの小さな画面を再度開きます。 */
    fun reopenPip() {
        pipClosed = false
        _ui.update { it.copy(pip = gated(it.pip)) }
    }

    /** 浮层的一次性「恢复声音」:只发真实音频动作,不在界面伪造一个静音开关状态。 */
    fun unmutePip() {
        val id = _ui.value.conversationId ?: return
        actions.unmuteIn(id)
    }

    /** は特定の会話をカットします。タスクは実行中にカットされません。 */
    fun openConversation(id: String) {
        refreshParallel()
        if (parallel()) return switchTo(id)
        if (busy()) return
        val past = saved(id)
        liveIndex = -1
        _ui.update { ChatUi(conversationId = id, exchanges = past, takeoverStage = it.takeoverStage, attachEnabled = it.attachEnabled) }
        markCurrent(CurrentChat.Open(id))
        // この会話のタスクは他の場所で開始されました (中断されたカプセル、時限タスク): 開いて接続してください
        if (id in activeConversations.value) follow(id)
    }

    /**
     * は、ユーザーが現在どの会話に参加しているかをフローティング カプセルに伝えます ([CurrentChat])。カプセルの無料カードは、最後の会話ファイルを書いた人によって推測するのではなく、それに続きます。
     * は書き込むことができず、インターフェイスには影響しません。
     */
    private fun markCurrent(current: CurrentChat) {
        runCatching { store.setCurrent(current) }
    }

    fun newConversation() {
        refreshParallel()
        if (parallel()) {
            // 他のタスクがまだ実行されている可能性があります。最初に新しい会話に番号を付けて、そのステータスがこの会話とみなされないようにします。
            lingerJob?.cancel()
            parkLive()
            liveIndex = -1
            _ui.update { ChatUi(conversationId = store.newConversation(), takeoverStage = Takeover.Stage.None, attachEnabled = it.attachEnabled) }
            // はまだ話していません。カプセルには新しい対話カードがあります (番号は発表後に決定されます。開始を参照)
            markCurrent(CurrentChat.Fresh)
            return
        }
        if (busy()) return
        liveIndex = -1
        _ui.update { ChatUi(takeoverStage = it.takeoverStage, attachEnabled = it.attachEnabled) }
        markCurrent(CurrentChat.Fresh)
    }

    /** 画面上のこのダイアログには、現在実行中またはキューに入れられているタスクがありますか? */
    fun currentRunning(): Boolean = _ui.value.conversationId?.let { it in activeConversations.value } == true

    /** 現時点では、画面上のダイアログは密接にリンクされています。ダイアログはまだ保存されていません。実行中またはキューにあるタスクがあり、削除されているか削除中です。入力ボックスは使用中です。 */
    private fun branchBlocked(): Boolean {
        val id = _ui.value.conversationId ?: return true
        return id in activeConversations.value || id in deleted.value || id in deleting || busy()
    }

    /** 「ここから分岐」の場合、メッセージ [key] を長押ししてラウンドに戻ります。メニューもそれに応じて表示され、[branchFrom] も同じ判断を使用します。 */
    fun branchTurn(key: String): Int? = branchableTurn(_ui.value.exchanges, key, branchBlocked())

    /** メッセージ長押し [key] 「ここからもう一度聞く」でターンに戻ることができます。それに応じてメニューが表示され、[branchBefore] も同様の判断を行います。 */
    fun retryTurn(key: String): Int? = retryableTurn(_ui.value.exchanges, key, branchBlocked())

    /** 最後のブランチ コピー ワークスペースのプロンプトをクリアします。 */
    fun clearBranchCopy() {
        _branchCopy.value = null
    }

    /**
     * 現在の会話の [throughTurn] ラウンド (このラウンドを含む 0 から) から分岐します。ラウンドとタイトル/Web ページのソースをコピーし、ワークスペース inbox/ をコピーして、成功後に新しい会話に切り替えます。
     * は新しい会話番号を返します。 [canBranchAt] は、確立されていない場合 (実行中またはキューに入れられているタスクがある場合、削除中のタスクがある場合、入力ボックスがビジー状態である場合、ラウンドが範囲外である場合、ラウンドがまだ終了していない場合)、またはコピーが失敗した場合には null になります。
     * のワークスペースコピーの結果(上限超え等)は[branchCopy]に格納され、失敗とはみなされません。
     */
    suspend fun branchFrom(throughTurn: Int): String? =
        branchInto(throughTurn, afterTurn = true, allowed = ::canBranchAt)

    /**
     * 現在の会話の [turn] ユーザー メッセージからの「再質問」: このラウンドの前のラウンドをコピーし、新しい会話番号と事前入力されるテキストを返します。自動的には送信しません。
     * 元のメッセージの添付ファイル/画面は入力ボックスに取り込まれません。 [BranchRetry.needsReattach] を使用して、インターフェイスに再度追加するように指示します。
     * [canRetryAt] は確立されていないか ([branchFrom] と同じですが、このラウンドのユーザーに対しては空です)、コピーが失敗した場合は null になります。
     */
    suspend fun branchBefore(turn: Int): BranchRetry? {
        val raw = _ui.value.exchanges.getOrNull(turn)?.goal
        val targetId = branchInto(turn, afterTurn = false, allowed = ::canRetryAt) ?: return null
        return BranchRetry(targetId, retryText(raw.orEmpty()), needsReattach(raw.orEmpty()))
    }

    /**
     * [branchFrom] と [branchBefore] の共有: [allowed] は、確立時に新しいダイアログを作成し、それを [turn] ラウンドにコピーします ([afterTurn] の場合、このラウンドが含まれます。それ以外の場合はその前のみ)。
     * タイトルにサフィックスを追加し、ブランチ ソースを記述してから、ワークスペース inbox/ をコピーして、新しい会話に切り替えます。新しい会話番号を返します。許可されない場合、またはコピーが失敗した場合は null になり、切り取られません。
     */
    private suspend fun branchInto(turn: Int, afterTurn: Boolean, allowed: (List<Exchange>, Int, Boolean) -> Boolean): String? {
        refreshParallel()
        _branchCopy.value = null
        val ui = _ui.value
        val sourceId = ui.conversationId ?: return null
        if (!allowed(ui.exchanges, turn, branchBlocked())) return null
        val targetId = store.newConversation()
        val base = withContext(io) { store.title(sourceId) }?.takeIf { it.isNotBlank() }
            ?: ChatCopy.title(ui.exchanges).ifBlank { tr(R.string.chat_new) }
        val origin = BranchOrigin(sourceId, turn, afterTurn, at = clock())
        val created = withContext(io) {
            if (afterTurn) store.branch(sourceId, targetId, turn, branchTitle(base), origin)
            else store.branchBefore(sourceId, targetId, turn, branchTitle(base), origin)
        }
        if (!created) return null
        _branchCopy.value = try { branchWorkspace?.copyInbox(sourceId, targetId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        openConversation(targetId)
        return targetId
    }

    /** [id] を削除するとどうなりますか: サイドバーの確認ポップアップ ウィンドウに、タスクが停止され、スケジュールされたタスクが削除されることが示されます。 */
    fun deleteImpact(id: String): DeleteImpact = deletionImpact(id)

    /**
     * ダイアログを削除します [id] (サイドバーでユーザーが確認): その場でサイドバーから削除します。画面上のダイアログは新しい空のダイアログに置き換えられます。
     * は、バックグラウンドでタスクを停止し、スケジュールされたタスクを削除し、ファイルを削除します (`ConversationDeleter` を参照)。
     */
    fun deleteConversation(id: String) {
        if (!deleting.add(id)) return
        _deleted.update { it + id }
        if (id == _ui.value.conversationId) startOver(id) else unpark(id)
        scope.launch {
            try { removeConversation(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { } finally { deleting -= id }
        }
    }

    /** ユーザーは、サイドバーで会話の名前を [id] に変更します。これを [title] に置き換えます (両端の空白スペースを削除します。空のスペースは変更しないでください)。後から作成したタイトルで再度隠すことはできません。 */
    fun renameConversation(id: String, title: String) {
        if (id in _deleted.value) return
        runCatching { store.rename(id, title) }
    }

    /** サイドバーで会話 [id] を固定または固定解除します。 */
    fun setPinned(id: String, pinned: Boolean) {
        if (id in _deleted.value) return
        runCatching { store.setPinned(id, pinned, clock()) }
    }

    /** 画面上のダイアログが削除されました。タスクに関連しなくなり (まだサービスに渡されていないものは直接無効になります)、新しい空のダイアログに置き換えられます。 */
    private fun startOver(id: String) {
        lingerJob?.cancel()
        pendingStart?.cancel()
        pendingStart = null
        held.clear()
        unpark(id)
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        val fresh = if (parallel()) store.newConversation() else null
        _ui.update { ChatUi(conversationId = fresh, takeoverStage = Takeover.Stage.None, attachEnabled = it.attachEnabled) }
        // 新しい空のダイアログが画面上で置き換えられました: カプセルは削除されたダイアログを継続しなくなりました。
        markCurrent(CurrentChat.Fresh)
    }

    fun busy() = _ui.value.composer != ComposerMode.Idle

    /** あなたが操作しているアプリまたはウェブサイト (オブジェクトを持つ最新のもの)。上部の列のサブタイトル「Operating...」を使用してください。そうでない場合は null。 */
    fun livePlace(ex: Exchange): String? =
        ex.activities.lastOrNull { !it.target.isNullOrBlank() }?.target?.let { where(it, appLabel) }

    /** 会話が実行されているタスクの元の単語。これを、サイドバーの「進行中」で会話リストに保存されていないもののタイトルとして使用します。 */
    fun runningGoal(conversationId: String): String? = runGoal(conversationId)

    /** 別の会話に切り替えられますか: 並列操作中はいつでも変更できます。変更しない場合は、この会話のタスクが終了するまで待つ必要があります。 */
    fun canSwitch() = parallel() || !busy()

    private fun onState(s: TaskState) {
        if (liveIndex < 0) {
            // タスクはここからは開始されません (たとえば、インターフェイスが再構築される前に実行されていました): その後、表示されますが、ユーザーの文はありません。
            if (!s.active()) return
            screenSeen = screenOpen.value
            pipClosed = false
            _ui.update {
                liveIndex = it.exchanges.size
                // 元の単語とファイルが会話に従って取得されます: このステータスは、画面上のこの会話のタスクに属します
                val goal = it.conversationId?.let(runGoal).orEmpty()
                val files = if (ownTask()) sharedFiles.value else emptyList()
                val ex = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, files = files, startedAt = clock())
                it.copy(exchanges = it.exchanges + ex)
            }
        }
        _ui.update { ui ->
            val old = ui.exchanges[liveIndex]
            val ex = reduce(withGoal(old, ui.conversationId), s, appLabel, toolLabels::label).let {
                if (it.status.active() || !old.status.active()) {
                    // 実行中に、このダイアログのタスク番号を終了すると、その場で「プロセスの実行」が利用可能になります。終了後、次へ進みます。
                    val live = if (it.status.active() && it.runId == null) ui.conversationId?.let(liveRunId) else null
                    if (live != null) it.copy(runId = live) else it
                }
                // まだタスクに引き渡されていない終了時の中断: サービスはそれをこの会話の次のものとして扱います
                else withSummarizing(withNotes(it.copy(runId = endedRunFor(ui.conversationId), elapsedMs = elapsedOf(old),
                    steers = it.steers.map { n -> if (n.state.unconsumed) n.copy(state = SteerState.Next) else n }), runNotes.value))
            }
            val exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = ex }
            ui.copy(
                exchanges = exchanges,
                pip = gated(pipFor(ui.pip, old.status, ex)),
                composer = when {
                    ex.status is ExchangeStatus.NeedsInput -> ComposerMode.Answer
                    ex.status.active() -> ComposerMode.Busy
                    else -> ComposerMode.Idle
                },
            )
        }
        if (!s.active() && s != TaskState.Idle) {
            liveIndex = -1
            // このラウンドは会話に保存され、画面に追加された可能性があります: ラン番号を押して 1 つにマージしてください
            _ui.value.conversationId?.let(::syncSaved)
            if (s is TaskState.Succeeded) {
                lingerJob = scope.launch {
                    delay(DONE_LINGER_MS)
                    _ui.update { it.copy(pip = it.pip.copy(visible = false, expanded = false)) }
                }
            }
        }
    }

    /** あなたがフォローしている質問と回答には元の単語がありません（他の場所で開始されたタスクを取り上げるときにまだ登録されていません）: 画面上のこのダイアログのタスクを押して追加します。 */
    private fun fillGoal() = _ui.update { ui ->
        val ex = ui.exchanges.getOrNull(liveIndex) ?: return@update ui
        val filled = withGoal(ex, ui.conversationId)
        if (filled === ex) ui else ui.copy(exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = filled })
    }

    private fun withGoal(ex: Exchange, conversationId: String?): Exchange {
        if (ex.goal.isNotEmpty() || !ex.status.active()) return ex
        return conversationId?.let(runGoal)?.takeIf(String::isNotEmpty)?.let { ex.copy(goal = it) } ?: ex
    }

    private fun elapsedOf(ex: Exchange): Long? = ex.startedAt.takeIf { it > 0 }?.let { (clock() - it).coerceAtLeast(0) }

    private fun endedRunFor(conversationId: String?): String? {
        // ダイアログ番号がない場合 (インターフェイスが再構築される前に実行されていたタスクに接続するため)、表示されている番号のみが使用できます。
        val byConversation = endedRunIds ?: return endedRunId.value
        return if (conversationId == null) endedRunId.value else byConversation.value[conversationId]
    }

    private fun pipFor(old: PipUi, before: ExchangeStatus, ex: Exchange): PipUi {
        val st = ex.status
        return when (st) {
            is ExchangeStatus.Done -> PipUi(true, PipMode.Done, false)
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> PipUi()
            else -> {
                val needs = st.needsUser()
                // は、引き継ぎが開始された場合にのみ自動的に展開されます (質問をしたり、入力ボックスとカード ボタンを目の前に置いたままにするために一時停止したりする)。乗っ取りが終わると崩壊するだろう。
                // ユーザーが同じテイクオーバーで閉じた後にポップアップが表示されなくなりました
                val takeover = st is ExchangeStatus.NeedsTakeover
                val wasTakeover = before is ExchangeStatus.NeedsTakeover
                val expanded = when {
                    takeover && !wasTakeover -> true
                    !takeover && wasTakeover -> false
                    else -> old.expanded
                }
                PipUi(true, if (needs) PipMode.NeedsYou else PipMode.Running, expanded)
            }
        }
    }

    companion object {
        const val DONE_LINGER_MS = 3_000L


        private fun TaskState.active() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.active() = when (this) {
            is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.needsUser() =
            this is ExchangeStatus.NeedsTakeover || this is ExchangeStatus.NeedsInput || this is ExchangeStatus.Paused ||
                this is ExchangeStatus.NeedsConfirmation

        internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
            state(ex, s, appLabel).let {
                it.copy(steps = rows(it, toolLabel, appLabel), stepActions = actionsOf(it.activities, appLabel, toolLabel))
            }

        /** ステップ番号ごとに整理されたアクションの詳細: ステップをクリックするとリストされます。 */
        private fun actionsOf(activities: List<RunEvent.Activity>, appLabel: (String) -> String, toolLabel: (String) -> String) =
            activities.groupBy { it.step }.mapValues { (_, list) ->
                // ツールのインターフェイス説明 (実行中のプロセス ページと同じ) を使用し、ツール名をユーザーに公開しないでください。
                list.map { a -> StepAction(toolLabel(a.label), a.target?.let { where(it, appLabel) }, a.effect, a.failed) }
            }

        private fun state(ex: Exchange, s: TaskState, appLabel: (String) -> String): Exchange = when (s) {
            TaskState.Idle -> ex
            is TaskState.Running -> {
                val progress = s.progress?.let(StatusCopy::progress)
                // 「何をしているのですか？」最初にモデル コールバックを使用して作成した場合、それを再度使用するつもりはありません。
                val said = (s.narration ?: s.reason)?.takeIf(String::isNotBlank)
                val waiting = progress == null && s.tool == null && said == null
                ex.copy(
                    status = ExchangeStatus.Working, waitingForModel = waiting, draft = s.preview,
                    line = if (waiting) "" else progress ?: said.orEmpty(),
                    notes = if (s.step > 0 && said != null) ex.notes + (s.step to said) else ex.notes,
                )
            }
            // およびその他の機能の前提条件: 画面がロックされても自動的に続行します。開始するキュー。 [続行] をクリックできる他の人は続行されます。
            is TaskState.Held -> ex.copy(
                status = when {
                    s.reason == TaskGate.LOCKED -> ExchangeStatus.WaitingUnlock
                    s.reason.queued -> ExchangeStatus.Queued(s.reason.why)
                    else -> ExchangeStatus.Paused(s.reason.why, s.reason.resumable)
                },
                waitingForModel = false, draft = null,
            )
            is TaskState.WaitingTakeover ->
                ex.copy(status = ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address), waitingForModel = false, draft = null)
            is TaskState.WaitingInput -> ex.copy(status = ExchangeStatus.NeedsInput(s.prompt, s.options), waitingForModel = false, draft = null)
            is TaskState.WaitingConfirmation -> ex.copy(
                waitingForModel = false,
                draft = null,
                status = ExchangeStatus.NeedsConfirmation(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
                    ConfirmationNotifier.requestKey(s),
                ),
            )
            // 答えはそのまま表示されます (モデルによって記述された基礎セクションはなくなりました)。ソースは実行記録に書き留められたコードによって示されます。
            is TaskState.Succeeded -> ex.copy(status = ExchangeStatus.Done(s.answer.trim(), null, s.verified, s.note, s.unconfirmed, s.reviewed),
                waitingForModel = false, draft = null, line = "")
            is TaskState.Failed -> ex.copy(status = ExchangeStatus.Failed(s.reason), waitingForModel = false, draft = null, line = "")
            TaskState.Cancelled -> ex.copy(status = ExchangeStatus.Cancelled(), waitingForModel = false, draft = null, line = "")
        }

        /**
         * ステップ リスト: 実際に行われたことごとに 1 行。リーディング クラスは、どこを読んでいるのかがわかっている場合にのみ表示され、同じ場所が連続して 1 行に読み上げられます (「WeChat チャット リストを見る」)。
         * アクションは「クリック・WeChat」と書きます。モデルがそのステップで単語を記述する場合は、その単語を使用します。失敗した場合は、「失敗」と表示されるだけで、エラー コードは表示されません。
         * の最後の行のステータスは、この質問と回答に従います: 実行中、待機中、終了。
         */
        internal fun rows(ex: Exchange, toolLabel: (String) -> String, appLabel: (String) -> String): List<StepRow> {
            val out = mutableListOf<StepRow>()
            val said = mutableSetOf<Int>()
            for (a in ex.activities) {
                val where = a.target?.let { where(it, appLabel) }
                if (a.kind == "observe") {
                    // 読み取りは成功しましたが、どこで読み取られたかわかりません。リストされていません。読まれなかったものもリストに表示されます。
                    if (where == null && !a.failed) continue
                    val text = if (where == null) toolLabel(a.label)
                        else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
                    val subtitle = if (a.failed) tr(R.string.step_failed) else ""
                    val last = out.lastOrNull()
                    if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
                } else {
                    val reason = if (said.add(a.step)) ex.notes[a.step].orEmpty() else ""
                    val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
                    out += StepRow(a.step, title, if (a.failed) tr(R.string.step_failed) else "", StepState.Ok, reason)
                }
            }
            if (out.isEmpty()) return out
            val last = when (ex.status) {
                ExchangeStatus.Working -> StepState.Run
                is ExchangeStatus.Done -> StepState.Ok
                is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> StepState.Dim
                else -> StepState.Wait
            }
            out[out.lastIndex] = out.last().copy(state = last)
            return out
        }

        /** ユーザーに表示されるアクティビティ オブジェクトの説明: パッケージ名がアプリ名に置き換えられ、ドメイン名のみが「Web サイトのドメイン名」に残ります。 */
        private fun where(target: String, appLabel: (String) -> String): String? {
            if (target.isBlank()) return null
            return recordedSiteHost(target) ?: when {
                com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
                else -> target
            }
        }

        private fun fromTurn(t: Turn): Exchange {
            val status = when (t.status) {
                "succeeded" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e) }
                "unverified" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e, verified = false, note = t.note, unconfirmed = t.unconfirmed) }
                "cancelled" -> ExchangeStatus.Cancelled(Conversation.stopSummary(t))
                else -> ExchangeStatus.Failed(t.outcome)
            }
            return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId,
                memoryNotes = t.memoryNotes, usedMemory = t.usedMemory, skillNotes = t.skillNotes,
                keptPage = t.keptPage, keptPageClosed = t.keptPageClosed,
                steers = t.steers.mapIndexed { i, text ->
                    SteerNote("turn-$i", text, SteerState.Joined, t.interruptedReplies.getOrNull(i)?.takeIf(String::isNotEmpty),
                        t.finishedReplies.getOrNull(i)?.takeIf(String::isNotEmpty))
                })
        }


        /** では、メッセージの最後に添付ファイルごとに 1 行が表示されます: "Attachment:/workspace/inbox/<name>(<size>)"; [files] がワークスペース (パス、バイト数) にコピーされます。 */
        internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String =
            AttachmentLines.compose(text, files.map { (p, n) -> p to sizeLabel(n) })

        fun sizeLabel(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * 結果カードの回答と証拠テキストはここからのみ引用されています。新しい回答には根拠セクションがありません（ソースはコードによって証拠台帳に見つかります。[evidenceSource]を参照してください）。
 * 古い実行レコードと会話ファイルのモデル自体の最後に書かれている「Based:」セクションはまだ削除されています。 [evidence] を与えたらすぐに使用してください。
 */
fun answerParts(answer: String, evidence: String? = null): Pair<String, String?> =
    evidence?.trim()?.takeIf { it.isNotEmpty() }?.let { answer.trim() to it } ?: splitEvidence(answer)

/**
 * 答えのソース: アプリまたは Web サイト ([place]、不明な場合は null)、関連する画面番号、およびステップバイステップの説明 ([items]、「ステップ 3 · 12306 · クエリのトレーニング」)。
 * 実行中のレコードから取得します。コードによって記録されたソース (ソース イベント) がある場合は、それを使用します。古いレコードの場合は、画面が保存された最後のステップを使用します。
 */
data class EvidenceSource(val place: String?, val screenshots: List<Int>, val items: List<String> = emptyList())

/** [EvidenceSource] を参照。 [appLabel] パッケージ名をアプリ名に置き換えると、Web サイトと URL にドメイン名が表示されます。 */
fun evidenceSource(events: List<RunEvent>, appLabel: (String) -> String = { it }): EvidenceSource {
    events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { list ->
        fun where(p: String?) = p?.let { placeLabel(it, appLabel) }
        val items = list.map { s -> listOfNotNull(tr(R.string.step_number, s.step), where(s.place), s.title).joinToString(" · ") }
        return EvidenceSource(list.asReversed().firstNotNullOfOrNull { where(it.place) }, list.flatMap { it.mediaIds }.distinct(), items)
    }
    val shots = events.filterIsInstance<RunEvent.Media>()
    val lastStep = shots.maxOfOrNull { it.step } ?: return EvidenceSource(null, emptyList())
    val final = shots.filter { it.step == lastStep }
    val place = shots.lastOrNull { it.place != null }?.place?.let { recordedSiteHost(it) ?: appLabel(it) }
    return EvidenceSource(place, final.map { it.mediaId }.distinct())
}

/** 現在と旧バージョンの実行記録にあるサイトの場所を読み取る。 */
private val SITE_PREFIXES = listOf("ウェブサイト ", "网站 ")

internal fun recordedSiteHost(place: String): String? =
    SITE_PREFIXES.firstOrNull { place.startsWith(it) }?.let { place.removePrefix(it) }

/** ソースの「Webサイトドメイン名」とURL表示ドメイン名は、パッケージ名がアプリ名に置き換えられ、その他（コマンド、ファイル）は変更されません。 */
private fun placeLabel(place: String, appLabel: (String) -> String): String = recordedSiteHost(place) ?: when {
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}

/** 「ソース」行: 「ソース · 12306 · 2 スクリーンショット」;場所やスクリーンショットがない場合は、その段落を省略してください。 */
fun sourceLabel(source: EvidenceSource?): String = listOfNotNull(
    tr(R.string.source_label), source?.place, source?.screenshots?.size?.takeIf { it > 0 }?.let { trPlural(R.plurals.screenshots, it) },
).joinToString(" · ")

/**
 * 回答本文と末尾の根拠ブロックを分ける。「根拠」「出典」と、旧記録の中国語見出しを認識する。
 * 行頭または文末の句読点に続く見出し以降を根拠とし、本文の句読点や書式は保持する。
 * 見出しがない場合や本文・根拠のどちらかが空の場合は、回答全体を本文として残す。
 */
fun splitEvidence(answer: String): Pair<String, String?> {
    val m = EVIDENCE.find(answer) ?: return answer.trim() to null
    val evidence = answer.substring(m.range.last + 1).trim()
    val main = answer.substring(0, m.range.first + m.groupValues[1].length).trim()
    if (evidence.isEmpty() || main.isEmpty()) return answer.trim() to null
    return main to evidence
}

// 段落の先頭または文末の句読点の後に、マークダウン **、#、- およびその他の接頭辞を使用できます
private val EVIDENCE = Regex("""(^|\n|[。.!！?？]\s*)[\s*#>\-]*\**(?:根拠|出典|依据|证据|来源)\**\s*[:：]\**""")

/**
 * ツールの表示文言は変更され得る宣言ラベルではなく、動詞と能力からリソースを選ぶ。
 * 旧記録の名前は [legacy] で現在の宣言に解決し、未対応の宣言と不明な名前は従来の表示に戻す。
 */
class ToolLabels(private val tools: ToolIndex, private val legacy: LegacyNames = legacyNames()) {
    fun label(tool: String): String {
        val declared = tools[tool] ?: legacy.resolve(tool) { tools[it] != null }?.let { tools[it] } ?: return tool
        return resourceFor(declared)?.let { tr(it) } ?: declared.label ?: declared.name
    }

    companion object {
        internal fun resourceFor(tool: ToolInfo): Int? =
            CAPABILITY_TOOL_LABELS[tool.capability to tool.verb] ?: TOOL_LABELS[tool.verb]

        /** 同じ待機動詞でも、スマートフォンと Web では説明が異なる。 */
        private val CAPABILITY_TOOL_LABELS: Map<Pair<String?, Verb>, Int> = mapOf(
            (PHONE to Verb.WaitFor) to R.string.tool_wait_condition,
            (WEB to Verb.WaitFor) to R.string.tool_wait_page,
        )

        private val TOOL_LABELS: Map<Verb, Int> = mapOf(
            Verb.ListApps to R.string.tool_list_apps,
            Verb.LaunchApp to R.string.tool_open_app,
            Verb.ShareText to R.string.tool_share_text,
            Verb.SetAlarm to R.string.tool_set_alarm,
            Verb.SetTimer to R.string.tool_set_timer,
            Verb.ShowAlarms to R.string.tool_show_alarms,
            Verb.AddCalendarEvent to R.string.tool_add_calendar_event,
            Verb.SetWheel to R.string.tool_set_wheel,
            Verb.PhoneFind to R.string.tool_scroll_find,
            Verb.PhoneEvent to R.string.tool_wait_event,
            Verb.PhoneExtract to R.string.tool_extract,
            Verb.PhoneDialog to R.string.tool_check_dialog,
            Verb.PhoneGesture to R.string.tool_gesture,
            Verb.PhoneCommand to R.string.tool_shell,
            Verb.RootCommand to R.string.tool_root,
            Verb.Query to R.string.tool_find_element,
            Verb.Collect to R.string.tool_collect,
            Verb.PhoneRun to R.string.tool_phone_run,
            Verb.RunCommand to R.string.tool_process_files,
            Verb.RunBundledScript to R.string.tool_run_script,
            Verb.ReadFile to R.string.tool_read_file,
            Verb.WriteFile to R.string.tool_write_file,
            Verb.SearchFiles to R.string.tool_search_files,
            Verb.ShareFile to R.string.tool_export_file,
            Verb.Navigate to R.string.tool_open_page,
            Verb.Tabs to R.string.tool_switch_tab,
            Verb.SiteMode to R.string.tool_switch_version,
            Verb.KeepOpen to R.string.tool_keep_page,
            Verb.AppAfterTask to R.string.tool_app_after_task,
            Verb.ReadPage to R.string.tool_read_page,
            Verb.Downloads to R.string.tool_downloads,
            Verb.Capture to R.string.tool_network,
            Verb.EvaluateJs to R.string.tool_read_script,
            Verb.SiteCommand to R.string.tool_site_command,
            Verb.Upload to R.string.tool_upload,
            Verb.HandleObstacle to R.string.tool_obstacle,
            Verb.KnowledgeSearch to R.string.tool_find_knowledge,
            Verb.SkillSearch to R.string.tool_find_skill,
            Verb.KnowledgeRead to R.string.tool_read_knowledge,
            Verb.SkillRead to R.string.tool_read_skill,
            Verb.SkillSave to R.string.tool_save_skill,
            Verb.SettingsList to R.string.tool_list_settings,
            Verb.SettingsSet to R.string.tool_set_setting,
            Verb.EndNotDone to R.string.tool_explain_failure,
            Verb.AskUser to R.string.tool_ask_user,
            Verb.HandToUser to R.string.tool_takeover,
            Verb.RequestConfirmation to R.string.tool_confirm,
            Verb.Wait to R.string.tool_wait,
            Verb.Diagnose to R.string.tool_diagnose,
            Verb.Back to R.string.tool_back,
            Verb.PressKey to R.string.tool_press_key,
            Verb.Observe to R.string.tool_observe,
            Verb.ListElements to R.string.tool_list_elements,
            Verb.Screenshot to R.string.tool_screenshot,
            Verb.Scroll to R.string.tool_scroll,
            Verb.Swipe to R.string.tool_swipe,
            Verb.Tap to R.string.tool_tap,
            Verb.TypeText to R.string.tool_type,
            Verb.ClearText to R.string.tool_clear,
        )
    }
}
