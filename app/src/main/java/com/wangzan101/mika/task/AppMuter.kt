package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.shell.AppAudioKey

/**
 * タスク中にミカの画面上のアプリを沈黙させます。 PLAY_AUDIO 「パッケージ+ユーザー」設定([AppAudioKey])を押すと、オリジナルともう一方のコピー(アバター、作品情報)が分離されます。
 *
 * - [beforeOpen]: ミカがアプリを起動したいときは、起動メッセージを発行する前にアプリをサイレントにする必要があります。最初の数秒間は音が出ません。このステップが失敗した場合、実際に起動して再試行するまで報告されません。
 * - [opened]: ミカの画面にアプリがポップアップします (開始するターゲット、または別のアプリからクリックすると、別のコピーが上部にポップアップします): サイレントでない場合は、サイレントになります。無音でない場合は「騒音が出る可能性があります」として記録されます。
 * - [notOpened]: 起動が拒否されました (インストールされていません、ユーザーが使用しています...)。事前にサイレント化されたものの起動されなかったものは、タスク中にサイレント化するのを防ぐためにすぐに復元されます。
 * - [release]: ユーザーが引き継ぐと、このアプリのサウンドが返されます。その後、[opened] は再び沈黙します。
 * - [unmute]: 本人が音を戻したアプリは、このタスク中に再び自動でミュートしない。
 *
 * 触れないでください: ミカ自身。デスクトップ入口のないシステム パッケージ (システム インターフェイス、選択ボックス、許可ボックスなど。サイレント時にシステム サウンドが発生します)。
 * 現時点では、ユーザーはホーム画面でそれを見ることができます (ミュート機能が押されており、ユーザーの手がミュートされているときは音は鳴りません)。設定でミュートをオフにすると、誰も沈黙しません。
 * はミッション中に上がった分も含めて最後に[AudioMute.restoreAll]とともに復元されました。
 */
class AppMuter(
    private val audio: AudioMute,
    /** 設定 › 作業中は「開いているアプリをミュート」;今それを読むたびに、タスクの途中でそれを閉じることも重要です。 */
    private val enabled: () -> Boolean,
    /** このパッケージが静的であるかどうか (クラスの説明の「Untouchable」の最初の 2 つの項目を参照)。 パッケージ */
    private val mutable: (packageName: String) -> Boolean,
    /** ([user] のコピー、null はオリジナルのコピー) がホーム画面に表示されます。 */
    private val onMainScreen: (packageName: String, user: Int?) -> Boolean,
    /** は静かであるはずですが、そうではありません。タスクの進行中に「このアプリは騒音を発する可能性があります」というプロンプトが表示されます。 */
    private val failed: (packageName: String) -> Unit,
) {
    /** は、このミッションでは無効になっている (まだ復元されていない) キーです。 */
    private val muted = HashSet<String>()

    /** は起動前にサイレントでオンになっていますが、キーが出てくるのはまだ見ていません。起動が拒否された場合は、それに応じて復元します。 */
    private val early = HashSet<String>()

    /** 用户明确恢复过声音的键;这次任务里不再自动静它。 */
    private val unmuted = HashSet<String>()

    @Synchronized
    fun beforeOpen(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (key in muted || key in unmuted || !wanted(packageName) || onMainScreen(packageName, user)) return
        if (audio.mute(key)) {
            muted += key
            early += key
        }
    }

    @Synchronized
    fun opened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (key in muted || key in unmuted || !wanted(packageName)) return
        // ユーザーがホーム画面で使用しています: 無音ではありません、正直に言うと音が出るかもしれません
        if (onMainScreen(packageName, user) || !audio.mute(key)) {
            failed(packageName)
            return
        }
        muted += key
    }

    @Synchronized
    fun notOpened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (!early.remove(key)) return
        if (audio.restore(key)) muted -= key
    }

    /** はアプリのサウンドを復元します (ユーザーが引き継ぎます)。復元できない場合は false を返し、記録に残して最後に再試行します。 */
    @Synchronized
    fun release(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (!audio.restore(key)) return false
        muted -= key
        return true
    }

    /**
     * 用户明确恢复声音。成功后本次任务不再自动静它;失败时保留静音与持久化记录,
     * 重复点击可以重试,任务收尾的 restoreAll 也仍能恢复。
     */
    @Synchronized
    fun unmute(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        if (!audio.restore(key)) return false
        early -= key
        muted -= key
        unmuted += key
        return true
    }

    private fun wanted(packageName: String) = enabled() && mutable(packageName)
}
