package com.wangzan101.mika.capsule

import kotlinx.coroutines.delay

import androidx.compose.runtime.produceState

import com.wangzan101.mika.ui.KeptPageCopy

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.ScreenThumb
import com.wangzan101.mika.ui.UserMessage
import androidx.compose.ui.graphics.ImageBitmap
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val CardShape = RoundedCornerShape(22.dp)
private val ScreenShape = RoundedCornerShape(14.dp)
private val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
private val CardWidth = 322.dp
private val ScreenWidth = 104.dp
private val ZoomWidth = 300.dp
private val Title = TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
private val Sub = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val Body = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
private val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
private val Step = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** カードのステートメント (ボタン、リンク、画面読み取りの説明) を修正しました。 */
object CardCopy {
    val STOP: String get() = tr(R.string.chat_stop)
    val TAKE_OVER: String get() = tr(R.string.action_take_over)
    val RESUME: String get() = tr(R.string.chat_continue)
    val APPROVE_ONCE_ONLY: String get() = tr(R.string.card_approve_once)
    val DECLINE: String get() = tr(R.string.action_decline)
    val SEE_PROCESS: String get() = tr(R.string.card_see_process)
    val BACK_TO_MIKA: String get() = tr(R.string.card_back_to_mika)
    val SEND: String get() = tr(R.string.chat_send)
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
    val COLLAPSE: String get() = tr(R.string.collapse)
    val SCREEN: String get() = tr(R.string.card_screen)
    val SCREEN_LABEL: String get() = tr(R.string.card_screen_label)
    val ZOOMED: String get() = tr(R.string.card_zoomed)
    val ZOOM_HINT: String get() = tr(R.string.card_zoom_hint)
    val HIDE_CAPSULE: String get() = tr(R.string.capsule_hide)

    /** カプセルを再び有効にする設定の場所を、画面と同じ名前で案内します。 */
    fun hiddenHint(): String = tr(R.string.capsule_hidden_hint, tr(R.string.settings_title), tr(R.string.capsule_settings_title))
}

/** カードのボタン。タスク サービスの既存のアクションに変換されます ([CapsuleController] を参照)。 [runKey] は、押されたときのそのものの番号です。 */
interface CardActions {
    fun collapse()
    fun stop(runKey: String)
    fun takeOver(runKey: String)
    fun resume(runKey: String)
    fun answer(runKey: String, text: String)
    fun approve(runKey: String, requestKey: Int)
    fun decline(runKey: String, requestKey: Int)
    fun openMika(conversationId: String)
    fun zoom(on: Boolean)
    /** は下部の入力ボックスをクリックしました。そのとき初めて、フローティング レイヤーがキーボードを受信できるように変更されました。 */
    fun openInput()
    fun draft(text: String)
    /** は入力ボックス内の単語を送信します ([CapsuleCards.input] を表示するために誰かにそれを渡します)。 */
    fun send()
    /** アイドル カード: [fresh] true の場合は新しい会話を開き、false の場合は最新の会話を継続します。 */
    fun freshChat(fresh: Boolean)
    /** ダイアログ カードが一番上までスクロールしました。さらに数ラウンド前方にロードしてください。 */
    fun loadEarlier()
    /** 入力ボックスの左側にある画面キャプチャ ボタン: 最初にカードを非表示にして、その時点のユーザーの画面をキャプチャし、入力ボックスの上に掛けます。 */
    fun captureScreen()
    /** 添付ファイル バーの ×: このスクリーンショットは不要です。 */
    fun dropScreen()
    /** バックグラウンドで実行されたままになっているページのカード上の「Web ページを閉じる」。 画面 */
    fun closeKeptPage() {}
    /** カード下部から設定をオフにしてカプセルとカードを閉じます。実行中のタスクと通知は継続します。 */
    fun hideCapsule() {}
}

/** がキャプチャされ、送信を待って入力ボックスにハングしました。[thumb] はサムネイルです (スクリーンショットが許可されていないページにはサムネイルがありません)。 */
data class PendingScreen(val shot: CapturedScreen, val thumb: ImageBitmap?)

/**
 * カードの下部にある入力ボックスは、現時点では次のようになります。 [open] の場合、これは入力できるボックスです (フローティング レイヤーがキーボードを受け取ることができます)。それ以外の場合は、プロンプトの行が表示され、クリック後に開きます。
 * [screen] は、切り取られてぶら下がった画面です（次の文と一緒に送信します。そのまま送信できます）。
 */
