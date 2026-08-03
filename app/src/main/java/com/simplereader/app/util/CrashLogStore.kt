package com.simplereader.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogStore {
    private const val FILE_NAME = "pending_crash_log.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun readPending(context: Context): String? = pendingFile(context)
        .takeIf { it.isFile && it.length() > 0L }
        ?.readText(Charsets.UTF_8)
        ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) { pendingFile(context).delete() }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})"
        }.getOrDefault("unknown")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val text = buildString {
            appendLine("简阅闪退/崩溃日志")
            appendLine("时间：$timestamp")
            appendLine("版本：$version")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("线程：${thread.name}")
            appendLine()
            append(trace)
        }
        val target = pendingFile(context)
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) { target.writeText(text, Charsets.UTF_8); temp.delete() }
    }

    private fun pendingFile(context: Context): File = File(context.filesDir, FILE_NAME)
}
