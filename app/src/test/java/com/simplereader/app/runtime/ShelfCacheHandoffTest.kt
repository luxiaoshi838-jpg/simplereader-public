package com.simplereader.app.runtime

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ShelfCacheHandoffTest {
    @Before
    fun setUp() {
        ShelfCacheHandoff.resetForTests()
    }

    @After
    fun tearDown() {
        ShelfCacheHandoff.resetForTests()
    }

    @Test
    fun readerOwnsPendingBookAndWorkerCannotEnterUntilRelease() {
        ShelfCacheHandoff.beginWork("work-1")

        assertEquals(
            ShelfCacheHandoff.ReaderClaimResult.ACQUIRED,
            ShelfCacheHandoff.tryClaimForReader(26527L)
        )
        assertFalse(ShelfCacheHandoff.tryClaimForWorker(26527L))

        assertTrue(ShelfCacheHandoff.markForegroundCompleted(26527L))
        ShelfCacheHandoff.releaseReader(26527L)

        assertTrue(ShelfCacheHandoff.tryClaimForWorker(26527L))
        assertTrue(ShelfCacheHandoff.consumeForegroundCompleted(26527L))
        ShelfCacheHandoff.releaseWorker(26527L)
    }

    @Test
    fun readerWaitsWhenWorkerAlreadyOwnsSameBook() {
        ShelfCacheHandoff.beginWork("work-1")
        assertTrue(ShelfCacheHandoff.tryClaimForWorker(26527L))

        assertEquals(
            ShelfCacheHandoff.ReaderClaimResult.WAIT_FOR_WORKER,
            ShelfCacheHandoff.tryClaimForReader(26527L)
        )

        ShelfCacheHandoff.releaseWorker(26527L)
        assertEquals(
            ShelfCacheHandoff.ReaderClaimResult.ACQUIRED,
            ShelfCacheHandoff.tryClaimForReader(26527L)
        )
    }

    @Test
    fun noShelfTaskMeansReaderDoesNotCreateAStaleCompletionMarker() {
        assertEquals(
            ShelfCacheHandoff.ReaderClaimResult.NOT_NEEDED,
            ShelfCacheHandoff.tryClaimForReader(26527L)
        )
        assertFalse(ShelfCacheHandoff.markForegroundCompleted(26527L))
    }

    @Test
    fun endingLastWorkClearsForegroundCompletionMarkers() {
        ShelfCacheHandoff.beginWork("work-1")
        assertTrue(ShelfCacheHandoff.markForegroundCompleted(26527L))
        ShelfCacheHandoff.endWork("work-1")

        ShelfCacheHandoff.beginWork("work-2")
        assertFalse(ShelfCacheHandoff.consumeForegroundCompleted(26527L))
    }
}