data class CardInputUi(val open: Boolean = false, val draft: String = "", val screen: PendingScreen? = null) {
    /** が書き込んだか、画面がハングしています。送信できます。 */
    val canSend: Boolean get() = draft.isNotBlank() || screen != null

    /** カードが収納され、カプセルが非表示になった場合: 入力ボックスは収納され、画面のスクリーンショットは保持されず、未送信の単語は保持され、次回拡張および変更できます。 */
    fun collapsed(): CardInputUi = CardInputUi(draft = draft)

    /** が話した単語は、未送信の単語の後に接続されます (ダイアログ ページの入力ボックスと同じ接続方法)。 */
    fun withSpoken(text: String): CardInputUi = copy(draft = HoldToTalk.join(draft, text))
}

/**
 * カードをクリックしてカプセルを開きます。ステータスは表示されず、1 つのことだけが表示されます (どれを選択するかについては、[CapsuleCards.of] を参照してください)。
 * タスク カード ([CardView.Detail]) の左側は、小さな仮想画面 [preview] (画面がある場合にのみ使用可能) です。右側のステータスを押して、手順、やりたいこと、ボタンを配置します。
 * 会話カード ([CardView.Chat]) は、その会話 [thread] の前後です (読み取り中は null、最初は中央が空です)。
 * ダイアログ カードの高さは [height] に固定されており (カードが表示され、シャドウ エッジなし。設定されていない場合は、画面の高さ [CardDocking.SHARE] を押します)、コンテンツによって変更されません。タスクカード
 * 内容の高さに合わせ最大で [height]、下に余白を残さない — タスクカード中央の部分は、小さな画面（画面のアスペクト比で取る）と右側のテキストのうち高い方を取る。実行中のステップは最大でも小さな画面と
 * 同じ高さ（画面がまだない場合はメイン画面のアスペクト比で計算し、画面が来てもカードが跳ねない）、ステップが増えてもカードは高くならない: 最新のステップが下にあり、古いものは上へスクロールして見る; 他の内容が収まらなければ内側でスクロールし、
 * タップしてほしいボタンと入力欄は下部に固定する。高さが変わったとき（カードの切替、内容の変化）は一気に最終値にする: フローティングウィンドウは内容の大きさに追随し、一度だけ変わり、フレームごとにウィンドウを変えない; 上端は動かさない（[CardDocking] 参照）。
 * 展開・収納のアニメーションは外側で付ける（[OverlayMotion]）。
 * [onHandle] ウィンドウ内のタイトル バーの位置 (ピクセル) を報告します。タイトル バーを押してカードをドラッグします。
 * [zoomed]の場合、全体画像が拡大画像に変わりますので、クリックして戻ります。 [elapsed] 開始時刻を経過時間に変更します(1秒ごとに更新)。
 */
@Composable
fun CapsuleCard(
    card: CardView,
    preview: Bitmap?,
    zoomed: Boolean,
    actions: CardActions,
    elapsed: @Composable (Long?) -> String?,
    modifier: Modifier = Modifier,
    input: CardInputUi = CardInputUi(),
    thread: ChatThread? = null,
    height: Dp = Dp.Unspecified,
    onHandle: (Rect) -> Unit = {},
) {
    val field = CapsuleCards.input(card)
    // タスクがまだ進行中 (作業中、ミカがあなたに尋ねるなど) の場合、別のボタンを占有せずに、ダイアログ ページと同様に入力ボックスの右側に停止が配置されます。
    val stopKey = CapsuleCards.stopKey(card)
    val h = if (height == Dp.Unspecified) (LocalConfiguration.current.screenHeightDp * CardDocking.SHARE).dp else height
    val report by rememberUpdatedState(onHandle)
    val handle = Modifier.testTag("capsule-header").onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        report(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }
    val inputRow: @Composable () -> Unit = {
        field?.let { InputRow(it, input, actions, stop = stopKey?.let { k -> { actions.stop(k) } }) }
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        when {
            zoomed && preview != null -> Zoomed(preview, actions)
            card is CardView.Detail -> Detail(card, preview, actions, elapsed, h, handle, inputRow)
            card is CardView.Chat -> ChatCard(card, thread, actions, elapsed, h, handle, inputRow)
            card is CardView.Kept -> KeptCard(card, preview, actions, h, handle)
        }
    }
}

