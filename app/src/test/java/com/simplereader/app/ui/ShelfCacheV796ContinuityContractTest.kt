package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCacheV796ContinuityContractTest {
    @Test
    fun shelfCacheStaysContinuousAndRestoresDurableProgress() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val ui = File("src/main/java/com/simplereader/app/operation/ShelfCacheUiController.kt").readText()
        val keepAlive = File("src/main/java/com/simplereader/app/worker/ShelfCacheKeepAliveService.kt").readText()
        val build = File("build.gradle.kts").readText()
        val catalog = File("src/main/java/com/simplereader/app/parser/TxtParser.kt").readText()

        assertTrue(worker.contains("ShelfCacheKeepAliveService.start(app)"))
        assertTrue(worker.contains("awaitForegroundReaderIdle()"))
        assertFalse(worker.contains("Result.retry()"))
        assertTrue(worker.contains("paginationOwnerJob?.isActive == false"))

        assertTrue(ui.contains("ShelfCacheCheckpointStore.load"))
        assertTrue(ui.contains("checkpoint.nextIndex"))
        assertTrue(ui.contains("checkpoint.total"))
        assertTrue(ui.contains("\"等待继续\""))

        assertTrue(keepAlive.contains("NOTIFICATION_ID = 61314"))
        assertTrue(build.contains("2098000796"))
        assertTrue(build.contains("\"796\""))

        // Catalog Rule 117 is intentionally outside this fix and must stay unchanged.
        assertTrue(catalog.contains("const val CATALOG_RULE_VERSION = 117"))
    }
}
