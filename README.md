# MatchDay Widget v9

Android football fixture widget with a FotMob/SofaScore-style favorite-team picker.

## v9 changes
- Favorite search now queries both FotMob and SofaScore and merges team results.
- Search results open in a modern bottom sheet with team crest, country/source, and instant add/remove.
- Popular-team quick picks and league browsing remain available.
- Favorites support up to 10 teams and preserve previous v7/v8 selections.
- Four launcher icons are bundled and can be switched from Settings.
- Automatic updates: GitHub Actions creates a signed release APK; the app checks every 12 hours, downloads a newer APK automatically, and notifies when it is ready to install.
- Android requires the final install confirmation tap. Silent APK installation is not possible for a normal app.

## Update channel
`res/values/strings.xml` contains `__UPDATE_REPO__`. The included one-tap Termux script replaces it with the GitHub repository used for the build. In-app update checks work without credentials when that repository/release is publicly readable. A private repository cannot be queried anonymously by the installed app.