/**
 * フローティング レイヤー内のオブジェクトが表示されたり折りたたまれたりする動的な効果 (カードが展開されて折りたたまれ、横に戻される): フローティング レイヤー ウィンドウは 1 回のクリックで最終的なサイズと位置になり、内部のオブジェクトはレイヤーによってのみ決定されます。
 * は [origin] からスケールと透明度を伸ばします (現在のサイズに合わせて方向を指定します。たとえば、カードがカプセル側から来るようにします)。実際に透明になり始めたら、[onOpened] を調整します。
 * [open] が false になったら、そこで撤回してフェードアウトし、[onClosed] の調整を終了します。
 * はレイヤーを移動するだけでレイアウトは変更しないため、ウィンドウをフレームごとに変更することはありません。アニメーションをオフにするとそのままその場に、しまうとすぐに[onClosed]に調整されます。
 */
@Composable
fun OverlayMotion(
    open: Boolean, origin: (Size) -> TransformOrigin, onClosed: () -> Unit, onOpened: () -> Unit = {}, content: @Composable () -> Unit,
) {
    val still = MikaTheme.reduceMotion
    val shown = remember { Animatable(if (still) 1f else 0f) }
    val closed by rememberUpdatedState(onClosed)
    val opened by rememberUpdatedState(onOpened)
    val from by rememberUpdatedState(origin)
    LaunchedEffect(open, still) {
        if (open) {
            if (still) {
                shown.snapTo(1f)
                opened()
            } else {
                // 表示されたフレームを入力し、ウィンドウのサイズを変更する必要があります。最も遅い場合: 描画が完了するまで待ってから開始します。アニメーションの最初の数フレームは食い込まれません。
                if (shown.value == 0f) repeat(SETTLE_FRAMES) { withFrameNanos { } }
                opened()
                shown.animateTo(1f, tween(CARD_IN_MS, easing = MikaMotion.Decelerate))
            }
        } else {
            runOverlayClose(still) {
                shown.animateTo(0f, tween(CARD_OUT_MS, easing = FastOutLinearInEasing))
                // 最初に完全に非表示のフレームを描画してから、内容を変更してウィンドウを移動し、半透明のカード フレームを残さないようにします。
                withFrameNanos { }
            }
            // 动画帧停了也不能把中间帧永远留在屏上;收尾回调负责换内容、收回窗口状态。
            if (!still) shown.snapTo(0f)
            closed()
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = shown.value
            val scale = CARD_FROM_SCALE + (1f - CARD_FROM_SCALE) * v
            scaleX = scale
            scaleY = scale
            // 透明度はズームよりも速く、出てくるとすぐにはっきりと見え、戻すとフェードアウトします。
            alpha = (v * 2f).coerceAtMost(1f)
            transformOrigin = from(size)
        },
    ) { content() }
}

/**
 * 等收起动画和最后一帧,但浮层拿不到后续帧时也必须收尾。等待时长跟随系统的动画速度;
 * 极大的慢动作倍率最多按十倍算,避免窗口状态无限期停在 closing。
 */
internal suspend fun runOverlayClose(reduceMotion: Boolean, animate: suspend () -> Unit) {
    if (reduceMotion) return
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor
        ?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(10f) ?: 1f
    withTimeoutOrNull((CARD_OUT_MS * scale).toLong() + CLOSE_GRACE_MS) { animate() }
}

private const val CARD_IN_MS = 240
private const val CARD_OUT_MS = 180
private const val CLOSE_GRACE_MS = 500L
private const val CARD_FROM_SCALE = 0.6f
private const val SETTLE_FRAMES = 2

/**
 * 下部の入力ボックス: 通常、これは一連のプロンプトです (フローティング レイヤーはキーボードを取得しません)。クリックして入力可能なボックスを開くと、入力方法がポップアップ表示されます。 Enterを押すか、右側のボタンを押して発行します。
 * に [stop] がある場合、右側には常に黒いストップ キーがあります (ダイアログ ページの入力フィールドと同じ)。ひとこと書いたら、もうひとつ送ることがあります。
 */
@Composable
private fun InputRow(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)? = null) {
    ui.screen?.let { ScreenChip(it, actions::dropScreen) }
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CameraKey(actions::captureScreen)
        Box(Modifier.weight(1f)) { InputField(field, ui, actions, stop) }
    }
}

