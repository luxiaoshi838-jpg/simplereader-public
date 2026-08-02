package com.simplereader.app.reader.page

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import com.simplereader.app.parser.EpubParser
import com.simplereader.app.parser.TxtParser
import java.security.MessageDigest
import kotlin.math.max

typealias ImageSpanProvider = (href: String, maxWidthPx: Int, maxHeightPx: Int) -> ReplacementSpan?

/** One immutable page in the single page sequence used by every reader feature. */
data class ReaderPage(
    val globalPageIndex: Int,
    val totalPageCount: Int,
    val chapterIndex: Int,
    val pageIndexInChapter: Int,
    val chapterPageCount: Int,
    val startOffset: Int,
    val endOffset: Int
)

/** Normalized chapter model shared by TXT and EPUB. Offsets are UTF-16 indices in [ReaderBook.text]. */
data class BookChapter(
    val title: String,
    val startOffset: Int,
    val endOffset: Int,
    val sourceHref: String? = null,
    val catalogVisible: Boolean = true
)

data class ReaderLayoutSettings(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val contentPaddingLeftPx: Int,
    val contentPaddingTopPx: Int,
    val contentPaddingRightPx: Int,
    val contentPaddingBottomPx: Int,
    val textSizePx: Float,
    val typefaceKey: String,
    val lineSpacingExtraPx: Float,
    val lineSpacingMultiplier: Float,
    val titleScale: Float = 1.12f
) {
    val textWidthPx: Int
        get() = (viewportWidthPx - contentPaddingLeftPx - contentPaddingRightPx).coerceAtLeast(1)

    val textHeightPx: Int
        get() = (viewportHeightPx - contentPaddingTopPx - contentPaddingBottomPx).coerceAtLeast(1)

    fun stableHash(): String {
        val raw = listOf(
            CACHE_MODEL_VERSION,
            viewportWidthPx,
            viewportHeightPx,
            contentPaddingLeftPx,
            contentPaddingTopPx,
            contentPaddingRightPx,
            contentPaddingBottomPx,
            textSizePx,
            typefaceKey,
            lineSpacingExtraPx,
            lineSpacingMultiplier,
            titleScale
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CACHE_MODEL_VERSION = 4
    }
}

data class ReaderBook(
    val text: String,
    val chapters: List<BookChapter>,
    val pages: List<ReaderPage>,
    val settingsHash: String
) {
    fun pageAt(index: Int): ReaderPage = pages[index.coerceIn(0, pages.lastIndex)]

    fun pageForOffset(offset: Int): ReaderPage {
        if (pages.isEmpty()) error("No pages")
        val safe = offset.coerceIn(0, text.length)
        var low = 0
        var high = pages.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val page = pages[mid]
            when {
                safe < page.startOffset -> high = mid - 1
                safe >= page.endOffset && mid < pages.lastIndex -> {
                    answer = mid
                    low = mid + 1
                }
                else -> return page
            }
        }
        return pages[answer.coerceIn(0, pages.lastIndex)]
    }

    fun firstPageOfChapter(chapterIndex: Int): Int =
        pages.firstOrNull { it.chapterIndex == chapterIndex }?.globalPageIndex
            ?: pages.lastIndex.coerceAtLeast(0)

    fun chapterForOffset(offset: Int): Int {
        if (chapters.isEmpty()) return 0
        val safe = offset.coerceIn(0, text.length)
        return chapters.indexOfLast { safe >= it.startOffset }
            .coerceAtLeast(0)
            .coerceAtMost(chapters.lastIndex)
    }
}

/**
 * Real layout paginator. It asks Android's StaticLayout to shape the exact text using the
 * active width, height, font size, line spacing, title style and EPUB image spans.
 * Chapters are laid out independently, so a short final page is still a complete page.
 */
