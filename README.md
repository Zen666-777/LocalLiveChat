# MEL Chat

自分専用のAndroidキャラクターチャット。Kotlin / Jetpack Compose / OpenRouter / Room。

## Galaxyから使う

1. GitHubでこのリポジトリの **Actions → MEL Chat APK → Run workflow** を開く。
2. 成功した実行の **Artifacts → MEL-Chat-APK** をダウンロードし、Galaxyの「マイファイル」でZIPを展開する。
3. `MEL-Chat.apk` を開く。必要な場合、そのファイルを開いたアプリに「不明なアプリのインストール」を許可する。
4. MEL Chatの右上の設定でOpenRouter APIキー（`sk-or-…`）を登録する。キーはGitHubへ登録しない。
5. キャラクターを作成するか「メル」を選び、会話をはじめる。

GitHubにコードを反映した後は、PCやAndroid StudioなしでAPKを作成・取得できます。Foldの展開時は740dp以上で2ペイン、閉じた画面では一覧と会話を切り替えます。

## MVPの機能

- キャラクターの作成・編集・削除、性格・世界観・最初の挨拶の設定。
- OpenRouter経由のAIチャット。通信停止、保存済みの未回答メッセージの再試行。
- 公開モデルカタログの検索、料金表示、キャラクター別のモデル選択、任意のモデルIDの直接入力。
- APIキーはAndroid KeystoreのAES鍵とAES-GCMで暗号化し、アプリ専用領域に保存。平文表示・ログ出力なし。
- キャラクター、全会話履歴、キャラクターごとの要約をRoomに永続保存。
- 未要約のメッセージが24件を超えたとき、または文脈予算を超えたときに自動要約。直近8件は未要約の文脈として保持。
- 記憶の手動編集・手動要約。モデルを切り替えても会話・記憶は継続。
- ダークテーマ、日本語UI、画面幅に応じたFold向けレイアウト。

成人向けRPへの対応は、選択するモデルと提供元の利用条件・出力ポリシーに依存します。モデルIDを自由に指定できますが、特定の出力への対応をアプリ側で保証するものではありません。

## 記憶とデータ

要約は「既存の記憶＋新しく要約するログ」から作成し、要約本文と要約済みメッセージIDを同一のRoomレコードへ保存します。失敗した場合は更新位置を進めず、元の履歴も削除しません。要約には選択したチャットモデルを使うため、API料金が発生します。長期記憶は圧縮した要約であり、全文の完全な再現を保証しません。

直近の会話・キャラクター設定・記憶をOpenRouterと選択モデルの提供元へ送信します。APIキーはOpenRouterの認証にのみ使用します。会話DBはAndroidのアプリ専用領域にありますが、DB自体は暗号化していません。OSによるアプリのクラウドバックアップと端末移行は無効です。アンインストールでローカルデータが消えるため注意してください。MVPにはエクスポート機能はありません。

文脈の長さはUTF-8バイト数から保守的に見積もり、出力1024トークンと余白を確保します。モデルIDを直接入力した場合は、そのモデルの実際のコンテキスト上限以下を設定してください。直近8件や設定だけで上限を超える場合は、大きいコンテキストのモデルへ変更して再試行してください。会話を黙って切り捨てる動作はありません。

## 更新インストールの署名

Actionsはインストール可能な**デバッグ署名済みAPK**を生成します。初回はSecretsなしでビルドできます。ただし、GitHubの実行環境が生成するデバッグ署名鍵は実行ごとに変わるため、継続使用前に固定してください。

同じAPKを更新インストールし、Roomの履歴を残すには、次の手順で同じ鍵を使います。

1. GitHub Codespacesなど、ブラウザーから使えるJDK入りターミナルで以下を一度だけ実行する。

```sh
keytool -genkeypair -keystore mel-debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MEL Chat Personal,O=Personal,C=JP"
base64 -w 0 mel-debug.keystore
```

2. 出力されたBase64文字列を、リポジトリの **Settings → Secrets and variables → Actions → New repository secret** に `MEL_DEBUG_KEYSTORE_BASE64` という名前で保存する。
3. 以後のActionsはその鍵を使用する。元の鍵も安全に保管する。

このSecretは**署名鍵**用です。OpenRouter APIキーとは別です。鍵の設定前に別の署名のAPKを入れた場合、そのAPKへ上書きできないため、実際の会話を蓄積する前に署名を固定してください。個人利用向けのデバッグビルドで、Playストア公開向けのリリース設定は含みません。

## 開発と検証

JDK 17、Android SDK platform 36 / build-tools 35.0.0。

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Windowsでは`gradlew.bat`を使用します。この作業環境向けには`.tools`内のJDK/SDKを使う`./scripts/build-local.ps1`もあります。`.tools`はGit管理しません。初回の取得用`setup-local.ps1`はAndroid SDKライセンスを承諾します。

APK: `app/build/outputs/apk/debug/app-debug.apk`。テスト: `app/build/reports/tests/testDebugUnitTest/index.html`。Lint: `app/build/reports/lint-results-debug.html`。

テストは文脈予算、要約の更新位置、キャラクター間の記憶分離、モデル切り替え、履歴の永続化、再試行時の重複防止、削除時の参照整合性、HTTP認証・リクエスト・エラー処理・キャンセルを検証します。OpenRouter実サービスとの会話には利用者のAPIキーが必要です。

既存のMEL StudioからGradle Wrapper・互換性のある依存バージョン・Keystore暗号化処理を流用しました。従来のアーカイブとAPKは保持し、新アプリは`jp.mel.chat`として独立しています。

## API仕様の参照

- [OpenRouter Chat Completions](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)
- [OpenRouter Models](https://openrouter.ai/docs/api/api-reference/models/get-models)
- [AGP 8.13 互換性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
