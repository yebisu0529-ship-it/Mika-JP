package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.HINT_NOT_SETTLED
import com.wangzan101.mika.core.tool.HINT_TAP_UNCHANGED
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.switchHint
import com.wangzan101.mika.core.vision.LocateRegistry
import com.wangzan101.mika.core.vision.Locator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * ツールコンテキストのインターフェイスステータス: [Interfaces.attach] が最初に入力され、インターフェイスを持つツール (携帯電話、Web ページ、一般ツールの点呼画面) がここから取得されます。
 * ツール エンジン (コア/ツール) はこれらを認識せず、[com.wangzan101.mika.core.tool.SurfaceLayer] を介してインターフェイス側のみを使用します。
 */

/** ツールコンテキストのインターフェイスステータスキー。 */
internal val INTERFACES = CapabilityKey<Interfaces>("interfaces")

/** このタスクのインターフェースステータス (最初に入力されたもの)。 */
internal val ToolContext.interfaces: Interfaces get() = this[INTERFACES] ?: error("interfaces not attached")

/** ディストリビュータのタスクのインタフェース状態（画面読み取り判定による入力、再生を実行する場合に使用）。 */
internal val ToolDispatcher.interfaces: Interfaces get() = ctx.interfaces

/** アビリティのIDに応じた各画面の画面読み取り状況。 */
internal val ToolContext.sessions: Map<String, ScreenSession> get() = interfaces.sessions

/** 最近使用した画面。 */
internal val ToolContext.focus: SurfaceFocus get() = interfaces.focus

/** 現在操作されている画面の画面読み取りステータス (ディストリビュータは各呼び出しの前にツールが属する画面を記録します。[focus] を参照)。 */
internal val ToolContext.session: ScreenSession get() = sessions.getValue(focus.current.id)
internal val ToolContext.screen: ScreenPort get() = session.screen

/** が現在操作している画面です。 */
internal val ToolContext.surface: Surface get() = focus.current
internal val ToolContext.lastSnapshot: ScreenSnapshot? get() = session.lastSnapshot

/** 現時点でその画面上で find_visually によって見つかったポイント (@vN)。その画面で書き込み操作を行うとクリアされます。 */
internal val ToolContext.located: LocateRegistry get() = session.located
internal val ToolContext.locatedInfo: MutableMap<String, Located> get() = session.locatedInfo

/** 最後の書き込み操作後に表示される説明: この期間中の座標クリックは、画像から取得された点として扱われます。 */
internal var ToolContext.described: Boolean
    get() = session.described
    set(v) { session.described = v }
internal val ToolContext.locator: Locator? get() = session.locator

internal fun ToolContext.clearLocated() = session.clearLocated()

