package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome

/**
 * 停止規則:タスクがいつ、どの結末で終わるか(諦め、保護による停止、モデルエラー、回答なし、ステップ上限)。
 * 完了はここでは扱わない:ツール呼び出しのない応答が回答であり、ループが照合して渡す。純粋関数。
 * 結末でユーザーに見せる説明はモデルへは送らない。
 */
internal object StopRules {
    /** アクション実行後:諦めた場合はそのまま終え、それ以外は保護([stop])が停止したら止める。どちらでもなければ null で続行する。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> gaveUp(outcome.message)
        else -> stop?.let(::guard)
    }

    /** ステップ上限後の最終ターンでツールが呼ばれた場合(end_not_done のみ提供):諦めた場合はそのまま終え、それ以外はステップ上限とする。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> gaveUp(outcome.message)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("操作回数の上限（$maxSteps 回）に達したため、タスクは未完了です。", FailKind.StepLimit)

    private fun gaveUp(message: String) = TaskState.Failed(
        if (ReplyLanguage.mismatch(message) == null) message
        else "タスクは未完了です。モデルから日本語で理由の説明が得られませんでした。",
        FailKind.GaveUp,
    )

    /** 1回通知してもモデルが空の応答を返した(または出力上限で切れた)。 */
    fun noAnswer() = TaskState.Failed("モデルが操作を終了しましたが、回答を返しませんでした。", FailKind.Model)

    /** 1回差し戻してもモデルのツール呼び出しが文章として返ってきた場合([LeakedToolCall] 参照):元のマークアップを回答として渡さない。 */
    fun toolCallAsText() = TaskState.Failed("モデルのツール呼び出しがサーバーに認識されず、テキストとして返されたため実行できませんでした。", FailKind.Model)

    /** 日本語への書き直しを1回求めてもモデルが日本語で回答しなかった場合:外国語の回答をそのまま渡さず、日本語で正直に失敗を伝える。翻訳を捏造しない。 */
    fun languageNotJapanese(mismatch: ReplyLanguage.Mismatch, facts: ReviewFacts): TaskState.Failed =
        TaskState.Failed(
            "モデルが日本語で回答しませんでした（回答は${mismatch.answer.display}でした）。日本語の回答が得られなかったため、結果の説明を表示できません。" +
                if (facts.acted || facts.outward) "操作の成否はこの表示上の問題だけでは判断できません。画面や履歴で実際の結果を確認し、同じ操作を繰り返さないでください。"
                else "操作はまだ開始していません。",
            FailKind.Model,
        )

    /** モデル呼び出しの失敗(再試行しても直らない、または再試行しても無駄な場合)。 */
    fun modelFailed(e: Throwable): TaskState.Failed =
        if (e is ModelError) TaskState.Failed(userReason(e), FailKind.Model)
        else TaskState.Failed("モデルの呼び出しに失敗しました: ${e.message ?: e::class.simpleName}", FailKind.Model)

