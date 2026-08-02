package com.simplereader.app.ui

data class SearchPageHit(
    val keyword: String,
    val chapterIndex: Int,
    val globalPageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val previewText: String,
    val pageLabel: String
)
