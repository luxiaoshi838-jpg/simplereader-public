package com.simplereader.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.BookGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupDeletionTest {
    private lateinit var database: SimpleReaderDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SimpleReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletingGroupMovesBooksToUngroupedWithoutDeletingThem() = runBlocking {
        val groupId = database.bookGroupDao().insert(
            BookGroup(
                name = "测试分组",
                displayName = "测试分组",
                sourceKey = "test:delete-group"
            )
        )
        val bookId = database.bookDao().insert(
            Book(
                title = "测试书籍",
                filePath = "content://test/book.txt",
                format = "TXT",
                groupId = groupId
            )
        )

        assertNotNull(database.bookGroupDao().getGroup(groupId))
        assertEquals(groupId, database.bookDao().getBook(bookId)?.groupId)

        database.withTransaction {
            database.bookDao().clearGroup(groupId)
            database.bookGroupDao().deleteById(groupId)
        }

        val remainingBook = database.bookDao().getBook(bookId)
        assertNotNull(remainingBook)
        assertNull(remainingBook?.groupId)
        assertNull(database.bookGroupDao().getGroup(groupId))
    }
}