    fun guard(v: GuardVerdict): TaskState.Failed {
        val why = when (v.code) {
            "repeating_action" -> "同じ操作を繰り返しても進展がありません"
            "no_progress" -> "操作後も画面が変わりません"
            "read_loop" -> "同じ画面を読み直しても必要な内容が見つかりません"
            "too_many_failures" -> "操作が続けて失敗しました"
            "sandbox_loop" -> "同じコマンドを繰り返しても失敗します"
            else -> "操作の保護機能が作動しました"
        }
        return TaskState.Failed("操作を停止しました（${v.code}）: $why。", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "モデルサービスが認証を拒否しました。「設定 › モデル」でAPIキーとモデルの利用権限を確認してください。"
    is ModelError.QuotaExceeded -> "モデルサービスの利用上限に達しました。プロバイダーの残高やプランを確認してください。"
    is ModelError.ModelNotFound -> {
        // 404 はモデル名の誤りとは限らない(アドレス誤り、ゲートウェイや中継サービスの別事情):原文がある場合はそのまま伝え、モデルが存在しないと断定しない
        val check = if (e.model.isEmpty()) "「設定 › モデル」でモデル名とBase URLを確認してください。"
            else "「設定 › モデル」で正式なモデルIDを確認するか、プロバイダーのプリセットを選び直してください。"
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "モデル「${e.model}」の呼び出し時に、") +
                    "サーバーから次の応答がありました: ${quoted(e.detail)}。モデル名またはサービスの状態を確認してください。$check"
            e.model.isEmpty() -> "モデルが見つかりません。$check"
            else -> "このプロバイダーではモデル「${e.model}」が見つかりません。$check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "このアカウントではモデル「${e.model.ifEmpty { "選択中のモデル" }}」を利用できません。「設定 › モデル」で別のモデルを選ぶか、プロバイダーの利用権限を確認してください。"
    is ModelError.ToolsUnsupported ->
        "モデル「${e.model.ifEmpty { "選択中のモデル" }}」はツール呼び出しに対応していません。Mikaの操作には必要なため、「設定 › モデル」で対応モデルを選んでください。"
    is ModelError.ContextTooLong -> "モデルのコンテキスト長を超えました。より長いコンテキストに対応するモデルを選ぶか、タスクを分けてください。"
    is ModelError.ContentRefused -> "モデルがこの内容の処理を拒否しました。依頼内容を見直すか、別のモデルを選んでください。"
    is ModelError.ReasoningEffortRejected ->
        "モデルが選択した推論の強さを受け付けませんでした。サービスの応答：${quoted(e.detail)}。「設定 › モデル」で推論の強さを既定または対応する値に変更してください。"
    is ModelError.RateLimited -> "モデルサービスの呼び出し頻度制限に達し、再試行でも解消しませんでした。時間をおくか、アカウントの制限を確認してください。"
    is ModelError.Network -> "再試行してもモデルサービスに接続できませんでした。ネットワークとBase URLを確認してください。"
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "再試行してもプロキシ経由で接続できませんでした。「設定 › ネットワークプロキシ」で接続先と動作状態を確認してください。プロキシを使わない場合は設定を空にしてください。"
        ProxyFailure.AuthFailed -> "プロキシが認証を拒否しました（HTTP 407）。「設定 › ネットワークプロキシ」で認証設定を確認してください。"
    }
    is ModelError.Server -> "モデルサービスでエラーが発生しました（HTTP ${e.status}）。時間をおいて再試行してください。"
    is ModelError.StreamCut ->
        "モデルの応答を受信し終わる前に接続が切れ、再試行でも解消しませんでした。ネットワークや中継サービスのタイムアウトを確認し、時間をおいて再試行してください。"
    is ModelError.BadResponse -> "${e.message}。Base URLとAPIの種類が一致しているか確認してください。"
    is ModelError.ChatGptLimitReached ->
        "ChatGPTの利用上限に達しました。時間をおくか、別のモデルを選んでください。利用状況はChatGPT側の設定で確認できます。"
    is ModelError.ChatGptNotEligible ->
        "このアカウントでは、MikaからChatGPTを利用できません。${if (e.detail.isEmpty()) "" else "サーバーの説明: ${quoted(e.detail)}。"}アカウントの利用条件を確認するか、別のプロバイダーを選んでください。"
    is ModelError.SignInExpired -> "${e.account}のログインが失効しました。「設定 › モデル」で${e.account}に再ログインしてください。"
    is ModelError.AccountPlanNotAllowed ->
        "現在の${e.account}アカウントでは、このAPIを利用できません（HTTP 403）。${if (e.detail.isEmpty()) "" else "サーバーの説明: ${quoted(e.detail)}。"}契約と利用権限を確認するか、「設定 › モデル」で別の接続方法を選んでください。"
}

/** 文中に引用するサーバー原文:末尾の句点(原文由来)を取り、引用文側で「。」を補う。省略記号(短縮の「…」「...」)は触らない。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
