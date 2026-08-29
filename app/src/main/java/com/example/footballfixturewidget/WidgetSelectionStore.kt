package com.example.footballfixturewidget

import android.appwidget.AppWidgetManager
import android.content.Context
import org.json.JSONArray

object WidgetKinds {
    const val TEAM = "team"
    const val PLAYER = "player"
    const val LEAGUE = "league"
}

object WidgetSelectionStore {
    private const val PREFS = "widget_instance_prefs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(widgetId: Int, kind: String) = "selection_${kind}_$widgetId"

    fun getSelectedIds(context: Context, widgetId: Int, kind: String): List<Int> {
        val raw = prefs(context).getString(key(widgetId, kind), null)
        if (raw == null) return defaultIds(context, kind)
        val available = defaultIds(context, kind).toSet()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val id = a.optInt(i)
                    if (id != 0 && id in available) add(id)
                }
            }.distinct()
        }.getOrDefault(defaultIds(context, kind))
    }

    fun saveSelectedIds(context: Context, widgetId: Int, kind: String, ids: Collection<Int>) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val a = JSONArray()
        ids.filter { it != 0 }.distinct().take(10).forEach { a.put(it) }
        prefs(context).edit().putString(key(widgetId, kind), a.toString()).apply()
    }

    fun hasExplicitSelection(context: Context, widgetId: Int, kind: String): Boolean =
        prefs(context).contains(key(widgetId, kind))

    fun deleteWidget(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove(key(widgetId, WidgetKinds.TEAM))
            .remove(key(widgetId, WidgetKinds.PLAYER))
            .remove(key(widgetId, WidgetKinds.LEAGUE))
            .apply()
    }

    fun availableCount(context: Context, kind: String): Int = defaultIds(context, kind).size

    private fun defaultIds(context: Context, kind: String): List<Int> = when (kind) {
        WidgetKinds.PLAYER -> FavoriteEntityRepository.getFavoritePlayers(context).map { it.id }
        WidgetKinds.LEAGUE -> FavoriteEntityRepository.getFavoriteLeagues(context).map { it.id }
        else -> FixtureRepository.getFavoriteTeams(context).map { it.id }
    }.take(10)
}
