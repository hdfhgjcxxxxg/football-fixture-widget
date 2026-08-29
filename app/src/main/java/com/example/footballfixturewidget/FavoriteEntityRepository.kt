package com.example.footballfixturewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class FavoritePlayer(
    val id: Int,
    val name: String,
    val fotmobId: Int = if (id > 0) id else 0,
    val sofascoreId: Int = if (id < 0) -id else 0,
    val teamName: String = "",
    val fotmobTeamId: Int = 0,
    val sofascoreTeamId: Int = 0,
    val country: String = "",
    val position: String = ""
) {
    val sourceLabel: String
        get() = when {
            fotmobId > 0 && sofascoreId > 0 -> "FotMob + SofaScore"
            fotmobId > 0 -> "FotMob"
            sofascoreId > 0 -> "SofaScore"
            else -> "Football"
        }
}

data class FavoriteLeague(
    val id: Int,
    val name: String,
    val country: String = "",
    val ccode: String = ""
) {
    val label: String get() = if (country.isBlank()) name else "$name  •  $country"
}

/**
 * Player/league favorites used by v11.
 * Team favorites stay in FixtureRepository so existing installs migrate without data loss.
 */
object FavoriteEntityRepository {
    private const val PREFS = "fixture_prefs"
    private const val KEY_PLAYERS = "favorite_players"
    private const val KEY_LEAGUES = "favorite_leagues"

