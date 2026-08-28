package com.simplereader.app.worker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable checkpoint for a single user-triggered shelf cache WorkRequest.
 *
 * WorkManager may recreate a CoroutineWorker after process death. The WorkRequest id remains the
 * same, so the worker can resume from the first unfinished book instead of starting the shelf over.
 */
object ShelfCacheCheckpointStore {
    private const val PREFS = "shelf_cache_checkpoint"
    private const val KEY_PREFIX = "work_"

    data class Checkpoint(
        val mode: String,
        val bookIds: List<Long>,
        val nextIndex: Int,
        val completed: Int,
        val skipped: Int,
        val failed: Int
    ) {
        val total: Int get() = bookIds.size
    }

    @Synchronized
    fun load(context: Context, workId: String): Checkpoint? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(workId), null)
            ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val ids = root.getJSONArray("bookIds")
            val bookIds = buildList {
                for (index in 0 until ids.length()) add(ids.getLong(index))
            }
            val nextIndex = root.optInt("nextIndex", 0).coerceIn(0, bookIds.size)
            Checkpoint(
                mode = root.getString("mode"),
                bookIds = bookIds,
                nextIndex = nextIndex,
                completed = root.optInt("completed", 0).coerceAtLeast(0),
                skipped = root.optInt("skipped", 0).coerceAtLeast(0),
                failed = root.optInt("failed", 0).coerceAtLeast(0)
            )
        }.getOrNull()
    }

    @Synchronized
    fun create(
        context: Context,
        workId: String,
        mode: String,
        bookIds: List<Long>,
        nextIndex: Int = 0,
        completed: Int = 0,
        skipped: Int = 0,
        failed: Int = 0
    ): Checkpoint {
        val stableBookIds = bookIds.distinct()
        val safeCompleted = completed.coerceAtLeast(0)
        val safeSkipped = skipped.coerceAtLeast(0)
        val safeFailed = failed.coerceAtLeast(0)
        val fullyProcessed = (safeCompleted + safeSkipped + safeFailed).coerceAtMost(stableBookIds.size)
        val checkpoint = Checkpoint(
            mode = mode,
            bookIds = stableBookIds,
            nextIndex = nextIndex.coerceIn(fullyProcessed, stableBookIds.size),
            completed = safeCompleted,
            skipped = safeSkipped,
            failed = safeFailed
        )
        save(context, workId, checkpoint)
        return checkpoint
    }

    @Synchronized
    fun save(context: Context, workId: String, checkpoint: Checkpoint) {
        val ids = JSONArray().apply {
            checkpoint.bookIds.forEach { bookId -> put(bookId) }
        }
        val root = JSONObject()
            .put("mode", checkpoint.mode)
            .put("bookIds", ids)
            .put("nextIndex", checkpoint.nextIndex.coerceIn(0, checkpoint.bookIds.size))
            .put("completed", checkpoint.completed.coerceAtLeast(0))
            .put("skipped", checkpoint.skipped.coerceAtLeast(0))
            .put("failed", checkpoint.failed.coerceAtLeast(0))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(workId), root.toString())
            .commit()
    }

    @Synchronized
    fun clear(context: Context, workId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(workId))
            .apply()
    }

    private fun key(workId: String): String = "$KEY_PREFIX$workId"
}
