package com.simplereader.app.ui

/** Exact page snapshot consumed by the confirmed horizontal page-turn renderer. */
data class ReaderPageAnchor(
    val chapterIndex: Int,
    val chapterOffset: Int,
    val sourceOffset: Long = -1L
)

data class ReaderPageSnapshot(
    val startAnchor: ReaderPageAnchor,
    val endAnchor: ReaderPageAnchor,
    val content: CharSequence,
    val pageIndexInChapter: Int,
    val pageCountInChapter: Int
)
