package com.wangzan101.mika.ui

import androidx.compose.foundation.gestures.scrollBy
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.record.ConversationTokens
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.keepsKeyboard
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.unconsumed
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.viewer.FileViewer
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * 会話ページ: トップ バー (左側にサイドバーが開いており、中央に会話のタイトルと現在行っている内容、右側に新しい会話)、会話はトップ バーとガラス入力フィールドの間でスクロールします。
 * 「進行中」はステータスの行と、カードのない垂直のステップの行です。処理中に現在のステップで停止するように求められ、下の白いカードが表示されます。プロセスが完了すると、プロセスは 1 行になり、答えは背景に直接配置されます。
 * エージェントの仮想画面は、レイアウトを占有しない小さなフローティング ウィンドウ ([AgentPip]) で会話をカバーします。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    frames: StateFlow<Bitmap?>,
    webOpen: StateFlow<Boolean>,
    reduceMotion: Boolean,
    /** 左上隅のメニュー: サイドバーを開きます。 */
    onOpenDrawer: () -> Unit,
    /** 停止したプロンプトで「設定に移動」。 */
    openSettings: () -> Unit,
    openRun: (String) -> Unit = {},
    /** このタスクでは携帯電話の使用が必要ですが、携帯電話の操作の準備ができていません。最後のラウンドの下に行を入れて設定してください。 */
    phoneSetupNeeded: Boolean = false,
    openPhoneSetup: () -> Unit = {},
    /** は、実行レコードのソース (「ソース」カプセルとクリック後のスクリーンショット) に基づいて基準を取得します。ディスク読み取りアクティビティは、呼び出し元によってバックグラウンド スレッドに配置されます。 */
    loadSource: suspend (runId: String) -> EvidenceSource? = { null },
    openSource: (runId: String?, legacyText: String?) -> Unit = { _, _ -> },
    /** 入力フィールドを長押しして発言し、ここからセッションを開きます。 null の場合は、入力のみが可能です。 */
    voice: VoiceSource? = null,
    /** 「音声入力はまだ利用できません」の「選択に移動」。 */
    openSpeechModel: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    val others by vm.otherRunning.collectAsState()
    val keptAny by vm.keptPage.collectAsState()
    // この会話で残されたページはそのままにしておいてください
    val kept = keptAny?.takeIf { it.conversationId == ui.conversationId }
    val live by frames.collectAsState()
    val webLive by webOpen.collectAsState()
    // 仮想画面はタスクが完了するとすぐに解放されます。完了すると、小さなウィンドウが数秒間表示され、最後のフレームを確認して表示し続けます。
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastFrameWasWeb by remember { mutableStateOf(false) }
    LaunchedEffect(live, webLive, ui.pip.mode) {
        live?.let {
            lastFrame = it
            if (webLive) lastFrameWasWeb = true
            else if (ui.pip.mode != PipMode.Done) lastFrameWasWeb = false
        }
    }
    val frame = live ?: lastFrame.takeIf { ui.pip.visible && ui.pip.mode == PipMode.Done }
    val webContent = if (ui.pip.mode == PipMode.Done) lastFrameWasWeb else webLive
    var input by rememberSaveable { mutableStateOf("") }
    var voiceHolding by remember { mutableStateOf(false) }
    var editRequest by remember { mutableIntStateOf(0) }
    var composerHeight by rememberSaveable { mutableIntStateOf(0) }
    var topHeight by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // は最新ニュースを追い続けるだけです。この状態は、リストの位置とともにページ全体に保存されます。
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var previousScrollMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var previousConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val tailThresholdPx = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(listState, tailThresholdPx) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.nearTail(tailThresholdPx) }.collect { (scrolling, nearTail) ->
            if (scrolling || wasScrolling) followingTail = nearTail
            wasScrolling = scrolling
        }
    }

    // チャットに戻ったときに、同じコンテンツは再スクロールされません。新しい進行状況は、最後に続く場合にのみスクロールされます。
    val last = ui.exchanges.lastOrNull()
    val rowCount = chatItemCount(ui.exchanges, kept, phoneSetupNeeded, hasBlocked = ui.blocked != null)
    val scrollMarker = "${ui.conversationId}:${ui.exchanges.size}:${last?.steps?.size}:${last?.steers?.size}:${last?.status?.hashCode()}:${ui.blocked?.hashCode()}"
    LaunchedEffect(scrollMarker) {
        if (previousScrollMarker == scrollMarker) return@LaunchedEffect
        val firstEntry = previousScrollMarker == null
        val changedConversation = previousConversation != ui.conversationId
        previousScrollMarker = scrollMarker
        previousConversation = ui.conversationId
        if (changedConversation) followingTail = true
        if (!firstEntry && !changedConversation && !followingTail) return@LaunchedEffect
        if (rowCount > 0) {
            if (firstEntry || changedConversation || reduceMotion) listState.scrollToItem(rowCount - 1)
            else listState.animateScrollToItem(rowCount - 1)
        }
    }
    // ストリーミングテキストが増加すると下に進みます。ユーザーが積極的に上方向にスクロールしているときに後退しないでください。
    LaunchedEffect(last?.draft?.length) {
        if (last?.draft.isNullOrEmpty() || !followingTail || listState.isScrollInProgress) return@LaunchedEffect
        val layout = listState.layoutInfo
        val end = layout.totalItemsCount - 1
        val draft = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return@LaunchedEffect
        val tailGap = draft.offset + draft.size - layout.viewportEndOffset
        if (tailGap > with(density) { 240.dp.roundToPx() }) return@LaunchedEffect
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val offset = (draft.size - viewport + with(density) { 12.dp.roundToPx() }).coerceAtLeast(0)
        listState.scrollToItem(end, offset)
    }

    // 入力フィールドが高くなる (主に入力方法のポップアップ、添付ファイル、または複数行入力が原因): もともと、最新ニュースを追うとき、会話の流れは同じ距離まで上がりました。
    // 最後の項目はまだ入力フィールドに添付されています。ユーザーが上にスクロールしているときは動きません。リストは短くなると自動的に撤回されます。
    var lastComposerHeight by remember { mutableIntStateOf(composerHeight) }
    LaunchedEffect(composerHeight) {
        val grown = composerHeight - lastComposerHeight
        lastComposerHeight = composerHeight
        if (grown > 0 && followingTail && !listState.isScrollInProgress) listState.scrollBy(grown.toFloat())
    }

    // 終了した各ラウンドの操作の概要: 各ラウンドの最後の行にこのラウンドのトークンを書き込み、一番上の列がアイドル状態のときにこのダイアログを書き込みます。
    val endedRuns = ui.exchanges.filter { !it.status.isActive() }.mapNotNull { it.runId }
    val runMetrics = rememberRunMetrics(endedRuns)

    val c = MikaTheme.colors
    val backdrop = rememberGlassBackdrop()
    val context = LocalContext.current
    /** 長押しされたメッセージ。メニューが開きます (折りたたみアニメーションは再生後にのみクリアされます)。 */
    var menu by remember { mutableStateOf<MessageTarget?>(null) }
    /** 「テキストの選択」項目を選択します。システムの長押し選択に戻ります。ダイアログを変更するとクリアされます。 */
    var selecting by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    /** メニューの外観の進行状況: 0 は存在しないことを意味し、1 は完全に存在しないことを意味します。アニメーションがオフになると、アニメーションは直接配置されます。 */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(menu) {
        if (menu != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    /** メニューを折りたたむ: 表示されるアニメーションを逆方向に再生し、再生後にクリアします。 [then] その場でやってください。 */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            menu = null
        }
    }
    val menuHost = remember(selecting) { MessageMenuHost(selecting) { menu = it } }
    val menuOpen = menu != null
    PageBackground {
        CompositionLocalProvider(LocalMessageMenu provides menuHost) {
            LazyColumn(
                Modifier.fillMaxSize().behindMenu(menuOpen) { lift.value }.voiceDim(voiceHolding, reduceMotion).glassSource(backdrop),
                state = listState,
                contentPadding = with(density) {
                    PaddingValues(
                        start = 16.dp, end = 16.dp, top = topHeight.toDp() + 4.dp, bottom = composerHeight.toDp() + 12.dp,
                    )
                },
            ) {
                if (ui.exchanges.isEmpty() && ui.blocked == null) {
                    item("empty") { EmptyState(ui.conversationId) { input = it } }
                }
                ui.exchanges.forEachIndexed { i, ex ->
                    val backgroundNote = i == ui.exchanges.lastIndex && ui.operatingPhone
                    exchange(
                        i, ex, ui.conversationId, ui.takeoverStage, vm, reduceMotion, backgroundNote, openRun, loadSource, openSource, kept,
                        tokens = ConversationTokens.turn(ex.runId?.let(runMetrics::get)),
                    )
                }
                if (phoneSetupNeeded && ui.exchanges.isNotEmpty()) item("phone-setup") { PhoneSetupLine(openPhoneSetup) }
                ui.blocked?.let { problems ->
                    item("blocked") {
                        NeedCard(
                            stringResource(R.string.chat_cannot_start),
                            stringResource(R.string.chat_missing, problems.joinToString(stringResource(R.string.list_sep)) { StatusCopy.banner(it).title }),
                        ) {
                            CardButton(stringResource(R.string.ok_got_it), onClick = vm::dismissBlocked)
                            CardButton(stringResource(R.string.chat_go_settings), primary = true, onClick = { vm.dismissBlocked(); openSettings() })
                        }
                    }
                }
            }
        }

        // 実行中のタスクがなく、この会話が 1 つのページのバックグラウンドで実行されている場合: 一番上の列に「1 つの Web ページがバックグラウンドで維持されている」と書き込みます。タスクがない場合は、この会話に使用されるトークンの数を書き込みます。
        // (会話の総数は最後の行のみです。不要な場合はこの行を削除してください)
        val sub: Pair<String, ChatCopy.Tone?>? = ChatCopy.subtitle(last, last?.let(vm::livePlace))
            ?: kept?.let { ChatCopy.KEPT_PAGE to ChatCopy.Tone.Ok }
            ?: ChatTokensCopy.conversationSubtitle(endedRuns, runMetrics)?.let { it to null }
        Column(Modifier.behindMenu(menuOpen) { lift.value }.onSizeChanged { topHeight = it.height }) {
            TopBar(
                ChatCopy.APP_TITLE,
                left = MikaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = onOpenDrawer,
                // 他の会話で実行中のタスクがあります (並行して): メニュー上の小さな点。「進行中」サイドバーでそれに切り替えることができます
                leftBadge = others.isNotEmpty(),
                right = { BarIconButton(MikaIcons.New, stringResource(R.string.chat_new), { vm.newConversation() }, enabled = vm.canSwitch()) },
                subtitle = sub?.first,
                subtitleColor = when (sub?.second) {
                    ChatCopy.Tone.Accent -> c.accent
                    ChatCopy.Tone.Warn -> c.warn
                    ChatCopy.Tone.Ok -> c.ok
                    null -> Color.Unspecified
                },
            )
        }

        Composer(
            Modifier.align(Alignment.BottomCenter).behindMenu(menuOpen) { lift.value }.onSizeChanged { composerHeight = it.height },
            backdrop = backdrop,
            text = input,
            onText = { input = it },
            mode = ui.composer,
            hasHistory = ui.exchanges.isNotEmpty(),
            onSend = { if (vm.send(input, check)) input = "" },
            onStop = vm::cancel,
            attachPanel = if (ui.attachEnabled && ui.composer == ComposerMode.Idle) ({
                AttachPanelHost(ui.attachments, onPicked = vm::attach, onToggle = vm::toggleAttachment)
            }) else null,
            attachments = ui.attachments,
            importing = ui.importing,
            rejected = ui.rejectedAttachments,
            overLimit = ui.overLimit,
            reduceMotion = reduceMotion,
            onRemove = vm::removeAttachment,
            onDismissRejected = vm::dismissRejected,
            quote = ui.quote,
            onRemoveQuote = vm::clearQuote,
            voice = voice,
            // の発言の後には既存の単語が続きます。実行中にタスクが送信されると、タスクが挿入されます。
            onVoiceText = { input = HoldToTalk.join(input, it) },
            onPickSpeech = openSpeechModel,
            steerTarget = last?.goal?.let(ChatCopy::plainTitle),
            onVoiceHolding = { voiceHolding = it },
            editRequest = editRequest,
        )

        AgentPip(
            pip = ui.pip,
            frame = frame,
            webContent = webContent,
            reduceMotion = reduceMotion,
            topInset = topHeight.toFloat(),
            bottomInset = composerHeight.toFloat(),
            onExpand = vm::expandPip,
            onCollapse = vm::collapsePip,
            // 小さな画面をオフにします。このタスクが終了する前に、右下隅の小さなボタンだけが残ります。他にどこを見るべきかを思い出させる
            onClose = {
                vm.closePip()
                Toast.makeText(context, context.getString(R.string.pip_closed_hint), Toast.LENGTH_SHORT).show()
            },
            onReopen = vm::reopenPip,
            onUnmute = vm::unmutePip,
            // が処理したい場合は、小さなウィンドウをクリックして、それを片付け、処理したいカードまでスクロールします（最後の質問と回答では、ファイルカードとその後に設定する行しかない場合があります）
            onAttention = {
                vm.attendPip()
                val count = listState.layoutInfo.totalItemsCount
                val after = (if (last?.files?.isNotEmpty() == true) 1 else 0) + (if (phoneSetupNeeded) 1 else 0)
                val card = count - 1 - after
                if (card >= 0) scope.launch { if (reduceMotion) listState.scrollToItem(card) else listState.animateScrollToItem(card) }
            },
        )

        // 長押しメッセージのメニューが上部にあります: プレーンテキストをコピーします。入力ボックスの上に引用符を置きます。テキストを選択し、長押しして選択してシステムに戻します。
        // 完了したラウンドは、ここから分岐することができます。
        menu?.let { m ->
            val pending = withdrawableSteer(ui.exchanges, m.key)
            val canEdit = input.isBlank() && ui.quote == null && ui.attachments.isEmpty() && !ui.importing
            val branchTurn = vm.branchTurn(m.key)
            val retryTurn = vm.retryTurn(m.key)
            MessageMenu(
                m, progress = { lift.value },
                onCopy = { closeMenu { copyMessage(context, m.text) } },
                onQuote = { closeMenu { vm.quote(m.from, m.text) } },
                onSelect = {
                    closeMenu {
                        selecting = m.key
                        Toast.makeText(context, context.getString(R.string.chat_select_hint), Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { closeMenu() },
                onBranch = branchTurn?.let { turn -> {
                    closeMenu {
                        scope.launch {
                            val id = vm.branchFrom(turn)
                            val copy = vm.branchCopy.value
                            val message = when {
                                id == null -> R.string.chat_branch_failed
                                copy?.truncated == true -> R.string.chat_branch_attachments_skipped
                                else -> R.string.chat_branch_done
                            }
                            if (id != null) input = ""
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                            vm.clearBranchCopy()
                        }
                    }
                } },
                onBranchRetry = retryTurn?.let { turn -> {
                    closeMenu {
                        scope.launch {
                            val result = vm.branchBefore(turn)
                            val message = when {
                                result == null -> R.string.chat_branch_failed
                                result.needsReattach -> R.string.chat_branch_attachments_readd
                                vm.branchCopy.value?.truncated == true -> R.string.chat_branch_attachments_skipped
                                else -> R.string.chat_branch_done
                            }
                            if (result != null) input = result.prefill
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                            vm.clearBranchCopy()
                        }
                    }
                } },
                onEdit = if (pending != null && canEdit && editableSteer(pending.text)) ({ closeMenu {
                    vm.editSteer(pending.id)?.let { input = it; editRequest++ }
                } }) else null,
                onWithdraw = pending?.let { n -> ({ closeMenu { vm.withdrawSteer(n.id) } }) },
            )
        }
    }
}

/** 最後の項目は非常に長い返信になる可能性があり、その冒頭を見ただけでは、ユーザーが最後まで読んだことにはなりません。 */
private fun LazyListState.nearTail(thresholdPx: Int): Boolean {
    val layout = layoutInfo
    val end = layout.totalItemsCount - 1
    val tail = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return false
    return tail.offset + tail.size - layout.viewportEndOffset <= thresholdPx
}

// ---------- 空のダイアログ ----------

/** はじめまして、「ミカさんにしてほしいことは何ですか？」および例のリスト。例をクリックして入力ボックスに入力し、修正して再送信してください。 */
@Composable
private fun EmptyState(conversation: String?, onPick: (String) -> Unit) {
    val c = MikaTheme.colors
    val hour = remember(conversation) { LocalTime.now().hour }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
            Text(ChatCopy.greeting(hour), style = MikaType.LargeTitle, color = c.ink)
            Text(ChatCopy.EMPTY_PROMPT, Modifier.padding(top = 6.dp), style = EmptyLead, color = c.ink2)
        }
        MikaCard(Modifier.padding(top = 28.dp)) {
            ChatCopy.examples.forEachIndexed { i, ex ->
                ExampleRow(exampleIcon(ex.kind), ex.text, first = i == 0) { onPick(ex.text) }
            }
        }
    }
}

