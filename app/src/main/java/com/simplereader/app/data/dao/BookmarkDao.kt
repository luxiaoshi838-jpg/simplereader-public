package com.simplereader.app.data.dao

import androidx.room.*
import com.simplereader.app.data.entity.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY CAST(position AS INTEGER) ASC, createTime ASC")
    fun getBookmarks(bookId: Long): Flow<List<Bookmark>>

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}
