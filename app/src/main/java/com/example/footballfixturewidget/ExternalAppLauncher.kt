package com.example.footballfixturewidget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ExternalAppLauncher {
    private const val FOTMOB_PACKAGE = "com.mobilefootie.wc2010"
    private const val SOFASCORE_PACKAGE = "com.sofascore.results"
    private const val ONEFOOTBALL_PACKAGE = "de.motain.iliga"
    private const val FLASHSCORE_PACKAGE = "eu.livesport.FlashScore_com"
    private const val LIVESCORE_PACKAGE = "com.livescore"
    private const val SCORES365_PACKAGE = "com.scores365"

    fun openForFixture(
        context: Context,
        teamName: String,
        fotmobMatchId: Long,
        fotmobUrl: String,
        sofascoreEventId: Long,
        sofascoreUrl: String
    ) {
        when (FixtureRepository.getTapTarget(context)) {
            FixtureRepository.TAP_FOTMOB -> {
                val urls = buildList {
                    if (fotmobUrl.isNotBlank()) add(fotmobUrl)
                    if (fotmobMatchId > 0L) {
                        // Numeric match route is still a valid exact-match FotMob web route.
                        add("https://www.fotmob.com/match/$fotmobMatchId")
                    }
                }.distinct()

                if (urls.isNotEmpty()) {
                    openExact(context, FOTMOB_PACKAGE, urls, "FotMob")
                } else {
                    Toast.makeText(context, "FotMobの試合IDを取得できませんでした。更新してから再度押してください", Toast.LENGTH_LONG).show()
                    openPackageOrStore(context, FOTMOB_PACKAGE)
                }
            }

            FixtureRepository.TAP_SOFASCORE -> {
                val urls = buildList {
                    // v4 resolver stores Sofascore's real canonical match URL here.
                    if (sofascoreUrl.isNotBlank()) add(sofascoreUrl)
                    if (sofascoreEventId > 0L) {
                        // Current public match pages use an #id fragment; Android App Links
                        // can hand this exact event to the installed SofaScore app.
                        add("https://www.sofascore.com/football/match/match#id:$sofascoreEventId")
                        add("https://www.sofascore.com/football/match/#id:$sofascoreEventId")
                    }
                }.distinct()

                if (urls.isNotEmpty()) {
                    openExact(context, SOFASCORE_PACKAGE, urls, "SofaScore")
                } else {
                    Toast.makeText(context, "SofaScoreの試合IDを取得できませんでした。更新してから再度押してください", Toast.LENGTH_LONG).show()
                    openPackageOrStore(context, SOFASCORE_PACKAGE)
                }
            }

            FixtureRepository.TAP_ONEFOOTBALL -> openPackageOrStore(context, ONEFOOTBALL_PACKAGE)
            FixtureRepository.TAP_FLASHSCORE -> openPackageOrStore(context, FLASHSCORE_PACKAGE)
            FixtureRepository.TAP_LIVESCORE -> openPackageOrStore(context, LIVESCORE_PACKAGE)
            FixtureRepository.TAP_365SCORES -> openPackageOrStore(context, SCORES365_PACKAGE)
            FixtureRepository.TAP_SETTINGS -> {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("selected_team_name", teamName)
                })
            }
            else -> Unit
        }
    }

    /**
     * Try every exact-match URL against the chosen app first. If that app does not
     * register a handler for a URL, fall back to the same exact URL without a package
     * so Android/browser App Links still get a chance to route it correctly.
     */
    private fun openExact(
        context: Context,
        packageName: String,
        urls: List<String>,
        appLabel: String
    ) {
        val appInstalled = context.packageManager.getLaunchIntentForPackage(packageName) != null

        if (appInstalled) {
            for (url in urls) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        setPackage(packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        return
                    }
                } catch (_: ActivityNotFoundException) {
                    // Try the next canonical URL.
                } catch (_: Throwable) {
                    // Try the next canonical URL.
                }
            }
        }

        // Do not fall back straight to the app home screen. Opening the exact web URL
        // is better because Android verified links / the browser can still hand it to
        // the installed app, and otherwise the user still lands on the exact match.
        for (url in urls) {
            if (openWeb(context, url)) {
                if (appInstalled) {
                    Toast.makeText(context, "$appLabel の試合リンクを開きました", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        openPackageOrStore(context, packageName)
    }

    private fun openWeb(context: Context, url: String): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun openPackageOrStore(context: Context, packageName: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return
        }

        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
