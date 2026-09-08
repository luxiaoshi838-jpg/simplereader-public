from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# Version.
gradle = Path('app/build.gradle.kts')
g = gradle.read_text(encoding='utf-8')
if '2098000766' not in g:
    g = g.replace('"2098000765"', '"2098000766"')
    g = g.replace('?: 2098000765', '?: 2098000766')
    g = g.replace('?: "765"', '?: "766"')
gradle.write_text(g, encoding='utf-8')

# Start every process with a fresh diagnostic journal only after the previous process has been captured.
app = Path('app/src/main/java/com/simplereader/app/App.kt')
a = app.read_text(encoding='utf-8')
a = replace_once(
    a,
    '''        CrashLogStore.capturePreviousProcessExit(this)\n        CrashLogStore.install(this)''',
    '''        CrashLogStore.capturePreviousProcessExit(this)\n        CrashLogStore.startProcessSession(this)\n        CrashLogStore.install(this)''',
    'App process-session start',
    'CrashLogStore.startProcessSession(this)'
)
app.write_text(a, encoding='utf-8')

crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt')
c = crash.read_text(encoding='utf-8')

c = replace_once(
    c,
    '''    private const val HISTORY_FILE_NAME = "pending_crash_log_history.txt"\n''',
    '''    private const val HISTORY_FILE_NAME = "pending_crash_log_history.txt"\n    private const val SYSTEM_EXIT_HISTORY_FILE_NAME = "system_exit_history.txt"\n    private const val DIAGNOSTIC_HISTORY_FILE_NAME = "process_diagnostic_history.txt"\n    private const val PROCESS_SESSION_META_FILE_NAME = "process_session_meta.json"\n''',
    'V766 diagnostic files',
    'SYSTEM_EXIT_HISTORY_FILE_NAME'
)
c = replace_once(
    c,
    '''    private const val MAX_EXIT_TRACE_CHARS = 160_000\n''',
    '''    private const val MAX_EXIT_TRACE_CHARS = 160_000\n    private const val MAX_HISTORY_CHARS = 512_000\n''',
    'history cap',
    'MAX_HISTORY_CHARS'
)

# Replace the actionable selection so cached memory-pressure reclaim is archived silently.
old_select = '''        val state = readReaderState(appContext)\n        val abnormal = newExits\n            .filter { isActionableExit(it, state, packageLastUpdateTime) }\n            .maxByOrNull { it.timestamp }\n            ?: return\n\n        val journal = readJournal(appContext)\n        val memoryJournal = readMemoryJournal(appContext)\n        val systemTrace = readExitTrace(abnormal)\n'''
new_select = '''        val state = readReaderState(appContext)\n        val previousSessionMeta = readProcessSessionMeta(appContext)\n        val journal = readJournal(appContext)\n        val memoryJournal = readMemoryJournal(appContext)\n        val abnormal = newExits\n            .filter { isActionableExit(it, packageLastUpdateTime) }\n            .maxByOrNull { it.timestamp }\n        if (abnormal == null) {\n            newExits\n                .filter { isSilentBackgroundReclaim(it, packageLastUpdateTime) }\n                .maxByOrNull { it.timestamp }\n                ?.let { archiveSystemExitSilently(appContext, it, state, previousSessionMeta, memoryJournal, journal) }\n            return\n        }\n\n        val systemTrace = readExitTrace(abnormal)\n'''
c = replace_once(c, old_select, new_select, 'select actionable/silent exit', 'archiveSystemExitSilently(appContext')

# Include previous-process identity in a real abnormal report.
c = replace_once(
    c,
    '''            abnormal.description?.takeIf { it.isNotBlank() }?.let { appendLine("系统描述：$it") }\n            appendLine()\n            appendReaderState(this, state)\n''',
    '''            abnormal.description?.takeIf { it.isNotBlank() }?.let { appendLine("系统描述：$it") }\n            previousSessionMeta.takeIf { it.isNotBlank() }?.let { appendLine("上一进程会话：$it") }\n            appendLine()\n            appendReaderState(this, state)\n''',
    'abnormal process-session metadata',
    'appendLine("上一进程会话：$it")'
)

