from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# Version only. V762 intentionally does not tune reader caches or scrolling behavior.
gradle = Path('app/build.gradle.kts')
g = gradle.read_text(encoding='utf-8')
g = replace_once(g, '"2098000761"', '"2098000762"', 'version code env default')
g = replace_once(g, '?: 2098000761', '?: 2098000762', 'version code fallback')
g = replace_once(g, '?: "761"', '?: "762"', 'version name fallback')
gradle.write_text(g, encoding='utf-8')

# Expand crash/exit diagnostics with coarse, background-only memory attribution.
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt')
c = crash.read_text(encoding='utf-8')
c = replace_once(c, 'import android.os.Build\n', 'import android.os.Build\nimport android.os.Debug\n', 'Debug import')
c = replace_once(c, 'import java.util.concurrent.Executors\n', 'import java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\n', 'TimeUnit import')
c = replace_once(c,
'''    private const val JOURNAL_FILE_NAME = "reader_diagnostic_journal.txt"\n''',
'''    private const val JOURNAL_FILE_NAME = "reader_diagnostic_journal.txt"\n    private const val MEMORY_JOURNAL_FILE_NAME = "process_memory_diagnostic.txt"\n''', 'memory journal file')
c = replace_once(c,
'''    private const val MAX_JOURNAL_CHARS = 96_000\n''',
'''    private const val MAX_JOURNAL_CHARS = 96_000\n    private const val MAX_MEMORY_JOURNAL_CHARS = 160_000\n''', 'memory journal cap')
c = replace_once(c,
'''    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->\n        Thread(runnable, "jianyu-crash-log-io").apply { isDaemon = true }\n    }\n''',
'''    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->\n        Thread(runnable, "jianyu-crash-log-io").apply { isDaemon = true }\n    }\n''', 'scheduled diagnostics executor')

# Include the persisted memory trend in Android exit reports.
c = replace_once(c,
'''        val journal = readJournal(appContext)\n        val systemTrace = readExitTrace(abnormal)\n''',
'''        val journal = readJournal(appContext)\n        val memoryJournal = readMemoryJournal(appContext)\n        val systemTrace = readExitTrace(abnormal)\n''', 'read memory journal for exit')
c = replace_once(c,
'''            appendReaderState(this, state)\n            if (journal.isNotBlank()) {\n''',
'''            appendReaderState(this, state)\n            if (memoryJournal.isNotBlank()) {\n                appendLine()\n                appendLine("进程退出前内存诊断流水：")\n                append(memoryJournal.takeLast(MAX_MEMORY_JOURNAL_CHARS))\n            }\n            if (journal.isNotBlank()) {\n''', 'append memory journal to exit')

