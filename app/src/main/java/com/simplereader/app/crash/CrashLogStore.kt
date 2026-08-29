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

/**
 * Source-owned crash history.
 *
 * - keeps at most 10 crash/handled-error entries for the log directory UI;
 * - also keeps the legacy pending file so the existing one-time relaunch prompt still works;
 * - contains no APK/DEX overlay dependency.
 */
object CrashLogStore {
    private const val DIRECTORY_NAME = "crash_logs"
    private const val LEGACY_FILE_NAME = "pending_crash_log.txt"
    private const val MAX_ENTRIES = 10
    private const val MAX_LOG_CHARS = 512_000
    @Volatile private var installed = false

    data class Entry(
        val fileName: String,
        val displayName: String
    )

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            migrateLegacy(appContext)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { persist(appContext, thread, error, "未捕获崩溃", updatePending = true) }
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

    /** Compatibility with the existing one-time relaunch crash prompt. */
    fun readPending(context: Context): String? = runCatching {
        pendingFile(context)
            .takeIf { it.isFile && it.length() > 0L }
            ?.readText(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    /** Clears only the one-time pending marker; retained crash history remains available in 日志. */
    fun clear(context: Context): Boolean = runCatching {
        val file = pendingFile(context)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    fun hasLogs(context: Context): Boolean = list(context).isNotEmpty()

    fun list(context: Context): List<Entry> {
        migrateLegacy(context.applicationContext)
        val directory = logDirectory(context)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .take(MAX_ENTRIES)
            .map { file ->
                val header = runCatching {
                    file.useLines(Charsets.UTF_8) { lines -> lines.take(3).toList() }
                }.getOrDefault(emptyList())
                val time = header.firstOrNull { it.startsWith("时间：") }
                    ?.removePrefix("时间：")
                    ?.trim()
                    .orEmpty()
                val type = header.firstOrNull { it.startsWith("类型：") }
                    ?.removePrefix("类型：")
                    ?.trim()
                    .orEmpty()
                Entry(
                    fileName = file.name,
                    displayName = listOf(time, type).filter(String::isNotBlank).joinToString(" · ")
                        .ifBlank { file.nameWithoutExtension }
                )
            }
            .toList()
    }

    fun read(context: Context, entry: Entry): String? = runCatching {
        val directory = logDirectory(context).canonicalFile
        val target = File(directory, entry.fileName).canonicalFile
        if (target.parentFile != directory || !target.isFile) return@runCatching null
        target.readText(Charsets.UTF_8).takeIf(String::isNotBlank)
    }.getOrNull()

    /** Records a caught/handled failure in the same history directory without replacing pending crash state. */
    fun recordHandled(context: Context, label: String, error: Throwable) {
        runCatching {
            persist(
                context.applicationContext,
                Thread.currentThread(),
                error,
                label.ifBlank { "已处理异常" },
                updatePending = false
            )
        }
    }

    private fun persist(
        context: Context,
        thread: Thread,
        error: Throwable,
        type: String,
        updatePending: Boolean
    ) {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        }
        val now = Date()
        val timestamp = displayStamp().format(now)
        val content = buildString {
            appendLine("简阅闪退/崩溃日志")
            appendLine("时间：$timestamp")
            appendLine("类型：$type")
            appendLine("版本：${packageInfo?.versionName ?: "未知"} (${versionCode ?: "未知"})")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("线程：${thread.name} / id=${thread.id}")
            appendLine()
            append(Log.getStackTraceString(error))
        }.take(MAX_LOG_CHARS)

        val directory = logDirectory(context).apply { mkdirs() }
        val name = "crash_${fileStamp().format(now)}_${System.nanoTime()}.txt"
        writeAtomically(File(directory, name), content)
        trimToLimit(directory)

        if (updatePending) {
            writeAtomically(pendingFile(context), content)
        }
    }

    private fun migrateLegacy(context: Context) {
        val legacy = pendingFile(context)
        if (!legacy.isFile || legacy.length() <= 0L) return
        val directory = logDirectory(context).apply { mkdirs() }
        val alreadyMigrated = directory.listFiles().orEmpty().any { file ->
            file.isFile && runCatching { file.length() == legacy.length() && file.readText() == legacy.readText() }
                .getOrDefault(false)
        }
        if (!alreadyMigrated) {
            val migrated = File(directory, "legacy_${legacy.lastModified().coerceAtLeast(0L)}.txt")
            runCatching { writeAtomically(migrated, legacy.readText(Charsets.UTF_8)) }
            trimToLimit(directory)
        }
    }

    private fun trimToLimit(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_ENTRIES)
            .forEach { runCatching { it.delete() } }
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun logDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)
    private fun pendingFile(context: Context): File = File(context.filesDir, LEGACY_FILE_NAME)

    private fun displayStamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private fun fileStamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
