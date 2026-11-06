package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.visual.FrameGrid
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DOUBLE_TAP_UNSUPPORTED = "double_tap_unsupported"

/** 2 回のクリック間の間隔: ダブルクリックとして認識されるのに十分な短さである必要があります (Android のダブルクリック時間制限は 300 ミリ秒です)。 */
const val DOUBLE_TAP_GAP_MS = 120L

/** スライド ジェスチャのデフォルトの継続時間。 */
const val DEFAULT_SWIPE_MS = 400L

/** とタップの間の変換は逆数です: n =round(p*999/(size-1))、0 ～ 999 の間に挟まれます。 */
fun normalize(p: Int, size: Int): Int {
    if (size <= 1) return 0
    return min(999, max(0, (p * 999.0 / (size - 1)).roundToInt()))
}

/**
 * 画面上の要素。 ref は「@e3」の形式です。 center はピクセルです (ポート内で内部的に使用され、モデルに渡される前に 0 ～ 999 に変更されます)。境界は [l,t,r,b] で、0 ～ 999 に正規化されています。
 * [hint]: 入力ボックスに表示されるのは、入力された内容ではなく、プロンプト/プリセットの単語 (名前です) です。
 * [focused]: 入力フォーカスがあります (キャリッジ リターンとスペースがそれに作用します)。
 * [secret]: パス​​ワードボックス。
 * [placeholder]: 入力ボックス自体のプロンプト (入力後も表示されており、現在表示されている [name] とは何の関係もありません)。そうでない場合は null です。
 * [searchField]: コントロールは、独自のセマンティクスに従った検索ボックスです (Web ページの type=search、role=searchbox、および Enter キーは検索としてマークされます。Android の検索ボックス コントロール)。
 * [states]: コントロールは現時点では true 状態です ([UiStates] の名前: 選択、フック、展開、押下、使用不可)。アクション効果を判断する際は、スイッチを入れることで確認できます。
 */
data class UiElement(
    val ref: String,
    val role: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: List<Int>,
    val resourceId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val hint: Boolean = false,
    val focused: Boolean = false,
    /** Web ページ上でページがこのコントロールに宣言したショートカットキー（aria-keyshortcuts、accesskey を Alt+キーに読み替える）。書き方は web_interact の key と同じ。 */
    val keyShortcuts: List<String> = emptyList(),
    val secret: Boolean = false,
    val placeholder: String? = null,
    val searchField: Boolean = false,
    val states: Set<String> = emptySet(),
) {
    /** 名前がなく操作もできないノードは、モデルにとって情報量がない。 */
    val meaningful: Boolean get() = name.isNotBlank() || clickable || editable || scrollable
}

/** コントロールの状態の名前で、スマホと Web で共有する。 */
object UiStates {
    const val CHECKED = "checked"
    const val SELECTED = "selected"
    const val EXPANDED = "expanded"
    const val PRESSED = "pressed"
    const val DISABLED = "disabled"
    val ALL = setOf(CHECKED, SELECTED, EXPANDED, PRESSED, DISABLED)
}

/**
 * [changedDuringRead]: この一覧を読む前後で UI が一致していない（ページが自分で動いている）ため、一覧が画面と合っていない可能性がある。
 * [protected]: 明確な根拠により、システムの撮影制限があると判定した状態。
 * [captureError]: 撮影制限とは断定できないが、現在の画像を使えない場合のエラーコード。
 * 読み取れた要素は利用できるが、黒い画像や古い画像は視覚モデルへ渡さない。
 * [hasWindow]: 画面上にアプリ ウィンドウがあります (ルート ノードが読み取り可能かどうかは関係ありません)。 false で要素がない場合、画面は本当に空です。
 * [web]: Web ページを読み取るときのページ情報。携帯電話で画面を読むときは空です。
 * [unreadableApp]: ウィンドウの内容 (セキュリティ ウィンドウ、起動画面、他の場所に表示される) を読み取ることはできませんが、ポートがフォーカス ウィンドウまたは最上位のタスクを押して、どのアプリがフォアグラウンドにあるかを知ると、
 * そのパッケージ名。読み取り可能または不明の場合は null。この場合、「要素がない」ということは「アプリが開いていない」という意味ではありません。
 */
