package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.visual.FrameDiff
import com.wangzan101.mika.core.visual.FrameGrid
import com.wangzan101.mika.core.visual.Recall
import com.wangzan101.mika.core.visual.Shots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import com.wangzan101.mika.core.vision.LocateRegistry
import com.wangzan101.mika.core.vision.Locator
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.observation.InputHints
import com.wangzan101.mika.core.observation.Look
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.HINT_PROTECTED
import com.wangzan101.mika.core.tool.HINT_DISPLAY_CAPTURE
import com.wangzan101.mika.core.tool.MAX_READ_CHARS
import com.wangzan101.mika.core.tool.NOTE_CHANGED_DURING_READ
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.net.UrlHost

internal const val MAX_ELEMENTS = 200
/** は、全画面表示後、最大数回まで差異を検出できます。 */
private const val MAX_CHAIN_DIFFS = 5
/** 書き込み操作後、画面読み取り前のインターフェイス安定性の上限。 */
private const val INLINE_SETTLE_MS = 1500L
/** OCR は画面読み取りの補足であり、次のモデル呼び出しを無期限にブロックすることはできません。 */
private const val OCR_WAIT_MS = 2000L
/** ツリーが十分でない場合は、画面上のテキストが主な素材になります。しばらくお待ちください (ポートは、画面が認識される前に画面が停止するまで待つ必要があります)。 */
private const val OCR_WAIT_POOR_MS = 5000L
/** 不可逆的な判定を行うには、画面の読み取り値に従って ref が属する要素を見つけ、最新の時刻を保持する必要があります。 */
private const val RECENT_SNAPSHOTS = 8
/** は、発行された snapshotId の数を記憶します。 */
private const val ISSUED_MEMORY = 256
/** 結果の「制限に達しました」という文はこの長さまでしかありません。 */
private const val LIMIT_TEXT_MAX = 120

/** find_visually は、探しているもの、指定された元のテキスト、ビジュアル モデルで認識される単語などのポイントを見つけます。不可逆的な決意を見つめる必要がある。 [unverified]: 写真に頼るだけで、裏付けとなる証拠はありません。 */
internal class Located(val target: String, val text: String?, val seen: String?, val unverified: Boolean = false)

/**
 * 画面の画面読み取りステータス: 安定した番号、モデルによって最近取得された画面と差分チェーン、最後の数回の画面読み取り、および画像を見て発見されたポイント。 1 画面につき 1 コピー、 独自の画面ツール セット
 * のみを使用してください。画面番号は複数の再生画面で共有されます（[shots]）。
 */
