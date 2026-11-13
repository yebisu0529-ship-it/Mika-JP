package com.wangzan101.mika.core.uistate

enum class ScreenState(val wire: String) {
    NORMAL("normal"),
    /** にはウィンドウや読み取り可能な要素がありません。 */
    BLANK("blank"),
    /** インターフェースは常に変化しており、読み込みが完了していません。 */
    LOADING("loading"),
    /** スクリーンショット対策ページ: 画面は真っ黒ですが、ウィンドウはあります。 */
    PROTECTED("protected"),
    /** 画面持续不可用;黑像素本身不能证明是防截屏还是虚拟屏没有合成出内容。 */
    CAPTURE_UNAVAILABLE("capture_unavailable"),
    /** ログイン、確認コード、支払い、およびユーザーが個人的に処理する必要があるその他のページ。 */
    NEEDS_HUMAN("needs_human"),
}

/**
 * [takeoverReason] が hand_to_user を推奨する理由です。 [cause] は、判断根拠の短い説明です。
 * 例外: [ScreenDiagnosis.UNSUPPORTED_BROWSER] (Web サイトが組み込みブラウザを拒否します) は人を必要としません。状態は NORMAL、needsHuman は false、ルートを変更する時期です。
 */
data class ScreenCheck(
    val state: ScreenState,
    val currentApp: String?,
    val needsHuman: Boolean,
    val takeoverReason: String? = null,
    val cause: String? = null,
)

/**
 * ポートによって収集された事実。 [blackFrames] は、インターフェースが静かになるたびに画面が完全に黒くなるかどうかを時系列で示した結果です。画面が取得できない場合は空になります。
 * [hasWindow] には、ルート ノードを読み取ることができるアプリ ウィンドウがあります。 [anyWindow] には App ウィンドウがあります (ルート ノードが読み取れなくても問題ありません)。
 * [foregroundApp] は、ウィンドウが読み取れないときにフォーカス ウィンドウまたはトップ タスクを押すことで認識されるフォアグラウンド アプリです。不明な場合は null です。
 */
data class ScreenFacts(
    val currentApp: String?,
    val hasWindow: Boolean,
    val readableElements: Int,
    val changing: Boolean,
    val blackFrames: List<Boolean>,
    val texts: List<String>,
    val passwordField: Boolean,
    val anyWindow: Boolean = hasWindow,
    val foregroundApp: String? = null,
)

object ScreenDiagnosis {
    /** 画面にはアプリ ウィンドウがありません (これは、仮想画面が最初に構築されたときのケースです)。 */
    const val NO_WINDOW = "no window"
    const val UNREADABLE_WINDOW = "unreadable window"
    const val DISPLAY_CAPTURE_UNAVAILABLE = "display_capture_unavailable"
    const val DISPLAY_CAPTURE = "display_capture"
    /** Web ページ: この Web サイトは内蔵ブラウザをサポートしていません (ブラウザを変更するか、アプリをダウンロードする必要があります)。 */
    const val UNSUPPORTED_BROWSER = "unsupported_browser"

    private val CAPTCHA = listOf(
        "ロボットではありません", "私はロボットではありません", "人間であることを確認", "画像を選択してください", "セキュリティ認証",
        "captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "verify you are human", "not a robot",
    )
    private val PAYMENT = listOf(
        "支払い用パスワード", "決済用パスワード", "支払いを確定", "支払う", "決済を確定", "暗証番号を入力して支払",
        "支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "payment password", "confirm payment", "pay now",
    )
    private val SMS_CODE = listOf(
        "認証コード", "確認コード", "認証番号", "ワンタイムパスワード", "sms認証", "コードを送信",
        "获取验证码", "发送验证码", "短信验证码", "get code", "send code", "verification code",
    )

    /** 画面上のテキストの最初の支払い単語 (支払いの確認、支払いパスワードなど)。そうでない場合は null。 */
    fun paymentText(texts: List<String>): String? {
        val text = Hant.fold(texts.joinToString("\n")).lowercase()
        return PAYMENT.firstOrNull { Hant.fold(it) in text }
    }

    fun classify(f: ScreenFacts): ScreenCheck {
        val text = Hant.fold(f.texts.joinToString("\n")).lowercase()
        fun hit(words: List<String>) = words.firstOrNull { Hant.fold(it) in text }
        fun human(reason: String, cause: String) = ScreenCheck(ScreenState.NEEDS_HUMAN, f.currentApp, true, reason, cause)

        // ページを切り替えるときに、画面が短時間完全に黒になる場合があります。インターフェースがまだ変化している場合は、「読み込み中」を押してください。
        if (f.changing && f.blackFrames.any { it }) {
            return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "black frame while the screen keeps changing")
        }
        val human = when {
            hit(CAPTCHA) != null -> "captcha" to "captcha text: ${hit(CAPTCHA)}"
            hit(PAYMENT) != null -> "payment" to "payment text: ${hit(PAYMENT)}"
            f.passwordField -> "login" to "password field"
            hit(SMS_CODE) != null -> "login" to "verification code text: ${hit(SMS_CODE)}"
            else -> null
        }
        // 黒いフレームが続いても、撮影制限と描画失敗は区別できない。
        // 読める要素があれば使い、要素もなければユーザーへ操作を引き継ぐ。
        if ((f.hasWindow || f.anyWindow || f.foregroundApp != null) && f.blackFrames.size >= 2 && f.blackFrames.all { it }) {
            val needsHuman = human != null || f.readableElements == 0
            return ScreenCheck(
                ScreenState.CAPTURE_UNAVAILABLE,
                f.currentApp ?: f.foregroundApp,
                needsHuman,
                if (needsHuman) human?.first ?: DISPLAY_CAPTURE else null,
                "frames stay black; secure window and display rendering failure cannot be distinguished",
            )
        }
        human?.let { (reason, cause) -> return human(reason, cause) }
        // Event activity alone is not evidence that usable content is still loading:
        // animations, timers and video can keep emitting events indefinitely.
        if (f.changing && (!f.hasWindow || f.readableElements == 0)) {
            return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "screen keeps changing without readable content")
        }
        if (!f.hasWindow || f.readableElements == 0) {
            val cause = when {
                f.hasWindow -> "no readable elements"
                f.anyWindow || f.foregroundApp != null -> UNREADABLE_WINDOW
                else -> NO_WINDOW
            }
            return ScreenCheck(ScreenState.BLANK, f.currentApp ?: f.foregroundApp, false, cause = cause)
        }
        return ScreenCheck(ScreenState.NORMAL, f.currentApp, false)
    }
}
