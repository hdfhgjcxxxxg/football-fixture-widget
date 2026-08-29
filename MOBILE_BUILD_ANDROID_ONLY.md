# AndroidスマホだけでAPKを作る

このプロジェクトには `.github/workflows/build-apk.yml` が入っています。
GitHubへpushするとGitHub Actionsが自動で `app-debug.apk` を作ります。

必要なもの:
- Androidスマホ
- GitHubアカウント
- Termux（F-Droid/GitHub版推奨）

Termuxで:

```sh
termux-setup-storage
pkg update -y
pkg install -y git gh unzip
```

ZIPを `Download` に保存してから:

```sh
cd ~
rm -rf FootballFixtureWidget
unzip ~/storage/downloads/FootballFixtureWidget_v4_mobile_cloud_build.zip
cd FootballFixtureWidget

git config --global user.name "Android User"
git config --global user.email "android@example.invalid"

gh auth login
```

`gh auth login` では GitHub.com → HTTPS → Login with a web browser を選ぶ。

ログイン後:

```sh
git init -b main
git add .
git commit -m "Initial Android widget build"
gh repo create football-fixture-widget --private --source=. --remote=origin --push
```

push後、Actionsのビルドが開始する。
最新のビルドを待って、完了したらAPKをDownloadsへ取得:

```sh
RUN_ID=$(gh run list --workflow=build-apk.yml --limit 1 --json databaseId --jq ".[0].databaseId")
gh run watch "$RUN_ID" --exit-status
mkdir -p ~/storage/downloads/FootballFixtureWidgetAPK
gh run download "$RUN_ID" --name FootballFixtureWidget-debug-apk --dir ~/storage/downloads/FootballFixtureWidgetAPK
```

`Download/FootballFixtureWidgetAPK/app-debug.apk` をタップしてインストールする。