private fun exampleIcon(kind: ChatCopy.ExampleKind): ImageVector = when (kind) {
    ChatCopy.ExampleKind.App -> MikaIcons.Apps
    ChatCopy.ExampleKind.Web -> MikaIcons.Globe
    ChatCopy.ExampleKind.File -> MikaIcons.Doc
    ChatCopy.ExampleKind.Schedule -> MikaIcons.Clock
    ChatCopy.ExampleKind.Device -> MikaIcons.Alarm
}

@Composable
private fun ExampleRow(icon: ImageVector, text: String, first: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawBehind {
                val y = 0.25.dp.toPx()
                drawLine(c.hair, Offset(52.dp.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Text(text, Modifier.weight(1f), style = ExampleText, color = c.ink)
    }
}

// ---------- Q&A ----------

private fun LazyListScope.exchange(
    index: Int, ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    openRun: (String) -> Unit, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    kept: KeptPage? = null,
    /** このラウンドで使用されたトークンの数 (終了後および実行概要を読んだ後にのみ利用可能)。 */
    tokens: Long? = null,
) {
    if (ex.goal.isNotEmpty()) item("u$index") { UserBubble(ex.goal, conversationId, key = "u$index") }
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    if (active) {
        if (!answering) item("s$index") { WorkBlock(ex, reduceMotion, backgroundNote, openRun) }
    } else if (ex.steps.isNotEmpty() || ex.runId != null) {
        item("s$index") { DoneLine(ex, reduceMotion, openRun, tokens) }
    }
    // タスクの進行中の介入: ユーザーのオリジナルの言葉と、その場所を説明する下の小さな文字の行。
    // 中断前に終了した返信は通常どおり表示されます。中断によって中断されたセクションはそのまま残ります (明るい色で「新しいメッセージによって中断されました」とマークされます)。次に中断が起こります。
    // モデルにまだ与えられていない間投詞が、増加する回答の背後にリストされています。その回答は、回答が到着する前に書かれたものであり、返信ではありません。
    fun steer(n: SteerNote) {
        n.finished?.takeIf(String::isNotBlank)?.let { done -> item("a$index-${n.id}") { FinishedReply(done, "a$index-${n.id}") } }
        n.interrupted?.takeIf(String::isNotBlank)?.let { cut -> item("i$index-${n.id}") { InterruptedReply(cut) } }
        item("m$index-${n.id}") { UserBubble(n.text, conversationId, steerLabel(n.state), "m$index-${n.id}") }
    }
    val (joined, waiting) = steersAroundDraft(ex.steers)
    joined.forEach(::steer)
    ex.draft?.takeIf(String::isNotBlank)?.let { draft -> item("d$index") { DraftText(draft, ex.waitingForModel) } }
    waiting.forEach(::steer)
    item("r$index") { StatusCard(ex.status, stage, vm, ex.runId, loadSource, openSource, "r$index") }
    // このラウンドの後、ページがバックグラウンドで実行されたままになっています。ページがまだ開いている場合は、緑色の線が表示されます (オフにすることができます)。オフになっている場合は、理由があります。
    keptPageRow(index, ex, kept, vm::closeKeptPage)
    if (ex.files.isNotEmpty()) item("f$index") { FileCards(ex.files) }
    learningNotes(index, ex, vm)
}

/**
 * の間投詞は、モデル ([SteerState.unconsumed]) に引き渡される前の最初の文と、モデルに渡された後の文の 2 つの段落に分かれています。
 * が導き出した答えは、2 つの段落の間にランク付けされています。
 */
internal fun steersAroundDraft(steers: List<SteerNote>): Pair<List<SteerNote>, List<SteerNote>> {
    val visible = steers.filterNot { it.state == SteerState.Withdrawn }
    val first = visible.indexOfFirst { it.state.unconsumed }.let { if (it < 0) visible.size else it }
    return visible.take(first) to visible.drop(first)
}

/**
 * [key] の長押しは、携帯電話が進行中の質疑応答を中断し、モデルが奪われていない場合に与えられます。メニューには、それに応じて取り消しと編集が表示されます (編集については、[editableSteer] も参照してください)。
 * はコンピュータによって追加されません ([SteerNote.remote])。編集者は現在のタスクにのみ属する単語を携帯電話の中断に変換し、次のタスクに転送される可能性があります。撤退するか否かはコンピュータが判断します。
 */
internal fun withdrawableSteer(exchanges: List<Exchange>, key: String): SteerNote? = exchanges.mapIndexedNotNull { i, ex ->
    if (ex.status.isActive()) ex.steers.firstOrNull { key == "m$i-${it.id}" && it.state.unconsumed && !it.remote } else null
}.firstOrNull()

internal fun ExchangeStatus.isActive() =
    this !is ExchangeStatus.Done && this !is ExchangeStatus.Failed && this !is ExchangeStatus.Cancelled

/**
 * ユーザーの言葉: 青いバブル、角丸 20、右下隅角 6、最大幅 80%。 [note] が間投詞の場所です。
 * がユーザーの現在の画面を取得すると、スクリーンショットのサムネイルと「現在の画面 · アプリ · モーメント」が表示され、下の吹き出し内に追記が表示されます ([UserMessage] を参照)。
 * にアタッチメントが付属している場合、上部に写真グリッドとファイル カードがあります ([SentAttachments] を参照)。アプリの表示ページをクリックして表示します。書きたい場合は下に吹き出しを作成するとパスの線が表示されなくなります。
 * が前のメッセージを引用する場合、先頭のメッセージが引用されます ([SentQuote] を参照)。バブルを長押しすると、テキストのコピー/引用/選択がポップアップ表示されます ([key] でどれを識別するかについては、[MessageArea] を参照してください)。
 * はブラウザ ページ上のページについて質問しました。一番上には「ウェブページ・ウェブサイト」と選択した単語（[SentPage]を参照）が表示されますが、ページ説明の行は表示されません。
 */
@Composable
internal fun UserBubble(text: String, conversationId: String?, note: String? = null, key: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paged = remember(text) { AttachedPage.parse(text) }
    val quoted = remember(text) { QuoteMessage.parse(paged.words) }
    val attached = remember(text) { AttachmentMessage.parse(quoted.words) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        Column(horizontalAlignment = Alignment.End) {
            paged.page?.let { SentPage(it, max) }
            quoted.quote?.let { SentQuote(it, max) }
            if (attached.hasAttachments) {
                SentAttachments(attached, conversationId, max) { item ->
                    val id = conversationId ?: return@SentAttachments
                    scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                }
            }
            if (attached.hasAttachments && attached.words.isNotEmpty()) Spacer(Modifier.size(6.dp))
            if ((!attached.hasAttachments || attached.words.isNotEmpty()) && (paged.page == null || quoted.words.isNotEmpty())) UserMessage(conversationId, attached.words.takeIf { attached.hasAttachments } ?: quoted.words, ChatThumbWidth) { words ->
                // テキストを選択すると、青色の背景で選択された背景色は白で半透明になり、ハンドルは青色のままになります（バブルの外側に描画されます）
                val colors = MikaTheme.colors
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.onAccent.copy(alpha = 0.35f))) {
                    MessageArea(
                        key, QuoteMessage.From.You, words, "selectable-user",
                        Modifier.widthIn(max = max).background(colors.accent, MikaShapes.UserBubble),
                        lifted = { LiftedMessage(MikaShapes.UserBubble, colors.accent) { UserBubbleText(words) } },
                    ) { UserBubbleText(words) }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 3.dp, end = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3) }
        }
    }
}

