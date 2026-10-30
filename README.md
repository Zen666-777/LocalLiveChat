# MEL Chat

自分専用のAndroidキャラクターチャット。Kotlin / Jetpack Compose / llama.cpp / Room。端末内LLMが標準で、OpenRouterも選択できます。

## Galaxyから使う

1. GitHubでこのリポジトリの **Actions → MEL Chat APK → Run workflow** を開く。開発版はブランチに`codex/mel-chat-mvp`を選ぶ。
2. 成功した実行の **Artifacts → MEL-Chat-APK** をダウンロードし、Galaxyの「マイファイル」でZIPを展開する。
3. `MEL-Chat.apk` を開く。必要な場合、そのファイルを開いたアプリに「不明なアプリのインストール」を許可する。
4. 右上の設定で「推奨モデルを取得」を押す。標準はWi-Fi。「モバイル通信も使う」を選ぶとモバイル通信でも約2.5GBを取得する。取得後は会話も要約も端末内で動く。APIキー不要。
5. キャラクターを作成するか「メル」を選び、会話をはじめる。

GitHubにコードを反映した後は、PCやAndroid StudioなしでAPKを作成・取得できます。Foldの展開時は740dp以上で2ペイン、閉じた画面では一覧と会話を切り替えます。

詳細は[ローカルLLMの使い方・独自モデル化の工程](docs/local-llm.md)を参照。独自の重みの追加学習は未実施です。MEL RPはキャラと物語を重視した会話設定です。

会話画面のプロット名から物語を作成・切り替えできます。「ロアブック」で人物詳細や世界設定を登録し、プロットごとに使う項目を選べます。[プロットとロアブックの使い方](docs/stories.md)も参照してください。

1.3.0では、個別の記憶帳と過去の原文の自動検索を追加しました。プロット編集で「小説」と返信の長さを選べます。完成目標は「会話はzetaと同等以上、記憶はさらに上」ですが、比較評価はまだ完了していません。[品質の完成基準と評価方法](docs/quality-baseline.md)を参照してください。

## MVPの機能

- キャラクターの作成・編集・削除、性格・話し方・詳細プロフィールの設定。
- プロットの作成・編集・切替。あらすじ・舞台・開始状況・目標・最初の挨拶を保存。
- ロアブックに人物詳細・人物関係・場所・世界観などを登録。プロット別の選択、任意のキーワードでの読み込み。
- GGUFモデルで端末内の会話・要約。推奨モデル取得、外部GGUF取り込み・切替・削除。停止、未回答メッセージの再試行。
- 「端末内で動かす」をOFFにした場合のみOpenRouterを使用。APIキーはアプリで登録し、GitHubには登録しない。
- 公開モデルカタログの検索、料金表示、キャラクター別のモデル選択、任意のモデルIDの直接入力。
- APIキーはAndroid KeystoreのAES鍵とAES-GCMで暗号化し、アプリ専用領域に保存。平文表示・ログ出力なし。
- キャラクター・プロット・ロア・全会話履歴をRoomに永続保存。履歴と長期記憶はプロットごとに分離。
- 未要約24件超、または文脈予算超過で自動要約。通常は直近8件、ローカルで文脈不足の場合は直近2件まで保持して要約。
- 記憶の手動編集・手動要約。モデルを切り替えても会話・記憶は継続。
- 人物・関係・約束・伏線などの記憶帳。要約済み原文の日本語検索と入力予算内の自動参照。参照した原文の確認。
- プロット別のRP/小説形式、返信上限の選択。実モデルの評価用出力集をActionsで生成。
- ダークテーマ、日本語UI、画面幅に応じたFold向けレイアウト。

成人向けRPへの対応は、選択するモデルと提供元の利用条件・出力ポリシーに依存します。モデルIDを自由に指定できますが、特定の出力への対応をアプリ側で保証するものではありません。

## 記憶とデータ

要約は「既存の記憶＋新しく要約するログ」から作成し、本文と更新位置を同時に保存します。失敗時に位置を進めず、履歴原文も消しません。ローカルでは端末の同じモデルで処理し、OpenRouterを選択した場合のみAPI料金が発生します。要約は圧縮された記憶で、全文の完全な再現を保証しません。

