package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StableWorkflowContractTest {
    @Test
    fun `group card filters the existing bookshelf instead of launching another activity`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val method = source.substringAfter("private fun showGroupBooksV2(")
            .substringBefore("private fun showBookActionsV2(")
        assertTrue(method.contains("selectedGroupId = group.id"))
        assertTrue(method.contains("updateUI()"))
        assertFalse(source.contains("GroupBooksActivity::class.java"))
    }

    @Test
    fun `schema v1 is decoded before the single original-library folder selection`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val prepare = source.substringAfter("private fun prepareBackupRestore(")
            .substringBefore("private fun restoreBackupFromRoot(")
        assertTrue(prepare.contains("SimpleReaderBackupDecoder.decode(text)"))
        assertTrue(prepare.contains("pendingBackup = backup"))
        assertTrue(prepare.contains("backupRootLauncher.launch(null)"))
        assertFalse(source.contains("补充关联未找到的书籍"))
        assertFalse(source.contains("relinkRestoredDataLauncher"))
    }

    @Test
    fun `build files never rewrite application source`() {
        val settings = projectFile("../settings.gradle.kts", "settings.gradle.kts").readText()
        val build = projectFile("build.gradle.kts").readText()
        assertFalse(settings.contains("gradle.beforeProject"))
        assertFalse(settings.contains("app/scripts"))
        assertFalse(build.contains("applyRuntimeUiPatches"))
        assertFalse(build.contains("scripts/"))
    }

    @Test
    fun `backup export schema and table names remain unchanged`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        assertTrue(source.contains("manifest.put(\"format\", \"SimpleReaderBackup\")"))
        assertTrue(source.contains("private const val BACKUP_SCHEMA_VERSION = 1"))
        assertTrue(source.contains("listOf(\"book_groups\", \"books\", \"bookmarks\", \"read_progress\")"))
    }

    private fun projectFile(vararg candidates: String): File {
        val cwd = File(System.getProperty("user.dir"))
        return candidates.asSequence()
            .map { File(cwd, it) }
            .firstOrNull { it.exists() }
            ?: error("Project file not found: ${candidates.joinToString()}")
    }
}
