package com.wangzan101.mika.capsule

import com.wangzan101.mika.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.ceil

/** カプセルの外側の影のために残されたエッジ: フローティング レイヤー ウィンドウのサイズはコンテンツに応じて変更され、影はウィンドウの内側に描画されます。残りが少ないほど、ウィンドウの左側にある透明なエッジが狭くなり、下のアプリをブロックします。 */
internal val CapsuleShadowRoom = 8.dp
private val CapsuleHeight = 40.dp
private val BarHeight = 48.dp
private val LogoSize = 30.dp
private const val FADE_MS = 400
/** 画面をキャプチャすると、明るさと消えていくまでの時間が点滅します。 */
private const val FLASH_ALPHA = 0.8f
private const val FLASH_MS = 320
/** カプセルが拡張および収縮するのにかかる時間。 */
private const val MORPH_MS = 260
/** 長押しして話すとどのくらいズームインしますか、外側のリングの厚さはどれくらいですか、ズームインするのにどのくらい時間がかかりますか? */
private const val HOLD_SCALE = 0.06f
private val HoldRing = 3.dp
private const val HOLD_MS = 180
/** 仕事における一周の時間の長さ。 */
private const val SPIN_MS = 1_200
private val CapsuleText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val BarDetail = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val CountText = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)

/** カプセルの発言を修正しました。 */
object CapsuleCopy {
    val STOP: String get() = tr(R.string.capsule_stop)
    val EXPAND: String get() = tr(R.string.capsule_expand)
    val TALK: String get() = tr(R.string.capsule_talk)
    val SWAP_SIDE: String get() = tr(R.string.capsule_swap_side)
    const val IDLE = "Mika"

    /** 画面を読み取るときのアイコンの説明: 何をしているのか、それを処理するかどうか、および結果。数個分の個数はすでに [CapsuleView.Shown.text] にあります。 */
    fun describe(view: CapsuleView.Shown): String = when {
        view.kind == CapsuleKind.Idle -> IDLE
        view.kind.needsYou -> tr(R.string.capsule_desc_needs, view.text)
        view.kind == CapsuleKind.Running -> tr(R.string.capsule_desc_running, view.text)
        view.kind == CapsuleKind.Held || view.kind == CapsuleKind.Cancelled -> tr(R.string.capsule_desc_other, view.text)
        else -> "Mika ${view.text}"
    }
}

/**
 * フローティングカプセルの表示と操作。単押しでカードを開き、ダブルタップと読上げのスクリーンショット操作は入力欄にプレビューを追加します。
 * 画面は利用者が送信を押すまで会話へ渡しません。ドラッグ位置と長押し音声入力は呼出し側で管理します。
 */
@Composable
fun CapsuleContent(
    view: CapsuleView.Shown,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onHandBack: () -> Unit,
    modifier: Modifier = Modifier,
    side: DockSide = DockSide.Right,
    tapLabel: String = CapsuleCopy.EXPAND,
    onSwapSide: (() -> Unit)? = null,
    alpha: Float = 1f,
    onSendScreen: (() -> Unit)? = null,
    flash: Int = 0,
    holding: Boolean = false,
    onTalk: (() -> Unit)? = null,
    talkLabel: String = "",
    onCompleteStop: (() -> Unit)? = null,
) {
    // フェードと復元には時間をかけてください。アニメーションをオフにする場合は直接変更してください
    val shown = if (MikaTheme.reduceMotion) alpha else animateFloatAsState(alpha, tween(FADE_MS), label = "fade").value
    val a11y = Modifier.semantics {
        customActions = listOfNotNull(
            onSwapSide?.let { f -> CustomAccessibilityAction(CapsuleCopy.SWAP_SIDE) { f(); true } },
            onSendScreen?.let { f -> CustomAccessibilityAction(ScreenCopy.SEND_SCREEN) { f(); true } },
            onTalk?.let { f -> CustomAccessibilityAction(talkLabel) { f(); true } },
            onCompleteStop?.let { f -> CustomAccessibilityAction(tr(R.string.quit_action)) { f(); true } },
        )
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        if (view.kind == CapsuleKind.HandedOver) TakeoverBar(view, onStop, onHandBack)
        else Capsule(view, side == DockSide.Right, onTap, tapLabel, a11y, shown, flash, holding)
    }
}

