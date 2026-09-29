# 検証結果

## 0.5.0 Windows読み取りとGalaxy同期

Windows版の単体テスト7件で、章の目次照合、Kindle下部表示の除外、同期リビジョンの競合検出、読書位置のマージ、期限切れペアリングコードの更新、TLS通信を確認しました。ユーザー提供の縦書きタイトル画像をPaddleOCRで処理し、「サピエンス全史」「文明の構造と人類の幸福」「ユヴァル・ノア」を抽出しました。同じ画像を配布用EXEに同梱したOCRモデルでも処理できることを確認しました。

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36520058111)で、競合選択画面を含むAndroid単体テストと2種類の未署名APKビルドが成功しました。両APKを0.4.1と同じ証明書（SHA-256 `25450fe70cf4c23edece57bc54d99b5c1d8574420de603e48f31aab1174682b2`）で署名し、`apksigner verify` を通しました。Galaxy向けAPKのSHA-256は `ea1f308b8c18a0cc6d226348e9c1e7d6b1a3a8582104833000a13fb46ab4d414`、ZIP版は `dc30dcf3d13a7e426e56b4d89b43e9e63e797f00256623c13a52b29cccd24b66` です。両方を公開Raw URLから取得し、手元とハッシュ一致を確認しました。PCの新しいKindleアプリを使った実本の撮影と、Galaxy実機でのペアリング・転送・削除復元は未確認です。撮影が制限された本への回避処理は実装していません。

## 0.4.1 黒背景のタイトルページ

ユーザー提供のKindleタイトルページは、画面中央の文字が少なく、従来のサンプル計算でインク率が約0.00562でした。旧版の表紙判定条件0.01に達しなかったため、OCR文字が読書本文に入り、本名も仮名のままでした。提供画像に対応するOCR文字列を使った単体テストを追加し、タイトルページとして判定され、本名候補が「サピエンス全史」になることを確認しました。[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36418075314)で単体テストと2種類のAPKビルドが成功しました。

Android 16エミュレーターのv6データベースへ、提供例と同じ5段落のタイトルページと2段落の本文を持つ既存本を追加し、0.4.1へ上書き更新しました。v7への移行後、仮の本名が「サピエンス全史」に変わり、タイトルページの5段落は読書本文から除外され、本文の2段落は残りました。保存済み本の元画像は復元できません。

署名済みGalaxy向けAPKのSHA-256は `43299c2dfb5fe9fc55bc1f23fbb5e5b867da9fc949baefab8b493e254c8610b0` です。ZIPから展開したAPKと一致しました。downloadsブランチへ登録したAPKのGit blob IDも手元の署名済みAPKと一致し、リモートブランチの更新を確認しました。この環境からRaw URLを直接取得する試行はWindowsのTLS認証エラーで失敗したため、Galaxy実機での0.4.1ダウンロードとKindle実画面での再撮影は未確認です。今回の例はタイトルページであり、本文ページの文字誤認識には別の実例が必要です。

## 0.4.0 画像付き読書とOCR確認

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36412521608)で単体テスト、全機種向けとGalaxy向けのクリーンビルドが成功しました。単体テストでは表紙・挿絵・目次の画像判定と、目次のみ保存された場合の本文除外を確認しました。

Android 16エミュレーターで、0.3.6のデータベースを0.4.0へ更新し、データベースがv6へ移行することを確認しました。目次と表紙の段落は読書本文から除外され、本文4段落、目次由来の2章、手動編集した別の本の章境界と読書位置は維持されました。章編集画面で「章を追加」→「画面 3」を押すと、開始位置4の章が追加されることをデータベースで確認しました。本棚のOCR確認画面では除外文字を表示し、テスト画像を登録した画面では確認画面と読書画面の両方で画像を表示しました。

