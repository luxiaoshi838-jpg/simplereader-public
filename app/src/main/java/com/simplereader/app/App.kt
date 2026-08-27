package com.simplereader.app

import android.app.Application
import com.simplereader.app.crash.CrashLogStore
import com.simplereader.app.operation.OperationLogStore
import com.simplereader.app.operation.OperationLogUiInstaller

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // v723 migration must happen before any operation-log SharedPreferences are opened.
        // v721/v722 can leave a very large operation.xml because they append per-book updates.
        OperationLogStore.purgeLegacyV722StoreBeforeLoad(this)

        CrashLogStore.install(this)
        OperationLogUiInstaller.install(this)
    }
}
