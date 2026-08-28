package com.simplereader.app.operation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCacheResumeMigrationContractTest {
    @Test
    fun unfinishedLegacyV725WorkSeedsCheckpointFromPersistedWorkManagerProgress() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val checkpoint = File("src/main/java/com/simplereader/app/worker/ShelfCacheCheckpointStore.kt").readText()

        assertTrue(worker.contains("getWorkInfoById(id).get()?.progress"))
        assertTrue(worker.contains("val legacyCompleted = persistedWorkProgress.getInt(KEY_COMPLETED, 0)"))
        assertTrue(worker.contains("val legacySkipped = persistedWorkProgress.getInt(KEY_SKIPPED, 0)"))
        assertTrue(worker.contains("val legacyFailed = persistedWorkProgress.getInt(KEY_FAILED, 0)"))
        assertTrue(worker.contains("val legacyCurrent = persistedWorkProgress.getInt(KEY_CURRENT, 0)"))
        assertTrue(worker.contains("val classifiedCount = (legacyCompleted + legacySkipped + legacyFailed)"))
        assertTrue(worker.contains("(legacyCurrent - 1).coerceAtLeast(0)"))
        assertTrue(worker.contains("nextIndex = legacyResumeIndex"))
        assertTrue(worker.contains("completed = legacyCompleted"))
        assertTrue(worker.contains("skipped = legacySkipped"))
        assertTrue(worker.contains("failed = legacyFailed"))

        assertTrue(checkpoint.contains("nextIndex: Int = 0"))
        assertTrue(checkpoint.contains("completed: Int = 0"))
        assertTrue(checkpoint.contains("skipped: Int = 0"))
        assertTrue(checkpoint.contains("failed: Int = 0"))
    }
}
