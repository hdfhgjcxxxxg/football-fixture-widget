package com.example.footballfixturewidget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Resolves football-data.org fixtures to FotMob/Sofascore event IDs.
 *
 * The providers use unrelated IDs, so kickoff + both team names are matched.
 * v4 additionally stores each provider's canonical match URL so Android App Links
 * have an exact content URL instead of only a provider home page.
 */
object ExternalMatchResolver {

    private data class Candidate(
        val id: Long,
        val home: String,
        val away: String,
        val kickoff: Instant?,
        val pageUrl: String? = null
    )

    fun resolveAll(fixtures: List<NextFixture>): List<NextFixture> = resolveSofaScore(resolveFotMob(fixtures))

    fun resolveForTarget(fixtures: List<NextFixture>, target: String): List<NextFixture> {
        if (fixtures.isEmpty()) return fixtures
        return when (target) {
            FixtureRepository.TAP_FOTMOB -> resolveFotMob(fixtures)
            FixtureRepository.TAP_SOFASCORE -> resolveSofaScore(fixtures)
            else -> fixtures
        }
    }

    private fun resolveFotMob(fixtures: List<NextFixture>): List<NextFixture> {
        val byDate = mutableMapOf<LocalDate, List<Candidate>>()
        return fixtures.map { fixture ->
            if (!fixture.hasMatch || fixture.utcDate.isBlank() || fixture.fotmobMatchId > 0L) return@map fixture
            val match = candidateDates(fixture.utcDate).asSequence()
                .flatMap { date ->
                    byDate.getOrPut(date) { runCatching { fetchFotMobDate(date) }.getOrDefault(emptyList()) }.asSequence()
                }
                .distinctBy { it.id }
                .map { it to matchScore(fixture, it) }
                .filter { it.second >= 0.62 }
                .maxByOrNull { it.second }
                ?.first

            if (match != null) {
                fixture.copy(
                    fotmobMatchId = match.id,
                    fotmobUrl = match.pageUrl?.let(::absoluteFotMobUrl)
                        ?: exactFotMobLegacyUrl(match)
                )
            } else fixture
        }
    }

    private fun resolveSofaScore(fixtures: List<NextFixture>): List<NextFixture> {
        val byDate = mutableMapOf<LocalDate, List<Candidate>>()
        return fixtures.map { fixture ->
            if (!fixture.hasMatch || fixture.utcDate.isBlank() || fixture.sofascoreEventId > 0L) return@map fixture
            val match = candidateDates(fixture.utcDate).asSequence()
                .flatMap { date ->
                    byDate.getOrPut(date) { runCatching { fetchSofaDate(date) }.getOrDefault(emptyList()) }.asSequence()
                }
                .distinctBy { it.id }
                .map { it to matchScore(fixture, it) }
                .filter { it.second >= 0.62 }
                .maxByOrNull { it.second }
                ?.first

            if (match != null) {
                fixture.copy(
                    sofascoreEventId = match.id,
                    // Important: current Sofascore match pages use
                    // /football/match/{slug}/{customId}; /event/{eventId} is not the
                    // preferred public match-page URL and often won't deep-link.
                    sofascoreUrl = match.pageUrl ?: "https://www.sofascore.com/football/match/match#id:${match.id}"
                )
            } else fixture
        }
    }

    /** Query UTC and local dates so late-night matches aren't missed. */
    private fun candidateDates(utcDate: String): List<LocalDate> {
        val instant = runCatching { Instant.parse(utcDate) }.getOrNull() ?: return emptyList()
        return listOf(
            instant.atZone(ZoneOffset.UTC).toLocalDate(),
            instant.atZone(ZoneId.systemDefault()).toLocalDate()
        ).distinct()
    }

    private fun fetchFotMobDate(date: LocalDate): List<Candidate> {
        val value = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        // Prefer the current /api/data/matches route, with the legacy route as fallback.
        val root = requestJsonWithFallback(
            "https://www.fotmob.com/api/data/matches?date=$value&timezone=Asia%2FTokyo&ccode3=JPN",
            "https://www.fotmob.com/api/matches?date=$value"
        )
        val leagues = root.optJSONArray("leagues") ?: JSONArray()
        return buildList {
            for (i in 0 until leagues.length()) {
                val league = leagues.optJSONObject(i) ?: continue
                val matches = league.optJSONArray("matches") ?: continue
                for (j in 0 until matches.length()) {
                    val match = matches.optJSONObject(j) ?: continue
                    val id = match.optLong("id", -1L)
                    if (id <= 0L) continue
                    val home = match.optJSONObject("home")?.optString("name").orEmpty()
                    val away = match.optJSONObject("away")?.optString("name").orEmpty()
                    val utc = match.optJSONObject("status")?.optString("utcTime").orEmpty()
                    val kickoff = parseInstantFlexible(utc)

                    // Some FotMob responses include a page URL. Keep it when present;
                    // otherwise exactFotMobLegacyUrl() uses the numeric match ID.
                    val pageUrl = match.optString("pageUrl").takeIf { it.isNotBlank() }
                    add(Candidate(id, home, away, kickoff, pageUrl))
                }
            }
        }
    }

