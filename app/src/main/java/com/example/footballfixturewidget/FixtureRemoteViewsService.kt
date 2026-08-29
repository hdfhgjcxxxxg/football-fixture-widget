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

class FixtureRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        FixtureFactory(
            context = applicationContext,
            widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
            kind = intent.getStringExtra("widget_kind") ?: WidgetKinds.TEAM
        )
}

private data class WidgetRow(
    val id: Int,
    val kind: String,
    val title: String,
    val subtitle: String,
    val detail: String,
    val fixture: NextFixture?,
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
            WidgetKinds.PLAYER -> {
                val favorites = FavoriteEntityRepository.getFavoritePlayers(context).associateBy { it.id }
                val cache = SupplementalWidgetRepository.loadPlayerCache(context).associateBy { it.playerId }
                selected.mapNotNull { id ->
                    val player = favorites[id] ?: return@mapNotNull null
                    val cached = cache[id]
                    val fixture = cached?.fixture
                    WidgetRow(
                        id = id,
                        kind = kind,
                        title = player.name,
                        subtitle = cached?.teamName?.ifBlank { player.teamName } ?: player.teamName.ifBlank { "所属チーム不明" },
                        detail = fixture?.let { if (it.hasMatch) (if (it.isHome) "vs " else "@ ") + it.opponent else it.opponent }
                            ?: "次の試合を取得中",
                        fixture = fixture,
                        player = player
                    )
                }
            }
            WidgetKinds.LEAGUE -> {
                val favorites = FavoriteEntityRepository.getFavoriteLeagues(context).associateBy { it.id }
                val cache = SupplementalWidgetRepository.loadLeagueCache(context).associateBy { it.leagueId }
                selected.mapNotNull { id ->
                    val league = favorites[id] ?: return@mapNotNull null
                    val fixture = cache[id]?.fixture
                    WidgetRow(
                        id = id,
                        kind = kind,
                        title = league.name,
                        subtitle = league.country.ifBlank { "League" },
                        detail = fixture?.opponent ?: "次の試合を取得中",
                        fixture = fixture,
                        league = league
                    )
                }
            }
            else -> {
                val favorites = FixtureRepository.getFavoriteTeams(context).associateBy { it.id }
                val cache = FixtureRepository.loadCache(context).fixtures.associateBy { it.teamId }
                selected.mapNotNull { id ->
                    val team = favorites[id] ?: return@mapNotNull null
                    val fixture = cache[id]
                    WidgetRow(
                        id = id,
                        kind = WidgetKinds.TEAM,
                        title = team.name,
                        subtitle = fixture?.competition ?: team.country,
                        detail = fixture?.let { if (it.hasMatch) (if (it.isHome) "vs " else "@ ") + it.opponent else it.opponent }
                            ?: "次の試合を取得中",
                        fixture = fixture,
                        team = team
                    )
                }
            }
        }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(R.id.row_root, "setBackgroundTintList", ColorStateList.valueOf(rowColor))
        } else {
            views.setInt(R.id.row_root, "setBackgroundColor", rowColor)
        }

        listOf(R.id.team_name, R.id.matchup, R.id.countdown).forEach { views.setTextColor(it, primary) }
        listOf(R.id.date_time, R.id.competition, R.id.open_hint, R.id.countdown_label).forEach { views.setTextColor(it, secondary) }

        views.setTextViewText(R.id.team_name, row.title)
        views.setTextViewText(R.id.matchup, row.detail)
        views.setTextViewText(
            R.id.competition,
            when (row.kind) {
                WidgetKinds.PLAYER -> listOf(row.subtitle, fixture?.competition.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
                WidgetKinds.LEAGUE -> row.subtitle
                else -> fixture?.competition ?: row.subtitle
            }
        )
        views.setTextViewText(R.id.date_time, if (fixture?.hasMatch == true) FixtureRepository.formatDate(fixture.utcDate) else "日時未定")

        val logo = when (row.kind) {
            WidgetKinds.PLAYER -> row.player?.let { EntityImageLoader.loadPlayer(context, it) }
            WidgetKinds.LEAGUE -> row.league?.let { EntityImageLoader.loadLeague(context, it) }
            else -> row.team?.let { TeamLogoLoader.load(context, it) }
        }
        if (logo != null) views.setImageViewBitmap(R.id.team_logo, logo)
        else views.setImageViewResource(R.id.team_logo, R.drawable.ic_launcher)

        if (fixture?.hasMatch == true && fixture.utcDate.isNotBlank()) {
            val remaining = FixtureRepository.remainingMillis(fixture.utcDate)
            val base = SystemClock.elapsedRealtime() + remaining
            views.setViewVisibility(R.id.countdown_label, View.VISIBLE)
            views.setViewVisibility(R.id.countdown, View.VISIBLE)
            views.setChronometer(R.id.countdown, base, "%s", true)
            views.setChronometerCountDown(R.id.countdown, true)
        } else {
            views.setViewVisibility(R.id.countdown_label, View.GONE)
            views.setViewVisibility(R.id.countdown, View.GONE)
        }

        val tapTarget = FixtureRepository.getTapTarget(context)
        views.setViewVisibility(R.id.open_hint, if (tapTarget == FixtureRepository.TAP_NONE) View.GONE else View.VISIBLE)
        views.setTextViewText(
            R.id.open_hint,
            when (tapTarget) {
                FixtureRepository.TAP_FOTMOB -> if ((fixture?.fotmobMatchId ?: 0L) > 0L) "FotMob 試合 ↗" else "FotMob ↗"
                FixtureRepository.TAP_SOFASCORE -> if ((fixture?.sofascoreEventId ?: 0L) > 0L) "SofaScore 試合 ↗" else "SofaScore ↗"
                FixtureRepository.TAP_SETTINGS -> "設定 ↗"
                FixtureRepository.TAP_ONEFOOTBALL -> "OneFootball ↗"
                FixtureRepository.TAP_FLASHSCORE -> "Flashscore ↗"
                FixtureRepository.TAP_LIVESCORE -> "LiveScore ↗"
                FixtureRepository.TAP_365SCORES -> "365Scores ↗"
                else -> ""
            }
        )

        views.setOnClickFillInIntent(
            R.id.row_root,
            Intent().apply {
                putExtra("team_id", fixture?.teamId ?: row.id)
                putExtra("team_name", row.title)
                putExtra("fotmob_match_id", fixture?.fotmobMatchId ?: 0L)
                putExtra("fotmob_url", fixture?.fotmobUrl.orEmpty())
                putExtra("sofascore_event_id", fixture?.sofascoreEventId ?: 0L)
                putExtra("sofascore_url", fixture?.sofascoreUrl.orEmpty())
            }
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