# Replace V765 contextual classifier with V766 importance-based background reclaim classification.
start = c.index('    private fun isActionableExit(\n')
end = c.index('    private fun archivePendingBeforeUpgrade(', start)
classifier = '''    private fun isActionableExit(\n        info: ApplicationExitInfo,\n        packageLastUpdateTime: Long\n    ): Boolean {\n        // Package replacement stops the previous process; never present that as a new-version crash.\n        if (packageLastUpdateTime > 0L && info.timestamp in 1 until packageLastUpdateTime) return false\n        if (isSilentBackgroundReclaim(info, packageLastUpdateTime)) return false\n\n        return when (info.reason) {\n            ApplicationExitInfo.REASON_UNKNOWN,\n            ApplicationExitInfo.REASON_SIGNALED,\n            ApplicationExitInfo.REASON_LOW_MEMORY,\n            ApplicationExitInfo.REASON_CRASH,\n            ApplicationExitInfo.REASON_CRASH_NATIVE,\n            ApplicationExitInfo.REASON_ANR,\n            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,\n            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,\n            ApplicationExitInfo.REASON_DEPENDENCY_DIED,\n            ApplicationExitInfo.REASON_OTHER -> true\n            else -> false\n        }\n    }\n\n    private fun isSilentBackgroundReclaim(\n        info: ApplicationExitInfo,\n        packageLastUpdateTime: Long\n    ): Boolean {\n        if (packageLastUpdateTime > 0L && info.timestamp in 1 until packageLastUpdateTime) return false\n        val cachedOrWorse = info.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED\n        if (!cachedOrWorse) return false\n        return when (info.reason) {\n            ApplicationExitInfo.REASON_OTHER -> info.description.orEmpty().lowercase().contains("mem_pressure")\n            ApplicationExitInfo.REASON_LOW_MEMORY -> true\n            else -> false\n        }\n    }\n\n    private fun archiveSystemExitSilently(\n        context: Context,\n        info: ApplicationExitInfo,\n        state: ReaderState?,\n        previousSessionMeta: String,\n        memoryJournal: String,\n        journal: String\n    ) {\n        val section = buildString {\n            appendLine("================ 后台系统回收（静默，不弹窗） ================")\n            appendLine("退出时间：${formatTimestamp(info.timestamp)}")\n            appendLine("退出原因：${exitReasonName(info.reason)} (${info.reason})")\n            appendLine("importance：${info.importance}")\n            appendLine("PSS：${info.pss} kB")\n            appendLine("RSS：${info.rss} kB")\n            info.description?.takeIf { it.isNotBlank() }?.let { appendLine("系统描述：$it") }\n            previousSessionMeta.takeIf { it.isNotBlank() }?.let { appendLine("上一进程会话：$it") }\n            appendLine()\n            appendReaderState(this, state)\n            if (memoryJournal.isNotBlank()) {\n                appendLine()\n                appendLine("该进程内存诊断流水：")\n                append(memoryJournal.takeLast(MAX_MEMORY_JOURNAL_CHARS))\n            }\n            if (journal.isNotBlank()) {\n                appendLine()\n                appendLine("该进程诊断流水：")\n                append(journal.takeLast(MAX_JOURNAL_CHARS))\n            }\n        }\n        prependBounded(systemExitHistoryFile(context), section, MAX_HISTORY_CHARS)\n    }\n\n'''
c = c[:start] + classifier + c[end:]