/** カプセルまたは展開されたカプセル: 同じガラス底で、目に見える幅が 2 つの間でゆっくりと変化します (描画時のみ変化します。[Morph] を参照)。 [end]は右側に付いています(右側のアイコン)。 */
@Composable
private fun Capsule(view: CapsuleView.Shown, end: Boolean, onTap: () -> Unit, tapLabel: String, a11y: Modifier, alpha: Float, flash: Int, holding: Boolean) {
    val c = MikaTheme.colors
    // 長押しして話す: 少しズームインすると、外側に青い境界線が表示されます (影の境界線に描画され、ウィンドウのサイズは変わりません)
    val press = if (MikaTheme.reduceMotion) (if (holding) 1f else 0f)
        else animateFloatAsState(if (holding) 1f else 0f, tween(HOLD_MS), label = "hold").value
    val need = view.kind.needsYou || view.needCount > 0
    // スクリーンショット: 白色光が明るい状態から消えます。これは、スクリーンショットを撮るときに画面が点滅することを意味します。アニメーションがオフの場合は点滅しません。
    val glow = remember { Animatable(0f) }
    val still = MikaTheme.reduceMotion
    val morph = rememberMorph(animate = !still)
    LaunchedEffect(flash) {
        if (flash == 0 || still) return@LaunchedEffect
        glow.snapTo(FLASH_ALPHA)
        glow.animateTo(0f, tween(FLASH_MS))
    }
    Box(
        Modifier.graphicsLayer {
            scaleX = 1f + HOLD_SCALE * press
            scaleY = 1f + HOLD_SCALE * press
        }.drawBehind {
            if (press <= 0f) return@drawBehind
            val ring = HoldRing.toPx()
            val r = size.height / 2 + ring / 2
            drawRoundRect(
                c.accent.copy(alpha = press), Offset(-ring / 2, -ring / 2), Size(size.width + ring, size.height + ring),
                CornerRadius(r, r), style = Stroke(ring),
            )
        }.then(if (holding) Modifier.testTag("capsule-holding") else Modifier),
    ) {
        Box(
            Modifier.testTag(if (view.pill) "capsule" else "capsule-ball")
                .morphGlass(morph, end, c, need, alpha)
                .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap)
                .then(if (view.pill) Modifier else Modifier.semantics { contentDescription = CapsuleCopy.describe(view) })
                .then(a11y)
                .morphLayout(morph, end),
        ) {
            if (view.pill) PillRow(view, end, need)
            else Box(Modifier.size(CapsuleHeight), contentAlignment = Alignment.Center) { CapsuleLogo() }
            if (glow.value > 0f) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = glow.value)))
        }
        // 外輪とコーナーマークは底部とともに消えません。引っ込めると、マークの側面にくっついて、まだ完成していない幅に追従しません。
        if (!view.pill) Box(Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart).size(CapsuleHeight)) {
            Ring(view.kind, need, Modifier.matchParentSize())
            Badge(view, need, Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp))
        }
    }
}

/** 展開されたカプセル内の行: アイコン、ステータス マーク、単語。右に貼り付けると、アイコンはカプセルのときと同じ一番右の位置になります。 */
@Composable
private fun PillRow(view: CapsuleView.Shown, end: Boolean, need: Boolean) {
    val c = MikaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
    // 同じこと、同じ状態では、他の文は幅が広くなるだけです。この段落で最も幅が広い瞬間を書き留めてください。
    var widest by remember(view.widthEpoch) { mutableIntStateOf(0) }
    val minWidth = with(LocalDensity.current) { widest.toDp() }
    Row(
        Modifier.widthIn(min = minWidth.coerceAtMost(maxWidth), max = maxWidth)
            .onSizeChanged { if (it.width > widest) widest = it.width }
            .height(CapsuleHeight)
            .padding(start = if (end) 13.dp else 5.dp, end = if (end) 5.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (end) Alignment.End else Alignment.Start),
    ) {
        if (!end) CapsuleLogo()
        Leading(view.kind)
        Text(
            view.text, Modifier.weight(1f, fill = false), style = CapsuleText,
            color = if (need) c.warn else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (end) CapsuleLogo()
    }
}

/** テキストの前のステータス マーク: 処理したい場合は呼吸ポイントを意味し、処理が完了した場合はチェックマーク、処理が完了していない場合は感嘆符、停止している場合は停止し、バックグラウンド Web ページが実行されている場合は緑色の点になります。 */
@Composable
private fun Leading(kind: CapsuleKind) {
    val c = MikaTheme.colors
    when {
        kind.needsYou -> BreathingDot(c)
        kind == CapsuleKind.Done -> Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.ok)
        kind == CapsuleKind.Failed -> Icon(MikaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.Cancelled -> Icon(MikaIcons.Stop, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.KeptPage -> LiveDot(c)
        else -> Unit
    }
}

/** 背景の Web ページが実行中です。緑色の点とその外側に薄緑色の円が表示されます。 */
@Composable
private fun LiveDot(c: MikaColors) {
    Box(Modifier.size(14.dp).testTag("capsule-live-dot"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).background(c.okSoft, MikaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.ok, MikaShapes.Pill))
    }
}

/** はあなたを必要としています。オレンジ色の点、外側の明るいオレンジ色の円、息を吐き、吸います。アニメーションをオフにすると動きません。 */
@Composable
private fun BreathingDot(c: MikaColors) {
    val halo = breathe(still = 0f)
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = halo }.background(c.warnSoft, MikaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.warnSolid, MikaShapes.Pill))
    }
}

