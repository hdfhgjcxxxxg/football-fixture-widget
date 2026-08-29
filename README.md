# MatchDay Widget v10

Android football fixture widget with no API-key field.

## Favorites
- Teams: up to 10
- Players: up to 10
- Leagues/competitions: up to 10

The league-to-team picker now parses only explicit standings/team/match-team structures, so player names are not accepted as teams.

## Three separate widgets
Android's widget picker shows:
- MatchDay • Teams
- MatchDay • Players
- MatchDay • Leagues

When adding any widget, a configuration screen opens. Select the exact favorites for that widget instance. You can add multiple widgets of the same type and give each a different selection. Tap the widget title later to edit its selection.

## Player widget
Shows the favorite player, current club (when the provider supplies it), and the club's next fixture with 24-hour date/time + countdown.

## League widget
Shows the selected league and the next scheduled match in that competition.

## Updates
The included GitHub Actions workflow builds a fixed-signing release APK and publishes it as the latest GitHub Release. The installed app can check for releases every 12 hours and download a newer APK. Android still requires the final install/update confirmation tap.