/** 入力ボックスの左側にあるスクリーンショット ボタン: 丸いボタン内の画面。 */
@Composable
private fun CameraKey(onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-camera").size(38.dp).clip(MikaShapes.Pill).background(c.ink.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClickLabel = ScreenCopy.CAMERA, onClick = onClick)
            .semantics { contentDescription = ScreenCopy.CAMERA },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Camera, null, Modifier.size(18.dp), tint = c.ink2)
    }
}

/** 入力ボックスの上にあるもの: サムネイル、「現在の画面」、アプリ・タイム、右 × 不要です。スクリーンショットのページに「キャプチャされていません」と書かないようにしてください。 */
@Composable
private fun ScreenChip(p: PendingScreen, onRemove: () -> Unit) {
    val c = MikaTheme.colors
    val s = p.shot.screen
    Row(
        Modifier.testTag("capsule-attachment").padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.05f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.blocked) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(MikaIcons.Lock, null, Modifier.size(16.dp), tint = c.ink2) }
        else ScreenThumb(p.thumb, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(if (s.blocked) ScreenCopy.BLOCKED else ScreenCopy.TAG, style = Strong.copy(fontSize = 12.5.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ScreenCopy.detail(s), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.testTag("capsule-attachment-remove").size(26.dp).clip(MikaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = ScreenCopy.REMOVE, onClick = onRemove)
                .semantics { contentDescription = ScreenCopy.REMOVE },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3)
        }
    }
}

/** 送信ボタン: 送信するものがある場合は背景が青色になり、送信しない場合は灰色になり、クリックできません。 */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit, start: Dp = 0.dp) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-send").padding(start = start).size(KeySize).clip(MikaShapes.Pill)
            .background(if (enabled) c.accent else c.ink3.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = CardCopy.SEND, onClick = onSend)
            .semantics { contentDescription = CardCopy.SEND },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Up, null, Modifier.size(17.dp), tint = Color.White)
    }
}

@Composable
private fun InputField(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)?) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (!ui.open) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
                .padding(end = if (stop != null || ui.screen != null) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.testTag("capsule-input").weight(1f).heightIn(min = 42.dp)
                    .clickable(role = Role.Button, onClickLabel = field.hint, onClick = actions::openInput).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(ui.draft.ifEmpty { field.hint }, style = Body, color = if (ui.draft.isEmpty()) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stop?.let { StopKey(it) }
            // は、画面がハングしたときに入力ボックスを開かずに直接送信できます。
            if (ui.screen != null) SendKey(true, actions::send, start = if (stop != null) 4.dp else 0.dp)
        }
        return
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // は会話ページと同じです。これを開くと、外側の単語が変わり (話し終えた後に続きます)、カーソルが最後に配置され、その後の入力はすべて最後に行われます。
    var fieldValue by remember { mutableStateOf(TextFieldValue(ui.draft, TextRange(ui.draft.length))) }
    val shownValue = if (fieldValue.text == ui.draft) fieldValue else TextFieldValue(ui.draft, TextRange(ui.draft.length))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (ui.draft.isEmpty()) Text(field.hint, style = Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                shownValue, { v -> fieldValue = v; if (v.text != ui.draft) actions.draft(v.text) },
                Modifier.testTag("capsule-field").fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.hint },
                textStyle = Body.copy(color = c.ink), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ui.canSend) actions.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || ui.canSend) SendKey(ui.canSend, actions::send, start = if (stop != null) 4.dp else 0.dp)
    }
}

/** 入力ボックスの右側にあるストップ キー: 黒い丸いボタンの中に四角形があり、ダイアログ ページの入力フィールドと同じです。 */
@Composable
internal fun StopKey(onStop: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-stop").size(KeySize).clip(MikaShapes.Pill).background(c.ink)
            .clickable(role = Role.Button, onClickLabel = CardCopy.STOP, onClick = onStop)
            .semantics { contentDescription = CardCopy.STOP },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Stop, null, Modifier.size(20.dp), tint = c.bg)
    }
}

private val KeySize = 34.dp

private const val TITLE_MAX = 12

@Composable
private fun cardWidth() = minOf(CardWidth, (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(240).dp)

/** カードの下部が [fixed] の場合、[height] の高さ (ダイアログ カード)、それ以外の場合、内容は最高 [height] (タスク カード) です。 */
@Composable
private fun CardFrame(height: Dp, fixed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.testTag("capsule-card").width(cardWidth()).then(if (fixed) Modifier.height(height) else Modifier.heightIn(max = height))
            .glass(c, CardShape, need = false).padding(12.dp),
        content = content,
    )
}