# Snapshot at reader session boundaries. The delayed samples hold only application context/state,
# never an Activity/View reference, so they can reveal whether memory falls after ReaderActivity dies.
c = replace_once(c,
'''        enqueueStateWrite(appContext, state, force = true)\n        recordEvent(appContext, "reader_session_begin book=$bookId mode=$turnMode preservedOffset=${state.sourceOffset}")\n    }\n''',
'''        enqueueStateWrite(appContext, state, force = true)\n        recordEvent(appContext, "reader_session_begin book=$bookId mode=$turnMode preservedOffset=${state.sourceOffset}")\n        recordMemorySnapshot(appContext, "reader_session_begin")\n    }\n''', 'session begin memory')
c = replace_once(c,
'''        enqueueStateWrite(appContext, state, force = true)\n        recordEvent(appContext, "$event book=$bookId page=${state.pageIndex} offset=${state.sourceOffset}")\n    }\n\n    /** Returns only a non-zero anchor from an unfinished reader session. */\n''',
'''        enqueueStateWrite(appContext, state, force = true)\n        recordEvent(appContext, "$event book=$bookId page=${state.pageIndex} offset=${state.sourceOffset}")\n        recordMemorySnapshot(appContext, event)\n        schedulePostReaderMemorySnapshots(appContext)\n    }\n\n    /**\n     * V762 memory attribution. Collection happens only on the crash-log executor, never in a\n     * RecyclerView scroll callback. Android Debug.MemoryInfo lets the next exit report distinguish\n     * Java/native/graphics/code/private-other/system PSS instead of reporting only one total PSS.\n     */\n    fun recordMemorySnapshot(context: Context, reason: String, details: String = "") {\n        val appContext = context.applicationContext\n        val safeReason = reason.replace('\\n', ' ').replace('\\r', ' ').take(200)\n        val safeDetails = details.replace('\\n', ' ').replace('\\r', ' ').take(1200)\n        ioExecutor.execute {\n            runCatching { writeMemorySnapshot(appContext, safeReason, safeDetails) }\n        }\n    }\n\n    private fun schedulePostReaderMemorySnapshots(context: Context) {\n        val appContext = context.applicationContext\n        listOf(\n            5L to TimeUnit.SECONDS,\n            30L to TimeUnit.SECONDS,\n            2L to TimeUnit.MINUTES,\n            10L to TimeUnit.MINUTES,\n            30L to TimeUnit.MINUTES\n        ).forEach { (delay, unit) ->\n            ioExecutor.schedule({\n                runCatching { writeMemorySnapshot(appContext, "post_reader_finish_${delay}_${unit.name.lowercase()}", "") }\n            }, delay, unit)\n        }\n    }\n\n    private fun writeMemorySnapshot(context: Context, reason: String, details: String) {\n        val debug = Debug.MemoryInfo()\n        Debug.getMemoryInfo(debug)\n        val stats = debug.memoryStats\n        val runtime = Runtime.getRuntime()\n        val system = ActivityManager.MemoryInfo()\n        runCatching {\n            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(system)\n        }\n        val state = stateRef.get() ?: readReaderState(context)\n        fun stat(name: String): String = stats[name] ?: "?"\n        val javaUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L\n        val javaCommittedKb = runtime.totalMemory() / 1024L\n        val javaMaxKb = runtime.maxMemory() / 1024L\n        val nativeAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L\n        val line = buildString {\n            append(formatTimestamp(System.currentTimeMillis()))\n            append(" | memory reason=").append(reason)\n            append(" totalPss=").append(debug.totalPss).append("kB")\n            append(" javaPss=").append(stat("summary.java-heap")).append("kB")\n            append(" nativePss=").append(stat("summary.native-heap")).append("kB")\n            append(" graphicsPss=").append(stat("summary.graphics")).append("kB")\n            append(" codePss=").append(stat("summary.code")).append("kB")\n            append(" stackPss=").append(stat("summary.stack")).append("kB")\n            append(" privateOtherPss=").append(stat("summary.private-other")).append("kB")\n            append(" systemPss=").append(stat("summary.system")).append("kB")\n            append(" swapPss=").append(stat("summary.total-swap")).append("kB")\n            append(" dalvikPss=").append(debug.dalvikPss).append("kB")\n            append(" nativeRawPss=").append(debug.nativePss).append("kB")\n            append(" otherRawPss=").append(debug.otherPss).append("kB")\n            append(" javaUsed=").append(javaUsedKb).append("kB")\n            append(" javaCommitted=").append(javaCommittedKb).append("kB")\n            append(" javaMax=").append(javaMaxKb).append("kB")\n            append(" nativeAllocated=").append(nativeAllocatedKb).append("kB")\n            append(" sysAvail=").append(system.availMem / 1024L).append("kB")\n            append(" sysThreshold=").append(system.threshold / 1024L).append("kB")\n            append(" sysLow=").append(system.lowMemory)\n            if (state != null) {\n                append(" readerActive=").append(state.active)\n                append(" book=").append(state.bookId)\n                append(" page=").append(state.pageIndex)\n                append('/').append(state.totalPages)\n                append(" offset=").append(state.sourceOffset)\n                append(" event=").append(state.event)\n            }\n            if (details.isNotBlank()) append(" details=").append(details)\n            append('\\n')\n        }\n        val file = memoryJournalFile(context)\n        file.parentFile?.mkdirs()\n        file.appendText(line, Charsets.UTF_8)\n        if (file.length() > MAX_MEMORY_JOURNAL_CHARS * 2L) {\n            writeAtomic(file, file.readText(Charsets.UTF_8).takeLast(MAX_MEMORY_JOURNAL_CHARS))\n        }\n    }\n\n    /** Returns only a non-zero anchor from an unfinished reader session. */\n''', 'session finish and memory functions')

