package com.example.footballfixturewidget

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                UpdateManager.handleDownloadComplete(
                    context,
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                )
            }

            UpdateManager.ACTION_AUTO_CHECK -> {
                if (UpdateManager.isAutoEnabled(context)) {
                    val pending = goAsync()
                    UpdateManager.checkAsync(context.applicationContext, manual = false) { pending.finish() }
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                UpdateManager.schedule(context)
                if (UpdateManager.isAutoEnabled(context) && UpdateManager.isAutoCheckDue(context)) {
                    val pending = goAsync()
                    UpdateManager.checkAsync(context.applicationContext, manual = false) { pending.finish() }
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UpdateManager.cleanupInstalledUpdate(context)
                UpdateManager.schedule(context)
                if (UpdateManager.isAutoEnabled(context) && UpdateManager.isAutoCheckDue(context)) {
                    val pending = goAsync()
                    UpdateManager.checkAsync(context.applicationContext, manual = false) { pending.finish() }
                }
            }
        }
    }
}
