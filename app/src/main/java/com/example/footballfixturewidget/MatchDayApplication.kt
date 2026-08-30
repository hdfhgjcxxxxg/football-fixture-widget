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

        // v12.3: updater startup is deliberately delayed and wrapped so it can
        // never make the launcher crash. The normal app UI starts first.
        if (UpdateManager.isAutoEnabled(applicationContext)) {
            runCatching { UpdateManager.schedule(applicationContext) }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    UpdateManager.checkAsync(applicationContext, manual = false)
                }
            }, 30_000L)
        }
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
