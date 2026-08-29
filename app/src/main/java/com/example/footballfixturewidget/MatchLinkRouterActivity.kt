package com.example.footballfixturewidget

import android.app.Activity
import android.os.Bundle

/**
 * Tiny transparent activity launched directly by the widget user's tap.
 *
 * Launching the external football app from an Activity that was itself opened by
 * the user is much more reliable on modern Android than doing it from a
 * BroadcastReceiver (background-activity launch restrictions can block that path).
 */
class MatchLinkRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ExternalAppLauncher.openForFixture(
            context = this,
            teamName = intent.getStringExtra("team_name").orEmpty(),
            fotmobMatchId = intent.getLongExtra("fotmob_match_id", 0L),
            fotmobUrl = intent.getStringExtra("fotmob_url").orEmpty(),
            sofascoreEventId = intent.getLongExtra("sofascore_event_id", 0L),
            sofascoreUrl = intent.getStringExtra("sofascore_url").orEmpty()
        )

        finish()
        overridePendingTransition(0, 0)
    }
}