data class ScreenSnapshot(
    val snapshotId: String,
    val packageName: String?,
    val elements: List<UiElement>,
    val width: Int,
    val height: Int,
    val changedDuringRead: Boolean = false,
    val protected: Boolean = false,
    val hasWindow: Boolean = true,
    val web: WebInfo? = null,
    val unreadableApp: String? = null,
    /** 携帯電話のアクセシビリティ ウィンドウの完全な表示レベル。 Web ページとテスト ポートは提供されない場合があります。 */
    val windows: List<UiWindowTree> = emptyList(),
    /** 前のアクションの後にユーザーのホーム画面が開かれ、この画面を読み取る直前に戻ったアプリ ([OpenedElsewhere] を参照)。そうでない場合は null。 */
    val opened: OpenedElsewhere? = null,
    /** 画面不可用但不是已证实的防截屏;见上方 [captureError] 说明。 */
    val captureError: String? = null,
)

/**
 * アクションにより、アプリがミカの画面の外でユーザーのホーム画面に開かれます (アプリはミカの画面上の独自のページから開かれず、システムによってホーム画面に配置されます)。
 * [packageName] そのアプリ (システムがどれを開くかを尋ねるとき、ウィンドウが配置されているパッケージについて尋ねます)、[app] その表示名。
 * [pulledBack]: それはミカの画面に移動され、一番上にあります。 false の場合、ユーザーのホーム画面 (ユーザーが使用しているアプリ、またはシステムがユーザーに要求しているアプリ) に残ります。
 * [inProfile]: このアプリの 2 つのコピー (アバター、作品情報) がインストールされている場合、どちらが開かれるか、[MAIN_PROFILE] が元のコピー、[OTHER_PROFILE] がもう 1 つのコピーです。コピーが 1 つだけインストールされている場合、または表示できない場合は、null になります。
 * [picker]: 開くのは、システムが「どれを使用して開くか?」と尋ねるウィンドウです。 (同じアプリが 2 回インストールされている、複数のアプリを開くことができるなど)。決して動くことはありません。ユーザーが自分の画面で選択することができます。
 * は、タスクを選択する前に待機することを意味します。 [app] は、質問したアプリの名前です (認識した場合)。
 * [closed]: ユーザーがその質問をオフにしましたが、何もオンになりませんでした。 [byUser]: 選択したアプリでは、ユーザーが手動で処理する必要があります。自分の画面で処理した後、ユーザーはクリックして続行します。
 * [needsUser]: ホーム画面に表示されるポップアップ ウィンドウは、ユーザーが個人的に処理する必要があります (ログイン確認、アカウント確認、支払い確認など)。クリックできるようにホーム画面に残しておきます。 現時点では、
 */
data class OpenedElsewhere(
    val packageName: String, val app: String?, val pulledBack: Boolean, val inProfile: String? = null, val picker: Boolean = false,
    val closed: Boolean = false, val byUser: Boolean = false, val needsUser: Boolean = false,
) {
    /** はまだユーザーのホーム画面にあります (ユーザーが使用しているか、システムがユーザーに尋ねています)。 */
    val onUserScreen: Boolean get() = !pulledBack && !closed && !byUser

    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        app?.let { put("app", it) }
        put("pulledBack", pulledBack)
        if (onUserScreen) put("onUserScreen", true)
        if (needsUser) put("needsUser", true)
        inProfile?.let { put("inProfile", it) }
        if (picker) put("picker", true)
        if (closed) put("closed", true)
        if (byUser) put("byUser", true)
    }

    companion object {
        const val MAIN_PROFILE = "main"
        const val OTHER_PROFILE = "other"
    }
}

/** 現在のタスクの仮想画面から受信したインターフェイス イベント。通知イベントはデバイス全体から発生する可能性があります。 */
data class ScreenEvent(val type: String, val packageName: String?, val text: String?)

/** ネイティブ OCR は、アクセス可能な要素と同じ 0 ～ 999 の座標を使用した境界を持つテキスト行を現在の画面イメージから読み取ります。 [confidence] は、null でない場合、認識によって与えられる 0 ～ 1 の信頼度です。 */
data class VisibleTextLine(val text: String, val bounds: List<Int>, val confidence: Float? = null)

/** Android シェルとしてコマンドを実行した組み込みヘルパーの結果。 */
data class SystemCommandResult(val exitCode: Int, val output: String)

/** は、入力後に入力ボックスの検証結果を読み取ります。読み戻せない場合 (パスワード ボックス、ノードを読み取れない場合)、それは UNKNOWN です。 */
enum class Verification(val wire: String) { MATCH("match"), MISMATCH("mismatch"), UNKNOWN("unknown") }

