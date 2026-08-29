package com.example.footballfixturewidget

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A team selected by the user. */
data class FavoriteTeam(
    val id: Int,
    val name: String
)

/** The next scheduled match for one favorite team. */
data class NextFixture(
    val teamId: Int,
    val teamName: String,
    val utcDate: String,
    val opponent: String,
    val competition: String,
    val isHome: Boolean,
    val hasMatch: Boolean = true,
    val homeTeamName: String = "",
    val homeTeamShortName: String = "",
    val homeTeamTla: String = "",
    val awayTeamName: String = "",
    val awayTeamShortName: String = "",
    val awayTeamTla: String = "",
    val fotmobMatchId: Long = 0L,
    val fotmobUrl: String = "",
    val sofascoreEventId: Long = 0L,
    val sofascoreUrl: String = ""
)

data class WidgetState(
    val fixtures: List<NextFixture>,
    val updatedAt: Long,
    val error: String? = null
)

object FixtureRepository {
    private const val PREFS = "fixture_prefs"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_FAVORITES = "favorite_teams"
    private const val KEY_CACHE = "fixture_cache"
    private const val KEY_WIDGET_COLOR = "widget_color"
    private const val KEY_TAP_TARGET = "tap_target"

    const val MAX_FAVORITES = 10
    const val DEFAULT_WIDGET_COLOR = 0xFF17202A.toInt()

    const val TAP_NONE = "none"
    const val TAP_FOTMOB = "fotmob"
    const val TAP_SOFASCORE = "sofascore"
    const val TAP_SETTINGS = "settings"
    const val TAP_ONEFOOTBALL = "onefootball"
    const val TAP_FLASHSCORE = "flashscore"
    const val TAP_LIVESCORE = "livescore"
    const val TAP_365SCORES = "365scores"

    val COMPETITIONS = listOf(
        "Premier League" to "PL",
        "La Liga" to "PD",
        "Bundesliga" to "BL1",
        "Serie A" to "SA",
        "Ligue 1" to "FL1",
        "Eredivisie" to "DED",
        "Primeira Liga" to "PPL",
        "Champions League" to "CL"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "")?.trim().orEmpty()

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun getWidgetColor(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_COLOR, DEFAULT_WIDGET_COLOR)

