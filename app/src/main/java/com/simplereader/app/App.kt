package com.simplereader.app

import android.app.Application
import com.simplereader.app.crash.CrashLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    /** Short application-lifetime IO jobs that must survive an Activity finishing. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogStore.install(this)
    }
}