c = replace_once(c,
'''    private fun readJournal(context: Context): String = runCatching {\n        val file = journalFile(context)\n        if (!file.isFile || file.length() <= 0L) "" else file.readText(Charsets.UTF_8).takeLast(MAX_JOURNAL_CHARS)\n    }.getOrDefault("")\n''',
'''    private fun readJournal(context: Context): String = runCatching {\n        val file = journalFile(context)\n        if (!file.isFile || file.length() <= 0L) "" else file.readText(Charsets.UTF_8).takeLast(MAX_JOURNAL_CHARS)\n    }.getOrDefault("")\n\n    private fun readMemoryJournal(context: Context): String = runCatching {\n        val file = memoryJournalFile(context)\n        if (!file.isFile || file.length() <= 0L) "" else file.readText(Charsets.UTF_8).takeLast(MAX_MEMORY_JOURNAL_CHARS)\n    }.getOrDefault("")\n''', 'read memory journal')
c = replace_once(c,
'''    private fun journalFile(context: Context): File = File(context.filesDir, JOURNAL_FILE_NAME)\n}''',
'''    private fun journalFile(context: Context): File = File(context.filesDir, JOURNAL_FILE_NAME)\n    private fun memoryJournalFile(context: Context): File = File(context.filesDir, MEMORY_JOURNAL_FILE_NAME)\n}''', 'memory journal path')
crash.write_text(c, encoding='utf-8')

# Application memory-pressure callbacks are rare and are exactly where attribution is useful.
app = Path('app/src/main/java/com/simplereader/app/App.kt')
a = app.read_text(encoding='utf-8')
a = replace_once(a,
'''        CrashLogStore.capturePreviousProcessExit(this)\n        CrashLogStore.install(this)\n    }\n}''',
'''        CrashLogStore.capturePreviousProcessExit(this)\n        CrashLogStore.install(this)\n        CrashLogStore.recordMemorySnapshot(this, "process_start")\n    }\n\n    override fun onTrimMemory(level: Int) {\n        CrashLogStore.recordMemorySnapshot(this, "app_onTrimMemory_$level")\n        super.onTrimMemory(level)\n    }\n\n    override fun onLowMemory() {\n        CrashLogStore.recordMemorySnapshot(this, "app_onLowMemory")\n        super.onLowMemory()\n    }\n}''', 'App memory callbacks')
app.write_text(a, encoding='utf-8')

# Reader-side calls pass only cheap structural counts. No memory API is executed on the main thread.
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
r = reader.read_text(encoding='utf-8')
r = replace_once(r,
'''        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset")\n        saveProgress()\n''',
'''        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset")\n        CrashLogStore.recordMemorySnapshot(this, "reader_onPause", memoryDiagnosticDetails())\n        saveProgress()\n''', 'onPause memory')
r = replace_once(r,
'''        CrashLogStore.recordEvent(this, "ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset")\n        if (isFinishing && !isChangingConfigurations) {\n''',
'''        CrashLogStore.recordEvent(this, "ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset")\n        CrashLogStore.recordMemorySnapshot(this, "reader_onDestroy", memoryDiagnosticDetails())\n        if (isFinishing && !isChangingConfigurations) {\n''', 'onDestroy memory')
r = replace_once(r,
'''                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:success book=$bookId pages=${paged.pages.size} target=$currentPageIndex stable=$lastStableSourceOffset cached=${cached != null}")\n                paginationInProgress = false\n''',
'''                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:success book=$bookId pages=${paged.pages.size} target=$currentPageIndex stable=$lastStableSourceOffset cached=${cached != null}")\n                CrashLogStore.recordMemorySnapshot(\n                    this@ReaderActivity,\n                    "paginate_success",\n                    memoryDiagnosticDetails() + " cached=${cached != null}"\n                )\n                paginationInProgress = false\n''', 'paginate memory')
r = replace_once(r,
'''    private fun createLayoutSettings(): ReaderLayoutSettings {\n''',
'''    private fun memoryDiagnosticDetails(): String {\n        val paged = readerBook\n        val sourceTextChars = paged?.text?.length ?: document?.text?.length ?: -1\n        val rv = verticalRecyclerView\n        return "book=$bookId mode=$pageTurnMode textChars=$sourceTextChars pages=${paged?.pages?.size ?: 0} chapters=${paged?.chapters?.size ?: 0} currentPage=$currentPageIndex rvChildren=${rv?.childCount ?: 0} rvItems=${rv?.adapter?.itemCount ?: 0}"\n    }\n\n    private fun createLayoutSettings(): ReaderLayoutSettings {\n''', 'memory details helper')
reader.write_text(r, encoding='utf-8')

print('v762 memory-attribution diagnostics patch applied/idempotent')