1.2.0では既存の会話・要約をキャラごとの「メインストーリー」へ移行します。メッセージIDと要約の更新位置を保持し、以降はプロットごとに独立して保存します。

ローカルでは会話・設定・記憶を外部送信しません。OpenRouterを明示選択した場合は会話の文脈をOpenRouterとモデル提供元へ送信します。APIキーはOpenRouterの認証だけに使用します。Roomの会話DB自体は暗号化していません。OSのクラウドバックアップと端末移行は無効です。アンインストールでデータが消えます。MVPにはエクスポート機能はありません。

ローカルは実際のトークナイザーで入力を数え、文脈4096・標準返信256トークン、要約用の512トークンと余白を確保します。返信上限はプロット別に変更できます。返信は生成途中から表示し、失敗理由は会話画面に残します。OpenRouterはUTF-8バイト数から保守的に見積もり、標準出力1024トークンを確保します。OpenRouterのID直接入力時は実際の上限以下に設定してください。設定や直近の投稿だけで上限を超える場合はエラーを出し、会話を黙って切り捨てません。

## 更新インストールの署名

このリポジトリは**署名鍵の固定を設定済み**です。通常の利用では署名鍵の手動生成・登録は必要ありません。ActionsはGitHubのRepository secret `MEL_DEBUG_KEYSTORE_BASE64` に保存した専用鍵から、インストール可能なデバッグ署名済みAPKを生成します。

APKの署名証明書を`config/signing-certificate.sha256`と照合し、鍵が消えたり別の鍵に変わった場合は公開前にビルドを停止します。更新時は同じアプリを上書きインストールすることで、Roomの履歴を保持できます。PRで生成するテスト用APKは別名のアーティファクトに分けています。

このSecretは**署名鍵**用です。OpenRouter APIキーとは別です。署名鍵は作り直さずに継続使用してください。秘密鍵のバックアップはGit管理対象外の`.tools/signing/mel-chat-debug.keystore`に保存しています。秘密鍵そのものをソースコードや公開アーティファクトへ追加しないでください。

CIは復元した鍵を`MEL_SIGNING_KEYSTORE_FILE`で明示指定します。ローカルの`build-local.ps1`もバックアップが存在する場合は同じ鍵を使います。

個人利用向けのデバッグビルドで、Playストア公開向けのリリース設定は含みません。管理用の`scripts/configure-signing.py`は既存のGit認証を使用し、鍵をGitHubの公開鍵で暗号化してから登録します。登録済みのSecretは上書きしません。

## 開発と検証

JDK 17、Android SDK platform 36 / build-tools 35.0.0、NDK 27.2.12479018、CMake 3.22.1。APKはarm64-v8a向け。

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Windowsでは`gradlew.bat`を使用します。この日本語パス・OneDrive環境では`./scripts/build-local.ps1`を使います。NDK用に公開ソースとSDKをASCIIの一時パスへコピーし、出力先を`.tools/local-build-output.txt`へ記録します。署名鍵はコピーせず元のバックアップを参照します。`.tools`はGit管理しません。`setup-local.ps1`は必要なツールを取得してSDKライセンスを承諾します。

APK: `app/build/outputs/apk/debug/app-debug.apk`。テスト: `app/build/reports/tests/testDebugUnitTest/index.html`。Lint: `app/build/reports/lint-results-debug.html`。

テストは文脈予算、要約の更新位置、キャラクター間の記憶分離、モデル切り替え、履歴の永続化、再試行時の重複防止、削除時の参照整合性、HTTP認証・リクエスト・エラー処理・キャンセルを検証します。OpenRouter実サービスとの会話には利用者のAPIキーが必要です。

既存のMEL StudioからGradle Wrapper・互換性のある依存バージョン・Keystore暗号化処理・llama.cppソースを流用しました。従来のアーカイブとAPKは保持し、新アプリは`jp.mel.chat`として独立しています。[第三者ライセンス](THIRD_PARTY_NOTICES.md)も参照してください。

## API仕様の参照

- [OpenRouter Chat Completions](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)
- [OpenRouter Models](https://openrouter.ai/docs/api/api-reference/models/get-models)
- [AGP 8.13 互換性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
