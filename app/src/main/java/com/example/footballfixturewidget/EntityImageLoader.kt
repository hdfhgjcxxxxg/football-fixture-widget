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

    /** SofaScore画像を優先し、IDが不足している旧お気に入りは名前検索で自動解決する。 */
    fun loadPlayer(context: Context, player: FavoritePlayer): Bitmap? {
        val direct = loadPlayerAvatar(context, "player_avatar_v124_${player.id}", FavoriteEntityRepository.playerImageUrls(player))
        if (direct != null) return direct

        val resolved = runCatching {
            FavoriteEntityRepository.searchPlayers(player.name)
                .filter { candidate ->
                    candidate.name.equals(player.name, true) &&
                        (player.teamName.isBlank() || candidate.teamName.isBlank() || candidate.teamName.equals(player.teamName, true))
                }
                .firstOrNull()
        }.getOrNull()
        return if (resolved != null) {
            loadPlayerAvatar(context, "player_avatar_v124_${player.id}_cross", FavoriteEntityRepository.playerImageUrls(resolved))
        } else null
    }


    private fun loadPlayerAvatar(context: Context, key: String, urls: List<String>): Bitmap? {
        val raw = load(context, key, urls) ?: return null
        return runCatching { AvatarProcessor.preparePlayerAvatar(raw) }.getOrDefault(raw)
    }

    fun loadLeague(context: Context, league: FavoriteLeague): Bitmap? {
        val n = league.name.lowercase()
        val isMensUcl =
            n.contains("champions league") &&
            !n.contains("women") &&
            !n.contains("女子") &&
            !n.contains("youth")

        if (isMensUcl) {
            // UEFA Champions Leagueを別大会IDから拾わないように完全固定。
            // FotMob公式リーグID=42を優先し、SofaScore unique tournament ID=7を予備にする。
            return load(
                context,
                "league_ucl_official_v1213",
                listOf(
                    "https://images.fotmob.com/image_resources/logo/leaguelogo/42.png",
                    "https://img.sofascore.com/api/v1/unique-tournament/7/image"
                )
            )
        }

        var urls = FavoriteEntityRepository.leagueImageUrls(league)
        if (urls.isEmpty() && DataSourceManager.getMode(context) != DataSourceManager.FOTMOB) {
            val sofaId = runCatching { FavoriteEntityRepository.resolveSofaLeagueId(league) }.getOrDefault(0)
            if (sofaId > 0) urls = listOf("https://img.sofascore.com/api/v1/unique-tournament/$sofaId/image")
        }
        return load(context, "league_v1213_${league.id}_${DataSourceManager.getMode(context)}", urls)
    }

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
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 6500
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
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
            val contentType = connection.contentType.orEmpty()
            if (contentType.contains("svg", true)) return null
            return connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }
}
