package com.simplereader.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val position: String,
    val content: String = "",
    val createTime: Long = System.currentTimeMillis()
)