/** タイトル バー (押してカードをドラッグします。[CapsuleCard] の onHandle を参照): 左側にミカ アイコン、中央にタイトルとステータス、右側に折りたたみます。 */
@Composable
private fun Header(title: String, sub: String, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Sub, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

/** アイドル カードのタイトル バー: 「「...」とチャット · 新しい会話」または「新しい会話 · 最後のチャットを続行」;会話がない場合は、「新しい会話」のみが存在します。 */
@Composable
private fun IdleHeader(card: CardView.Chat, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    val continuing = !card.fresh && card.conversationId != null
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (continuing) tr(R.string.card_continuing, card.title.orEmpty().let(ScreenCopy::plain).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }) else CardCopy.NEW_CHAT,
                Modifier.weight(1f, fill = false), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (card.conversationId != null) {
                Text(" · ", style = Sub, color = c.ink3)
                TextLink(if (continuing) CardCopy.NEW_CHAT else CardCopy.CONTINUE_LAST) { actions.freshChat(continuing) }
            }
        }
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

@Composable
private fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MikaTheme.colors.ink3)
    }
}

/** タスク カード: アプリまたは Web ページで現在操作している内容。コンテンツの高さに応じて、中央のセクションは左側に小さな画面の高さ、右側にテキストの高さのものを選択します（ステップの高さは最大でも小さな画面と同じです）。 */
@Composable
private fun Detail(
    card: CardView.Detail, preview: Bitmap?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit,
) {
    val c = MikaTheme.colors
    val r = card.run
    val sub = listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · ")
    CardFrame(height, fixed = false) {
        Header(ScreenCopy.plain(r.run.goal).ifBlank { "Mika" }, sub, actions, handle)
        val screen = preview?.takeIf { CapsuleCards.showsScreen(card.content) }
        // ステップ部の高さの上限＝小画面の高さになります。画面がない場合、メイン画面のアスペクト比に応じて同じ高さが計算され、画面が来たときに画面がジャンプすることはありません。
        val config = LocalConfiguration.current
        val stepsMax = ScreenWidth / (screen?.let(::ratio) ?: ratio(config.screenWidthDp, config.screenHeightDp))
        Row(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (screen != null) MiniScreen(screen) { actions.zoom(true) }
            Column(Modifier.weight(1f)) {
                val content = card.content
                // 作品のステップは個別に配置されており（小さな画面の高さまで自分でロールします）、クリックするためのボタンはありません。
                if (content is CardContent.Working) Steps(content.steps, stepsMax) else {
                    // テキストが収まらない場合は、この段落内をスクロールしてください。クリックしたいボタンを下に置き、そのままスクロールしないでください。
                    Column(
                        Modifier.testTag("capsule-scroll").weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (content) {
                            // は作業時に上に別途配置されます
                            is CardContent.Working -> Unit
                            is CardContent.Takeover -> {
                                Text(content.need, style = Strong, color = c.warn)
                                if (content.body.isNotEmpty()) Text(content.body, style = Body, color = c.ink2)
                                content.queueLine?.let { Text(it, style = Sub, color = c.ink3) }
                            }
                            is CardContent.Confirm -> {
                                Text(content.title, style = Strong, color = c.warn)
                                Text(content.body, style = Body, color = c.ink2)
                                Text(ConfirmationCopy.NOTE, style = Sub, color = c.ink3)
                            }
                            is CardContent.Question -> Text(content.prompt, style = Body, color = c.ink)
                            is CardContent.Held -> Text(content.why, style = Body, color = c.ink2)
                            // 終わったのは対話カード ここではない
                            is CardContent.Result -> Unit
                        }
                    }
                    Pinned(content, r.run.key, actions)
                }
            }
        }
        input()
        FooterLinks(actions, CardCopy.SEE_PROCESS to { actions.openMika(r.run.conversationId) })
    }
}

/**
 * バックグラウンドで実行されたままになっているページのカード: タイトル「バックグラウンド Web ページ · 残り 1 時間 52 分」、リアルタイム画面 (最新のフレームが表示されています、クリックして拡大)、
 * サイトとその会話がどの会話からのものであるかを確認するには、下の [会話を開く] をクリックして [Web ページを閉じる] をクリックします。コンテンツを高く押します。残りの時間は毎分歩きます。
 */
