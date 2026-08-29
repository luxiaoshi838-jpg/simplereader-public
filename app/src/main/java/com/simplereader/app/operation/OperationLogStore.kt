package com.simplereader.app.operation

import android.content.Context
import com.simplereader.app.worker.ShelfCacheKeepAliveService
import java.io.File

/**
 * Compatibility shim retained only because ShelfCacheWorker still calls these hooks.
 *
 * Persistent operation-log functionality has been removed. These hooks now own only the existing
 * foreground keep-alive lifecycle so source behavior stays compatible without storing or exposing
 * operation-history data.
 */
object OperationLogStore {
    private const val LEGACY_PREFS = "operation"
    private const val REMOVED_PREFS = "operation_history"
    @Volatile private var purgeAttempted = false

    data class Entry(
        val id: String,
        val title: String,
        val body: String,
        val startedAt: Long,
        val updatedAt: Long
    )

    @Synchronized
    fun purgeLegacyV722StoreBeforeLoad(context: Context) {
        if (purgeAttempted) return
        purgeAttempted = true
        runCatching {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            listOf(LEGACY_PREFS, REMOVED_PREFS).forEach { name ->
                File(prefsDir, "$name.xml").delete()
                File(prefsDir, "$name.xml.bak").delete()
            }
        }
    }

    @Synchronized
    fun beginShelfCache(context: Context, workId: String, modeTitle: String, total: Int) {
        purgeLegacyV722StoreBeforeLoad(context)
        runCatching { ShelfCacheKeepAliveService.start(context) }
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
        if (state == "已完成") {
            runCatching { ShelfCacheKeepAliveService.stop(context) }
        }
    }

    @Synchronized
    fun list(context: Context): List<Entry> {
        purgeLegacyV722StoreBeforeLoad(context)
        return emptyList()
    }
}
