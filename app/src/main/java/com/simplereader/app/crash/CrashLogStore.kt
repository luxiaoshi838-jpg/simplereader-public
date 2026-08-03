package com.simplereader.app.crash

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** Persists the latest uncaught crash so it can be copied on the next app launch. */
object CrashLogStore {
    private const val FILE_NAME = "pending_crash_log.txt"
    private const val MAX_LOG_CHARS = 512_000
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { persist(appContext, thread, error) }
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
            installed = true
        }
    }

    fun readPending(context: Context): String? = runCatching {
        pendingFile(context)
            .takeIf { it.isFile && it.length() > 0L }
            ?.readText(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun clear(context: Context): Boolean = runCatching {
        val file = pendingFile(context)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    private fun persist(context: Context, thread: Thread, error: Throwable) {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val content = buildString {
            appendLine("简阅闪退/崩溃日志")
            appendLine("时间：$timestamp")
            appendLine("版本：${packageInfo?.versionName ?: "未知"} (${versionCode ?: "未知"})")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("线程：${thread.name} / id=${thread.id}")
            appendLine()
            append(Log.getStackTraceString(error))
        }.take(MAX_LOG_CHARS)

        val target = pendingFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun pendingFile(context: Context): File =
        File(context.filesDir, FILE_NAME)
}