/** は吐き出しと吸い込みを行います (0..1 往復)。アニメーションをオフにすると、[still] で停止します。 */
@Composable
private fun breathe(still: Float): Float = if (MikaTheme.reduceMotion) still else rememberInfiniteTransition(label = "breathe").animateFloat(
    0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathe",
).value

/** アイコンの外側の円: 作業中は青い円弧が回転し (アニメーションがオフになっている場合は一周静止します)、その他の状況では灰色の円が表示され、オレンジ色の円は 1 回の呼吸と 1 回の呼吸で処理する必要があります。残りはそうではありません。 */
@Composable
private fun Ring(kind: CapsuleKind, need: Boolean, modifier: Modifier) {
    val c = MikaTheme.colors
    val color = when {
        need -> c.warnSolid
        kind == CapsuleKind.Running -> c.accent
        kind == CapsuleKind.Held -> c.ink3
        else -> return
    }
    val spin = kind == CapsuleKind.Running && !need && !MikaTheme.reduceMotion
    val angle = if (!spin) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin",
    ).value
    val strength = if (need) 0.45f + 0.55f * breathe(still = 1f) else 1f
    Canvas(modifier.testTag("capsule-ring")) {
        val stroke = 2.dp.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        if (spin) {
            drawArc(c.accentSoft, 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
            drawArc(color, angle - 90f, 100f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawArc(color.copy(alpha = strength), 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
        }
    }
}

/** アイコンの隅にあるマーク: 項目が複数ある場合は、項目の数を記入します (オレンジ色、その他の条件の場合は灰色、その他の場合は青色)。項目が 1 つだけ完了している場合は緑色のチェック マークが表示され、完了していない場合は灰色のチェック マークが表示され、停止されています。 */
@Composable
private fun Badge(view: CapsuleView.Shown, need: Boolean, modifier: Modifier) {
    val c = MikaTheme.colors
    if (view.count >= 2) {
        val fill = when {
            need -> c.warnSolid
            view.kind == CapsuleKind.Held -> c.ink3
            else -> c.accent
        }
        Box(
            modifier.testTag("capsule-count").background(c.surface, MikaShapes.Pill).padding(1.5.dp)
                .sizeIn(minWidth = 15.dp, minHeight = 15.dp).background(fill, MikaShapes.Pill).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("${view.count}", style = CountText, color = c.onAccent, maxLines = 1)
        }
        return
    }
    val (icon, tint) = when (view.kind) {
        CapsuleKind.Done -> MikaIcons.Check to c.ok
        CapsuleKind.Failed -> MikaIcons.Exclaim to c.ink3
        CapsuleKind.Cancelled -> MikaIcons.Stop to c.ink3
        else -> return
    }
    Box(
        modifier.testTag("capsule-badge").size(16.dp).background(c.surface, MikaShapes.Pill).padding(1.5.dp)
            .background(tint, MikaShapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(9.dp), tint = c.surface)
    }
}

/**
 * カプセルとカプセルの間の幅はゆっくりと変化し、ペイントされたガラス片のみが変化し、レイアウトはフレームごとに変更されません。フローティング レイヤー ウィンドウはコンテンツのサイズに従い、レイアウトが変更されると、ウィンドウを再配置してキャンバスを変更する必要があります。
 * はフレームごとに変化します。 [target] は現時点のコンテンツの幅、[width] は表示幅 (アニメーション)、[hold] はアニメーション中にレイアウトが占める幅 (開始点と終了点の間の幅)、
 * そのため、ウィンドウを広げると一気に最大幅に達し、狭くする場合は塗装が完了するまで縮みません。ウィンドウは変形中に最大 1 回変更されます。最初に表示されるとき、およびアニメーションがオフになっているときは、コンテンツの幅がそのまま表示されます。
 */
private class Morph {
    val width = Animatable(0f)
    var target by mutableIntStateOf(0)
    var hold by mutableIntStateOf(0)
}

@Composable
private fun rememberMorph(animate: Boolean): Morph {
    val m = remember { Morph() }
    LaunchedEffect(m.target, animate) {
        val t = m.target
        if (t == 0) return@LaunchedEffect
        if (!animate || m.width.value == 0f) m.width.snapTo(t.toFloat())
        else {
            m.hold = maxOf(ceil(m.width.value).toInt(), t)
            m.width.animateTo(t.toFloat(), tween(MORPH_MS))
        }
        m.hold = t
    }
    return m
}

/** のレイアウトは、[Morph.hold] の幅とコンテンツを占めます。コンテンツはドッキングされた側に配置されます ([end] は右側に接続されています)。コンテンツは内側に拡大し、側面に縮小します。アイコンは移動しません。 */
private fun Modifier.morphLayout(m: Morph, end: Boolean): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    if (p.width != m.target) m.target = p.width
    val w = maxOf(m.hold, p.width)
    layout(w, p.height) { p.place(if (end) w - p.width else 0, 0) }
}

/**
 * ガラス底部 ([glass] と同じ)、表示される幅とドッキングされている側のみを描画します。クリックすると、影、トリミング、背景色、ストロークが表示されます。幅が変更されると、レイヤーのみが再描画されます。
 * [alpha] 明るい背景と内側のアイコン。
 */
private fun Modifier.morphGlass(m: Morph, end: Boolean, c: MikaColors, need: Boolean, alpha: Float): Modifier =
    graphicsLayer {
        this.alpha = alpha
        shadowElevation = 8.dp.toPx()
        ambientShadowColor = c.floatShadow.copy(alpha = 0.25f)
        spotShadowColor = c.floatShadow.copy(alpha = 0.3f)
        shape = EdgePill(m.width.value, end)
        clip = true
    }.drawWithContent {
        val pill = EdgePill(m.width.value, end)
        val o = pill.createOutline(size, layoutDirection, this)
        drawOutline(o, c.surface.copy(alpha = 0.95f))
        if (need) drawOutline(o, c.warnSoft)
        drawContent()
        val stroke = (if (need) 1.dp else 0.5.dp).toPx()
        drawOutline(EdgePill(m.width.value, end, inset = stroke / 2).createOutline(size, layoutDirection, this),
            if (need) c.warnSolid.copy(alpha = 0.5f) else c.glassEdge, style = Stroke(stroke))
    }

/** は、幅が [width] (0 以上、ブロック全体を押す) で片側 ([end] の場合は右側) に近いカプセル形状です。 [inset] は閉じられています (ストロークを描画するため)。 */
private class EdgePill(private val width: Float, private val end: Boolean, private val inset: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = if (width > 0f) width.coerceAtMost(size.width) else size.width
        val atRight = end != (layoutDirection == LayoutDirection.Rtl)
        val left = if (atRight) size.width - w else 0f
        val r = size.height / 2 - inset
        return Outline.Rounded(RoundRect(left + inset, inset, left + w - inset, size.height - inset, CornerRadius(r, r)))
    }
}