@Composable
private fun KeptCard(card: CardView.Kept, preview: Bitmap?, actions: CardActions, height: Dp, handle: Modifier) {
    val c = MikaTheme.colors
    val p = card.page
    val now by produceState(System.currentTimeMillis(), p.id) {
        while (true) {
            delay(KEPT_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    CardFrame(height, fixed = false) {
        Header(tr(R.string.kept_card_title), tr(R.string.kept_card_left, KeptPageCopy.duration(p.closesAt - now)), actions, handle)
        Column(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) {
                val image = remember(preview) { preview.asImageBitmap() }
                Box(
                    Modifier.testTag("kept-live").fillMaxWidth().aspectRatio(ratio(preview)).clip(ScreenShape)
                        .border(1.dp, c.glassEdge, ScreenShape).background(Color.Black)
                        .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN) { actions.zoom(true) }
                        .semantics { contentDescription = tr(R.string.kept_card_live) },
                ) {
                    Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            Text(p.host, style = Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(tr(R.string.kept_card_from, ScreenCopy.plain(p.goal).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }), tr(R.string.kept_card_live))
                    .joinToString(" · "),
                style = Sub, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).testTag("kept-open")) { Action(tr(R.string.kept_open_chat), ButtonKind.Tonal) { actions.openMika(p.conversationId) } }
            // 閉じるは、他のボタンとは区切られた、赤文字の薄赤色のボタンです。
            Box(
                Modifier.weight(1f).height(40.dp).testTag("kept-close").clip(MikaShapes.Pill).background(c.badSoft)
                    .clickable(role = Role.Button, onClick = actions::closeKeptPage),
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.kept_close), style = Strong, color = c.bad) }
        }
    }
}

private const val KEPT_TICK_MS = 30_000L

/** がクリックしてほしいボタン (引継ぎ、同意、オプション、続行) はカードの中央セクションの下に固定されており、一緒に回転しません。 */
@Composable
internal fun Pinned(content: CardContent?, key: String, actions: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content) {
            is CardContent.Takeover -> {
                Spacer(Modifier.height(0.dp))
                if (content.canTakeOver) Action(CardCopy.TAKE_OVER, ButtonKind.Primary) { actions.takeOver(key) }
                Action(CardCopy.STOP, ButtonKind.Tonal) { actions.stop(key) }
            }
            is CardContent.Confirm -> {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.APPROVE_ONCE_ONLY, ButtonKind.Primary) { actions.approve(key, content.key) }
                Action(CardCopy.DECLINE, ButtonKind.Tonal) { actions.decline(key, content.key) }
            }
            is CardContent.Question -> if (content.options.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                content.options.forEach { o -> Action(o, ButtonKind.Tonal) { actions.answer(key, o) } }
            }
            is CardContent.Held -> if (content.resumable) {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.RESUME, ButtonKind.Primary) { actions.resume(key) }
            }
            else -> Unit
        }
    }
}

/**
 * ダイアログ カード: ダイアログは中央で前後にスクロールします。回答または同意を求められる場合、ボタンは前後の下に固定されており、その下には入力ボックスと「ミカに戻る」があります。
 * は、タスクがない場合のアイドル カードです (タイトル バーで新しい会話を開くことができます)。
 */
@Composable
private fun ChatCard(
    card: CardView.Chat, thread: ChatThread?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit,
) {
    val r = card.run
    CardFrame(height) {
        if (r == null) IdleHeader(card, actions, handle)
        else Header(
            ScreenCopy.plain(thread?.title ?: r.run.goal).ifBlank { "Mika" },
            listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · "),
            actions, handle,
        )
        Box(Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth()) {
            // 会話が変更されると (アイドル カードが新しい会話に切り替わる)、その会話の最新の質問からもう一度読みます。
            if (thread != null) key(card.target) { ThreadList(thread, card.conversationId, actions) }
        }
        if (r != null) Pinned(card.content, r.run.key, actions)
        input()
        FooterLinks(actions, card.target?.let { id -> CardCopy.BACK_TO_MIKA to { actions.openMika(id) } })
    }
}

/** カード下部に [primary] とカプセルをオフにする操作を表示します。右上の閉じるボタンはカードを折り畳むだけです。 */
@Composable
private fun FooterLinks(actions: CardActions, primary: Pair<String, () -> Unit>?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        primary?.let { (text, onClick) ->
            TextLink(text, onClick = onClick)
            Text("·", style = Sub, color = MikaTheme.colors.ink3)
        }
        TextLink(CardCopy.HIDE_CAPSULE, MikaTheme.colors.ink3, actions::hideCapsule)
    }
}

