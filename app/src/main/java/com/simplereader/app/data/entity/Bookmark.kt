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
    val globalPageIndex: Int? = null,
    val chapterIndex: Int? = null,
    val pageIndexInChapter: Int? = null,
    val startOffset: Int? = position.toIntOrNull(),
    val createTime: Long = System.currentTimeMillis()
)