@Composable
private fun UserBubbleText(words: String) {
    Text(words, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = BubbleText, color = MikaTheme.colors.onAccent)
}

/** 長押ししてメッセージを持ち上げます: [color] 下、影付き。長すぎる場合（画面の半分まで）、上部のみが露出し、下部が切り取られます。 */
@Composable
private fun LiftedMessage(shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.floatShadow(MikaTheme.colors, shape).background(color, shape).clip(shape)
            .wrapContentHeight(Alignment.Top, unbounded = true),
    ) { content() }
}

/** 会話ページのスクリーンショットのサムネイルの幅。 */
private val ChatThumbWidth = 110.dp

/** 生成中の回答: 完成した回答と同様に明るい灰色の背景に配置されます。 */
@Composable
private fun DraftText(text: String, generating: Boolean) {
    MarkdownText(
        text, AnswerText.copy(color = MikaTheme.colors.ink),
        Modifier.fillMaxWidth().padding(bottom = 12.dp).answerSurface(), streaming = generating,
    )
}

/** が口を挟んで、すでに完成していた回答をモデルに渡す前に渡しました。回答と同じベースと文字があり、長押しするとコピー、引用、テキストの選択が可能です。 */
@Composable
private fun FinishedReply(text: String, key: String) {
    Box(Modifier.padding(bottom = 4.dp)) {
        AnswerArea(key, text, Modifier.fillMaxWidth().answerSurface()) {
            MarkdownText(text, AnswerText.copy(color = MikaTheme.colors.ink), streaming = false)
        }
    }
}

