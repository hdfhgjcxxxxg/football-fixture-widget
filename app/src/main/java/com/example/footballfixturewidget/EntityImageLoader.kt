package com.example.footballfixturewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object EntityImageLoader {
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * v11: first try every ID already known for the player, then cross-resolve the
     * same player on the other provider and retry its portrait. This fixes many
     * cases where one provider has no headshot but the other does.
     */
    fun loadPlayer(context: Context, player: FavoritePlayer): Bitmap? {
        val direct = load(context, "player_${player.id}", FavoriteEntityRepository.playerImageUrls(player))
        if (direct != null) return direct

        val resolved = runCatching {
            FavoriteEntityRepository.searchPlayers(player.name)
                .filter { candidate ->
                    candidate.name.equals(player.name, true) &&
                        (player.teamName.isBlank() || candidate.teamName.isBlank() || candidate.teamName.equals(player.teamName, true))
                }
                .firstOrNull { it.sofascoreId > 0 || it.fotmobId > 0 }
        }.getOrNull()
        return if (resolved != null) {
            load(context, "player_${player.id}_cross", FavoriteEntityRepository.playerImageUrls(resolved))
        } else null
    }

    fun loadLeague(context: Context, league: FavoriteLeague): Bitmap? =
        load(context, "league_${league.id}", FavoriteEntityRepository.leagueImageUrls(league))

    private fun load(context: Context, key: String, urls: List<String>): Bitmap? {
        if (urls.isEmpty()) return null
        val dir = File(context.cacheDir, "entity_images").apply { mkdirs() }
        val file = File(dir, "$key.png")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        for (url in urls.distinct()) {
            val bitmap = runCatching { download(url) }.getOrNull() ?: continue
            runCatching { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            return bitmap
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun download(url: String): Bitmap? {
        val sofa = url.contains("sofascore", true)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 6500
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            setRequestProperty("Referer", if (sofa) "https://www.sofascore.com/" else "https://www.fotmob.com/")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType.orEmpty()
            if (contentType.contains("svg", true)) return null
            return connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }
}
