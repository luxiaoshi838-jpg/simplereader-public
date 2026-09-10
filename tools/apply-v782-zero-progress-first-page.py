#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
reader_path = root / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
engine_path = root / "app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt"
gradle_path = root / "app/build.gradle.kts"

r = reader_path.read_text(encoding="utf-8")
e = engine_path.read_text(encoding="utf-8")
g = gradle_path.read_text(encoding="utf-8")

markers = (
    "private var zeroProgressOpeningPreviewVisible = false",
    "private suspend fun showZeroProgressFirstPageImmediately(): Int?",
    "PageEngine.layoutFirstPage(",
    "open_zero_progress_preview:applied",
)
engine_markers = (
    "data class FirstPageLayout(",
    "fun layoutFirstPage(",
    "FIRST_PAGE_PREVIEW_CHARS",
)
if all(m in r for m in markers) and all(m in e for m in engine_markers) and 'SIMPLE_READER_VERSION_NAME") ?: "782"' in g:
    print("v782 already applied: zero-progress first page opens immediately")
    raise SystemExit(0)

# 1) Runtime state: this flag means a display-only first-page snapshot is visible while
# the authoritative complete page table is being built silently.
old = "    private var shelfCacheClaimBookId: Long = 0L\n"
new = old + "    private var zeroProgressOpeningPreviewVisible = false\n"
if new not in r:
    if old not in r:
        raise SystemExit("v782: ReaderActivity state anchor missing")
    r = r.replace(old, new, 1)

# 2) Opening policy: exact/current-font cache -> compatible complete cache -> zero-progress
# first-page snapshot -> only then blocking pagination for non-zero uncached recovery cases.
old = """                val previewOffset = showCachedBookImmediately()\n                paginateAndDisplay(previewOffset, null, backgroundOpen = previewOffset != null)\n"""
new = """                val previewOffset = showCachedBookImmediately()\n                    ?: showZeroProgressFirstPageImmediately()\n                paginateAndDisplay(previewOffset, null, backgroundOpen = previewOffset != null)\n"""
if new not in r:
    if old not in r:
        raise SystemExit("v782: loadBook preview anchor missing")
    r = r.replace(old, new, 1)

# 3) Add a no-ReaderBook first-page display path. It never invents a local page list and
# therefore cannot become a second navigation truth or cause the historical window-edge jumps.
anchor = """        return restoreOffset\n    }\n\n    // Historical two-argument entry point stays intact for font-change and other callers.\n"""
insert = """        return restoreOffset\n    }\n\n    /**\n     * A never-paginated book at source offset 0 must still open like a normal reader. Build only\n     * the first visible page with the current typography and bind it directly to PagedReaderView.\n     * readerBook stays null until the authoritative complete page table is ready, so this preview\n     * can never be mistaken for a partial/global page sequence.\n     */\n    private suspend fun showZeroProgressFirstPageImmediately(): Int? {\n        val selectedBook = book ?: return null\n        val loaded = document ?: return null\n        val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }\n        if (!isZeroReadingProgress(progress)) return null\n        while (pagedReaderView.width <= 0 || pagedReaderView.height <= 0) {\n            if (isFinishing || isDestroyed || !ownsReaderSession()) return null\n            delay(16L)\n        }\n\n        val settings = createLayoutSettings()\n        val preview = withContext(Dispatchers.Default) {\n            PageEngine.layoutFirstPage(\n                text = loaded.text,\n                sourceChapters = loaded.chapters,\n                settings = settings,\n                typeface = Typeface.DEFAULT,\n                imageSpanProvider = { href, width, height -> imageRepository?.span(href, width, height) }\n            )\n        }\n        if (selectedBook.id != bookId || !ownsReaderSession() || isFinishing || isDestroyed) return null\n\n        val chapters = PageEngine.normalizeChapters(loaded.text, loaded.chapters)\n        val chapter = chapters.getOrNull(preview.chapterIndex) ?: chapters.first()\n        layoutSettings = settings\n        currentPageIndex = 0\n        lastStableSourceOffset = 0\n        zeroProgressOpeningPreviewVisible = true\n\n        verticalRecyclerView?.apply { stopScroll(); visibility = View.GONE }\n        readerScrollView.visibility = View.GONE\n        readerTopHaze.visibility = View.GONE\n        readerBottomHaze.visibility = View.GONE\n        configurePagedReaderStyle()\n        pagedReaderView.bind(\n            previous = null,\n            current = ReaderPageSnapshot(\n                startAnchor = ReaderPageAnchor(\n                    preview.chapterIndex,\n                    preview.startOffset - chapter.startOffset,\n                    preview.startOffset.toLong()\n                ),\n                endAnchor = ReaderPageAnchor(\n                    preview.chapterIndex,\n                    preview.endOffset - chapter.startOffset,\n                    preview.endOffset.toLong()\n                ),\n                content = preview.content,\n                pageIndexInChapter = 0,\n                pageCountInChapter = 1\n            ),\n            next = null\n        )\n        pagedReaderView.visibility = View.VISIBLE\n        progressLabel.text = "1/…"\n        CrashLogStore.recordEvent(\n            this,\n            "open_zero_progress_preview:applied book=$bookId anchor=0 end=${preview.endOffset} size=$readerTextSizeSp"\n        )\n        return 0\n    }\n\n    private fun isZeroReadingProgress(progress: ReadProgress?): Boolean {\n        if (progress == null) return true\n        val offsets = listOfNotNull(\n            progress.startOffset,\n            progress.txtCharOffset,\n            progress.position.toIntOrNull(),\n            progress.globalPageIndex,\n            progress.chapterIndex,\n            progress.pageIndexInChapter\n        )\n        return offsets.all { it <= 0 } && (progress.epubProgressFraction ?: 0f) <= 0f\n    }\n\n    // Historical two-argument entry point stays intact for font-change and other callers.\n"""
if "private suspend fun showZeroProgressFirstPageImmediately(): Int?" not in r:
    if anchor not in r:
        raise SystemExit("v782: opening-preview insertion anchor missing")
    r = r.replace(anchor, insert, 1)

