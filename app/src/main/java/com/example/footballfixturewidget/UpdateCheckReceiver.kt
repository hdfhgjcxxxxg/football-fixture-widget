package com.example.footballfixturewidget

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                UpdateManager.handleDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
            }
            UpdateManager.ACTION_AUTO_CHECK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (intent.action != UpdateManager.ACTION_AUTO_CHECK) UpdateManager.schedule(context)
                if (UpdateManager.isAutoEnabled(context)) {
                    val pending = goAsync()
                    UpdateManager.checkAsync(context.applicationContext, manual = false) { pending.finish() }
                }
            }
        }
    }
}
