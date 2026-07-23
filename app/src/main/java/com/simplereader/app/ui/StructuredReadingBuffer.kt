package com.simplereader.app.ui

/**
 * A small, ordered window of structured-book chapters. It keeps the previous,
 * current and next chapter in one scrollable string so crossing a chapter does
 * not discard the content the user just read.
 */
data class StructuredReadingBuffer(
    val centerChapterIndex: Int,
    val segments: List<Segment>,
    val content: String
) {
    data class Segment(
        val chapterIndex: Int,
        val start: Int,
        val endExclusive: Int
    ) {
        val length: Int get() = (endExclusive - start).coerceAtLeast(0)
    }

    data class Location(val chapterIndex: Int, val offset: Int)

    fun locationFor(position: Int): Location {
        if (segments.isEmpty()) return Location(centerChapterIndex, 0)
        val safePosition = position.coerceIn(0, content.length)
        val segment = segments.lastOrNull { safePosition >= it.start } ?: segments.first()
        return Location(segment.chapterIndex, (safePosition - segment.start).coerceIn(0, segment.length))
    }

    fun positionFor(chapterIndex: Int, offset: Int): Int? {
        val segment = segments.firstOrNull { it.chapterIndex == chapterIndex } ?: return null
        return segment.start + offset.coerceIn(0, segment.length)
    }

    fun chapterLength(chapterIndex: Int): Int =
        segments.firstOrNull { it.chapterIndex == chapterIndex }?.length ?: 0

    val firstChapterIndex: Int get() = segments.firstOrNull()?.chapterIndex ?: centerChapterIndex
    val lastChapterIndex: Int get() = segments.lastOrNull()?.chapterIndex ?: centerChapterIndex

    companion object {
        private const val SEPARATOR = "\n\n"

        fun build(centerChapterIndex: Int, chapters: List<Pair<Int, String>>): StructuredReadingBuffer {
            val ordered = chapters.sortedBy { it.first }
            val content = StringBuilder()
            val segments = mutableListOf<Segment>()
            ordered.forEachIndexed { position, (chapterIndex, chapterText) ->
                if (position > 0) content.append(SEPARATOR)
                val start = content.length
                content.append(chapterText)
                segments += Segment(chapterIndex, start, content.length)
            }
            return StructuredReadingBuffer(centerChapterIndex, segments, content.toString())
        }
    }
}
