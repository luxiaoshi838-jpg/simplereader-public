package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StableWorkflowContractTest {
    @Test
    fun `group card opens an isolated group bookshelf activity`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val method = source.substringAfter("private fun showGroupBooksV2(")
            .substringBefore("private fun showBookActionsV2(")
        assertTrue(method.contains("GroupBooksActivity::class.java"))
        assertTrue(method.contains("GroupBooksActivity.EXTRA_GROUP_ID"))
        assertFalse(method.contains("selectedGroupId = group.id"))
        assertFalse(method.contains("updateUI()"))
    }

    @Test
    fun `folder import treats the selected folder as depth one`() {
        val source = projectFile("src/main/java/com/simplereader/app/data/repository/FolderGrouping.kt").readText()
        val groupingSegments = source.substringAfter("private fun groupingSegments(")
            .substringBeforeLast("}")
        assertTrue(source.contains("Depth 1 is the folder selected by the user"))
        assertTrue(groupingSegments.contains("return allSegments"))
        assertFalse(groupingSegments.contains("drop(1)"))
    }

    @Test
    fun `schema v1 restore uses saved backup locations before asking for a folder`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val prepare = source.substringAfter("private fun prepareBackupRestore(")
            .substringBefore("private fun restoreBackupFromSavedLocations(")
        val directRestore = source.substringAfter("private fun restoreBackupFromSavedLocations(")
            .substringBefore("private fun restoreBackupFromRoot(")
        assertTrue(prepare.contains("SimpleReaderBackupDecoder.decode(text)"))
        assertTrue(prepare.contains("restoreBackupFromSavedLocations(backup)"))
        assertFalse(prepare.contains("backupRootLauncher.launch(null)"))
        assertTrue(directRestore.contains("scannedFiles = emptyList()"))
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

    @Test
    fun `backup restore preserves original file locations when permission is missing`() {
        val source = projectFile("src/main/java/com/simplereader/app/data/backup/SimpleReaderBackupRestorer.kt").readText()
        val fallback = source.substringAfter("!originalFilePath.isNullOrBlank() -> BookLocation(")
            .substringBefore("else -> BookLocation(")
        assertTrue(fallback.contains("filePath = originalFilePath"))
        assertTrue(fallback.contains("sourceTreeUri = row.stringOrNull(\"sourceTreeUri\")"))
        assertTrue(fallback.contains("relativePath = oldRelativePath"))
        assertTrue(fallback.contains("fileStatus = \"PERMISSION_REVOKED\""))
    }

    private fun projectFile(vararg candidates: String): File {
        val cwd = File(System.getProperty("user.dir"))
        return candidates.asSequence()
            .map { File(cwd, it) }
            .firstOrNull { it.exists() }
            ?: error("Project file not found: ${candidates.joinToString()}")
    }
}
