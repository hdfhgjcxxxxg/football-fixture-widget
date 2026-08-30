# MatchDay Widget v12.0

## Dual API mode

v12 adds a global data-source switch in the app settings:

- **両方（自動統合）** — queries FotMob and SofaScore, merges matching teams/players/leagues, and falls back to whichever provider is available.
- **FotMob** — strict FotMob mode.
- **SofaScore** — strict SofaScore mode.

Favorites persist both FotMob and SofaScore IDs whenever they can be resolved, so changing providers does not require re-registering favorites.

The existing team/player/league widgets, per-widget selection, unlimited favorites, countdown display toggle, advanced player/team stats, fixed signing, public GitHub repository workflow, verified APK build, GitHub Release upload, and build-error sharing remain enabled.

### Important note about SofaScore
SofaScore may return HTTP 403 to unofficial clients. In **両方** mode this does not stop the app: FotMob is used automatically when SofaScore is unavailable. **SofaScore** strict mode reports the SofaScore error instead of silently using FotMob.