署名済みAPKは0.3.6と同じ署名証明書で、Galaxy向けAPKのSHA-256は `a4a0a2b77c517c828146da82acc17653f6715dcb170b84bc44ecb5493cfb4445` です。ZIPから展開したAPKも一致しました。GitHubのdownloadsブランチに登録したAPKのGit blob IDは手元の署名済みAPKと一致しました。この環境から公開Raw URLへのHTTP取得はTLS資格情報エラーで実行できなかったため、Galaxyでの0.4.0の保存・インストールとKindle実画面での表紙・挿絵判定は未確認です。

## 0.3.6 目次からの章立て

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36408811425)で単体テスト、全機種向けとGalaxy向けのクリーンビルドが成功しました。単体テストでは、目次の複数画面、漢数字と全角数字、見出しの改行、番号のない章名、目次しか保存されていない場合を確認しました。

Android 16エミュレーターのv4データベースへ、表紙・目次・本文2章を持つ本と、手動編集済みの別の本を入れて0.3.6へ更新しました。データベースはv5へ移行し、表紙と目次の4段落が読書本文から除外され、本文の4段落は残りました。章名は目次の「第一章 はじまり」「第二章 続き」になり、開始位置は本文の対応する段落を指しました。手動編集した別の本の章立てと読書位置は維持されました。章編集画面に再検出と目次項目からの追加が表示されることも確認しました。

署名済みAPKはアプリID `jp.shiori.capture`、versionCode 9、0.3.5と同じ署名証明書であることを確認しました。[GitHub RawのGalaxy向け直接リンク](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.3.6-galaxy-arm64.apk)から全量を取得し、公開前のAPKとSHA-256が一致しました。ZIP内のAPKも同じハッシュでした。[配布ページ](https://hikachu1018.github.io/shiori-capture/)の0.3.6表示を確認しました。Galaxy実機の本での目次検出は未確認です。Kindleのリンク先情報はスクリーンショットから取得できないため、章境界はOCRした目次と本文見出しの照合で決めます。

## 0.3.5 Kindle進捗表示の再修正

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36405297315)で単体テスト、全機種向けとGalaxy向けのクリーンビルドが成功しました。文字列テストでは数字・記号が欠けた進捗表示、複数行に分かれた時間・割合、本文と同じ行の表示を除去し、通常の本文は保持しました。OCRの画面下端10％に位置する行を本文に入れない判定もテストしました。

Android 16エミュレーターのv3データベースに、旧フィルターが見逃す「章を読み終えるまで：」「７分 ４２％」と本文・手動章・読書位置を保存してから0.3.5のデバッグ版へ更新しました。データベースがv4へ移行し、進捗表示の2段落だけが消え、本文2段落、手動章の境界、読書位置が残ることをSQLiteで確認しました。

署名済みAPKはアプリID `jp.shiori.capture`、versionCode 8、0.3.4と同じ署名証明書であることを確認しました。[GitHub RawのGalaxy向け直接リンク](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.3.5-galaxy-arm64.apk)から全量を取得し、公開前のAPKとSHA-256が一致しました。[配布ページ](https://hikachu1018.github.io/shiori-capture/)の0.3.5表示も確認しました。Galaxy実機でのKindle画面の撮影、下部行の位置、保存済みの実際の本の修復は未確認です。

## 0.3.4 本棚管理

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36385350319)で単体テスト、全機種向けとGalaxy向けのクリーンビルドが成功しました。Android 16エミュレーターにサンプル本を登録し、検索で一致件数と空の検索結果を確認しました。本の削除をキャンセルした場合、本文・章を含むデータは残りました。削除を確定すると、本・章・段落がデータベースから削除され、本棚の空状態が表示されました。並び替えは実装済みですが、複数冊を使ったエミュレーターでの操作確認は行っていません。