/**
 * 会話の前後 (古いものが一番上): 開くと、あなたが言った最新の文が一番上で停止します。一番上までスクロールしてさらにいくつかのラウンドをロードすると、リストは各項目のキーに従って同じ場所で停止します。
 * 下向きの長い単語は、あなたが既に最下位にいる場合にのみ下に続きます (会話ページと同じ)。新しい質問が出てきたらスクロールしてください。 [footer] は最後のものの後に来ます。
 */
@Composable
internal fun ThreadList(thread: ChatThread, conversationId: String?, actions: CardActions, footer: (@Composable () -> Unit)? = null) {
    val c = MikaTheme.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = thread.anchor)
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        following = !state.canScrollForward
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollForward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    val anchorKey = thread.items.getOrNull(thread.anchor)?.key
    var lastAnchor by remember { mutableStateOf(anchorKey) }
    LaunchedEffect(anchorKey) {
        if (anchorKey == lastAnchor) return@LaunchedEffect
        lastAnchor = anchorKey
        state.scrollToItem(thread.anchor)
    }
    // の回答は Markdown に従ってフォーマットされており (ストリーミング中にバックグラウンドで解析されます)、高さは配置後にのみ変更されます。つまり、単語の数ではなく、項目の数と最後の項目の高さに応じて変わります。
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.size
        }.collect { (n, _) ->
            if (following && !state.isScrollInProgress && n > 0) state.scrollToItem(n - 1, END_OFFSET)
        }
    }
    if (thread.hasEarlier) LaunchedEffect(thread.items.size) {
        snapshotFlow { state.firstVisibleItemIndex == 0 }.first { it }
        actions.loadEarlier()
    }
    LazyColumn(Modifier.testTag("capsule-thread").fillMaxWidth().fillMaxHeight(), state = state) {
        items(thread.items, key = { it.key }) { item ->
            when (item) {
                // 画面付きの文章: サムネイルと「現時点・アプリ・瞬間の画面」、追記で別のバブルを追加
                is ThreadItem.User -> BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    val maxWidth = maxWidth
                    Column(horizontalAlignment = Alignment.End) {
                        UserMessage(conversationId, item.text, ThreadThumbWidth) { text ->
                            Text(
                                text,
                                Modifier.widthIn(max = maxWidth * 0.8f).background(c.accent, BubbleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                                style = Body, color = c.onAccent,
                            )
                        }
                        // 挿入された単語の宛先 (受信、追加、次のもの)
                        item.note?.let { Text(it, Modifier.padding(top = 2.dp, end = 4.dp), style = Sub, color = c.ink3) }
                    }
                }
                is ThreadItem.Note -> Text(
                    item.text, Modifier.padding(bottom = 4.dp), style = Sub,
                    color = if (item.kind.needsYou) c.warn else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // テーブルとコード ブロックは、カードを伸ばさずに独自の領域で左右にスライドできます。リンクは別のタスクで開くことができ、フローティング レイヤー内でクリックすることもできます。
                is ThreadItem.Answer -> if (!item.cut) {
                    MarkdownText(item.text, Body.copy(color = c.ink), Modifier.fillMaxWidth().padding(bottom = 8.dp), streaming = item.streaming)
                } else Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    // 中断により中断された段落: 明るい色、以下の短い単語の行 (会話ページと同じ文)
                    MarkdownText(item.text, Body.copy(color = c.ink2), Modifier.fillMaxWidth(), streaming = false)
                    Text(tr(R.string.steer_interrupted_reply), Modifier.padding(top = 2.dp), style = Sub, color = c.ink3)
                }
            }
        }
        // 往復の後のセクション (ブラウザー ページ パネルの現在のステップ): 最後のセクションに従い、一番下に到達したら一緒に下に進みます。
        footer?.let { f -> item(FOOTER_KEY) { f() } }
    }
}

private const val FOOTER_KEY = "thread-footer"

/** カード内のスクリーンショットのサムネイルの幅。 */
private val ThreadThumbWidth = 78.dp

/** 最下部に到達したら、最後の項目までスクロールし、さらに多くのピクセルを下に移動して、リストから実際の最下部にクリップします。 */
private const val END_OFFSET = 1_000_000