    // v11: お気に入り数に固定上限を設けない。
    const val MAX_PLAYERS = Int.MAX_VALUE
    const val MAX_LEAGUES = Int.MAX_VALUE

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFavoritePlayers(context: Context): List<FavoritePlayer> {
        val raw = prefs(context).getString(KEY_PLAYERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optInt("id")
                    val name = o.optString("name")
                    if (id == 0 || name.isBlank()) continue
                    add(
                        FavoritePlayer(
                            id = id,
                            name = name,
                            fotmobId = o.optInt("fotmobId"),
                            sofascoreId = o.optInt("sofascoreId"),
                            teamName = o.optString("teamName"),
                            fotmobTeamId = o.optInt("fotmobTeamId"),
                            sofascoreTeamId = o.optInt("sofascoreTeamId"),
                            country = o.optString("country"),
                            position = o.optString("position")
                        )
                    )
                }
            }.distinctBy { playerKey(it.name, it.teamName) }
        }.getOrDefault(emptyList())
    }

    fun saveFavoritePlayers(context: Context, players: List<FavoritePlayer>) {
        val array = JSONArray()
        players.distinctBy { playerKey(it.name, it.teamName) }.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("fotmobId", p.fotmobId)
                put("sofascoreId", p.sofascoreId)
                put("teamName", p.teamName)
                put("fotmobTeamId", p.fotmobTeamId)
                put("sofascoreTeamId", p.sofascoreTeamId)
                put("country", p.country)
                put("position", p.position)
            })
        }
        prefs(context).edit().putString(KEY_PLAYERS, array.toString()).apply()
    }

    fun addFavoritePlayer(context: Context, player: FavoritePlayer): Boolean {
        val current = getFavoritePlayers(context).toMutableList()
        val key = playerKey(player.name, player.teamName)
        val index = current.indexOfFirst { playerKey(it.name, it.teamName) == key }
        if (index >= 0) {
            current[index] = mergePlayer(current[index], player)
            saveFavoritePlayers(context, current)
            return true
        }
        current += player
        saveFavoritePlayers(context, current)
        return true
    }

    fun removeFavoritePlayer(context: Context, id: Int) {
        saveFavoritePlayers(context, getFavoritePlayers(context).filterNot { it.id == id })
    }

    fun getFavoriteLeagues(context: Context): List<FavoriteLeague> {
        val raw = prefs(context).getString(KEY_LEAGUES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optInt("id")
                    val name = o.optString("name")
                    if (id > 0 && name.isNotBlank()) {
                        add(FavoriteLeague(id, name, o.optString("country"), o.optString("ccode")))
                    }
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun saveFavoriteLeagues(context: Context, leagues: List<FavoriteLeague>) {
        val array = JSONArray()
        leagues.distinctBy { it.id }.forEach { l ->
            array.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("country", l.country)
                put("ccode", l.ccode)
            })
        }
        prefs(context).edit().putString(KEY_LEAGUES, array.toString()).apply()
    }

    fun addFavoriteLeague(context: Context, league: LeagueInfo): Boolean {
        val current = getFavoriteLeagues(context).toMutableList()
        if (current.any { it.id == league.id }) return true
        current += FavoriteLeague(league.id, league.name, league.country, league.ccode)
        saveFavoriteLeagues(context, current)
        return true
    }

    fun removeFavoriteLeague(context: Context, id: Int) {
        saveFavoriteLeagues(context, getFavoriteLeagues(context).filterNot { it.id == id })
    }

    fun searchPlayers(term: String): List<FavoritePlayer> {
        val clean = term.trim()
        if (clean.length < 2) return emptyList()
        val fotmob = runCatching { searchFotMobPlayers(clean) }.getOrDefault(emptyList())
        val sofa = runCatching { searchSofaPlayers(clean) }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, FavoritePlayer>()
        (fotmob + sofa).forEach { p ->
            val key = playerKey(p.name, p.teamName)
            val old = merged[key]
            merged[key] = if (old == null) p else mergePlayer(old, p)
        }
        val q = normalize(clean)
        return merged.values.sortedWith(
            compareByDescending<FavoritePlayer> {
                val n = normalize(it.name)
                when {
                    n == q -> 100
                    n.startsWith(q) -> 90
                    n.contains(q) -> 80
                    else -> 0
                }
            }.thenBy { it.name.lowercase(Locale.ROOT) }
        ).take(50)
    }

    private fun searchFotMobPlayers(term: String): List<FavoritePlayer> {
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.toString())
        val value = requestAny("https://www.fotmob.com/api/data/search/suggest?hits=80&lang=en,ja&term=$encoded")
        val found = LinkedHashMap<Int, FavoritePlayer>()

        fun add(obj: JSONObject, forced: Boolean) {
            val type = obj.optString("type").lowercase(Locale.ROOT)
            if (!forced && type != "player") return
            if (type == "team" || type == "club" || type == "league") return
            val entity = obj.optJSONObject("entity")
            if (entity != null) {
                add(entity, forced || type == "player")
                return
            }
            val id = firstPositiveInt(obj, "id", "playerId")
            val name = obj.optString("name").ifBlank { obj.optString("title") }
            if (id <= 0 || name.isBlank()) return
            val teamObj = obj.optJSONObject("team")
            val teamId = firstPositiveInt(obj, "teamId", "team_id").takeIf { it > 0 }
                ?: teamObj?.let { firstPositiveInt(it, "id", "teamId") } ?: 0
            val teamName = obj.optString("teamName")
                .ifBlank { teamObj?.optString("name").orEmpty() }
                .ifBlank { obj.optString("clubName") }
            val country = obj.optJSONObject("country")?.optString("name").orEmpty()
                .ifBlank { obj.optString("country") }
            val position = obj.optString("position").ifBlank { obj.optString("role") }
            found[id] = FavoritePlayer(
                id = id,
                name = name,
                fotmobId = id,
                teamName = teamName,
                fotmobTeamId = teamId,
                country = country,
                position = position
            )
        }

        fun walk(node: Any?, keyHint: String = "", depth: Int = 0) {
            if (node == null || depth > 10) return
            when (node) {
                is JSONObject -> {
                    val keyForces = keyHint.equals("player", true) || keyHint.equals("players", true)
                    add(node, keyForces)
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        walk(node.opt(key), key, depth + 1)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), keyHint, depth + 1)
            }
        }
        walk(value)
        return found.values.toList()
    }

    private fun searchSofaPlayers(term: String): List<FavoritePlayer> {
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.toString())
        val root = requestObject(
            "https://api.sofascore.com/api/v1/search/all?q=$encoded",
            "https://www.sofascore.com/api/v1/search/all?q=$encoded"
        )
        val results = root.optJSONArray("results") ?: root.optJSONArray("entities") ?: JSONArray()
        val found = LinkedHashMap<Int, FavoritePlayer>()
        for (i in 0 until results.length()) {
            val wrapper = results.optJSONObject(i) ?: continue
            val type = wrapper.optString("type").lowercase(Locale.ROOT)
            val e = wrapper.optJSONObject("entity") ?: wrapper
            val entityType = e.optString("type").lowercase(Locale.ROOT)
            val effectiveType = type.ifBlank { entityType }
            if (effectiveType != "player") continue
            val sport = e.optJSONObject("sport")?.optString("slug").orEmpty()
                .ifBlank { e.optJSONObject("sport")?.optString("name").orEmpty() }
                .ifBlank { e.optString("sport") }
                .lowercase(Locale.ROOT)
            if (sport.isNotBlank() && sport != "football" && sport != "soccer") continue
            val id = firstPositiveInt(e, "id", "playerId")
            val name = e.optString("name").ifBlank { e.optString("shortName") }
            if (id <= 0 || name.isBlank()) continue
            val team = e.optJSONObject("team")
            val teamId = team?.let { firstPositiveInt(it, "id", "teamId") } ?: firstPositiveInt(e, "teamId")
            val teamName = team?.optString("name").orEmpty().ifBlank { e.optString("teamName") }
            val country = e.optJSONObject("country")?.optString("name").orEmpty()
            val position = e.optString("position")
            found[id] = FavoritePlayer(
                id = -id,
                name = name,
                sofascoreId = id,
                teamName = teamName,
                sofascoreTeamId = teamId,
                country = country,
                position = position
            )
        }
        return found.values.toList()
    }

    /** Fill missing team information so a player's widget can show the next club match. */
    fun hydratePlayerTeam(player: FavoritePlayer): FavoritePlayer {
        if (player.fotmobTeamId > 0 || player.sofascoreTeamId > 0) return player
        if (player.fotmobId > 0) {
            val p = runCatching {
                val root = requestObject("https://www.fotmob.com/api/data/playerData?id=${player.fotmobId}&includeMarketValues=true")
                var teamId = 0
                var teamName = ""
                fun consider(o: JSONObject?) {
                    if (o == null || teamId > 0) return
                    val id = firstPositiveInt(o, "teamId", "id")
                    val name = o.optString("teamName").ifBlank { o.optString("name") }
                    if (id > 0 && name.isNotBlank()) {
                        teamId = id
                        teamName = name
                    }
                }
                consider(root.optJSONObject("primaryTeam"))
                consider(root.optJSONObject("team"))
                root.optJSONArray("teams")?.let { a -> for (i in 0 until a.length()) consider(a.optJSONObject(i)) }
                if (teamId > 0) player.copy(teamName = player.teamName.ifBlank { teamName }, fotmobTeamId = teamId) else player
            }.getOrNull()
            if (p != null && p.fotmobTeamId > 0) return p
        }
        if (player.sofascoreId > 0) {
            val p = runCatching {
                val root = requestObject(
                    "https://api.sofascore.com/api/v1/player/${player.sofascoreId}",
                    "https://www.sofascore.com/api/v1/player/${player.sofascoreId}"
                )
                val e = root.optJSONObject("player") ?: root
                val team = e.optJSONObject("team")
                val teamId = team?.let { firstPositiveInt(it, "id", "teamId") } ?: 0
                val teamName = team?.optString("name").orEmpty()
                if (teamId > 0) player.copy(teamName = player.teamName.ifBlank { teamName }, sofascoreTeamId = teamId) else player
            }.getOrNull()
            if (p != null) return p
        }
        return player
    }

    fun playerImageUrls(player: FavoritePlayer): List<String> = buildList {
        if (player.fotmobId > 0) add("https://images.fotmob.com/image_resources/playerimages/${player.fotmobId}.png")
        if (player.sofascoreId > 0) add("https://img.sofascore.com/api/v1/player/${player.sofascoreId}/image")
    }

    fun leagueImageUrls(league: FavoriteLeague): List<String> = listOf(
        "https://images.fotmob.com/image_resources/logo/leaguelogo/${league.id}.png"
    )

    private fun mergePlayer(a: FavoritePlayer, b: FavoritePlayer): FavoritePlayer {
        val fm = if (a.fotmobId > 0) a.fotmobId else b.fotmobId
        val ss = if (a.sofascoreId > 0) a.sofascoreId else b.sofascoreId
        val stable = if (fm > 0) fm else -ss
        return FavoritePlayer(
            id = stable,
            name = if (a.name.length >= b.name.length) a.name else b.name,
            fotmobId = fm,
            sofascoreId = ss,
            teamName = a.teamName.ifBlank { b.teamName },
            fotmobTeamId = if (a.fotmobTeamId > 0) a.fotmobTeamId else b.fotmobTeamId,
            sofascoreTeamId = if (a.sofascoreTeamId > 0) a.sofascoreTeamId else b.sofascoreTeamId,
            country = a.country.ifBlank { b.country },
            position = a.position.ifBlank { b.position }
        )
    }

    private fun playerKey(name: String, teamName: String): String = "${normalize(name)}|${normalize(teamName)}"

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace("fc", "")
        .replace(Regex("[^a-z0-9\\p{L}]+"), "")

    private fun requestObject(vararg urls: String): JSONObject {
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

    private fun requestAny(endpoint: String): Any {
        val sofa = endpoint.contains("sofascore", true)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 11000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Referer", if (sofa) "https://www.sofascore.com/" else "https://www.fotmob.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONTokener(body).nextValue()
        } finally {
            connection.disconnect()
        }
    }

    private fun firstPositiveInt(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            val any = obj.opt(key)
            val v = when (any) {
                is Number -> any.toInt()
                is String -> any.toIntOrNull() ?: 0
                else -> 0
            }
            if (v > 0) return v
        }
        return 0
    }
}
