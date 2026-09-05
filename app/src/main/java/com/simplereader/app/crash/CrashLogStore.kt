package com.simplereader.app.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Crash/exit diagnostics for 简阅.
 *
 * V758 only persisted Java/Kotlin uncaught exceptions. V759 additionally records Android's
 * historical process-exit reason (ANR, native crash/signal, low-memory kill, excessive resource
 * use, etc.) on the next launch, and keeps a throttled reader-state file outside Room so an
 * abnormal process death cannot turn a transient RecyclerView row 0 into the only recovery point.
 * All routine reader-state/journal IO runs on a single background executor and never on a scroll
 * callback's main-thread hot path.
 */
object CrashLogStore {
    private const val PENDING_FILE_NAME = "pending_crash_log.txt"
    private const val READER_STATE_FILE_NAME = "reader_recovery_state.json"
    private const val JOURNAL_FILE_NAME = "reader_diagnostic_journal.txt"
    private const val PREFS_NAME = "crash_log_store"
    private const val PREF_LAST_HANDLED_EXIT_TS = "last_handled_exit_timestamp"
    private const val MAX_LOG_CHARS = 512_000
    private const val MAX_STACK_CHARS = 300_000
    private const val MAX_OOM_STACK_CHARS = 64_000
    private const val MAX_JOURNAL_CHARS = 96_000
    private const val STATE_FLUSH_MIN_INTERVAL_MS = 850L

    @Volatile private var installed = false
    @Volatile private var emergencyReserve: ByteArray? = null
    @Volatile private var lastStateFlushUptime = 0L

