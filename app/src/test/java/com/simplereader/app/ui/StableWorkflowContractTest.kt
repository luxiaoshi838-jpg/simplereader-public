package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StableWorkflowContractTest {
    @Test
    fun `group card always opens dedicated group activity`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val method = source.substringAfter("private fun showGroupBooksV2(")
            .substringBefore("private fun showBookActionsV2(")
        assertTrue(method.contains("GroupBooksActivity::class.java"))
        assertTrue(method.contains("startActivity(intent)"))
        assertFalse(method.contains("AlertDialog.Builder"))
    }

    @Test
    fun `backup import immediately starts one guided relink`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val method = source.substringAfter("private fun importReaderData(")
            .substringBefore("private suspend fun restoreReaderBackup(")
        assertTrue(method.contains("relinkRestoredDataLauncher.launch(null)"))
        assertFalse(source.contains("showBackupRestoreCompleted"))
        assertFalse(source.contains("继续选择文件夹"))
        assertTrue(source.contains("导入备份并自动关联"))
    }

    @Test
    fun `legacy backup relink build patch is disabled`() {
        val settings = projectFile("../settings.gradle.kts", "settings.gradle.kts").readText()
        assertFalse(settings.contains("backup_relink_migration.gradle.kts"))
    }

    private fun projectFile(vararg candidates: String): File {
        val cwd = File(System.getProperty("user.dir"))
        return candidates.asSequence()
            .map { File(cwd, it) }
            .firstOrNull { it.exists() }
            ?: error("Project file not found: ${candidates.joinToString()}")
    }
}