# Add process-session rotation before beginReaderSession.
anchor = '    fun beginReaderSession(context: Context, bookId: Long, turnMode: String) {\n'
if 'fun startProcessSession(context: Context)' not in c:
    session_code = '''    /**\n     * V766: after the previous process exit has been captured, rotate its journals and start a\n     * clean process-local diagnostic stream. Reader recovery state is intentionally not cleared.\n     */\n    fun startProcessSession(context: Context) {\n        val appContext = context.applicationContext\n        synchronized(pendingLock) {\n            val oldMeta = readProcessSessionMeta(appContext)\n            val oldMemory = readMemoryJournal(appContext)\n            val oldJournal = readJournal(appContext)\n            if (oldMeta.isNotBlank() || oldMemory.isNotBlank() || oldJournal.isNotBlank()) {\n                val section = buildString {\n                    appendLine("================ 已轮转进程诊断 ================")\n                    if (oldMeta.isNotBlank()) appendLine("进程会话：$oldMeta")\n                    if (oldMemory.isNotBlank()) {\n                        appendLine("内存流水：")\n                        append(oldMemory.takeLast(MAX_MEMORY_JOURNAL_CHARS))\n                    }\n                    if (oldJournal.isNotBlank()) {\n                        appendLine()\n                        appendLine("事件流水：")\n                        append(oldJournal.takeLast(MAX_JOURNAL_CHARS))\n                    }\n                }\n                prependBounded(diagnosticHistoryFile(appContext), section, MAX_HISTORY_CHARS)\n            }\n            runCatching { memoryJournalFile(appContext).delete() }\n            runCatching { journalFile(appContext).delete() }\n\n            val packageInfo = runCatching {\n                appContext.packageManager.getPackageInfo(appContext.packageName, 0)\n            }.getOrNull()\n            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {\n                packageInfo?.longVersionCode ?: -1L\n            } else {\n                @Suppress("DEPRECATION") packageInfo?.versionCode?.toLong() ?: -1L\n            }\n            val meta = JSONObject()\n                .put("versionName", packageInfo?.versionName ?: "?")\n                .put("versionCode", versionCode)\n                .put("pid", Process.myPid())\n                .put("processSession", processSessionId)\n                .put("startedAt", System.currentTimeMillis())\n                .toString()\n            writeAtomic(processSessionMetaFile(appContext), meta)\n        }\n    }\n\n'''
    c = c.replace(anchor, session_code + anchor, 1)

# Helpers near readJournal.
helper_anchor = '    private fun readJournal(context: Context): String = runCatching {\n'
if 'private fun readProcessSessionMeta' not in c:
    helpers = '''    private fun readProcessSessionMeta(context: Context): String = runCatching {\n        val file = processSessionMetaFile(context)\n        if (!file.isFile || file.length() <= 0L) "" else file.readText(Charsets.UTF_8).take(2000)\n    }.getOrDefault("")\n\n    private fun prependBounded(file: File, section: String, maxChars: Int) {\n        val existing = runCatching { file.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty() }.getOrDefault("")\n        val combined = if (existing.isBlank()) section else section + "\\n\\n" + existing\n        writeAtomic(file, combined.take(maxChars))\n    }\n\n'''
    c = c.replace(helper_anchor, helpers + helper_anchor, 1)

# File paths at bottom.
c = replace_once(
    c,
    '''    private fun memoryJournalFile(context: Context): File = File(context.filesDir, MEMORY_JOURNAL_FILE_NAME)\n}''',
    '''    private fun memoryJournalFile(context: Context): File = File(context.filesDir, MEMORY_JOURNAL_FILE_NAME)\n    private fun processSessionMetaFile(context: Context): File = File(context.filesDir, PROCESS_SESSION_META_FILE_NAME)\n    private fun systemExitHistoryFile(context: Context): File = File(context.filesDir, SYSTEM_EXIT_HISTORY_FILE_NAME)\n    private fun diagnosticHistoryFile(context: Context): File = File(context.filesDir, DIAGNOSTIC_HISTORY_FILE_NAME)\n}''',
    'V766 file paths',
    'private fun processSessionMetaFile'
)

crash.write_text(c, encoding='utf-8')
print('v766 exit classification + process-session isolation patch applied')
