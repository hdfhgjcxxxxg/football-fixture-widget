package com.example.footballfixturewidget

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper

class MatchDayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RuntimeCrashStore.install(applicationContext)

        // v12.5: keep startup resilient, clean stale update files, and avoid
        // duplicate network checks after every launcher open.
        runCatching { UpdateManager.cleanupInstalledUpdate(applicationContext) }
        if (UpdateManager.isAutoEnabled(applicationContext)) {
            runCatching { UpdateManager.schedule(applicationContext) }
            if (UpdateManager.isAutoCheckDue(applicationContext)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        UpdateManager.checkAsync(applicationContext, manual = false)
                    }
                }, 30_000L)
            }
        }
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