    private fun fetchSofaDate(date: LocalDate): List<Candidate> {
        val root = requestJson("https://api.sofascore.com/api/v1/sport/football/scheduled-events/$date")
        val events = root.optJSONArray("events") ?: JSONArray()
        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val id = event.optLong("id", -1L)
                if (id <= 0L) continue

                val homeObj = event.optJSONObject("homeTeam") ?: JSONObject()
                val awayObj = event.optJSONObject("awayTeam") ?: JSONObject()
                val home = homeObj.optString("name").ifBlank { homeObj.optString("shortName") }
                val away = awayObj.optString("name").ifBlank { awayObj.optString("shortName") }
                val startTimestamp = event.optLong("startTimestamp", 0L)
                val kickoff = startTimestamp.takeIf { it > 0L }?.let { Instant.ofEpochSecond(it) }

                val slug = event.optString("slug").trim('/')
                val customId = event.optString("customId").trim('/')
                val canonicalUrl = when {
                    slug.isNotBlank() && customId.isNotBlank() -> "https://www.sofascore.com/football/match/$slug/$customId#id:$id"
                    slug.isNotBlank() -> "https://www.sofascore.com/football/match/$slug#id:$id"
                    else -> null
                }

                add(Candidate(id, home, away, kickoff, canonicalUrl))
            }
        }
    }


    private fun requestJsonWithFallback(vararg urls: String): JSONObject {
        var last: Throwable? = null
        for (url in urls) {
            try { return requestJson(url) } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("試合データを取得できませんでした")
    }

    private fun requestJson(url: String): JSONObject {
        if (url.contains("sofascore", true)) {
            val value = SofaScoreHttp.getAny(url)
            return value as? JSONObject ?: throw IllegalStateException("SofaScore JSON object expected")
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6500
            readTimeout = 9000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "ja,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Referer", "https://www.fotmob.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("${URL(url).host}: HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun exactFotMobLegacyUrl(candidate: Candidate): String {
        val slug = "${webSlug(candidate.home)}-vs-${webSlug(candidate.away)}"
        // This legacy numeric route identifies one exact match, unlike a generic
        // team-v-team matchup page. FotMob currently still serves these URLs.
        return "https://www.fotmob.com/match/${candidate.id}/lineup/$slug"
    }

    private fun webSlug(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun matchScore(fixture: NextFixture, candidate: Candidate): Double {
        val expected = runCatching { Instant.parse(fixture.utcDate) }.getOrNull()
        val timeScore = if (expected != null && candidate.kickoff != null) {
            val diffMinutes = abs(expected.epochSecond - candidate.kickoff.epochSecond) / 60.0
            when {
                diffMinutes <= 3 -> 1.0
                diffMinutes <= 15 -> 0.96
                diffMinutes <= 45 -> 0.84
                diffMinutes <= 120 -> 0.62
                else -> 0.0
            }
        } else 0.35

        val homeScore = bestNameScore(
            listOf(fixture.homeTeamName, fixture.homeTeamShortName, fixture.homeTeamTla),
            candidate.home
        )
        val awayScore = bestNameScore(
            listOf(fixture.awayTeamName, fixture.awayTeamShortName, fixture.awayTeamTla),
            candidate.away
        )

        if (homeScore < 0.22 || awayScore < 0.22) return 0.0
        return (timeScore * 0.52) + (homeScore * 0.24) + (awayScore * 0.24)
    }

    private fun bestNameScore(aliases: List<String>, other: String): Double =
        aliases.asSequence()
            .filter { it.isNotBlank() }
            .map { nameScore(it, other) }
            .maxOrNull() ?: 0.0

    private fun nameScore(a: String, b: String): Double {
        val x = normalizeName(a)
        val y = normalizeName(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.90

        val xt = x.split(' ').filter { it.length > 1 }.toSet()
        val yt = y.split(' ').filter { it.length > 1 }.toSet()
        if (xt.isEmpty() || yt.isEmpty()) return 0.0
        val intersection = xt.intersect(yt).size.toDouble()
        val union = xt.union(yt).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union

        val edgeBonus = when {
            xt.firstOrNull() == yt.firstOrNull() && xt.lastOrNull() == yt.lastOrNull() -> 0.18
            xt.firstOrNull() == yt.firstOrNull() || xt.lastOrNull() == yt.lastOrNull() -> 0.10
            else -> 0.0
        }
        return (jaccard + edgeBonus).coerceAtMost(0.88)
    }

    private fun normalizeName(input: String): String {
        val ascii = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        val noise = setOf(
            "fc", "cf", "afc", "ac", "sc", "club", "football", "futbol", "calcio",
            "the", "de", "cd", "ud", "sv", "fk", "sk", "ssc"
        )
        return ascii.split(' ')
            .filter { it.isNotBlank() && it !in noise }
            .joinToString(" ")
            .replace("manchester united", "man united")
            .replace("manchester city", "man city")
            .replace("paris saint germain", "psg")
            .replace("internazionale", "inter")
            .replace("inter milan", "inter")
            .replace("bayern munich", "bayern")
    }

    private fun parseInstantFlexible(value: String): Instant? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value) }.getOrElse {
            runCatching {
                val normalized = if (value.endsWith("Z")) value else "${value}Z"
                Instant.parse(normalized)
            }.getOrNull()
        }
    }

    private fun absoluteFotMobUrl(pageUrl: String): String = when {
        pageUrl.startsWith("https://", true) || pageUrl.startsWith("http://", true) -> pageUrl
        pageUrl.startsWith("/") -> "https://www.fotmob.com$pageUrl"
        else -> "https://www.fotmob.com/$pageUrl"
    }
}