/**
 * ミカの答え: 長押しすると、コピー/引用/テキストの選択がポップアップ表示されます ([MessageArea] を参照)。コピーして引用するものは、Markdown 記号が削除されたプレーン テキストです。
 * [modifier] が答えの一番下です。持ち上げられたものには、白いカードに同じ文字が書かれています。テキストを選択する場合、選択ハンドルは別のポップアップ ウィンドウに表示され、ハンドルをドラッグしてもサイドバーは表示されません。
 */
@Composable
private fun AnswerArea(key: String, answer: String, modifier: Modifier, content: @Composable () -> Unit) {
    val plain = remember(answer) { MarkdownModel.toPlainText(answer) }
    MessageArea(
        key, QuoteMessage.From.Mika, plain, "selectable-answer", modifier,
        lifted = {
            LiftedMessage(MikaShapes.AnswerBubble, MikaTheme.colors.surface) {
                MarkdownText(answer, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), streaming = false)
            }
        },
    ) { content() }
}

/** 中断により中断された返信: 回答と同じ背景に、単語が 2 番目の色で表示され、「新しいメッセージにより中断されました」の下に小さな単語が並んでいます。 */
@Composable
private fun InterruptedReply(text: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        MarkdownText(text, AnswerText.copy(color = MikaTheme.colors.ink2), Modifier.fillMaxWidth().answerSurface(), streaming = false)
        Text(stringResource(R.string.steer_interrupted_reply), Modifier.padding(top = 3.dp, start = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
    }
}

/** ミカの答え: 非常に明るいグレー、左下隅。 */
@Composable
private fun Modifier.answerSurface(): Modifier =
    background(MikaTheme.colors.answer, MikaShapes.AnswerBubble).padding(horizontal = 14.dp, vertical = 12.dp)

// ---------- 進捗 ----------

/**
 * 進行中: ステータスの行 (円 + 現在の進行状況 + 時間)、その下のステップの縦線 (完了した灰色の点、現在の青い点。停止して待っているとき、現在のステップはオレンジ色の点)。
 * バックグラウンドで電話を操作しているときの小さな言葉の最後の行。
 */
@Composable
private fun WorkBlock(ex: Exchange, reduceMotion: Boolean, backgroundNote: Boolean, openRun: (String) -> Unit) {
    val c = MikaTheme.colors
    val paused = ChatCopy.paused(ex.status)
    val elapsed = rememberElapsed(ex.startedAt)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(
                Modifier.size(16.dp), spinning = !paused && !reduceMotion,
                track = if (paused) c.warnSoft else c.accentSoft, head = if (paused) c.warnSolid else c.accent,
            )
            Text(
                ChatCopy.progressLine(ex), Modifier.weight(1f), style = NowText, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (elapsed != null) Text(ChatCopy.clock(elapsed), style = ClockText, color = c.ink3)
        }
        if (ex.steps.isNotEmpty()) StepLine(ex.steps, ex.stepActions, live = true, paused = paused)
        // の実行中に、実行中のプロセスに入ることができます (ページは秒単位で拡大し続けます)。終了後、「N ステップ完了・プロセスの表示」の行に含まれます。
        ex.runId?.let { id ->
            Text(
                stringResource(R.string.chat_view_process),
                Modifier.padding(top = 2.dp).clip(MikaShapes.Pill).clickable(role = Role.Button) { openRun(id) }
                    .padding(horizontal = 4.dp, vertical = 4.dp).testTag("work-view-process"),
                style = DoneText.copy(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline), color = c.ink3,
            )
        }
        if (backgroundNote) {
            Row(
                Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Eye, null, Modifier.size(15.dp), tint = c.ink3)
                Text(ChatCopy.BACKGROUND_NOTE, style = NoteText, color = c.ink3)
            }
        }
    }
}