/** ポートのアクション結果はツール結果に変換されます。変化なし、別のアプリに切り替えられている、ページが静かでないなどはすべてプロンプトとして書き込まれます。失敗はエラー コードによって説明されます。 */
internal fun ToolContext.action(r: ActionResult): ToolExecution = when (r) {
    is ActionResult.Ok -> {
        // は別のアプリに切り替えられました (ターゲット アプリ自体によって呼び出された支払いページではありません): 支払い宣言は無効です
        if (r.appSwitched != null && !r.switchedByTarget) refusedPay = null
        val hints = listOfNotNull(
            HINT_TAP_UNCHANGED.takeIf { r.changed == false && !reportsEffect && r.opened == null },
            // はユーザーのホーム画面に開かれ、その後元に戻りました。次の文でそれを明確にしましょう。もう切り取られたとは言えなくなります。
            r.appSwitched?.takeIf { r.opened == null }?.let { switchHint(it, r.switchedByTarget) },
            HINT_NOT_SETTLED.takeIf { r.settled == false },
            r.coveredBy?.let { Feedback.format("covered_by", "pkg" to it) },
            r.opened?.let { openedHint(it) },
            // システムはユーザー画面に質問を表示し、ユーザーの選択に任せます。選択方法については指示されません。
            r.chooser?.takeIf { r.opened?.picker != true }?.let { Feedback.format("launch.chooser", "pkg" to it.packageName) },
            // 別のコピーが必要な場合は、別のコピーを発行するときに通知する必要はありません。要求したコピーが要求したものと異なる場合は、真実を話してください。
            (r.opened?.inProfile ?: r.inProfile)?.takeIf { it == OpenedElsewhere.OTHER_PROFILE && r.requestedUser == null }?.let { Feedback["profile.other"] },
            r.requestedUser?.takeIf { r.profileUser != null && r.profileUser != it }?.let { wanted ->
                if (r.inProfile == OpenedElsewhere.MAIN_PROFILE) Feedback["profile.main_not_requested"]
                else Feedback.format("profile.copy_not_requested", "user" to "${r.profileUser}", "wanted" to "$wanted")
            },
            r.hint,
        )
        ToolExecution(okObj {
            r.changed?.let { put("changed", it) }
            r.appSwitched?.let { put("app_switched", it) }
            if (r.appSwitched != null && r.switchedByTarget) put("opened_by_target", true)
            if (r.relocated) put("relocated", true)
            r.relocatedTo?.takeIf { r.relocated }?.let { put("relocatedTo", it) }
            if (r.settled == false) put("settled", false)
            r.coveredBy?.let { put("coveredBy", it) }
            r.chooser?.let { put("chooser", it.toJson()) }
            r.opened?.let { put("opened", it.toJson()) }
            r.inProfile?.let { put("inProfile", it) }
            r.profileUser?.let { put("profileUser", it) }
            r.requestedUser?.let { wanted -> r.profileUser?.let { put("requestedCopy", it == wanted) } }
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = r.changed)
    }
    is ActionResult.Failed ->
        if (r.reason == "ref_expired") refExpired(Feedback["ref_expired"], "element_lost")
        else {
            val code = r.reason.takeIf { Regex("^[a-z][a-z0-9_]*$").matches(it) } ?: "action_failed"
            val message = FAILURE_MESSAGES[code] ?: Feedback.format("action_failed", "reason" to r.reason)
            fail(code, r.hint?.let { "$message $it" } ?: message)
        }
}

/**
 * アクションは、ユーザーのホーム画面でアプリが開かれたとき、モデルに通知します。元に戻されたときは、ミカの画面で続行し、再度クリックしないでください。システムはユーザーの画面上でどれを開くかを尋ねます。
 * は、ユーザーの選択に任されていると述べています。再度クリックしたり、ユーザーに代わって選択したりしないでください。次のアクションは、ユーザーが選択を完了するまで待機します。ユーザーが問題をクローズし、ユーザーが個人的に対処する場合、これは当てはまります。
 * ユーザーが自分で処理する必要があるポップアップ ウィンドウ (ログイン認証など) がユーザー画面に残り、ウィンドウを元に戻さず、そこをクリックするようユーザーに指示します。ユーザー画面上で変更されていないもの (ユーザーが現在使用しているもの)、
 * は二度と来ないでユーザーに対処してほしいと言いました。
 */
internal fun openedHint(o: OpenedElsewhere): String {
    val app = o.app ?: o.packageName
    return when {
        o.closed -> Feedback["spill.picker_closed"]
        o.byUser -> Feedback.format("spill.user_done", "app" to app)
        o.picker && o.onUserScreen -> Feedback.format("spill.user_picking", "app" to app)
        o.needsUser && o.onUserScreen -> Feedback.format("spill.user_popup", "app" to app)
        o.onUserScreen -> Feedback.format("spill.in_use", "app" to app)
        else -> Feedback.format("spill.pulled_back", "app" to app)
    }
}

/**
 * 安定した番号がオンの場合、最初にモデル番号をポート番号に変換してから、それを [act] に渡します。番号は廃止され、ref_expired が返され、実行されません。
 * 最新の画面が使用される場合、古い画面の番号は引き続き実行され、通常どおり再配置済みとマークされます。ポート番号も実行記録に記録します。
 */
internal suspend fun ToolContext.withPortRef(ref: Pair<String, String>?, act: suspend (Pair<String, String>?) -> ToolExecution): ToolExecution {
    val st = session.stabilizer
    if (st == null || ref == null) return act(ref)
    val r = when (val resolved = st.resolve(ref.first, ref.second)) {
        is Resolved.Current -> resolved
        is Resolved.Stale -> return refExpired(Feedback["ref_expired"], resolved.cause.wire)
    }
    val e = act(r.portRef to r.portSnapshotId)
    val content = if (r.relocated && e.content["ok"] == JsonPrimitive(true)) JsonObject(e.content + ("relocated" to JsonPrimitive(true))) else e.content
    return ToolExecution(content, changed = e.changed, outcome = e.outcome, frame = e.frame, images = e.images,
        record = JsonObject(e.record + buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }))
}

/** 座標変換に使用する画面。最新の画面読み込みと、まだ読み込んでいない画面です。 */
internal suspend fun ToolContext.lastOrSnapshot(): ScreenSnapshot = session.lastSnapshot ?: session.snapshot()

/** 現在の画面の記述方法（携帯電話ではパッケージ名、Webページではサイトホスト名）に従って、宣言したアプリが検証されていることを確認します。 */
internal fun ToolContext.confirmTarget(app: String): String = surface.declaredTarget(app)
