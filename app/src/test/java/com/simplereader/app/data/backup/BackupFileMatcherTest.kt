package com.simplereader.app.data.backup

import com.simplereader.app.data.backup.BackupFileMatcher.BackupBookIdentity
import com.simplereader.app.data.backup.LocalLibraryScanner.LocalBookFile
import com.simplereader.app.data.model.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFileMatcherTest {
    @Test
    fun `matches an old root path to the same relative book under a new root`() {
        val match = BackupFileMatcher.selectUnique(
            backup = identity(relativePath = "旧根目录/文学"),
            candidates = listOf(file(relativePath = "文学"))
        )

        assertEquals("content://new/第一本.txt", match?.uri)
    }

    @Test
    fun `does not guess between ambiguous same-name files`() {
        val match = BackupFileMatcher.selectUnique(
            backup = identity(relativePath = null),
            candidates = listOf(
                file(relativePath = "A", uri = "content://new/A/第一本.txt"),
                file(relativePath = "B", uri = "content://new/B/第一本.txt")
            )
        )

        assertNull(match)
    }

    private fun identity(relativePath: String?) = BackupBookIdentity(
        oldId = 10,
        title = "第一本",
        fileName = "第一本.txt",
        format = "TXT",
        fileSize = 100,
        relativePath = relativePath
    )

    private fun file(
        relativePath: String,
        uri: String = "content://new/第一本.txt"
    ) = LocalBookFile(
        uri = uri,
        displayName = "第一本.txt",
        format = "TXT",
        sourceTreeUri = "content://tree/new",
        relativeParentPath = relativePath,
        size = 100,
        lastModified = 3000,
        permissionStatus = PermissionStatus.PERSISTED
    )
}