/** [startedAt] からの時間。毎秒更新されます。開始時間が不明な場合は null (0)。 */
@Composable
private fun rememberElapsed(startedAt: Long): Long? {
    if (startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return (now - startedAt).coerceAtLeast(0L)
}

private enum class Dot { Done, Current, Paused, Dim }

/**
 * の縦ラインステップ。 [live] の場合、その時点のタスクのステータスに応じて、最後のステップが青またはオレンジの点でマークされます。実行中は、最新の [ChatCopy.VISIBLE_STEPS] ステップのみがリストされます。
 * 以前の収穫ライン、クリックするとすべて表示されます。
 */
@Composable
private fun StepLine(steps: List<StepRow>, actions: Map<Int, List<StepAction>>, live: Boolean, paused: Boolean) {
    val c = MikaTheme.colors
    var all by rememberSaveable { mutableStateOf(!live) }
    // どのステップがクリックされたか: この画面でシリアル番号を押してマークすると、リストが長くなっても乱雑になりません。
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val hidden = if (all) 0 else (steps.size - ChatCopy.VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().drawBehind {
            val x = StepRail.toPx()
            drawLine(c.hair, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        },
    ) {
        if (hidden > 0) {
            StepItem(ChatCopy.earlierSteps(hidden), "", Dot.Dim, current = false, link = true, onClick = { all = true })
        }
        steps.drop(hidden).forEachIndexed { i, row ->
            val key = hidden + i
            val isLast = key == steps.lastIndex
            val dot = when {
                live && isLast && paused -> Dot.Paused
                live && isLast && row.state == StepState.Run -> Dot.Current
                row.state == StepState.Dim -> Dot.Dim
                else -> Dot.Done
            }
            val open = expanded[key] == true
            val detail = actions[row.step].orEmpty()
            StepItem(
                row.text, row.subtitle, dot, current = dot == Dot.Current || dot == Dot.Paused,
                actions = detail, open = open,
                // 特定のアクションがある場合にのみクリックしてください。クリックすると、このステップが実際に何を行ったのか、実行された内容、およびその結果が表示されます。
                onClick = if (detail.isEmpty()) null else ({ expanded[key] = !open }),
            )
        }
    }
}

@Composable
private fun StepItem(
    text: String, subtitle: String, dot: Dot, current: Boolean, link: Boolean = false, onClick: (() -> Unit)? = null,
    actions: List<StepAction> = emptyList(), open: Boolean = false,
) {
    val c = MikaTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(if (open) R.string.collapse else R.string.chat_expand_steps), onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 28.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StepRail * 2), contentAlignment = Alignment.Center) { StepDot(dot) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = if (current) StepCurrentText else StepText,
                color = when {
                    link -> c.accent
                    current -> c.ink
                    else -> c.ink2
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // クリックして開きます: このステップで実際に行われたいくつかのこと、「何が行われたか、何が行われたか、結果」が 1 つずつ表示されます。ステートメントは実行中のプロセス ページと同じです。
            if (open) actions.forEach { a ->
                Text(
                    ChatCopy.actionLine(a), Modifier.padding(top = 2.dp),
                    style = MikaType.Hint, color = if (a.failed) c.bad else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // クリックできるのはアクションのあるものだけです。右側にある小さな矢印が、クリックすると下を向きます。
        if (actions.isNotEmpty()) DirectionalIcon(
            MikaIcons.Chevron, null,
            Modifier.padding(start = 6.dp).size(12.dp).graphicsLayer { rotationZ = if (open) if (rtl) -90f else 90f else 0f },
            tint = c.ink3,
        )
    }
}

/** ステップポイント: 8dp ソリッド。現在のステップの外側にある 3dp ライト ハロー。 */
@Composable
private fun StepDot(dot: Dot) {
    val c = MikaTheme.colors
    val (fill, ring) = when (dot) {
        Dot.Done -> c.ink3 to null
        Dot.Dim -> c.hair to null
        Dot.Current -> c.accent to c.accentSoft
        Dot.Paused -> c.warnSolid to c.warnSoft
    }
    // 背景色が最初に縦線を覆い、その後ドットがきれいになります
    val bg = c.bg
    Box(
        Modifier.size(14.dp).drawBehind {
            val center = Offset(size.width / 2, size.height / 2)
            if (ring != null) {
                drawCircle(bg, 7.dp.toPx(), center)
                drawCircle(ring, 7.dp.toPx(), center)
            }
            drawCircle(fill, 4.dp.toPx(), center)
        },
    )
}

/** 回転円: 上部に単色が付いている明るい色のリング。 [spinning] が false (アニメーションをオフにして停止して待機する) の場合、その場で停止します。 */
@Composable
internal fun Spinner(modifier: Modifier, spinning: Boolean, track: Color, head: Color) {
    val turn = if (!spinning) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    ).value
    Box(
        modifier.graphicsLayer { rotationZ = turn }
            .border(2.dp, track, CircleShape)
            .border(
                2.dp,
                Brush.sweepGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 0.63f to head, 0.88f to head, 0.89f to Color.Transparent),
                CircleShape,
            ),
    )
}

/**
 * が完了すると、プロセスにより「N ステップ実行 · 所要時間 · 12,000 トークン」 (クリックして以下のステップを展開) と「プロセスの表示」 (タイムラインを入力) という 1 行が表示されます。
 * が手順を実行していない場合 (数単語チャットしただけ)、トークンを書き込むだけです。 [tokens] が null の場合 (概要が読み取られていない、サービス プロバイダーが使用状況を報告していない)、書き込まないでください。
 */
@Composable
private fun DoneLine(ex: Exchange, reduceMotion: Boolean, openRun: (String) -> Unit, tokens: Long? = null) {
    val tokenText = tokens?.let(ChatTokensCopy::amount)
    val c = MikaTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ex.status is ExchangeStatus.Done) Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.ink3)
            if (ex.steps.isNotEmpty()) {
                Text(
                    stepsSummary(ex) + tokenText?.let { " · $it" }.orEmpty(),
                    Modifier.clickable(onClickLabel = if (open) stringResource(R.string.chat_collapse_steps) else stringResource(R.string.chat_expand_steps)) { open = !open },
                    style = DoneText, color = c.ink3,
                )
            } else if (tokenText != null) {
                Text(tokenText, style = DoneText, color = c.ink3)
            }
            ex.runId?.let { id ->
                Text(
                    // 灰色の斜体と下線: 答えに溶け込んでいて、はっきりと見えます。
                    stringResource(R.string.chat_view_process), Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { openRun(id) }.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = DoneText.copy(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline), color = c.ink3,
                )
            }
        }
        Reveal(open && ex.steps.isNotEmpty(), reduceMotion) { StepLine(ex.steps, ex.stepActions, live = false, paused = false) }
    }
}

