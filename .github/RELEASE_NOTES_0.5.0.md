# しおり Capture 0.5.0

Windows 11のKindle表示をPC版で読み取り、同じWi-Fi上のGalaxy版へ同期できる版です。Galaxyでは引き続き章ごとの読書ができます。

- PC版: 画面ごとのOCR本文の確認・修正、目次からの章立て、表紙・挿絵の保存、一時停止・再開に対応しました。
- 同期: QRコードまたは接続情報でペアリングし、両アプリの起動中に本・章・画像・読書位置を暗号化して同期します。クラウドに本のデータは送りません。
- 本棚: 競合した変更の選択、同じ本の確認後の統合、削除から30日以内の復元に対応しました。

## ダウンロード

- Galaxy向けAPK: [直接ダウンロード](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.5.0-galaxy-arm64.apk)
- Galaxy向けZIP: [直接ダウンロード](https://raw.githubusercontent.com/hikachu1018/shiori-capture/downloads/shiori-capture-0.5.0-zip-download.zip)（APK単体の保存が終わらない場合）
- Windows版: このReleaseの `ShioriCapture-0.5.0-windows.zip` を展開し、`ShioriCapture.exe` を起動してください。

Android版は0.4.1と同じ署名証明書で、通常は本棚を残して上書き更新できます。APKを開けない場合は、ブラウザーや「マイファイル」に不明なアプリのインストールを許可してください。SHA-256は添付のチェックサムファイルで確認できます。

Windows版はPaddleOCRのモデルを同梱するため約440MBです。新しいKindle for Windowsで本を開き、しおり Capture PCの「撮影・再開」を押してからKindleを前面にしてください。撮影が制限される本では処理を停止します。

提供された縦書きタイトル画像でOCR動作を確認しました。新しいKindle for Windowsでの実本撮影とGalaxy実機での0.5.0同期は、現時点では未確認です。最初は短い本の3画面で本文と章を確認してから長い本を取り込んでください。