    fun saveWidgetColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_COLOR, color).apply()
    }

    fun getTapTarget(context: Context): String =
        prefs(context).getString(KEY_TAP_TARGET, TAP_NONE) ?: TAP_NONE

    fun saveTapTarget(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TAP_TARGET, value).apply()
    }

    fun getFavoriteTeams(context: Context): List<FavoriteTeam> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optInt("id", -1)
                    val name = item.optString("name")
                    if (id > 0 && name.isNotBlank()) add(FavoriteTeam(id, name))
                }
            }.distinctBy { it.id }.take(MAX_FAVORITES)
        }.getOrDefault(emptyList())
    }

    fun saveFavoriteTeams(context: Context, teams: List<FavoriteTeam>) {
        val clean = teams.distinctBy { it.id }.take(MAX_FAVORITES)
        val array = JSONArray()
        clean.forEach { team ->
            array.put(JSONObject().apply {
                put("id", team.id)
                put("name", team.name)
            })
        }
        prefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    fun addFavoriteTeam(context: Context, team: FavoriteTeam): Boolean {
        val current = getFavoriteTeams(context).toMutableList()
        if (current.any { it.id == team.id }) return true
        if (current.size >= MAX_FAVORITES) return false
        current += team
        saveFavoriteTeams(context, current)
        return true
    }

    fun removeFavoriteTeam(context: Context, teamId: Int) {
        saveFavoriteTeams(context, getFavoriteTeams(context).filterNot { it.id == teamId })
    }

    fun moveFavoriteToTop(context: Context, teamId: Int) {
        val current = getFavoriteTeams(context).toMutableList()
        val team = current.firstOrNull { it.id == teamId } ?: return
        current.removeAll { it.id == teamId }
        current.add(0, team)
        saveFavoriteTeams(context, current)
    }

    private fun request(context: Context, endpoint: String): JSONObject {
        val token = getToken(context)
        if (token.isBlank()) throw IllegalStateException("APIキー未設定")

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 9000
            setRequestProperty("X-Auth-Token", token)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw IllegalStateException(message)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Load selectable clubs for the league/competition chosen in Settings. */
    fun fetchTeamsForCompetition(context: Context, competitionCode: String): List<FavoriteTeam> {
        val root = request(context, "https://api.football-data.org/v4/competitions/$competitionCode/teams")
        val teams = root.optJSONArray("teams") ?: JSONArray()
        return buildList {
            for (i in 0 until teams.length()) {
                val team = teams.getJSONObject(i)
                val id = team.optInt("id", -1)
                val name = team.optString("shortName").ifBlank { team.optString("name") }
                if (id > 0 && name.isNotBlank()) add(FavoriteTeam(id, name))
            }
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** Resolve a manually entered football-data.org Team ID. */
    fun resolveTeam(context: Context, teamId: Int): FavoriteTeam {
        val root = request(context, "https://api.football-data.org/v4/teams/$teamId")
        val name = root.optString("shortName").ifBlank { root.optString("name") }.ifBlank { "Team #$teamId" }
        return FavoriteTeam(teamId, name)
    }

    private fun fetchNextFixture(context: Context, team: FavoriteTeam): NextFixture? {
        val today = LocalDate.now()
        val dateTo = today.plusMonths(9)
        val endpoint = "https://api.football-data.org/v4/teams/${team.id}/matches?dateFrom=$today&dateTo=$dateTo&limit=100"
        val root = request(context, endpoint)
        val matches = root.optJSONArray("matches") ?: JSONArray()
        val now = Instant.now()
        var bestInstant: Instant? = null
        var bestFixture: NextFixture? = null

        for (i in 0 until matches.length()) {
            val match = matches.getJSONObject(i)
            val utcDate = match.optString("utcDate")
            val instant = runCatching { Instant.parse(utcDate) }.getOrNull() ?: continue
            if (instant.isBefore(now.minusSeconds(60))) continue

            val home = match.optJSONObject("homeTeam") ?: JSONObject()
            val away = match.optJSONObject("awayTeam") ?: JSONObject()
            val homeId = home.optInt("id", -1)
            val awayId = away.optInt("id", -1)
            if (homeId != team.id && awayId != team.id) continue

            if (bestInstant == null || instant.isBefore(bestInstant)) {
                val isHome = homeId == team.id
                val opponent = (if (isHome) away else home)
                    .let { it.optString("shortName").ifBlank { it.optString("name") } }
                    .ifBlank { "Opponent" }
                val competition = match.optJSONObject("competition")?.optString("name")
                    .orEmpty().ifBlank { "Competition" }
                bestInstant = instant
                bestFixture = NextFixture(
                    teamId = team.id,
                    teamName = team.name,
                    utcDate = utcDate,
                    opponent = opponent,
                    competition = competition,
                    isHome = isHome,
                    hasMatch = true,
                    homeTeamName = home.optString("name"),
                    homeTeamShortName = home.optString("shortName"),
                    homeTeamTla = home.optString("tla"),
                    awayTeamName = away.optString("name"),
                    awayTeamShortName = away.optString("shortName"),
                    awayTeamTla = away.optString("tla")
                )
            }
        }
        return bestFixture
    }

    fun fetchAll(context: Context): WidgetState {
        val favorites = getFavoriteTeams(context)
        if (favorites.isEmpty()) {
            val empty = WidgetState(emptyList(), System.currentTimeMillis(), "お気に入りチーム未設定")
            saveCache(context, empty)
            return empty
        }
        if (getToken(context).isBlank()) {
            return loadCache(context).copy(error = "APIキー未設定")
        }

        val previous = loadCache(context).fixtures.associateBy { it.teamId }
        val fixtures = mutableListOf<NextFixture>()
        val errors = mutableListOf<String>()

        favorites.forEach { team ->
            try {
                val next = fetchNextFixture(context, team) ?: NextFixture(
                    teamId = team.id,
                    teamName = team.name,
                    utcDate = "",
                    opponent = "次の試合予定なし",
                    competition = "日程未発表",
                    isHome = true,
                    hasMatch = false
                )
                fixtures += next
            } catch (t: Throwable) {
                errors += "${team.name}: ${t.message ?: "取得失敗"}"
                fixtures += previous[team.id] ?: NextFixture(
                    teamId = team.id,
                    teamName = team.name,
                    utcDate = "",
                    opponent = "取得できませんでした",
                    competition = "更新失敗",
                    isHome = true,
                    hasMatch = false
                )
            }
        }

        val linkedFixtures = ExternalMatchResolver.resolveForTarget(fixtures, getTapTarget(context))

        val state = WidgetState(
            fixtures = linkedFixtures,
            updatedAt = System.currentTimeMillis(),
            error = errors.firstOrNull()
        )
        saveCache(context, state)
        return state
    }

    private fun saveCache(context: Context, state: WidgetState) {
        val root = JSONObject().apply {
            put("updatedAt", state.updatedAt)
            val array = JSONArray()
            state.fixtures.forEach { f ->
                array.put(JSONObject().apply {
                    put("teamId", f.teamId)
                    put("teamName", f.teamName)
                    put("utcDate", f.utcDate)
                    put("opponent", f.opponent)
                    put("competition", f.competition)
                    put("isHome", f.isHome)
                    put("hasMatch", f.hasMatch)
                    put("homeTeamName", f.homeTeamName)
                    put("homeTeamShortName", f.homeTeamShortName)
                    put("homeTeamTla", f.homeTeamTla)
                    put("awayTeamName", f.awayTeamName)
                    put("awayTeamShortName", f.awayTeamShortName)
                    put("awayTeamTla", f.awayTeamTla)
                    put("fotmobMatchId", f.fotmobMatchId)
                    put("fotmobUrl", f.fotmobUrl)
                    put("sofascoreEventId", f.sofascoreEventId)
                    put("sofascoreUrl", f.sofascoreUrl)
                })
            }
            put("fixtures", array)
        }
        prefs(context).edit().putString(KEY_CACHE, root.toString()).apply()
    }

    fun loadCache(context: Context): WidgetState {
        val raw = prefs(context).getString(KEY_CACHE, null)
            ?: return WidgetState(emptyList(), 0L, null)
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("fixtures") ?: JSONArray()
            val fixtures = buildList {
                for (i in 0 until array.length()) {
                    val f = array.getJSONObject(i)
                    add(
                        NextFixture(
                            teamId = f.optInt("teamId"),
                            teamName = f.optString("teamName"),
                            utcDate = f.optString("utcDate"),
                            opponent = f.optString("opponent"),
                            competition = f.optString("competition"),
                            isHome = f.optBoolean("isHome"),
                            hasMatch = f.optBoolean("hasMatch", true),
                            homeTeamName = f.optString("homeTeamName"),
                            homeTeamShortName = f.optString("homeTeamShortName"),
                            homeTeamTla = f.optString("homeTeamTla"),
                            awayTeamName = f.optString("awayTeamName"),
                            awayTeamShortName = f.optString("awayTeamShortName"),
                            awayTeamTla = f.optString("awayTeamTla"),
                            fotmobMatchId = f.optLong("fotmobMatchId", 0L),
                            fotmobUrl = f.optString("fotmobUrl"),
                            sofascoreEventId = f.optLong("sofascoreEventId", 0L),
                            sofascoreUrl = f.optString("sofascoreUrl")
                        )
                    )
                }
            }
            WidgetState(fixtures, root.optLong("updatedAt", 0L), null)
        }.getOrElse { WidgetState(emptyList(), 0L, null) }
    }

    /** Japanese date + weekday + 24-hour time, e.g. 8/30 (日) 22:00. */
    fun formatDate(utcDate: String): String = runCatching {
        Instant.parse(utcDate)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M/d (E) HH:mm", Locale.JAPAN))
    }.getOrDefault("日時未定")

    fun formatUpdatedAt(epochMillis: Long): String {
        if (epochMillis <= 0) return "未更新"
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPAN))
    }

    fun parseColorOrNull(value: String): Int? = runCatching {
        val normalized = value.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color.parseColor(normalized)
    }.getOrNull()

    fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    fun preferredTextColor(background: Int): Int =
        if (Color.luminance(background) > 0.55) Color.BLACK else Color.WHITE

    fun secondaryTextColor(background: Int): Int {
        val primary = preferredTextColor(background)
        return if (primary == Color.WHITE) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
    }

    fun rowColor(background: Int): Int {
        val primary = preferredTextColor(background)
        val overlay = if (primary == Color.WHITE) Color.WHITE else Color.BLACK
        return blend(background, overlay, 0.10f)
    }

    private fun blend(base: Int, overlay: Int, amount: Float): Int {
        fun channel(a: Int, b: Int): Int = (a * (1f - amount) + b * amount).toInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(base), Color.red(overlay)),
            channel(Color.green(base), Color.green(overlay)),
            channel(Color.blue(base), Color.blue(overlay))
        )
    }
}
