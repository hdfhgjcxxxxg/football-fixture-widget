package com.example.footballfixturewidget

import android.content.Context

/**
 * Controls which upstream football data source is used by the app.
 *
 * AUTO_BOTH tries both providers and merges compatible entities. Runtime data refreshes
 * prefer SofaScore when it works, then fall back to FotMob. FOTMOB and SOFASCORE are
 * strict modes and avoid the other provider except for optional deep-link resolution.
 */
object DataSourceManager {
    private const val PREFS = "fixture_prefs"
    private const val KEY_MODE = "data_source_mode_v12"

    const val AUTO_BOTH = "both"
    const val FOTMOB = "fotmob"
    const val SOFASCORE = "sofascore"

    fun getMode(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AUTO_BOTH)
        return when (saved) {
            FOTMOB, SOFASCORE, AUTO_BOTH -> saved
            else -> AUTO_BOTH
        }
    }

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            FOTMOB, SOFASCORE -> mode
            else -> AUTO_BOTH
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, normalized).apply()
    }

    fun label(mode: String): String = when (mode) {
        FOTMOB -> "FotMob"
        SOFASCORE -> "SofaScore"
        else -> "結合"
    }

    fun label(context: Context): String = label(getMode(context))

    fun allowsFotMob(context: Context): Boolean = getMode(context) != SOFASCORE
    fun allowsSofaScore(context: Context): Boolean = getMode(context) != FOTMOB
}
