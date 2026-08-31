package com.example.footballfixturewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

/** Schedules lightweight refreshes at kickoff / half-time-ish / full-time-ish boundaries. */
object MatchPhaseScheduler {
    private const val ACTION = "com.example.footballfixturewidget.MATCH_PHASE_REFRESH"

    fun scheduleFromState(context: Context, state: WidgetState) = scheduleFixtures(context, state.fixtures)

    fun scheduleFixtures(context: Context, fixtures: List<NextFixture>) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        fixtures.filter { it.hasMatch && it.utcDate.isNotBlank() }.distinctBy { it.utcDate + "|" + it.opponent }.forEach { f ->
            val start = runCatching { Instant.parse(f.utcDate).toEpochMilli() }.getOrNull() ?: return@forEach
            // Refresh before kickoff so an officially announced lineup replaces the
            // "未発表" state, then refresh through normal/full/extra-time boundaries so
            // a stale LIVE flag is cleared even if the provider update at 90' is late.
            listOf(
                start - 75L * 60_000L,
                start - 60L * 60_000L,
                start - 45L * 60_000L,
                start,
                start + 2L * 60_000L,
                start + 46L * 60_000L,
                start + 62L * 60_000L,
                start + 106L * 60_000L,
                start + 120L * 60_000L,
                start + 150L * 60_000L,
                start + 210L * 60_000L
            ).forEachIndexed { index, whenMs ->
                if (whenMs <= now) return@forEachIndexed
                val requestCode = (f.teamId * 31 + index * 997 + (start / 60_000L).toInt()) and 0x7fffffff
                val pi = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    Intent(context, MatchPhaseReceiver::class.java).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
        }
    }
}

class MatchPhaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.sendBroadcast(Intent(context, FixtureWidgetProvider::class.java).apply { action = FixtureWidgetProvider.ACTION_REFRESH })
        context.sendBroadcast(Intent(context, PlayerWidgetProvider::class.java).apply { action = PlayerWidgetProvider.ACTION_REFRESH })
        context.sendBroadcast(Intent(context, LeagueWidgetProvider::class.java).apply { action = LeagueWidgetProvider.ACTION_REFRESH })
    }
}