# 4) Exact background commit owns navigation again and clears the temporary state.
old = """                readerBook = paged\n                layoutSettings = settings\n                pendingFontRollback = null\n"""
new = """                readerBook = paged\n                layoutSettings = settings\n                pendingFontRollback = null\n                zeroProgressOpeningPreviewVisible = false\n"""
if new not in r:
    if old not in r:
        raise SystemExit("v782: exact commit anchor missing")
    r = r.replace(old, new, 1)

# 5) A silent background failure must never tear down an already-visible first page.
old = """                if (backgroundOpen && readerBook != null) {\n"""
new = """                if (backgroundOpen && (readerBook != null || zeroProgressOpeningPreviewVisible)) {\n"""
if new not in r:
    if old not in r:
        raise SystemExit("v782: background failure guard anchor missing")
    r = r.replace(old, new, 1)

# 6) Appearance changes can restyle the direct first-page snapshot without needing ReaderBook.
old = """        if (rebindPages && readerBook != null) {\n            if (pageTurnMode == TURN_MODE_VERTICAL) showContinuousBook() else showHorizontalBook()\n        }\n"""
new = """        if (rebindPages && readerBook != null) {\n            if (pageTurnMode == TURN_MODE_VERTICAL) showContinuousBook() else showHorizontalBook()\n        } else if (rebindPages && zeroProgressOpeningPreviewVisible) {\n            configurePagedReaderStyle()\n        }\n"""
if new not in r:
    if old not in r:
        raise SystemExit("v782: appearance preview anchor missing")
    r = r.replace(old, new, 1)

# 7) First-page layout primitive. It shapes only a bounded prefix and returns a display slice,
# not ReaderBook/pages. Its first page must match the same StaticLayout rules as full pagination.
old = """data class ReaderPage(\n    val globalPageIndex: Int,\n    val totalPageCount: Int,\n    val chapterIndex: Int,\n    val pageIndexInChapter: Int,\n    val chapterPageCount: Int,\n    val startOffset: Int,\n    val endOffset: Int\n)\n\n"""
new = old + """data class FirstPageLayout(\n    val chapterIndex: Int,\n    val startOffset: Int,\n    val endOffset: Int,\n    val content: CharSequence\n)\n\n"""
if "data class FirstPageLayout(" not in e:
    if old not in e:
        raise SystemExit("v782: PageEngine data-class anchor missing")
    e = e.replace(old, new, 1)

