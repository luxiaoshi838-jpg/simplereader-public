package com.simplereader.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val filePath: String,
    val format: String, // TXT or EPUB
    val groupId: Long? = null,
    val lastReadTime: Long = 0,
    val addTime: Long = System.currentTimeMillis(),
    val cover: String? = null,
    @ColumnInfo(defaultValue = "")
    val fileName: String = title,
    val fileSize: Long? = null,
    val lastModified: Long? = null,
    val sourceTreeUri: String? = null,
    val relativePath: String? = null,
    @ColumnInfo(defaultValue = "AVAILABLE")
    val fileStatus: String = "AVAILABLE",
    val txtCharset: String? = null
)
