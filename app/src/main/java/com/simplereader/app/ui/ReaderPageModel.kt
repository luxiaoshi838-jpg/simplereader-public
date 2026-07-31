package com.simplereader.app.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.LinkedHashMap

/** Stable logical position used by every paged turn mode. */
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

data class ReaderLayoutSignature(
    val widthPx: Int,
    val heightPx: Int,
    val textSizePx: Int,
    val lineSpacingMultiplierX100: Int,
    val horizontalPaddingPx: Int,
    val verticalPaddingPx: Int
)

/** Small LRU cache: previous, current and next chapter page lists. */
class ReaderPageCache(private val maxChapters: Int = 3) {
    private data class Key(val chapterIndex: Int, val signature: ReaderLayoutSignature)

    private val pages = object : LinkedHashMap<Key, List<ReaderPageSnapshot>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, List<ReaderPageSnapshot>>?
        ): Boolean = size > maxChapters
    }

    @Synchronized
    fun get(chapterIndex: Int, signature: ReaderLayoutSignature): List<ReaderPageSnapshot>? =
        pages[Key(chapterIndex, signature)]

    @Synchronized
    fun put(
        chapterIndex: Int,
        signature: ReaderLayoutSignature,
        value: List<ReaderPageSnapshot>
    ) {
        pages[Key(chapterIndex, signature)] = value
    }

    @Synchronized
    fun clear() = pages.clear()
}

/**
 * Real screen-layout pagination. It uses the same width, height, text size and
 * line spacing as the visible page instead of a fixed character count.
 */
object ReaderTextPaginator {
    fun paginate(
        chapterIndex: Int,
        text: CharSequence,
        signature: ReaderLayoutSignature,
        typeface: Typeface? = Typeface.DEFAULT,
        lineSpacingMultiplier: Float = 1.75f,
        sourceOffsetForCharacter: (Int) -> Long = { -1L }
    ): List<ReaderPageSnapshot> {
        if (text.isEmpty()) {
            val anchor = ReaderPageAnchor(chapterIndex, 0, sourceOffsetForCharacter(0))
            return listOf(ReaderPageSnapshot(anchor, anchor, "", 0, 1))
        }

        val contentWidth = (signature.widthPx - signature.horizontalPaddingPx * 2)
            .coerceAtLeast(1)
        val contentHeight = (signature.heightPx - signature.verticalPaddingPx * 2)
            .coerceAtLeast(1)
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = signature.textSizePx.toFloat()
            this.typeface = typeface
        }

        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start < text.length) {
            val layout = StaticLayout.Builder.obtain(text, start, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            var lastLine = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= contentHeight) {
                    lastLine = line
                } else {
                    break
                }
            }
            val end = when {
                lastLine >= 0 -> layout.getLineEnd(lastLine)
                else -> (start + 1).coerceAtMost(text.length)
            }.coerceIn(start + 1, text.length)

            ranges += start until end
            start = end
        }

        val pageCount = ranges.size.coerceAtLeast(1)
        return ranges.mapIndexed { index, range ->
            val startOffset = range.first
            val endOffset = range.last + 1
            ReaderPageSnapshot(
                startAnchor = ReaderPageAnchor(
                    chapterIndex = chapterIndex,
                    chapterOffset = startOffset,
                    sourceOffset = sourceOffsetForCharacter(startOffset)
                ),
                endAnchor = ReaderPageAnchor(
                    chapterIndex = chapterIndex,
                    chapterOffset = endOffset,
                    sourceOffset = sourceOffsetForCharacter(endOffset)
                ),
                content = text.subSequence(startOffset, endOffset),
                pageIndexInChapter = index,
                pageCountInChapter = pageCount
            )
        }
    }
}
