# MatchDay Widget v12.5

## Dual API mode

v12 adds a global data-source switch in the app settings:

- **両方（自動統合）** — queries FotMob and SofaScore, merges matching teams/players/leagues, and falls back to whichever provider is available.
- **FotMob** — strict FotMob mode.
- **SofaScore** — strict SofaScore mode.

Favorites persist both FotMob and SofaScore IDs whenever they can be resolved, so changing providers does not require re-registering favorites.

The existing team/player/league widgets, per-widget selection, unlimited favorites, countdown display toggle, advanced player/team stats, fixed signing, public GitHub repository workflow, verified APK build, GitHub Release upload, and build-error sharing remain enabled.

### Important note about SofaScore
SofaScore may return HTTP 403 to unofficial clients. In **両方** mode this does not stop the app: FotMob is used automatically when SofaScore is unavailable. **SofaScore** strict mode reports the SofaScore error instead of silently using FotMob.


## v12.1
- データ取得元メニューを「結合 / SofaScore / FotMob」の3択に修正。
- 選択済みの文字列でAutoCompleteTextViewが絞り込まれ、1件しか表示されない問題を修正。


## v12.5
- Fixed-signing recovery is now fail-safe: an existing signing backup is restored before any new key can be generated.
- Existing installs are protected from accidental signing-key rotation.
- Update checks/downloads are deduplicated and the 12-hour UI label now matches the actual schedule.
- Downloaded update APKs are validated for package name, version and signing certificate before installation.
- Android 13+ notification permission and a retry-install button were added for update reliability.
- The one-tap Termux flow verifies the GitHub Release SHA-256 before opening Android Package Installer.
- Workflow run selection and Git clone error handling were hardened.


## v12.9
- Android 16/TermuxでPackage InstallerにAPKを渡した際の「パッケージの解析中に問題が発生しました」対策。Termux private領域を使わず、Download上の検証済みAPKから起動します。
