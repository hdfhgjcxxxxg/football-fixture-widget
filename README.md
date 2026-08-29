# MatchDay Widget v7

Android home-screen football fixture widget.

## v7 changes

- No football-data.org API key required.
- Automatically connects to FotMob public JSON data.
- Dynamically loads the full FotMob league/competition directory instead of a small hard-coded list.
- Global team-name search.
- Up to 10 favourite teams, with a user-selectable first team.
- Each favourite shows the next fixture in Japanese date + weekday + 24-hour time.
- Live countdown using Android Chronometer (hours:minutes:seconds, including 24+ hours).
- Exact FotMob match link comes directly from FotMob match data.
- SofaScore event link is resolved from kickoff + home/away names.
- Modern Material 3 settings UI with dynamic colors, cards, dropdowns and custom widget color.
- Team crests in the widget (disk-cached).
- Existing pre-v7 favourites are automatically migrated by team name from football-data.org IDs to FotMob IDs.

## Data source note

FotMob's web JSON endpoints do not require a user API key, but they are not a guaranteed public developer API and may change. The app caches the last successful widget state so a temporary upstream problem does not erase the widget.
