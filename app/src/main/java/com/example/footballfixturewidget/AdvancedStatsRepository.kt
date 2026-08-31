package com.example.footballfixturewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import kotlin.math.max

/**
 * Rich, best-effort data for MatchDay widgets.
 * v12 supports FotMob, SofaScore, or both with automatic fallback/merging where possible.
 */
data class RichEvent(
    val eventId: Long,
    val startTimestamp: Long,
    val statusType: String,
    val statusDescription: String,
    val homeName: String,
    val awayName: String,
    val homeId: Int,
    val awayId: Int,
    val homeScore: Int?,
    val awayScore: Int?,
    val competition: String,
    val roundLabel: String,
    val slug: String,
    val customId: String,
    val liveMinute: Int = 0,
    val provider: String = DataSourceManager.SOFASCORE,
    val providerUrl: String = "",
    // Score used by team form/results. Penalty-shootout kicks are excluded.
    val formHomeScore: Int? = homeScore,
    val formAwayScore: Int? = awayScore
) {
    val isLive: Boolean
        get() {
            val providerSaysLive = statusType.equals("inprogress", true) || statusType.equals("live", true)
            if (!providerSaysLive) return false
            // Never keep a stale cached "live" flag forever. Even an extra-time match
            // should have left live state within four hours of kickoff.
            return startTimestamp <= 0L || Instant.now().epochSecond <= startTimestamp + 4L * 60L * 60L
        }
    val isFinished: Boolean
        get() {
            if (statusType.equals("finished", true) || statusType.equals("ended", true)) return true
            val providerSaysLive = statusType.equals("inprogress", true) || statusType.equals("live", true)
            return providerSaysLive && startTimestamp > 0L && Instant.now().epochSecond > startTimestamp + 4L * 60L * 60L
        }
    val isScheduled: Boolean get() = !isLive && !isFinished
    val scoreText: String get() = if (homeScore != null && awayScore != null) "$homeScore-$awayScore" else "-"
    val sofaUrl: String
        get() = if (provider == DataSourceManager.SOFASCORE) {
            when {
                providerUrl.isNotBlank() -> providerUrl
                slug.isNotBlank() && customId.isNotBlank() -> "https://www.sofascore.com/football/match/$slug/$customId#id:$eventId"
                slug.isNotBlank() -> "https://www.sofascore.com/football/match/$slug#id:$eventId"
                else -> "https://www.sofascore.com/football/match/match#id:$eventId"
            }
        } else ""

    val fotmobUrl: String
        get() = if (provider == DataSourceManager.FOTMOB) providerUrl.ifBlank { "https://www.fotmob.com/match/$eventId" } else ""

    fun asFixture(ownerId: Int, ownerName: String, ownerTeamId: Int = 0): NextFixture {
        val isHome = ownerTeamId > 0 && ownerTeamId == homeId || ownerTeamId <= 0 && normalizeName(ownerName) == normalizeName(homeName)
        val opponent = if (isHome) awayName else homeName
        return NextFixture(
            teamId = ownerId,
            teamName = ownerName,
            utcDate = if (startTimestamp > 0L) Instant.ofEpochSecond(startTimestamp).toString() else "",
            opponent = opponent,
            competition = competition,
            isHome = isHome,
            homeTeamName = homeName,
            homeTeamShortName = homeName,
            awayTeamName = awayName,
            awayTeamShortName = awayName,
            fotmobMatchId = if (provider == DataSourceManager.FOTMOB) eventId else 0L,
            fotmobUrl = fotmobUrl,
            sofascoreEventId = if (provider == DataSourceManager.SOFASCORE) eventId else 0L,
            sofascoreUrl = sofaUrl
        )
    }

    companion object {
        private fun normalizeName(v: String): String = v.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\p{L}]+"), "")
    }
}

data class TeamExtra(
    val teamId: Int,
    val sofaTeamId: Int,
    val live: RichEvent?,
    val last: RichEvent?,
    val next: RichEvent?,
    val recentForm: List<String>
)

data class PlayerPerformance(
    val eventId: Long,
    val rating: String,
    val goals: Int,
    val assists: Int,
    val cleanSheet: Boolean,
    val minutesPlayed: Int,
    val resultText: String
)

data class PlayerExtra(
    val playerId: Int,
    val sofaPlayerId: Int,
    val sofaTeamId: Int,
    val lineupStatus: String,
    val live: RichEvent?,
    val next: RichEvent?,
    val last: RichEvent?,
    val recentRatings: List<String>,
    val lastPerformance: PlayerPerformance?
)

data class LeagueRoundData(
    val leagueId: Int,
    val sofaTournamentId: Int,
    val seasonId: Int,
    val roundNumber: Int,
    val roundLabel: String,
    val events: List<RichEvent>
)

object AdvancedStatsRepository {
    private const val PREFS = "advanced_widget_cache_v12_6"
    private const val TEAM_KEY = "team_extra"
    private const val PLAYER_KEY = "player_extra"
    private const val LEAGUE_KEY = "league_rounds"
    private const val UPDATED_KEY = "updated"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refreshTeamExtras(context: Context, teams: List<FavoriteTeam>): Map<Int, TeamExtra> {
        val old = loadTeamExtras(context)
        val out = LinkedHashMap<Int, TeamExtra>()
        val mode = DataSourceManager.getMode(context)
        teams.forEach { team ->
            val extra = when (mode) {
                DataSourceManager.FOTMOB -> runCatching { fetchTeamExtraFotMob(team) }.getOrNull()
                DataSourceManager.SOFASCORE -> runCatching { fetchTeamExtra(team) }.getOrNull()
                else -> runCatching { fetchTeamExtra(team) }.getOrNull()
                    ?: runCatching { fetchTeamExtraFotMob(team) }.getOrNull()
            } ?: old[team.id]
            if (extra != null) out[team.id] = extra
        }
        saveTeamExtras(context, out)
        return out
    }

