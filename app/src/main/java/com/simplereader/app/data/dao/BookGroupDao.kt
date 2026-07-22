package com.simplereader.app.data.dao

import androidx.room.*
import com.simplereader.app.data.entity.BookGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: BookGroup): Long

    @Update
    suspend fun update(group: BookGroup)

    @Delete
    suspend fun delete(group: BookGroup)

    @Query("SELECT * FROM book_groups ORDER BY createTime ASC")
    fun getAllGroups(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups WHERE id = :id")
    suspend fun getGroup(id: Long): BookGroup?

    @Query("SELECT * FROM book_groups WHERE name = :name LIMIT 1")
    suspend fun getGroupByName(name: String): BookGroup?

    @Query("SELECT * FROM book_groups WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getGroupBySourceKey(sourceKey: String): BookGroup?

    @Query("UPDATE book_groups SET name = :newName, displayName = :newName, relativePath = NULL WHERE id = :id")
    suspend fun renameById(id: Long, newName: String): Int

    @Query("DELETE FROM book_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}
