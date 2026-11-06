package com.wangzan101.mika.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.view.Surface;

// シェル ID (uid 2000) プロセスで実行されている常駐サービス: ワイヤレス デバッグによって起動される組み込みアシスタント。
interface IMikaShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=開始済み 1=ユーザーはホーム画面でアプリを使用中 (起動せず、ホーム画面を邪魔しないでください) 2=起動可能なエントリがありません 3=エラー
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    // コールド スタート: まずアプリのプロセスを終了してから開始します。戻り値はstartAppと同じです。ユーザーがホーム画面で使用している間は終了せず、1 を返します。
    // ミカの画面外にタスクがあるとアプリが終了しません。通常起動して4に戻ります
    int restartApp(int displayId, String packageName) = 9;
    // このパッケージの現在の PLAY_AUDIO モード (許可/無視/デフォルト…)。読み取ることができず、null を返します
    String getAudioMode(String packageName) = 10;
    // PLAY_AUDIO を指定されたモードに戻します。ミカが異常終了した後、永続レコードに従って復元するために使用されます。
    boolean setAudioMode(String packageName, String mode) = 11;
    // タスクが配置されている画面。タスクはもう存在しないので、-1 を返します
    int displayOfTask(int taskId) = 12;
    // タスクのトップページが属するパッケージ。タスクが存在しない場合は null を返します
    String packageOfTask(int taskId) = 13;
    // シェルとして、実際の入力のみが必要な編集領域に貼り付けるために、クリップボードをこのプレーン テキストに設定します (システムの「コピーされた」フローティング レイヤーをポップアップ表示せずに)。
    // コンテンツは機密としてマークされています (API 33+)。初めてセットアップする前に、元のクリップボードをメモしておいてください。元のクリップボードを読み取れない場合は、そのままにしておきます。戻り値 0=すでに設定されている 1=元のクリップボードを読み取れない（変更されていない） 2=エラー
    int setClipText(String text) = 14;
    // は、setClipText の前にクリップボードを復元します (元々空だった場合はクリアします)。クリップボードがユーザーによって他のコンテンツに置き換えられているか、現時点では読み取ることができません。クリアされたら元に戻します
    void restoreClip() = 15;
    // リンクまたは Android 設定ページを開きます (アクションが空の場合は、VIEW を押して URI を開きます。packageName により、開くアプリが制限されます)。
    // 戻り値は startApp と同じで、5= 開くことができるアプリはありません。6= リンクは許可されません。ホーム画面にタスクがある場合、解析されたアプリは起動せず、1 を返します。
    int startView(int displayId, String action, String uri, String packageName) = 17;
    // このパッケージのタスクが配置されている Mega 仮想画面 (並列タスクの競合をチェックするために使用されます)。ミカのどの画面にも存在しない場合は、-1 が返されます。
    int displayOfPackage(String packageName) = 18;
    // setClipText ユーザーのクリップボードの前のテキスト (元は空、またはテキストでない場合は空の文字列)。復元するものが存在しない場合は null を返します。これは、Mika がデータを暗号化し、プロセスが強制終了された後にそれを復元するために使用されます。
    String savedClipText() = 19;
    // クリップボードがまだミカによって保存されたものである場合、クリップボードはユーザーのオリジナルのテキストに置き換えられます (空の文字列はクリアされます)。ユーザーが交換した場合は移動しません。
    void restoreClipText(String original, String ours) = 20;
    // サービスが存在するプロセスの [uid, pid]。ミカは内蔵アシスタントのバインダーを受け取る前に引き継ぎ中であることを確認する。
    int[] identity() = 21;
    // ミカのバインディングによって残された変更を元に戻します (クリップボードの復元、ミュート解除、すべての仮想画面の解放)。プロセスは終了しません。バインドを解除するときに組み込みアシスタントを使用する
    void releaseAll() = 22;
    // は、ミカ自身の WebView デバッグ ソケットにシェルとして接続します (抽象名 webview_devtools_remote_<pid>、pid はミカのプロセスである必要があります)。
    // 接続されたソケットを返します。名前が不正な場合、または接続できない場合は、null を返します。ミカは、自分の直接接続がシステムによってブロックされている場合にこれを使用します。
    ParcelFileDescriptor openDevtoolsSocket(String name) = 23;
    // このタスク用に作成された仮想画面コンテキストで、コマンドをシェルとして実行します。 exitCode と出力を返します。
    Bundle runSystemCommand(int displayId, String command) = 24;
    // 現在の仮想画面上の最上位アクティビティの完全なクラス名。タスクがない場合は null を返します。
    String topActivity(int displayId) = 25;
    // この画面に Ctrl+ キーを送信します (すべてを選択するには A のみ、貼り付けには V のみ): ツリーには入力ボックスはなく、フォーカスがどこにあるかがわかっている場合の入力にのみ使用されます。
    boolean injectCtrlKey(int displayId, int keyCode) = 26;
    // `pm list users` そのまま出力します。エラーの場合は null
    String listUsers() = 27;
    // `pm list packages --user <userId>` そのまま出力します。エラーの場合は null
    String listPackages(int userId) = 28;
    // このパッケージはシステム (更新されたシステム アプリを含む) に付属しています。見つからない場合は false を返します
    boolean isSystemPackage(String packageName) = 29;
    // テキストの一部をパッケージ (ACTION_SEND text/plain、EXTRA_TEXT) に共有し、この画面で開始します。戻り値は startApp と同じで、5 = パッケージはテキスト共有ページを受け取りません。
    // ホーム画面にタスクがある場合、このパッケージは起動せず、1 を返します。
    int startShare(int displayId, String packageName, String text) = 30;
    // これからホーム画面を見つめてください。 windowMs: この期間中にメイン画面の上部に変更されたタスクをすべて書き留めてください (mainArrivals を参照)。この時点でメイン画面の一番上のタスクに戻りますが、タスクが無い場合は-1となります。
    // エージェントは各アクションの前に呼び出されます。アプリがミカの画面上で独自のページを開かず、バックグラウンドから別のアプリを開いた場合、システムはそのアプリをホーム画面に置き、ミカはそれを認識して、それに応じて元に戻します。
    int watchMainScreen(int windowMs) = 31;
    // becauseUptimeMs(SystemClock.uptimeMillis) の後、メイン画面がポップアップし、一番上のタスクが順番に配置されます。フィールドについては、MainScreenWatch.toBundle を参照してください。エラーはnullです
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    // タスクが属するユーザー (アバターと仕事用プロファイルは他のユーザー)。タスクが存在しないため、-1 が返されます。
    int userOfTask(int taskId) = 33;
    // ミカ自身の画面への 1 本の指のシングル タッチ: 指が (xs[i]、ys[i]) にあるとき、回 [i] ミリ秒 (押したときとの相対)。最初のポイントが押され、最後のポイントが持ち上げられます。
    // は時間に応じて 1 つずつ注入され、注入が完了すると戻ります (最大約 10 秒)。 Mega 画面ではない場合、パラメータが間違っている場合、または押しても注入が成功しなかった場合は、false が返されます。
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    // システム アクション (目覚まし時計の設定、タイマー、目覚まし時計リストを開く、事前入力された新しいスケジュールを開く、SystemIntents を参照。他のインテントは開始されません)、この画面が開始されます。
    // は最初にシェルとして起動します。引き継ぐページには、Mika が持つ権限 (目覚まし時計の設定など) が必要ですが、シェルにはそれがありません。代わりに、Mika が用意した asMika を発行します (Mika として開始し、
    // の画面はまだこれです)。戻り値は startView と同じです。5 = ページ アクセスなし、6 = リリース リストに含まれていません。ホーム画面にタスクがある場合は引き継がれるアプリは起動せず、1を返す
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMika) = 35;
    // は、特定の画面に限定されず、セットアップ コマンド (認証、アクセシビリティ リストなど。ペアリングされたワイヤレス デバッグの実行と同じ) をシェルとして実行します。 exitCode と出力を返します。
    // アシスタントが USB 経由でコンピュータに接続されており、ワイヤレス デバッグ用にペアリングされていない場合、Mika のコマンドはここでのみアクセスできます。
    Bundle runCommand(String command) = 36;
    // アシスタントは、Mika のプロセスが強制終了された後にそれをプルアップする必要があります (RestoreWish.flags: 1= プルアップ 2= プルアップする前にアクセシビリティ サービスに書き戻します)。
    // アシスタントはミカの設定を読み取ることができず、最後に渡された設定だけを覚えています。引き渡し後や願いが変わるたびにミカから手渡されます。
    void setRestoreWish(int flags) = 37;
    // アプリ内でアプリを開くことができるユーザーの数 (利用可能なアバター、仕事用プロファイル。ルールについては AppCopies を参照): users=int[] 数値、summary= 診断情報の行
    // (番号、タグ、タイプ、データグループ、名前とインストールされたコンテンツを除く);ユーザーを読み取れない場合は null
    Bundle appCopyUsers() = 38;
    // 別の userId でパッケージを開き、この画面を起動します。 appCopyUsers にリストされているもののみを認識し、その他の場合は 8 を返します。戻り値はstartApp/restartAppと同じです。
    // メイン画面、ミカ もう一方の画面の職業は、この画面 (パッケージ名 + ユーザー) のみを参照します。ユーザーは、メイン画面で元の画面を使用するときに、この画面をブロックしません。また、その逆も同様です。
    // がコールドの場合、このユーザーのプロセスのみが終了し、他のプロセスは移動されません。 userIdがMika自身のユーザーの場合、startApp / restartAppと同等
    int startAppInUser(int displayId, String packageName, int userId, boolean cold) = 39;
    // パッケージは、userId のタスクが配置されている Mega 仮想画面内にあります。それがMikaのどの画面にもない場合は、-1が返されます。
    int displayOfPackageInUser(String packageName, int userId) = 40;

    // メイン画面デバイスの準備。仮想画面ボタンのホワイトリストとは別に。資格情報は、シェル コマンド ラインではなく、Binder を通じてのみ渡されます。
    boolean wakeDevice() = 41;
    boolean dismissDeviceKeyguard() = 42;
    boolean enterDeviceCredential(int kind, in byte[] credential) = 43;

    // は getAudioMode / setAudioMuted / setAudioMode と同じで、ユーザーの userId (アバター、作品情報) の共有に基づいて動作します。
    // userId がミカ自身のユーザーの場合、オリジナルのものと同等になります。 PLAY_AUDIO はパッケージとユーザーによって別々に開かれ、2 つのコピーは相互に影響しません。
    String getAudioModeInUser(String packageName, int userId) = 44;
    boolean setAudioMutedInUser(String packageName, int userId, boolean muted) = 45;
    boolean setAudioModeInUser(String packageName, int userId, String mode) = 46;
    // このパッケージ (userId) には、ホーム画面に表示されるタスクがあります。つまり、ユーザーはホーム画面でそれを使用しています。ホーム画面がはっきりと見えない場合は true を返します。
    boolean visibleOnMainScreen(String packageName, int userId) = 47;

    // タスク終了: ルールに従ってこの画面上のアプリを処理し (AppsAfterTask を参照)、この画面を解放します。残りはホーム画面に移動され、最後に (ユーザーの目の前ではなく) ランク付けされます。
    // 近くで音と画面をオフにします。 keep / close はモデルによってマークされたキーです (パッケージ名、もう 1 つはパッケージ名 @user)。
    // AppsAfterTask.KEY_* に戻ります。keep= 残ったキー、closed= 閉じる「キー: 理由」、note= 移動できない場合の理由 (空でも可)。ミカ以外の画面はヌルです
    Bundle finishDisplay(int displayId, in String[] keep, in String[] close) = 48;
    // 安全锁屏还停在通知/指纹页时,做一次定向给 SystemUI 的通用上滑;不含凭据。
    boolean revealDeviceCredential() = 49;

    // は、以前に決定された破棄トランザクション番号に従います。 AIDL では、すべてのメソッドに番号を付けるか、何も番号を付けないかのどちらかが必要です。
    void destroy() = 16777114;
}