    fun refreshPlayerExtras(context: Context, players: List<FavoritePlayer>): Map<Int, PlayerExtra> {
        val old = loadPlayerExtras(context)
        val out = LinkedHashMap<Int, PlayerExtra>()
        val mode = DataSourceManager.getMode(context)
        players.forEach { player ->
            val extra = when (mode) {
                DataSourceManager.FOTMOB -> runCatching { fetchPlayerExtraFotMob(player) }.getOrNull()
                DataSourceManager.SOFASCORE -> runCatching { fetchPlayerExtra(player) }.getOrNull()
                else -> runCatching { fetchPlayerExtra(player) }.getOrNull()
                    ?: runCatching { fetchPlayerExtraFotMob(player) }.getOrNull()
            } ?: old[player.id]
            if (extra != null) out[player.id] = extra
        }
        savePlayerExtras(context, out)
        return out
    }

    fun refreshLeagueRounds(context: Context, leagues: List<FavoriteLeague>): Map<Int, LeagueRoundData> {
        val old = loadLeagueRounds(context)
        val out = LinkedHashMap<Int, LeagueRoundData>()
        val mode = DataSourceManager.getMode(context)
        leagues.forEach { league ->
            val data = when (mode) {
                DataSourceManager.FOTMOB -> runCatching { fetchLeagueRoundFotMob(league) }.getOrNull()
                DataSourceManager.SOFASCORE -> runCatching { fetchLeagueRound(league) }.getOrNull()
                else -> runCatching { fetchLeagueRound(league) }.getOrNull()
                    ?: runCatching { fetchLeagueRoundFotMob(league) }.getOrNull()
            } ?: old[league.id]
            if (data != null) out[league.id] = data
        }
        saveLeagueRounds(context, out)
        return out
    }

    fun updatedAt(context: Context): Long = prefs(context).getLong(UPDATED_KEY, 0L)