/**
 * を使用する手順 (古いものが上にあります): 内容によると、最大値は [max] です。どうしてもやめられない場合は、最新のステップを一番下に残し、一番上の古いステップを削除して、一番上でフェードアウトします。上にスクロールして以前のものを表示します。
 * が新しいステップを踏み出すとき、すでに最下位にあり、その後下に移動します。上に上げても引きません（[ThreadList]と同じ）。
 * [tags] と [steps] は 1 対 1 に対応します。そのステップ (「Web ページ」「アプリ」) はどこですか。ステップの前に小さなラベルとして書かれています。そうでない場合は null です。
 */
@Composable
internal fun Steps(steps: List<String>, max: Dp, tags: List<String?> = emptyList()) {
    val c = MikaTheme.colors
    // 逆方向に配置: 項目 0 が最新のステップで、一番下に表示されます。
    val state = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollBackward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(steps.size) {
        if (following && !state.isScrollInProgress) state.scrollToItem(0)
    }
    val fade = with(LocalDensity.current) { FadeEdge.toPx() }
    LazyColumn(
        Modifier.testTag("capsule-steps").fillMaxWidth().heightIn(max = max)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // 上記の初期の手順があります: 上部のフェードアウト
                if (state.canScrollForward) drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade), blendMode = BlendMode.DstIn)
            },
        state = state,
        reverseLayout = true,
    ) {
        // 古いものから新しいものまでのシリアル番号をキーとして使用します。上を見上げると、新しいステップが現れ、見ていたいくつかのステップは同じ場所に留まります。
        items(steps.size, key = { steps.lastIndex - it }) { j ->
            val i = steps.lastIndex - j
            val current = j == 0
            StepItem(if (current) StepState.Current else StepState.Done, first = i == 0, last = current, dotY = 11.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    tags.getOrNull(i)?.let { t ->
                        Text(
                            t, Modifier.padding(top = 3.dp, end = 5.dp).background(c.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                            style = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium), color = c.accent, maxLines = 1,
                        )
                    }
                    Text(
                        steps[i], Modifier.padding(vertical = 2.dp), style = if (current) Step.copy(fontWeight = FontWeight.Medium) else Step,
                        color = if (current) c.ink else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val FadeEdge = 28.dp

@Composable
private fun Action(text: String, kind: ButtonKind, onClick: () -> Unit) {
    MikaButton(text, onClick, Modifier.fillMaxWidth(), kind = kind, size = ButtonSize.Medium)
}

@Composable
private fun TextLink(text: String, color: Color = MikaTheme.colors.accent, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        style = Sub.copy(fontSize = 13.sp), color = color,
    )
}

/** 仮想画面の小さな画像、クリックして拡大します。 */
@Composable
private fun MiniScreen(frame: Bitmap, onZoom: () -> Unit) {
    val c = MikaTheme.colors
    val image = remember(frame) { frame.asImageBitmap() }
    Box(
        Modifier.testTag("capsule-screen").width(ScreenWidth).aspectRatio(ratio(frame)).clip(ScreenShape)
            .border(1.dp, c.glassEdge, ScreenShape).background(c.surface)
            .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN, onClick = onZoom)
            .semantics { contentDescription = CardCopy.SCREEN },
    ) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Text(
            CardCopy.SCREEN_LABEL, Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp), color = Color.White,
        )
    }
}

/** 拡大画面：クリックするとカードに戻ります。 */
@Composable
private fun Zoomed(frame: Bitmap, actions: CardActions) {
    val image = remember(frame) { frame.asImageBitmap() }
    val width = minOf(ZoomWidth, (LocalConfiguration.current.screenWidthDp * 0.72f).dp)
    Box(
        Modifier.testTag("capsule-zoom").width(width).aspectRatio(ratio(frame)).glass(MikaTheme.colors, CardShape, need = false)
            .clickable(role = Role.Image, onClickLabel = CardCopy.ZOOMED) { actions.zoom(false) }
            .semantics { contentDescription = CardCopy.ZOOMED },
    ) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Text(
            CardCopy.ZOOM_HINT, Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = Sub, color = Color.White,
        )
    }
}

/** 画面のアスペクト比は妥当な範囲内です (異常なサイズによるカードの損傷を防ぐため)。 */
private fun ratio(b: Bitmap): Float = ratio(b.width, b.height)

private fun ratio(width: Int, height: Int): Float = if (height <= 0) 0.45f else (width.toFloat() / height).coerceIn(0.3f, 1.8f)
