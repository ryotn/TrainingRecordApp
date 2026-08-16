# TrainingRecordApp

カメラで撮影したトレーニングマシンの結果画面から情報をJSON化し、AndroidのHealth Connectへ記録するアプリです。

## 機能

- **カメラ撮影**: トレーニングマシンの結果画面を2〜3枚撮影
- **AI解析**: Gemini APIを使って撮影画像からトレーニング情報をJSON形式に変換
- **Health Connect連携**: 解析したトレーニング記録をAndroid Health Connectに保存
- **安全なAPIキー管理**: Gemini APIキーをEncryptedSharedPreferencesで暗号化して安全に保存

## 初回起動時

アプリの初回起動時にGemini APIキーの入力を求められます。
- [Google AI Studio](https://aistudio.google.com) でAPIキーを取得してください
- APIキーはデバイスにAES-256-GCMで暗号化されて保存されます

## 権限について

以下の権限が必要です：
- **カメラ**: トレーニングマシンの結果を撮影するため
- **Health Connect**: エクササイズ記録の書き込みのため

権限が拒否された場合はダイアログで説明し、設定画面へのリンクを表示します。

## 動作環境

- Android 8.0 (API 26) 以上
- Health Connectアプリが必要（未インストールの場合はPlay Storeへ案内）

## セットアップ

1. Android Studio でプロジェクトを開く
2. `local.properties` にSDKパスを設定
3. Gradle同期を実行
4. デバイスまたはエミュレーターにインストール
5. 初回起動時にGemini APIキーを入力

## 技術スタック

- Kotlin
- CameraX
- Gemini API (Google AI)
- Android Health Connect
- EncryptedSharedPreferences (セキュアストレージ)
- OkHttp + Gson
