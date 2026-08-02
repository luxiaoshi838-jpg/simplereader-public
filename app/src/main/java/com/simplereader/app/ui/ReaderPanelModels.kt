package com.simplereader.app.ui

import com.simplereader.app.data.entity.Bookmark

data class CatalogPageRow(
    val title: String,
    val chapterIndex: Int,
    val globalPageIndex: Int,
    val pageLabel: String
)

data class BookmarkPageRow(
    val bookmark: Bookmark,
    val pageLabel: String
)

data class SearchPageHit(
    val keyword: String,
    val chapterIndex: Int,
    val globalPageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val previewText: String,
    val pageLabel: String
)
