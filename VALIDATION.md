# 検証結果（0.3.0）

## 自動検証

GitHub ActionsではJDK 17 / Android SDK 35で `./gradlew --no-daemon testDebugUnitTest assembleRelease` をクリーン環境から実行します。ローカル環境のJDKではJavaコンパイル終了時に `AccessDeniedException` が発生するため、生成済みクラスを用いたAPKパッケージを行いました。CIの成功状況はGitHubのActionsを参照してください。

## エミュレーターで確認したこと

Android 16エミュレーターにデバッグ版を入れ、本棚、2章の選択、RSVPの再生と本の末尾での停止、読書位置のSQLiteへの保存、章開始段落の移動を確認しました。章境界を移動した後、EPUBと単一PDFを書き出し、EPUBの目次が2章であることと、PDFが本文を日本語で表示することを確認しました。

旧版形式のEPUB取り込みは、AndroidのXML解析機能差による失敗を修正して再確認します。Galaxy実機とKindleを使う3画面撮影、ページ送り速度、実際の本の末尾判定は未確認です。実機では最初に3画面を手動停止してOCRと章立てを確認し、その後で複数章と末尾停止を確認してください。
