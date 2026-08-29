package com.example.footballfixturewidget

import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(R.id.row_root, "setBackgroundTintList", ColorStateList.valueOf(rowColor))
        } else {
            views.setInt(R.id.row_root, "setBackgroundColor", rowColor)
        }

        listOf(R.id.team_name, R.id.matchup, R.id.countdown).forEach { views.setTextColor(it, primary) }
        listOf(R.id.date_time, R.id.competition, R.id.open_hint, R.id.countdown_label).forEach { views.setTextColor(it, secondary) }

        views.setTextViewText(R.id.team_name, item.teamName)
        views.setTextViewText(
            R.id.matchup,
            if (item.hasMatch) (if (item.isHome) "vs " else "@ ") + item.opponent else item.opponent
        )
        views.setTextViewText(R.id.competition, item.competition)
        views.setTextViewText(R.id.date_time, if (item.hasMatch) FixtureRepository.formatDate(item.utcDate) else "日時未定")

        val logo = TeamLogoLoader.load(context, item.teamId)
        if (logo != null) {
            views.setImageViewBitmap(R.id.team_logo, logo)
        } else {
            views.setImageViewResource(R.id.team_logo, R.drawable.ic_launcher)
        }

        if (item.hasMatch && item.utcDate.isNotBlank()) {
            val remaining = FixtureRepository.remainingMillis(item.utcDate)
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
