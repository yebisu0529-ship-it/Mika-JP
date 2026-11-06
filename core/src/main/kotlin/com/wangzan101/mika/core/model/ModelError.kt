package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("APIキーが無効か、権限がありません")
    class QuotaExceeded : ModelError("利用上限に達しました")
    class RateLimited(val retryAfterMs: Long?) : ModelError("リクエストが頻繁すぎます。しばらく待ってから再試行してください")
    class ContextTooLong : ModelError("会話がモデルのコンテキスト長を超えました")
    /**
     * プロバイダーにそのモデルがない(またはアドレス誤りでリクエスト先のAPI自体がない)。[model] はリクエストしたモデル名、不明なら空。
     * [detail] はサーバーJSONエラー内の原文(短縮済み)、なければ空。ゲートウェイや中継サービスは 404 で別の事情
     * (上流モデルが一時的にすべて利用不可など)を伝えることもあり、原文を見なければ区別できない。
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "モデルが見つかりません" else "モデル「$model」が見つかりません")
    /**
     * プロバイダーにそのモデルはあるが、このアカウントでは使えない(未開通、権限なし、未購入)。[detail] はサーバー原文(短縮済み)、なければ空。
     * 別のモデルに変えるか、プロバイダー側で有効化しなければ直らず、再試行しても無駄である。[ModelAccessErrors] 参照。
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "このアカウントではモデルを利用できません（未開通または権限なし）" else "このアカウントではモデル「$model」を利用できません（未開通または権限なし）")
    /** サーバーが明示的にツール呼び出し非対応を返したモデル(ツール付きリクエストが拒否された)。Mika はツールで作業するため主モデルにはできない。 */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "このモデルはツール呼び出しに対応していません" else "モデル「$model」はツール呼び出しに対応していません")
    class Network(cause: Throwable? = null) : ModelError("ネットワーク接続に失敗しました", cause)
    /**
     * ネットワークプロキシ経由の区間で起きたエラー(プロキシに接続できない、またはプロキシがアカウント／パスワードを拒否した)。プロバイダー側の問題ではない。[com.wangzan101.mika.core.net.NetProxy] 参照。
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "ネットワークプロキシに接続できません"
            ProxyFailure.AuthFailed -> "ネットワークプロキシがアカウントまたはパスワードを拒否しました"
        },
        cause,
    )
    /**
     * ストリーミング応答が終了マーカーも終了理由もなく接続が閉じた場合。途中のゲートウェイやプロキシのアイドルタイムアウト、ネットワーク切替で切断された可能性が高い。
     * 応答は不完全で使えず、同じリクエストを再送すれば直ることが多い。API設定とは無関係である。
     */
    class StreamCut : ModelError("返信が完了する前に接続が切れました")
    class Server(val status: Int) : ModelError("モデルサービスでエラーが発生しました（HTTP $status）")
    class ContentRefused : ModelError("モデルがこの内容の処理を拒否しました")
    /** サービスが推論の強さを明示的に拒否した場合は、設定の見直しを案内する。 */
    class ReasoningEffortRejected(val detail: String) : ModelError("モデルが選択した推論の強さを受け付けませんでした")
    /** ChatGPTプランの他アプリ向け利用枠が上限に達した。再試行せず、枠の回復を待つかモデルを変える。 */
    class ChatGptLimitReached : ModelError("ChatGPTの利用上限に達しました")
    /** この ChatGPT アカウントでは他アプリでプランの利用枠を使えない(プラン不一致、地域またはアカウントポリシーで不可)。再試行しない。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "このアカウントでは他のアプリでChatGPTの利用枠を使えません" else "このアカウントでは他のアプリでChatGPTの利用枠を使えません: $detail")
    /** ログインが失効した(トークン更新が拒否された、またはユーザーがアカウント側でこのアプリを切断した)ため、再ログインが必要。[account] はログイン先の表示名。 */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("ログインの有効期限が切れました。再度ログインしてください")
    /**
     * アカウントログイン(xAIなど)のAPIが HTTP 403 を返した場合。ログイン自体は成功しているが、このプランではそのAPIを呼べない。再試行しない。
     * そのプロバイダーのAPIキーに切り替える必要がある。[account] はプロバイダー名、[detail] はサーバー原文(短縮済み)、なければ空。
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("ご利用の${account}プランではこのAPIを利用できません")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "モデルの返信を解析できませんでした" else "モデルの返信を解析できませんでした: $detail", cause)
}
