package com.example.footballfixturewidget

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** A team selected by the user. IDs are FotMob team IDs in v7+. */
data class FavoriteTeam(
    val id: Int,
    val name: String
)

data class LeagueInfo(
    val id: Int,
    val name: String,
    val country: String = "",
    val ccode: String = ""
) {
    val label: String get() = if (country.isBlank()) name else "$name  •  $country"
}

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
    private const val KEY_FAVORITES = "favorite_teams"
    private const val KEY_CACHE = "fixture_cache"
    private const val KEY_WIDGET_COLOR = "widget_color"
    private const val KEY_TAP_TARGET = "tap_target"
    private const val KEY_SOURCE_VERSION = "data_source_version"
    private const val SOURCE_VERSION_FOTMOB = 2

    const val MAX_FAVORITES = 10
    const val DEFAULT_WIDGET_COLOR = 0xFF15171C.toInt()

    const val TAP_NONE = "none"
    const val TAP_FOTMOB = "fotmob"
    const val TAP_SOFASCORE = "sofascore"
    const val TAP_SETTINGS = "settings"
    const val TAP_ONEFOOTBALL = "onefootball"
    const val TAP_FLASHSCORE = "flashscore"
    const val TAP_LIVESCORE = "livescore"
    const val TAP_365SCORES = "365scores"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getWidgetColor(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_COLOR, DEFAULT_WIDGET_COLOR)

    fun saveWidgetColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_COLOR, color).apply()
    }

    fun getTapTarget(context: Context): String =
        prefs(context).getString(KEY_TAP_TARGET, TAP_FOTMOB) ?: TAP_FOTMOB

    fun saveTapTarget(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TAP_TARGET, value).apply()
    }

    fun getFavoriteTeams(context: Context): List<FavoriteTeam> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = flexibleInt(item, "id")
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
        prefs(context).edit()
            .putString(KEY_FAVORITES, array.toString())
            .putInt(KEY_SOURCE_VERSION, SOURCE_VERSION_FOTMOB)
            .apply()
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

    /**
     * v6 and earlier stored football-data.org IDs. On the first v7 run, names are
     * searched on FotMob and converted automatically so users do not need to re-enter IDs.
     */
    fun migrateFavoritesToFotMobIfNeeded(context: Context): Boolean {
        if (prefs(context).getInt(KEY_SOURCE_VERSION, 0) >= SOURCE_VERSION_FOTMOB) return false
        val old = getFavoriteTeams(context)
        if (old.isEmpty()) {
            prefs(context).edit().putInt(KEY_SOURCE_VERSION, SOURCE_VERSION_FOTMOB).apply()
            return false
        }

        val migrated = mutableListOf<FavoriteTeam>()
        var successfulSearch = false
        for (oldTeam in old) {
            val results = runCatching { searchTeams(oldTeam.name) }.getOrElse { emptyList() }
            if (results.isNotEmpty()) successfulSearch = true
            val exact = results.firstOrNull {
                normalizeTeamName(it.name) == normalizeTeamName(oldTeam.name)
            }
            val chosen = exact ?: results.firstOrNull()
            if (chosen != null && migrated.none { it.id == chosen.id }) migrated += chosen
        }

        if (!successfulSearch) {
            throw IllegalStateException("チームIDの自動移行に失敗しました。通信を確認してもう一度更新してください")
        }

        saveFavoriteTeams(context, migrated)
        clearCache(context)
        return true
    }

    /** No API key is required. The app connects directly to FotMob's public JSON feed. */
    private fun requestAny(endpoint: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 11000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.7,en;q=0.6")
            setRequestProperty("Referer", "https://www.fotmob.com/")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
            )
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("データ取得エラー HTTP $code")
            if (body.isBlank()) throw IllegalStateException("空のレスポンス")
            return JSONTokener(body).nextValue()
        } finally {
            connection.disconnect()
        }
    }

    private fun requestObjectWithFallback(vararg urls: String): JSONObject {
        var last: Throwable? = null
        for (url in urls) {
            try {
                val value = requestAny(url)
                if (value is JSONObject) return value
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("データを取得できませんでした")
    }

    /** Load every league/competition exposed by FotMob instead of a hard-coded short list. */
    fun fetchLeagueDirectory(): List<LeagueInfo> {
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/allLeagues",
            "https://www.fotmob.com/api/data/allLeagues?locale=en&country=JPN"
        )
        val ordered = LinkedHashMap<Int, LeagueInfo>()

        fun addLeague(obj: JSONObject, country: String = "", ccode: String = "") {
            val id = flexibleInt(obj, "id")
            val name = obj.optString("localizedName").ifBlank { obj.optString("name") }
            if (id > 0 && name.isNotBlank()) ordered.putIfAbsent(id, LeagueInfo(id, name, country, ccode))
        }

        fun parseBucket(array: JSONArray?, inheritedCountry: String = "", inheritedCode: String = "") {
            if (array == null) return
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val groupName = obj.optString("name").ifBlank { inheritedCountry }
                val groupCode = obj.optString("ccode").ifBlank { inheritedCode }
                val leagues = obj.optJSONArray("leagues")
                if (leagues != null) {
                    for (j in 0 until leagues.length()) {
                        leagues.optJSONObject(j)?.let { addLeague(it, groupName, groupCode) }
                    }
                } else {
                    addLeague(obj, inheritedCountry, inheritedCode)
                }
            }
        }

        // Popular first, then international competitions, then every country's leagues.
        parseBucket(root.optJSONArray("popular"))
        parseBucket(root.optJSONArray("international"), "International", "INT")

        val countries = root.optJSONArray("countries")
        if (countries != null) {
            for (i in 0 until countries.length()) {
                val country = countries.optJSONObject(i) ?: continue
                parseBucket(
                    country.optJSONArray("leagues"),
                    country.optString("name"),
                    country.optString("ccode")
                )
            }
        }

        return ordered.values.toList()
    }

    /** Load teams from any selected FotMob league, including competitions with no classic table. */
    fun fetchTeamsForLeague(leagueId: Int): List<FavoriteTeam> {
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/leagues?id=$leagueId",
            "https://www.fotmob.com/api/data/leagues?id=$leagueId&ccode3=JPN"
        )
        val found = LinkedHashMap<Int, FavoriteTeam>()

        fun addTeam(obj: JSONObject?) {
            if (obj == null) return
            val id = flexibleInt(obj, "id")
            val name = obj.optString("shortName").ifBlank { obj.optString("name") }.ifBlank { obj.optString("longName") }
            if (id > 0 && name.isNotBlank()) found.putIfAbsent(id, FavoriteTeam(id, name))
        }

        fun parseTableRows(rows: JSONArray?) {
            if (rows == null) return
            for (i in 0 until rows.length()) addTeam(rows.optJSONObject(i))
        }

        val tableSections = root.optJSONArray("table")
        if (tableSections != null) {
            for (i in 0 until tableSections.length()) {
                val data = tableSections.optJSONObject(i)?.optJSONObject("data") ?: continue
                parseTableRows(data.optJSONObject("table")?.optJSONArray("all"))
                val tables = data.optJSONArray("tables")
                if (tables != null) {
                    for (j in 0 until tables.length()) {
                        parseTableRows(tables.optJSONObject(j)?.optJSONObject("table")?.optJSONArray("all"))
                    }
                }
            }
        }

        // Cups and qualification competitions may not have a normal table; collect teams from fixtures.
        walkJson(root, maxDepth = 10) { obj ->
            val home = obj.optJSONObject("home")
            val away = obj.optJSONObject("away")
            if (home != null && away != null) {
                addTeam(home)
                addTeam(away)
            }
        }

        return found.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** Search any FotMob team globally by name, so the app isn't limited to a league list. */
    fun searchTeams(term: String): List<FavoriteTeam> {
        val clean = term.trim()
        if (clean.length < 2) return emptyList()
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.toString())
        val candidates = listOf(
            "https://www.fotmob.com/api/searchData?term=$encoded",
            "https://www.fotmob.com/api/data/search/suggest?hits=50&lang=en&term=$encoded"
        )
        var last: Throwable? = null

        for (url in candidates) {
            try {
                val root = requestAny(url)
                val found = LinkedHashMap<Int, FavoriteTeam>()

                fun addTeam(obj: JSONObject) {
                    val id = flexibleInt(obj, "id")
                    val name = obj.optString("name").ifBlank { obj.optString("title") }
                    if (id > 0 && name.isNotBlank()) found.putIfAbsent(id, FavoriteTeam(id, name))
                }

                fun recurse(node: Any?, keyHint: String = "", depth: Int = 0) {
                    if (node == null || depth > 8) return
                    when (node) {
                        is JSONObject -> {
                            val type = node.optString("type").lowercase(Locale.ROOT)
                            if (type == "team" || keyHint.equals("team", true) || keyHint.equals("teams", true)) {
                                addTeam(node)
                            }
                            val keys = node.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                recurse(node.opt(key), key, depth + 1)
                            }
                        }
                        is JSONArray -> for (i in 0 until node.length()) recurse(node.opt(i), keyHint, depth + 1)
                    }
                }
                recurse(root)
                if (found.isNotEmpty()) return found.values.take(50)
            } catch (t: Throwable) {
                last = t
            }
        }
        if (last != null) throw last
        return emptyList()
    }

    private fun fetchNextFixture(team: FavoriteTeam): NextFixture? {
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/teams?id=${team.id}&ccode3=JPN",
            "https://www.fotmob.com/api/data/teams?id=${team.id}&ccode3=JPN"
        )
        val now = Instant.now()
        val matches = mutableListOf<JSONObject>()
        walkJson(root, maxDepth = 12) { obj ->
            if (obj.optJSONObject("home") != null && obj.optJSONObject("away") != null) {
                val kickoff = parseKickoff(obj)
                if (kickoff != null) matches += obj
            }
        }

        val unique = LinkedHashMap<Long, JSONObject>()
        for (m in matches) {
            val id = flexibleLong(m, "id")
            val key = if (id > 0) id else parseKickoff(m)?.toEpochMilli() ?: continue
            unique.putIfAbsent(key, m)
        }

        var bestTime: Instant? = null
        var best: NextFixture? = null
        for (match in unique.values) {
            val kickoff = parseKickoff(match) ?: continue
            if (kickoff.isBefore(now.minusSeconds(30))) continue
            val status = match.optJSONObject("status")
            if (status?.optBoolean("cancelled", false) == true) continue
            if (status?.optBoolean("finished", false) == true) continue

            val home = match.optJSONObject("home") ?: continue
            val away = match.optJSONObject("away") ?: continue
            val homeId = flexibleInt(home, "id")
            val awayId = flexibleInt(away, "id")
            if (homeId != team.id && awayId != team.id) continue

            if (bestTime == null || kickoff.isBefore(bestTime)) {
                val isHome = homeId == team.id
                val opponentObj = if (isHome) away else home
                val opponent = opponentObj.optString("shortName")
                    .ifBlank { opponentObj.optString("name") }
                    .ifBlank { opponentObj.optString("longName") }
                    .ifBlank { "Opponent" }

                val competition = match.optJSONObject("league")?.optString("name")
                    .orEmpty().ifBlank { match.optJSONObject("tournament")?.optString("name").orEmpty() }
                    .ifBlank { match.optString("leagueName") }
                    .ifBlank { match.optString("parentLeagueName") }
                    .ifBlank { "Football" }

                val id = flexibleLong(match, "id")
                val page = match.optString("pageUrl")
                val url = when {
                    page.startsWith("https://", true) -> page
                    page.startsWith("/") -> "https://www.fotmob.com$page"
                    page.isNotBlank() -> "https://www.fotmob.com/$page"
                    id > 0 -> "https://www.fotmob.com/match/$id"
                    else -> ""
                }

                val homeName = home.optString("name").ifBlank { home.optString("shortName") }
                val awayName = away.optString("name").ifBlank { away.optString("shortName") }
                bestTime = kickoff
                best = NextFixture(
                    teamId = team.id,
                    teamName = team.name,
                    utcDate = kickoff.toString(),
                    opponent = opponent,
                    competition = competition,
                    isHome = isHome,
                    hasMatch = true,
                    homeTeamName = homeName,
                    homeTeamShortName = home.optString("shortName").ifBlank { homeName },
                    awayTeamName = awayName,
                    awayTeamShortName = away.optString("shortName").ifBlank { awayName },
                    fotmobMatchId = id,
                    fotmobUrl = url
                )
            }
        }
        return best
    }

    fun fetchAll(context: Context): WidgetState {
        if (prefs(context).getInt(KEY_SOURCE_VERSION, 0) < SOURCE_VERSION_FOTMOB) {
            try {
                migrateFavoritesToFotMobIfNeeded(context)
            } catch (t: Throwable) {
                return loadCache(context).copy(error = t.message ?: "チーム移行失敗")
            }
        }

        val favorites = getFavoriteTeams(context)
        if (favorites.isEmpty()) {
            val empty = WidgetState(emptyList(), System.currentTimeMillis(), "お気に入りチーム未設定")
            saveCache(context, empty)
            return empty
        }

        val previous = loadCache(context).fixtures.associateBy { it.teamId }
        val fixtures = mutableListOf<NextFixture>()
        val errors = mutableListOf<String>()

        favorites.forEach { team ->
            try {
                fixtures += fetchNextFixture(team) ?: NextFixture(
                    teamId = team.id,
                    teamName = team.name,
                    utcDate = "",
                    opponent = "次の試合予定なし",
                    competition = "日程未発表",
                    isHome = true,
                    hasMatch = false
                )
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

        // FotMob IDs/URLs are already exact because FotMob is the primary source.
        // SofaScore still needs an event-ID match by kickoff + home/away names.
        val linked = ExternalMatchResolver.resolveForTarget(fixtures, getTapTarget(context))
        val state = WidgetState(linked, System.currentTimeMillis(), errors.firstOrNull())
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

    private fun clearCache(context: Context) {
        prefs(context).edit().remove(KEY_CACHE).apply()
    }

    fun loadCache(context: Context): WidgetState {
        val raw = prefs(context).getString(KEY_CACHE, null)
            ?: return WidgetState(emptyList(), 0L, null)
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("fixtures") ?: JSONArray()
            val fixtures = buildList {
                for (i in 0 until array.length()) {
                    val f = array.optJSONObject(i) ?: continue
                    add(
                        NextFixture(
                            teamId = flexibleInt(f, "teamId"),
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
                            fotmobMatchId = flexibleLong(f, "fotmobMatchId"),
                            fotmobUrl = f.optString("fotmobUrl"),
                            sofascoreEventId = flexibleLong(f, "sofascoreEventId"),
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

    fun remainingMillis(utcDate: String): Long = runCatching {
        (Instant.parse(utcDate).toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun parseColorOrNull(value: String): Int? = runCatching {
        val normalized = value.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color.parseColor(normalized)
    }.getOrNull()

    fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    fun preferredTextColor(background: Int): Int =
        if (Color.luminance(background) > 0.55) Color.BLACK else Color.WHITE

    fun secondaryTextColor(background: Int): Int {
        val primary = preferredTextColor(background)
        return if (primary == Color.WHITE) 0xBFFFFFFF.toInt() else 0xB8000000.toInt()
    }

    fun rowColor(background: Int): Int {
        val primary = preferredTextColor(background)
        val overlay = if (primary == Color.WHITE) Color.WHITE else Color.BLACK
        return blend(background, overlay, if (primary == Color.WHITE) 0.10f else 0.06f)
    }

    fun accentRowColor(background: Int): Int {
        val primary = preferredTextColor(background)
        val overlay = if (primary == Color.WHITE) Color.WHITE else Color.BLACK
        return blend(background, overlay, if (primary == Color.WHITE) 0.16f else 0.10f)
    }

    private fun blend(base: Int, overlay: Int, amount: Float): Int {
        fun channel(a: Int, b: Int): Int = (a * (1f - amount) + b * amount).toInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(base), Color.red(overlay)),
            channel(Color.green(base), Color.green(overlay)),
            channel(Color.blue(base), Color.blue(overlay))
        )
    }

    private fun flexibleInt(obj: JSONObject, key: String): Int {
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: -1
            else -> -1
        }
    }

    private fun flexibleLong(obj: JSONObject, key: String): Long {
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
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
                ?: runCatching {
                    val fixed = if (value.endsWith("Z")) value else "${value}Z"
                    Instant.parse(fixed)
                }.getOrNull()
            if (parsed != null) return parsed
        }
        val ts = flexibleLong(match, "timeTS")
        if (ts > 0L) return if (ts > 10_000_000_000L) Instant.ofEpochMilli(ts) else Instant.ofEpochSecond(ts)
        return null
    }

    private fun normalizeTeamName(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")
        .replace("fc", "")
        .replace("afc", "")

    private fun walkJson(node: Any?, maxDepth: Int, depth: Int = 0, visit: (JSONObject) -> Unit) {
        if (node == null || depth > maxDepth) return
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walkJson(node.opt(key), maxDepth, depth + 1, visit)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walkJson(node.opt(i), maxDepth, depth + 1, visit)
        }
    }
}