/** クリックされたコンテンツ: 通常はアニメーションとともに展開および縮小されますが、アニメーションがオフになると直接表示および非表示になります。 */
@Composable
private fun Reveal(visible: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    if (reduceMotion) {
        if (visible) content()
    } else {
        AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { content() }
    }
}

// ---------- ステータスと結果 ----------

@StringRes
fun takeoverReasonLabel(reason: String): Int? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    "decision" -> R.string.takeover_reason_decision
    else -> null
}

/** Q&A 現状: 求められているのは白いカード([NeedCard])で、結果は背景に直接配置されます。 */
@Composable
private fun StatusCard(
    status: ExchangeStatus, stage: Takeover.Stage, vm: ChatViewModel, runId: String?,
    loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    /** は、長押しメニューに応答してこの項目を認識します ([MessageArea] を参照)。 */
    key: String,
) {
    when (status) {
        ExchangeStatus.Working -> Unit
        ExchangeStatus.WaitingUnlock -> NeedCard(stringResource(R.string.chat_wait_unlock), stringResource(R.string.chat_unlock_body))
        is ExchangeStatus.NeedsTakeover -> {
            val label = takeoverReasonLabel(status.reason)
            val title = if (label != null) stringResource(R.string.takeover_title_reason, stringResource(label)) else stringResource(R.string.takeover_title)
            val handedOver = stage == Takeover.Stage.HandedOver
            val text = if (handedOver) stringResource(R.string.takeover_handed_over_hint) else status.message
            // 支払い商品、金額、住所は別の行にあります。このカードは支払い前の確認です。
            val body = listOfNotNull(text, StatusCopy.paymentLines(status.item, status.amount, status.address)).joinToString("\n")
            NeedCard(title, body, footer = { TextLink(stringResource(R.string.action_cancel_task), vm::cancel) }) {
                if (handedOver) {
                    CardButton(stringResource(R.string.action_finished_myself), onClick = vm::finishedMyself)
                    CardButton(stringResource(R.string.action_hand_back), primary = true, onClick = vm::handBack)
                } else {
                    CardButton(stringResource(R.string.action_skip), onClick = vm::skip)
                    CardButton(stringResource(R.string.action_take_over), primary = true, onClick = vm::takeOver)
                }
            }
        }
        is ExchangeStatus.NeedsInput -> {
            val options = status.options.orEmpty()
            val note = stringResource(R.string.no_answer_note, TaskService.INPUT_TIMEOUT_MINUTES)
            NeedCard(
                stringResource(R.string.input_title), stringResource(R.string.chat_input_body, status.prompt, note),
                options = if (options.isEmpty()) null else ({
                    options.forEach { o -> MikaButton(o, { vm.answer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
                }),
            ) {
                if (options.isEmpty()) CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            }
        }
        // お金を稼ぐ操作のみがあなたの同意を必要とし、これに同意するのは毎回 1 回だけです
        is ExchangeStatus.NeedsConfirmation -> NeedCard(status.title, status.body + "\n" + ConfirmationCopy.NOTE) {
            CardButton(stringResource(R.string.chat_refuse), onClick = { vm.decline(status.key) })
            CardButton(stringResource(R.string.chat_allow_once), primary = true, onClick = { vm.approve(status.key) })
        }
        is ExchangeStatus.Paused -> NeedCard(stringResource(if (status.resumable) R.string.chat_paused else R.string.chat_waiting_for_you), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            if (status.resumable) CardButton(stringResource(R.string.chat_continue), primary = true, onClick = vm::resume)
        }
        // キューイングの開始: 自分の番になると自動的に開始されます。 [続行] をクリックした場合、キューに参加することはできません。キャンセルのみ可能です。
        is ExchangeStatus.Queued -> NeedCard(stringResource(R.string.chat_queued), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
        }
        // 答え全体を押し続けると、メニューがポップアップします。上記のプロンプトや以下のソースは含まれず、回答の単語のみがコピーおよび引用されます。
        is ExchangeStatus.Done -> Box(Modifier.padding(bottom = 12.dp)) {
            AnswerArea(key, status.answer, Modifier.fillMaxWidth().answerSurface()) {
                Column {
                    status.unverifiedLine?.let { line ->
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(MikaTheme.colors.warnSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Exclaim, null, Modifier.size(11.dp), tint = MikaTheme.colors.warn)
                            }
                            Text(line, style = MikaType.Caption, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ink2)
                        }
                    }
                    MarkdownText(status.answer, AnswerText.copy(color = MikaTheme.colors.ink))
                    // ソース: 古いレコード内のモデルによって書き込まれた基本テキスト、または実行中のレコード内のコードによって記録されたソース。どちらも見つからない場合は表示されません。
                    if (status.evidence != null || runId != null) SourceChip(status.evidence, runId, loadSource, openSource)
                }
            }
        }
        is ExchangeStatus.Failed -> Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(MikaTheme.colors.bad, CircleShape))
                Text(stringResource(R.string.chat_not_done), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
            }
            Text(status.reason, Modifier.padding(top = 4.dp), style = CardBody, color = MikaTheme.colors.ink2)
        }
        // 停止後に追加された説明は、このラウンドの解答とみなされ、「停止済み」にリストされます。書いている途中に小さな文字が並んでいます
        is ExchangeStatus.Cancelled -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(stringResource(R.string.chat_stopped), Modifier.padding(start = 2.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
            if (status.summarizing) Text(
                StatusCopy.STOP_SUMMARIZING, Modifier.padding(start = 2.dp, top = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3,
            )
            status.summary?.let { summary ->
                Box(Modifier.padding(top = 8.dp)) {
                    AnswerArea(key, summary, Modifier.fillMaxWidth().answerSurface()) {
                        MarkdownText(summary, AnswerText.copy(color = MikaTheme.colors.ink))
                    }
                }
            }
        }
    }
}

/** は、以下の「ソース」カプセルに答えました。これは単なる入り口です。基礎とスクリーンショットは別のページでご覧いただけます。 */
@Composable
private fun SourceChip(text: String?, runId: String?, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit) {
    val source by produceState<EvidenceSource?>(null, runId) { value = runId?.let { loadSource(it) } }
    if (text == null && source?.items.isNullOrEmpty() && source?.screenshots.isNullOrEmpty()) return
    val c = MikaTheme.colors
    Row(
        Modifier.padding(top = 12.dp).heightIn(min = 30.dp)
            .cardSurface(c, MikaShapes.Pill).clip(MikaShapes.Pill)
            .clickable(onClickLabel = stringResource(R.string.chat_view_source)) { openSource(runId, text) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Link, null, Modifier.size(15.dp), tint = c.ink2)
        Text(sourceLabel(source), style = PillText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 「Need you」カード (処理、質問、確認、一時停止、画面ロック、および停止が必要): 白いカード、タイトルの前のオレンジ色の点、以下の手順。
 * [options] は、クリック可能な回答のリストです。ボタンは並んでおり、左側にセカンダリ (明るい色)、右側 (青色) にプライマリがあります。 [footer] が一番下にあります。
 */
@Composable
private fun NeedCard(
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warnSolid, CircleShape))
                Text(title, style = NeedTitle, color = c.ink)
            }
            Text(body, Modifier.padding(top = 6.dp), style = CardBody, color = c.ink2)
            if (options != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
            }
            if (buttons != null) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
            footer?.let { Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
        }
    }
}

