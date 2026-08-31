package com.example.footballfixturewidget

import android.app.AlarmManager
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val PREFS = "update_prefs"
    private const val KEY_AUTO = "auto_update"
    private const val KEY_LAST_ATTEMPT = "last_attempt"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val KEY_DOWNLOADED_FILE = "downloaded_file"
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 9001
    private const val ERROR_NOTIFICATION_ID = 9002
    private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private const val FAILED_RETRY_MS = 30L * 60L * 1000L
    const val ACTION_AUTO_CHECK = "com.example.footballfixturewidget.AUTO_UPDATE_CHECK"

    private val downloadLock = Any()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseUrl: String
    )

    private data class DownloadState(val status: Int, val reason: Int)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) schedule(context) else cancelSchedule(context)
    }

    fun repository(context: Context): String = context.getString(R.string.update_repo).trim()

    fun isAutoCheckDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        val lastSuccess = p.getLong(KEY_LAST_SUCCESS, 0L)
        val lastAttempt = p.getLong(KEY_LAST_ATTEMPT, 0L)
        if (lastSuccess > 0L && now - lastSuccess < CHECK_INTERVAL_MS) return false
        if (lastAttempt > 0L && now - lastAttempt < FAILED_RETRY_MS) return false
        return true
    }

    fun checkAsync(context: Context, manual: Boolean = false, callback: ((Result<UpdateInfo?>) -> Unit)? = null) {
        val appContext = context.applicationContext
        Thread {
            val result = if (!manual && !isAutoCheckDue(appContext)) {
                Result.success(null)
            } else {
                runCatching { checkNow(appContext) }
            }
            if (!manual && result.getOrNull() != null && isAutoEnabled(appContext)) {
                result.getOrNull()?.let { info -> runCatching { enqueueDownload(appContext, info) } }
            }
            callback?.invoke(result)
        }.start()
    }

    private fun checkNow(context: Context): UpdateInfo? {
        prefs(context).edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
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
                ?: throw IllegalStateException("ReleaseタグからversionCodeを取得できません: $tag")
            if (versionCode <= BuildConfig.VERSION_CODE) {
                prefs(context).edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply()
                return null
            }
            val assets = root.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.equals("FootballFixtureWidget.apk", true) || name.endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url")
                        if (apkUrl.isNotBlank()) break
                    }
                }
            }
            if (apkUrl.isBlank()) throw IllegalStateException("ReleaseにAPKがありません")
            val versionName = tag.substringBeforeLast('-').removePrefix("v").ifBlank { tag }
            prefs(context).edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply()
            return UpdateInfo(versionCode, versionName, apkUrl, root.optString("html_url"))
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueDownload(context: Context, info: UpdateInfo): Long = synchronized(downloadLock) {
        val appContext = context.applicationContext
        val p = prefs(appContext)
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val previousVersion = p.getInt(KEY_DOWNLOAD_VERSION, -1)
        val previousId = p.getLong(KEY_DOWNLOAD_ID, -1L)
        val previousPath = p.getString(KEY_DOWNLOADED_FILE, null)
        val previousFile = previousPath?.let(::File)

        if (previousVersion == info.versionCode) {
            if (previousFile != null && validateDownloadedApk(appContext, previousFile) == null) {
                showReadyNotification(appContext)
                return@synchronized previousId
            }
            if (previousId > 0L) {
                val state = queryDownloadState(dm, previousId)
                if (state?.status == DownloadManager.STATUS_PENDING ||
                    state?.status == DownloadManager.STATUS_RUNNING ||
                    state?.status == DownloadManager.STATUS_PAUSED
                ) {
                    return@synchronized previousId
                }
            }
        }

        previousFile?.takeIf { it.isFile }?.delete()
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("更新保存先を作れません")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("更新保存先を作成できません")
        dir.listFiles()?.filter { it.name.startsWith("FootballFixtureWidget-update-") && it.name.endsWith(".apk") }
            ?.forEach { it.delete() }

        val file = File(dir, "FootballFixtureWidget-update-${info.versionCode}.apk")
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Football Fixture Widget 更新")
            .setDescription("v${info.versionName} をダウンロード中")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, file.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val id = dm.enqueue(request)
        p.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putInt(KEY_DOWNLOAD_VERSION, info.versionCode)
            .putString(KEY_DOWNLOADED_FILE, file.absolutePath)
            .apply()
        id
    }

    fun handleDownloadComplete(context: Context, completedId: Long) {
        val appContext = context.applicationContext
        if (completedId != prefs(appContext).getLong(KEY_DOWNLOAD_ID, -1L)) return
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val state = queryDownloadState(dm, completedId)
        if (state?.status != DownloadManager.STATUS_SUCCESSFUL) {
            val reason = state?.reason ?: -1
            showErrorNotification(appContext, "APKダウンロードに失敗しました ($reason)")
            return
        }
        val path = prefs(appContext).getString(KEY_DOWNLOADED_FILE, null) ?: return
        val file = File(path)
        val error = validateDownloadedApk(appContext, file)
        if (error != null) {
            file.delete()
            clearDownloadPrefs(appContext)
            showErrorNotification(appContext, error)
            return
        }
        showReadyNotification(appContext)
    }

    fun downloadedFile(context: Context): File? {
        val path = prefs(context).getString(KEY_DOWNLOADED_FILE, null) ?: return null
        val file = File(path)
        return file.takeIf { validateDownloadedApk(context, it) == null }
    }

    fun downloadedVersionName(context: Context): String? {
        val file = downloadedFile(context) ?: return null
        return archiveInfo(context, file)?.versionName?.takeIf { it.isNotBlank() }
    }

    fun cleanupInstalledUpdate(context: Context) {
        val p = prefs(context)
        val path = p.getString(KEY_DOWNLOADED_FILE, null) ?: return
        val file = File(path)
        val info = archiveInfo(context, file)
        val code = info?.let(::packageVersionCode) ?: -1L
        if (!file.isFile || code <= BuildConfig.VERSION_CODE.toLong()) {
            file.delete()
            clearDownloadPrefs(context)
        }
    }

    private fun validateDownloadedApk(context: Context, file: File): String? {
        if (!file.isFile || file.length() < 50_000L) return "ダウンロードしたAPKが不完全です"
        val archive = archiveInfo(context, file) ?: return "APKを解析できません"
        if (archive.packageName != context.packageName) return "APKのパッケージ名が一致しません"
        if (packageVersionCode(archive) <= BuildConfig.VERSION_CODE.toLong()) return "APKが現在のバージョンより新しくありません"
        val current = installedInfo(context) ?: return "現在のアプリ署名を確認できません"
        val currentSigners = signerSet(current)
        val archiveSigners = signerSet(archive)
        if (currentSigners.isEmpty() || archiveSigners.isEmpty()) return "APK署名を確認できません"
        if (currentSigners.intersect(archiveSigners).isEmpty()) return "APKの署名が現在のアプリと一致しません"
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(context: Context): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return runCatching { context.packageManager.getPackageInfo(context.packageName, flags) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun signerSet(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = info.signingInfo ?: return emptySet()
            signingInfo.apkContentsSigners
        } else {
            info.signatures ?: emptyArray()
        }
        return signatures.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun queryDownloadState(dm: DownloadManager, id: Long): DownloadState? {
        if (id <= 0L) return null
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                DownloadState(status, reason)
            }
        }.getOrNull()
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "アプリのアップデート", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun showReadyNotification(context: Context) {
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, UpdateInstallActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 77, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("アップデートの準備ができました")
            .setContentText("タップして検証済みAPKをインストールします")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun showErrorNotification(context: Context, message: String) {
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("アップデートを完了できませんでした")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ERROR_NOTIFICATION_ID, notification) }
    }

    private fun clearDownloadPrefs(context: Context) {
        prefs(context).edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_VERSION)
            .remove(KEY_DOWNLOADED_FILE)
            .apply()
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
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, CHECK_INTERVAL_MS, pi)
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
