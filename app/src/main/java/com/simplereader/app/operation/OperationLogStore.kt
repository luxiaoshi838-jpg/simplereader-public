package com.simplereader.app.operation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent user-operation history.
 * One user-triggered cache WorkRequest maps to one entry; progress mutates that same entry.
 */
object OperationLogStore {
    private const val PREFS = "operation_history"
    private const val KEY_ENTRIES = "entries"
    private const val LEGACY_PREFS = "operation"
    const val MAX_ENTRIES = 10

    @Volatile private var legacyPurgeAttempted = false

    data class Entry(
        val id: String,
        val title: String,
        val body: String,
        val startedAt: Long,
        val updatedAt: Long
    )

    /**
     * v721/v722 may leave a very large shared_prefs/operation.xml. Never open or parse it.
     * Delete the obsolete XML directly; new v725 history uses operation_history instead.
     */
    @Synchronized
    fun purgeLegacyV722StoreBeforeLoad(context: Context) {
        if (legacyPurgeAttempted) return
        legacyPurgeAttempted = true
        runCatching {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(prefsDir, "$LEGACY_PREFS.xml").delete()
            File(prefsDir, "$LEGACY_PREFS.xml.bak").delete()
        }
    }

    @Synchronized
    fun beginShelfCache(context: Context, workId: String, modeTitle: String, total: Int) {
        purgeLegacyV722StoreBeforeLoad(context)
        val current = readMutable(context)
        // WorkManager may recreate the same WorkRequest after process death. The same workId must
        // keep the same single log entry and original start time instead of looking like a new run.
        if (current.any { it.id == workId }) return

        val now = System.currentTimeMillis()
        val title = "$modeTitle · ${formatTime(now)}"
        current.add(
            0,
            Entry(
                id = workId,
                title = title,
                body = buildShelfBody(
                    title = modeTitle,
                    state = "准备中",
                    current = 0,
                    total = total,
                    currentTitle = "",
                    completed = 0,
                    failed = 0,
                    skipped = 0,
                    startedAt = now,
                    updatedAt = now
                ),
                startedAt = now,
                updatedAt = now
            )
        )
        write(context, current)
    }

    @Synchronized
    fun updateShelfCache(
        context: Context,
        workId: String,
        modeTitle: String,
        state: String,
        currentIndex: Int,
        total: Int,
        currentTitle: String,
        completed: Int,
        failed: Int,
        skipped: Int
    ) {
        purgeLegacyV722StoreBeforeLoad(context)
        val now = System.currentTimeMillis()
        val entries = readMutable(context)
        val index = entries.indexOfFirst { it.id == workId }
        val startedAt = entries.getOrNull(index)?.startedAt ?: now
        val title = entries.getOrNull(index)?.title ?: "$modeTitle · ${formatTime(startedAt)}"
        val updated = Entry(
            id = workId,
            title = title,
            body = buildShelfBody(
                title = modeTitle,
                state = state,
                current = currentIndex,
                total = total,
                currentTitle = currentTitle,
                completed = completed,
                failed = failed,
                skipped = skipped,
                startedAt = startedAt,
                updatedAt = now
            ),
            startedAt = startedAt,
            updatedAt = now
        )
        if (index >= 0) entries[index] = updated else entries.add(0, updated)
        write(context, entries)
    }

    @Synchronized
    fun list(context: Context): List<Entry> {
        purgeLegacyV722StoreBeforeLoad(context)
        return readMutable(context).sortedByDescending { it.startedAt }.take(MAX_ENTRIES)
    }

    private fun buildShelfBody(
        title: String,
        state: String,
        current: Int,
        total: Int,
        currentTitle: String,
        completed: Int,
        failed: Int,
        skipped: Int,
        startedAt: Long,
        updatedAt: Long
    ): String {
        val safeTotal = total.coerceAtLeast(0)
        val safeCurrent = current.coerceIn(0, if (safeTotal > 0) safeTotal else Int.MAX_VALUE)
        val percent = if (safeTotal > 0) safeCurrent * 100.0 / safeTotal else 0.0
        return buildString {
            appendLine("操作：$title")
            appendLine("状态：$state")
            appendLine("开始：${formatTime(startedAt)}")
            appendLine("更新：${formatTime(updatedAt)}")
            appendLine("进度：$safeCurrent / $safeTotal（${String.format(Locale.getDefault(), "%.1f", percent)}%）")
            if (currentTitle.isNotBlank()) appendLine("当前：$currentTitle")
            append("成功：$completed 本；失败：$failed 本；跳过：$skipped 本")
        }
    }

    private fun readMutable(context: Context): MutableList<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null).orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            val entries = buildList {
                for (i in 0 until array.length().coerceAtMost(MAX_ENTRIES)) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        Entry(
                            id = id,
                            title = item.optString("title", "操作日志"),
                            body = item.optString("body", "暂无结果"),
                            startedAt = item.optLong("startedAt", 0L),
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.toMutableList()
            if (array.length() > MAX_ENTRIES) write(context, entries)
            entries
        }.getOrDefault(mutableListOf())
    }

    private fun write(context: Context, input: List<Entry>) {
        val trimmed = input
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }
            .take(MAX_ENTRIES)
        val array = JSONArray()
        trimmed.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("body", entry.body)
                    .put("startedAt", entry.startedAt)
                    .put("updatedAt", entry.updatedAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }

    private fun formatTime(value: Long): String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date(value))
}
