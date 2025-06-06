/* Automatically generated by GNU msgfmt.  Do not modify!  */
package org.slackerdb.jdbc.translation;
public class messages_ja extends java.util.ResourceBundle {
  private static final java.lang.String[] table;
  static {
    java.lang.String[] t = new java.lang.String[1426];
    t[0] = "";
    t[1] = "Project-Id-Version: head-ja\nReport-Msgid-Bugs-To: \nPO-Revision-Date: 2018-07-23 11:10+0900\nLast-Translator: Kyotaro Horiguchi <horiguchi.kyotaro@lab.ntt.co.jp>\nLanguage-Team: PostgreSQL <z-saito@guitar.ocn.ne.jp>\nLanguage: ja_JP\nMIME-Version: 1.0\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: 8bit\nX-Generator: Poedit 1.5.4\n";
    t[2] = "Method {0} is not yet implemented.";
    t[3] = "{0} メソッドはまだ実装されていません。";
    t[10] = "Got {0} error responses to single copy cancel request";
    t[11] = "一つのコピー中断要求にたいして {0} 個のエラー応答が返されました";
    t[20] = "The array index is out of range: {0}, number of elements: {1}.";
    t[21] = "配列インデックスが範囲外です: {0} 、要素の数: {1}";
    t[26] = "Tried to obtain lock while already holding it";
    t[27] = "すでに取得中のロックを取得しようとしました";
    t[28] = "Invalid protocol state requested. Attempted transaction interleaving is not supported. xid={0}, currentXid={1}, state={2}, flags={3}";
    t[29] = "不正なプロトコル状態が要求されました。Transaction interleaving を試みましたが実装されていません。xid={0}, currentXid={1}, state={2}, flags={3}";
    t[34] = "Unsupported property name: {0}";
    t[35] = "サポートされていないプロパティ名: {0}";
    t[36] = "Unsupported Types value: {0}";
    t[37] = "サポートされない Types の値: {0}.";
    t[44] = "The hostname {0} could not be verified by hostnameverifier {1}.";
    t[45] = "ホスト名 {0} は、hostnameverifier {1} で検証できませんでした。";
    t[52] = "Invalid UUID data.";
    t[53] = "不正なUUIDデータです。";
    t[54] = "{0} parameter value must be an integer but was: {1}";
    t[55] = "パラメータ {0} の値は整数でなければなりませんが指定された値は {1} でした";
    t[56] = "Copying from database failed: {0}";
    t[57] = "データベースからのコピーに失敗しました: {0}";
    t[58] = "Requested CopyDual but got {0}";
    t[59] = "CopyDualを要求しましたが {0} が返却されました。";
    t[64] = "Multiple ResultSets were returned by the query.";
    t[65] = "クエリの実行により、複数のResultSetが返されました。";
    t[76] = "Too many update results were returned.";
    t[77] = "返却された更新結果が多すぎます。";
    t[84] = "Unable to determine a value for MaxIndexKeys due to missing system catalog data.";
    t[85] = "システムカタログにデータがないため MaxIndexKeys の値を決定できません。";
    t[90] = "Database connection failed when starting copy";
    t[91] = "コピー開始時のデータベース接続に失敗しました";
    t[94] = "Unknown XML Result class: {0}";
    t[95] = "未知のXML結果クラス: {0}";
    t[100] = "The server''s standard_conforming_strings parameter was reported as {0}. The JDBC driver expected on or off.";
    t[101] = "サーバのstandard_conforming_stringsパラメータは、{0}であると報告されました。JDBCドライバは、on または off を想定しています。";
    t[102] = "Batch entry {0} {1} was aborted: {2}  Call getNextException to see other errors in the batch.";
    t[103] = "バッチ {0} {1} はアボートしました: {2} このバッチの他のエラーは getNextException を呼び出すことで確認できます。";
    t[104] = "Protocol error.  Session setup failed.";
    t[105] = "プロトコルエラー。セッションは準備できませんでした。";
    t[106] = "This SQLXML object has not been initialized, so you cannot retrieve data from it.";
    t[107] = "このSQLXMLオブジェクトは初期化されてなかったため、そこからデータを取得できません。";
    t[116] = "Bad value for type {0} : {1}";
    t[117] = "型 {0} に対する不正な値 : {1}";
    t[120] = "A CallableStatement was executed with an invalid number of parameters";
    t[121] = "CallableStatement は不正な数のパラメータで実行されました。";
    t[124] = "Error preparing transaction. prepare xid={0}";
    t[125] = "トランザクションの準備エラー。prepare xid={0}";
    t[126] = "Can''t use relative move methods while on the insert row.";
    t[127] = "行挿入中に相対移動メソッドは使えません。";
    t[130] = "Failed to create object for: {0}.";
    t[131] = "{0} のオブジェクトの生成に失敗しました。";
    t[138] = "Cannot change transaction read-only property in the middle of a transaction.";
    t[139] = "トランザクションの中で read-only プロパティは変更できません。";
    t[154] = "{0} function takes three and only three arguments.";
    t[155] = "{0} 関数はちょうど3個の引数を取ります。";
    t[158] = "One-phase commit called for xid {0} but connection was prepared with xid {1}";
    t[159] = "単相コミットが xid {0} に対してよびだされましたが、コネクションは xid {1} と関連付けられています";
    t[160] = "Validating connection.";
    t[161] = "コネクションを検証しています";
    t[166] = "This replication stream has been closed.";
    t[167] = "このレプリケーション接続は既にクローズされています。";
    t[168] = "An error occurred while trying to get the socket timeout.";
    t[169] = "ソケットタイムアウト取得中にエラーが発生しました。";
    t[170] = "Conversion of money failed.";
    t[171] = "貨幣金額の変換に失敗しました。";
    t[172] = "Provided Reader failed.";
    t[173] = "渡された Reader で異常が発生しました。";
    t[174] = "tried to call end without corresponding start call. state={0}, start xid={1}, currentXid={2}, preparedXid={3}";
    t[175] = "対応する start の呼び出しなしで、end を呼び出しました。state={0}, start xid={1}, currentXid={2}, preparedXid={3}";
    t[178] = "Got CopyBothResponse from server during an active {0}";
    t[179] = "{0} を実行中のサーバから CopyOutResponse を受け取りました";
    t[186] = "Unknown ResultSet holdability setting: {0}.";
    t[187] = "ResultSet の holdability に対する未知の設定値です: {0}";
    t[188] = "Not implemented: 2nd phase commit must be issued using an idle connection. commit xid={0}, currentXid={1}, state={2}, transactionState={3}";
    t[189] = "実装されていません: 第二フェーズの COMMIT は、待機接続で使わなくてはなりません。xid={0}, currentXid={1}, state={2}, transactionState={3}";
    t[190] = "Invalid server SCRAM signature";
    t[191] = "不正なサーバSCRAM署名です";
    t[192] = "The server''s client_encoding parameter was changed to {0}. The JDBC driver requires client_encoding to be UTF8 for correct operation.";
    t[193] = "サーバの client_encoding パラメータが {0} に変わりました。JDBCドライバが正しく動作するためには、 client_encoding は UTF8 である必要があります。";
    t[198] = "Detail: {0}";
    t[199] = "詳細: {0}";
    t[200] = "Unexpected packet type during copy: {0}";
    t[201] = "コピー中の想定外のパケット型です: {0}";
    t[206] = "Transaction isolation level {0} not supported.";
    t[207] = "トランザクション分離レベル{0} はサポートされていません。";
    t[210] = "The server requested password-based authentication, but no password was provided.";
    t[211] = "サーバはパスワード・ベースの認証を要求しましたが、パスワードが渡されませんでした。";
    t[214] = "Interrupted while attempting to connect.";
    t[215] = "接続試行中に割り込みがありました。";
    t[216] = "Fetch size must be a value greater than or equal to 0.";
    t[217] = "フェッチサイズは、0または、より大きな値でなくてはなりません。";
    t[228] = "Added parameters index out of range: {0}, number of columns: {1}.";
    t[229] = "パラメータ・インデックスは範囲外です: {0} , カラム数: {1}";
    t[230] = "Could not decrypt SSL key file {0}.";
    t[231] = "SSL keyファイル {0} を復号できませんでした。";
    t[242] = "Could not initialize SSL context.";
    t[243] = "SSLコンテクストを初期化できませんでした。";
    t[244] = "{0} function takes one and only one argument.";
    t[245] = "{0} 関数はちょうど1個の引数を取ります。";
    t[248] = "Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.";
    t[249] = "{0} 型のパラメータが登録されましたが、get{1} (sqltype={2}) が呼び出されました。";
    t[258] = "Conversion of interval failed";
    t[259] = "時間間隔の変換に失敗しました。";
    t[262] = "xid must not be null";
    t[263] = "xidはnullではいけません。";
    t[264] = "Your security policy has prevented the connection from being attempted.  You probably need to grant the connect java.net.SocketPermission to the database server host and port that you wish to connect to.";
    t[265] = "セキュリティ・ポリシーにより、接続が妨げられました。おそらく、接続先のデータベースサーバのホストとポートに対して java.net.SocketPermission の connect 権限を許可する必要があります。";
    t[270] = "ClientInfo property not supported.";
    t[271] = "ClientInfo プロパティはサポートされていません。";
    t[272] = "LOB positioning offsets start at 1.";
    t[273] = "LOB 位置指定のオフセット値は 1 以上です。";
    t[276] = "Tried to write to an inactive copy operation";
    t[277] = "実行中ではないコピー操作に書き込もうとしました";
    t[278] = "suspend/resume not implemented";
    t[279] = "停止/再開 は実装されていません。";
    t[290] = "Transaction control methods setAutoCommit(true), commit, rollback and setSavePoint not allowed while an XA transaction is active.";
    t[291] = "トランザクション制御メソッド setAutoCommit(true), commit, rollback, setSavePoint は、XAトランザクションが有効である間は利用できません。";
    t[292] = "Unable to find server array type for provided name {0}.";
    t[293] = "指定された名前 {0} のサーバ配列型はありません。";
    t[300] = "Statement has been closed.";
    t[301] = "ステートメントはクローズされました。";
    t[302] = "The fastpath function {0} is unknown.";
    t[303] = "{0} は未知の fastpath 関数です。";
    t[306] = "The server''s DateStyle parameter was changed to {0}. The JDBC driver requires DateStyle to begin with ISO for correct operation.";
    t[307] = "サーバのDateStyleパラメータは、{0} に変わりました。JDBCドライバが正しく動作するためには、DateStyle が ISO で始まる値である必要があります。";
    t[308] = "Invalid flags {0}";
    t[309] = "不正なフラグ {0}";
    t[324] = "A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made.";
    t[325] = "CallableStatementは宣言されましたが、registerOutParameter(1, <some type>) は呼び出されませんでした。";
    t[328] = "Cannot commit when autoCommit is enabled.";
    t[329] = "autoCommit有効時に、明示的なコミットはできません。";
    t[330] = "Database connection failed when writing to copy";
    t[331] = "コピーへの書き込み中にデータベース接続で異常が発生しました";
    t[334] = "Hint: {0}";
    t[335] = "ヒント: {0}";
    t[336] = "Interval {0} not yet implemented";
    t[337] = "時間間隔 {0} は実装されていません";
    t[338] = "No X509TrustManager found";
    t[339] = "X509TrustManager が見つかりません";
    t[346] = "No results were returned by the query.";
    t[347] = "クエリは結果を返却しませんでした。";
    t[354] = "Heuristic commit/rollback not supported. forget xid={0}";
    t[355] = "ヒューリスティック commit/rollback はサポートされません。forget xid={0}";
    t[362] = "Fastpath call {0} - No result was returned or wrong size while expecting an integer.";
    t[363] = "Fastpath 呼び出し {0} - integer を想定していましたが、結果は返却されないかまたは間違った大きさでした。";
    t[364] = "Cannot cast an instance of {0} to type {1}";
    t[365] = "{0} のインスタンスは {1} 型へキャストできません";
    t[366] = "ResultSet not positioned properly, perhaps you need to call next.";
    t[367] = "適切な位置にいない ResultSetです。おそらく、nextを呼ぶ必要があります。";
    t[372] = "Cannot establish a savepoint in auto-commit mode.";
    t[373] = "自動コミットモードでsavepointを作成できません。";
    t[374] = "Prepare called before end. prepare xid={0}, state={1}";
    t[375] = "end より前に prepare が呼ばれました prepare xid={0}, state={1}";
    t[382] = "You must specify at least one column value to insert a row.";
    t[383] = "行挿入には、最低でも１つの列の値が必要です。";
    t[388] = "Query timeout must be a value greater than or equals to 0.";
    t[389] = "クエリタイムアウトは、0またはより大きな値でなくてはなりません。";
    t[394] = "The SSLSocketFactory class provided {0} could not be instantiated.";
    t[395] = "渡された SSLSocketFactoryクラス {0} はインスタンス化できませんでした。";
    t[396] = "The parameter index is out of range: {0}, number of parameters: {1}.";
    t[397] = "パラメータのインデックスが範囲外です: {0} , パラメータ数: {1}";
    t[400] = "This ResultSet is closed.";
    t[401] = "この ResultSet はクローズされています。";
    t[402] = "Cannot update the ResultSet because it is either before the start or after the end of the results.";
    t[403] = "開始位置より前もしくは終了位置より後ろであるため、ResultSetを更新することができません。";
    t[404] = "SSL error: {0}";
    t[405] = "SSL エラー: {0}";
    t[408] = "The column name {0} was not found in this ResultSet.";
    t[409] = "この ResultSet に列名 {0} ありません。";
    t[412] = "The authentication type {0} is not supported. Check that you have configured the pg_hba.conf file to include the client''s IP address or subnet, and that it is using an authentication scheme supported by the driver.";
    t[413] = "認証タイプ {0} はサポートされません。pg_hba.confでクライアントのIPアドレスまたはサブネットの指定があり、そのエントリでこのドライバがサポートする認証機構を使うように設定されていることを確認してください。";
    t[440] = "The driver currently does not support COPY operations.";
    t[441] = "ドライバはコピー操作をサポートしていません。";
    t[442] = "This statement has been closed.";
    t[443] = "このステートメントはクローズされています。";
    t[444] = "Object is too large to send over the protocol.";
    t[445] = "オブジェクトが大きすぎてこのプロトコルでは送信できません。";
    t[448] = "oid type {0} not known and not a number";
    t[449] = "OID型 {0} は未知でかつ数値でもありません";
    t[452] = "No hstore extension installed.";
    t[453] = "hstore 拡張がインストールされてません。";
    t[454] = "Currently positioned after the end of the ResultSet.  You cannot call deleteRow() here.";
    t[455] = "ResultSet の最後尾より後ろにいるため、deleteRow() を呼ぶことはできません。";
    t[462] = "The column index is out of range: {0}, number of columns: {1}.";
    t[463] = "列インデックスは範囲外です: {0} , 列の数: {1}";
    t[468] = "Got CopyInResponse from server during an active {0}";
    t[469] = "{0} を実行中のサーバから CopyInResponse を受け取りました";
    t[474] = "Fastpath call {0} - No result was returned and we expected a numeric.";
    t[475] = "Fastpath 呼び出し {0} - numeric を想定していましたが、結果は返却されませんでした。";
    t[482] = "An error occurred while setting up the SSL connection.";
    t[483] = "SSL接続のセットアップ中に、エラーが起こりました。";
    t[484] = "Could not open SSL certificate file {0}.";
    t[485] = "SSL証明書ファイル {0} を開けませんでした。";
    t[490] = "free() was called on this LOB previously";
    t[491] = "このLOBに対して free() はすでに呼び出し済みです";
    t[492] = "Finalizing a Connection that was never closed:";
    t[493] = "クローズされていないコネクションの終了処理を行います: ";
    t[494] = "Unsupported properties: {0}";
    t[495] = "サポートされないプロパティ: {0}";
    t[498] = "Interrupted while waiting to obtain lock on database connection";
    t[499] = "データベース接続のロック待ちの最中に割り込みがありました";
    t[504] = "The HostnameVerifier class provided {0} could not be instantiated.";
    t[505] = "与えれた HostnameVerifier クラス {0} はインスタンス化できませんした。";
    t[506] = "Unable to create SAXResult for SQLXML.";
    t[507] = "SQLXMLに対するSAXResultを生成できません。";
    t[510] = "The server does not support SSL.";
    t[511] = "サーバはSSLをサポートしていません。";
    t[516] = "Got CopyData without an active copy operation";
    t[517] = "実行中のコピー操作がないにもかかわらず CopyData を受け取りました";
    t[518] = "Error during one-phase commit. commit xid={0}";
    t[519] = "単一フェーズのCOMMITの処理中のエラー commit xid={0}";
    t[522] = "Network timeout must be a value greater than or equal to 0.";
    t[523] = "ネットワークタイムアウトは、0またはより大きな値でなくてはなりません。";
    t[532] = "Unsupported type conversion to {1}.";
    t[533] = "{1} への型変換はサポートされていません。";
    t[534] = "Premature end of input stream, expected {0} bytes, but only read {1}.";
    t[535] = "入力ストリームが途中で終了しました、{0} バイトを読み込もうとしましたが、 {1} バイトしかありませんでした。";
    t[536] = "Zero bytes may not occur in string parameters.";
    t[537] = "バイト値0を文字列ラメータに含めることはできません。";
    t[538] = "This connection has been closed.";
    t[539] = "このコネクションは既にクローズされています。";
    t[540] = "Cannot call deleteRow() when on the insert row.";
    t[541] = "行挿入時に deleteRow() を呼び出せません。";
    t[544] = "Unable to bind parameter values for statement.";
    t[545] = "ステートメントのパラメータ値をバインドできませんでした。";
    t[552] = "Cannot convert an instance of {0} to type {1}";
    t[553] = "{0} のインスタンスは {1} 型に変換できません";
    t[554] = "Conversion to type {0} failed: {1}.";
    t[555] = "{0} への型変換に失敗しました: {1}";
    t[556] = "Error loading default settings from driverconfig.properties";
    t[557] = "driverconfig.properties からの初期設定ロード中のエラー";
    t[558] = "Expected command status BEGIN, got {0}.";
    t[559] = "BEGINコマンドステータスを想定しましたが、{0} が返却されました。";
    t[564] = "An unexpected result was returned by a query.";
    t[565] = "クエリが想定外の結果を返却しました。";
    t[568] = "Something unusual has occurred to cause the driver to fail. Please report this exception.";
    t[569] = "何らかの異常によりドライバが動作できません。この例外を報告して下さい。";
    t[576] = "One or more ClientInfo failed.";
    t[577] = "1つ以上の ClinentInfo で問題が発生しました。";
    t[578] = "Location: File: {0}, Routine: {1}, Line: {2}";
    t[579] = "場所: ファイル: {0}, ルーチン: {1},行: {2}";
    t[582] = "Unknown type {0}.";
    t[583] = "未知の型 {0}.";
    t[590] = "This SQLXML object has already been freed.";
    t[591] = "このSQLXMLオブジェクトはすでに解放されています。";
    t[594] = "Unexpected copydata from server for {0}";
    t[595] = "{0} を実行中のサーバからのあり得ない CopyData";
    t[596] = "{0} function takes two or three arguments.";
    t[597] = "{0} 関数は2個、または3個の引数を取ります。";
    t[602] = "Connection to {0} refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.";
    t[603] = "{0} への接続が拒絶されました。ホスト名とポート番号が正しいことと、postmaster がTCP/IP接続を受け付けていることを確認してください。";
    t[612] = "Unsupported binary encoding of {0}.";
    t[613] = "{0} 型に対するサポートされないバイナリエンコーディング。";
    t[616] = "Returning autogenerated keys is not supported.";
    t[617] = "自動生成キーを返すことはサポートされていません。";
    t[620] = "Provided InputStream failed.";
    t[621] = "渡された InputStream で異常が発生しました。";
    t[626] = "No IOException expected from StringBuffer or StringBuilder";
    t[627] = "StringBuffer または StringBuilder からの IOException は想定されていません";
    t[638] = "Not implemented: one-phase commit must be issued using the same connection that was used to start it";
    t[639] = "実装されていません: 単一フェーズのCOMMITは、開始時と同じ接続で発行されなければなりません。";
    t[640] = "Cannot reference a savepoint after it has been released.";
    t[641] = "解放された savepoint は参照できません。";
    t[642] = "Ran out of memory retrieving query results.";
    t[643] = "クエリの結果取得中にメモリ不足が起きました。";
    t[654] = "No primary key found for table {0}.";
    t[655] = "テーブル {0} には主キーがありません。";
    t[658] = "Error during recover";
    t[659] = "recover 処理中のエラー";
    t[666] = "This copy stream is closed.";
    t[667] = "このコピーストリームはクローズされています。";
    t[668] = "Could not open SSL root certificate file {0}.";
    t[669] = "SSLルート証明書ファイル {0} をオープンできませんでした。";
    t[676] = "Invalid sslmode value: {0}";
    t[677] = "不正な sslmode 値: {0}";
    t[678] = "Cannot tell if path is open or closed: {0}.";
    t[679] = "経路が開いているか、閉じているか判別できません: {0}";
    t[682] = "Illegal UTF-8 sequence: {0} bytes used to encode a {1} byte value: {2}";
    t[683] = "不正なUTF-8シーケンス: {1} バイトの値のエンコードに{0} バイト使用しています: {2}";
    t[684] = "Unknown XML Source class: {0}";
    t[685] = "未知のXMLソースクラス: {0}";
    t[686] = "Internal Query: {0}";
    t[687] = "内部クエリ: {0}";
    t[702] = "Could not find a java cryptographic algorithm: {0}.";
    t[703] = "javaの暗号化アルゴリズム {0} を見つけることができませんでした。";
    t[706] = "Connection has been closed automatically because a new connection was opened for the same PooledConnection or the PooledConnection has been closed.";
    t[707] = "同じ PooledConnection に対して新しい接続をオープンしたか、この PooledConnection がクローズされたため、接続が自動的にクローズされました。";
    t[708] = "Invalid fetch direction constant: {0}.";
    t[709] = "不正なフェッチ方向の定数です: {0}";
    t[714] = "Can''t use query methods that take a query string on a PreparedStatement.";
    t[715] = "PreparedStatement でクエリ文字列を取るクエリメソッドは使えません。";
    t[716] = "SCRAM authentication failed, server returned error: {0}";
    t[717] = "スクラム認証が失敗しました、サーバはエラーを返却しました:  {0}";
    t[722] = "Invalid elements {0}";
    t[723] = "不正な要素です: {0}";
    t[738] = "Not on the insert row.";
    t[739] = "挿入行上にいません。";
    t[740] = "Unable to load the class {0} responsible for the datatype {1}";
    t[741] = "データ型 {1} に対応するクラス{0} をロードできません。";
    t[752] = "Could not find a java cryptographic algorithm: X.509 CertificateFactory not available.";
    t[753] = "javaの暗号化アルゴリズムを見つけることができませんでした。X.509 CertificateFactory は利用できません。";
    t[756] = "Can''t infer the SQL type to use for an instance of {0}. Use setObject() with an explicit Types value to specify the type to use.";
    t[757] = "{0} のインスタンスに対して使うべきSQL型を推測できません。明示的な Types 引数をとる setObject() で使うべき型を指定してください。";
    t[760] = "Invalid server-first-message: {0}";
    t[761] = "不正な server-first-message: {0}";
    t[762] = "No value specified for parameter {0}.";
    t[763] = "パラメータ {0} に値が設定されてません。";
    t[766] = "Fastpath call {0} - No result was returned and we expected an integer.";
    t[767] = "Fastpath 呼び出し {0} - integer を想定していましたが、結果は返却されませんでした。";
    t[774] = "Unable to create StAXResult for SQLXML";
    t[775] = "SQLXMLに対するStAXResultを生成できません。";
    t[798] = "CommandComplete expected COPY but got: ";
    t[799] = "CommandComplete はCOPYを想定しましたが、次の結果が返却されました:";
    t[800] = "Enter SSL password: ";
    t[801] = "SSLパスワード入力: ";
    t[802] = "Failed to convert binary xml data to encoding: {0}.";
    t[803] = "バイナリxmlデータのエンコード: {0} への変換に失敗しました。";
    t[804] = "No SCRAM mechanism(s) advertised by the server";
    t[805] = "サーバは SCRAM認証機構を広告していません";
    t[818] = "Custom type maps are not supported.";
    t[819] = "カスタム型マップはサポートされません。";
    t[822] = "Illegal UTF-8 sequence: final value is a surrogate value: {0}";
    t[823] = "不正なUTF-8シーケンス: 変換後の値がサロゲート値です: {0}";
    t[824] = "The SocketFactory class provided {0} could not be instantiated.";
    t[825] = "渡された SocketFactoryクラス {0} はインスタンス化できませんでした。";
    t[832] = "Large Objects may not be used in auto-commit mode.";
    t[833] = "ラージオブジェクトは、自動コミットモードで使うことができません。";
    t[834] = "Fastpath call {0} - No result was returned or wrong size while expecting a long.";
    t[835] = "Fastpath 呼び出し {0} - long を想定していましたが、結果は返却されないかまたは間違った大きさでした。";
    t[844] = "Invalid stream length {0}.";
    t[845] = "不正なストリーム長 {0}。";
    t[850] = "The sslfactoryarg property must start with the prefix file:, classpath:, env:, sys:, or -----BEGIN CERTIFICATE-----.";
    t[851] = "プロパティ sslfactoryarg の先頭はプリフィクス file:, classpath:, env:, sys: もしくは -----BEGIN CERTIFICATE----- のいずれかでなければなりません。";
    t[852] = "Can''t use executeWithFlags(int) on a Statement.";
    t[853] = "executeWithFlags(int) は Statement インスタンスでは使えません。";
    t[856] = "Cannot retrieve the id of a named savepoint.";
    t[857] = "名前付き savepoint の id は取得できません。";
    t[860] = "Could not read password for SSL key file by callbackhandler {0}.";
    t[861] = "callbackhandler {0} で、SSL keyファイルを読めませんでした。";
    t[874] = "Tried to break lock on database connection";
    t[875] = "データベース接続のロックを破壊しようとしました";
    t[878] = "Unexpected error writing large object to database.";
    t[879] = "データベースへのラージオブジェクト書き込み中に想定外のエラーが起きました。";
    t[880] = "Expected an EOF from server, got: {0}";
    t[881] = "サーバからの EOF を期待していましたが、{0} が送られてきました";
    t[886] = "Could not read SSL root certificate file {0}.";
    t[887] = "SSLルート証明書ファイル {0} を読めませんでした。";
    t[888] = "This SQLXML object has already been initialized, so you cannot manipulate it further.";
    t[889] = "このSQLXMLオブジェクトは既に初期化済みであるため、これ以上操作できません。";
    t[896] = "The array index is out of range: {0}";
    t[897] = "配列インデックスが範囲外です: {0}";
    t[898] = "Unable to set network timeout.";
    t[899] = "ネットワークタイムアウトが設定できません。";
    t[900] = "{0} function takes four and only four argument.";
    t[901] = "{0} 関数はちょうど4個の引数を取ります。";
    t[904] = "Unable to decode xml data.";
    t[905] = "xmlデータをデコードできません。";
    t[916] = "Bad value for type timestamp/date/time: {1}";
    t[917] = "timestamp/date/time 型に対する不正な値: {1}";
    t[928] = "Illegal UTF-8 sequence: final value is out of range: {0}";
    t[929] = "不正なUTF-8シーケンス: 変換後の値が範囲外です: {0}";
    t[932] = "Unable to parse the count in command completion tag: {0}.";
    t[933] = "コマンド完了タグのカウントをパースできません: {0}";
    t[942] = "Read from copy failed.";
    t[943] = "コピーストリームからの読み取りに失敗しました。";
    t[944] = "Maximum number of rows must be a value grater than or equal to 0.";
    t[945] = "行数の制限値は 0またはより大きな値でなくてはなりません。";
    t[958] = "The password callback class provided {0} could not be instantiated.";
    t[959] = "渡されたパスワードコールバッククラス {0} はインスタンス化できませんでした。";
    t[960] = "Returning autogenerated keys by column index is not supported.";
    t[961] = "列インデックスで自動生成キーを返すことはサポートされていません。";
    t[966] = "Properties for the driver contains a non-string value for the key ";
    t[967] = "このドライバのプロパティでは以下のキーに対して文字列ではない値が設定されています: ";
    t[974] = "Database connection failed when canceling copy operation";
    t[975] = "コピー操作中断のためのデータベース接続に失敗しました";
    t[976] = "DataSource has been closed.";
    t[977] = "データソースはクローズされました。";
    t[996] = "Unable to get network timeout.";
    t[997] = "ネットワークタイムアウトが取得できません。";
    t[1000] = "A CallableStatement was executed with nothing returned.";
    t[1001] = "CallableStatement が実行されましたがなにも返却されませんでした。";
    t[1002] = "Can''t refresh the insert row.";
    t[1003] = "挿入行を再フェッチすることはできません。";
    t[1004] = "Could not find a server with specified targetServerType: {0}";
    t[1005] = "指定された targetServerType のサーバーが見つかりません: {0}";
    t[1006] = "This PooledConnection has already been closed.";
    t[1007] = "この PooledConnectionは、すでに閉じられています。";
    t[1010] = "Cannot call cancelRowUpdates() when on the insert row.";
    t[1011] = "行挿入時に cancelRowUpdates() を呼び出せません。";
    t[1012] = "Preparing already prepared transaction, the prepared xid {0}, prepare xid={1}";
    t[1013] = "すでにプリペアされているトランザクションをプリペアしようとしました、プリペアされている xid={0}, プリペアしようとした xid={1}";
    t[1018] = "CopyIn copy direction can't receive data";
    t[1019] = "コピー方向 CopyIn はデータを受信できません";
    t[1024] = "conversion to {0} from {1} not supported";
    t[1025] = "{1} から {0} への変換はサポートされていません。";
    t[1030] = "An error occurred reading the certificate";
    t[1031] = "証明書の読み込み中にエラーが起きました";
    t[1032] = "Invalid or unsupported by client SCRAM mechanisms";
    t[1033] = "不正であるかクライアントのSCRAM機構でサポートされていません";
    t[1034] = "Malformed function or procedure escape syntax at offset {0}.";
    t[1035] = "関数またはプロシージャの間違ったエスケープ構文が位置{0}で見つかりました。";
    t[1038] = "Bind message length {0} too long.  This can be caused by very large or incorrect length specifications on InputStream parameters.";
    t[1039] = "バインドメッセージ長 {0} は長すぎます。InputStreamのパラメータにとても大きな長さ、あるいは不正確な長さが設定されている可能性があります。";
    t[1050] = "Cannot change transaction isolation level in the middle of a transaction.";
    t[1051] = "トランザクションの中でトランザクション分離レベルは変更できません。";
    t[1058] = "Internal Position: {0}";
    t[1059] = "内部位置: {0}";
    t[1062] = "No function outputs were registered.";
    t[1063] = "関数出力は登録されていません。";
    t[1072] = "Unexpected packet type during replication: {0}";
    t[1073] = "レプリケーション中に想定外のパケット型: {0}";
    t[1076] = "Error disabling autocommit";
    t[1077] = "自動コミットの無効化処理中のエラー";
    t[1080] = "Requested CopyOut but got {0}";
    t[1081] = "CopyOut を要求しましたが {0} が返却されました";
    t[1084] = "Error rolling back prepared transaction. rollback xid={0}, preparedXid={1}, currentXid={2}";
    t[1085] = "プリペアドトランザクションのロールバック中のエラー rollback xid={0}, preparedXid={1}, currentXid={2}";
    t[1086] = "Database connection failed when ending copy";
    t[1087] = "コピー操作の終了中にデータベース接続で異常が発生しました";
    t[1090] = "Unsupported value for stringtype parameter: {0}";
    t[1091] = "サポートされないstringtypeパラメータ値です: {0}";
    t[1094] = "The sslfactoryarg property may not be empty.";
    t[1095] = "プロパティ sslfactoryarg は空であってはなりません。";
    t[1102] = "Loading the SSL root certificate {0} into a TrustManager failed.";
    t[1103] = "SSLルート証明書 {0} をTrustManagerへ読み込めませんでした。";
    t[1104] = "Illegal UTF-8 sequence: initial byte is {0}: {1}";
    t[1105] = "不正なUTF-8シーケンス: 先頭バイトが {0}: {1}";
    t[1116] = "The environment variable containing the server's SSL certificate must not be empty.";
    t[1117] = "サーバのSSL証明書を指定する環境変数は空であってはなりません。";
    t[1118] = "Connection attempt timed out.";
    t[1119] = "接続試行がタイムアウトしました。";
    t[1130] = "Cannot write to copy a byte of value {0}";
    t[1131] = "バイト値{0}はコピーストリームへの書き込みはできません";
    t[1132] = "Connection has been closed.";
    t[1133] = "接続はクローズされました。";
    t[1136] = "Could not read password for SSL key file, console is not available.";
    t[1137] = "SSL keyファイルのパスワードを読めませんでした。コンソールは利用できません。";
    t[1140] = "The JVM claims not to support the encoding: {0}";
    t[1141] = "JVMでサポートされないエンコーディングです: {0}";
    t[1146] = "Unexpected command status: {0}.";
    t[1147] = "想定外のコマンドステータス: {0}。";
    t[1154] = "Cannot rollback when autoCommit is enabled.";
    t[1155] = "autoCommit有効時に、明示的なロールバックはできません。";
    t[1158] = "Not implemented: Prepare must be issued using the same connection that started the transaction. currentXid={0}, prepare xid={1}";
    t[1159] = "実装されていません: Prepareは、トランザクションを開始したものと同じコネクションで発行しなくてはなりません。currentXid={0}, prepare xid={1}";
    t[1162] = "The connection attempt failed.";
    t[1163] = "接続試行は失敗しました。";
    t[1166] = "Illegal UTF-8 sequence: byte {0} of {1} byte sequence is not 10xxxxxx: {2}";
    t[1167] = "不正なUTF-8シーケンス: {1} バイトのシーケンス中 {0} バイト目が、10xxxxxx ではありません: {2}";
    t[1178] = "A connection could not be made using the requested protocol {0}.";
    t[1179] = "要求されたプロトコル {0} で接続することができませんでした。";
    t[1182] = "The system property containing the server's SSL certificate must not be empty.";
    t[1183] = "サーバーのSSL証明書を指定するシステムプロパティは空であってはなりません。";
    t[1188] = "Cannot call updateRow() when on the insert row.";
    t[1189] = "挿入行上では updateRow() を呼び出すことができません。";
    t[1192] = "Fastpath call {0} - No result was returned and we expected a long.";
    t[1193] = "Fastpath 呼び出し {0} - long を想定していましたが、結果は返却されませんでした。";
    t[1198] = "Truncation of large objects is only implemented in 8.3 and later servers.";
    t[1199] = "ラージオブジェクトの切り詰めは、バージョン8.3 以降のサーバでのみ実装されています。";
    t[1200] = "Cannot convert the column of type {0} to requested type {1}.";
    t[1201] = "{0}型のカラムの値を指定の型 {1} に変換できませんでした。";
    t[1204] = "Requested CopyIn but got {0}";
    t[1205] = "CopyIn を要求しましたが {0} が返却されました";
    t[1206] = "Cannot cast to boolean: \"{0}\"";
    t[1207] = "boolean へのキャストはできません: \"{0}\"";
    t[1212] = "Invalid server-final-message: {0}";
    t[1213] = "不正な server-final-message: {0}.";
    t[1214] = "This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one.";
    t[1215] = "このステートメントは、OUTパラメータを宣言していません。'{' ?= call ... '}' を使って宣言して下さい。";
    t[1218] = "Cannot truncate LOB to a negative length.";
    t[1219] = "LOBを負の長さに切り詰めることはできません。";
    t[1220] = "Zero bytes may not occur in identifiers.";
    t[1221] = "バイト値0を識別子に含めることはできません。";
    t[1222] = "Unable to convert DOMResult SQLXML data to a string.";
    t[1223] = "DOMResult SQLXMLデータを文字列に変換することができません。";
    t[1224] = "Missing expected error response to copy cancel request";
    t[1225] = "予期していたコピーの中断要求へのエラー応答がありませんでした";
    t[1234] = "SCRAM authentication is not supported by this driver. You need JDK >= 8 and pgjdbc >= 42.2.0 (not \".jre\" versions)";
    t[1235] = "SCRAM認証はこのドライバではサポートされません。JDK8 以降かつ pgjdbc 42.2.0 以降(\".jre\"のバージョンではありません)が必要です。";
    t[1240] = "Tried to end inactive copy";
    t[1241] = "実行中ではないコピー操作を終了しようとしました";
    t[1246] = "A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.";
    t[1247] = "CallableStatement 関数が実行され、出力パラメータ {0} は {1} 型 でした。しかし、{2} 型 が登録されました。";
    t[1250] = "Failed to setup DataSource.";
    t[1251] = "データソースのセットアップに失敗しました。";
    t[1252] = "Loading the SSL certificate {0} into a KeyManager failed.";
    t[1253] = "SSL証明書 {0} をKeyManagerへ読み込めませんでした。";
    t[1254] = "Could not read SSL key file {0}.";
    t[1255] = "SSL keyファイル {0} を読めませんでした。";
    t[1258] = "Tried to read from inactive copy";
    t[1259] = "実行中ではないコピーから読み取ろうとしました";
    t[1260] = "ResultSet is not updateable.  The query that generated this result set must select only one table, and must select all primary keys from that table. See the JDBC 2.1 API Specification, section 5.6 for more details.";
    t[1261] = "ResultSetは更新不可です。この結果セットを生成したクエリは、ただ一つのテーブルを選択して、そのテーブルの全ての主キーを選択する必要があります。詳細に関しては JDBC 2.1 API仕様、章 5.6 を参照して下さい。";
    t[1264] = "A result was returned when none was expected.";
    t[1265] = "ないはずの結果が返却されました。";
    t[1266] = "Tried to cancel an inactive copy operation";
    t[1267] = "実行中ではないコピー操作の中断を試みました";
    t[1268] = "Server SQLState: {0}";
    t[1269] = "サーバ SQLState: {0}";
    t[1272] = "Unable to find keywords in the system catalogs.";
    t[1273] = "キーワードはシステムカタログにありません。";
    t[1276] = "Connection is busy with another transaction";
    t[1277] = "接続は、別のトランザクションを処理中です";
    t[1280] = "ResultSets with concurrency CONCUR_READ_ONLY cannot be updated.";
    t[1281] = "CONCUR_READ_ONLYに設定されている ResultSet は更新できません。";
    t[1296] = "commit called before end. commit xid={0}, state={1}";
    t[1297] = "end の前に COMMIT を呼びました commit xid={0}, state={1}";
    t[1308] = "PostgreSQL LOBs can only index to: {0}";
    t[1309] = "PostgreSQL LOB 上の位置指定は最大 {0} までです";
    t[1310] = "Where: {0}";
    t[1311] = "場所: {0}";
    t[1312] = "Unable to find name datatype in the system catalogs.";
    t[1313] = "name データ型がシステムカタログにありません。";
    t[1314] = "Invalid targetServerType value: {0}";
    t[1315] = "不正な  targetServerType 値です。{0}.";
    t[1318] = "Cannot retrieve the name of an unnamed savepoint.";
    t[1319] = "無名 savepoint の名前は取得できません。";
    t[1320] = "Error committing prepared transaction. commit xid={0}, preparedXid={1}, currentXid={2}";
    t[1321] = "プリペアドトランザクションの COMMIT 処理中のエラー。commit xid={0}, preparedXid={1}, currentXid={2}";
    t[1324] = "Invalid timeout ({0}<0).";
    t[1325] = "不正なタイムアウト値 ({0}<0)。";
    t[1328] = "Operation requires a scrollable ResultSet, but this ResultSet is FORWARD_ONLY.";
    t[1329] = "操作は、スクロール可能なResultSetを必要としますが、このResultSetは、 FORWARD_ONLYです。";
    t[1330] = "Results cannot be retrieved from a CallableStatement before it is executed.";
    t[1331] = "実行前の CallableStatement から結果の取得はできません。";
    t[1332] = "wasNull cannot be call before fetching a result.";
    t[1333] = "wasNullは、結果フェッチ前に呼び出せません。";
    t[1336] = "{0} function doesn''t take any argument.";
    t[1337] = "{0} 関数は引数を取りません。";
    t[1344] = "Unknown Response Type {0}.";
    t[1345] = "未知の応答タイプ {0} です。";
    t[1346] = "The JVM claims not to support the {0} encoding.";
    t[1347] = "JVMは、エンコーディング {0} をサポートしません。";
    t[1348] = "{0} function takes two and only two arguments.";
    t[1349] = "{0} 関数はちょうど2個の引数を取ります。";
    t[1350] = "The maximum field size must be a value greater than or equal to 0.";
    t[1351] = "最大の項目サイズは、0またはより大きな値でなくてはなりません。";
    t[1352] = "Received CommandComplete ''{0}'' without an active copy operation";
    t[1353] = "実行中のコピー操作がないにもかかわらず CommandComplete ''{0}'' を受信しました";
    t[1354] = "Unable to translate data into the desired encoding.";
    t[1355] = "データを指定されたエンコーディングに変換することができません。";
    t[1368] = "Got CopyOutResponse from server during an active {0}";
    t[1369] = "{0} を実行中のサーバから CopyOutResponse を受け取りました";
    t[1370] = "Failed to set ClientInfo property: {0}";
    t[1371] = "ClientInfo のプロパティの設定に失敗しました: {0}";
    t[1372] = "Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database.";
    t[1373] = "不正な文字データが見つかりました。これはデータベース作成時の文字セットに対して不正な文字を含むデータが格納されているために起きている可能性が高いです。最も一般的な例は、SQL_ASCIIデータベースに8bitデータが保存されている場合です。";
    t[1374] = "Unknown Types value.";
    t[1375] = "未知の Types の値です。";
    t[1376] = " (pgjdbc: autodetected server-encoding to be {0}, if the message is not readable, please check database logs and/or host, port, dbname, user, password, pg_hba.conf)";
    t[1377] = "(pgjdbc: server-encoding として {0}  を自動検出しました、メッセージが読めない場合はデータベースログおよび host, port, dbname, user, password, pg_dba.conf を確認してください)";
    t[1386] = "GSS Authentication failed";
    t[1387] = "GSS認証は失敗しました。";
    t[1390] = "An error occurred while trying to reset the socket timeout.";
    t[1391] = "ソケットタイムアウトのリセット中にエラーが発生しました。";
    t[1392] = "Currently positioned before the start of the ResultSet.  You cannot call deleteRow() here.";
    t[1393] = "RsultSet の開始点より前にいるため、deleteRow() を呼ぶことはできません。";
    t[1394] = "Current connection does not have an associated xid. prepare xid={0}";
    t[1395] = "この接続は xid と関連付けられていません。プリペア xid={0}";
    t[1408] = "An I/O error occurred while sending to the backend.";
    t[1409] = "バックエンドへの送信中に、入出力エラーが起こりました。";
    t[1416] = "One-phase commit with unknown xid. commit xid={0}, currentXid={1}";
    t[1417] = "未知の xid の単相コミット。 コミットxid={0}, 現在のxid={1}";
    t[1420] = "Position: {0}";
    t[1421] = "位置: {0}";
    t[1422] = "There are no rows in this ResultSet.";
    t[1423] = "このResultSetに行がありません。";
    t[1424] = "Database connection failed when reading from copy";
    t[1425] = "コピーからの読み取り中にデータベース接続で異常が発生しました";
    table = t;
  }
  public java.lang.Object handleGetObject (java.lang.String msgid) throws java.util.MissingResourceException {
    int hash_val = msgid.hashCode() & 0x7fffffff;
    int idx = (hash_val % 713) << 1;
    {
      java.lang.Object found = table[idx];
      if (found == null)
        return null;
      if (msgid.equals(found))
        return table[idx + 1];
    }
    int incr = ((hash_val % 711) + 1) << 1;
    for (;;) {
      idx += incr;
      if (idx >= 1426)
        idx -= 1426;
      java.lang.Object found = table[idx];
      if (found == null)
        return null;
      if (msgid.equals(found))
        return table[idx + 1];
    }
  }
  public java.util.Enumeration getKeys () {
    return
      new java.util.Enumeration() {
        private int idx = 0;
        { while (idx < 1426 && table[idx] == null) idx += 2; }
        public boolean hasMoreElements () {
          return (idx < 1426);
        }
        public java.lang.Object nextElement () {
          java.lang.Object key = table[idx];
          do idx += 2; while (idx < 1426 && table[idx] == null);
          return key;
        }
      };
  }
  public java.util.ResourceBundle getParent () {
    return parent;
  }
}
