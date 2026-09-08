from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing anchor: {label}')
    return text.replace(old, new, 1)

# version
p = Path('app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('"2098000764"', '"2098000765"')
s = s.replace('?: 2098000764', '?: 2098000765')
s = s.replace('?: "764"', '?: "765"')
p.write_text(s, encoding='utf-8')

# CrashLogStore filtering / update-session archive
p = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s,
'''    private const val PREF_LAST_HANDLED_EXIT_TS = "last_handled_exit_timestamp"\n''',
'''    private const val PREF_LAST_HANDLED_EXIT_TS = "last_handled_exit_timestamp"\n    private const val PREF_LAST_SEEN_VERSION_CODE = "last_seen_version_code"\n    private const val HISTORY_FILE_NAME = "pending_crash_log_history.txt"\n''', 'prefs constants')

old = '''        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n        val handledTimestamp = prefs.getLong(PREF_LAST_HANDLED_EXIT_TS, 0L)\n        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return\n'''
new = '''        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n        val packageInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()\n        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {\n            packageInfo?.longVersionCode ?: -1L\n        } else {\n            @Suppress("DEPRECATION")\n            packageInfo?.versionCode?.toLong() ?: -1L\n        }\n        val packageLastUpdateTime = packageInfo?.lastUpdateTime ?: 0L\n        val previousVersionCode = prefs.getLong(PREF_LAST_SEEN_VERSION_CODE, 0L)\n        val isUpgradeLaunch = previousVersionCode > 0L && currentVersionCode > 0L && previousVersionCode != currentVersionCode\n        val pending = pendingFile(appContext)\n        val pendingPredatesCurrentInstall = pending.isFile && packageLastUpdateTime > 0L &&\n            pending.lastModified() in 1 until packageLastUpdateTime\n        if (isUpgradeLaunch || pendingPredatesCurrentInstall) {\n            archivePendingBeforeUpgrade(appContext, previousVersionCode, currentVersionCode)\n        }\n        if (currentVersionCode > 0L) {\n            prefs.edit().putLong(PREF_LAST_SEEN_VERSION_CODE, currentVersionCode).apply()\n        }\n        val handledTimestamp = prefs.getLong(PREF_LAST_HANDLED_EXIT_TS, 0L)\n        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return\n'''
s = replace_once(s, old, new, 'capture metadata')

old = '''        val abnormal = newExits\n            .filter { isActionableExitReason(it.reason) }\n            .maxByOrNull { it.timestamp }\n            ?: return\n\n        val state = readReaderState(appContext)\n'''
new = '''        val state = readReaderState(appContext)\n        val abnormal = newExits\n            .filter { isActionableExit(it, state, packageLastUpdateTime) }\n            .maxByOrNull { it.timestamp }\n            ?: return\n\n'''
s = replace_once(s, old, new, 'actionable exit filter')

# replace old simple reason function with contextual classifier + archive helper
start = s.index('    private fun isActionableExitReason(reason: Int): Boolean = when (reason) {')
end = s.index('    private fun exitReasonName(reason: Int): String = when (reason) {', start)
replacement = '''    private fun isActionableExit(\n        info: ApplicationExitInfo,\n        state: ReaderState?,\n        packageLastUpdateTime: Long\n    ): Boolean {\n        // A package update necessarily ends the old process. Never present an exit that predates\n        // the current APK installation as a fresh error on the first launch after updating.\n        if (packageLastUpdateTime > 0L && info.timestamp in 1 until packageLastUpdateTime) return false\n\n        if (info.reason == ApplicationExitInfo.REASON_OTHER) {\n            val description = info.description.orEmpty().lowercase()\n            val cleanReader = state?.active == false && state.event.startsWith("reader_clean_finish")\n            // Android commonly reports cached background-process reclamation this way. It remains in\n            // the memory diagnostics, but a cleanly closed reader must not trigger an error dialog.\n            if (cleanReader && description.contains("normal_mem_pressure")) return false\n        }\n\n        return when (info.reason) {\n            ApplicationExitInfo.REASON_UNKNOWN,\n            ApplicationExitInfo.REASON_SIGNALED,\n            ApplicationExitInfo.REASON_LOW_MEMORY,\n            ApplicationExitInfo.REASON_CRASH,\n            ApplicationExitInfo.REASON_CRASH_NATIVE,\n            ApplicationExitInfo.REASON_ANR,\n            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,\n            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,\n            ApplicationExitInfo.REASON_DEPENDENCY_DIED,\n            ApplicationExitInfo.REASON_OTHER -> true\n            else -> false\n        }\n    }\n\n    private fun archivePendingBeforeUpgrade(context: Context, fromVersion: Long, toVersion: Long) {\n        synchronized(pendingLock) {\n            val pending = pendingFile(context)\n            if (!pending.isFile || pending.length() <= 0L) return\n            val old = runCatching { pending.readText(Charsets.UTF_8) }.getOrDefault("")\n            if (old.isNotBlank()) {\n                val history = File(context.filesDir, HISTORY_FILE_NAME)\n                val existing = runCatching { history.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty() }.getOrDefault("")\n                val section = buildString {\n                    appendLine("================ 升级前历史异常记录：$fromVersion -> $toVersion ================")\n                    appendLine("归档时间：${formatTimestamp(System.currentTimeMillis())}")\n                    append(old)\n                    if (existing.isNotBlank()) {\n                        appendLine()\n                        append(existing)\n                    }\n                }.take(MAX_LOG_CHARS)\n                writeAtomic(history, section)\n            }\n            runCatching { pending.delete() }\n        }\n    }\n\n'''
s = s[:start] + replacement + s[end:]
p.write_text(s, encoding='utf-8')

print('v765 exit-popup filter patch applied')
