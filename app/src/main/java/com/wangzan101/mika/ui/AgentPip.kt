package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.view.Display
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.PipPhysics.Corner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.floatShadow

/** 小さなウィンドウ幅: 折りたたみ/展開。高さは画面（画面がない場合はメイン画面）のアスペクト比に基づいて計算され、下部が切れることなく画面全体が完全に表示されます。 */
private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** メイン画面のアスペクト比 (システム バーを含む実ピクセルによる): 仮想画面はメイン画面のサイズに従って構築されます。画像がない場合は、まず画像に合わせて小ウィンドウのサイズを調整します。 */
private fun mainScreenAspect(context: Context): Float {
    val m = DisplayMetrics()
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
    return if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 2.2f
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * フローティング ピクチャー イン ピクチャー: ダイアログ上でカバーされ、エージェントの仮想画面のリアルタイム画像のみが表示され、クリックのみを表示できます。テキストやボタンは配置されておらず、やりたいことはすべてダイアログ カード内にあります。
 * ステータスはフレーム ライトにのみ依存します。動作中は薄い緑色のエッジが表示され、必要な場合はオレンジ色の呼吸ハローが表示されます (テイクオーバーが開始されると、[ChatViewModel] によって自動的に拡張されます)。
 * は通常、トップバーと最新ニュースをブロックすることなく、右下隅の入力フィールドの上で停止します。
 * 1:1 でドラッグして手に追従します。手を放し、速度に応じて着地点を最も近いコーナーに投影します (臨界減衰スプリング、[PipPhysics] を参照)。
 * クリックすると展開し、×または外側をクリックすると折りたたまれます。必要な場合は、クリックして [onAttention] に渡します (折りたたんでそのカードまでスクロールします)。
 * 展開時に左上隅にある「小さい画面をオフにする」([onClose]): このタスクが終了する前は、右下隅に小さなボタン ([PipUi.closed]) しかありません。
 * クリックして開きます ([onReopen])。それを処理したいときは、小さなボタンがオレンジ色に変わり、息を吹き込み、クリックして[onAttention]に渡します。
 * [reduceMotion] (システムがアニメーションをオフにする) の場合、スプリング、スケーリング、呼吸はなく、その位置に直接移動します。
 * [topInset]/[bottomInset] は、トップバーと入力ボックスが占めるピクセルの高さであり、小さなウィンドウはそれらの下で止まりません。
 */
@Composable
fun AgentPip(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit = {},
    onReopen: () -> Unit = {},
    onUnmute: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = if (reduceMotion) tween<Float>(150) else tween(300)
        // がオフになります。右下隅の小さなボタンのみが残ります (タスク完了後の数秒は残りません)。
        AnimatedVisibility(
            pip.visible && pip.closed && pip.mode != PipMode.Done, Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) {
            val bottom = with(LocalDensity.current) { bottomInset.toDp() }
            ClosedPip(pip.mode == PipMode.NeedsYou, reduceMotion, Modifier.padding(end = 12.dp, bottom = bottom + 12.dp), onReopen, onAttention)
        }
        AnimatedVisibility(pip.visible && !pip.closed && pip.expanded, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(MikaTheme.colors.floatShadow.copy(alpha = 0.12f)).pointerInput(Unit) { detectTapGestures { onCollapse() } })
        }
        AnimatedVisibility(pip.visible && !pip.closed, enter = fadeIn(fade), exit = fadeOut(if (reduceMotion) tween(150) else tween(500))) {
            PipWindow(
                pip, frame, webContent, reduceMotion,
                areaWidth = constraints.maxWidth.toFloat(), areaHeight = constraints.maxHeight.toFloat(),
                topInset = topInset, bottomInset = bottomInset, onExpand = onExpand, onCollapse = onCollapse, onAttention = onAttention,
                onClose = onClose,
                onUnmute = onUnmute,
            )
        }
    }
}

/**
 * 小さな画面がオフになった後に残る小さなボタン。通常は「画面を表示」と書き、クリックして開きます。処理したい場合は、オレンジ色に点灯し、「処理を待っています」と表示されます。
 * クリックして、対処するよう求められているカードまでスクロールします (小さな画面はオフになりますが、求められている内容を見逃すことはありません)。
 */