object PageEngine {
    fun paginate(
        text: String,
        sourceChapters: List<BookChapter>,
        settings: ReaderLayoutSettings,
        typeface: Typeface = Typeface.DEFAULT,
        imageSpanProvider: ImageSpanProvider? = null
    ): ReaderBook {
        val chapters = normalizeChapters(text, sourceChapters)
        val draftPages = mutableListOf<DraftPage>()
        chapters.forEachIndexed { chapterIndex, chapter ->
            val chapterText = text.substring(chapter.startOffset, chapter.endOffset)
            val styled = styledText(
                text = chapterText,
                settings = settings,
                titleStartsAtZero = true,
                imageSpanProvider = imageSpanProvider
            )
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = settings.textSizePx
                this.typeface = typeface
            }
            val layout = StaticLayout.Builder
                .obtain(styled, 0, styled.length, paint, settings.textWidthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(settings.lineSpacingExtraPx, settings.lineSpacingMultiplier)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build()

            val chapterPages = mutableListOf<Pair<Int, Int>>()
            if (styled.isEmpty() || layout.lineCount == 0) {
                chapterPages += 0 to 0
            } else {
                var firstLine = 0
                while (firstLine < layout.lineCount) {
                    val pageTop = layout.getLineTop(firstLine)
                    var lastLine = firstLine
                    while (lastLine + 1 < layout.lineCount) {
                        val candidateBottom = layout.getLineBottom(lastLine + 1)
                        if (candidateBottom - pageTop > settings.textHeightPx) break
                        lastLine += 1
                    }
                    val localStart = layout.getLineStart(firstLine).coerceIn(0, styled.length)
                    var localEnd = layout.getLineEnd(lastLine).coerceIn(localStart, styled.length)
                    if (localEnd == localStart && localEnd < styled.length) localEnd += 1
                    chapterPages += localStart to localEnd
                    firstLine = lastLine + 1
                }
            }
            val chapterPageCount = chapterPages.size.coerceAtLeast(1)
            chapterPages.forEachIndexed { pageInChapter, range ->
                draftPages += DraftPage(
                    chapterIndex = chapterIndex,
                    pageIndexInChapter = pageInChapter,
                    chapterPageCount = chapterPageCount,
                    startOffset = (chapter.startOffset + range.first).coerceIn(chapter.startOffset, chapter.endOffset),
                    endOffset = (chapter.startOffset + range.second).coerceIn(chapter.startOffset, chapter.endOffset)
                )
            }
        }

        val total = draftPages.size.coerceAtLeast(1)
        val pages = if (draftPages.isEmpty()) {
            listOf(ReaderPage(0, 1, 0, 0, 1, 0, text.length))
        } else {
            draftPages.mapIndexed { index, page ->
                ReaderPage(
                    globalPageIndex = index,
                    totalPageCount = total,
                    chapterIndex = page.chapterIndex,
                    pageIndexInChapter = page.pageIndexInChapter,
                    chapterPageCount = page.chapterPageCount,
                    startOffset = page.startOffset,
                    endOffset = page.endOffset
                )
            }
        }
        return ReaderBook(text, chapters, pages, settings.stableHash())
    }

