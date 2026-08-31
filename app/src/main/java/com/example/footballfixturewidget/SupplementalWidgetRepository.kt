package com.example.footballfixturewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PlayerFixtureItem(
    val playerId: Int,
    val playerName: String,
    val teamName: String,
    val fixture: NextFixture?
)

data class LeagueFixtureItem(
    val leagueId: Int,
    val leagueName: String,
    val country: String,
    val fixture: NextFixture?
)

/** v11 supplemental cache. Rich data lives in AdvancedStatsRepository. */
object SupplementalWidgetRepository {
    private const val PREFS = "fixture_prefs"
    private const val KEY_PLAYER_CACHE = "player_widget_cache"
    private const val KEY_LEAGUE_CACHE = "league_widget_cache"
    private const val KEY_PLAYER_UPDATED = "player_widget_updated"
    private const val KEY_LEAGUE_UPDATED = "league_widget_updated"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refreshPlayers(context: Context): List<PlayerFixtureItem> {
        val saved = FavoriteEntityRepository.getFavoritePlayers(context)
        val hydrated = saved.map { FavoriteEntityRepository.hydratePlayerTeam(it) }
        if (hydrated != saved) FavoriteEntityRepository.saveFavoritePlayers(context, hydrated)
        val rich = AdvancedStatsRepository.refreshPlayerExtras(context, hydrated)
        val previous = loadPlayerCache(context).associateBy { it.playerId }
        val items = hydrated.map { player ->
            val extra = rich[player.id]
            val event = extra?.live?.takeIf { it.isLive } ?: extra?.next
            val fixture = event?.asFixture(player.id, player.name, extra?.sofaTeamId ?: 0)
                ?: previous[player.id]?.fixture
                ?: runCatching {
                    val team = when {
                        player.fotmobTeamId > 0 -> FavoriteTeam(player.fotmobTeamId, player.teamName.ifBlank { "Team" }, fotmobId = player.fotmobTeamId, sofascoreId = player.sofascoreTeamId)
                        player.sofascoreTeamId > 0 -> FavoriteTeam(-player.sofascoreTeamId, player.teamName.ifBlank { "Team" }, sofascoreId = player.sofascoreTeamId)
                        else -> null
                    }
                    team?.let { FixtureRepository.fetchNextFixtureForTeam(it) }
                }.getOrNull()
            PlayerFixtureItem(player.id, player.name, player.teamName.ifBlank { "所属チーム不明" }, fixture)
        }
        savePlayerCache(context, items)
        MatchPhaseScheduler.scheduleFixtures(context, items.mapNotNull { it.fixture })
        prefs(context).edit().putLong(KEY_PLAYER_UPDATED, System.currentTimeMillis()).apply()
        return items
    }

    fun refreshLeagues(context: Context): List<LeagueFixtureItem> {
        val leagues = FavoriteEntityRepository.getFavoriteLeagues(context)
        val rich = AdvancedStatsRepository.refreshLeagueRounds(context, leagues)
        val previous = loadLeagueCache(context).associateBy { it.leagueId }
        val items = leagues.map { league ->
            val event = rich[league.id]?.events?.firstOrNull { it.isLive }
                ?: rich[league.id]?.events?.firstOrNull { it.isScheduled }
                ?: rich[league.id]?.events?.firstOrNull()
            val fixture = event?.asFixture(-league.id, league.name) ?: previous[league.id]?.fixture
            LeagueFixtureItem(league.id, league.name, league.country, fixture)
        }
        saveLeagueCache(context, items)
        MatchPhaseScheduler.scheduleFixtures(context, items.mapNotNull { it.fixture })
        prefs(context).edit().putLong(KEY_LEAGUE_UPDATED, System.currentTimeMillis()).apply()
        return items
    }

    fun loadPlayerCache(context: Context): List<PlayerFixtureItem> {
        val raw = prefs(context).getString(KEY_PLAYER_CACHE, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    add(PlayerFixtureItem(o.optInt("playerId"), o.optString("playerName"), o.optString("teamName"), o.optJSONObject("fixture")?.let(::fixtureFromJson)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadLeagueCache(context: Context): List<LeagueFixtureItem> {
        val raw = prefs(context).getString(KEY_LEAGUE_CACHE, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    add(LeagueFixtureItem(o.optInt("leagueId"), o.optString("leagueName"), o.optString("country"), o.optJSONObject("fixture")?.let(::fixtureFromJson)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun playerUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_PLAYER_UPDATED, 0L)
    fun leagueUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_LEAGUE_UPDATED, 0L)

    private fun savePlayerCache(context: Context, items: List<PlayerFixtureItem>) {
        val a = JSONArray()
        items.forEach { item -> a.put(JSONObject().apply {
            put("playerId", item.playerId); put("playerName", item.playerName); put("teamName", item.teamName); item.fixture?.let { put("fixture", fixtureToJson(it)) }
        }) }
        prefs(context).edit().putString(KEY_PLAYER_CACHE, a.toString()).apply()
    }

    private fun saveLeagueCache(context: Context, items: List<LeagueFixtureItem>) {
        val a = JSONArray()
        items.forEach { item -> a.put(JSONObject().apply {
            put("leagueId", item.leagueId); put("leagueName", item.leagueName); put("country", item.country); item.fixture?.let { put("fixture", fixtureToJson(it)) }
        }) }
        prefs(context).edit().putString(KEY_LEAGUE_CACHE, a.toString()).apply()
    }

    private fun fixtureToJson(f: NextFixture): JSONObject = JSONObject().apply {
        put("teamId", f.teamId); put("teamName", f.teamName); put("utcDate", f.utcDate); put("opponent", f.opponent); put("competition", f.competition)
        put("isHome", f.isHome); put("hasMatch", f.hasMatch); put("homeTeamName", f.homeTeamName); put("homeTeamShortName", f.homeTeamShortName)
        put("awayTeamName", f.awayTeamName); put("awayTeamShortName", f.awayTeamShortName); put("fotmobMatchId", f.fotmobMatchId); put("fotmobUrl", f.fotmobUrl)
        put("sofascoreEventId", f.sofascoreEventId); put("sofascoreUrl", f.sofascoreUrl)
    }

    private fun fixtureFromJson(o: JSONObject): NextFixture = NextFixture(
        teamId = o.optInt("teamId"), teamName = o.optString("teamName"), utcDate = o.optString("utcDate"), opponent = o.optString("opponent"), competition = o.optString("competition"),
        isHome = o.optBoolean("isHome", true), hasMatch = o.optBoolean("hasMatch", true), homeTeamName = o.optString("homeTeamName"), homeTeamShortName = o.optString("homeTeamShortName"),
        awayTeamName = o.optString("awayTeamName"), awayTeamShortName = o.optString("awayTeamShortName"), fotmobMatchId = o.optLong("fotmobMatchId"), fotmobUrl = o.optString("fotmobUrl"),
        sofascoreEventId = o.optLong("sofascoreEventId"), sofascoreUrl = o.optString("sofascoreUrl")
    )
}
