package com.health.app

import android.app.Application
import androidx.work.Configuration

class HealthApp : Application(), Configuration.Provider {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        container.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}

val android.content.Context.container: Container get() = (applicationContext as HealthApp).container
