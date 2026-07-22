package com.simplereader.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simplereader.app.data.backup.LocalLibraryScanner.LocalBookFile
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.model.PermissionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SimpleReaderBackupRestorerTest {
    private lateinit var context: Context
    private lateinit var database: SimpleReaderDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SimpleReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `restores schema v1 with new ids and current file uri in one transaction`() = runBlocking {
        val backup = SimpleReaderBackupDecoder.decode(SimpleReaderBackupDecoderTest.schemaV1Json())
        val scanned = listOf(
            LocalBookFile(
                uri = "content://new/文学/第一本.txt",
                displayName = "第一本.txt",
                format = "TXT",
                sourceTreeUri = "content://tree/new",
                relativeParentPath = "文学",
                size = 100,
                lastModified = 3000,
                permissionStatus = PermissionStatus.PERSISTED
            )
        )

        val summary = SimpleReaderBackupRestorer(context, database).restore(backup, scanned)
        val groups = database.bookGroupDao().getAllGroups().first()
        val books = database.bookDao().getAllBooks().first()
        val book = books.single()

        assertEquals(1, summary.linkedBooks)
        assertEquals(0, summary.missingBooks)
        assertEquals("文学", groups.single().displayName)
        assertNotEquals(0L, groups.single().id)
        assertEquals(groups.single().id, book.groupId)
        assertEquals("content://new/文学/第一本.txt", book.filePath)
        assertEquals("AVAILABLE", book.fileStatus)
        assertEquals("25", database.readProgressDao().getProgress(book.id)?.position)
        assertTrue(database.bookmarkDao().getBookmarks(book.id).first().any { it.content == "书签" })
    }
}
