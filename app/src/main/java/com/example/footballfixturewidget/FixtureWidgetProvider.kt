package com.example.footballfixturewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews

private object WidgetRenderer {
    fun renderAll(
        context: Context,
        providerClass: Class<out AppWidgetProvider>,
        kind: String,
        statusOverride: String? = null
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, providerClass)
        val ids = manager.getAppWidgetIds(component)
        ids.forEach { widgetId -> renderOne(context, manager, widgetId, kind, statusOverride) }
        if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.fixture_list)
    }

    private fun renderOne(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        kind: String,
        statusOverride: String?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_fixture)
        val color = FixtureRepository.getWidgetColor(context)
        val primaryText = FixtureRepository.preferredTextColor(color)
        val secondaryText = FixtureRepository.secondaryTextColor(color)
        val selected = WidgetSelectionStore.getSelectedIds(context, widgetId, kind)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(color))
            views.setColorStateList(
                R.id.refresh_button,
                "setBackgroundTintList",
                ColorStateList.valueOf(FixtureRepository.accentRowColor(color))
            )
        } else {
            views.setInt(R.id.widget_root, "setBackgroundColor", color)
        }

        views.setTextColor(R.id.widget_title, primaryText)
        views.setTextColor(R.id.widget_subtitle, secondaryText)
        views.setTextColor(R.id.refresh_button, primaryText)
        views.setTextColor(R.id.status_text, secondaryText)
        views.setTextColor(R.id.empty_text, secondaryText)

        val title = when (kind) {
            WidgetKinds.PLAYER -> "FAVORITE PLAYERS"
            WidgetKinds.LEAGUE -> "FAVORITE LEAGUES"
            else -> "NEXT MATCHES"
        }
        val itemLabel = when (kind) {
            WidgetKinds.PLAYER -> "選手"
            WidgetKinds.LEAGUE -> "リーグ"
            else -> "チーム"
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, "$itemLabel ${selected.size}件 • 上限なし • タップで編集")

        val configClass = when (kind) {
            WidgetKinds.PLAYER -> PlayerWidgetConfigActivity::class.java
            WidgetKinds.LEAGUE -> LeagueWidgetConfigActivity::class.java
            else -> TeamWidgetConfigActivity::class.java
        }
        val configIntent = Intent(context, configClass).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("edit_existing", true)
        }
        val configPending = PendingIntent.getActivity(
            context,
            10000 + widgetId + kind.hashCode(),
            configIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_title, configPending)
        views.setOnClickPendingIntent(R.id.widget_subtitle, configPending)

        val providerClass: Class<out AppWidgetProvider> = when (kind) {
            WidgetKinds.PLAYER -> PlayerWidgetProvider::class.java
            WidgetKinds.LEAGUE -> LeagueWidgetProvider::class.java
            else -> FixtureWidgetProvider::class.java
        }
        val refreshAction = when (kind) {
            WidgetKinds.PLAYER -> PlayerWidgetProvider.ACTION_REFRESH
            WidgetKinds.LEAGUE -> LeagueWidgetProvider.ACTION_REFRESH
            else -> FixtureWidgetProvider.ACTION_REFRESH
        }
        val refreshIntent = Intent(context, providerClass).apply { action = refreshAction }
        val refreshPending = PendingIntent.getBroadcast(
            context,
            20000 + widgetId + kind.hashCode(),
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.refresh_button, refreshPending)

        val serviceIntent = Intent(context, FixtureRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("widget_kind", kind)
            data = Uri.parse("ffw://widget/$kind/$widgetId")
        }
        views.setRemoteAdapter(R.id.fixture_list, serviceIntent)
        views.setEmptyView(R.id.fixture_list, R.id.empty_text)
        views.setTextViewText(
            R.id.empty_text,
            if (selected.isEmpty()) "このウィジェットに表示する${itemLabel}を選んでください" else "日程を取得しています…"
        )

        val clickTemplate = PendingIntent.getActivity(
            context,
            30000 + widgetId + kind.hashCode(),
            Intent(context, MatchLinkRouterActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.fixture_list, clickTemplate)

        val updatedAt = when (kind) {
            WidgetKinds.PLAYER -> SupplementalWidgetRepository.playerUpdatedAt(context)
            WidgetKinds.LEAGUE -> SupplementalWidgetRepository.leagueUpdatedAt(context)
            else -> FixtureRepository.loadCache(context).updatedAt
        }
        val status = statusOverride ?: when {
            selected.isEmpty() -> "${itemLabel}未選択"
            updatedAt <= 0L -> "未更新"
            else -> "更新 ${FixtureRepository.formatUpdatedAt(updatedAt)}"
        }
        views.setTextViewText(R.id.status_text, "FotMob / SofaScore • $status")
        manager.updateAppWidget(widgetId, views)
    }
}

abstract class BaseFavoriteWidgetProvider : AppWidgetProvider() {
    abstract val kind: String
    abstract val refreshAction: String

    private fun providerClass(): Class<out AppWidgetProvider> = when (kind) {
        WidgetKinds.PLAYER -> PlayerWidgetProvider::class.java
        WidgetKinds.LEAGUE -> LeagueWidgetProvider::class.java
        else -> FixtureWidgetProvider::class.java
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.renderAll(context, providerClass(), kind)
        context.sendBroadcast(Intent(context, providerClass()).apply { action = refreshAction })
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == refreshAction) {
            val pending = goAsync()
            WidgetRenderer.renderAll(context, providerClass(), kind, "更新中…")
            Thread {
                try {
                    when (kind) {
                        WidgetKinds.PLAYER -> SupplementalWidgetRepository.refreshPlayers(context.applicationContext)
                        WidgetKinds.LEAGUE -> SupplementalWidgetRepository.refreshLeagues(context.applicationContext)
                        else -> FixtureRepository.fetchAll(context.applicationContext)
                    }
                    WidgetRenderer.renderAll(context.applicationContext, providerClass(), kind)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetSelectionStore.deleteWidget(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}

class FixtureWidgetProvider : BaseFavoriteWidgetProvider() {
    override val kind = WidgetKinds.TEAM
    override val refreshAction = ACTION_REFRESH

    companion object {
        const val ACTION_REFRESH = "com.example.footballfixturewidget.ACTION_REFRESH_TEAM"
        fun renderAll(context: Context, statusOverride: String? = null) =
            WidgetRenderer.renderAll(context, FixtureWidgetProvider::class.java, WidgetKinds.TEAM, statusOverride)
    }
}

class PlayerWidgetProvider : BaseFavoriteWidgetProvider() {
    override val kind = WidgetKinds.PLAYER
    override val refreshAction = ACTION_REFRESH

    companion object {
        const val ACTION_REFRESH = "com.example.footballfixturewidget.ACTION_REFRESH_PLAYER"
        fun renderAll(context: Context, statusOverride: String? = null) =
            WidgetRenderer.renderAll(context, PlayerWidgetProvider::class.java, WidgetKinds.PLAYER, statusOverride)
    }
}

class LeagueWidgetProvider : BaseFavoriteWidgetProvider() {
    override val kind = WidgetKinds.LEAGUE
    override val refreshAction = ACTION_REFRESH

    companion object {
        const val ACTION_REFRESH = "com.example.footballfixturewidget.ACTION_REFRESH_LEAGUE"
        fun renderAll(context: Context, statusOverride: String? = null) =
            WidgetRenderer.renderAll(context, LeagueWidgetProvider::class.java, WidgetKinds.LEAGUE, statusOverride)
    }
}
