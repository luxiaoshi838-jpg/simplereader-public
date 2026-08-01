package com.simplereader.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SimpleReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installRecyclerViewScrollbarGuard()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrashLog(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun installRecyclerViewScrollbarGuard() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                guardActivity(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                guardActivity(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun guardActivity(activity: Activity) {
        val root = activity.window?.decorView ?: return
        disableRecyclerViewSystemScrollbars(root)
        root.post { disableRecyclerViewSystemScrollbars(root) }
        root.postDelayed({ disableRecyclerViewSystemScrollbars(root) }, SCROLLBAR_GUARD_DELAY_MS)
    }

    private fun disableRecyclerViewSystemScrollbars(view: View) {
        if (view is RecyclerView) {
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.isScrollbarFadingEnabled = false
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                disableRecyclerViewSystemScrollbars(view.getChildAt(index))
            }
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
        private const val SCROLLBAR_GUARD_DELAY_MS = 500L

        fun pendingCrashLog(application: Application): String? {
            val file = File(File(application.filesDir, CRASH_DIRECTORY), CRASH_FILE)
            return file.takeIf { it.isFile }?.runCatching { readText() }?.getOrNull()
        }

        fun clearPendingCrashLog(application: Application) {
            File(File(application.filesDir, CRASH_DIRECTORY), CRASH_FILE).delete()
        }
    }
}
