package com.simplereader.app.data.migration

import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.model.DuplicateStatus
import com.simplereader.app.data.model.ImportCandidate
import com.simplereader.app.data.model.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRelinkMatcherTest {
    @Test
    fun `matches restored rows without changing logical book identity`() {
        val books = listOf(
            restoredBook(id = 11, fileName = "第一本.txt", relativePath = "旧根目录/小说", size = 100),
            restoredBook(id = 22, fileName = "第二本.epub", relativePath = "旧根目录/文学", size = 200)
        )
        val candidates = listOf(
            candidate(fileName = "第二本.epub", relativePath = "新根目录/文学", size = 200, format = "EPUB"),
            candidate(fileName = "第一本.txt", relativePath = "新根目录/小说", size = 100, format = "TXT")
        )

        val matches = BackupRelinkMatcher.match(books, candidates)

        assertEquals(2, matches.size)
        assertEquals(setOf(11L, 22L), matches.map { it.book.id }.toSet())
        assertEquals("content://new/第一本.txt", matches.single { it.book.id == 11L }.candidate.uri)
    }

    @Test
    fun `does not guess when duplicate files are ambiguous`() {
        val books = listOf(
            restoredBook(id = 1, fileName = "同名.txt", relativePath = null, size = 100),
            restoredBook(id = 2, fileName = "同名.txt", relativePath = null, size = 100)
        )
        val candidates = listOf(
            candidate(fileName = "同名.txt", relativePath = null, size = 100, format = "TXT")
        )

        assertTrue(BackupRelinkMatcher.match(books, candidates).isEmpty())
    }

    @Test
    fun `uses each restored row at most once`() {
        val books = listOf(
            restoredBook(id = 7, fileName = "唯一.txt", relativePath = "books", size = 100)
        )
        val candidates = listOf(
            candidate(fileName = "唯一.txt", relativePath = "root/books", size = 100, format = "TXT"),
            candidate(fileName = "唯一.txt", relativePath = "other/books", size = 100, format = "TXT")
        )

        val matches = BackupRelinkMatcher.match(books, candidates)

        assertEquals(1, matches.size)
        assertEquals(7L, matches.single().book.id)
    }

    private fun restoredBook(
        id: Long,
        fileName: String,
        relativePath: String?,
        size: Long
    ) = Book(
        id = id,
        title = fileName.substringBeforeLast('.'),
        filePath = "backup://missing/$id",
        format = fileName.substringAfterLast('.').uppercase(),
        groupId = id,
        fileName = fileName,
        fileSize = size,
        relativePath = relativePath,
        fileStatus = "MISSING"
    )

    private fun candidate(
        fileName: String,
        relativePath: String?,
        size: Long,
        format: String
    ) = ImportCandidate(
        uri = "content://new/$fileName",
        displayName = fileName,
        format = format,
        sourceTreeUri = "content://tree/new",
        relativeParentPath = relativePath,
        suggestedGroupKey = null,
        suggestedGroupName = null,
        size = size,
        lastModified = 123L,
        duplicateStatus = DuplicateStatus.POSSIBLE_DUPLICATE,
        permissionStatus = PermissionStatus.PERSISTED
    )
}
