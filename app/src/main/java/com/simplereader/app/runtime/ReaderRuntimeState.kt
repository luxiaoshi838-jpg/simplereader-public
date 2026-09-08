package com.simplereader.app.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local ownership for ReaderActivity instances.
 *
 * Only the newest ReaderActivity generation may persist reading progress/recovery state. This
 * prevents an older hidden Activity from overwriting the foreground reader after a duplicate
 * launch. Foreground state is also exposed so the full-shelf cache worker can yield while the user
 * is actively reading/searching.
 */
object ReaderRuntimeState {
    private val generationCounter = AtomicLong(0L)

    @Volatile private var currentGeneration: Long = 0L
    @Volatile private var foregroundGeneration: Long = 0L

    fun claim(): Long {
        val generation = generationCounter.incrementAndGet()
        currentGeneration = generation
        return generation
    }

    fun isOwner(generation: Long): Boolean = generation > 0L && currentGeneration == generation

    fun markResumed(generation: Long) {
        if (isOwner(generation)) foregroundGeneration = generation
    }

    fun markPaused(generation: Long) {
        if (foregroundGeneration == generation) foregroundGeneration = 0L
    }

    fun isReaderForeground(): Boolean = foregroundGeneration != 0L
    fun ownerGeneration(): Long = currentGeneration
}