    fun loadTeamExtras(context: Context): Map<Int, TeamExtra> {
        val raw = prefs(context).getString(TEAM_KEY, null) ?: return emptyMap()
        return runCatching {
            val a = JSONArray(raw)
            buildMap {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optInt("teamId")
                    if (id == 0) continue
                    put(id, TeamExtra(
                        teamId = id,
                        sofaTeamId = o.optInt("sofaTeamId"),
                        live = o.optJSONObject("live")?.let(::eventFromJson),
                        last = o.optJSONObject("last")?.let(::eventFromJson),
                        next = o.optJSONObject("next")?.let(::eventFromJson),
                        recentForm = jsonStringList(o.optJSONArray("recentForm"))
                    ))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun loadPlayerExtras(context: Context): Map<Int, PlayerExtra> {
        val raw = prefs(context).getString(PLAYER_KEY, null) ?: return emptyMap()
        return runCatching {
            val a = JSONArray(raw)
            buildMap {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optInt("playerId")
                    if (id == 0) continue
                    put(id, PlayerExtra(
                        playerId = id,
                        sofaPlayerId = o.optInt("sofaPlayerId"),
                        sofaTeamId = o.optInt("sofaTeamId"),
                        lineupStatus = o.optString("lineupStatus"),
                        live = o.optJSONObject("live")?.let(::eventFromJson),
                        next = o.optJSONObject("next")?.let(::eventFromJson),
                        last = o.optJSONObject("last")?.let(::eventFromJson),
                        recentRatings = jsonStringList(o.optJSONArray("recentRatings")),
                        lastPerformance = o.optJSONObject("lastPerformance")?.let(::performanceFromJson)
                    ))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun loadLeagueRounds(context: Context): Map<Int, LeagueRoundData> {
        val raw = prefs(context).getString(LEAGUE_KEY, null) ?: return emptyMap()
        return runCatching {
            val a = JSONArray(raw)
            buildMap {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optInt("leagueId")
                    if (id == 0) continue
                    val events = buildList {
                        val ea = o.optJSONArray("events") ?: JSONArray()
                        for (j in 0 until ea.length()) ea.optJSONObject(j)?.let { add(eventFromJson(it)) }
                    }
                    put(id, LeagueRoundData(
                        leagueId = id,
                        sofaTournamentId = o.optInt("sofaTournamentId"),
                        seasonId = o.optInt("seasonId"),
                        roundNumber = o.optInt("roundNumber"),
                        roundLabel = o.optString("roundLabel"),
                        events = events
                    ))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun fetchTeamExtra(team: FavoriteTeam): TeamExtra {
        val sofaId = resolveSofaTeamId(team)
        if (sofaId <= 0) throw IllegalStateException("SofaScore team id unavailable")
        val lastEvents = fetchEvents("/team/$sofaId/events/last/0")
        val nextEvents = fetchEvents("/team/$sofaId/events/next/0")
        val combined = (lastEvents + nextEvents).distinctBy { it.eventId }
        val live = combined.firstOrNull { it.isLive }
        val last = lastEvents.filter { it.isFinished }.maxByOrNull { it.startTimestamp }
        val next = nextEvents.filter { !it.isFinished }.minByOrNull { it.startTimestamp }
        val recent = lastEvents.filter { it.isFinished }.sortedByDescending { it.startTimestamp }.take(5).map { event ->
            val isHome = event.homeId == sofaId
            val own = if (isHome) event.formHomeScore else event.formAwayScore
            val opp = if (isHome) event.formAwayScore else event.formHomeScore
            when {
                own == null || opp == null -> "-"
                own > opp -> "W${own}-${opp}"
                own < opp -> "L${own}-${opp}"
                else -> "D${own}-${opp}"
            }
        }
        return TeamExtra(team.id, sofaId, live, last, next, recent)
    }

    private fun fetchTeamExtraFotMob(team: FavoriteTeam): TeamExtra {
        val fmId = resolveFotMobTeamId(team)
        if (fmId <= 0) throw IllegalStateException("FotMob team id unavailable")
        val events = fetchFotMobTeamEvents(fmId)
        val live = events.firstOrNull { it.isLive }
        val last = events.filter { it.isFinished }.maxByOrNull { it.startTimestamp }
        val next = events.filter { it.isScheduled }.minByOrNull { it.startTimestamp }
        val recent = events.filter { it.isFinished }.sortedByDescending { it.startTimestamp }.take(5).map { event ->
            val isHome = event.homeId == fmId
            val own = if (isHome) event.formHomeScore else event.formAwayScore
            val opp = if (isHome) event.formAwayScore else event.formHomeScore
            when {
                own == null || opp == null -> "-"
                own > opp -> "W${own}-${opp}"
                own < opp -> "L${own}-${opp}"
                else -> "D${own}-${opp}"
            }
        }
        return TeamExtra(team.id, fmId, live, last, next, recent)
    }

    private fun fetchFotMobTeamEvents(teamId: Int): List<RichEvent> {
        val root = requestObjectAbsolute("https://www.fotmob.com/api/data/teams?id=$teamId&ccode3=JPN")
        val found = LinkedHashMap<Long, RichEvent>()
        walkJson(root, 0, 14) { o ->
            if (o.optJSONObject("home") != null && o.optJSONObject("away") != null) {
                parseFotMobEvent(o)?.let { if (it.eventId > 0) found[it.eventId] = it }
            }
        }
        return found.values.sortedBy { it.startTimestamp }
    }

    private fun fetchPlayerExtraFotMob(player: FavoritePlayer): PlayerExtra {
        var resolved = player
        if (resolved.fotmobId <= 0 || resolved.fotmobTeamId <= 0) {
            resolved = FavoriteEntityRepository.hydratePlayerTeam(resolved)
        }
        val playerId = resolved.fotmobId
        val teamId = resolved.fotmobTeamId
        if (playerId <= 0) throw IllegalStateException("FotMob player id unavailable")

        val events = if (teamId > 0) fetchFotMobTeamEvents(teamId) else emptyList()
        val live = events.firstOrNull { it.isLive }
        val next = events.filter { it.isScheduled }.minByOrNull { it.startTimestamp }
        val finished = events.filter { it.isFinished }.sortedByDescending { it.startTimestamp }.take(5)
        val performances = finished.mapNotNull { event ->
            runCatching { fetchFotMobPlayerPerformance(event, playerId, teamId, resolved.teamName, resolved.position) }.getOrNull()
        }
        val ratings = performances.mapNotNull { it.rating.takeIf(String::isNotBlank) }.take(5)
        val last = finished.firstOrNull()
        val lastPerf = performances.firstOrNull { it.eventId == last?.eventId }
        val target = live ?: next
        val lineup = if (target != null) runCatching { fetchFotMobLineupStatus(target.eventId, playerId) }.getOrDefault("未発表") else "試合なし"
        return PlayerExtra(player.id, playerId, teamId, lineup, live, next, last, ratings, lastPerf)
    }

    private fun fetchFotMobPlayerPerformance(event: RichEvent, playerId: Int, teamId: Int, teamName: String, position: String): PlayerPerformance {
        val root = requestObjectAbsolute("https://www.fotmob.com/api/data/matchDetails?matchId=${event.eventId}")
        val content = root.optJSONObject("content") ?: JSONObject()
        var stats: JSONObject? = content.optJSONObject("playerStats")?.optJSONObject(playerId.toString())

        if (stats == null) {
            val lineup = content.optJSONObject("lineup")
            val lineups = lineup?.optJSONArray("lineups") ?: JSONArray()
            loop@ for (i in 0 until lineups.length()) {
                val players = lineups.optJSONObject(i)?.optJSONArray("players") ?: continue
                for (j in 0 until players.length()) {
                    val e = players.optJSONObject(j) ?: continue
                    val p = e.optJSONObject("player") ?: e
                    val id = firstInt(p, "id", "playerId").takeIf { it > 0 } ?: firstInt(e, "id", "playerId")
                    if (id == playerId) { stats = e.optJSONObject("stats") ?: e.optJSONObject("statistics") ?: e; break@loop }
                }
            }
        }
        val s = stats ?: JSONObject()
        val rating = extractFotMobRating(s)
        val goals = firstInt(s, "goals", "goalsScored")
        val assists = firstInt(s, "assists", "goalAssist", "goalAssists")
        val minutes = firstInt(s, "minutesPlayed", "minutes", "minutesOnField")
        val isHome = when {
            teamId > 0 -> event.homeId == teamId
            teamName.isNotBlank() -> normalize(event.homeName) == normalize(teamName)
            else -> false
        }
        val oppScore = if (isHome) event.awayScore else event.homeScore
        val resultText = if (event.homeScore != null && event.awayScore != null) "${event.homeName} ${event.scoreText} ${event.awayName}" else ""
        val pos = position.uppercase(Locale.ROOT)
        val tookPart = minutes > 0 || rating.isNotBlank()
        val cleanSheet = (pos.startsWith("D") || pos.startsWith("G")) && tookPart && oppScore == 0
        return PlayerPerformance(event.eventId, rating, goals, assists, cleanSheet, minutes, resultText)
    }

    private fun extractFotMobRating(o: JSONObject): String {
        fun format(v: Any?): String = when (v) {
            is Number -> String.format(Locale.US, "%.1f", v.toDouble())
            is String -> v.toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            is JSONObject -> format(v.opt("num")).ifBlank { format(v.opt("value")) }
            else -> ""
        }
        for (key in listOf("rating", "playerRating", "ratingNum")) format(o.opt(key)).takeIf { it.isNotBlank() }?.let { return it }
        var result = ""
        walkJson(o, 0, 5) { node ->
            if (result.isBlank()) {
                for (key in listOf("rating", "playerRating")) {
                    val v = format(node.opt(key)); if (v.isNotBlank()) { result = v; break }
                }
            }
        }
        return result
    }

    private fun fetchFotMobLineupStatus(matchId: Long, playerId: Int): String {
        val root = requestObjectAbsolute("https://www.fotmob.com/api/data/matchDetails?matchId=$matchId")
        val lineup = root.optJSONObject("content")?.optJSONObject("lineup") ?: return "未発表"

        // FotMob can expose predicted/provisional players before the official XI is
        // announced. Do not label those players as starters unless the lineup is
        // explicitly confirmed, or the match itself has already started.
        val headerStatus = root.optJSONObject("header")?.optJSONObject("status")
        val general = root.optJSONObject("general")
        val matchStarted = headerStatus?.optBoolean("started", false) == true ||
            general?.optBoolean("started", false) == true ||
            general?.optBoolean("matchStarted", false) == true
        val confirmed = lineup.optBoolean("confirmed", false) ||
            lineup.optBoolean("isConfirmed", false) ||
            lineup.optBoolean("lineupConfirmed", false) ||
            lineup.optString("status").contains("confirm", true) ||
            lineup.optString("lineupStatus").contains("confirm", true)
        if (!confirmed && !matchStarted) return "未発表"

        val lineups = lineup.optJSONArray("lineups") ?: JSONArray()
        var hasOfficialLineup = false
        for (i in 0 until lineups.length()) {
            val side = lineups.optJSONObject(i) ?: continue
            val players = side.optJSONArray("players") ?: JSONArray()
            if (players.length() > 0) hasOfficialLineup = true
            for (j in 0 until players.length()) {
                val entry = players.optJSONObject(j) ?: continue
                val p = entry.optJSONObject("player") ?: entry
                val id = firstInt(p, "id", "playerId").takeIf { it > 0 } ?: firstInt(entry, "id", "playerId")
                if (id != playerId) continue
                val starter = when {
                    entry.has("isStarter") -> entry.optBoolean("isStarter", false)
                    entry.has("substitute") -> !entry.optBoolean("substitute", false)
                    // The main players array is the XI on published FotMob lineups.
                    else -> true
                }
                return if (starter) "スタメン" else "ベンチ"
            }
        }

        // Some FotMob payloads expose the bench separately.
        val bench = lineup.optJSONObject("bench")
        if (bench != null) {
            val arr = bench.optJSONArray("benchArr") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val group = arr.optJSONArray(i) ?: continue
                for (j in 0 until group.length()) {
                    val e = group.optJSONObject(j) ?: continue
                    if (firstInt(e, "id", "playerId") == playerId) return "ベンチ"
                }
            }
        }
        return if (hasOfficialLineup || confirmed || matchStarted) "ベンチ外" else "未発表"
    }

    private fun fetchLeagueRoundFotMob(league: FavoriteLeague): LeagueRoundData {
        val leagueId = resolveFotMobTournamentId(league)
        if (leagueId <= 0) throw IllegalStateException("FotMob league id unavailable")
        val root = requestObjectAbsolute("https://www.fotmob.com/api/data/leagues?id=$leagueId&ccode3=JPN")
        val events = LinkedHashMap<Long, RichEvent>()
        walkJson(root, 0, 14) { o ->
            if (o.optJSONObject("home") != null && o.optJSONObject("away") != null) {
                parseFotMobEvent(o)?.let { if (it.eventId > 0) events[it.eventId] = it }
            }
        }
        val all = events.values.sortedBy { it.startTimestamp }
        val live = all.filter { it.isLive }
        val future = all.filter { it.isScheduled }.sortedBy { it.startTimestamp }
        val past = all.filter { it.isFinished }.sortedByDescending { it.startTimestamp }
        val seed = live.firstOrNull() ?: future.firstOrNull() ?: past.firstOrNull()
        val label = seed?.roundLabel.orEmpty()
        val selected = if (label.isNotBlank()) all.filter { it.roundLabel == label } else (live + future.take(12)).distinctBy { it.eventId }
        val round = label.filter { it.isDigit() }.toIntOrNull() ?: 0
        return LeagueRoundData(league.id, leagueId, 0, round, label.ifBlank { "現在の節" }, selected.sortedBy { it.startTimestamp })
    }

    private fun resolveFotMobTeamId(team: FavoriteTeam): Int {
        if (team.fotmobId > 0) return team.fotmobId
        return FixtureRepository.searchTeams(team.name).filter { it.fotmobId > 0 }
            .maxByOrNull { nameScore(team.name, it.name) }?.fotmobId ?: 0
    }

    private fun resolveFotMobTournamentId(league: FavoriteLeague): Int {
        if (league.fotmobId > 0) return league.fotmobId
        return FixtureRepository.fetchLeagueDirectory().filter { it.fotmobId > 0 }
            .maxByOrNull { nameScore(league.name, it.name) }?.fotmobId ?: 0
    }

    private fun parseFotMobEvent(o: JSONObject): RichEvent? {
        val home = o.optJSONObject("home") ?: return null
        val away = o.optJSONObject("away") ?: return null
        val id = when (val v = o.opt("id")) { is Number -> v.toLong(); is String -> v.toLongOrNull() ?: 0L; else -> 0L }
        if (id <= 0L) return null
        val status = o.optJSONObject("status") ?: JSONObject()
        val kickoff = parseFotMobInstant(o) ?: return null
        val reasonText = fotMobReasonText(status)
        val finished = status.optBoolean("finished", false) ||
            reasonText.contains("full-time", true) || reasonText.equals("ft", true) || reasonText.contains("after penalties", true)
        val started = !finished && (status.optBoolean("started", false) ||
            reasonText.contains("live", true) || reasonText.contains("half", true) || reasonText.contains("extra time", true))
        val statusType = when { finished -> "finished"; started -> "inprogress"; else -> "scheduled" }
        val scoreHome = flexibleNullableInt(home.opt("score"))
        val scoreAway = flexibleNullableInt(away.opt("score"))
        // In FotMob team history, home/away.score can include shootout kicks,
        // while status.scoreStr remains the score before the shootout.
        val scoreBeforeShootout = if (isFotMobPenaltyShootout(status)) parseScorePair(status.optString("scoreStr")) else null
        val formScoreHome = scoreBeforeShootout?.first ?: scoreHome
        val formScoreAway = scoreBeforeShootout?.second ?: scoreAway
        val league = o.optJSONObject("league")
        val competition = league?.optString("name").orEmpty()
            .ifBlank { o.optString("leagueName") }.ifBlank { o.optString("parentLeagueName") }
        val round = o.optString("roundName").ifBlank { o.optString("leagueRoundName") }
            .ifBlank { o.optString("tournamentStage") }
        val liveMinute = extractFotMobLiveMinute(status)
        val page = o.optString("pageUrl")
        val url = when {
            page.startsWith("https://", true) -> page
            page.startsWith("/") -> "https://www.fotmob.com$page"
            page.isNotBlank() -> "https://www.fotmob.com/$page"
            else -> "https://www.fotmob.com/match/$id"
        }
        return RichEvent(
            eventId = id, startTimestamp = kickoff.epochSecond, statusType = statusType,
            statusDescription = reasonText,
            homeName = home.optString("name").ifBlank { home.optString("longName") },
            awayName = away.optString("name").ifBlank { away.optString("longName") },
            homeId = firstInt(home, "id", "teamId"), awayId = firstInt(away, "id", "teamId"),
            homeScore = scoreHome, awayScore = scoreAway,
            competition = competition,
            roundLabel = round, slug = "", customId = "", liveMinute = liveMinute,
            provider = DataSourceManager.FOTMOB, providerUrl = url,
            formHomeScore = formScoreHome, formAwayScore = formScoreAway
        )
    }

    private fun scoreWithoutShootout(score: JSONObject?): Int? {
        if (score == null) return null
        // `display` is the visible match score and excludes penalty-shootout kicks.
        // Fall back through overtime/normaltime/current for older payload shapes.
        return flexibleNullableInt(score.opt("display"))
            ?: flexibleNullableInt(score.opt("overtime"))
            ?: flexibleNullableInt(score.opt("normaltime"))
            ?: flexibleNullableInt(score.opt("current"))
    }

    private fun isFotMobPenaltyShootout(status: JSONObject): Boolean {
        val reason = status.opt("reason")
        val pieces = when (reason) {
            is String -> listOf(reason)
            is JSONObject -> listOf(
                reason.optString("short"), reason.optString("long"), reason.optString("name"),
                reason.optString("shortKey"), reason.optString("longKey")
            )
            else -> emptyList()
        }
        return pieces.any { value ->
            value.contains("penalt", ignoreCase = true) ||
                value.equals("pen", ignoreCase = true) ||
                value.contains("shootout", ignoreCase = true)
        }
    }

    private fun parseScorePair(value: String): Pair<Int, Int>? {
        val match = Regex("""(\d+)\s*[-–:]\s*(\d+)""").find(value) ?: return null
        val home = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val away = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return home to away
    }

    private fun fotMobReasonText(status: JSONObject): String {
        return when (val reason = status.opt("reason")) {
            is String -> reason
            is JSONObject -> listOf(reason.optString("long"), reason.optString("short"), reason.optString("name"))
                .firstOrNull { it.isNotBlank() }.orEmpty()
            else -> ""
        }
    }

    private fun parseFotMobInstant(o: JSONObject): Instant? {
        val values = listOf(
            o.optJSONObject("status")?.optString("utcTime").orEmpty(),
            o.optString("utcTime"), o.optString("matchTimeUTCDate")
        )
        for (v in values) if (v.isNotBlank()) runCatching { Instant.parse(v) }.getOrNull()?.let { return it }
        val millis = when (val v = o.opt("timeTS")) { is Number -> v.toLong(); is String -> v.toLongOrNull() ?: 0L; else -> 0L }
        return when { millis > 10_000_000_000L -> Instant.ofEpochMilli(millis); millis > 0L -> Instant.ofEpochSecond(millis); else -> null }
    }

    private fun extractFotMobLiveMinute(status: JSONObject): Int {
        val candidates = mutableListOf<String>()

        val liveObject = status.optJSONObject("liveTime")
        if (liveObject != null) {
            candidates += liveObject.optString("short")
            candidates += liveObject.optString("long")
        }

        val liveString = status.optString("liveTime")
        if (liveString.isNotBlank() && !liveString.trim().startsWith("{")) {
            candidates += liveString
        }

        candidates += fotMobReasonText(status)

        // 45+2 / 90 + 4 / 105+1 などを先に解析する。
        val addedTime = Regex("""(?<!\d)(\d{1,3})\s*['’]?\s*\+\s*(\d{1,2})""")
        for (candidate in candidates) {
            val match = addedTime.find(candidate) ?: continue
            val base = match.groupValues[1].toIntOrNull() ?: continue
            val extra = match.groupValues[2].toIntOrNull() ?: 0
            val total = base + extra
            if (total > 0) return total.coerceAtMost(130)
        }

        // 通常の 65' など。
        val minuteWithQuote = Regex("""(?<!\d)(\d{1,3})\s*['’]""")
        for (candidate in candidates) {
            val minute = minuteWithQuote.find(candidate)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (minute != null && minute > 0) {
                return minute.coerceAtMost(130)
            }
        }

        // 最後のフォールバック。
        val plainMinute = Regex(
            """(?<!\d)(\d{1,3})(?!\s*(?:st|nd|rd|th))""",
            RegexOption.IGNORE_CASE
        )

        for (candidate in candidates) {
            val minute = plainMinute.find(candidate)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (minute != null && minute in 1..130) {
                return minute
            }
        }

        return 0
    }

    private fun walkJson(node: Any?, depth: Int, maxDepth: Int, block: (JSONObject) -> Unit) {
        if (node == null || depth > maxDepth) return
        when (node) {
            is JSONObject -> {
                block(node)
                val keys = node.keys()
                while (keys.hasNext()) { val k = keys.next(); walkJson(node.opt(k), depth + 1, maxDepth, block) }
            }
            is JSONArray -> for (i in 0 until node.length()) walkJson(node.opt(i), depth + 1, maxDepth, block)
        }
    }

    private fun fetchPlayerExtra(player: FavoritePlayer): PlayerExtra {
        val resolved = resolveSofaPlayer(player)
        val sofaPlayerId = resolved.first
        val sofaTeamId = resolved.second
        if (sofaPlayerId <= 0) throw IllegalStateException("SofaScore player id unavailable")

        val playerEvents = fetchEvents("/player/$sofaPlayerId/events/last/0")
        val nextTeamEvents = if (sofaTeamId > 0) fetchEvents("/team/$sofaTeamId/events/next/0") else emptyList()
        val lastTeamEvents = if (sofaTeamId > 0) fetchEvents("/team/$sofaTeamId/events/last/0") else emptyList()
        val live = (nextTeamEvents + lastTeamEvents).firstOrNull { it.isLive }
        val next = nextTeamEvents.filter { !it.isFinished }.minByOrNull { it.startTimestamp }
        val finished = playerEvents.filter { it.isFinished }.sortedByDescending { it.startTimestamp }.take(5)
        val performances = finished.mapNotNull { event -> runCatching { fetchPlayerPerformance(event, sofaPlayerId, sofaTeamId, player.teamName, player.position) }.getOrNull() }
        val ratings = performances.mapNotNull { it.rating.takeIf(String::isNotBlank) }.take(5)
        val last = finished.firstOrNull()
        val lastPerf = performances.firstOrNull { it.eventId == last?.eventId }
        val targetEvent = live ?: next
        val lineupStatus = if (targetEvent != null) runCatching { fetchLineupStatus(targetEvent.eventId, sofaPlayerId) }.getOrDefault("未発表") else "試合なし"
        return PlayerExtra(player.id, sofaPlayerId, sofaTeamId, lineupStatus, live, next, last, ratings, lastPerf)
    }

    private fun fetchLeagueRound(league: FavoriteLeague): LeagueRoundData {
        val tournamentId = resolveSofaTournamentId(league)
        if (tournamentId <= 0) throw IllegalStateException("SofaScore league id unavailable")
        val seasonsRoot = requestObject("/unique-tournament/$tournamentId/seasons")
        val seasons = seasonsRoot.optJSONArray("seasons") ?: JSONArray()
        if (seasons.length() == 0) throw IllegalStateException("season unavailable")
        val seasonId = seasons.optJSONObject(0)?.optInt("id") ?: 0
        if (seasonId <= 0) throw IllegalStateException("season id unavailable")

        val roundsRoot = runCatching { requestObject("/unique-tournament/$tournamentId/season/$seasonId/rounds") }.getOrNull()
        var round = roundsRoot?.optJSONObject("currentRound")?.optInt("round") ?: 0
        var events = if (round > 0) runCatching { fetchEvents("/unique-tournament/$tournamentId/season/$seasonId/events/round/$round") }.getOrDefault(emptyList()) else emptyList()

        // Cup competitions sometimes expose a round number that the round endpoint does not serve.
        // Fall back to the next/last season events and group by the nearest available round.
        if (events.isEmpty()) {
            val next = fetchPagedSeasonEvents(tournamentId, seasonId, "next")
            val last = fetchPagedSeasonEvents(tournamentId, seasonId, "last")
            val combined = (last + next).distinctBy { it.eventId }
            val live = combined.filter { it.isLive }
            val future = combined.filter { it.isScheduled }.sortedBy { it.startTimestamp }
            val past = combined.filter { it.isFinished }.sortedByDescending { it.startTimestamp }
            val seed = live.firstOrNull() ?: future.firstOrNull() ?: past.firstOrNull()
            val label = seed?.roundLabel.orEmpty()
            if (label.isNotBlank()) events = combined.filter { it.roundLabel == label }.sortedBy { it.startTimestamp }
            else events = (live + future.take(10)).distinctBy { it.eventId }.sortedBy { it.startTimestamp }
            round = label.filter { it.isDigit() }.toIntOrNull() ?: round
        }

        events = events.sortedBy { it.startTimestamp }
        val label = events.firstOrNull()?.roundLabel?.ifBlank { if (round > 0) "第${round}節" else "現在の節" }
            ?: if (round > 0) "第${round}節" else "現在の節"
        return LeagueRoundData(league.id, tournamentId, seasonId, round, label, events)
    }

    private fun fetchPagedSeasonEvents(tournamentId: Int, seasonId: Int, course: String): List<RichEvent> {
        val paths = listOf(
            "/unique-tournament/$tournamentId/season/$seasonId/events/$course/0",
            "/unique-tournament/$tournamentId/season/$seasonId/events/$course/1"
        )
        return paths.flatMap { runCatching { fetchEvents(it) }.getOrDefault(emptyList()) }
    }

    private fun fetchPlayerPerformance(event: RichEvent, playerId: Int, teamId: Int, teamName: String, position: String): PlayerPerformance {
        // The dedicated player-stat endpoint is preferred. Some competitions only expose
        // player ratings inside the lineup payload, so use that as a fallback.
        val direct = runCatching { requestObject("/event/${event.eventId}/player/$playerId/statistics") }.getOrNull()
        var stats = direct?.optJSONObject("statistics") ?: direct
        if (stats == null || stats.length() == 0 || !stats.has("rating")) {
            stats = lineupPlayerStatistics(event.eventId, playerId) ?: stats
        }
        val s = stats ?: JSONObject()
        val rating = when (val v = s.opt("rating")) {
            is Number -> String.format(Locale.US, "%.1f", v.toDouble())
            is String -> v.toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: v
            else -> ""
        }
        val goals = firstInt(s, "goals", "goalsScored")
        val assists = firstInt(s, "goalAssist", "assists", "goalAssists")
        val minutes = firstInt(s, "minutesPlayed", "minutes", "minutesOnField")
        val isHome = when {
            teamId > 0 -> event.homeId == teamId
            teamName.isNotBlank() -> normalize(event.homeName) == normalize(teamName)
            else -> false
        }
        val oppScore = if (isHome) event.awayScore else event.homeScore
        val resultText = if (event.homeScore != null && event.awayScore != null) "${event.homeName} ${event.scoreText} ${event.awayName}" else ""
        val pos = position.uppercase(Locale.ROOT)
        val tookPart = minutes > 0 || rating.isNotBlank()
        val cleanSheet = (pos.startsWith("D") || pos.startsWith("G")) && tookPart && oppScore == 0
        return PlayerPerformance(event.eventId, rating, goals, assists, cleanSheet, minutes, resultText)
    }

    private fun lineupPlayerStatistics(eventId: Long, playerId: Int): JSONObject? {
        val root = runCatching { requestObject("/event/$eventId/lineups") }.getOrNull() ?: return null
        for (sideName in listOf("home", "away")) {
            val players = root.optJSONObject(sideName)?.optJSONArray("players") ?: continue
            for (i in 0 until players.length()) {
                val entry = players.optJSONObject(i) ?: continue
                val p = entry.optJSONObject("player") ?: entry
                if (p.optInt("id") == playerId) return entry.optJSONObject("statistics")
            }
        }
        return null
    }

    private fun fetchLineupStatus(eventId: Long, playerId: Int): String {
        val root = requestObject("/event/$eventId/lineups")
        val confirmed = root.optBoolean("confirmed", false)
        // SofaScore may provide predicted lineups before the official announcement.
        // Only classify starter/bench/absent after confirmed=true.
        if (!confirmed) return "未発表"
        for (sideName in listOf("home", "away")) {
            val side = root.optJSONObject(sideName) ?: continue
            val players = side.optJSONArray("players") ?: JSONArray()
            for (i in 0 until players.length()) {
                val entry = players.optJSONObject(i) ?: continue
                val p = entry.optJSONObject("player") ?: entry
                if (p.optInt("id") != playerId) continue
                val substitute = entry.optBoolean("substitute", false)
                return if (substitute) "ベンチ" else "スタメン"
            }
            val missing = side.optJSONArray("missingPlayers") ?: JSONArray()
            for (i in 0 until missing.length()) {
                val entry = missing.optJSONObject(i) ?: continue
                val p = entry.optJSONObject("player") ?: entry
                if (p.optInt("id") == playerId) return "ベンチ外"
            }
        }
        return if (confirmed) "ベンチ外" else "未発表"
    }

    private fun resolveSofaTeamId(team: FavoriteTeam): Int {
        if (team.sofascoreId > 0) return team.sofascoreId
        return FixtureRepository.searchTeams(team.name)
            .filter { it.sofascoreId > 0 }
            .maxByOrNull { nameScore(team.name, it.name) }
            ?.takeIf { nameScore(team.name, it.name) >= 0.72 }
            ?.sofascoreId ?: 0
    }

    private fun resolveSofaPlayer(player: FavoritePlayer): Pair<Int, Int> {
        if (player.sofascoreId > 0) return player.sofascoreId to player.sofascoreTeamId
        val candidates = FavoriteEntityRepository.searchPlayers(player.name).filter { it.sofascoreId > 0 }
        val best = candidates.maxByOrNull {
            nameScore(player.name, it.name) * 2.0 + if (player.teamName.isNotBlank() && normalize(player.teamName) == normalize(it.teamName)) 1.0 else 0.0
        }
        return if (best != null && nameScore(player.name, best.name) >= 0.72) best.sofascoreId to best.sofascoreTeamId else 0 to 0
    }

    private fun resolveSofaTournamentId(league: FavoriteLeague): Int {
        if (league.sofascoreId > 0) return league.sofascoreId
        val encoded = URLEncoder.encode(league.name, StandardCharsets.UTF_8.toString())
        val root = requestObjectAbsolute("https://api.sofascore.com/api/v1/search/all?q=$encoded")
        val a = root.optJSONArray("results") ?: root.optJSONArray("entities") ?: JSONArray()
        var bestId = 0
        var bestScore = 0.0
        for (i in 0 until a.length()) {
            val w = a.optJSONObject(i) ?: continue
            val type = w.optString("type").lowercase(Locale.ROOT)
            val e = w.optJSONObject("entity") ?: w
            val effectiveType = if (type.isNotBlank()) type else e.optString("type").lowercase(Locale.ROOT)
            if (effectiveType !in setOf("uniquetournament", "unique_tournament", "tournament", "league")) continue
            val name = e.optString("name")
            val id = e.optInt("id")
            if (id <= 0 || name.isBlank()) continue
            val score = nameScore(league.name, name)
            if (score > bestScore) { bestScore = score; bestId = id }
        }
        return bestId.takeIf { bestScore >= 0.60 } ?: 0
    }

    private fun fetchEvents(path: String): List<RichEvent> {
        val root = requestObject(path)
        val a = root.optJSONArray("events") ?: JSONArray()
        return buildList {
            root.optJSONObject("event")?.let { add(parseEvent(it)) }
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(parseEvent(it)) }
        }.filter { it.eventId > 0 }.distinctBy { it.eventId }
    }

    private fun parseEvent(o: JSONObject): RichEvent {
        val home = o.optJSONObject("homeTeam") ?: JSONObject()
        val away = o.optJSONObject("awayTeam") ?: JSONObject()
        val status = o.optJSONObject("status") ?: JSONObject()
        val tournament = o.optJSONObject("tournament") ?: JSONObject()
        val unique = tournament.optJSONObject("uniqueTournament")
        val homeScoreObject = o.optJSONObject("homeScore")
        val awayScoreObject = o.optJSONObject("awayScore")
        val scoreHome = flexibleNullableInt(homeScoreObject?.opt("current"))
        val scoreAway = flexibleNullableInt(awayScoreObject?.opt("current"))
        // SofaScore keeps shootout goals in `current` but exposes the match score
        // separately as `display` (and the shootout itself as `penalties`).
        val formScoreHome = scoreWithoutShootout(homeScoreObject) ?: scoreHome
        val formScoreAway = scoreWithoutShootout(awayScoreObject) ?: scoreAway
        val roundObj = o.optJSONObject("roundInfo")
        val round = roundObj?.optInt("round") ?: 0
        val roundName = roundObj?.optString("name").orEmpty().ifBlank { o.optString("roundName") }
        val roundLabel = when {
            roundName.isNotBlank() -> roundName
            round > 0 -> "第${round}節"
            else -> ""
        }
        val start = o.optLong("startTimestamp", 0L)
        val liveMinute = computeLiveMinute(o, start, status.optString("type"))
        return RichEvent(
            eventId = o.optLong("id", 0L),
            startTimestamp = start,
            statusType = status.optString("type"),
            statusDescription = status.optString("description"),
            homeName = home.optString("name").ifBlank { home.optString("shortName") },
            awayName = away.optString("name").ifBlank { away.optString("shortName") },
            homeId = home.optInt("id"),
            awayId = away.optInt("id"),
            homeScore = scoreHome,
            awayScore = scoreAway,
            competition = unique?.optString("name").orEmpty().ifBlank { tournament.optString("name") },
            roundLabel = roundLabel,
            slug = o.optString("slug"),
            customId = o.optString("customId"),
            liveMinute = liveMinute,
            formHomeScore = formScoreHome,
            formAwayScore = formScoreAway
        )
    }

    private fun computeLiveMinute(o: JSONObject, startTimestamp: Long, statusType: String): Int {
        if (!statusType.equals("inprogress", true) && !statusType.equals("live", true)) return 0
        val time = o.optJSONObject("time")
        val initial = time?.optInt("initial", 0) ?: 0
        val periodStart = time?.optLong("currentPeriodStartTimestamp", 0L) ?: 0L
        if (periodStart > 0L) {
            val extra = max(0L, Instant.now().epochSecond - periodStart).toInt()
            val raw = if (initial > 180) (initial + extra) / 60 else initial + extra / 60
            if (raw > 0) return raw.coerceAtMost(130)
        }
        if (startTimestamp <= 0L) return 1
        var mins = ((Instant.now().epochSecond - startTimestamp) / 60L).toInt().coerceAtLeast(1)
        if (mins > 60) mins -= 15 // approximate halftime when provider clock fields are missing
        return mins.coerceAtMost(130)
    }

    private fun requestObject(path: String): JSONObject = requestObjectAbsolute("https://api.sofascore.com/api/v1$path")

    private fun requestObjectAbsolute(url: String): JSONObject {
        if (url.contains("sofascore", true)) {
            val value = SofaScoreHttp.getAny(url)
            return value as? JSONObject ?: throw IllegalStateException("SofaScore JSON object expected")
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 11000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val value = JSONTokener(body).nextValue()
            return value as? JSONObject ?: throw IllegalStateException("JSON object expected")
        } finally { conn.disconnect() }
    }

    private fun saveTeamExtras(context: Context, data: Map<Int, TeamExtra>) {
        val a = JSONArray()
        data.values.forEach { e -> a.put(JSONObject().apply {
            put("teamId", e.teamId); put("sofaTeamId", e.sofaTeamId)
            e.live?.let { put("live", eventToJson(it)) }; e.last?.let { put("last", eventToJson(it)) }; e.next?.let { put("next", eventToJson(it)) }
            put("recentForm", JSONArray(e.recentForm))
        }) }
        prefs(context).edit().putString(TEAM_KEY, a.toString()).putLong(UPDATED_KEY, System.currentTimeMillis()).apply()
    }

    private fun savePlayerExtras(context: Context, data: Map<Int, PlayerExtra>) {
        val a = JSONArray()
        data.values.forEach { e -> a.put(JSONObject().apply {
            put("playerId", e.playerId); put("sofaPlayerId", e.sofaPlayerId); put("sofaTeamId", e.sofaTeamId); put("lineupStatus", e.lineupStatus)
            e.live?.let { put("live", eventToJson(it)) }; e.next?.let { put("next", eventToJson(it)) }; e.last?.let { put("last", eventToJson(it)) }
            put("recentRatings", JSONArray(e.recentRatings)); e.lastPerformance?.let { put("lastPerformance", performanceToJson(it)) }
        }) }
        prefs(context).edit().putString(PLAYER_KEY, a.toString()).putLong(UPDATED_KEY, System.currentTimeMillis()).apply()
    }

    private fun saveLeagueRounds(context: Context, data: Map<Int, LeagueRoundData>) {
        val a = JSONArray()
        data.values.forEach { e -> a.put(JSONObject().apply {
            put("leagueId", e.leagueId); put("sofaTournamentId", e.sofaTournamentId); put("seasonId", e.seasonId); put("roundNumber", e.roundNumber); put("roundLabel", e.roundLabel)
            put("events", JSONArray().apply { e.events.forEach { put(eventToJson(it)) } })
        }) }
        prefs(context).edit().putString(LEAGUE_KEY, a.toString()).putLong(UPDATED_KEY, System.currentTimeMillis()).apply()
    }

    private fun eventToJson(e: RichEvent): JSONObject = JSONObject().apply {
        put("eventId", e.eventId); put("startTimestamp", e.startTimestamp); put("statusType", e.statusType); put("statusDescription", e.statusDescription)
        put("homeName", e.homeName); put("awayName", e.awayName); put("homeId", e.homeId); put("awayId", e.awayId)
        if (e.homeScore != null) put("homeScore", e.homeScore); if (e.awayScore != null) put("awayScore", e.awayScore)
        if (e.formHomeScore != null) put("formHomeScore", e.formHomeScore); if (e.formAwayScore != null) put("formAwayScore", e.formAwayScore)
        put("competition", e.competition); put("roundLabel", e.roundLabel); put("slug", e.slug); put("customId", e.customId); put("liveMinute", e.liveMinute)
        put("provider", e.provider); put("providerUrl", e.providerUrl)
    }

    private fun eventFromJson(o: JSONObject): RichEvent = RichEvent(
        eventId = o.optLong("eventId"),
        startTimestamp = o.optLong("startTimestamp"),
        statusType = o.optString("statusType"),
        statusDescription = o.optString("statusDescription"),
        homeName = o.optString("homeName"),
        awayName = o.optString("awayName"),
        homeId = o.optInt("homeId"),
        awayId = o.optInt("awayId"),
        homeScore = if (o.has("homeScore")) o.optInt("homeScore") else null,
        awayScore = if (o.has("awayScore")) o.optInt("awayScore") else null,
        competition = o.optString("competition"),
        roundLabel = o.optString("roundLabel"),
        slug = o.optString("slug"),
        customId = o.optString("customId"),
        liveMinute = o.optInt("liveMinute"),
        provider = o.optString("provider").ifBlank { DataSourceManager.SOFASCORE },
        providerUrl = o.optString("providerUrl"),
        formHomeScore = if (o.has("formHomeScore")) o.optInt("formHomeScore") else if (o.has("homeScore")) o.optInt("homeScore") else null,
        formAwayScore = if (o.has("formAwayScore")) o.optInt("formAwayScore") else if (o.has("awayScore")) o.optInt("awayScore") else null
    )

    private fun performanceToJson(p: PlayerPerformance): JSONObject = JSONObject().apply {
        put("eventId", p.eventId); put("rating", p.rating); put("goals", p.goals); put("assists", p.assists); put("cleanSheet", p.cleanSheet); put("minutesPlayed", p.minutesPlayed); put("resultText", p.resultText)
    }

    private fun performanceFromJson(o: JSONObject): PlayerPerformance = PlayerPerformance(
        o.optLong("eventId"), o.optString("rating"), o.optInt("goals"), o.optInt("assists"), o.optBoolean("cleanSheet"), o.optInt("minutesPlayed"), o.optString("resultText")
    )

    private fun jsonStringList(a: JSONArray?): List<String> = buildList {
        if (a != null) for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun firstInt(o: JSONObject, vararg keys: String): Int {
        keys.forEach { k -> when (val v = o.opt(k)) { is Number -> return v.toInt(); is String -> v.toIntOrNull()?.let { return it } } }
        return 0
    }

    private fun flexibleNullableInt(v: Any?): Int? = when (v) { is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null }
    private fun normalize(v: String): String = v.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\p{L}]+"), "")
    private fun nameScore(a: String, b: String): Double {
        val x = normalize(a); val y = normalize(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.9
        val xs = x.chunked(3).toSet(); val ys = y.chunked(3).toSet()
        if (xs.isEmpty() || ys.isEmpty()) return 0.0
        return xs.intersect(ys).size.toDouble() / xs.union(ys).size.toDouble()
    }
}
