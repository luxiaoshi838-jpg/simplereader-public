package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCacheV797LegacyResumeContractTest {
    @Test
    fun stalledRetryWorkIsReplacedOnlyAfterCheckpointMigration() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val checkpoint = File("src/main/java/com/simplereader/app/worker/ShelfCacheCheckpointStore.kt").readText()
        val ui = File("src/main/java/com/simplereader/app/operation/ShelfCacheUiController.kt").readText()
        val build = File("build.gradle.kts").readText()
        val catalog = File("src/main/java/com/simplereader/app/parser/TxtParser.kt").readText()

        assertTrue(worker.contains("info.runAttemptCount > 0"))
        assertTrue(worker.contains("WorkInfo.State.ENQUEUED"))
        assertTrue(worker.contains("WorkInfo.State.BLOCKED"))
        assertTrue(worker.contains("ShelfCacheCheckpointStore.migrate"))
        assertTrue(worker.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(worker.indexOf("ShelfCacheCheckpointStore.migrate") < worker.indexOf("ExistingWorkPolicy.REPLACE"))
        assertTrue(worker.contains("resumeStalledLegacyWork(context: Context, workId: UUID)"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertFalse(worker.contains("Result.retry()"))

        assertTrue(checkpoint.contains("fun migrate(context: Context, fromWorkId: String, toWorkId: String)"))
        assertTrue(checkpoint.contains("save(context, toWorkId, checkpoint)"))

        assertTrue(ui.contains("running.runAttemptCount > 0"))
        assertTrue(ui.contains("ShelfCacheWorker.resumeStalledLegacyWork"))
        assertTrue(ui.contains("\"正在自动恢复\""))
        assertFalse(ui.contains("\"等待继续\""))

        assertTrue(build.contains("2098000797"))
        assertTrue(build.contains("\"797\""))
        assertTrue(catalog.contains("const val CATALOG_RULE_VERSION = 117"))
    }
}
