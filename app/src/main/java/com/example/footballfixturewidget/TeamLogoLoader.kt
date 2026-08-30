package com.example.footballfixturewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object TeamLogoLoader {
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    fun load(context: Context, teamId: Int): Bitmap? {
        if (teamId >= 0) return null
        val id = -teamId
        return loadFromUrls(context, "ss_$id", listOf("https://img.sofascore.com/api/v1/team/$id/image"))
    }

    fun load(context: Context, team: FavoriteTeam): Bitmap? {
        val mode = DataSourceManager.getMode(context)
        var resolved = team
        if ((mode != DataSourceManager.SOFASCORE && resolved.fotmobId <= 0) ||
            (mode != DataSourceManager.FOTMOB && resolved.sofascoreId <= 0)) {
            resolved = runCatching {
                FixtureRepository.searchTeams(team.name).firstOrNull {
                    FixtureRepository.normalizeTeamName(it.name) == FixtureRepository.normalizeTeamName(team.name)
                } ?: FixtureRepository.searchTeams(team.name).firstOrNull()
            }.getOrNull()?.let { candidate ->
                FavoriteTeam(
                    id = if (team.id != 0) team.id else candidate.id,
                    name = team.name.ifBlank { candidate.name },
                    fotmobId = if (team.fotmobId > 0) team.fotmobId else candidate.fotmobId,
                    sofascoreId = if (team.sofascoreId > 0) team.sofascoreId else candidate.sofascoreId,
                    country = team.country.ifBlank { candidate.country }
                )
            } ?: team
        }
        val urls = buildList {
            if (mode != DataSourceManager.SOFASCORE && resolved.fotmobId > 0)
                add("https://images.fotmob.com/image_resources/logo/teamlogo/${resolved.fotmobId}.png")
            if (mode != DataSourceManager.FOTMOB && resolved.sofascoreId > 0)
                add("https://img.sofascore.com/api/v1/team/${resolved.sofascoreId}/image")
        }
        val key = "team_${resolved.fotmobId}_${resolved.sofascoreId}_${mode}"
        return loadFromUrls(context, key, urls)
    }

    private fun loadFromUrls(context: Context, cacheKey: String, urls: List<String>): Bitmap? {
        if (urls.isEmpty()) return null
        val dir = File(context.cacheDir, "team_logos").apply { mkdirs() }
        val file = File(dir, "$cacheKey.png")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        for (url in urls) {
            val bitmap = runCatching { download(url) }.getOrNull() ?: continue
            runCatching { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            return bitmap
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            if (url.contains("sofascore", true)) {
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Sec-Fetch-Site", "same-origin")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Dest", "empty")
            }
            setRequestProperty("Referer", "https://www.sofascore.com/")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }
}
