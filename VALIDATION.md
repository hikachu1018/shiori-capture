# 検証結果

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
