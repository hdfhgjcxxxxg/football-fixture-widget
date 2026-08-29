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

/**
 * One favorite team. v9 can keep IDs from both providers.
 * id is the stable local key: FotMob ID when available, otherwise -SofaScore ID.
 */
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
            else -> "Football"
        }
}

data class LeagueInfo(
    val id: Int,
    val name: String,
    val country: String = "",
    val ccode: String = ""
) {
    val label: String get() = if (country.isBlank()) name else "$name  •  $country"
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
    private const val KEY_WIDGET_COLOR = "widget_color"
    private const val KEY_TAP_TARGET = "tap_target"
    private const val KEY_SOURCE_VERSION = "data_source_version"
    private const val SOURCE_VERSION_MULTI_PROVIDER = 3

    // v11: お気に入りチーム数は上限なし。
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
    fun getTapTarget(context: Context): String = prefs(context).getString(KEY_TAP_TARGET, TAP_FOTMOB) ?: TAP_FOTMOB
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
        val isSofa = endpoint.contains("sofascore", ignoreCase = true)
        val ref = referer ?: if (isSofa) "https://www.sofascore.com/" else "https://www.fotmob.com/"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 11000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Referer", ref)
            setRequestProperty("Origin", ref.trimEnd('/'))
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

    fun fetchLeagueDirectory(): List<LeagueInfo> {
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/data/allLeagues?locale=en&country=JPN",
            "https://www.fotmob.com/api/allLeagues"
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
                    for (j in 0 until leagues.length()) leagues.optJSONObject(j)?.let { addLeague(it, groupName, groupCode) }
                } else addLeague(obj, inheritedCountry, inheritedCode)
            }
        }

        parseBucket(root.optJSONArray("popular"))
        parseBucket(root.optJSONArray("international"), "International", "INT")
        root.optJSONArray("countries")?.let { countries ->
            for (i in 0 until countries.length()) {
                val country = countries.optJSONObject(i) ?: continue
                parseBucket(country.optJSONArray("leagues"), country.optString("name"), country.optString("ccode"))
            }
        }
        return ordered.values.toList()
    }

    fun fetchTeamsForLeague(leagueId: Int): List<FavoriteTeam> {
        val root = requestObjectWithFallback(
            "https://www.fotmob.com/api/data/leagues?id=$leagueId&ccode3=JPN",
            "https://www.fotmob.com/api/leagues?id=$leagueId"
        )
        val found = LinkedHashMap<Int, FavoriteTeam>()

        fun addTeam(obj: JSONObject?) {
            if (obj == null) return
            val id = firstPositiveInt(obj, "id", "teamId")
            val name = obj.optString("shortName").ifBlank { obj.optString("name") }.ifBlank { obj.optString("longName") }
            if (id > 0 && name.isNotBlank()) found.putIfAbsent(id, FavoriteTeam(id, name, fotmobId = id))
        }

        fun parseTableRows(rows: JSONArray?) {
            if (rows == null) return
            for (i in 0 until rows.length()) addTeam(rows.optJSONObject(i))
        }
        fun parseTableSection(section: JSONObject?) {
            if (section == null) return
            val data = section.optJSONObject("data") ?: section
            parseTableRows(data.optJSONObject("table")?.optJSONArray("all"))
            parseTableRows(data.optJSONArray("all"))
            val tables = data.optJSONArray("tables")
            if (tables != null) {
                for (j in 0 until tables.length()) {
                    val table = tables.optJSONObject(j) ?: continue
                    parseTableRows(table.optJSONObject("table")?.optJSONArray("all"))
                    parseTableRows(table.optJSONArray("all"))
                }
            }
        }
        root.optJSONArray("table")?.let { sections ->
            for (i in 0 until sections.length()) parseTableSection(sections.optJSONObject(i))
        }
        parseTableSection(root.optJSONObject("table"))

        // IMPORTANT: only collect entities that are unambiguously teams.
        // v9 walked every object with a teamId, which also matched player objects
        // (e.g. Adam Smith, Adam Wharton) and caused league pickers to show players.
        root.optJSONArray("teams")?.let { teams ->
            for (i in 0 until teams.length()) addTeam(teams.optJSONObject(i))
        }
        walkJson(root, maxDepth = 11) { obj ->
            val home = obj.optJSONObject("home")
            val away = obj.optJSONObject("away")
            if (home != null && away != null) {
                addTeam(home)
                addTeam(away)
            }
            val homeTeam = obj.optJSONObject("homeTeam")
            val awayTeam = obj.optJSONObject("awayTeam")
            if (homeTeam != null && awayTeam != null) {
                addTeam(homeTeam)
                addTeam(awayTeam)
            }
        }

        return found.values
            .filterNot { it.name.matches(Regex(".*\\b(Referee|Coach|Manager)\\b.*", RegexOption.IGNORE_CASE)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /**
     * FotMob-style instant search with SofaScore fallback. This is deliberately tolerant of
     * both providers changing nesting/field names; only football team entities are returned.
     */
    fun searchTeams(term: String): List<FavoriteTeam> {
        val clean = term.trim()
        if (clean.length < 2) return emptyList()

        val fotmob = runCatching { searchFotMobTeams(clean) }.getOrDefault(emptyList())
        val sofa = runCatching { searchSofaScoreTeams(clean) }.getOrDefault(emptyList())
        if (fotmob.isEmpty() && sofa.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, FavoriteTeam>()
        fun put(team: FavoriteTeam) {
            val key = normalizeTeamName(team.name)
            if (key.isBlank()) return
            val existing = merged[key]
            merged[key] = if (existing == null) team else mergeTeam(existing, team)
        }
        fotmob.forEach(::put)
        sofa.forEach(::put)

        // Prefer closest name matches, then teams carrying both provider IDs.
        return merged.values.sortedWith(
            compareByDescending<FavoriteTeam> { nameRelevance(clean, it.name) }
                .thenByDescending { (if (it.fotmobId > 0) 1 else 0) + (if (it.sofascoreId > 0) 1 else 0) }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        ).take(50)
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
        var firstError: Throwable? = null
        if (team.fotmobId > 0) {
            try {
                return fetchNextFixtureFotMob(team)
            } catch (t: Throwable) {
                firstError = t
            }
        }
        if (team.sofascoreId > 0) {
            return try {
                fetchNextFixtureSofaScore(team)
            } catch (t: Throwable) {
                throw firstError ?: t
            }
        }
        throw firstError ?: IllegalStateException("チームIDがありません")
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

        // v11 resolves both providers regardless of the currently selected tap target so
        // switching FotMob/SofaScore later never leaves stale/missing event IDs.
        val linked = ExternalMatchResolver.resolveAll(fixtures)
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
