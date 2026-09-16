package com.example.footballfixturewidget

import android.app.AlarmManager
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val PREFS = "update_prefs"
    private const val KEY_AUTO = "auto_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOADED_FILE = "downloaded_file"
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 9001
    const val ACTION_AUTO_CHECK = "com.example.footballfixturewidget.AUTO_UPDATE_CHECK"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseUrl: String
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isAutoEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)
    fun setAutoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) schedule(context) else cancelSchedule(context)
    }

    fun repository(context: Context): String = context.getString(R.string.update_repo).trim()

    fun checkAsync(context: Context, manual: Boolean = false, callback: ((Result<UpdateInfo?>) -> Unit)? = null) {
        Thread {
            val result = runCatching { checkNow(context) }
            if (!manual && result.getOrNull() != null && isAutoEnabled(context)) {
                result.getOrNull()?.let { enqueueDownload(context, it) }
            }
            callback?.invoke(result)
        }.start()
    }

    private fun checkNow(context: Context): UpdateInfo? {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val repo = repository(context)
        if (repo.isBlank() || repo.contains("__UPDATE_REPO__")) {
            throw IllegalStateException("アップデート用GitHubリポジトリが未設定です")
        }
        val connection = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "FootballFixtureWidget/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 404) throw IllegalStateException("更新リポジトリが非公開、またはReleaseがまだありません")
            if (code !in 200..299) throw IllegalStateException("更新確認 HTTP $code")
            val root = JSONObject(body)
            val tag = root.optString("tag_name")
            val versionCode = Regex("(\\d{6,})$").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return null
            if (versionCode <= BuildConfig.VERSION_CODE) return null
            val assets = root.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url")
                        if (apkUrl.isNotBlank()) break
                    }
                }
            }
            if (apkUrl.isBlank()) throw IllegalStateException("ReleaseにAPKがありません")
            val versionName = tag.substringBeforeLast('-').removePrefix("v").ifBlank { tag }
            return UpdateInfo(versionCode, versionName, apkUrl, root.optString("html_url"))
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueDownload(context: Context, info: UpdateInfo): Long {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("更新保存先を作れません")
        dir.mkdirs()
        val file = File(dir, "FootballFixtureWidget-update-${info.versionCode}.apk")
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Football Fixture Widget 更新")
            .setDescription("v${info.versionName} をダウンロード中")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        prefs(context).edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_DOWNLOADED_FILE, file.absolutePath)
            .apply()
        return id
    }

    fun handleDownloadComplete(context: Context, completedId: Long) {
        if (completedId != prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)) return
        val path = prefs(context).getString(KEY_DOWNLOADED_FILE, null) ?: return
        val file = File(path)
        if (!file.isFile || file.length() < 50_000L) return
        showReadyNotification(context)
    }

    fun downloadedFile(context: Context): File? {
        val path = prefs(context).getString(KEY_DOWNLOADED_FILE, null) ?: return null
        return File(path).takeIf { it.isFile && it.length() > 50_000L }
    }

    private fun showReadyNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "アプリのアップデート", NotificationManager.IMPORTANCE_HIGH))
        }
        val intent = Intent(context, UpdateInstallActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 77, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("アップデートの準備ができました")
            .setContentText("タップしてインストールします")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun schedule(context: Context) {
        if (!isAutoEnabled(context)) return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UpdateCheckReceiver::class.java).apply { action = ACTION_AUTO_CHECK }
        val pi = PendingIntent.getBroadcast(
            context, 78, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val first = System.currentTimeMillis() + 5 * 60 * 1000L
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, 6 * 60 * 60 * 1000L, pi)
    }

    fun cancelSchedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UpdateCheckReceiver::class.java).apply { action = ACTION_AUTO_CHECK }
        val pi = PendingIntent.getBroadcast(
            context, 78, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pi)
    }
}
