# MatchDay Widget v11.5

- SofaScoreを中心に試合・リーグ・チーム・選手データを取得
- APKのビルドと署名検証が成功した場合だけGitHub Releaseへ自動公開
- Releaseには `FootballFixtureWidget.apk` と `FootballFixtureWidget.apk.sha256` を自動添付
- 同一バージョンを再ビルドした場合はReleaseアセットを自動で置き換え
- Releaseを `latest` に設定し、アプリ内アップデートが最新版を検出できるようにする
- ビルド失敗時はReleaseを作成・更新しない

v11.4: SofaScore tournament API 404 fix
v11.2: nullable Int build fix + automatic share sheet on build failure

## v11.6 SofaScore HTTP 403 fix
- Adds `X-Requested-With: XMLHttpRequest` and browser/XHR-style request headers for SofaScore requests.
- Retries SofaScore via `www.sofascore.com/api/v1` when the API host is challenged.
- Prefers the www-host tournament directory on mobile networks.
- Keeps public GitHub Release auto-publish and fixed signing.
