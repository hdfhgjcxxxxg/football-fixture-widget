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
        if (teamId <= 0) return null
        val dir = File(context.cacheDir, "team_logos").apply { mkdirs() }
        val file = File(dir, "$teamId.png")

        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }

        val urls = listOf(
            "https://images.fotmob.com/image_resources/logo/teamlogo/$teamId.png",
            "https://images.fotmob.com/image_resources/logo/teamlogo/${teamId}_small.png"
        )
        for (url in urls) {
            val bitmap = runCatching { download(url) }.getOrNull() ?: continue
            runCatching {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return bitmap
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 4500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.fotmob.com/")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }
}
