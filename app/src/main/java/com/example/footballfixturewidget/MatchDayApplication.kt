package com.example.footballfixturewidget

import android.app.Application
import android.content.Context

class MatchDayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RuntimeCrashStore.install(applicationContext)
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
