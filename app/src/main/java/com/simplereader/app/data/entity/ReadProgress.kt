package com.simplereader.app.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "read_progress")
data class ReadProgress(
    @PrimaryKey
    val bookId: Long,
    val position: String,
    @ColumnInfo(defaultValue = "TXT")
    val locatorType: String = "TXT",
    val txtCharOffset: Int? = position.toIntOrNull(),
    val txtTotalLength: Int? = null,
    val epubSpineIndex: Int? = null,
    val epubChapterHref: String? = null,
    val epubChapterOffset: Int? = null,
    val epubProgressFraction: Float? = null,
    val updateTime: Long = System.currentTimeMillis()
)
