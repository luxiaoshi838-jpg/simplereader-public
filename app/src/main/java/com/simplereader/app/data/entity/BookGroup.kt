package com.simplereader.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_groups",
    indices = [Index(value = ["sourceKey"], unique = true)]
)
data class BookGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    val displayName: String = name,
    val sourceTreeUri: String? = null,
    val relativePath: String? = null,
    @ColumnInfo(defaultValue = "")
    val sourceKey: String = name,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val isExpanded: Boolean = true
)
