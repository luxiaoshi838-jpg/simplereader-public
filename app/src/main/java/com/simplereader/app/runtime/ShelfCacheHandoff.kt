package com.simplereader.app.runtime

/**
 * Process-local handoff between the foreground reader and the full-shelf cache worker.
 *
 * A single book may only be owned by one side while its derived catalog/page cache is being
 * created or replaced. When the user opens a not-yet-finished book during a shelf-cache pass,
 * the reader can finish that book itself and register it as externally completed. The worker then
 * counts that book as completed instead of clearing and rebuilding the same cache again.
 */
object ShelfCacheHandoff {
    enum class ReaderClaimResult {
        NOT_NEEDED,
        ACQUIRED,
        WAIT_FOR_WORKER
    }

    private enum class Owner {
        READER,
        WORKER
    }

    private val activeWorkIds = linkedSetOf<String>()
    private val owners = mutableMapOf<Long, Owner>()
    private val foregroundCompletedBooks = linkedSetOf<Long>()

    @Synchronized
    fun beginWork(workId: String) {
        if (workId.isNotBlank()) activeWorkIds += workId
    }

    @Synchronized
    fun endWork(workId: String) {
        activeWorkIds.remove(workId)
        if (activeWorkIds.isEmpty()) {
            owners.entries.removeAll { it.value == Owner.WORKER }
            foregroundCompletedBooks.clear()
        }
    }

    @Synchronized
    fun tryClaimForReader(bookId: Long): ReaderClaimResult {
        if (bookId <= 0L || activeWorkIds.isEmpty()) return ReaderClaimResult.NOT_NEEDED
        return when (owners[bookId]) {
            Owner.WORKER -> ReaderClaimResult.WAIT_FOR_WORKER
            Owner.READER -> ReaderClaimResult.ACQUIRED
            null -> {
                owners[bookId] = Owner.READER
                ReaderClaimResult.ACQUIRED
            }
        }
    }

    @Synchronized
    fun tryClaimForWorker(bookId: Long): Boolean {
        if (bookId <= 0L) return false
        return when (owners[bookId]) {
            Owner.READER -> false
            Owner.WORKER -> true
            null -> {
                owners[bookId] = Owner.WORKER
                true
            }
        }
    }

    @Synchronized
    fun releaseReader(bookId: Long) {
        if (owners[bookId] == Owner.READER) owners.remove(bookId)
    }

    @Synchronized
    fun releaseWorker(bookId: Long) {
        if (owners[bookId] == Owner.WORKER) owners.remove(bookId)
    }

    @Synchronized
    fun markForegroundCompleted(bookId: Long): Boolean {
        if (bookId <= 0L || activeWorkIds.isEmpty()) return false
        foregroundCompletedBooks += bookId
        return true
    }

    @Synchronized
    fun consumeForegroundCompleted(bookId: Long): Boolean =
        foregroundCompletedBooks.remove(bookId)

    @Synchronized
    internal fun resetForTests() {
        activeWorkIds.clear()
        owners.clear()
        foregroundCompletedBooks.clear()
    }
}
