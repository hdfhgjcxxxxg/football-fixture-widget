# MatchDay Widget v13.0 — Google Play 公開ガイド

このリポジトリの **APK配布版を維持** しながら、Google Play用の**署名済みAAB**を作成します。

## 1. Play専用ビルド
- APK配布: これまで通り `gradle :app:assembleRelease`。既存の `build-apk.yml` は変更しません。
- Google Play: `gradle :app:bundlePlay`。出力は `app/build/outputs/bundle/play/app-play.aab`。
- Play版では GitHub APK自動更新のUIとスケジュールを無効化し、APKインストール権限・自動更新用Receiverを削除します。
- API: `targetSdk=36`、`minSdk=26`、`versionCode=2000000026`、`versionName=13.0`。
- パッケージID: `com.example.footballfixturewidget`。**Play初回リリース前に今後永続的に使うIDを決めてください**。変更すると従来APKと別アプリになります。

## 2. GitHubから署名済みAABを取得
1. この変更がmainに入ったら、Actions -> `Build Google Play AAB` -> `Run workflow` で実行します。
2. Secretsは既存APKビルドと共通:
   - `FFW_KEYSTORE_BASE64`
   - `FFW_STORE_PASSWORD`
   - `FFW_KEY_ALIAS`
   - `FFW_KEY_PASSWORD`
3. 実行成功後、Run detailの **Artifacts -> MatchDayWidget-play-aab** を保存。
4. ZIPを展開して `MatchDayWidget-v13.0-play.aab` とSHA-256を取得。
5. 秘密鍵そのもの、パスワード、base64をコミットしないこと。AABの公開は不要です。

## 3. Google Play Console
1. https://play.google.com/console/signup で開発者アカウント作成。本人確認と登録料US$25を支払います。
2. [アプリを作成] でアプリ名 `MatchDay Widget`、デフォルト言語日本語、アプリ、無料/有料、ポリシー同意を設定します。
3. [ダッシュボード] の必須項目を完了します: ストア掲載情報、カテゴリ、サポート連絡先、スクリーンショット、プライバシーポリシー（公開URLとアプリ内表示）、データセーフティ、広告、アプリのアクセス、コンテンツのレーティング、ターゲット層など。
4. [テストとリリース] -> [テスト] -> [クローズドテスト] でトラックを作成しAABをアップロード、テスターを招待します。
5. 2023-11-13以降作成した新規個人アカウントでは12人以上を14日間連続でオプトインさせ、製品版へのアクセスを申請します。審査後に製品版リリースへ進めます。
6. 2026年のAndroidデベロッパー確認・パッケージ名登録について、Play Consoleが提示する手続きに従います。

## 4. 公開前に必ず確認
- **データの権利:** FotMob / SofaScore等のデータ利用・再配布規約、リーグ・クラブのロゴと選手写真の商用配布権を確認。許諾がない素材は外す。非公式アプリであることを明確に。
- **プライバシー:** 外部APIとの通信、端末情報、広告SDK、解析SDK、クラッシュログ等の実際のデータの扱いを確認してから正確なデータセーフティ申告とプライバシーポリシーを作成。
- **動作:** 初回起動、ウィジェットの配置・設定・更新、オフライン動作、Android 16での表示、デバイス再起動後の挙動、Play版の「独自APK更新が無効」をテスト。
- **既存APKのユーザー:** Googleが新しいアプリ署名鍵を生成した場合、既存APKからPlay版へ同じパッケージ名でも署名不一致で上書きできない可能性があります。移行を重視するならPlay App Signing登録時に既存APK署名鍵を引き継ぐ方法を選び、検証してください。
- Google Play公開後の更新は`versionCode`を必ず増やしてください（Google Playでは最大 2,100,000,000）。現在は2,000,000,026です。

公式:
- https://support.google.com/googleplay/android-developer/answer/6112435?hl=ja
- https://support.google.com/googleplay/android-developer/answer/14151465?hl=ja
- https://support.google.com/googleplay/android-developer/answer/11926878?hl=ja
- https://support.google.com/googleplay/android-developer/answer/12085295?hl=ja
- https://developer.android.com/studio/publish/app-signing
