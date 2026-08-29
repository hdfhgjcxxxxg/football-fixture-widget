package com.example.footballfixturewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class FixtureWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.footballfixturewidget.ACTION_REFRESH"
        const val ACTION_OPEN_TEAM = "com.example.footballfixturewidget.ACTION_OPEN_TEAM"

        fun renderAll(
            context: Context,
            state: WidgetState = FixtureRepository.loadCache(context),
            statusOverride: String? = null
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, FixtureWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { widgetId -> renderOne(context, manager, widgetId, state, statusOverride) }
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.fixture_list)
        }

        private fun renderOne(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            state: WidgetState,
            statusOverride: String?
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_fixture)
            val color = FixtureRepository.getWidgetColor(context)
            val primaryText = FixtureRepository.preferredTextColor(color)
            val secondaryText = FixtureRepository.secondaryTextColor(color)
            val favoriteCount = FixtureRepository.getFavoriteTeams(context).size

            views.setInt(R.id.widget_root, "setBackgroundColor", color)
            views.setTextColor(R.id.widget_title, primaryText)
            views.setTextColor(R.id.refresh_button, primaryText)
            views.setTextColor(R.id.status_text, secondaryText)
            views.setTextViewText(R.id.widget_title, "お気に入りの次の試合  $favoriteCount/10")

            val openSettings = Intent(context, MainActivity::class.java)
            val openSettingsPending = PendingIntent.getActivity(
                context,
                1000 + widgetId,
                openSettings,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openSettingsPending)

            val refreshIntent = Intent(context, FixtureWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                2000 + widgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.refresh_button, refreshPending)

            val serviceIntent = Intent(context, FixtureRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.fixture_list, serviceIntent)
            views.setEmptyView(R.id.fixture_list, R.id.empty_text)
            views.setTextColor(R.id.empty_text, secondaryText)

            // Launch a user-visible Activity directly from the widget tap.
            // This avoids Android background-activity-launch restrictions that can
            // block BroadcastReceiver -> FotMob/SofaScore navigation.
            val clickTemplate = PendingIntent.getActivity(
                context,
                3000 + widgetId,
                Intent(context, MatchLinkRouterActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.fixture_list, clickTemplate)

            val tokenMissing = FixtureRepository.getToken(context).isBlank()
            val statusCore = statusOverride ?: when {
                tokenMissing -> "APIキーを設定してください"
                favoriteCount == 0 -> "お気に入りチームを選択してください"
                state.error != null -> "一部更新失敗: ${state.error}"
                state.fixtures.isEmpty() -> "次の試合が見つかりません"
                else -> "更新 ${FixtureRepository.formatUpdatedAt(state.updatedAt)}"
            }
            views.setTextViewText(R.id.status_text, "football-data.org • $statusCore")
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context)
        context.sendBroadcast(Intent(context, FixtureWidgetProvider::class.java).apply { action = ACTION_REFRESH })
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                val pendingResult = goAsync()
                renderAll(context, statusOverride = "更新中…")
                Thread {
                    try {
                        val state = try {
                            FixtureRepository.fetchAll(context.applicationContext)
                        } catch (t: Throwable) {
                            FixtureRepository.loadCache(context).copy(error = t.message ?: t.javaClass.simpleName)
                        }
                        renderAll(context.applicationContext, state)
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
                return
            }

            ACTION_OPEN_TEAM -> {
                val teamName = intent.getStringExtra("team_name").orEmpty()
                ExternalAppLauncher.openForFixture(
                    context = context,
                    teamName = teamName,
                    fotmobMatchId = intent.getLongExtra("fotmob_match_id", 0L),
                    fotmobUrl = intent.getStringExtra("fotmob_url").orEmpty(),
                    sofascoreEventId = intent.getLongExtra("sofascore_event_id", 0L),
                    sofascoreUrl = intent.getStringExtra("sofascore_url").orEmpty()
                )
                return
            }
        }
        super.onReceive(context, intent)
    }
}