sealed interface ActionResult {
    /**
     * アクションが発行されました。残りのフィールドは、後でポートによって検証された事実であり、null/false は検証されていないか、適用されないことを意味します。
     * [changed] アクションの前後でインターフェイスが変化するかどうか。 [appSwitched] フォアグラウンドがターゲット アプリでなくなったときの現在のパッケージ名。
     * [relocated] 番号の有効期限が切れています。元の要素の特性に従って、新しいインターフェイスで取得後に実行されます。 [relocatedTo] は、取得された要素の現在の名前です。
     * [switchedByTarget] 新しいフロント デスクは、ターゲット アプリによって独自のタスク (別のアプリへの共有など) で開かれ、外部干渉によって中断されません。 [verification] はリードバック結果を入力します。
     * [hint] この結果に対するポートの補足説明（検証できない理由など）をそのままモデルに引き継ぎます。
     * [secret] 入力対象はパスワードボックスです（従って走行記録には原文は記録されません）。
     * [settled] Web ページに DevTools がある場合、ネットワークがアイドル状態でアクション後にページが静止している場合は true、上限に達するまで停止しなかった場合は false、残りは null になります。
     * [coveredBy] 開いたアプリがすでに一番上にあるが、別のパッケージのウィンドウがそれを覆っている場合 (権限ポップアップ ウィンドウ、アカウント セレクター)、そのパッケージ。
     * [chooser] アプリを開いた後、システムは最初に「どれを開きますか?」と尋ねます。 （同じアプリを2回インストールしている等）、アプリがフロントに到着していない状態で選択した場合
     * [opened] このアクションにより、ユーザーのホーム画面でアプリが開きます ([OpenedElsewhere] を参照)。
     * [inProfile] フロント デスクに到着したばかりのアプリが 2 つあったとき、どれが開かれたか ([OpenedElsewhere.inProfile] を参照): システムがどちらを開くかを尋ねた後、
     * とターゲット アプリは、別のコピーがリストされている場合、または別のコピーが必要な場合に毎回報告されます。 [profileUser] は、現時点で画面上のコピーに属するユーザー番号です。
     * [requestedUser] は、開くときに要求されたユーザーのコピーです (要求された元のコピーは null)。
     */
    data class Ok(
        val changed: Boolean? = null,
        val appSwitched: String? = null,
        val relocated: Boolean = false,
        val relocatedTo: String? = null,
        val switchedByTarget: Boolean = false,
        val verification: Verification? = null,
        val hint: String? = null,
        val secret: Boolean = false,
        val settled: Boolean? = null,
        val coveredBy: String? = null,
        val chooser: LaunchChoice? = null,
        val opened: OpenedElsewhere? = null,
        val inProfile: String? = null,
        val profileUser: Int? = null,
        val requestedUser: Int? = null,
    ) : ActionResult

    /** 理由が「ref_expired」の場合、参照の有効期限が切れています。 [hint] はポートの補足説明（ターゲットをカバーしている内容など）で、そのままエラー説明に付加されます。 */
    data class Failed(val reason: String, val hint: String? = null) : ActionResult
}

/** アプリを開いたとき、アプリは別のタスクによって使用されています (同じアプリは同時に 1 つの画面でのみ使用できます)。 */
const val APP_IN_USE_BY_OTHER_TASK = "app_in_use_by_other_task"

/** [ScreenPort.awaitAppFree] の結果。 */
enum class AppWait {
    /** がリリースされ、開くことができます。 */
    Free,
    /** は制限時間を過ぎてもまだ占有されています。 */
    TimedOut,
    /** それを占有しているタスクは、このタスクの手にあるアプリを待っています。あなたが待っていれば誰も待つことはできません、待つことはありません。 */
    WouldDeadlock,
    /** このポートは他のタスクと競合しません。待つことは何もありません。 */
    Unsupported,
}

/** エージェントは、Android モジュールによって実装された、携帯電話の画面を観察および操作するための唯一の入り口です。 */
interface ScreenPort {
    val appAccess: com.wangzan101.mika.core.access.AppAccessPolicy get() = com.wangzan101.mika.core.access.AppAccessPolicy.ALLOW

    /** はウィンドウの所有権をチェックするだけで、画面の内容は読み取りません。画面が空の場合に使用できます。ウィンドウはあるがその所有権が不明な場合は拒否されます。 */
    suspend fun checkAppAccess() {}

