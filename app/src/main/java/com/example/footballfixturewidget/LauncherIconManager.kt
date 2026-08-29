package com.example.footballfixturewidget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIconManager {
    private const val PREFS = "launcher_icon_prefs"
    private const val KEY_STYLE = "style"
    const val STYLE_COUNT = 4

    private fun alias(context: Context, style: Int): ComponentName =
        ComponentName(context, "${context.packageName}.LauncherAlias$style")

    fun currentStyle(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_STYLE, 1)
            .coerceIn(1, STYLE_COUNT)

    fun selectStyle(context: Context, style: Int) {
        val selected = style.coerceIn(1, STYLE_COUNT)
        val pm = context.packageManager
        // Enable the new icon first so the launcher always has one valid entry.
        pm.setComponentEnabledSetting(
            alias(context, selected),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        for (i in 1..STYLE_COUNT) {
            if (i == selected) continue
            pm.setComponentEnabledSetting(
                alias(context, i),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_STYLE, selected).apply()
    }

    fun iconRes(style: Int): Int = when (style) {
        2 -> R.drawable.launcher_style_2
        3 -> R.drawable.launcher_style_3
        4 -> R.drawable.launcher_style_4
        else -> R.drawable.launcher_style_1
    }
}
