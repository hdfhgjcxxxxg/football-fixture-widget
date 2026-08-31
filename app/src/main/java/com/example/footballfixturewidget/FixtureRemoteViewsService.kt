package com.example.footballfixturewidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.Instant

class FixtureRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        FixtureFactory(
            context = applicationContext,
            widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
            kind = intent.getStringExtra("widget_kind") ?: WidgetKinds.TEAM
        )
}

private data class WidgetRow(
    val rowId: Long,
    val entityId: Int,
    val kind: String,
    val title: String,
    val matchup: String,
    val meta: String,
    val bottom: String,
    val fixture: NextFixture?,
    val liveEvent: RichEvent? = null,
    val team: FavoriteTeam? = null,
    val player: FavoritePlayer? = null,
    val league: FavoriteLeague? = null
)

private class FixtureFactory(
    private val context: Context,
    private val widgetId: Int,
    private val kind: String
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetRow> = emptyList()

    override fun onCreate() = onDataSetChanged()

    override fun onDataSetChanged() {
        val selected = WidgetSelectionStore.getSelectedIds(context, widgetId, kind).toSet()
        rows = when (kind) {
            WidgetKinds.PLAYER -> playerRows(selected)
            WidgetKinds.LEAGUE -> leagueRows(selected)
            else -> teamRows(selected)
        }
    }

    private fun teamRows(selected: Set<Int>): List<WidgetRow> {
        val favorites = FixtureRepository.getFavoriteTeams(context).associateBy { it.id }
        val normalCache = FixtureRepository.loadCache(context).fixtures.associateBy { it.teamId }
        val extras = AdvancedStatsRepository.loadTeamExtras(context)
        return selected.mapNotNull { id ->
            val team = favorites[id] ?: return@mapNotNull null
            val extra = extras[id]
            val live = extra?.live
            val next = extra?.next
            val last = extra?.last
            val normal = normalCache[id]

            val fixture = when {
                live != null -> live.asFixture(team.id, team.name, extra.sofaTeamId)
                next != null -> mergeProviderLinks(next.asFixture(team.id, team.name, extra.sofaTeamId), normal)
                else -> normal
            }
            val matchup = when {
                live != null -> "${live.homeName} ${live.scoreText} ${live.awayName}"
                last != null && next != null -> "前 ${last.homeName} ${last.scoreText} ${last.awayName}  •  次 ${if (next.homeId == extra.sofaTeamId) "vs ${next.awayName}" else "@ ${next.homeName}"}"
                fixture != null -> (if (fixture.isHome) "vs " else "@ ") + fixture.opponent
                else -> "日程を取得中"
            }
            val form = extra?.recentForm?.take(5)?.joinToString(" ").orEmpty()
            val meta = listOf(
                if (live != null) "LIVE ${live.liveMinute.coerceAtLeast(1)}'" else fixture?.competition.orEmpty(),
                if (form.isNotBlank()) "直近5 $form" else ""
            ).filter(String::isNotBlank).joinToString(" • ")
            val bottom = when {
                live != null -> "試合中 • ${live.competition}"
                next != null -> "次 ${FixtureRepository.formatDate(Instant.ofEpochSecond(next.startTimestamp).toString())}"
                last != null -> "前試合 ${last.scoreText}"
                fixture?.hasMatch == true -> FixtureRepository.formatDate(fixture.utcDate)
                else -> "日時未定"
            }
            WidgetRow(team.id.toLong(), team.id, kind, team.name, matchup, meta, bottom, fixture, live, team = team)
        }
    }

    private fun playerRows(selected: Set<Int>): List<WidgetRow> {
        val favorites = FavoriteEntityRepository.getFavoritePlayers(context).associateBy { it.id }
        val cache = SupplementalWidgetRepository.loadPlayerCache(context).associateBy { it.playerId }
        val extras = AdvancedStatsRepository.loadPlayerExtras(context)
        return selected.mapNotNull { id ->
            val player = favorites[id] ?: return@mapNotNull null
            val extra = extras[id]
            val live = extra?.live
            val next = extra?.next
            val fixture = when {
                live != null -> live.asFixture(player.id, player.name, extra.sofaTeamId)
                next != null -> next.asFixture(player.id, player.name, extra.sofaTeamId)
                else -> cache[id]?.fixture
            }
            val matchup = when {
                live != null -> "${live.homeName} ${live.scoreText} ${live.awayName}"
                fixture != null -> (if (fixture.isHome) "vs " else "@ ") + fixture.opponent
                else -> "次の試合を取得中"
            }
            val ratings = extra?.recentRatings?.take(5)?.joinToString("  ").orEmpty()
            val meta = listOf(
                extra?.lineupStatus?.takeIf { it.isNotBlank() }.orEmpty(),
                if (ratings.isNotBlank()) "直近5評価 $ratings" else "",
                player.teamName
            ).filter(String::isNotBlank).joinToString(" • ")
            val p = extra?.lastPerformance
            val stat = p?.let {
                val pos = player.position.uppercase()
                val parts = mutableListOf<String>()
                if (it.rating.isNotBlank()) parts += "評価 ${it.rating}"
                if (pos.startsWith("F") || pos.startsWith("M") || pos.startsWith("D") || pos.startsWith("G")) {
                    parts += "G${it.goals}/A${it.assists}"
                }
                if ((pos.startsWith("D") || pos.startsWith("G")) && it.cleanSheet) parts += "CS"
                parts.joinToString(" • ")
            }.orEmpty()
            val bottom = when {
                live != null -> "試合中 • ${extra?.lineupStatus?.ifBlank { "出場状況取得中" } ?: "出場状況取得中"}"
                p != null -> "前試合 ${p.resultText}${if (stat.isNotBlank()) " • $stat" else ""}"
                fixture?.hasMatch == true -> FixtureRepository.formatDate(fixture.utcDate)
                else -> "日時未定"
            }
            WidgetRow(player.id.toLong(), player.id, kind, player.name, matchup, meta, bottom, fixture, live, player = player)
        }
    }

    private fun leagueRows(selected: Set<Int>): List<WidgetRow> {
        val favorites = FavoriteEntityRepository.getFavoriteLeagues(context).associateBy { it.id }
        val rounds = AdvancedStatsRepository.loadLeagueRounds(context)
        val fallback = SupplementalWidgetRepository.loadLeagueCache(context).associateBy { it.leagueId }
        val out = mutableListOf<WidgetRow>()
        selected.forEach { id ->
            val league = favorites[id] ?: return@forEach
            val data = rounds[id]
            if (data != null && data.events.isNotEmpty()) {
                data.events.forEach { event ->
                    val fixture = event.asFixture(-league.id, league.name)
                    val state = when {
                        event.isLive -> "LIVE ${event.liveMinute.coerceAtLeast(1)}' • ${event.scoreText}"
                        event.isFinished -> "FT • ${event.scoreText}"
                        else -> "${data.roundLabel} • 予定"
                    }
                    out += WidgetRow(
                        rowId = event.eventId,
                        entityId = league.id,
                        kind = kind,
                        title = "${league.name} • ${data.roundLabel}",
                        matchup = "${event.homeName} vs ${event.awayName}",
                        meta = state,
                        bottom = if (event.startTimestamp > 0L) FixtureRepository.formatDate(Instant.ofEpochSecond(event.startTimestamp).toString()) else "日時未定",
                        fixture = fixture,
                        liveEvent = event,
                        league = league
                    )
                }
            } else {
                val fixture = fallback[id]?.fixture
                out += WidgetRow(
                    rowId = -league.id.toLong(), entityId = league.id, kind = kind,
                    title = league.name, matchup = fixture?.opponent ?: "節データを取得中",
                    meta = league.country, bottom = fixture?.utcDate?.takeIf(String::isNotBlank)?.let(FixtureRepository::formatDate) ?: "更新してください",
                    fixture = fixture, league = league
                )
            }
        }
        return out
    }

    private fun mergeProviderLinks(primary: NextFixture, fallback: NextFixture?): NextFixture {
        if (fallback == null) return primary
        return primary.copy(
            fotmobMatchId = if (primary.fotmobMatchId > 0) primary.fotmobMatchId else fallback.fotmobMatchId,
            fotmobUrl = primary.fotmobUrl.ifBlank { fallback.fotmobUrl },
            sofascoreEventId = if (primary.sofascoreEventId > 0) primary.sofascoreEventId else fallback.sofascoreEventId,
            sofascoreUrl = primary.sofascoreUrl.ifBlank { fallback.sofascoreUrl }
        )
    }

    override fun onDestroy() = Unit
    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val row = rows.getOrNull(position) ?: return null
        val fixture = row.fixture
        val views = RemoteViews(context.packageName, R.layout.widget_team_row)
        val background = FixtureRepository.getWidgetColor(context)
        val primary = FixtureRepository.preferredTextColor(background)
        val secondary = FixtureRepository.secondaryTextColor(background)
        val rowColor = FixtureRepository.rowColor(background)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) views.setColorStateList(R.id.row_root, "setBackgroundTintList", ColorStateList.valueOf(rowColor))
        else views.setInt(R.id.row_root, "setBackgroundColor", rowColor)

        listOf(R.id.team_name, R.id.matchup, R.id.countdown).forEach { views.setTextColor(it, primary) }
        listOf(R.id.date_time, R.id.competition, R.id.open_hint, R.id.countdown_label).forEach { views.setTextColor(it, secondary) }

        views.setTextViewText(R.id.team_name, row.title)
        views.setTextViewText(R.id.matchup, row.matchup)
        views.setTextViewText(R.id.competition, row.meta)
        views.setTextViewText(R.id.date_time, row.bottom)

        val logo = when (row.kind) {
            WidgetKinds.PLAYER -> row.player?.let { EntityImageLoader.loadPlayer(context, it) }
            WidgetKinds.LEAGUE -> row.league?.let { EntityImageLoader.loadLeague(context, it) }
            else -> row.team?.let { TeamLogoLoader.load(context, it) }
        }
        if (logo != null) {
            views.setImageViewBitmap(R.id.team_logo, logo)
        } else {
            views.setImageViewResource(
                R.id.team_logo,
                when (row.kind) {
                    WidgetKinds.PLAYER -> R.drawable.ic_player_placeholder
                    WidgetKinds.LEAGUE -> R.drawable.ic_league_placeholder
                    else -> R.drawable.ic_launcher
                }
            )
        }

        renderClock(views, row, fixture)
        renderOpenHint(views, fixture)

        views.setOnClickFillInIntent(
            R.id.row_root,
            Intent().apply {
                putExtra("team_id", fixture?.teamId ?: row.entityId)
                putExtra("team_name", row.title)
                putExtra("fotmob_match_id", fixture?.fotmobMatchId ?: 0L)
                putExtra("fotmob_url", fixture?.fotmobUrl.orEmpty())
                putExtra("sofascore_event_id", fixture?.sofascoreEventId ?: 0L)
                putExtra("sofascore_url", fixture?.sofascoreUrl.orEmpty())
            }
        )
        return views
    }

    private fun renderClock(views: RemoteViews, row: WidgetRow, fixture: NextFixture?) {
        val live = row.liveEvent
        val showDetail = WidgetSelectionStore.showDetailedCountdown(context, widgetId, kind)
        when {
            live != null && live.isLive -> {
                views.setViewVisibility(R.id.countdown_label, View.VISIBLE)
                views.setViewVisibility(R.id.countdown, View.VISIBLE)
                val paused = live.statusDescription.contains("half", true) || live.statusDescription.contains("break", true)
                views.setTextViewText(R.id.countdown_label, if (paused) "HALF TIME" else "MATCH TIME")
                if (paused) {
                    views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime(), "%s", false)
                    views.setTextViewText(R.id.countdown, "HT")
                } else if (showDetail) {
                    val base = SystemClock.elapsedRealtime() - live.liveMinute.coerceAtLeast(1) * 60_000L
                    views.setChronometer(R.id.countdown, base, "%s", true)
                    views.setChronometerCountDown(R.id.countdown, false)
                } else {
                    views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime(), "%s", false)
                    views.setTextViewText(R.id.countdown, "${live.liveMinute.coerceAtLeast(1)}'")
                }
            }
            fixture?.hasMatch == true && fixture.utcDate.isNotBlank() -> {
                val remaining = FixtureRepository.remainingMillis(fixture.utcDate)
                if (remaining > 0L) {
                    views.setViewVisibility(R.id.countdown_label, View.VISIBLE)
                    views.setViewVisibility(R.id.countdown, View.VISIBLE)
                    views.setTextViewText(R.id.countdown_label, "KICKOFF IN")
                    if (showDetail) {
                        views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + remaining, "%s", true)
                        views.setChronometerCountDown(R.id.countdown, true)
                    } else {
                        views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime(), "%s", false)
                        views.setTextViewText(R.id.countdown, formatHoursOnly(remaining))
                    }
                } else {
                    views.setViewVisibility(R.id.countdown_label, View.VISIBLE)
                    views.setViewVisibility(R.id.countdown, View.VISIBLE)
                    views.setTextViewText(R.id.countdown_label, "LIVE")
                    views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime(), "%s", false)
                    views.setTextViewText(R.id.countdown, "LIVE")
                }
            }
            else -> {
                views.setViewVisibility(R.id.countdown_label, View.GONE)
                views.setViewVisibility(R.id.countdown, View.GONE)
            }
        }
    }

    private fun renderOpenHint(views: RemoteViews, fixture: NextFixture?) {
        val tapTarget = FixtureRepository.getTapTarget(context)
        views.setViewVisibility(R.id.open_hint, if (tapTarget == FixtureRepository.TAP_NONE) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.open_hint, when (tapTarget) {
            FixtureRepository.TAP_FOTMOB -> if ((fixture?.fotmobMatchId ?: 0L) > 0L) "FotMob 試合 ↗" else "FotMob ↗"
            FixtureRepository.TAP_SOFASCORE -> if ((fixture?.sofascoreEventId ?: 0L) > 0L) "SofaScore 試合 ↗" else "SofaScore ↗"
            FixtureRepository.TAP_SETTINGS -> "設定 ↗"
            FixtureRepository.TAP_ONEFOOTBALL -> "OneFootball ↗"
            FixtureRepository.TAP_FLASHSCORE -> "Flashscore ↗"
            FixtureRepository.TAP_LIVESCORE -> "LiveScore ↗"
            FixtureRepository.TAP_365SCORES -> "365Scores ↗"
            else -> ""
        })
    }

    private fun formatHoursOnly(remainingMillis: Long): String {
        if (remainingMillis <= 0L) return "LIVE"
        val hourMs = 60L * 60L * 1000L
        if (remainingMillis < hourMs) return "<1h"
        return "${remainingMillis / hourMs}h"
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.rowId ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