    private val pendingLock = Any()
    private val stateRef = AtomicReference<ReaderState?>(null)
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jianyu-crash-log-io").apply { isDaemon = true }
    }

    data class ReaderState(
        val active: Boolean,
        val bookId: Long,
        val pageIndex: Int,
        val totalPages: Int,
        val sourceOffset: Int,
        val turnMode: String,
        val event: String,
        val timestamp: Long
    )

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            // Reserve a small emergency block. Releasing it before crash serialization improves the
            // chance that a Java OutOfMemoryError can still write a minimal diagnostic file.
            emergencyReserve = runCatching { ByteArray(128 * 1024) }.getOrNull()
            stateRef.compareAndSet(null, readReaderState(appContext))
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                emergencyReserve = null
                runCatching { persistUncaught(appContext, thread, error) }
                    .onFailure {
                        runCatching {
                            writePendingSection(
                                appContext,
                                "简阅闪退日志写入失败\n" +
                                    "时间：${formatTimestamp(System.currentTimeMillis())}\n" +
                                    "原异常：${error.javaClass.name}: ${error.message.orEmpty()}\n" +
                                    "日志异常：${it.javaClass.name}: ${it.message.orEmpty()}",
                                preservePrevious = false
                            )
                        }
                    }
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

    /**
     * Android 11+ keeps process-death metadata even when no Java uncaught-exception handler runs.
     * Capture the newest unhandled abnormal exit before the new process starts a reader session.
     */
    fun capturePreviousProcessExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val handledTimestamp = prefs.getLong(PREF_LAST_HANDLED_EXIT_TS, 0L)
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 8)
        }.getOrDefault(emptyList())
        val newExits = exits.filter { it.timestamp > handledTimestamp }
        if (newExits.isEmpty()) return

        // Advance over both normal and abnormal exits so benign history is never reprocessed.
        prefs.edit().putLong(PREF_LAST_HANDLED_EXIT_TS, newExits.maxOf { it.timestamp }).apply()
        val abnormal = newExits
            .filter { isActionableExitReason(it.reason) }
            .maxByOrNull { it.timestamp }
            ?: return

        val state = readReaderState(appContext)
        val journal = readJournal(appContext)
        val section = buildString {
            appendLine("简阅异常进程退出记录（Android 系统）")
            appendLine("退出时间：${formatTimestamp(abnormal.timestamp)}")
            appendLine("退出原因：${exitReasonName(abnormal.reason)} (${abnormal.reason})")
            appendLine("status：${abnormal.status}")
            appendLine("importance：${abnormal.importance}")
            appendLine("PSS：${abnormal.pss} kB")
            appendLine("RSS：${abnormal.rss} kB")
            appendLine("进程：${abnormal.processName.orEmpty()}")
            abnormal.description?.takeIf { it.isNotBlank() }?.let { appendLine("系统描述：$it") }
            appendLine()
            appendReaderState(this, state)
            if (journal.isNotBlank()) {
                appendLine()
                appendLine("异常前诊断流水：")
                append(journal.takeLast(MAX_JOURNAL_CHARS))
            }
        }
        writePendingSection(appContext, section, preservePrevious = true)
    }

    fun beginReaderSession(context: Context, bookId: Long, turnMode: String) {
        if (bookId <= 0L) return
        val appContext = context.applicationContext
        val previous = stateRef.get() ?: readReaderState(appContext)
        val preserved = previous?.takeIf { it.bookId == bookId }
        val state = ReaderState(
            active = true,
            bookId = bookId,
            pageIndex = preserved?.pageIndex ?: -1,
            totalPages = preserved?.totalPages ?: 0,
            sourceOffset = preserved?.sourceOffset ?: -1,
            turnMode = turnMode,
            event = "reader_session_begin",
            timestamp = System.currentTimeMillis()
        )
        stateRef.set(state)
        enqueueStateWrite(appContext, state, force = true)
        recordEvent(appContext, "reader_session_begin book=$bookId mode=$turnMode preservedOffset=${state.sourceOffset}")
    }

    fun recordReaderPosition(
        context: Context,
        bookId: Long,
        pageIndex: Int,
        totalPages: Int,
        sourceOffset: Int,
        turnMode: String,
        event: String,
        force: Boolean = false
    ) {
        if (bookId <= 0L || pageIndex < 0 || totalPages <= 0 || sourceOffset < 0) return
        val appContext = context.applicationContext
        val state = ReaderState(
            active = true,
            bookId = bookId,
            pageIndex = pageIndex,
            totalPages = totalPages,
            sourceOffset = sourceOffset,
            turnMode = turnMode,
            event = event,
            timestamp = System.currentTimeMillis()
        )
        stateRef.set(state)
        enqueueStateWrite(appContext, state, force)
    }

    fun finishReaderSession(context: Context, bookId: Long, event: String = "reader_clean_finish") {
        if (bookId <= 0L) return
        val appContext = context.applicationContext
        val previous = stateRef.get() ?: readReaderState(appContext)
        if (previous == null || previous.bookId != bookId) return
        val state = previous.copy(active = false, event = event, timestamp = System.currentTimeMillis())
        stateRef.set(state)
        enqueueStateWrite(appContext, state, force = true)
        recordEvent(appContext, "$event book=$bookId page=${state.pageIndex} offset=${state.sourceOffset}")
    }

    /** Returns only a non-zero anchor from an unfinished reader session. */
    fun recoveryOffset(context: Context, bookId: Long): Int? {
        if (bookId <= 0L) return null
        val state = stateRef.get()?.takeIf { it.bookId == bookId }
            ?: readReaderState(context.applicationContext)?.takeIf { it.bookId == bookId }
        return state
            ?.takeIf { it.active && it.sourceOffset > 0 }
            ?.sourceOffset
    }

    fun recordEvent(context: Context, event: String) {
        val appContext = context.applicationContext
        val safe = event.replace('\n', ' ').replace('\r', ' ').take(1200)
        val line = "${formatTimestamp(System.currentTimeMillis())} | $safe\n"
        ioExecutor.execute {
            runCatching {
                val file = journalFile(appContext)
                file.parentFile?.mkdirs()
                file.appendText(line, Charsets.UTF_8)
                if (file.length() > MAX_JOURNAL_CHARS * 2L) {
                    val tail = file.readText(Charsets.UTF_8).takeLast(MAX_JOURNAL_CHARS)
                    writeAtomic(file, tail)
                }
            }
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

    private fun enqueueStateWrite(context: Context, state: ReaderState, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStateFlushUptime < STATE_FLUSH_MIN_INTERVAL_MS) return
        lastStateFlushUptime = now
        ioExecutor.execute {
            runCatching { writeReaderState(context, state) }
        }
    }

    private fun writeReaderState(context: Context, state: ReaderState) {
        val json = JSONObject()
            .put("active", state.active)
            .put("bookId", state.bookId)
            .put("pageIndex", state.pageIndex)
            .put("totalPages", state.totalPages)
            .put("sourceOffset", state.sourceOffset)
            .put("turnMode", state.turnMode)
            .put("event", state.event)
            .put("timestamp", state.timestamp)
            .toString()
        writeAtomic(readerStateFile(context), json)
    }

    private fun readReaderState(context: Context): ReaderState? = runCatching {
        val file = readerStateFile(context)
        if (!file.isFile || file.length() <= 0L) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        ReaderState(
            active = json.optBoolean("active", false),
            bookId = json.optLong("bookId", 0L),
            pageIndex = json.optInt("pageIndex", -1),
            totalPages = json.optInt("totalPages", 0),
            sourceOffset = json.optInt("sourceOffset", -1),
            turnMode = json.optString("turnMode", ""),
            event = json.optString("event", ""),
            timestamp = json.optLong("timestamp", 0L)
        )
    }.getOrNull()

    private fun persistUncaught(context: Context, thread: Thread, error: Throwable) {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        }
        val state = stateRef.get() ?: readReaderState(context)
        val runtime = Runtime.getRuntime()
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        }
        val stackLimit = if (error is OutOfMemoryError) MAX_OOM_STACK_CHARS else MAX_STACK_CHARS
        val stack = runCatching { Log.getStackTraceString(error).take(stackLimit) }
            .getOrElse { "${error.javaClass.name}: ${error.message.orEmpty()}" }
        val journal = if (error is OutOfMemoryError) "" else readJournal(context)

        val content = buildString {
            appendLine("简阅闪退/崩溃日志（Java/Kotlin 未捕获异常）")
            appendLine("时间：${formatTimestamp(System.currentTimeMillis())}")
            appendLine("版本：${packageInfo?.versionName ?: "未知"} (${versionCode ?: "未知"})")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("线程：${thread.name} / id=${thread.id}")
            appendLine("Java heap：max=${runtime.maxMemory()} total=${runtime.totalMemory()} free=${runtime.freeMemory()}")
            appendLine("系统内存：avail=${memoryInfo.availMem} lowMemory=${memoryInfo.lowMemory} threshold=${memoryInfo.threshold}")
            appendLine()
            appendReaderState(this, state)
            if (journal.isNotBlank()) {
                appendLine()
                appendLine("异常前诊断流水：")
                append(journal.takeLast(MAX_JOURNAL_CHARS))
                appendLine()
            }
            appendLine()
            appendLine("异常堆栈：")
            append(stack)
        }.take(if (error is OutOfMemoryError) 160_000 else MAX_LOG_CHARS)

        writePendingSection(
            context,
            content,
            preservePrevious = error !is OutOfMemoryError
        )
    }

    private fun appendReaderState(builder: StringBuilder, state: ReaderState?) {
        if (state == null) {
            builder.appendLine("阅读器状态：无可用快照")
            return
        }
        builder.appendLine("阅读器状态：active=${state.active}")
        builder.appendLine("bookId=${state.bookId}")
        builder.appendLine("page=${state.pageIndex + 1}/${state.totalPages}")
        builder.appendLine("sourceOffset=${state.sourceOffset}")
        builder.appendLine("turnMode=${state.turnMode}")
        builder.appendLine("lastEvent=${state.event}")
        builder.appendLine("stateTime=${formatTimestamp(state.timestamp)}")
    }

    private fun isActionableExitReason(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_OTHER -> true
        else -> false
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN/未知"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF/应用自行退出"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED/信号终止"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY/低内存终止"
        ApplicationExitInfo.REASON_CRASH -> "CRASH/Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE/native 崩溃"
        ApplicationExitInfo.REASON_ANR -> "ANR/无响应"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE/初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE/权限变化"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE/资源使用过量"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED/用户终止"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED/系统停止应用"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED/依赖进程死亡"
        ApplicationExitInfo.REASON_OTHER -> "OTHER/其他系统原因"
        else -> "SYSTEM_REASON_$reason"
    }

    private fun readJournal(context: Context): String = runCatching {
        val file = journalFile(context)
        if (!file.isFile || file.length() <= 0L) "" else file.readText(Charsets.UTF_8).takeLast(MAX_JOURNAL_CHARS)
    }.getOrDefault("")

    private fun writePendingSection(context: Context, section: String, preservePrevious: Boolean) {
        synchronized(pendingLock) {
            val target = pendingFile(context)
            val previous = if (preservePrevious) {
                runCatching { target.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty() }
                    .getOrDefault("")
            } else ""
            val combined = if (previous.isBlank()) {
                section
            } else {
                section + "\n\n================ 之前尚未清除的记录 ================\n\n" + previous
            }
            writeAtomic(target, combined.take(MAX_LOG_CHARS))
        }
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestamp))

    private fun pendingFile(context: Context): File = File(context.filesDir, PENDING_FILE_NAME)
    private fun readerStateFile(context: Context): File = File(context.filesDir, READER_STATE_FILE_NAME)
    private fun journalFile(context: Context): File = File(context.filesDir, JOURNAL_FILE_NAME)
}
