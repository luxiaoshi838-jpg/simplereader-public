package com.simplereader.app.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.LinkedHashMap
import kotlin.math.abs

/** Last measured reader viewport. Display metrics are only a pre-layout fallback. */
internal object ReaderViewportMetrics {
    @Volatile
    private var measuredWidthPx: Int = 0

    @Volatile
    private var measuredHeightPx: Int = 0

    @Synchronized
    fun record(widthPx: Int, heightPx: Int) {
        if (widthPx > 0) measuredWidthPx = widthPx
        if (heightPx > 0) measuredHeightPx = heightPx
    }

    fun resolveWidth(fallbackPx: Int): Int =
        measuredWidthPx.takeIf { it > 0 } ?: fallbackPx.coerceAtLeast(1)

    fun resolveHeight(fallbackPx: Int): Int =
        measuredHeightPx.takeIf { it > 0 } ?: fallbackPx.coerceAtLeast(1)

    @Synchronized
    internal fun resetForTests() {
        measuredWidthPx = 0
        measuredHeightPx = 0
    }
}

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
    val topPaddingPx: Int,
    val bottomPaddingPx: Int,
    val chapterTitleScaleX100: Int = 130,
    /** Distinguishes streaming TXT windows without coupling cache identity to turn mode. */
    val contentKey: Long = 0L,
    /** Actual reader container size captured when the signature is created. */
    val viewportWidthPx: Int = ReaderViewportMetrics.resolveWidth(widthPx),
    val viewportHeightPx: Int = ReaderViewportMetrics.resolveHeight(heightPx)
) {
    fun stableKey(): String = listOf(
        viewportWidthPx,
        viewportHeightPx,
        textSizePx,
        lineSpacingMultiplierX100,
        horizontalPaddingPx,
        topPaddingPx,
        bottomPaddingPx,
        chapterTitleScaleX100,
        contentKey
    ).joinToString(":")
}

/**
 * Bounded page cache for the interactive chapter window.
 *
 * The first read after every [put] belongs to page-index registration: the caller
 * immediately asks the cache for the just-built pages so it can persist exact page
 * starts. Background whole-book indexing therefore needs a temporary readable slot,
 * but it must never evict the chapters around the visible page. Near chapters are
 * retained in a three-entry window; one far temporary slot is overwritten as the
 * background index advances. A temporary chapter is promoted only when it is read
 * again later by real navigation.
 */
class ReaderPageCache(private val maxChapters: Int = 3) {
    private data class Key(val chapterIndex: Int, val signature: ReaderLayoutSignature)

    private data class Entry(
        var pages: List<ReaderPageSnapshot>,
        var registrationReadPending: Boolean
    )

    private val promoted = LinkedHashMap<Key, Entry>(8, 0.75f, true)
    private val near = LinkedHashMap<Key, Entry>(8, 0.75f, true)
    private var far: Pair<Key, Entry>? = null
    private var focus: Key? = null

    @Synchronized
    fun get(chapterIndex: Int, signature: ReaderLayoutSignature): List<ReaderPageSnapshot>? {
        val key = Key(chapterIndex, signature)
        promoted[key]?.let { entry ->
            if (entry.registrationReadPending) {
                entry.registrationReadPending = false
            } else if (isNearFocus(key)) {
                moveFocus(key)
            }
            return entry.pages
        }

        near[key]?.let { entry ->
            if (entry.registrationReadPending) {
                entry.registrationReadPending = false
                return entry.pages
            }
            near.remove(key)
            promote(key, entry)
            return entry.pages
        }

        val farEntry = far
        if (farEntry?.first == key) {
            val entry = farEntry.second
            if (entry.registrationReadPending) {
                entry.registrationReadPending = false
                return entry.pages
            }
            far = null
            promote(key, entry)
            return entry.pages
        }
        return null
    }