@Composable
private fun RowScope.CardButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.weight(1f),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Medium,
    )
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(MikaShapes.Pill).clickable(onClick = onClick).padding(4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}

/** 「ダウンロード/ミカ」にエクスポートされたファイル: 小さいカード、名前、サイズ、保存場所。表示されているものをクリックしてアプリ ([FileViewer]) で開き、残りはシステムで開くことができるアプリに任せます。 */
@Composable
private fun FileCards(files: List<SharedFile>) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button) { openShared(context, f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Doc, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ChatViewModel.sizeLabel(f.size)} · ${f.savedAs.substringBeforeLast('/')}",
                        style = FileMeta, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = c.ink3)
            }
        }
    }
}

private fun openShared(context: Context, f: SharedFile) = FileViewer.open(context, Uri.parse(f.uri), f.name)

// ---------- 入力フィールド ----------

/** 「+」パネル: 閉じている、開いている、キーボードに道を譲ります (入力ボックスをクリックします。キーボードが上がるとパネルが下がり、キーボードが所定の位置に来ると閉じます)。 */
private enum class Panel { Closed, Open, Yielding }

/**
 * ガラスカプセル入力フィールド: 左側に「+」、右側に送信 (コンテンツがない場合は灰色);タスクの実行中に追加の停止が発生し、入力ボックスの灰色のテキストで、中断が現在のタスクに引き継がれることが示されます。
 * 「＋」をクリックして開くアクセサリ パネル ([attachPanel]) は入力フィールドの下にあり、キーボードのスペースを占めます。キーボードと交換可能です。パネルを開いてキーボードを閉じます。
 * 「+」（今回は×）をクリックするか、入力ボックスをクリックしてパネルを閉じ、キーボードに切り替えます。戻るときは、パネルを閉じてください。送信される添付ファイルは、入力フィールド ([PendingTray]) の上の行にあります。
 */
