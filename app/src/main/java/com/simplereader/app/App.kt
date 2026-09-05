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
        // V759: Android 11+ may know that the previous process died from ANR/native crash/
        // low-memory even when no Java uncaught-exception handler ran. Capture it before the new
        // process starts another reader session, then install the live Java/Kotlin crash handler.
        CrashLogStore.capturePreviousProcessExit(this)
        CrashLogStore.install(this)
    }
}
