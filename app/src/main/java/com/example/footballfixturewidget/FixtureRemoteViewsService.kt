package com.example.footballfixturewidget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class FixtureRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory = FixtureFactory(applicationContext)
}

private class FixtureFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var fixtures: List<NextFixture> = emptyList()

    override fun onCreate() {
        fixtures = FixtureRepository.loadCache(context).fixtures
    }

    override fun onDataSetChanged() {
        val cached = FixtureRepository.loadCache(context).fixtures.associateBy { it.teamId }
        fixtures = FixtureRepository.getFavoriteTeams(context).mapNotNull { cached[it.id] }
    }

    override fun onDestroy() = Unit

    override fun getCount(): Int = fixtures.size

    override fun getViewAt(position: Int): RemoteViews? {
        val item = fixtures.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_team_row)
        val background = FixtureRepository.getWidgetColor(context)
        val primary = FixtureRepository.preferredTextColor(background)
        val secondary = FixtureRepository.secondaryTextColor(background)
        val rowColor = FixtureRepository.rowColor(background)

        views.setInt(R.id.row_root, "setBackgroundColor", rowColor)
        views.setTextColor(R.id.team_name, primary)
        views.setTextColor(R.id.matchup, primary)
        views.setTextColor(R.id.date_time, secondary)
        views.setTextColor(R.id.competition, secondary)

        views.setTextViewText(R.id.team_name, item.teamName)
        views.setTextViewText(R.id.date_time, if (item.hasMatch) FixtureRepository.formatDate(item.utcDate) else "日時未定")
        views.setTextViewText(
            R.id.matchup,
            if (item.hasMatch) (if (item.isHome) "vs " else "@ ") + item.opponent else item.opponent
        )
        views.setTextViewText(R.id.competition, item.competition)

        val tapTarget = FixtureRepository.getTapTarget(context)
        views.setViewVisibility(R.id.open_hint, if (tapTarget == FixtureRepository.TAP_NONE) View.GONE else View.VISIBLE)
        views.setTextColor(R.id.open_hint, secondary)
        views.setTextViewText(
            R.id.open_hint,
            when (tapTarget) {
                FixtureRepository.TAP_FOTMOB -> if (item.fotmobMatchId > 0) "FotMob 試合 ↗" else "FotMob ↗"
                FixtureRepository.TAP_SOFASCORE -> if (item.sofascoreEventId > 0) "SofaScore 試合 ↗" else "SofaScore ↗"
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
                putExtra("team_id", item.teamId)
                putExtra("team_name", item.teamName)
                putExtra("fotmob_match_id", item.fotmobMatchId)
                putExtra("fotmob_url", item.fotmobUrl)
                putExtra("sofascore_event_id", item.sofascoreEventId)
                putExtra("sofascore_url", item.sofascoreUrl)
            }
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = fixtures.getOrNull(position)?.teamId?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
