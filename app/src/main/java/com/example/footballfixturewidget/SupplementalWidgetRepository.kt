package com.example.footballfixturewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

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

        val previous = loadPlayerCache(context).associateBy { it.playerId }
        val items = hydrated.map { player ->
            val team = when {
                player.fotmobTeamId > 0 -> FavoriteTeam(
                    id = player.fotmobTeamId,
                    name = player.teamName.ifBlank { "Team" },
                    fotmobId = player.fotmobTeamId,
                    sofascoreId = player.sofascoreTeamId
                )
                player.sofascoreTeamId > 0 -> FavoriteTeam(
                    id = -player.sofascoreTeamId,
                    name = player.teamName.ifBlank { "Team" },
                    sofascoreId = player.sofascoreTeamId
                )
                else -> null
            }
            if (team == null) {
                PlayerFixtureItem(player.id, player.name, player.teamName.ifBlank { "所属チーム不明" }, null)
            } else {
                val fixture = runCatching { FixtureRepository.fetchNextFixtureForTeam(team) }.getOrNull()
                    ?: previous[player.id]?.fixture
                PlayerFixtureItem(player.id, player.name, player.teamName.ifBlank { team.name }, fixture)
            }
        }
        savePlayerCache(context, items)
        prefs(context).edit().putLong(KEY_PLAYER_UPDATED, System.currentTimeMillis()).apply()
        return items
    }

    fun refreshLeagues(context: Context): List<LeagueFixtureItem> {
        val leagues = FavoriteEntityRepository.getFavoriteLeagues(context)
        val previous = loadLeagueCache(context).associateBy { it.leagueId }
        val items = leagues.map { league ->
            val fixture = runCatching { fetchNextLeagueFixture(league) }.getOrNull()
                ?: previous[league.id]?.fixture
            LeagueFixtureItem(league.id, league.name, league.country, fixture)
        }
        saveLeagueCache(context, items)
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
                    add(PlayerFixtureItem(
                        playerId = o.optInt("playerId"),
                        playerName = o.optString("playerName"),
                        teamName = o.optString("teamName"),
                        fixture = o.optJSONObject("fixture")?.let(::fixtureFromJson)
                    ))
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
                    add(LeagueFixtureItem(
                        leagueId = o.optInt("leagueId"),
                        leagueName = o.optString("leagueName"),
                        country = o.optString("country"),
                        fixture = o.optJSONObject("fixture")?.let(::fixtureFromJson)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun playerUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_PLAYER_UPDATED, 0L)
    fun leagueUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_LEAGUE_UPDATED, 0L)

    private fun savePlayerCache(context: Context, items: List<PlayerFixtureItem>) {
        val a = JSONArray()
        items.forEach { item ->
            a.put(JSONObject().apply {
                put("playerId", item.playerId)
                put("playerName", item.playerName)
                put("teamName", item.teamName)
                item.fixture?.let { put("fixture", fixtureToJson(it)) }
            })
        }
        prefs(context).edit().putString(KEY_PLAYER_CACHE, a.toString()).apply()
    }

    private fun saveLeagueCache(context: Context, items: List<LeagueFixtureItem>) {
        val a = JSONArray()
        items.forEach { item ->
            a.put(JSONObject().apply {
                put("leagueId", item.leagueId)
                put("leagueName", item.leagueName)
                put("country", item.country)
                item.fixture?.let { put("fixture", fixtureToJson(it)) }
            })
        }
        prefs(context).edit().putString(KEY_LEAGUE_CACHE, a.toString()).apply()
    }

    private fun fetchNextLeagueFixture(league: FavoriteLeague): NextFixture? {
        val root = requestObject(
            "https://www.fotmob.com/api/data/leagues?id=${league.id}&ccode3=JPN",
            "https://www.fotmob.com/api/leagues?id=${league.id}"
        )
        val now = Instant.now()
        var bestTime: Instant? = null
        var best: NextFixture? = null

        walk(root, 0) { match ->
            val home = match.optJSONObject("home") ?: return@walk
            val away = match.optJSONObject("away") ?: return@walk
            val kickoff = parseKickoff(match) ?: return@walk
            if (kickoff.isBefore(now.minusSeconds(30))) return@walk
            val status = match.optJSONObject("status")
            if (status?.optBoolean("finished", false) == true || status?.optBoolean("cancelled", false) == true) return@walk
            if (bestTime != null && !kickoff.isBefore(bestTime)) return@walk

            val homeName = home.optString("name").ifBlank { home.optString("shortName") }
            val awayName = away.optString("name").ifBlank { away.optString("shortName") }
            if (homeName.isBlank() || awayName.isBlank()) return@walk
            val matchId = flexibleLong(match, "id")
            val page = match.optString("pageUrl")
            val url = when {
                page.startsWith("https://", true) -> page
                page.startsWith("/") -> "https://www.fotmob.com$page"
                page.isNotBlank() -> "https://www.fotmob.com/$page"
                matchId > 0 -> "https://www.fotmob.com/match/$matchId"
                else -> ""
            }
            bestTime = kickoff
            best = NextFixture(
                teamId = -league.id,
                teamName = league.name,
                utcDate = kickoff.toString(),
                opponent = "$homeName vs $awayName",
                competition = league.name,
                isHome = true,
                homeTeamName = homeName,
                homeTeamShortName = home.optString("shortName").ifBlank { homeName },
                awayTeamName = awayName,
                awayTeamShortName = away.optString("shortName").ifBlank { awayName },
                fotmobMatchId = matchId,
                fotmobUrl = url
            )
        }
        return best
    }

    private fun fixtureToJson(f: NextFixture): JSONObject = JSONObject().apply {
        put("teamId", f.teamId)
        put("teamName", f.teamName)
        put("utcDate", f.utcDate)
        put("opponent", f.opponent)
        put("competition", f.competition)
        put("isHome", f.isHome)
        put("hasMatch", f.hasMatch)
        put("homeTeamName", f.homeTeamName)
        put("homeTeamShortName", f.homeTeamShortName)
        put("awayTeamName", f.awayTeamName)
        put("awayTeamShortName", f.awayTeamShortName)
        put("fotmobMatchId", f.fotmobMatchId)
        put("fotmobUrl", f.fotmobUrl)
        put("sofascoreEventId", f.sofascoreEventId)
        put("sofascoreUrl", f.sofascoreUrl)
    }

    private fun fixtureFromJson(o: JSONObject): NextFixture = NextFixture(
        teamId = o.optInt("teamId"),
        teamName = o.optString("teamName"),
        utcDate = o.optString("utcDate"),
        opponent = o.optString("opponent"),
        competition = o.optString("competition"),
        isHome = o.optBoolean("isHome", true),
        hasMatch = o.optBoolean("hasMatch", true),
        homeTeamName = o.optString("homeTeamName"),
        homeTeamShortName = o.optString("homeTeamShortName"),
        awayTeamName = o.optString("awayTeamName"),
        awayTeamShortName = o.optString("awayTeamShortName"),
        fotmobMatchId = flexibleLong(o, "fotmobMatchId"),
        fotmobUrl = o.optString("fotmobUrl"),
        sofascoreEventId = flexibleLong(o, "sofascoreEventId"),
        sofascoreUrl = o.optString("sofascoreUrl")
    )

    private fun requestObject(vararg urls: String): JSONObject {
        var last: Throwable? = null
        for (endpoint in urls) {
            try {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 11000
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                    setRequestProperty("Referer", "https://www.fotmob.com/")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
                }
                try {
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val value = JSONTokener(body).nextValue()
                    if (value is JSONObject) return value
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("リーグ日程を取得できませんでした")
    }

    private fun parseKickoff(match: JSONObject): Instant? {
        val values = listOf(
            match.optJSONObject("status")?.optString("utcTime").orEmpty(),
            match.optString("utcTime"),
            match.optString("matchTimeUTCDate")
        )
        for (value in values) {
            if (value.isBlank()) continue
            val parsed = runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { Instant.parse(if (value.endsWith("Z")) value else "${value}Z") }.getOrNull()
            if (parsed != null) return parsed
        }
        val ts = flexibleLong(match, "timeTS")
        if (ts > 0L) return if (ts > 10_000_000_000L) Instant.ofEpochMilli(ts) else Instant.ofEpochSecond(ts)
        return null
    }

    private fun walk(node: Any?, depth: Int, visit: (JSONObject) -> Unit) {
        if (node == null || depth > 12) return
        when (node) {
            is JSONObject -> {
                if (node.optJSONObject("home") != null && node.optJSONObject("away") != null) visit(node)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walk(node.opt(key), depth + 1, visit)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1, visit)
        }
    }

    private fun flexibleLong(obj: JSONObject, key: String): Long = when (val value = obj.opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }
}
