package com.simplereader.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SimpleReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrashLog(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrashLog(thread: Thread, error: Throwable) {
        val directory = File(filesDir, CRASH_DIRECTORY).apply { mkdirs() }
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        File(directory, CRASH_FILE).writeText(
            buildString {
                appendLine("SimpleReader crash report")
                appendLine("time=$timestamp")
                appendLine("thread=${thread.name}")
                appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("appVersion=${installedVersionLabel()}")
                appendLine()
                append(writer.toString())
            }
        )
    }

    private fun installedVersionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName.orEmpty()} ($code)"
    }.getOrElse { "unknown" }

    companion object {
        private const val CRASH_DIRECTORY = "crash_logs"
        private const val CRASH_FILE = "latest_crash.txt"

        fun pendingCrashLog(application: Application): String? {
            val file = File(File(application.filesDir, CRASH_DIRECTORY), CRASH_FILE)
            return file.takeIf { it.isFile }?.runCatching { readText() }?.getOrNull()
        }

        fun clearPendingCrashLog(application: Application) {
            File(File(application.filesDir, CRASH_DIRECTORY), CRASH_FILE).delete()
        }
    }
}
