# Football Fixture Widget v3

Androidホーム画面に、お気に入りクラブ（最大10チーム）の「次の試合」を一覧表示するウィジェットです。

## v3 の主な機能

- 初期チーム固定なし。自分でリーグまたは football-data.org Team ID から選択
- お気に入り最大10チーム
- 選択順を保持し、任意のチームを先頭（初期）へ変更可能
- 各チームの次の1試合を表示
- 日付 + 日本語曜日 + 24時間表記（例: `8/30 (日) 22:00`）
- 大会名、ホーム `vs` / アウェイ `@` 表示
- 最大10チームをウィジェット内でスクロール
- 30分更新 + 右上 `↻` 手動更新
- RGB / HEXによるウィジェット背景色変更
- 背景色に応じた文字色の自動調整
- タップ先を選択
  - **FotMob: 表示中のその試合を直接開く**
  - **SofaScore: 表示中のその試合を直接開く**
  - OneFootball
  - Flashscore
  - LiveScore
  - 365Scores
  - 設定画面
  - 何もしない

## FotMob / SofaScore の直接試合リンク

football-data.org と FotMob / SofaScore では試合IDが異なります。
v3ではウィジェット更新時に、キックオフ時刻 + ホーム/アウェイのチーム名を照合して外部サービスの試合IDを自動解決します。

- FotMob: 日別試合一覧から `matchId` を取得し、`https://www.fotmob.com/match/<matchId>` を使用
- SofaScore: 日別予定から `eventId` を取得し、`https://www.sofascore.com/event/<eventId>` を使用
- 取得成功時、行の右側に `FotMob 試合 ↗` または `SofaScore 試合 ↗` と表示
- ID解決に失敗した場合は外部アプリ本体を開くフォールバック
- 選択アプリがURLを受け取れない場合は同じ試合URLをブラウザで開く

### 注意
FotMob / SofaScore のID解決には、各社がWebサービスで利用している非公式・未保証のJSONエンドポイントを利用しています。将来仕様が変わった場合でも、football-data.orgからの試合日程表示は継続し、直接リンクだけフォールバックする設計です。

## セットアップ

1. football-data.org でAPIキーを取得
2. Android Studioでこのフォルダを開く
3. Gradle Sync
4. Android端末へインストール
5. アプリを開いてAPIキーを設定
6. リーグから最大10チームを選ぶ（またはTeam IDで追加）
7. 「チームをタップした時」で FotMob / SofaScore などを選ぶ
8. 色を設定して「設定を保存してウィジェットを更新」
9. ホーム画面長押し → ウィジェット → Football Fixturesを配置

## データ

主要な試合日程: football-data.org v4 API

外部試合IDのベストエフォート照合: FotMob / SofaScore のWeb向けエンドポイント

## v4 direct-link fix
- Widget collection taps now open a transparent Activity directly, instead of BroadcastReceiver -> external Activity.
  This avoids modern Android background activity launch restrictions.
- Sofascore uses its canonical public match URL: `/football/match/{slug}/{customId}`.
- FotMob keeps the resolved numeric matchId and uses an exact numeric match route as fallback.
- If an installed app refuses the URL, the exact match URL is opened in the browser rather than dropping to the app home screen.
- After installing v4, open Settings, select FotMob/SofaScore, Save, then press the widget refresh button once so external match IDs/URLs are re-resolved.