/** アプリが手にあると、上部に「あなたが処理します」という行があり、その下に小さな単語の行があり、その右側には「もうだめです」と「完了しました」と表示されます。 */
@Composable
private fun TakeoverBar(view: CapsuleView.Shown, onStop: () -> Unit, onHandBack: () -> Unit) {
    val c = MikaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp
    Row(
        Modifier.testTag("capsule-bar")
            .glass(c, MikaShapes.Pill, need = false)
            .widthIn(max = maxWidth)
            .height(BarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsuleLogo()
        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}) {
            Text(view.text, style = CapsuleText, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            view.detail?.let { Text(it, style = BarDetail, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(
            Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onStop)
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(CapsuleCopy.STOP, style = CapsuleText.copy(fontWeight = FontWeight.Normal), color = c.ink2, maxLines = 1)
        }
        MikaButton(stringResource(R.string.action_hand_back), onHandBack, size = ButtonSize.Small)
    }
}

/** カプセル上のミカのアイコン (丸)。 */
@Composable
private fun CapsuleLogo() {
    MikaLogo(LogoSize, corner = LogoSize / 2)
}

/**
 * 他のアプリ上に浮かぶガラスの底部: 基礎となるアプリの画面がフローティング レイヤー ウィンドウに表示されないようにぼかし、ほぼ不透明なカード底部、細いストローク、および浮遊シャドウを使用します。
 * 必要に応じて、薄いオレンジのレイヤーを重ね、ストロークをオレンジに変更します。
 */
internal fun Modifier.glass(c: MikaColors, shape: Shape, need: Boolean): Modifier =
    shadow(8.dp, shape, clip = false, ambientColor = c.floatShadow.copy(alpha = 0.25f), spotColor = c.floatShadow.copy(alpha = 0.3f))
        .clip(shape)
        .background(c.surface.copy(alpha = 0.95f))
        .then(if (need) Modifier.background(c.warnSoft).border(1.dp, c.warnSolid.copy(alpha = 0.5f), shape) else Modifier.border(0.5.dp, c.glassEdge, shape))