署名済みAPKはアプリID `jp.shiori.capture`、versionCode 7、0.3.3と同じ署名証明書であることを確認しました。[GitHub RawのGalaxy向け直接リンク](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.3.4-galaxy-arm64.apk)から全量を取得し、公開前のAPKとSHA-256が一致しました。[配布ページ](https://hikachu1018.github.io/shiori-capture/)の0.3.4表示も確認しました。Galaxy実機での0.3.4のインストールと本棚操作は未確認です。

## 0.3.3 Kindle進捗表示の除外

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36377953884)で単体テスト、全機種向けとGalaxy向けのクリーンビルドが成功しました。Android 16エミュレーターの既存データベース（v2）に、本文と同じ段落に続く進捗表示、進捗表示だけの段落、2段落に分かれた進捗表示を入れ、0.3.3のデバッグ版へ更新しました。データベースはv3へ移行し、該当文字列と空になった段落だけが削除され、本文、手動設定の2章、読書位置は残りました。

署名済みAPKはアプリID `jp.shiori.capture`、versionCode 6、0.3.2と同じ署名証明書であることを確認しました。[GitHub RawのGalaxy向け直接リンク](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.3.3-galaxy-arm64.apk)から全量を取得し、公開前のAPKとSHA-256が一致しました。Galaxy実機での0.3.3のダウンロードとKindle画面の撮影は未確認です。

## 0.3.2 UI改善版

[GitHub Actionsのビルド](https://github.com/hikachu1018/shiori-capture/actions/runs/36374299993)で単体テスト、全機種向けとGalaxy向けのクリーンビルドが成功しました。Android 16エミュレーターにデバッグ版を入れ、本棚、撮影設定、読書、章編集の画面表示と画面遷移を確認しました。撮影オーバーレイは実機のKindle上では未確認です。

署名済み0.3.2のAPKはアプリID `jp.shiori.capture`、versionCode 5、前版と同じ署名証明書であることを確認しました。Galaxy向けAPKは約22MBです。[GitHub Rawの直接リンク](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.3.2-galaxy-arm64.apk)から全量を取得し、公開前のAPKとSHA-256が一致しました。Galaxy実機での0.3.2のダウンロードとインストールは未確認です。

## 0.3.1 の検証履歴

## 自動検証

GitHub ActionsではJDK 17 / Android SDK 35で `./gradlew --no-daemon testDebugUnitTest assembleRelease` をクリーン環境から実行します。ローカル環境のJDKではJavaコンパイル終了時に `AccessDeniedException` が発生するため、生成済みクラスを用いたAPKパッケージを行いました。CIの成功状況はGitHubのActionsを参照してください。

## エミュレーターで確認したこと

Android 16エミュレーターにデバッグ版を入れ、本棚、2章の選択、RSVPの再生と本の末尾での停止、読書位置のSQLiteへの保存、章開始段落の移動を確認しました。章境界を移動した後、EPUBと単一PDFを書き出し、EPUBの目次が2章であることと、PDFが本文を日本語で表示することを確認しました。

旧版形式のEPUB取り込みでAndroidのXML解析機能差による失敗を修正し、エミュレーター上で章付きEPUBを本棚へ取り込めることを確認しました。Galaxy実機とKindleを使う3画面撮影、ページ送り、一時停止からの再開、実際の本の末尾判定は未確認です。実機では最初に3画面を一時停止してOCRと章立てを確認し、同じ本を再開してから複数章と末尾停止を確認してください。

0.3.0のデバッグ版で途中まで保存した本（本文2画面、手動設定の2章）を0.3.1へ上書き更新し、データベースのバージョンが2へ移行したこと、本文と手動章立てが残ること、本棚に「撮影を再開」が表示されることを確認しました。Kindleの実画面で再開時の重複読み飛ばしと一時停止は未確認です。

Galaxy向け小容量APKはGitHub Actionsのクリーンビルドで作り、約22MBになりました。`arm64-v8a` のネイティブライブラリだけを含み、元の0.3.1と同じアプリID・バージョン・署名証明書であること、およびAPK署名の検証に成功したことを確認しました。GitHub Releasesの小容量APKもGalaxyでは100%から完了しなかったため、GitHub RawとGitHub Pagesに同じAPKを掲載しました。両方の公開URLから全量取得し、SHA-256が一致することを確認しました。GalaxyでGitHub Rawの直接リンクからAPKを保存できたことをユーザーが確認しました。インストールの成否は未確認です。
