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
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Favorite team. Legacy provider IDs are retained only for migration compatibility. */
data class FavoriteTeam(
    val id: Int,
    val name: String,
    val fotmobId: Int = if (id > 0) id else 0,
    val sofascoreId: Int = if (id < 0) -id else 0,
    val country: String = ""
) {
    val sourceLabel: String
        get() = when {
            fotmobId > 0 && sofascoreId > 0 -> "FotMob + SofaScore"
            fotmobId > 0 -> "FotMob"
            sofascoreId > 0 -> "SofaScore"
            else -> ""
        }
}

data class LeagueInfo(
    val id: Int,
    val name: String,
    val country: String = "",
    val ccode: String = "",
    val fotmobId: Int = 0,
    val sofascoreId: Int = 0
) {
    val label: String get() = if (country.isBlank()) name else "$name  •  $country"
    val sourceLabel: String get() = when {
        fotmobId > 0 && sofascoreId > 0 -> "FotMob + SofaScore"
        fotmobId > 0 -> "FotMob"
        sofascoreId > 0 -> "SofaScore"
        else -> ""
    }
}

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
    private const val KEY_LEAGUE_DIRECTORY_CACHE = "league_directory_cache_v124"
    private const val KEY_WIDGET_COLOR = "widget_color"
    private const val KEY_TAP_TARGET = "tap_target"
    private const val KEY_SOURCE_VERSION = "data_source_version"
    private const val SOURCE_VERSION_MULTI_PROVIDER = 3
    @Volatile private var lastLeagueDirectoryFallback = false

    // 保存件数に固定上限は設けない（UIには件数上限を表示しない）。
    const val MAX_FAVORITES = Int.MAX_VALUE
    const val DEFAULT_WIDGET_COLOR = 0xFF15171C.toInt()

    const val TAP_NONE = "none"
    const val TAP_FOTMOB = "fotmob"
    const val TAP_SOFASCORE = "sofascore"
    const val TAP_SETTINGS = "settings"
    const val TAP_ONEFOOTBALL = "onefootball"
    const val TAP_FLASHSCORE = "flashscore"
    const val TAP_LIVESCORE = "livescore"
    const val TAP_365SCORES = "365scores"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getWidgetColor(context: Context): Int = prefs(context).getInt(KEY_WIDGET_COLOR, DEFAULT_WIDGET_COLOR)
    fun saveWidgetColor(context: Context, color: Int) = prefs(context).edit().putInt(KEY_WIDGET_COLOR, color).apply()
    fun getTapTarget(context: Context): String =
        prefs(context).getString(KEY_TAP_TARGET, TAP_SOFASCORE) ?: TAP_SOFASCORE
    fun saveTapTarget(context: Context, value: String) = prefs(context).edit().putString(KEY_TAP_TARGET, value).apply()

    fun getFavoriteTeams(context: Context): List<FavoriteTeam> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val legacyId = flexibleInt(item, "id")
                    val name = item.optString("name")
                    val fotmobId = flexibleInt(item, "fotmobId").takeIf { it > 0 }
                        ?: legacyId.takeIf { it > 0 } ?: 0
                    val sofascoreId = flexibleInt(item, "sofascoreId").takeIf { it > 0 }
                        ?: legacyId.takeIf { it < 0 }?.let { -it } ?: 0
                    val stableId = if (fotmobId > 0) fotmobId else if (sofascoreId > 0) -sofascoreId else legacyId
                    if (stableId != 0 && name.isNotBlank()) {
                        add(FavoriteTeam(stableId, name, fotmobId, sofascoreId, item.optString("country")))
                    }
                }
            }.distinctBy { normalizeTeamName(it.name) }
        }.getOrDefault(emptyList())
    }

    fun saveFavoriteTeams(context: Context, teams: List<FavoriteTeam>) {
        val clean = teams
            .filter { it.id != 0 && it.name.isNotBlank() }
            .distinctBy { normalizeTeamName(it.name) }

        val array = JSONArray()
        clean.forEach { team ->
            array.put(JSONObject().apply {
                put("id", team.id)
                put("name", team.name)
                put("fotmobId", team.fotmobId)
                put("sofascoreId", team.sofascoreId)
                put("country", team.country)
            })
        }
        prefs(context).edit()
            .putString(KEY_FAVORITES, array.toString())
            .putInt(KEY_SOURCE_VERSION, SOURCE_VERSION_MULTI_PROVIDER)
            .apply()
    }

    fun addFavoriteTeam(context: Context, team: FavoriteTeam): Boolean {
        val current = getFavoriteTeams(context).toMutableList()
        val key = normalizeTeamName(team.name)
        val existingIndex = current.indexOfFirst { normalizeTeamName(it.name) == key }
        if (existingIndex >= 0) {
            current[existingIndex] = mergeTeam(current[existingIndex], team)
            saveFavoriteTeams(context, current)
            return true
        }
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

    /** Upgrade the v7/v8 favorite JSON without asking the user to choose teams again. */
    fun migrateFavoriteFormatIfNeeded(context: Context): Boolean {
        if (prefs(context).getInt(KEY_SOURCE_VERSION, 0) >= SOURCE_VERSION_MULTI_PROVIDER) return false
        val existing = getFavoriteTeams(context)
        saveFavoriteTeams(context, existing)
        return existing.isNotEmpty()
    }

    private fun requestAny(endpoint: String, referer: String? = null): Any {
        if (endpoint.contains("sofascore", ignoreCase = true)) {
            return SofaScoreHttp.getAny(endpoint)
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 11000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Referer", referer ?: "https://www.fotmob.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("${URL(endpoint).host}: HTTP $code")
            if (body.isBlank()) throw IllegalStateException("${URL(endpoint).host}: 空のレスポンス")
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

    fun fetchLeagueDirectory(): List<LeagueInfo> {
        val context = MatchDayApplication.appContext
        val mode = DataSourceManager.getMode(context)

        val live = when (mode) {
            DataSourceManager.FOTMOB -> runCatching { fetchFotMobLeagueDirectory() }.getOrNull().orEmpty()
            DataSourceManager.SOFASCORE -> runCatching { fetchSofaLeagueDirectory() }.getOrNull().orEmpty()
            else -> {
                val fm = runCatching { fetchFotMobLeagueDirectory() }.getOrNull().orEmpty()
                val ss = runCatching { fetchSofaLeagueDirectory() }.getOrNull().orEmpty()
                when {
                    fm.isNotEmpty() && ss.isNotEmpty() -> mergeLeagueLists(fm, ss)
                    fm.isNotEmpty() -> fm
                    ss.isNotEmpty() -> ss
                    else -> emptyList()
                }
            }
        }

        if (live.isNotEmpty()) {
            lastLeagueDirectoryFallback = false
            val combined = mergeWithBuiltInLeagues(live)
            saveLeagueDirectoryCache(context, combined)
            return combined
        }

        val cached = loadLeagueDirectoryCache(context)
        if (cached.isNotEmpty()) {
            lastLeagueDirectoryFallback = true
            return mergeWithBuiltInLeagues(cached)
        }

        // The selector must remain usable even when an upstream API is temporarily blocked.
        lastLeagueDirectoryFallback = true
        return builtInLeagueDirectory()
    }

    fun leagueDirectoryUsesFallback(): Boolean = lastLeagueDirectoryFallback

    private fun fetchSofaLeagueDirectory(): List<LeagueInfo> {
        val root = requestObjectWithFallback(
            "https://api.sofascore.com/api/v1/config/unique-tournaments/en/football",
            "https://www.sofascore.com/api/v1/config/unique-tournaments/en/football",
            "https://api.sofascore.com/api/v1/sport/football/unique-tournaments",
            "https://www.sofascore.com/api/v1/sport/football/unique-tournaments"
        )
        val tournaments = root.optJSONArray("uniqueTournaments") ?: JSONArray()
        val ordered = LinkedHashMap<Int, LeagueInfo>()
        for (i in 0 until tournaments.length()) {
            val obj = tournaments.optJSONObject(i) ?: continue
            val id = firstPositiveInt(obj, "id")
            val name = obj.optString("name")
            val category = obj.optJSONObject("category")
            val country = category?.optString("name").orEmpty()
            val ccode = category?.optString("alpha2").orEmpty()
            if (id > 0 && name.isNotBlank()) {
                ordered[id] = LeagueInfo(-id, name, country, ccode, fotmobId = 0, sofascoreId = id)
            }
        }
        return sortLeagues(ordered.values.toList())
    }

    private fun fetchFotMobLeagueDirectory(): List<LeagueInfo> {
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/allLeagues",
            "https://www.fotmob.com/api/allLeagues?locale=en",
            "https://www.fotmob.com/api/data/allLeagues?locale=en",
            "https://www.fotmob.com/api/data/allLeagues"
        )
        val found = LinkedHashMap<Int, LeagueInfo>()
        val popular = root.optJSONArray("popular") ?: JSONArray()
        for (i in 0 until popular.length()) {
            val o = popular.optJSONObject(i) ?: continue
            val id = firstPositiveInt(o, "id", "leagueId")
            val name = o.optString("name")
            val country = o.optString("country").ifBlank { "International" }
            if (id > 0 && name.isNotBlank()) found[id] = LeagueInfo(id, name, country, "", fotmobId = id)
        }
        val international = root.optJSONArray("international") ?: JSONArray()
        for (i in 0 until international.length()) {
            val o = international.optJSONObject(i) ?: continue
            val id = firstPositiveInt(o, "id", "leagueId")
            val name = o.optString("name")
            if (id > 0 && name.isNotBlank()) {
                found[id] = LeagueInfo(id, name, "International", "", fotmobId = id)
            }
        }
        val countries = root.optJSONArray("countries") ?: JSONArray()
        for (i in 0 until countries.length()) {
            val countryObj = countries.optJSONObject(i) ?: continue
            val country = countryObj.optString("name")
            val ccode = countryObj.optString("ccode").ifBlank { countryObj.optString("code") }
            val leagues = countryObj.optJSONArray("leagues") ?: JSONArray()
            for (j in 0 until leagues.length()) {
                val o = leagues.optJSONObject(j) ?: continue
                val id = firstPositiveInt(o, "id", "leagueId")
                val name = o.optString("name")
                if (id > 0 && name.isNotBlank()) {
                    found[id] = LeagueInfo(id, name, country, ccode, fotmobId = id)
                }
            }
        }
        if (found.isEmpty()) throw IllegalStateException("FotMobのリーグ一覧が空です")
        return sortLeagues(found.values.toList())
    }

    private fun mergeWithBuiltInLeagues(live: List<LeagueInfo>): List<LeagueInfo> {
        val out = LinkedHashMap<String, LeagueInfo>()
        builtInLeagueDirectory().forEach { out[normalizeTeamName(it.name)] = it }
        live.forEach { incoming ->
            val key = normalizeTeamName(incoming.name)
            val existing = out[key]
            out[key] = if (existing == null) incoming else LeagueInfo(
                id = when {
                    incoming.fotmobId > 0 -> incoming.fotmobId
                    incoming.sofascoreId > 0 -> -incoming.sofascoreId
                    else -> incoming.id
                },
                name = incoming.name.ifBlank { existing.name },
                country = incoming.country.ifBlank { existing.country },
                ccode = incoming.ccode.ifBlank { existing.ccode },
                fotmobId = if (incoming.fotmobId > 0) incoming.fotmobId else existing.fotmobId,
                sofascoreId = if (incoming.sofascoreId > 0) incoming.sofascoreId else existing.sofascoreId
            )
        }
        return sortLeagues(out.values.toList())
    }

    private fun builtInLeagueDirectory(): List<LeagueInfo> = sortLeagues(listOf(
        LeagueInfo(47, "Premier League", "England", "ENG", fotmobId = 47),
        LeagueInfo(48, "Championship", "England", "ENG", fotmobId = 48),
        LeagueInfo(42, "Champions League", "International", "", fotmobId = 42),
        LeagueInfo(73, "Europa League", "International", "", fotmobId = 73),
        LeagueInfo(10216, "Conference League", "International", "", fotmobId = 10216),
        LeagueInfo(87, "LaLiga", "Spain", "ESP", fotmobId = 87),
        LeagueInfo(54, "Bundesliga", "Germany", "GER", fotmobId = 54),
        LeagueInfo(55, "Serie A", "Italy", "ITA", fotmobId = 55),
        LeagueInfo(53, "Ligue 1", "France", "FRA", fotmobId = 53),
        LeagueInfo(57, "Eredivisie", "Netherlands", "NED", fotmobId = 57),
        LeagueInfo(223, "J. League", "Japan", "JPN", fotmobId = 223),
        LeagueInfo(130, "MLS", "USA", "USA", fotmobId = 130),
        LeagueInfo(268, "Brasileirão", "Brazil", "BRA", fotmobId = 268),
        LeagueInfo(208, "Liga MX", "Mexico", "MEX", fotmobId = 208),
        LeagueInfo(77, "FIFA World Cup", "International", "", fotmobId = 77),
        LeagueInfo(322, "Copa América", "International", "", fotmobId = 322),
        LeagueInfo(45, "Copa Libertadores", "International", "", fotmobId = 45),
        LeagueInfo(299, "Copa Sudamericana", "International", "", fotmobId = 299)
    ))

    private fun saveLeagueDirectoryCache(context: Context, leagues: List<LeagueInfo>) {
        if (leagues.isEmpty()) return
        val array = JSONArray()
        leagues.forEach { l ->
            array.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("country", l.country)
                put("ccode", l.ccode)
                put("fotmobId", l.fotmobId)
                put("sofascoreId", l.sofascoreId)
            })
        }
        prefs(context).edit().putString(KEY_LEAGUE_DIRECTORY_CACHE, array.toString()).apply()
    }

    private fun loadLeagueDirectoryCache(context: Context): List<LeagueInfo> {
        val raw = prefs(context).getString(KEY_LEAGUE_DIRECTORY_CACHE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = flexibleInt(o, "id")
                    val name = o.optString("name")
                    if (id == 0 || name.isBlank()) continue
                    add(LeagueInfo(
                        id = id,
                        name = name,
                        country = o.optString("country"),
                        ccode = o.optString("ccode"),
                        fotmobId = flexibleInt(o, "fotmobId").coerceAtLeast(0),
                        sofascoreId = flexibleInt(o, "sofascoreId").coerceAtLeast(0)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sortLeagues(input: List<LeagueInfo>): List<LeagueInfo> = input.sortedWith(
        compareByDescending<LeagueInfo> {
            val n = it.name.lowercase(Locale.ROOT)
            when {
                n.contains("premier league") -> 100
                n.contains("champions league") -> 95
                n == "laliga" || n.contains("la liga") -> 90
                n.contains("bundesliga") -> 85
                n.contains("serie a") -> 80
                n.contains("ligue 1") -> 75
                else -> 0
            }
        }.thenBy { it.country.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) }
    )

    private fun mergeLeagueLists(fotmob: List<LeagueInfo>, sofa: List<LeagueInfo>): List<LeagueInfo> {
        val out = fotmob.toMutableList()
        for (ss in sofa) {
            val matchIndex = out.indexOfFirst { fm ->
                normalizeTeamName(fm.name) == normalizeTeamName(ss.name) &&
                    (fm.country.isBlank() || ss.country.isBlank() ||
                        normalizeTeamName(fm.country) == normalizeTeamName(ss.country) ||
                        fm.country.equals("International", true) || ss.country.equals("International", true))
            }.takeIf { it >= 0 } ?: out.indexOfFirst { fm -> normalizeTeamName(fm.name) == normalizeTeamName(ss.name) }
            if (matchIndex >= 0) {
                val fm = out[matchIndex]
                out[matchIndex] = fm.copy(
                    country = fm.country.ifBlank { ss.country },
                    ccode = fm.ccode.ifBlank { ss.ccode },
                    sofascoreId = ss.sofascoreId
                )
            } else out += ss
        }
        return sortLeagues(out.distinctBy { leagueIdentityKey(it.name, it.country) })
    }

    private fun leagueIdentityKey(name: String, country: String): String =
        "${normalizeTeamName(name)}|${normalizeTeamName(country)}"

    fun fetchTeamsForLeague(leagueId: Int): List<FavoriteTeam> {
        val league = fetchLeagueDirectory().firstOrNull { it.id == leagueId || it.fotmobId == leagueId || it.sofascoreId == leagueId }
            ?: return emptyList()
        return fetchTeamsForLeague(league)
    }

    fun fetchTeamsForLeague(league: LeagueInfo): List<FavoriteTeam> {
        val mode = DataSourceManager.getMode(MatchDayApplication.appContext)
        return when (mode) {
            DataSourceManager.FOTMOB -> fetchFotMobTeamsForLeague(resolveFotMobLeagueId(league))
            DataSourceManager.SOFASCORE -> fetchSofaTeamsForLeague(resolveSofaLeagueId(league))
            else -> {
                val fm = runCatching { fetchFotMobTeamsForLeague(resolveFotMobLeagueId(league)) }
                val ss = runCatching { fetchSofaTeamsForLeague(resolveSofaLeagueId(league)) }
                when {
                    fm.isSuccess && ss.isSuccess -> mergeTeamLists(fm.getOrThrow(), ss.getOrThrow())
                    fm.isSuccess -> fm.getOrThrow()
                    ss.isSuccess -> ss.getOrThrow()
                    else -> throw IllegalStateException(
                        "FotMob: ${fm.exceptionOrNull()?.message ?: "取得失敗"}; SofaScore: ${ss.exceptionOrNull()?.message ?: "取得失敗"}"
                    )
                }
            }
        }
    }

    private fun resolveFotMobLeagueId(league: LeagueInfo): Int {
        if (league.fotmobId > 0) return league.fotmobId
        return fetchFotMobLeagueDirectory().maxByOrNull { nameRelevance(league.name, it.name) }?.fotmobId
            ?.takeIf { it > 0 } ?: throw IllegalStateException("FotMobで${league.name}を特定できません")
    }

    private fun resolveSofaLeagueId(league: LeagueInfo): Int {
        if (league.sofascoreId > 0) return league.sofascoreId
        return fetchSofaLeagueDirectory().maxByOrNull { nameRelevance(league.name, it.name) }?.sofascoreId
            ?.takeIf { it > 0 } ?: throw IllegalStateException("SofaScoreで${league.name}を特定できません")
    }

    private fun fetchSofaTeamsForLeague(leagueId: Int): List<FavoriteTeam> {
        val seasonsRoot = requestObjectWithFallback(
            "https://api.sofascore.com/api/v1/unique-tournament/$leagueId/seasons",
            "https://www.sofascore.com/api/v1/unique-tournament/$leagueId/seasons"
        )
        val seasons = seasonsRoot.optJSONArray("seasons") ?: JSONArray()
        val seasonId = seasons.optJSONObject(0)?.optInt("id") ?: 0
        if (seasonId <= 0) return emptyList()

        val found = LinkedHashMap<Int, FavoriteTeam>()
        fun addTeam(obj: JSONObject?) {
            if (obj == null) return
            val id = firstPositiveInt(obj, "id", "teamId")
            val name = obj.optString("shortName").ifBlank { obj.optString("name") }
            val country = obj.optJSONObject("country")?.optString("name").orEmpty()
            if (id > 0 && name.isNotBlank()) found[id] = FavoriteTeam(-id, name, sofascoreId = id, country = country)
        }

        runCatching {
            val standings = requestObjectWithFallback(
                "https://api.sofascore.com/api/v1/unique-tournament/$leagueId/season/$seasonId/standings/total",
                "https://www.sofascore.com/api/v1/unique-tournament/$leagueId/season/$seasonId/standings/total"
            )
            val groups = standings.optJSONArray("standings") ?: JSONArray()
            for (i in 0 until groups.length()) {
                val rows = groups.optJSONObject(i)?.optJSONArray("rows") ?: continue
                for (j in 0 until rows.length()) addTeam(rows.optJSONObject(j)?.optJSONObject("team"))
            }
        }

        if (found.isEmpty()) {
            for (suffix in listOf("next/0", "last/0")) {
                runCatching {
                    val eventsRoot = requestObjectWithFallback(
                        "https://api.sofascore.com/api/v1/unique-tournament/$leagueId/season/$seasonId/events/$suffix",
                        "https://www.sofascore.com/api/v1/unique-tournament/$leagueId/season/$seasonId/events/$suffix"
                    )
                    val events = eventsRoot.optJSONArray("events") ?: JSONArray()
                    for (i in 0 until events.length()) {
                        val event = events.optJSONObject(i) ?: continue
                        addTeam(event.optJSONObject("homeTeam"))
                        addTeam(event.optJSONObject("awayTeam"))
                    }
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun fetchFotMobTeamsForLeague(leagueId: Int): List<FavoriteTeam> {
        if (leagueId <= 0) return emptyList()
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/data/leagues?id=$leagueId&ccode3=JPN",
            "https://www.fotmob.com/api/leagues?id=$leagueId&ccode3=JPN"
        )
        val found = LinkedHashMap<Int, FavoriteTeam>()
        fun addTeam(obj: JSONObject?) {
            if (obj == null) return
            val id = firstPositiveInt(obj, "id", "teamId")
            val name = obj.optString("name").ifBlank { obj.optString("shortName") }
            if (id <= 0 || name.isBlank()) return
            val country = obj.optString("country").ifBlank { obj.optJSONObject("country")?.optString("name").orEmpty() }
            found[id] = FavoriteTeam(id, name, fotmobId = id, country = country)
        }

        // Current FotMob league payload exposes standings under table[].data.table.all.
        val table = root.optJSONArray("table") ?: JSONArray()
        for (i in 0 until table.length()) {
            val data = table.optJSONObject(i)?.optJSONObject("data") ?: continue
            val tableObj = data.optJSONObject("table") ?: continue
            for (key in listOf("all", "home", "away")) {
                val rows = tableObj.optJSONArray(key) ?: continue
                for (j in 0 until rows.length()) addTeam(rows.optJSONObject(j))
            }
        }

        // Fallback for cups / leagues without standings: collect only home/away team objects from matches.
        if (found.isEmpty()) {
            walkJson(root, maxDepth = 12) { obj ->
                if (obj.optJSONObject("home") != null && obj.optJSONObject("away") != null) {
                    addTeam(obj.optJSONObject("home")); addTeam(obj.optJSONObject("away"))
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** Team search obeys the selected provider mode. */
    fun searchTeams(term: String): List<FavoriteTeam> {
        val clean = term.trim()
        if (clean.length < 2) return emptyList()
        val mode = DataSourceManager.getMode(MatchDayApplication.appContext)
        val values = when (mode) {
            DataSourceManager.FOTMOB -> searchFotMobTeams(clean)
            DataSourceManager.SOFASCORE -> searchSofaScoreTeams(clean)
            else -> {
                val fm = runCatching { searchFotMobTeams(clean) }
                val ss = runCatching { searchSofaScoreTeams(clean) }
                when {
                    fm.isSuccess && ss.isSuccess -> mergeTeamLists(fm.getOrThrow(), ss.getOrThrow())
                    fm.isSuccess -> fm.getOrThrow()
                    ss.isSuccess -> ss.getOrThrow()
                    else -> throw IllegalStateException(
                        "FotMob: ${fm.exceptionOrNull()?.message ?: "検索失敗"}; SofaScore: ${ss.exceptionOrNull()?.message ?: "検索失敗"}"
                    )
                }
            }
        }
        return values.sortedWith(
            compareByDescending<FavoriteTeam> { nameRelevance(clean, it.name) }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        ).take(50)
    }

    private fun mergeTeamLists(fotmob: List<FavoriteTeam>, sofa: List<FavoriteTeam>): List<FavoriteTeam> {
        val out = fotmob.toMutableList()
        for (ss in sofa) {
            val idx = out.indexOfFirst { fm -> normalizeTeamName(fm.name) == normalizeTeamName(ss.name) }
            if (idx >= 0) out[idx] = mergeTeam(out[idx], ss) else out += ss
        }
        return out.distinctBy { normalizeTeamName(it.name) }
    }

    private fun searchFotMobTeams(term: String): List<FavoriteTeam> {
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.toString())
        val urls = listOf(
            "https://www.fotmob.com/api/data/search/suggest?hits=80&lang=en,ja&term=$encoded",
            "https://www.fotmob.com/api/searchData?term=$encoded"
        )
        val found = LinkedHashMap<Int, FavoriteTeam>()
        var last: Throwable? = null

        for (url in urls) {
            try {
                val root = requestAny(url)
                fun addFrom(obj: JSONObject, forcedTeam: Boolean = false) {
                    val type = obj.optString("type").lowercase(Locale.ROOT)
                    val entity = obj.optJSONObject("entity")
                    if (entity != null) {
                        addFrom(entity, forcedTeam || type == "team" || type == "club")
                        return
                    }
                    val sport = obj.optString("sport").lowercase(Locale.ROOT)
                    val entityType = obj.optString("entityType").lowercase(Locale.ROOT)
                    // Do not infer "team" from teamId/teamName: player search results also contain those fields.
                    val looksLikeTeam = forcedTeam || type == "team" || type == "club" || entityType == "team" || entityType == "club"
                    if (!looksLikeTeam || type == "player" || entityType == "player" ||
                        (sport.isNotBlank() && sport != "football" && sport != "soccer")) return
                    val id = firstPositiveInt(obj, "id", "teamId")
                    val name = obj.optString("name")
                        .ifBlank { obj.optString("title") }
                        .ifBlank { obj.optString("teamName") }
                        .ifBlank { obj.optString("shortName") }
                    val country = obj.optJSONObject("country")?.optString("name").orEmpty()
                        .ifBlank { obj.optString("country") }
                    if (id > 0 && name.isNotBlank()) found[id] = FavoriteTeam(id, name, fotmobId = id, country = country)
                }

                fun recurse(node: Any?, keyHint: String = "", depth: Int = 0) {
                    if (node == null || depth > 10) return
                    when (node) {
                        is JSONObject -> {
                            val keySaysTeam = keyHint.equals("team", true) || keyHint.equals("teams", true)
                            addFrom(node, keySaysTeam)
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
                if (found.isNotEmpty()) break
            } catch (t: Throwable) {
                last = t
            }
        }
        if (found.isEmpty() && last != null) throw last
        return found.values.toList()
    }

    private fun searchSofaScoreTeams(term: String): List<FavoriteTeam> {
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.toString())
        val root = requestObjectWithFallback(
            "https://api.sofascore.com/api/v1/search/all?q=$encoded",
            "https://www.sofascore.com/api/v1/search/all?q=$encoded"
        )
        val results = root.optJSONArray("results") ?: root.optJSONArray("entities") ?: JSONArray()
        val found = LinkedHashMap<Int, FavoriteTeam>()

        for (i in 0 until results.length()) {
            val wrapper = results.optJSONObject(i) ?: continue
            val type = wrapper.optString("type").lowercase(Locale.ROOT)
            val entity = wrapper.optJSONObject("entity") ?: wrapper
            val entityType = entity.optString("type").lowercase(Locale.ROOT)
            val effectiveType = type.ifBlank { entityType }
            val sportName = entity.optJSONObject("sport")?.optString("name").orEmpty()
                .ifBlank { entity.optJSONObject("sport")?.optString("slug").orEmpty() }
                .ifBlank { entity.optString("sport") }
                .lowercase(Locale.ROOT)
            if (effectiveType != "team") continue
            if (sportName.isNotBlank() && sportName != "football" && sportName != "soccer") continue
            val id = firstPositiveInt(entity, "id", "teamId")
            val name = entity.optString("name").ifBlank { entity.optString("shortName") }
            val country = entity.optJSONObject("country")?.optString("name").orEmpty()
                .ifBlank { entity.optJSONObject("category")?.optString("name").orEmpty() }
            if (id > 0 && name.isNotBlank()) found[id] = FavoriteTeam(-id, name, sofascoreId = id, country = country)
        }
        return found.values.toList()
    }

    fun fetchNextFixtureForTeam(team: FavoriteTeam): NextFixture? = fetchNextFixture(team)

    private fun fetchNextFixture(team: FavoriteTeam): NextFixture? {
        val mode = DataSourceManager.getMode(MatchDayApplication.appContext)

        fun fotmob(): NextFixture? {
            val fmTeam = if (team.fotmobId > 0) team else {
                searchFotMobTeams(team.name).maxByOrNull { nameRelevance(team.name, it.name) }
                    ?: throw IllegalStateException("FotMobでチームを特定できません")
            }
            return fetchNextFixtureFotMob(fmTeam)?.copy(teamId = team.id, teamName = team.name)
        }

        fun sofa(): NextFixture? {
            val sofaTeam = if (team.sofascoreId > 0) team else {
                searchSofaScoreTeams(team.name).maxByOrNull { nameRelevance(team.name, it.name) }
                    ?: throw IllegalStateException("SofaScoreでチームを特定できません")
            }
            return fetchNextFixtureSofaScore(sofaTeam)?.copy(teamId = team.id, teamName = team.name)
        }

        return when (mode) {
            DataSourceManager.FOTMOB -> fotmob()
            DataSourceManager.SOFASCORE -> sofa()
            else -> {
                val fm = runCatching { fotmob() }
                val ss = runCatching { sofa() }
                val fmFixture = fm.getOrNull()
                val ssFixture = ss.getOrNull()
                when {
                    fmFixture != null && ssFixture != null -> mergeFixtureProviders(fmFixture, ssFixture)
                    fmFixture != null -> fmFixture
                    ssFixture != null -> ssFixture
                    fm.isFailure && ss.isFailure -> throw IllegalStateException(
                        "FotMob: ${fm.exceptionOrNull()?.message ?: "取得失敗"}; SofaScore: ${ss.exceptionOrNull()?.message ?: "取得失敗"}"
                    )
                    else -> null
                }
            }
        }
    }

    private fun mergeFixtureProviders(fm: NextFixture, ss: NextFixture): NextFixture {
        val sameOpponent = normalizeTeamName(fm.opponent) == normalizeTeamName(ss.opponent)
        val fmTime = runCatching { Instant.parse(fm.utcDate) }.getOrNull()
        val ssTime = runCatching { Instant.parse(ss.utcDate) }.getOrNull()
        val closeTime = fmTime != null && ssTime != null && kotlin.math.abs(fmTime.epochSecond - ssTime.epochSecond) <= 6 * 3600
        val base = when {
            sameOpponent && closeTime -> fm
            fmTime == null -> ss
            ssTime == null -> fm
            fmTime.isBefore(ssTime) -> fm
            else -> ss
        }
        return base.copy(
            fotmobMatchId = if (fm.fotmobMatchId > 0L) fm.fotmobMatchId else base.fotmobMatchId,
            fotmobUrl = fm.fotmobUrl.ifBlank { base.fotmobUrl },
            sofascoreEventId = if (ss.sofascoreEventId > 0L) ss.sofascoreEventId else base.sofascoreEventId,
            sofascoreUrl = ss.sofascoreUrl.ifBlank { base.sofascoreUrl }
        )
    }

    private fun fetchNextFixtureFotMob(team: FavoriteTeam): NextFixture? {
        val teamId = team.fotmobId
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/data/teams?id=$teamId&ccode3=JPN",
            "https://www.fotmob.com/api/teams?id=$teamId&ccode3=JPN"
        )
        val now = Instant.now()
        val matches = mutableListOf<JSONObject>()
        walkJson(root, maxDepth = 12) { obj ->
            if (obj.optJSONObject("home") != null && obj.optJSONObject("away") != null && parseKickoff(obj) != null) matches += obj
        }

        var bestTime: Instant? = null
        var best: NextFixture? = null
        val seen = HashSet<Long>()
        for (match in matches) {
            val kickoff = parseKickoff(match) ?: continue
            if (kickoff.isBefore(now.minusSeconds(30))) continue
            val matchId = flexibleLong(match, "id")
            if (matchId > 0 && !seen.add(matchId)) continue
            val status = match.optJSONObject("status")
            if (status?.optBoolean("cancelled", false) == true || status?.optBoolean("finished", false) == true) continue

            val home = match.optJSONObject("home") ?: continue
            val away = match.optJSONObject("away") ?: continue
            val homeId = firstPositiveInt(home, "id", "teamId")
            val awayId = firstPositiveInt(away, "id", "teamId")
            if (homeId != teamId && awayId != teamId) continue
            if (bestTime != null && !kickoff.isBefore(bestTime)) continue

            val isHome = homeId == teamId
            val opponentObj = if (isHome) away else home
            val opponent = opponentObj.optString("shortName").ifBlank { opponentObj.optString("name") }.ifBlank { "Opponent" }
            val competition = match.optJSONObject("league")?.optString("name").orEmpty()
                .ifBlank { match.optJSONObject("tournament")?.optString("name").orEmpty() }
                .ifBlank { match.optString("leagueName") }
                .ifBlank { match.optString("parentLeagueName") }
                .ifBlank { "Football" }
            val page = match.optString("pageUrl")
            val url = when {
                page.startsWith("https://", true) -> page
                page.startsWith("/") -> "https://www.fotmob.com$page"
                page.isNotBlank() -> "https://www.fotmob.com/$page"
                matchId > 0 -> "https://www.fotmob.com/match/$matchId"
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

    private fun fetchNextFixtureSofaScore(team: FavoriteTeam): NextFixture? {
        val sofaId = team.sofascoreId
        val root = requestObjectWithFallback(
            "https://api.sofascore.com/api/v1/team/$sofaId/events/next/0",
            "https://www.sofascore.com/api/v1/team/$sofaId/events/next/0"
        )
        val events = root.optJSONArray("events") ?: JSONArray()
        val now = Instant.now()
        var bestTime: Instant? = null
        var best: NextFixture? = null
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val start = flexibleLong(event, "startTimestamp")
            if (start <= 0L) continue
            val kickoff = Instant.ofEpochSecond(start)
            if (kickoff.isBefore(now.minusSeconds(30))) continue
            val statusType = event.optJSONObject("status")?.optString("type").orEmpty().lowercase(Locale.ROOT)
            if (statusType in setOf("finished", "canceled", "cancelled")) continue

            val home = event.optJSONObject("homeTeam") ?: continue
            val away = event.optJSONObject("awayTeam") ?: continue
            val homeId = firstPositiveInt(home, "id", "teamId")
            val awayId = firstPositiveInt(away, "id", "teamId")
            if (homeId != sofaId && awayId != sofaId) continue
            if (bestTime != null && !kickoff.isBefore(bestTime)) continue

            val isHome = homeId == sofaId
            val opponentObj = if (isHome) away else home
            val opponent = opponentObj.optString("shortName").ifBlank { opponentObj.optString("name") }.ifBlank { "Opponent" }
            val tournament = event.optJSONObject("tournament")
            val competition = tournament?.optJSONObject("uniqueTournament")?.optString("name").orEmpty()
                .ifBlank { tournament?.optString("name").orEmpty() }
                .ifBlank { "Football" }
            val eventId = flexibleLong(event, "id")
            val slug = event.optString("slug").trim('/')
            val customId = event.optString("customId").trim('/')
            val sofaUrl = when {
                slug.isNotBlank() && customId.isNotBlank() -> "https://www.sofascore.com/football/match/$slug/$customId#id:$eventId"
                slug.isNotBlank() && eventId > 0L -> "https://www.sofascore.com/football/match/$slug#id:$eventId"
                eventId > 0L -> "https://www.sofascore.com/football/match/match#id:$eventId"
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
                homeTeamName = homeName,
                homeTeamShortName = home.optString("shortName").ifBlank { homeName },
                awayTeamName = awayName,
                awayTeamShortName = away.optString("shortName").ifBlank { awayName },
                sofascoreEventId = eventId,
                sofascoreUrl = sofaUrl
            )
        }
        return best
    }

    fun fetchAll(context: Context): WidgetState {
        migrateFavoriteFormatIfNeeded(context)
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

        val mode = DataSourceManager.getMode(context)
        val linked = when (mode) {
            DataSourceManager.FOTMOB -> ExternalMatchResolver.resolveForTarget(fixtures, TAP_FOTMOB)
            DataSourceManager.SOFASCORE -> ExternalMatchResolver.resolveForTarget(fixtures, TAP_SOFASCORE)
            else -> ExternalMatchResolver.resolveAll(fixtures)
        }
        val state = WidgetState(linked, System.currentTimeMillis(), errors.firstOrNull())
        saveCache(context, state)
        runCatching { AdvancedStatsRepository.refreshTeamExtras(context, favorites) }
        runCatching { MatchPhaseScheduler.scheduleFromState(context, state) }
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

    fun clearCache(context: Context) = prefs(context).edit().remove(KEY_CACHE).apply()

    fun loadCache(context: Context): WidgetState {
        val raw = prefs(context).getString(KEY_CACHE, null) ?: return WidgetState(emptyList(), 0L, null)
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("fixtures") ?: JSONArray()
            val fixtures = buildList {
                for (i in 0 until array.length()) {
                    val f = array.optJSONObject(i) ?: continue
                    add(NextFixture(
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
                    ))
                }
            }
            WidgetState(fixtures, root.optLong("updatedAt", 0L), null)
        }.getOrElse { WidgetState(emptyList(), 0L, null) }
    }

    fun formatDate(utcDate: String): String = runCatching {
        Instant.parse(utcDate).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d (E) HH:mm", Locale.JAPAN))
    }.getOrDefault("日時未定")

    fun formatUpdatedAt(epochMillis: Long): String {
        if (epochMillis <= 0) return "未更新"
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPAN))
    }

    fun remainingMillis(utcDate: String): Long = runCatching {
        (Instant.parse(utcDate).toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun parseColorOrNull(value: String): Int? = runCatching {
        val normalized = value.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color.parseColor(normalized)
    }.getOrNull()

    fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
    fun preferredTextColor(background: Int): Int = if (Color.luminance(background) > 0.55) Color.BLACK else Color.WHITE
    fun secondaryTextColor(background: Int): Int = if (preferredTextColor(background) == Color.WHITE) 0xBFFFFFFF.toInt() else 0xB8000000.toInt()

    fun rowColor(background: Int): Int {
        val overlay = if (preferredTextColor(background) == Color.WHITE) Color.WHITE else Color.BLACK
        return blend(background, overlay, if (preferredTextColor(background) == Color.WHITE) 0.10f else 0.06f)
    }

    fun accentRowColor(background: Int): Int {
        val overlay = if (preferredTextColor(background) == Color.WHITE) Color.WHITE else Color.BLACK
        return blend(background, overlay, if (preferredTextColor(background) == Color.WHITE) 0.16f else 0.10f)
    }

    fun teamLogoUrl(team: FavoriteTeam): String = when {
        team.fotmobId > 0 -> "https://images.fotmob.com/image_resources/logo/teamlogo/${team.fotmobId}.png"
        team.sofascoreId > 0 -> "https://img.sofascore.com/api/v1/team/${team.sofascoreId}/image"
        else -> ""
    }

    private fun mergeTeam(a: FavoriteTeam, b: FavoriteTeam): FavoriteTeam {
        val fm = if (a.fotmobId > 0) a.fotmobId else b.fotmobId
        val ss = if (a.sofascoreId > 0) a.sofascoreId else b.sofascoreId
        val stable = if (fm > 0) fm else -ss
        val country = a.country.ifBlank { b.country }
        val name = if (a.name.length >= b.name.length) a.name else b.name
        return FavoriteTeam(stable, name, fm, ss, country)
    }

    private fun nameRelevance(query: String, name: String): Int {
        val q = normalizeTeamName(query)
        val n = normalizeTeamName(name)
        return when {
            q == n -> 100
            n.startsWith(q) -> 90
            n.contains(q) -> 80
            q.contains(n) -> 70
            else -> commonPrefix(q, n)
        }
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        val max = minOf(a.length, b.length)
        while (i < max && a[i] == b[i]) i++
        return i
    }

    private fun blend(base: Int, overlay: Int, amount: Float): Int {
        fun channel(a: Int, b: Int): Int = (a * (1f - amount) + b * amount).toInt().coerceIn(0, 255)
        return Color.rgb(channel(Color.red(base), Color.red(overlay)), channel(Color.green(base), Color.green(overlay)), channel(Color.blue(base), Color.blue(overlay)))
    }

    private fun firstPositiveInt(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            val value = flexibleInt(obj, key)
            if (value > 0) return value
        }
        return -1
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
                ?: runCatching { Instant.parse(if (value.endsWith("Z")) value else "${value}Z") }.getOrNull()
            if (parsed != null) return parsed
        }
        val ts = flexibleLong(match, "timeTS")
        if (ts > 0L) return if (ts > 10_000_000_000L) Instant.ofEpochMilli(ts) else Instant.ofEpochSecond(ts)
        return null
    }

    fun normalizeTeamName(value: String): String {
        val latin = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return latin
            .replace("&", "and")
            .replace(Regex("\\b(fc|afc|cf|sc|ac|fk|club|football|futbol|soccer)\\b"), "")
            .replace(Regex("[^a-z0-9ぁ-んァ-ヶ一-龠]+"), "")
    }

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
