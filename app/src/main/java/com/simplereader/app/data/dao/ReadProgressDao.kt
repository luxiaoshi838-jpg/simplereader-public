package com.simplereader.app.data.dao

import androidx.room.*
import com.simplereader.app.data.entity.ReadProgress

@Dao
interface ReadProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ReadProgress)

    @Query("SELECT * FROM read_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: Long): ReadProgress?

    @Delete
    suspend fun delete(progress: ReadProgress)
}