anchor = """object PageEngine {\n    fun paginate(\n"""
method = """object PageEngine {\n    fun layoutFirstPage(\n        text: String,\n        sourceChapters: List<BookChapter>,\n        settings: ReaderLayoutSettings,\n        typeface: Typeface = Typeface.DEFAULT,\n        imageSpanProvider: ImageSpanProvider? = null\n    ): FirstPageLayout {\n        val chapters = normalizeChapters(text, sourceChapters)\n        val chapterIndex = 0\n        val chapter = chapters[chapterIndex]\n        if (text.isEmpty() || chapter.endOffset <= chapter.startOffset) {\n            return FirstPageLayout(chapterIndex, chapter.startOffset, chapter.startOffset, "")\n        }\n\n        val start = chapter.startOffset\n        var windowEnd = (start + FIRST_PAGE_PREVIEW_CHARS).coerceAtMost(chapter.endOffset)\n        if (windowEnd > start && windowEnd < text.length && Character.isHighSurrogate(text[windowEnd - 1])) {\n            windowEnd -= 1\n        }\n        windowEnd = windowEnd.coerceAtLeast((start + 1).coerceAtMost(chapter.endOffset))\n        val windowText = text.substring(start, windowEnd)\n        val styled = styledText(\n            text = windowText,\n            settings = settings,\n            titleStartsAtZero = start == chapter.startOffset,\n            imageSpanProvider = imageSpanProvider\n        )\n        if (styled.isEmpty()) return FirstPageLayout(chapterIndex, start, start, "")\n\n        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {\n            textSize = settings.textSizePx\n            this.typeface = typeface\n        }\n        val layout = StaticLayout.Builder\n            .obtain(styled, 0, styled.length, paint, settings.textWidthPx)\n            .setAlignment(Layout.Alignment.ALIGN_NORMAL)\n            .setIncludePad(false)\n            .setLineSpacing(settings.lineSpacingExtraPx, settings.lineSpacingMultiplier)\n            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)\n            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)\n            .build()\n        if (layout.lineCount <= 0) {\n            val end = (start + 1).coerceAtMost(windowEnd)\n            return FirstPageLayout(chapterIndex, start, end, SpannableString(text.substring(start, end)))\n        }\n\n        val pageTop = layout.getLineTop(0)\n        var lastLine = 0\n        while (lastLine + 1 < layout.lineCount) {\n            val candidateBottom = layout.getLineBottom(lastLine + 1)\n            if (candidateBottom - pageTop > settings.textHeightPx) break\n            lastLine += 1\n        }\n        val localStart = layout.getLineStart(0).coerceIn(0, styled.length)\n        var localEnd = layout.getLineEnd(lastLine).coerceIn(localStart, styled.length)\n        if (localEnd == localStart && localEnd < styled.length) localEnd += 1\n        val absoluteStart = (start + localStart).coerceIn(chapter.startOffset, chapter.endOffset)\n        val absoluteEnd = (start + localEnd).coerceIn(absoluteStart, chapter.endOffset)\n        return FirstPageLayout(\n            chapterIndex = chapterIndex,\n            startOffset = absoluteStart,\n            endOffset = absoluteEnd,\n            content = SpannableString(styled.subSequence(localStart, localEnd))\n        )\n    }\n\n    fun paginate(\n"""
if "fun layoutFirstPage(" not in e:
    if anchor not in e:
        raise SystemExit("v782: PageEngine method anchor missing")
    e = e.replace(anchor, method, 1)

old = """    private const val MAX_LAYOUT_WINDOW_CHARS = 160_000\n"""
new = """    private const val FIRST_PAGE_PREVIEW_CHARS = 16_384\n    private const val MAX_LAYOUT_WINDOW_CHARS = 160_000\n"""
if "FIRST_PAGE_PREVIEW_CHARS" not in e.split("private const val", 1)[-1]:
    if old not in e:
        raise SystemExit("v782: PageEngine constant anchor missing")
    e = e.replace(old, new, 1)

# 8) Version bump.
g = g.replace('SIMPLE_READER_VERSION_CODE") ?: "2098000780"', 'SIMPLE_READER_VERSION_CODE") ?: "2098000782"')
g = g.replace('?: 2098000780', '?: 2098000782')
g = g.replace('SIMPLE_READER_VERSION_NAME") ?: "780"', 'SIMPLE_READER_VERSION_NAME") ?: "782"')
# In case source-v781 was already bumped by its workflow, handle those persisted defaults too.
g = g.replace('SIMPLE_READER_VERSION_CODE") ?: "2098000781"', 'SIMPLE_READER_VERSION_CODE") ?: "2098000782"')
g = g.replace('?: 2098000781', '?: 2098000782')
g = g.replace('SIMPLE_READER_VERSION_NAME") ?: "781"', 'SIMPLE_READER_VERSION_NAME") ?: "782"')

reader_path.write_text(r, encoding="utf-8")
engine_path.write_text(e, encoding="utf-8")
gradle_path.write_text(g, encoding="utf-8")
print("v782 applied: zero-progress/no-page-cache books display page 1 immediately; full pagination stays silent")
