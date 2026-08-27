package com.simplereader.app

import android.app.Application
import com.simplereader.app.crash.CrashLogStore
import com.simplereader.app.operation.OperationLogUiInstaller

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogStore.install(this)
        OperationLogUiInstaller.install(this)
    }
}