@Composable
private fun ClosedPip(needsYou: Boolean, reduceMotion: Boolean, modifier: Modifier, onReopen: () -> Unit, onAttention: () -> Unit) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(50)
    val breath = if (needsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "closedBreathe").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "closedBreathe",
        ).value
    } else 1f
    val label = stringResource(if (needsYou) R.string.pip_closed_needs_you else R.string.pip_reopen)
    Row(
        modifier.floatShadow(c, shape).background(c.glass, shape)
            .border(if (needsYou) 2.dp else 0.5.dp, if (needsYou) c.warnSolid.copy(alpha = breath) else c.glassEdge, shape)
            .clip(shape)
            .clickable(onClickLabel = label, role = Role.Button) { if (needsYou) onAttention() else onReopen() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("pip-closed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (needsYou) Box(Modifier.size(7.dp).alpha(breath).background(c.warnSolid, CircleShape))
        else Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink2)
        Text(label, style = MikaType.Hint, color = if (needsYou) c.warn else c.ink2)
    }
}

@Composable
private fun PipWindow(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit,
    onUnmute: () -> Unit,
) {
    val density = LocalDensity.current
    val sizeSpec = if (reduceMotion) snap() else tween<androidx.compose.ui.unit.Dp>(350, easing = Ease)
    // サイズは画面のアスペクト比に従います。モバイル ゲームとデスクトップ Web ページは水平にすることができ、高さは固定幅によって決まります。
    // 全体像が収まります。収まらない場合は、画像全体が比例して縮小されます。
    val context = LocalContext.current
    val screenAspect = remember { mainScreenAspect(context) }
    val landscapeFrame = frame != null && frame.width > frame.height
    val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect
    val target = run {
        val margin = with(density) { 24.dp.toPx() }
        val availableWidth = with(density) { (areaWidth - margin).coerceAtLeast(1f).toDp() }
        val availableHeight = with(density) { (areaHeight - topInset - bottomInset - margin).coerceAtLeast(1f).toDp() }
        val base = when {
            landscapeFrame -> if (pip.expanded) 320.dp else 220.dp
            else -> if (pip.expanded) ExpandedWidth else CollapsedWidth
        }
        val inset = if (pip.expanded) 8.dp else 6.dp
        val contentWidth = minOf(base - inset, (availableWidth - inset).coerceAtLeast(1.dp),
            (availableHeight - inset).coerceAtLeast(1.dp) / aspect)
        contentWidth + inset to contentWidth * aspect + inset
    }
    val w by animateDpAsState(target.first, sizeSpec, label = "pipW")
    val h by animateDpAsState(target.second, sizeSpec, label = "pipH")
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    val bounds = PipPhysics.Bounds(areaWidth, areaHeight, wPx, hPx, with(density) { 12.dp.toPx() }, topInset, bottomInset)
    val currentBounds by rememberUpdatedState(bounds)
    val motionOff by rememberUpdatedState(reduceMotion)
    val expanded by rememberUpdatedState(pip.expanded)
    val expand by rememberUpdatedState(onExpand)
    val needsYou by rememberUpdatedState(pip.mode == PipMode.NeedsYou)
    val attend by rememberUpdatedState(onAttention)

    var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
    // ドラッグまたはスプリング時の位置。空の場合は、角に貼り付くことを意味します（展開および折りたたむと、角はサイズとともに貼り付けられます）。
    var free by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    /** は [start] からコーナー [c] まで速度でバウンドします (vx、vy)。アニメーションがオフになると、その位置に直接配置されます。 */
    fun springTo(c: Corner, start: Offset, vx: Float, vy: Float) {
        settle?.cancel()
        corner = c
        if (motionOff) {
            free = null
            return
        }
        settle = scope.launch {
            val spring = PipPhysics.Spring()
            var mx = PipPhysics.Motion(start.x, vx)
            var my = PipPhysics.Motion(start.y, vy)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val (tx, ty) = currentBounds.position(c)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)
                free = Offset(mx.position, my.position)
                if (spring.settled(mx, tx) && spring.settled(my, ty)) break
            }
            free = null
        }
    }

    fun release(vx: Float, vy: Float) {
        val start = free ?: return
        springTo(PipPhysics.snapCorner(start.x, start.y, vx, vy, currentBounds), start, vx, vy)
    }

    // が完了時に下 2 つのコーナーで停止した場合は、出たばかりのリザルト カードを覆わないように、最初に同じ側の上側に移動します。
    LaunchedEffect(pip.mode) {
        if (pip.mode != PipMode.Done) return@LaunchedEffect
        val up = when (corner) {
            Corner.BottomStart -> Corner.TopStart
            Corner.BottomEnd -> Corner.TopEnd
            else -> return@LaunchedEffect
        }
        springTo(up, free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }, 0f, 0f)
    }

    val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
    val outer = RoundedCornerShape(if (pip.expanded) 26.dp else 16.dp)
    val inner = RoundedCornerShape(if (pip.expanded) 22.dp else 13.dp)
    val breath = if (pip.mode == PipMode.NeedsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        ).value
    } else 1f

    Box(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }) {
        if (pip.mode == PipMode.NeedsYou) {
            // オレンジ色のハロー: ウィンドウよりも 1 つの円が大きく、ぼやけてエッジが表示されます (Android 12 以下ではぼかしがなく、明るいオレンジ色の円だけです)
            Box(
                Modifier.offset((-5).dp, (-5).dp).size(w + 10.dp, h + 10.dp).alpha(breath)
                    .blur(10.dp).background(MikaTheme.colors.warnSolid.copy(alpha = 0.55f), outer),
            )
        }
        Box(
            Modifier.testTag("pip-window").size(w, h)
                .alpha(if (pip.mode == PipMode.Done) 0.85f else 1f)
                .floatShadow(MikaTheme.colors, outer)
                .background(MikaTheme.colors.glass, outer)
                .border(0.5.dp, MikaTheme.colors.glassEdge, outer)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    // detectDragGesture を使用しないデジタル変位: タッチのしきい値を超えると、しきい値内のセグメントも追加されます。
                    // ウィンドウは、押されたときに常にグラブ位置を維持し、真の 1:1 フォローアップを実現します。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            total += change.positionChange()
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                settle?.cancel()
                            }
                            if (dragging) {
                                change.consume()
                                free = origin + total
                                tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                            }
                        }
                        if (dragging) tracker.calculateVelocity().let { v -> release(v.x, v.y) }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { if (needsYou) attend() else if (!expanded) expand() } }
                .padding(if (pip.expanded) 4.dp else 3.dp),
        ) {
            Box(Modifier.fillMaxSize().clip(inner).background(MikaTheme.colors.surface)) {
                if (frame != null) {
                    val image = remember(frame) { frame.asImageBitmap() }
                    Image(image, stringResource(R.string.pip_screen), Modifier.testTag("pip-frame").fillMaxSize(), alignment = Alignment.Center,
                        contentScale = ContentScale.Fit)
                }
            }
            if (pip.expanded) {
                // 左上角:一次性恢复当前 App 声音,再关掉小画面;不伪造与真实音频无关的开关状态
                Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PipButton(
                        icon = MikaIcons.Sound,
                        label = stringResource(R.string.pip_unmute),
                        onClick = onUnmute,
                        tag = "pip-unmute",
                    )
                    PipButton(
                        icon = MikaIcons.EyeOff,
                        label = stringResource(R.string.pip_close),
                        onClick = onClose,
                        tag = "pip-close",
                    )
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.collapse), onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, stringResource(R.string.collapse), Modifier.size(12.dp), tint = MikaTheme.colors.ink) }
            }
        }
        // ガラスフレームの外周にフレームライトを塗装
        val glow = when (pip.mode) {
            PipMode.Running -> MikaTheme.colors.ok to 2.dp
            PipMode.NeedsYou -> MikaTheme.colors.warnSolid.copy(alpha = breath) to 2.dp
            PipMode.Done -> null
        }
        if (glow != null) Box(Modifier.size(w, h).border(glow.second, glow.first, outer))
    }
}

@Composable
private fun PipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.size(26.dp)
            .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(13.dp), tint = MikaTheme.colors.ink) }
}
