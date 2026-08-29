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
        if (teamId == 0) return null
        val cacheKey = if (teamId > 0) "fm_$teamId" else "ss_${-teamId}"
        val urls = if (teamId > 0) {
            listOf(
                "https://images.fotmob.com/image_resources/logo/teamlogo/$teamId.png",
                "https://images.fotmob.com/image_resources/logo/teamlogo/${teamId}_small.png"
            )
        } else {
            val id = -teamId
            listOf("https://img.sofascore.com/api/v1/team/$id/image")
        }
        return loadFromUrls(context, cacheKey, urls)
    }

    fun load(context: Context, team: FavoriteTeam): Bitmap? {
        val urls = buildList {
            if (team.fotmobId > 0) {
                add("https://images.fotmob.com/image_resources/logo/teamlogo/${team.fotmobId}.png")
                add("https://images.fotmob.com/image_resources/logo/teamlogo/${team.fotmobId}_small.png")
            }
            if (team.sofascoreId > 0) add("https://img.sofascore.com/api/v1/team/${team.sofascoreId}/image")
        }
        return loadFromUrls(context, "fav_${team.id}", urls)
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
        val sofa = url.contains("sofascore", true)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            setRequestProperty("Referer", if (sofa) "https://www.sofascore.com/" else "https://www.fotmob.com/")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }
}
