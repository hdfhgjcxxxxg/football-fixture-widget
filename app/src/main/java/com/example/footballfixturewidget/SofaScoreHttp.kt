package com.example.footballfixturewidget

import android.os.Build
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * SofaScore transport.
 *
 * Android 14+ uses the platform Chromium HttpEngine, avoiding the Google Play
 * Services Cronet dependency/manifest merge entirely. Older Android versions
 * fall back to HttpURLConnection.
 */
object SofaScoreHttp {
    private const val API_BASE = "https://api.sofascore.com/api/v1"
    private const val WWW_BASE = "https://www.sofascore.com/api/v1"
    private const val UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    fun getAny(urlOrPath: String): Any {
        var last: Throwable? = null
        for (url in candidates(urlOrPath)) {
            try {
                val body = execute(url)
                if (body.isBlank()) throw IllegalStateException("${hostLabel(url)}: 空のレスポンス")
                return JSONTokener(body).nextValue()
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("SofaScoreデータを取得できませんでした")
    }

    private fun candidates(input: String): List<String> {
        val path = when {
            input.startsWith("https://api.sofascore.com/api/v1") -> input.removePrefix("https://api.sofascore.com/api/v1")
            input.startsWith("https://www.sofascore.com/api/v1") -> input.removePrefix("https://www.sofascore.com/api/v1")
            input.startsWith("/api/v1/") -> input.removePrefix("/api/v1")
            input.startsWith("/") -> input
            input.startsWith("http://") || input.startsWith("https://") -> return listOf(input)
            else -> "/$input"
        }
        return listOf("$API_BASE$path", "$WWW_BASE$path").distinct()
    }

    private fun execute(url: String): String {
        if (Build.VERSION.SDK_INT >= 34) {
            return try {
                PlatformHttpEngineClient.get(MatchDayApplication.appContext, url)
            } catch (t: Throwable) {
                // Keep a conventional transport fallback in case the platform
                // provider is unavailable on a particular Android build.
                executeLegacy(url, t)
            }
        }
        return executeLegacy(url, null)
    }

    private fun executeLegacy(url: String, previous: Throwable?): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Referer", "https://www.sofascore.com/")
                setRequestProperty("Origin", "https://www.sofascore.com")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Sec-Fetch-Dest", "empty")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Site", "same-site")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val prefix = previous?.message?.let { "${it}; " }.orEmpty()
                throw IllegalStateException("${prefix}${hostLabel(url)}: HTTP $code")
            }
            return body
        } finally {
            conn?.disconnect()
        }
    }

    private fun hostLabel(url: String): String = when {
        url.contains("api.sofascore.com") -> "api.sofascore.com"
        url.contains("www.sofascore.com") -> "www.sofascore.com"
        else -> url
    }
}