    @Synchronized
    fun put(
        chapterIndex: Int,
        signature: ReaderLayoutSignature,
        value: List<ReaderPageSnapshot>
    ) {
        val key = Key(chapterIndex, signature)
        promoted[key]?.let { entry ->
            entry.pages = value
            entry.registrationReadPending = true
            return
        }
        near[key]?.let { entry ->
            entry.pages = value
            entry.registrationReadPending = true
            return
        }
        if (far?.first == key) {
            far = key to Entry(value, registrationReadPending = true)
            return
        }

        if (focus == null) focus = key
        val entry = Entry(value, registrationReadPending = true)
        if (isNearFocus(key)) {
            near[key] = entry
            trimNearWindow()
        } else {
            // Whole-book indexing advances through distant chapters. Only its latest
            // result must remain readable long enough for exact start registration.
            far = key to entry
        }
    }

    @Synchronized
    fun clear() {
        promoted.clear()
        near.clear()
        far = null
        focus = null
    }

    private fun promote(key: Key, entry: Entry) {
        entry.registrationReadPending = false
        promoted[key] = entry
        moveFocus(key)
        trimPromoted()
    }

    private fun moveFocus(key: Key) {
        focus = key
        trimNearWindow()
        trimPromoted()
    }

    private fun isNearFocus(key: Key): Boolean {
        val current = focus ?: return true
        return current.signature == key.signature &&
            abs(current.chapterIndex - key.chapterIndex) <= 1
    }

    private fun trimNearWindow() {
        val current = focus ?: return
        val iterator = near.entries.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next().key
            if (key.signature != current.signature || abs(key.chapterIndex - current.chapterIndex) > 1) {
                iterator.remove()
            }
        }
        while (near.size > maxChapters) {
            val victim = near.keys.maxByOrNull { key -> abs(key.chapterIndex - current.chapterIndex) }
                ?: break
            near.remove(victim)
        }
    }

    private fun trimPromoted() {
        val current = focus ?: return
        while (promoted.size > maxChapters) {
            val victim = promoted.keys
                .filterNot { it == current }
                .maxByOrNull { key ->
                    if (key.signature == current.signature) {
                        abs(key.chapterIndex - current.chapterIndex)
                    } else {
                        Int.MAX_VALUE
                    }
                } ?: promoted.keys.firstOrNull() ?: break
            promoted.remove(victim)
        }
    }
}

/**
 * Real screen-layout pagination. It uses the same width, height, text size and
 * line spacing as the visible page instead of a fixed character count.
 */
object ReaderTextPaginator {
    /**
     * Builds one real Android text layout and cuts it into screen-height pages.
     * The old implementation rebuilt a layout for the whole remaining chapter
     * once per page, which was quadratic and delayed first open.
     */
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

        val contentWidth = (signature.viewportWidthPx - signature.horizontalPaddingPx * 2)
            .coerceAtLeast(1)
        val contentHeight = (
            signature.viewportHeightPx - signature.topPaddingPx - signature.bottomPaddingPx
        ).coerceAtLeast(1)
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = signature.textSizePx.toFloat()
            this.typeface = typeface
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        val ranges = mutableListOf<IntRange>()
        var firstLine = 0
        while (firstLine < layout.lineCount) {
            val pageTop = layout.getLineTop(firstLine)
            val pageBottom = pageTop + contentHeight
            var lastLine = firstLine
            while (
                lastLine + 1 < layout.lineCount &&
                layout.getLineBottom(lastLine + 1) <= pageBottom
            ) {
                lastLine += 1
            }
            val startOffset = layout.getLineStart(firstLine).coerceIn(0, text.length)
            val endOffset = layout.getLineEnd(lastLine)
                .coerceIn((startOffset + 1).coerceAtMost(text.length), text.length)
            ranges += startOffset until endOffset
            firstLine = lastLine + 1
        }

        if (ranges.isEmpty()) ranges += 0 until text.length
        val pageCount = ranges.size
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