    fun styledText(
        text: String,
        settings: ReaderLayoutSettings,
        titleStartsAtZero: Boolean,
        imageSpanProvider: ImageSpanProvider? = null
    ): CharSequence {
        if (text.isEmpty()) return text
        val styled = SpannableString(text)
        if (titleStartsAtZero) {
            val titleEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
            if (titleEnd > 0) {
                styled.setSpan(
                    RelativeSizeSpan(settings.titleScale),
                    0,
                    titleEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                styled.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    titleEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (imageSpanProvider != null) {
            IMAGE_MARKER.findAll(text).forEach { match ->
                val href = match.groupValues.getOrNull(1).orEmpty()
                val span = runCatching {
                    imageSpanProvider(href, settings.textWidthPx, settings.textHeightPx)
                }.getOrNull() ?: return@forEach
                styled.setSpan(
                    span,
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return styled
    }


    /**
     * One continuous vertical document. It never introduces page containers or page gaps.
     * Only real chapter starts receive title styling and extra top spacing. Text offsets stay unchanged.
     */
    fun styledWholeText(
        text: String,
        chapters: List<BookChapter>,
        settings: ReaderLayoutSettings,
        imageSpanProvider: ImageSpanProvider? = null
    ): CharSequence {
        if (text.isEmpty()) return text
        val styled = SpannableString(text)
        chapters.forEachIndexed { index, chapter ->
            val start = chapter.startOffset.coerceIn(0, text.length)
            if (start >= text.length) return@forEachIndexed
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (lineEnd <= start || text.substring(start, lineEnd).isBlank()) return@forEachIndexed
            styled.setSpan(
                RelativeSizeSpan(settings.titleScale),
                start,
                lineEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                lineEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (index > 0) {
                styled.setSpan(
                    ChapterTopSpacingSpan((settings.textSizePx * settings.lineSpacingMultiplier * 1.8f).toInt()),
                    start,
                    lineEnd.coerceAtLeast(start + 1),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (imageSpanProvider != null) {
            IMAGE_MARKER.findAll(text).forEach { match ->
                val href = match.groupValues.getOrNull(1).orEmpty()
                val span = runCatching {
                    imageSpanProvider(href, settings.textWidthPx, settings.textHeightPx)
                }.getOrNull() ?: return@forEach
                styled.setSpan(
                    span,
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return styled
    }

    fun normalizeChapters(text: String, input: List<BookChapter>): List<BookChapter> {
        if (text.isEmpty()) return listOf(BookChapter("正文", 0, 0))
        val sanitized = input.mapNotNull { chapter ->
            val start = chapter.startOffset.coerceIn(0, text.length)
            val end = chapter.endOffset.coerceIn(start, text.length)
            if (end <= start) null else chapter.copy(startOffset = start, endOffset = end)
        }.sortedBy { it.startOffset }
            .distinctBy { it.startOffset }
            .toMutableList()

        if (sanitized.isEmpty()) return listOf(BookChapter("正文", 0, text.length))
        if (sanitized.first().startOffset > 0) {
            sanitized.add(0, BookChapter("正文", 0, sanitized.first().startOffset, catalogVisible = false))
        }
        return sanitized.mapIndexed { index, chapter ->
            val nextStart = sanitized.getOrNull(index + 1)?.startOffset ?: text.length
            // Chapter boundaries own the full interval up to the next chapter. This prevents
            // separators or trailing text from disappearing and guarantees stable catalog jumps.
            chapter.copy(endOffset = max(chapter.startOffset, nextStart))
        }.filter { it.endOffset > it.startOffset }
            .ifEmpty { listOf(BookChapter("正文", 0, text.length)) }
    }

    private class ChapterTopSpacingSpan(private val extraTopPx: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence?,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: android.graphics.Paint.FontMetricsInt
        ) {
            if (extraTopPx <= 0) return
            fm.ascent -= extraTopPx
            fm.top -= extraTopPx
        }
    }

    private data class DraftPage(
        val chapterIndex: Int,
        val pageIndexInChapter: Int,
        val chapterPageCount: Int,
        val startOffset: Int,
        val endOffset: Int
    )

    private val IMAGE_MARKER = Regex(
        Regex.escape(EpubParser.IMAGE_MARKER_PREFIX) + "([^\\]]+)" + Regex.escape(EpubParser.IMAGE_MARKER_SUFFIX)
    )
}

/** Kept for source compatibility with the v607 streaming prototype; no UI uses it for page numbers. */
data class ReaderPageLocator(
    val pageIndex: Long,
    val byteOffset: Long,
    val chapterIndex: Int? = null,
    val chapterOffset: Int? = null
)

/** Legacy window helpers retained only for the safe fallback reader path. */
data class ReaderPageBlock(
    val index: Long,
    val startByte: Long,
    val endByte: Long,
    val text: String
) {
    val isReadable: Boolean get() = text.isNotEmpty() && endByte > startByte
}

data class ReaderPageWindow(
    val blocks: List<ReaderPageBlock>,
    val centerIndex: Long,
    val targetByte: Long
) {
    val text: String = blocks.joinToString(separator = "") { it.text }
    val startByte: Long = blocks.firstOrNull()?.startByte ?: targetByte
    val endByte: Long = blocks.lastOrNull()?.endByte ?: targetByte

    fun byteForScroll(scrollY: Int, maxScroll: Int): Long {
        if (maxScroll <= 0 || blocks.isEmpty()) return targetByte
        val fraction = (scrollY.toDouble() / maxScroll.toDouble()).coerceIn(0.0, 1.0)
        return (startByte + ((endByte - startByte).coerceAtLeast(1L) * fraction))
            .toLong().coerceIn(startByte, endByte)
    }

    fun fractionForByte(byteOffset: Long): Float {
        val span = (endByte - startByte).coerceAtLeast(1L)
        return ((byteOffset.coerceIn(startByte, endByte) - startByte).toDouble() / span.toDouble())
            .toFloat().coerceIn(0f, 1f)
    }
}

object TxtPageEngine {
    fun pageIndexForByte(byteOffset: Long, pageBytes: Int): Long =
        byteOffset.coerceAtLeast(0L) / pageBytes.coerceAtLeast(1).toLong()

    fun blockFromWindow(index: Long, window: com.simplereader.app.parser.TxtWindowResult): ReaderPageBlock =
        ReaderPageBlock(index, window.startByte, window.nextByte, window.text)

    fun windowFromBlocks(
        targetByte: Long,
        pageBytes: Int,
        blocks: List<ReaderPageBlock>
    ): ReaderPageWindow = ReaderPageWindow(
        blocks = blocks.filter { it.isReadable }
            .distinctBy { it.startByte to it.endByte }
            .sortedBy { it.startByte },
        centerIndex = pageIndexForByte(targetByte, pageBytes),
        targetByte = targetByte
    )
}