internal class ScreenSession(
    val surface: Surface,
    stableRefs: Boolean,
    private val visionEnabled: Boolean,
    private val recordFrames: Boolean,
    /** 最後に読み取られたインターフェイス テキスト (複数の画面で共有)。アクション後に読み取られた内容が同じであれば、再度記憶されることはありません。 */
    private val lastRead: () -> String?,
    val shots: Shots,
    vision: VisionService? = null,
    private val adaptiveVision: Boolean = false,
) {
    /** モバイル モデルの結果は要素数によって切り捨てられません。 Web ページは引き続き独自の出力バジェットを使用します。 */
    private val modelElementsLimit get() = if (surface.id == "phone") Int.MAX_VALUE else MAX_ELEMENTS
    val screen: ScreenPort get() = surface.screen
    private var textOnlyDepth = 0

    /** 複合携帯電話コマンドはテキストとステータスのみを内部に残し、モデルは必要に応じて写真を要求します。 */
    suspend fun <T> textOnly(block: suspend () -> T): T {
        textOnlyDepth++
        try { return block() } finally { textOnlyDepth-- }
    }

    val stabilizer = if (stableRefs) RefStabilizer(surface.family.snapshotPrefix) else null
    /** モデルによって取得された最後の画面 (書き込み操作結果の観察または画面)、違いのベース。 */
    private var shown: ModelSnapshot? = null
    /** 最新のフルスクリーンのスナップショット ID とその後の相違点: 相違点を理解するには、チェーン全体を理解する必要があります。 1 つが欠けている場合は、全画面が送信されます。 */
    private val chain = mutableListOf<String>()
    /** モデルでは依然として snapshotId を読み取る画面が表示されます。 null は、ループがまだ通知されていない (すべてが見える) ことを意味します。 */
    var visible: Set<String>? = null
    var lastSnapshot: ScreenSnapshot? = null
    val recentSnapshots = ArrayDeque<ScreenSnapshot>()
    val readAt = mutableMapOf<String, Long>()
    /** この画面によって送信される snapshotId ([RECENT_SNAPSHOTS] より長い: モデルは非常に初期のシリアル番号を使用する可能性があります)。 */
    private val issued = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > ISSUED_MEMORY
    }

    /** この画面上で視覚的に見つかったポイント (@vN);この画面で書き込み操作を行うとクリアされます。 */
    val located = LocateRegistry()
    val locatedInfo = mutableMapOf<String, Located>()
    /** 最後の書き込み操作後に表示される説明: この期間中の座標クリックは、画像から取得された点として扱われます。 */
    var described = false
    val locator = vision?.let { Locator(screen, it, located, surface.family) }

    fun clearLocated() { located.clear(); locatedInfo.clear(); described = false }

    private var accessRevision = screen.appAccess.current().revision

    suspend fun checkAppAccess(current: Boolean = true) {
        val state = screen.appAccess.current()
        if (state.revision != accessRevision) {
            clearLocated()
            clearPrimed()
            lastSnapshot = null
            recentSnapshots.clear()
            readAt.clear()
            shown = null
            chain.clear()
            stale = true
            accessRevision = state.revision
        }
        if (current) screen.checkAppAccess()
    }

    /** この snapshotId (画面を読み取る、または写真を見てポイントを見つける) は、この画面によって送信されます。 */
    fun owns(snapshotId: String): Boolean = snapshotId in issued || located.has(snapshotId)

    /** この画面は数回読み取られています (登録番号の読み取り)。効果を判断するとき、アクションの後にそれが読まれたかどうかがわかります。 */
    var reads = 0
        private set

    /**
     * モデルの最新の画面は古くなっています。アクション後にインターフェイスが変更され、アクション後の画面が結果に戻されません。次の画面が読み取られるまで、これは true のままです。
     */
    var stale = false
        private set

    /** ミカはこの画面を自分で移動しました (滞在中に数回スクロールしました)。モデルの手の画面の位置は正確ではなくなり、古いものとしてマークされます。 */
    fun movedByMika() { stale = true }

    /**
     * 1画面を読み込んで番号を登録します。 [internal]: モデルが見ることはできません (説明に従ってターゲットを見つける場合)。モデルが手に持っている画面は、古くても変わりません。
     * ポートの元の画面は、アクションに続く「プレアクション」として予約されています ([probe] はそれを使用し、再読み込みしません)。
     */
    suspend fun snapshot(internal: Boolean = false): ScreenSnapshot = screen.snapshot().let { p ->
        (stabilizer?.next(p)?.screen ?: p).also { primed = if (internal) it.snapshotId to p else null }
    }.also {
        reads++
        if (!internal) stale = false
        lastSnapshot = it
        recentSnapshots.addLast(it)
        readAt[it.snapshotId] = System.currentTimeMillis()
        issued[it.snapshotId] = Unit
        while (recentSnapshots.size > RECENT_SNAPSHOTS) recentSnapshots.removeFirst().snapshotId.let { id -> readAt.remove(id) }
    }

    /** この時点でもう一度画面を読み取り、番号を登録せず、モデルハンドの画面読み取りを変更しないでください。読み取れない場合は null になります。 */
    suspend fun peek(): ScreenSnapshot? = screenAttempt { screen.peek() }

    suspend fun observe(withImage: Boolean, adaptiveImage: Boolean = false, recordFrame: Boolean = true): ToolExecution {
        val s = snapshot()
        val (tree, ocr, ocrStatus) = readTree(s)
        val imageReason = if (adaptiveImage && tree.poor) tree.reason else null
        val sendImage = withImage || imageReason != null
        stabilizer?.latest?.let { shown = it; chain.clear(); chain += it.snapshotId }
        val seen = interfaceRead(s, ocr.orEmpty())
        val (elements, hidden) = ScreenJson.elements(s, modelElementsLimit)
        val shotId = shots.next()
        val content = okObj {
            val w = s.web
            if (w == null) put("currentApp", s.packageName ?: s.unreadableApp)
            else { put("currentSite", UrlHost.of(w.url)); put("url", w.url); put("title", w.title); put("tabId", w.tabId) }
            w?.dialog?.let { put("dialog", dialogJson(it)) }
            put("snapshotId", s.snapshotId)
            put("screenshotId", shotId)
            imageReason?.let { put("imageReason", it) }
            quality(tree)?.let { put("treeQuality", it) }
            ocrStatus?.let { put("ocr", it) }
            put("screenChangedDuringRead", s.changedDuringRead)
            if (s.changedDuringRead) put("note", NOTE_CHANGED_DURING_READ)
            s.opened?.let { put("opened", openedJson(it)) }
            if (s.captureError != null) {
                put("captureError", s.captureError)
                put("hint", HINT_DISPLAY_CAPTURE)
            }
            else if (s.protected) { put("protected", true); put("hint", HINT_PROTECTED) }
            else if (unreadable(s) != null && ocr.isNullOrEmpty()) {
                put("readable", false)
                // ウィンドウの読取り不能と画像の利用可否を区別し、スクリーンショットと視覚定位による操作を妨げません。
                put("hint", unreadableHint(s, tree, ocrStatus))
            }
            else if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) put("hint", Feedback[surface.family.emptyHint])
            else treeHint(tree, ocrStatus)?.let { put("hint", it) }
            put("viewport", buildJsonObject { put("width", s.width); put("height", s.height) })
            put("elements", elements)
            if (surface.id == "phone" && s.windows.isNotEmpty()) put("tree", UiTreeJson.windows(s))
            ocr?.let { put("visibleText", VisibleText.json(it)) }
            if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { put("screenMap", it) }
            val omitted = s.web?.omitted ?: 0
            put("truncated", hidden > 0 || omitted > 0)
            if (omitted > 0) put("omitted", hidden + omitted)
        }
        // 受保护或持续全黑的帧都不给视觉模型;后者仍保留可读控件供降级操作。
        val png = if ((sendImage && textOnlyDepth == 0 || (recordFrames && recordFrame && textOnlyDepth == 0)) &&
            !s.protected && s.captureError == null) screen.screenshotPng() else null
        // 只有主模型能看图才留图；付款页和普通页面按同一规则处理
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, observationText(s, ocr.orEmpty())))
        val image = png.takeIf { sendImage }
        return ToolExecution(content, image = image, frame = png, imageIds = listOfNotNull(image?.let { shotId }), observations = listOf(seen))
    }

    /**
     * メイン モデルでは、現時点の画面のフレーム (元の画像) とそのスクリーンショット ID を表示し、レビュー用に登録できます。画面読み上げと同じルール:
     * 保護されたページはスクリーンショットされません。画面上にアプリがなく、スクリーンショットを取得できない場合は、null になります。
     */
    suspend fun currentFrame(): Pair<ByteArray, Int>? {
        if (!visionEnabled) return null
        val s = screenAttempt { screen.peek() } ?: return null
        if (s.protected || s.captureError != null) return null
        if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) return null
        val png = screenAttempt { if (screen.isProtected()) null else screen.screenshotPng() } ?: return null
        val shotId = shots.next()
        shots.remember(Recall(shotId, shots.step, s.packageName, png, screenText(s)))
        return png to shotId
    }

    /**
     * メイン モデルはアクション後に画面を表示できます。画面と要素テーブルは同時に表示される必要があります。モデルは要素テーブルの ref/snapshotId に従って動作し、画面は理解のためにのみ使用されます。
     * は観察と同じです。保護されたページは画像を送信しません。画面上にアプリがない場合は null になります。
     */
    suspend fun currentView(): ToolDispatcher.Glance? {
        if (!visionEnabled && !adaptiveVision) return null
        val peeked = screenAttempt { screen.peek() } ?: return null
        if (peeked.packageName == null && peeked.elements.isEmpty() && !peeked.hasWindow) return null
        if (textOnlyDepth > 0) {
            val obs = screenAttempt { observe(withImage = false, recordFrame = false) } ?: return null
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), null, shotId, obs.observations)
        }
        if (adaptiveVision) {
            val obs = screenAttempt { observe(withImage = false, adaptiveImage = visionEnabled) } ?: return null
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), obs.image, shotId, obs.observations)
        }
        // 最初に要素テーブルを読み取り (snapshotId を登録)、次にこの画面を押して、インターセプトできるかどうかを判断します。保護されたページはインターセプトされません。
        val obs = screenAttempt { observe(withImage = false) } ?: return null
        val s = lastSnapshot ?: return null
        val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val png = if (s.protected || s.captureError != null) null else screenAttempt { obs.frame ?: if (screen.isProtected()) null else screen.screenshotPng() }
        // 回看里这一张补上画面
        val recalls = shots.recalls
        if (png != null) recalls.indexOfLast { it.id == shotId }.takeIf { it >= 0 }?.let { i ->
            val r = recalls[i]; recalls[i] = Recall(r.id, r.step, r.app, png, r.elementsText)
        }
        return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), png, shotId, obs.observations)
    }

    /** アクションが成功すると、現在の画面が接続されます (ポートはアクション中にインターフェイスの変更を待機しました)。要素テーブルが画面フィールドに配置され、画面が接続され、そのスクリーンショット ID が結果に書き込まれます。 */
    suspend fun withFrame(r: ToolExecution): ToolExecution {
        val v = currentView() ?: return r
        return r.with(content = JsonObject(r.content + ("screen" to v.content) + ("screenshotId" to JsonPrimitive(v.imageId))), images = listOfNotNull(v.image),
            screenElements = v.content["elements"] as? JsonArray ?: r.screenElements, imageIds = if (v.image != null) listOf(v.imageId) else emptyList(),
            observations = r.observations + v.observations)
    }

    /**
     * 書き込み操作が成功したら、インターフェイスが安定するまで待って (上限は [INLINE_SETTLE_MS])、別の画面を読み取ります。前回モデルで取得した画面と比較して、その差分を画面フィールドに入れます。
     * ベースまたは最新のフルスクリーン モデルは表示されなくなりました。画面が読み取れない場合はアクション結果をそのまま返します。
     */
    suspend fun withScreen(r: ToolExecution): ToolExecution {
        val cur = try {
            screen.waitStable(INLINE_SETTLE_MS)
            snapshot()
            stabilizer!!.latest!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowAppAccess()

            return r
        }
        val v = visible
        // は、連続する差分の最大数が MAX_CHAIN_DIFFS の場合、全額を 1 回送信します。チェーンが長ければ長いほど、読み取りが難しくなり、圧縮中に保持するラウンドトリップが多くなります。
        val seen = chain.isNotEmpty() && chain.size - 1 < MAX_CHAIN_DIFFS && (v == null || chain.all { it in v })
        val s = cur.screen
        val (tree, ocr, ocrStatus) = readTree(s)
        val shotId = shots.next()
        val diff = if (s.protected) {
            // 保護されたページでは画面と要素を表示できません。次回は全ページから再開します
            shown = null; chain.clear()
            buildJsonObject {
                put("snapshotId", cur.snapshotId); put("currentApp", s.packageName); put("full", false)
                put("protected", true); put("hint", HINT_PROTECTED)
                put("screenshotId", shotId)
            }
        } else {
            val d = InterfaceUpdate.between(shown, cur, seen, modelElementsLimit)
            shown = cur
            if (d["full"] == JsonPrimitive(true)) chain.clear()
            chain += cur.snapshotId
            JsonObject(d + ("screenshotId" to JsonPrimitive(shotId)) +
                (ocr?.let { mapOf("visibleText" to VisibleText.json(it)) } ?: emptyMap()) +
                (if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { mapOf("screenMap" to it) } ?: emptyMap() else emptyMap()) +
                (if (s.changedDuringRead) mapOf("note" to JsonPrimitive(NOTE_CHANGED_DURING_READ)) else emptyMap()) +
                (s.opened?.let { mapOf("opened" to openedJson(it)) } ?: emptyMap()) +
                (s.web?.dialog?.let { mapOf("dialog" to dialogJson(it)) } ?: emptyMap()) +
                (quality(tree)?.let { mapOf("treeQuality" to JsonPrimitive(it)) } ?: emptyMap()) +
                (ocrStatus?.let { mapOf("ocr" to JsonPrimitive(it)) } ?: emptyMap()) +
                (s.captureError?.let { mapOf("captureError" to JsonPrimitive(it), "hint" to JsonPrimitive(HINT_DISPLAY_CAPTURE)) } ?:
                    (unreadable(s)?.takeIf { ocr.isNullOrEmpty() }?.let {
                        mapOf("readable" to JsonPrimitive(false), "hint" to JsonPrimitive(unreadableHint(s, tree, ocrStatus)))
                    } ?: treeHint(tree, ocrStatus)?.let { mapOf("hint" to JsonPrimitive(it)) } ?: emptyMap())))
        }
        val text = observationText(s, ocr.orEmpty())
        // 前回読んだ内容と同じであれば、先ほどの画面読みを圧迫しないように再度書きません。
        val read = if (lastRead() != text) interfaceRead(s, ocr.orEmpty()) else null
        // には、観察と同じルールがあります。アンチスクリーンショット ページは取得されません。レビューのために写真を残す前に、メインモデルのみが写真を表示できます。
        val imageReason = if (adaptiveVision && visionEnabled && tree.poor) tree.reason else null
        val sendImage = visionEnabled && (!adaptiveVision || imageReason != null)
        val png = if ((recordFrames || sendImage) && textOnlyDepth == 0 && !s.protected && s.captureError == null) {
            screenAttempt { screen.screenshotPng() }
        } else null
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, text))
        // 古いモードでは常に画像が添付されます。グレーデッド モードでは、明らかに要素が不足している場合にのみ画像が添付されます。
        val attach = kept?.takeIf { sendImage && r.images.isEmpty() }
        val shownDiff = if (imageReason == null) diff else JsonObject(diff + ("imageReason" to JsonPrimitive(imageReason)))
        return r.with(content = JsonObject(r.content + ("screen" to onSurface(shownDiff))),
            frame = r.frame ?: png?.takeIf { recordFrames }, images = attach?.let { listOf(it) } ?: r.images,
            screenElements = ScreenJson.elements(s, modelElementsLimit).first, imageIds = if (attach != null) listOf(shotId) else r.imageIds).observing(read)
    }

    /** アクション前のインターフェイス状態 (登録番号なし) と画像の 1 フレームを使用して効果を判断します。読み取れない場合は null になります。 */
    class EffectProbe internal constructor(before: ScreenSnapshot?, frame: FrameGrid?) {
        var before: ScreenSnapshot? = before
            internal set
        var frame: FrameGrid? = frame
            internal set
    }

    /** このアクションの前の一目は、アクションが判断され、その効果が判断される前に保持されます。アクションが開始される前に、ミカ自身がインターフェイスを再度動かすと、[reprobe] によって再読み取りされます。 */
    private var pendingProbe: EffectProbe? = null

    /** このアクションの効果は判定されました（または判定されません）。 */
    fun probeDone() { pendingProbe = null }

    /**
     * アクションが開始される前に、ミカは自分でインターフェイスを移動しました (Web ページに留まりながらページを少しスクロールしました)。もう一度見て、アクションの前の状態として扱い、このスクロールをアクションの効果としてカウントしないようにします。
     * モデルの手の画面の位置も正しくなく、古いものとしてマークされています。アクションが有効になるまで何もしないでください。
     */
    suspend fun reprobe() {
        val p = pendingProbe ?: return
        stale = true
        withTimeoutOrNull(EffectTuning.PROBE_MS) { p.before = peek(); p.frame = grid() }
    }

    /** は、説明に従ってターゲットを見つけるために画面を読み取るだけです。モデルのスナップショット ID とポートがそのままの画面です。 [probe] によって削除されるか無効化されるまで有効です。 */
    private var primed: Pair<String, ScreenSnapshot>? = null

    fun clearPrimed() { primed = null }

    /**
     * アクションを実行する前に確認してください。最大コストは [EffectTuning.PROBE_MS] です。遅すぎる部分は null です (ポートの事実に従ってのみ判断されます)。
     * アクションは、説明に従って読み取ったばかりの画面を使用します ([snapshotId] が適切です)。使用するだけで、二度と読まないでください。
     */
    suspend fun probe(snapshotId: String? = null): EffectProbe {
        val ready = primed?.takeIf { it.first == snapshotId }?.second
        primed = null
        if (ready != null) {
            var frame: FrameGrid? = null
            withTimeoutOrNull(EffectTuning.PROBE_MS) { frame = grid() }
            return EffectProbe(ready, frame).also { pendingProbe = it }
        }
        var before: ScreenSnapshot? = null
        var frame: FrameGrid? = null
        withTimeoutOrNull(EffectTuning.PROBE_MS) { before = peek(); frame = grid() }
        return EffectProbe(before, frame).also { pendingProbe = it }
    }

    private suspend fun grid(): FrameGrid? = screenAttempt { screen.frameGrid() }

    /**
     * アクションが成功した後、インターフェイス状態に対する実際の影響を確認し ([ActionEffect] を参照)、結果の効果フィールドを書き込み、その効果に応じたプロンプトを表示します。変更された結果は効果に続きます。 アクション後に
     * が読み取られた場合 ([readsAtAction] が再度読み取られた場合、つまり、インターフェイスが結果に含まれている場合)、そのコピーを使用します。それ以外の場合: ポートはすでにアクションでインターフェイスを待機しています (レポートは変更されていないか、ページは沈黙しています)。 インターフェイスが安定するまで待たずに、
     * を見てください (上限 [EffectTuning.SETTLE_MS])。変化がない場合は、[EffectTuning.RECHECK_MS] を待って、もう一度観察してください (非同期更新)。
     * 判決全体にかかる費用は最大で [EffectTuning.BUDGET_MS] です。港の事実に基づいて結論を出す時期が来たとき。読み取り専用インターフェイスです。写真を撮りますが、アクションは実行しません。
     * インターフェイスが変更され、結果に新しいインターフェイスがない場合、モデルの手のコピーは古いものとしてマークされます ([stale])。
     */
    suspend fun withEffect(
        r: ToolExecution, probe: EffectProbe, verb: Verb, readsAtAction: Int, target: UiElement?, region: List<Int>?, typed: String?,
    ): ToolExecution {
        val c = r.content
        val opened = c["opened"] as? JsonObject
        val port = PortFacts(
            changed = r.changed,
            switchedTo = (c["app_switched"] as? JsonPrimitive)?.content,
            verified = c["verification"] == JsonPrimitive("match"),
            loading = c["settled"] == JsonPrimitive(false),
            onUserScreen = opened?.takeIf { it["onUserScreen"] == JsonPrimitive(true) }?.let { (it["package"] as? JsonPrimitive)?.content },
        )
        val portWaited = r.changed != null || "settled" in c
        val fresh = reads > readsAtAction
        fun judge(after: ScreenSnapshot?, frame: FrameGrid?) = ActionEffect.classify(
            verb, probe.before, after, port, FrameDiff.compare(probe.frame, frame, region), target, typed,
            pressedEnter = c["pressedEnter"] == JsonPrimitive(true), secret = c["secret"] == JsonPrimitive(true),
        )
        var effect: ActionEffect? = judge(null, null)
        // 新しいステートメント「上限に達しました」がアクション後に表示されるインターフェイスに表示されます (2 回表示されてもカウントされません。プロンプトはしばらくすると消えます)。
        var limit: String? = null
        withTimeoutOrNull(EffectTuning.BUDGET_MS) {
            if (!fresh && !portWaited) settle(EffectTuning.SETTLE_MS)
            val after = if (fresh) lastSnapshot else peek()
            limit = LimitNotice.find(probe.before, after)
            effect = judge(after, grid())
            if (effect?.kind == EffectKind.NoEffect) {
                // インターフェイスは非同期で更新される可能性があります。しばらく待ってからもう一度確認してください。それでも変化がない場合は効果がないと考えられます。
                delay(EffectTuning.RECHECK_MS)
                val again = peek()
                limit = limit ?: LimitNotice.find(probe.before, again)
                effect = judge(again, grid())
            }
        }
        // ツリーが読めない点（携帯電話でのみ判断。ウェブページがツリーに十分かどうかは問われません）：変化が見えないからといって、クリックされないわけではありません。
        val e = effect?.let { judged ->
            if (surface.id != "phone") return@let judged
            val quality = lastTree?.quality ?: probe.before?.let { TreeAdequacy.ofTree(it).quality }
            if (judged.kind == EffectKind.NoEffect && ActionEffect.treeBlind(verb, quality, probe.before, target, region)) judged.copy(treeBlind = true) else judged
        } ?: return r
        if ("screen" !in c && e.kind != EffectKind.NoEffect) stale = true
        val changed = e.kind != EffectKind.NoEffect
        val hint = listOfNotNull(limit?.let { Feedback.format("effect.limit_reached", "text" to it.take(LIMIT_TEXT_MAX)) },
            EffectFeedback.hint(e, verb, portSwitched = "app_switched" in c || opened != null),
            Feedback["effect.stale"].takeIf { stale && e.kind != EffectKind.SmallChange }, (c["hint"] as? JsonPrimitive)?.content)
        val content = JsonObject(c - "hint" + ("effect" to e.toJson()) +
            (if ("changed" in c) mapOf("changed" to JsonPrimitive(changed)) else emptyMap()) +
            (if (verb == Verb.Scroll && e.kind == EffectKind.NoEffect) mapOf("atEnd" to JsonPrimitive(true)) else emptyMap()) +
            (if (stale) mapOf("interfaceStale" to JsonPrimitive(true)) else emptyMap()) +
            (limit?.let { mapOf("limitReached" to JsonPrimitive(it.take(LIMIT_TEXT_MAX))) } ?: emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap()))
        return r.with(content = content, changed = changed)
    }

    /** 最新の画面読み取りにより、ツリーが十分であるかどうかが判断されました。効果判定では「変化が見られない」「クリック感がない」と言われました。 */
    private var lastTree: TreeCheck? = null

    /**
     * この画面のツリーはピースが欠けているか、使用できません（携帯電話でのみ判断できます。画面読み取り結果のtreeQualityと同じです）：ツリーを見ただけでは正確に判断できない場合は、最新のスクリーン読み取り（画面上のテキストを読み取る）の判断を押してください。
     * ツリー内の説明に従って入力ボックスが見つからない場合は、画像を参照してください。
     */
    fun treeBlind(s: ScreenSnapshot): Boolean = surface.id == "phone" &&
        (TreeAdequacy.ofTree(s).quality ?: lastTree?.quality).let { it == TreeQuality.Partial || it == TreeQuality.Unusable }

    private suspend fun settle(ms: Long) {
        screenAttempt { screen.waitStable(ms) }
    }

    /** 画面を読み取る前にユーザーのホーム画面から戻ったばかりのアプリ: アクションの結果で開かれたものと同じフィールドと追加の説明。 */
    private fun openedJson(o: OpenedElsewhere): JsonObject = JsonObject(o.toJson() + ("note" to JsonPrimitive(openedHint(o))))

    /**
     * フロントエンド アプリは開いているが、ウィンドウを読み取ることができない場合にモデルに与えられる説明 (「開いていない」ではなく、再度起動しない)。読み取り可能な場合、またはフロントエンドの人物が不明な場合は null になります。
     */
    private fun unreadable(s: ScreenSnapshot): String? {
        val app = s.unreadableApp ?: return null
        if (s.elements.any { it.meaningful }) return null
        return Feedback.format("unreadable_window", "app" to app)
    }

    /** スクリーンショットが使える場合は再起動しない案内を保ち、OCRまたは視覚定位へ切り替えます。 */
    private fun unreadableHint(s: ScreenSnapshot, tree: TreeCheck, ocrStatus: String?): String {
        val fallback = treeHint(tree, ocrStatus) ?: return unreadable(s)!!
        return Feedback.format("unreadable_window.fallback", "app" to s.unreadableApp!!, "fallback" to fallback)
    }

        /** 今回読み取られたインターフェイスのステータス: 読み取り可能な要素のテキスト、その場所、Web ページのタイトル、入力ボックス内の単語。 */
    private fun interfaceRead(s: ScreenSnapshot, ocr: List<VisibleTextLine> = emptyList()): Observation {
        val f = inputFields(s)
        val place = s.web?.url?.let(Place::page) ?: s.packageName?.let(Place::app)
        return Observation(ObsKind.Read, observationText(s, ocr), source = surface.id, place = place, title = s.web?.title,
            trust = if (s.web != null) Trust.Untrusted else Trust.Trusted, inputs = InputHints(f.names, f.focused))
    }

    /**
     * この画面上のツリーは十分ですか ([TreeAdequacy])、および追加する必要がある画面上のテキスト (OCR、ツリーにないものだけを残し、エラーを認めた行は削除します)。
     * ツリーが明らかに十分で保護されている場合、文字は読み取られません。不正確と判断された場合は、文字が再度読み取られます。ツリーが十分でない場合は、文字を読み取る必要があります。絵を見ることができないメインモデルはそれに依存します。
     * 写真を見ることができるメインモデルは、その位置をクリックすることもでき、証拠台帳（回答元、検証）とphone_collectは、ここから画面に書かれたもののみを取得できます。
     */
    private suspend fun readTree(s: ScreenSnapshot): TreeRead {
        val tree = TreeAdequacy.ofTree(s)
        if (tree.quality == TreeQuality.Adequate || tree.quality == TreeQuality.Protected) return TreeRead(tree, null, null).also { lastTree = it.tree }
        val (ocr, status) = visibleText(s, if (tree.poor) OCR_WAIT_POOR_MS else OCR_WAIT_MS)
        val checked = TreeAdequacy.withText(tree, ocr)
        lastTree = checked
        // 十分な木がありません、そして画面上にテキストの行がありません。その理由を正直に話してください。そうすれば、モデルは写真をもう一度読む代わりに写真を見るべきだと理解します。
        return TreeRead(checked, ocr, status.takeIf { quality(checked) != null && ocr.isNullOrEmpty() })
    }

    /**
     * 画面上のツリーには単語がありません。一行も読み取れない理由: 空 (単語が画面上で認識されない)、判読不能 (認識された単語がすべて間違っている)、
     * タイムアウト（制限時間内に認識が完了しなかった）、利用不可（ポートが提供できない：画面がなく、画面が切り替わり続ける）、失敗（認識エラー）。
     * は、読み取り中でない場合 (Web ページ、保護されており、ウィンドウなし) は null です。ツリーは読み取りプロセス中に変化しますが、同じままです。単語の位置は画像自体から得られ、
     * ポートは画面が安定するまで待機します。認識プロセス中にページ全体が変更され、再度認識されます。
     */
    private suspend fun visibleText(s: ScreenSnapshot, waitMs: Long): Pair<List<VisibleTextLine>?, String?> {
        if (surface.id != "phone" || s.protected || s.captureError != null || !s.hasWindow) return null to null
        var timedOut = true
        val raw = try {
            withTimeoutOrNull(waitMs) { screen.readVisibleText().also { timedOut = false } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { e.rethrowAppAccess(); return null to "failed" }
        if (raw == null) return null to if (timedOut) "timeout" else "unavailable"
        if (raw.isEmpty()) return raw to "empty"
        val legible = VisibleText.legible(raw)
        if (legible.isEmpty()) return legible to "illegible"
        return VisibleText.missing(s, legible) to null
    }

    /** は、画面読み取り結果の TreeQuality に書き込まれます。携帯電話で、ツリーが十分でない場合 (Web ページに補足する画面上のテキストがない場合) にのみ書き込まれます。 */
    private fun quality(tree: TreeCheck): String? = tree.quality?.takeIf { surface.id == "phone" && tree.poor }?.wire

    /** ツリーが十分でない場合のモデルの説明: 画面上のテキストの由来とクリック方法。モデルにピクチャービューが装備されている場合、単語のないアイコンを見つける方法。言葉さえ読めないとき、その理由と見つけ方。 確認する前に、 */
    private fun treeHint(tree: TreeCheck, ocrStatus: String? = null): String? = when {
        quality(tree) == null -> null
        ocrStatus != null && locator != null -> Feedback.format("tree_poor.no_text.visual", "ocr" to ocrStatus)
        ocrStatus != null -> Feedback.format("tree_poor.no_text", "ocr" to ocrStatus)
        locator != null -> Feedback.format("tree_poor.visual", "tree_poor" to Feedback["tree_poor"])
        else -> Feedback["tree_poor"]
    }

    /**
     * をもう一度見てください。この時点で画面をもう一度読み取り (登録番号はありません。モデルの手の参照には影響しません)、[withImage] のときにもう一度写真を撮ります (スクリーンショット防止ページは撮影されません)。
     * 読み込むときは、[extra] (Web ページのテキストの先頭: ビューポート内の要素のみが読み取られます) を追加します。読み取れない場合でも返却されます（観察なし）。
     */
    suspend fun freshLook(withImage: Boolean, extra: (suspend () -> Observation?)? = null): Look {
        val s = screenAttempt { screen.snapshot() }
        val png = if (withImage && s != null && !s.protected && s.captureError == null) {
            screenAttempt { screen.screenshotPng() }
        } else null
        // ツリーが十分でない場合でも画面上のテキストを読みます: 画面に書かれている内容を確認してください
        val seen = s?.let { interfaceRead(it, readTree(it).ocr.orEmpty()) }?.takeIf { it.text.isNotEmpty() }
        val more = if (s != null) extra?.invoke() else null
        return Look(listOfNotNull(seen, more), png, s?.protected == true)
    }

    /** この画面の意味に従って「どこ」を報告します（Webサイトがサイトです）：観察と同様に、携帯電話とWebサイトの「どこ」が混在しません。 */
    private fun onSurface(o: JsonObject): JsonObject {
        val key = surface.family.placeKey
        if (key == ScreenFamily.APP_PLACE || ScreenFamily.APP_PLACE !in o) return o
        return JsonObject(o.filterKeys { it != ScreenFamily.APP_PLACE } + (key to (o[ScreenFamily.APP_PLACE] ?: JsonNull)))
    }
}

/** この画面の入力ボックス内の単語 (プロンプトやパスワード ボックスではありません)、および入力フォーカスのある単語。証拠台帳は、入力ボックスから返され、これに基づいてユーザーが入力した単語を区別します。 */
internal fun inputFields(s: ScreenSnapshot): InputFields {
    val fields = s.elements.filter { it.editable && !it.hint && !it.secret && it.name.isNotBlank() }
    return InputFields(fields.map { it.name.replace('\n', ' ') }, fields.firstOrNull { it.focused }?.name?.replace('\n', ' '))
}

internal class InputFields(val names: List<String>, val focused: String?)

/** 1 つの画面読み取りのツリー判定、追加された画面上のテキスト、およびツリーが十分ではなくテキスト行がない理由 (ScreenSession.visibleText を参照)。 */
internal data class TreeRead(val tree: TreeCheck, val ocr: List<VisibleTextLine>?, val ocrStatus: String?)

/** 画面読み取り用の要素テキスト (1 行に 1 つ)。 the first line is the front-end App. If there is no text, it is an empty string. Don't remember the words in the password box. */
internal fun screenText(s: ScreenSnapshot): String {
    val names = s.elements.filter { it.meaningful && it.name.isNotBlank() && !it.secret }.map { it.name.replace('\n', ' ') }
    // Web ページのタイトルは要素内にありません。これはこの画面の事実でもあります (ページ上に表示されており、要素テキストと同じ内容で確認されます)。読み取り可能な要素のない Web ページは引き続き保持されます。
    val title = s.web?.title?.takeIf { it.isNotBlank() }?.let { "ページのタイトル:${it.replace('\n', ' ')}" }
    val dialog = s.web?.dialog?.let { "ウェブのダイアログ（${it.kind}）:${it.message.replace('\n', ' ')}" }
    if (names.isEmpty() && title == null && dialog == null) return ""
    val where = s.web?.let { "現在のページ:${it.url}" } ?: "現在のアプリ:${s.packageName ?: "未知"}"
    return (listOfNotNull(where, title, dialog) + names).joinToString("\n").take(MAX_READ_CHARS)
}

/** アクセシブルなテキストは依然として主要な証拠です。ネイティブ OCR は、ツリー内の欠落している画面テキストのみを追加します。 */
private fun observationText(s: ScreenSnapshot, ocr: List<VisibleTextLine>): String {
    val tree = screenText(s)
    if (ocr.isEmpty()) return tree
    return listOf(tree, "画面上の文字（OCR）:", ocr.joinToString("\n") { it.text }).filter(String::isNotEmpty)
        .joinToString("\n").take(MAX_READ_CHARS)
}

internal fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

/** 画面読み取り結果のページ ポップアップ ボックス: ページの実行を続行するには、まずモデルが web_interact のダイアログで応答する必要があります。 */
internal fun dialogJson(d: PageDialog): JsonObject = buildJsonObject {
    put("kind", d.kind)
    put("message", d.message.take(PageDialog.MAX_MESSAGE))
    d.defaultText?.let { put("defaultText", it.take(PageDialog.MAX_MESSAGE)) }
    put("hint", Feedback["page_dialog"])
}