    suspend fun snapshot(): ScreenSnapshot

    /** は 1 つの画面を読み取りますが、番号は登録しません (モデルの手の参照には影響しません)。不可逆的な決定に使用されます。 */
    suspend fun peek(): ScreenSnapshot = snapshot()
    suspend fun screenshotPng(): ByteArray?
    /** オプションのネイティブ テキスト認識。 null は、現在の画面が判読できないか、ポートに OCR がないことを意味します。 */
    suspend fun readVisibleText(): List<VisibleTextLine>? = null
    suspend fun tapRef(ref: String, snapshotId: String): ActionResult
    suspend fun tapXY(x: Int, y: Int): ActionResult
    /** ダブルクリック (2 回のクリック間隔はシステムによるダブルクリック認識の制限時間内です)。サポートされていないポートは double_tap_unsupported を報告します。 */
    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult
    suspend fun clearText(ref: String?, snapshotId: String?): ActionResult

    /**
     * この時点で入力ボックスに入力します ([x]、[y] はピクセルです)。最初に入力ボックスをクリックしてから、フォーカスのある入力ボックスをクリックして通常どおりに入力します。入力ボックスがツリーに表示されない場合は、クリップボードから貼り付けます。
     * 入力の前後に画面上の単語を確認します。貼り付け後、前後のポイントは point_not_typed に変化していません (そのポイントはおそらく入力ボックスではないか、フォーカスされていません)。
     * デフォルトでは、最初にクリックしてからフォーカスを押して、その点をチェックせずに入力します。
     */
    suspend fun typeAt(x: Int, y: Int, text: String): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) typeText(null, null, text) else it }

    /** はこの時点で入力ボックスをクリアします。方法は [typeAt] と同じです。 */
    suspend fun clearAt(x: Int, y: Int): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) clearText(null, null) else it }
    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult

    /**
     * 滞在中は、何もクリックせずにゆっくりとスクロールします ([fraction] 画面またはビューポートの高さ、右下)。ポインターを移動せずに、Web ページを下にスクロールするのと同じネイティブ入力。
     * 携帯電話は、画面の半分以上を占めるスクロール可能なコンテンツ上の小さなセクションをゆっくりと描画します。スクロールするのが不都合な場合はスクロールしないでください (ポップアップ ボックスがあり、入力メソッドが開いており、入力ボックスにフォーカスがあり、スクロール可能なコンテンツが表示されていない場合)。
     * は false を返します。 true にロールされました。デフォルトではロールしません。
     */
    suspend fun idleScroll(fraction: Double): Boolean = false
    /** マルチポイント ジェスチャ。各パスは時間内に配置された一連のピクセルであり、すべての指が同時に動きます。 */
    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = ActionResult.Failed("unsupported")
    suspend fun back(): ActionResult
    suspend fun pressKey(keyCode: Int): ActionResult
    suspend fun openApp(packageName: String, cold: Boolean = false): ActionResult

    /**
     * は、別のユーザー [userId] にインストールされている [packageName] を開きます (クローンと作業情報については、[appCopies] を参照)。残りは[openApp]と同じです。
     * ユーザーが使用できない場合、またはアプリがインストールされていない場合、copy_not_found は失敗しました。サポートされていないポートは、サポートされていないポートを返します。
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean = false): ActionResult = ActionResult.Failed("unsupported")

    /**
     * [packageName] (別のユーザー [userId] のもの。null は元のもの) が別のタスク ([APP_IN_USE_BY_OTHER_TASK]) で使用されている場合、
     * 解放されるまで最大 [timeoutMs] 待ちます。開かずに待ってください。発信者が到着するまで待って、もう一度開きます。その使命を担い、この使命をこの手に待っている
     * アプリ (お互いに待ちます、誰も待つことができません)、その場で [AppWait.WouldDeadlock] を返します。アプリのポートは他のタスクと競合せず、[AppWait.Unsupported] を返します。
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = AppWait.Unsupported

    /**
     * は、エージェントの画面上にリンクまたは Android 設定ページを開きます ([action] が null の場合、VIEW を押して [uri] を開きます)。 [packageName] は、開く必要があるアプリを制限します。
     * 失敗の理由: link_unresolved (アプリを開けません)、invalid_link、app_in_use_by_user。サポートされていないポートは、サポートされていないポートを返します。
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = ActionResult.Failed("unsupported")

    /**
     * は [packageName] (ACTION_SEND text/plain) とテキストを共有し、エージェントの画面でその共有ページを開きます。開封されただけで送信はされていません。
     * テキスト共有用のページがない場合、Cannot_share に失敗しました。残りは [openApp] と同じです (画面の作成、ユーザーがそれを使用し、システムがどれを開くかを尋ねます)。サポートされていないポートは、サポートされていないポートを返します。
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = ActionResult.Failed("unsupported")

    /**
     * は、Android 標準インターフェイス ([SystemAction] を参照) を通じてシステム アクションを実行します。まず、アプリが接続されているかどうかを確認し、接続されていない場合は not_supported を報告します。ある場合は、エージェントの画面でそれを渡します
     * (初めて使用するときに画面を作成し、ユーザーがホーム画面でアプリを使用しているときは開かないでください)。サポートされていないポートは、サポートされていないポートを返します。
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = SystemActionResult(ActionResult.Failed("unsupported"))

    /**
     * [ref] という番号のスクロール ホイール (またはスクロール ホイール内の項目) を回して、[value] を表示します。ポートで一度に完了します (可能であれば入力し、問題なくスクロールできる場合は 1 つのグリッドをスクロールします)。
     * うまくいかない場合は、押してグリッドを 1 つ上にドラッグして、数回ごとに読み返してください。サポートされていないポートは、サポートされていないとして報告されます。
     */
    suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        WheelOutcome(false, null, null, 0, "none", error = "unsupported")
    suspend fun waitStable(timeoutMs: Long): Boolean
    /**
     * この画面の次のインターフェース変更 ([timeoutMs] まで) を待ちます。 true が返されるまで待ちます。イベント駆動型ポートは、インターフェイス イベント (アクセシビリティ イベント、ページ変更) が発生すると起動します。
     * サポートされていないポート待機タイムアウトは false を返し、呼び出し元によって一定間隔で再読み取りされます。
     */
    suspend fun awaitChange(timeoutMs: Long): Boolean { delay(timeoutMs.coerceAtLeast(0)); return false }
    /** イベント駆動型インターフェイスの状態を待ちます。状態: 出現/消滅/活動/安定。 */
    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = false
    /** は、仮想画面インターフェイス イベントまたはグローバル通知をリッスンします。通話中にのみ購読します。 */
    suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = null
    /** は、一定期間にわたって継続的にイベントを収集します。 1 つのサブスクリプションで 2 つのイベント間の変更を見逃すことはありません。 */
    suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> =
        listOfNotNull(nextEvent(kind, packageName, durationMs))
    /** は、タスク保持シェル アシスタントを通じてコマンドを直接実行します。 */
    suspend fun systemCommand(command: String): SystemCommandResult? = null
    suspend fun listApps(): List<Pair<String, String>> // パッケージ名、表示名

    /** この電話の別のユーザーにもインストールされているアプリ (アバター、仕事用プロファイルなど): パッケージ名 → それらのユーザーの名前。見つからない場合、またはサポートされていない場合は空です。 */
    suspend fun otherProfiles(): Map<String, List<String>> = emptyMap()

    /** は、開くことができる別のアプリです (クローンや仕事用プロファイルなどの別のユーザーにインストールされます。ルールについては、[AppCopies] を参照してください)。見つからない場合、またはサポートされていない場合は空になります。 */
    suspend fun appCopies(): List<AppCopy> = emptyList()

    /**
     * ミッション終了時にこのアプリをどうするか ([AfterTaskMarks] を参照): 書き留めるだけで、現時点では何も動きません。サポートされていないポート (タスクの終了時に宛先がない) は null です。
     */
    val afterTask: AfterTaskMarks? get() = null

    /** 画面診断;ポートがサポートされていない場合は null、呼び出し元が要素リストに基づいて大まかに判断します。 */
    suspend fun checkScreen(): ScreenCheck? = null

    /** 現在のページがシステムによってスクリーンショットから保護されているかどうか。 */
    suspend fun isProtected(): Boolean = false

    /** 現時点で画面が縮小されている輝度グリッド (アクション効果を決定するために使用されます。[FrameGrid.sample] を参照)。画面がない場合、またはポートが画面をサポートしていない場合は null になります。安くする: 画像をエンコードしないでください。 */
    suspend fun frameGrid(): FrameGrid? = null
}
