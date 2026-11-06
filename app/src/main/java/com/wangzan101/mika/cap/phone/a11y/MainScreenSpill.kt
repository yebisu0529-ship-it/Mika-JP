package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent

/**
 * は、エージェントのアクションによりユーザーのホーム画面でアプリが開かれるかどうかを決定します。アプリは、Mika 画面上の独自のページからではなく、バックグラウンドから起動します。
 * (サービス、通知ジャンプ、システム セレクター、クロスユーザー転送) 別のアプリを開くと、システムはデフォルトでそのアプリをホーム画面に配置します。ミカの画面には何も変化が見られません。
 * モデルはクリックされていないと判断し、何度もクリックします。それを認識したら、それをミカの画面に戻します (AndroidScreen を参照)。
 *
 * 操作開始直後に主画面へ出たタスクを判定する。Mika自身、ホーム、ほかの画面からの移動は対象外。
 * ユーザーのタップ、またはホームへ戻ってからアプリを開いた記録があれば、ユーザー操作として扱う。
 * もともと主画面にあったタスクや、ユーザーが操作を始めたタスクも移動させない。
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** はメイン画面の一番上のタスクです。 [from] は、最後に表示されたときの画面 (以前に表示されていない場合は [NEW]) であり、[now] は現在どの画面に表示されているか (存在しなくなった場合は -1) です。 */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** ユーザーがホーム画面をクリックしました: [at] 時間、[packageName] はクリックが位置するパッケージです (null として読み取ることはできません)。 */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** ホーム画面で開くアクション: ミカの画面に戻ります。 */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** は、ユーザーが使用しているアプリをホーム画面で開きます。そのままにして、モデルに指示します。 */
    data class InUse(override val arrival: Arrival) : Verdict

    /** 主屏上的这次事件能证明用户在亲手操作;系统触摸事件不要求 App 另发控件点击事件。 */
    fun isUserTap(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        -> true
        else -> false
    }

    /**
     * [since] アクションが開始されると、[windowMs] の後に表示されるタスクはこのアクションとしてカウントされません。 [topBefore] アクション開始時のメイン画面の上部にあるタスク。
     * [userTaps] ユーザーがホーム画面で何かをクリックしたときの時刻とパッケージ。同じタスクには課題が 1 つだけあり (最後の課題を取る)、順番に配置されます。
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homePackages: Set<String> = emptySet(),
    ): List<Verdict> {
        val ordered = arrivals.sortedBy { it.at }
        val homeReturns = ordered.filter { it.packageName in homePackages }.map { it.at }
        val out = LinkedHashMap<Int, Verdict>()
        for (a in ordered) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps, homeReturns) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    enum class Handling {
        /** ミカの画面を元に戻します (タスクを移動するだけで、それを開く元のアプリは同じです)。 */
        Move,
        /** システムはユーザーにどれを開くかを尋ねます。移動しないで、ユーザーが自分の画面で選択するように任せ、タスクは待機中です。 */
        LeaveForUser,
    }

    /**
     * ホーム画面でシステム パッケージ ウィンドウを開いて、それがシステム セレクターである場合、またはウィンドウ内のオプションがインストールされている 2 つのアプリ ([optionsMatch]) と一致する場合はどうすればよいですか。
     * どれを開くか尋ねているものはユーザーに残す; それ以外は戻す。Mika が他の App を代わりに開き直すことは決してない。
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * ユーザーがメイン画面の選択ダイアログで選択を終えた後に開かれたもの（選択ダイアログは [gone] の時点で既に消えている）: 選択ダイアログの後にメイン画面へ現れたもの（選択ダイアログ自身 [chooserTask] は除く
     * それが現れる前にメイン画面最上部にあったもの [topBefore] ではないもの）; ユーザーがちょうど選択肢をタップした直後でも「ユーザーがタップした」ことを理由に除外しない。選択ダイアログが閉じたかどうかは間隔を置いて確認するため、
     * 閉じる前 [slackMs] 以内に現れたものも該当する。なければ null。
     */
    fun picked(
        arrivals: List<Arrival>,
        chooserTask: Int,
        gone: Long,
        slackMs: Long,
        waitMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        homePackages: Set<String> = emptySet(),
    ): Arrival? =
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList(), homePackages)
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * 選択されたアプリでは、ユーザーが個人的に対処する必要があります。ウィンドウにパスワード ボックスがあるか、メイン画面が読み取れず、スクリーンショット ウィンドウを防ぐためにメイン画面のスクリーンショットが報告されます (これは、支払いページやパスワード入力ページによく当てはまります)。
     * ツリーが読み取れない（一部のアプリ ツリーは非常に少ない）というだけではカウントされません。 このパッケージ
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    /**
     * はシステムのアカウント ログイン サービスですか?オーセンティケーター [authenticators] にはデスクトップ アイコンがありません ([hasLauncher] は false)。
     * この種のパッケージでは、ホーム画面にアカウントの追加、ログイン、確認などのページのみが表示されます。ユーザーは手動で行う必要があります。画面全体およびパスワードボックスなしをミカの画面に移動することはできません。
     * Authenticator パッケージ (連絡先を同期するチャット アプリや電子メール アプリ) はアカウントのみを登録し、そのページは通常どおり他の基準に従います。純粋な論理。
     */
    fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
        pkg in authenticators && !hasLauncher

    private fun verdict(
        a: Arrival,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homeReturns: List<Long>,
    ): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        // 用户先回主屏桌面，再从桌面或通知栏打开 App：这条导航链不依赖控件是否另发点击事件
        if (homeReturns.any { it in since..a.at }) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // ユーザーはすでにクリックしています (このアプリが表示された後、ウィンドウをクリックします): ユーザーに任せ、移動しないでください
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}
