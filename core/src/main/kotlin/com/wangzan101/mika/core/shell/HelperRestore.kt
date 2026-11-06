package com.wangzan101.mika.core.shell

/**
 * 組み込みアシスタントに対するミカの希望: 独自のプロセスが終了した後にプルアップするかどうか ([revive])、およびプルアップする前にミカのアクセシビリティ サービスをシステム リストに書き戻すかどうか ([accessibility])。
 * アシスタントはミカの設定を読み取ることができず、ミカがバインダーを通じて最後に渡した設定のみを認識します ([RestoreWishSender] を参照)。
 */
data class RestoreWish(val revive: Boolean, val accessibility: Boolean) {
    /** AIDL 経由で送信される整数。 */
    val flags: Int get() = (if (revive) REVIVE else 0) or (if (accessibility) ACCESSIBILITY else 0)

    companion object {
        const val REVIVE = 1
        const val ACCESSIBILITY = 2

        /** アシスタントが目を覚ますと、ミカはまだ願いを引き継いでいません。何もしないでください。 */
        val NONE = RestoreWish(revive = false, accessibility = false)

        fun fromFlags(flags: Int) = RestoreWish(flags and REVIVE != 0, flags and ACCESSIBILITY != 0)

        /**
         * カプセルは常に表示されるように設定されています。または、実行中のタスクがある場合は、強制終了後にそれを引き上げる価値があります。そうしないと、ユーザーが手で強制的に停止させて停止させてしまいます。
         * アクセシビリティ サービスは、ユーザーがミカ ([accessibilityWanted]) で「アクセシビリティをオンにする」をオフにしていない場合にのみ機能します。
         * そして、この時点でシステム リストに実際にミカ ([accessibilityOn]) が存在する場合にのみ、それが書き戻されます。ユーザーは、ミカが生きている間にシステム設定でミカをオフにし、自分のために開きませんでした。
         */
        fun of(capsuleAlways: Boolean, taskRunning: Boolean, accessibilityWanted: Boolean, accessibilityOn: Boolean): RestoreWish {
            val revive = capsuleAlways || taskRunning
            return RestoreWish(revive, revive && accessibilityWanted && accessibilityOn)
        }
    }
}

/**
 * ミカside: 手の中のアシスタントに願いを伝えてください。アシスタント (新しいバインダー) を変更するか、希望が変更された場合にのみ提出してください。提出しなかった場合は記憶されず、次回再度提出することになります。
 * [send] 配信済みかどうかを返します。
 */
class RestoreWishSender<T : Any>(private val send: (T, RestoreWish) -> Boolean) {
    private var shell: T? = null
    private var sent: RestoreWish? = null

    @Synchronized
    fun update(current: T?, wish: RestoreWish) {
        if (current == null) {
            shell = null
            sent = null
            return
        }
        if (current === shell && wish == sent) return
        if (send(current, wish)) {
            shell = current
            sent = wish
        } else {
            shell = null
            sent = null
        }
    }
}

/** ハンドオーバー中にアシスタントがミカに伝えたこと: 最後に引き上げられた時間、最近のウィンドウで引き上げられた回数、最後に引き上げられすぎたために停止された時間 (壁時計のミリ秒、0 はなしを意味します)。 */
data class HelperRestoreReport(val lastRestoredAtMs: Long, val recent: Int, val gaveUpAtMs: Long) {
    val empty: Boolean get() = lastRestoredAtMs == 0L && gaveUpAtMs == 0L
}

/**
 * アシスタント側：ミカのプロセスが消えてしまい、[GRACE_MS]が戻ってこない場合、引き上げるかどうかを判断します。
 * ミカから渡された最後の [RestoreWish] と [windowMs] を見てください。最大で [maxAttempts] 回引くことができます。引っ張られてすぐに死んでしまう場合は、停止してウィンドウが通過するのを待ちます。
 */
class HelperRestorePolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    enum class Decision {
        Restore,
        /** ミカはそれを望んでいません (カプセルは常に表示されるわけではなく、実行中のタスクもありません)。 */
        NotWanted,
        /** システム設定で明示的に強制停止された場合は、自動復帰しない。 */
        ForceStopped,
        /** しばらく待っていると、ミカ本人が戻ってきた（というか引き継ぎ中）。 */
        AppBack,
        /** 最近引っ張りすぎです：ミカは引き上げるとすぐに死んでしまい、もう引っ張りません。 */
        BackedOff,
    }

    private val attempts = ArrayDeque<Long>()
    private var lastRestoredAt = 0L
    private var gaveUpAt = 0L

    @Synchronized
    fun decide(wish: RestoreWish, appBack: Boolean, now: Long, forceStopped: Boolean = false): Decision {
        if (appBack) return Decision.AppBack
        if (forceStopped) return Decision.ForceStopped
        if (!wish.revive) return Decision.NotWanted
        prune(now)
        if (attempts.size >= maxAttempts) {
            gaveUpAt = now
            return Decision.BackedOff
        }
        attempts.addLast(now)
        lastRestoredAt = now
        return Decision.Restore
    }

    @Synchronized
    fun report(now: Long): HelperRestoreReport {
        prune(now)
        return HelperRestoreReport(lastRestoredAt, attempts.size, gaveUpAt)
    }

    private fun prune(now: Long) {
        // 時計が逆行した場合もクリアされます。はっきりと見えない場合はカウントされません。
        while (attempts.isNotEmpty() && (now - attempts.first() >= windowMs || now < attempts.first())) attempts.removeFirst()
    }

    companion object {
        /** ミカが死んだ後、このくらい待ってください。システムまたはユーザーはすぐに再オープンできるため、アシスタントがそれを行う必要はありません。 */
        const val GRACE_MS = 8_000L
        const val MAX_ATTEMPTS = 3
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** アシスタントがミカを引き上げる前に、アクセシビリティ サービス コマンドを書き戻します (元の Android 設定が書き込まれる方法)。 */
object RestoreCommands {
    const val ENABLE_ACCESSIBILITY = "settings put secure accessibility_enabled 1"

    /** リストにミカがない場合は追加します (他のサービスは変更されません)。ある場合は、メインスイッチをオンにするだけです。 [current] は、`settings get` によって読み取られた元の出力です。 */
    fun accessibility(current: String?, component: String): List<String> =
        if (AccessibilityList.contains(current, component)) listOf(ENABLE_ACCESSIBILITY)
        else PermissionCommands.writeAccessibility(AccessibilityList.with(current, component))
}

/** ミカの起動時にアクセシビリティ サービスの問題が発生しました。システム リストから削除されているか、リストには含まれていますがバインドされていません。 */
enum class A11yLoss { Removed, Unbound }

/** アクセシビリティ サービスの問題と、ミカの開始時に最後に確認された時間 (実測ミリ秒)。 */
data class A11yLossRecord(val loss: A11yLoss, val atMs: Long)

object A11yStartCheck {
    /** サービスの接続待機時間: プロセスが最初に開始されると、通常、システムは数秒以内にバインドされます。 */
    const val CONNECT_WAIT_MS = 5_000L

    /**
     * [initial] はミカが最初に目覚めたときの姿であり、[connectedSoon] は [CONNECT_WAIT_MS] です。内部で繋がっているのでしょうか？
     * [expected] が false (サービスがセットアップされていないなど、接続されていない) の場合は問題ありません。
     */
    fun loss(initial: A11yObservation, connectedSoon: Boolean, expected: Boolean): A11yLoss? = when {
        !expected || initial.connected -> null
        !initial.enabled -> A11yLoss.Removed
        !connectedSoon -> A11yLoss.Unbound
        else -> null
    }
}