@Composable
internal fun Composer(
    modifier: Modifier,
    text: String,
    onText: (String) -> Unit,
    mode: ComposerMode,
    hasHistory: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** サンドボックスの準備ができており、実行中のタスクがない場合は「+」が表示されます。クリックしてこのパネルを開きます。 */
    attachPanel: (@Composable () -> Unit)? = null,
    attachments: List<PendingAttachment> = emptyList(),
    importing: Boolean = false,
    rejected: List<String> = emptyList(),
    overLimit: Int = 0,
    onRemove: (String) -> Unit = {},
    onDismissRejected: () -> Unit = {},
    /** 次の文と一緒に送信される引用。場合によっては、入力ボックスの上にある小さなバー「×」が削除されることがあります。 */
    quote: QuoteMessage.Quote? = null,
    onRemoveQuote: () -> Unit = {},
    editRequest: Int = 0,
    /** 入力フィールドの後ろにあるコンテンツの画面。 null の場合、入力フィールドはぼかされず、背景が不透明になります。 */
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = false,
    /** ここからセッションを開始するには、長押しして話します。 null の場合、入力ボックスは入力のみ可能です。 */
    voice: VoiceSource? = null,
    /** 完成した言葉。呼び出し側はそれを既存の単語に接続し、自動的には送信しません。 */
    onVoiceText: (String) -> Unit = {},
    /** 「音声入力はまだ利用できません」の「選択を解除」：設定に移動し、テキスト変換を選択します。 */
    onPickSpeech: () -> Unit = {},
    /** タスクの実行中に誰に言葉を伝えますか: カードの上部に「「...」への感動詞」と書きます。 */
    steerTarget: String? = null,
    /** は、押すと話すときに true になります。会話の内容はぼやけます。 */
    onVoiceHolding: (Boolean) -> Unit = {},
) {
    val c = MikaTheme.colors
    val vh = rememberVoiceHold(voice, onVoiceText)
    val vState = voiceState(vh)
    val voiceHolding = vh?.holding == true
    LaunchedEffect(voiceHolding) { onVoiceHolding(voiceHolding) }
    var fieldFocused by remember { mutableStateOf(false) }
    // ページの下部の色が背景色から透明になり、入力フィールドはその上に浮かぶガラスになります。
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.6f to c.bg, 1f to c.bg) }
    val busy = mode == ComposerMode.Busy
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    var panel by rememberSaveable { mutableStateOf(Panel.Closed) }
    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            panel = Panel.Closed
            field.requestFocus()
            keyboard?.show()
        }
    }
    // パネルはキーボードと同じ高さです。これまでに見たキーボード (ナビゲーション バーの上のセクション) の高さをメモし、これまでに見たことがない場合はデフォルトの高さを使用します。
    var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ime, nav, density) {
        snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.collect { if (it > keyboardPx) keyboardPx = it }
    }
    // 入力ボックスがフォーカスされている場合、長押ししてシステムを終了し、単語を選択して貼り付けます (フローティング キーボード、分割キーボード、および物理キーボードにも同じことが当てはまります)。入力ボックスにフォーカスがない場合、入力ボックスは長押しジェスチャで覆われます。
    val voiceGesture = vh != null && (!fieldFocused || voiceHolding)
    // キーボードの再生から格納まで (リターン キー、キーボード自体の格納キー): その後、入力ボックスはフォーカスを失い、長押しして発話できるようになります。キーボードの変更のみが表示されます。物理キーボード (決してポップアップしない) は影響を受けません。
    LaunchedEffect(ime, density, vh != null) {
        if (vh == null) return@LaunchedEffect
        var wasUp = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { up ->
            if (wasUp && !up && fieldFocused) focus.clearFocus()
            wasUp = up
        }
    }
    // 入力ボックスの外側の単語が変わると (話し終わった後に入力し、例をクリックします)、カーソルは最後に置かれ、次の単語または入力が遅れます。
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shownValue = if (fieldValue.text == text) fieldValue else TextFieldValue(text, TextRange(text.length))
    val panelPx = with(density) {
        if (keyboardPx > 0) keyboardPx.coerceIn(PanelMin.roundToPx(), PanelMax.roundToPx()) else PanelDefault.roundToPx()
    }
    LaunchedEffect(attachPanel == null) { if (attachPanel == null) panel = Panel.Closed }
    BackHandler(enabled = panel == Panel.Open) { panel = Panel.Closed }
    // キーボードに道を譲ります: キーボードがパネルの高さ近くまで上昇するまで待ちます (キーボードがポップアップしない場合は、長くてもしばらく待ちます)。
    LaunchedEffect(panel, panelPx) {
        if (panel != Panel.Yielding) return@LaunchedEffect
        withTimeoutOrNull(YIELD_MS) {
            snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.first { it >= panelPx * 9 / 10 }
        }
        panel = Panel.Closed
    }
    val togglePanel: () -> Unit = {
        if (panel == Panel.Open) {
            panel = Panel.Yielding
            field.requestFocus()
            keyboard?.show()
        } else {
            focus.clearFocus()
            keyboard?.hide()
            panel = Panel.Open
        }
    }
    val turn by animateFloatAsState(if (panel == Panel.Open) 45f else 0f, if (reduceMotion) snap() else tween(200), label = "plus")
    Box(
        modifier.fillMaxWidth()
            .background(fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            quote?.let { QuoteChip(it, onRemoveQuote, Modifier.padding(bottom = 6.dp)) }
            if (attachments.isNotEmpty()) PendingTray(attachments, onRemove, Modifier.padding(bottom = 2.dp))
            if (importing || rejected.isNotEmpty() || overLimit > 0) {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (importing) Text(AttachCopy.IMPORTING, style = MikaType.Caption, color = c.ink3)
                    if (rejected.isNotEmpty() || overLimit > 0) {
                        val why = listOfNotNull(
                            rejected.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.chat_attach_rejected, it.joinToString(stringResource(R.string.list_sep))) },
                            overLimit.takeIf { it > 0 }?.let(AttachCopy::overLimit),
                        ).joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(why, Modifier.weight(1f), style = MikaType.Caption, color = c.warn)
                            TextLink(stringResource(R.string.ok_got_it), onDismissRejected)
                        }
                    }
                }
            }
            if (vh != null) {
                val title = if (busy && !steerTarget.isNullOrBlank()) stringResource(R.string.voice_steer_to, steerTarget)
                else stringResource(R.string.voice_listening)
                VoiceFloat(vh, vState, title, backdrop, reduceMotion)
            }
            Box(Modifier.fillMaxWidth()) {
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard(), backdrop) {
                if (attachPanel != null) {
                    val label = if (panel == Panel.Open) AttachCopy.CLOSE else AttachCopy.ADD
                    Box(
                        Modifier.size(MikaSize.IconButton).clip(CircleShape)
                            .clickable(enabled = !importing, onClickLabel = label, onClick = togglePanel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Plus, label, Modifier.size(MikaSize.Icon).graphicsLayer { rotationZ = turn }, tint = c.ink)
                    }
                }
                Box(Modifier.weight(1f).padding(start = if (attachPanel != null) 2.dp else 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    val converting = vState is HoldToTalk.State.Converting
                    if (text.isEmpty() && !converting) {
                        if (vh != null) VoicePlaceholder(mode)
                        else Text(ChatCopy.placeholder(mode, hasHistory), style = MikaType.Body, color = c.ink3, maxLines = 1)
                    }
                    BasicTextField(
                        shownValue,
                        { v -> fieldValue = v; if (v.text != text) onText(v.text) },
                        Modifier.fillMaxWidth().testTag("composer-field").focusRequester(field)
                            .alpha(if (converting) 0f else 1f)
                            // パネルが開いているときに入力ボックスをクリックすると、パネルがキーボードに代わって表示されます。
                            .onFocusChanged {
                                fieldFocused = it.isFocused
                                if (it.isFocused && panel == Panel.Open) panel = Panel.Yielding
                            },
                        textStyle = MikaType.Body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                    )
                    // サービス プロバイダーは、リリース後に回転します。入力ボックスを丸で囲み、転送された単語を入力します。
                    if (converting) VoiceConverting(Modifier.align(Alignment.CenterStart))
                    if (voiceGesture && vh != null) {
                        Box(Modifier.matchParentSize().holdToTalk(vh) { field.requestFocus(); keyboard?.show() })
                    }
                }
                val canSend = AttachTray.canSend(text, attachments, importing)
                // キーボードが出てきたら、入力ボックスを長押しして、システムが単語を選択できるようにします。マイクを押し続けて聞くと、クリックしたままにしてプロンプトが表示されます。
                if (fieldFocused && vh != null) {
                    val hint = stringResource(R.string.voice_hold_to_talk)
                    Box(
                        Modifier.size(MikaSize.IconButton).testTag("composer-mic").holdToTalk(vh) { vh.toast = hint },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Mic, hint, Modifier.size(MikaSize.Icon), tint = c.ink)
                    }
                }
                // タスクは実行中に停止します。単語を書いたら、別の単語を送信します。送信されると、現在のタスクに引き渡されます。
                if (busy) {
                    Box(
                        Modifier.size(MikaSize.IconButton).background(c.ink, CircleShape).clip(CircleShape)
                            .clickable(onClickLabel = stringResource(R.string.chat_stop), onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(26.dp), tint = c.bg)
                    }
                }
                if (!busy || canSend) {
                    Box(
                        Modifier.size(MikaSize.IconButton)
                            .background(if (canSend) c.accent else c.greySoft, CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { panel = Panel.Closed; onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(20.dp), tint = if (canSend) c.onAccent else c.ink3)
                    }
                }
            }
            if (vh != null) VoiceBarCover(vh, vState, reduceMotion, Modifier.matchParentSize())
            }
            if (vh != null) VoiceSheetHost(vh, onPickSpeech)
            if (panel != Panel.Closed && attachPanel != null) {
                // キーボードがまだ存在するセクション (パネルが最初に開かれたときに閉じ、キーボードに与えられたときに上昇します) がパネルから削除されます。入力フィールドは上下にジャンプしません。
                Box(
                    Modifier.fillMaxWidth().clipToBounds().layout { m, cons ->
                        val keyboardNow = (ime.getBottom(this) - nav.getBottom(this)).coerceAtLeast(0)
                        val shown = (panelPx - keyboardNow).coerceAtLeast(0)
                        val p = m.measure(Constraints.fixed(cons.maxWidth, panelPx))
                        layout(cons.maxWidth, shown) { p.place(0, 0) }
                    },
                ) {
                    Box(Modifier.fillMaxSize().padding(top = 4.dp)) { attachPanel() }
                }
            }
        }
    }
}

/** パネルの高さ: キーボードが見えていない場合のデフォルト値、およびキーボードに従う場合の上限と下限。 */
private val PanelDefault = 320.dp
private val PanelMin = 280.dp
private val PanelMax = 420.dp
/** は、キーボードに譲るときに最大でこの時間 (ミリ秒) 待機できます。 */
private const val YIELD_MS = 700L

/** 垂直線の位置 (左から数えて)。ステップ点と円の中心はすべてこの線上にあります。 */
private val StepRail = 8.dp

private val FileTileShape = RoundedCornerShape(10.dp)
private val EmptyLead = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val ExampleText = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val BubbleText = TextStyle(fontSize = 15.5.sp, lineHeight = 22.5.sp)
/** 回答：本文の文字サイズと行間を1.7に緩和しました。 */
private val AnswerText = TextStyle(fontSize = 15.5.sp, lineHeight = 26.sp)
private val NowText = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val ClockText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val StepText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val StepCurrentText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
private val NoteText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val DoneText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val NeedTitle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
private val CardBody = TextStyle(fontSize = 14.sp, lineHeight = 21.5.sp)
private val PillText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val FileName = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val FileMeta = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
