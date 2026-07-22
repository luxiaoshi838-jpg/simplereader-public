package com.simplereader.app.data.db

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SimpleReaderDatabase::class.java
    )

    @Test
    fun migrateEmptyDatabaseFrom1To2() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            SimpleReaderDatabase.MIGRATION_1_2
        ).close()
    }

    @Test
    fun migrateTxtAndEpubProgressFrom1To2() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertV1Group(id = 1, name = "完结")
            insertV1Book(id = 10, title = "TXT书", filePath = "content://txt", format = "TXT", groupId = 1)
            insertV1Book(id = 11, title = "EPUB书", filePath = "content://epub", format = "EPUB", groupId = 1)
            insertV1Progress(bookId = 10, position = "4000")
            insertV1Progress(bookId = 11, position = "8000")
            insertV1Bookmark(id = 1, bookId = 11, position = "8000")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            SimpleReaderDatabase.MIGRATION_1_2
        )

        db.query("SELECT txtCharOffset FROM read_progress WHERE bookId = 10").use { cursor ->
            cursor.moveToFirst()
            assertEquals(4000, cursor.getInt(0))
        }
        db.query("SELECT locatorType, epubChapterHref, epubChapterOffset FROM read_progress WHERE bookId = 11").use { cursor ->
            cursor.moveToFirst()
            assertEquals("EPUB_COMBINED_LEGACY", cursor.getString(0))
            assertEquals("EPUB_COMBINED_LEGACY", cursor.getString(1))
            assertEquals(8000, cursor.getInt(2))
        }
        db.query("SELECT bookId FROM bookmarks WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(11, cursor.getLong(0))
        }
        db.close()
    }

    @Test
    fun migrateDuplicateFilePathRemapsProgressAndBookmarks() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertV1Book(id = 20, title = "保留", filePath = "content://same", format = "TXT", groupId = null)
            insertV1Book(id = 21, title = "重复", filePath = "content://same", format = "TXT", groupId = null)
            insertV1Progress(bookId = 21, position = "6000")
            insertV1Bookmark(id = 2, bookId = 21, position = "6000")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            SimpleReaderDatabase.MIGRATION_1_2
        )

        db.query("SELECT COUNT(*) FROM books WHERE filePath = 'content://same'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT bookId, txtCharOffset FROM read_progress").use { cursor ->
            cursor.moveToFirst()
            assertEquals(20, cursor.getLong(0))
            assertEquals(6000, cursor.getInt(1))
        }
        db.query("SELECT bookId FROM bookmarks WHERE id = 2").use { cursor ->
            cursor.moveToFirst()
            assertEquals(20, cursor.getLong(0))
        }
        db.close()
    }

    @Test
    fun migrateDuplicateProgressWithSameUpdateTimeChoosesDeterministicWinner() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertV1Book(id = 30, title = "旧记录", filePath = "content://same-time", format = "TXT", groupId = null)
            insertV1Book(id = 31, title = "新记录", filePath = "content://same-time", format = "TXT", groupId = null)
            insertV1Progress(bookId = 30, position = "3000")
            insertV1Progress(bookId = 31, position = "9000")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            SimpleReaderDatabase.MIGRATION_1_2
        )

        db.query("SELECT bookId, txtCharOffset FROM read_progress").use { cursor ->
            cursor.moveToFirst()
            assertEquals(30, cursor.getLong(0))
            assertEquals(9000, cursor.getInt(1))
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.insertV1Group(id: Long, name: String) {
        insert("book_groups", 0, ContentValues().apply {
            put("id", id)
            put("name", name)
            put("createTime", 1L)
        })
    }

    private fun SupportSQLiteDatabase.insertV1Book(
        id: Long,
        title: String,
        filePath: String,
        format: String,
        groupId: Long?
    ) {
        insert("books", 0, ContentValues().apply {
            put("id", id)
            put("title", title)
            put("author", "")
            put("filePath", filePath)
            put("format", format)
            if (groupId == null) putNull("groupId") else put("groupId", groupId)
            put("lastReadPosition", "")
            put("lastReadTime", 0L)
            put("addTime", 1L)
            putNull("cover")
        })
    }

    private fun SupportSQLiteDatabase.insertV1Progress(bookId: Long, position: String) {
        insert("read_progress", 0, ContentValues().apply {
            put("bookId", bookId)
            put("position", position)
            put("updateTime", 1L)
        })
    }

    private fun SupportSQLiteDatabase.insertV1Bookmark(id: Long, bookId: Long, position: String) {
        insert("bookmarks", 0, ContentValues().apply {
            put("id", id)
            put("bookId", bookId)
            put("position", position)
            put("content", "")
            put("createTime", 1L)
        })
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
