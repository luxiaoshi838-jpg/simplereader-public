#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

def replace_once(path: Path, old: str, new: str, label: str):
    s = path.read_text(encoding='utf-8')
    if new in s:
        return
    if old not in s:
        raise SystemExit(f'v780 patch anchor missing: {label}')
    path.write_text(s.replace(old, new, 1), encoding='utf-8')

# Version bump 779 -> 780. Idempotent on already-persisted v780 source.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000779"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000780"')
s = s.replace('?: 2098000779', '?: 2098000780')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "779"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "780"')
gradle.write_text(s, encoding='utf-8')

reader = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = reader.read_text(encoding='utf-8')

# Every whole-book pagination gets an epoch. A newer font request, another book, or Activity death
# invalidates older work before it may commit anything back to the UI.
field_old = '    private var fontChangeRequestId: Long = 0L\n'
field_new = field_old + '    private var paginationEpoch: Long = 0L\n'
if 'private var paginationEpoch: Long = 0L' not in s:
    if field_old not in s:
        raise SystemExit('v780 pagination epoch field anchor missing')
    s = s.replace(field_old, field_new, 1)

destroy_old = '        fontChangeRequestId += 1L\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n'
destroy_new = '        fontChangeRequestId += 1L\n        paginationEpoch += 1L\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n'
if destroy_new not in s:
    if destroy_old not in s:
        raise SystemExit('v780 destroy epoch anchor missing')
    s = s.replace(destroy_old, destroy_new, 1)

old_font = '''    private fun changeTextSize(delta: Float) {
        stopAutoReading(false)
        val paged = readerBook ?: return
        val currentOffset = currentVisibleSourceOffset()
        val requested = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        if (requested == readerTextSizeSp) return

        // Restore the v754 contract: one stable rollback baseline per adjustment burst, a
        // source-character anchor, and only the latest delayed request may repaginate.
        fontChangeRunnable?.let(mainHandler::removeCallbacks)
        fontChangeRunnable = null
        fontChangeRequestId += 1L
        val requestId = fontChangeRequestId
        if (pendingFontRollback == null) {
            pendingFontRollback = FontRollback(
                readerTextSizeSp,
                paged,
                layoutSettings,
                currentPageIndex,
                currentOffset
            )
        }

        // If an earlier font pagination already started, cancel it immediately. Its
        // CancellationException is control flow and is explicitly excluded from failure UI.
        paginationJob?.cancel()
        readerTextSizeSp = requested
        applyReaderContentPadding()
        savePreferences()
        updateSettingsLabels()
        progressLabel.text = "字体分页中…"
        fontChangeRunnable = Runnable {
            fontChangeRunnable = null
            if (requestId != fontChangeRequestId || isFinishing || isDestroyed || !ownsReaderSession()) return@Runnable
            paginateAndDisplay(currentOffset)
        }.also { mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS) }
    }
'''
new_font = '''    private fun changeTextSize(delta: Float) {
        stopAutoReading(false)
        val paged = readerBook ?: return
        val currentOffset = currentVisibleSourceOffset()
        val requested = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        if (requested == readerTextSizeSp) return

        // Keep one stable rollback baseline for the whole adjustment burst. Page identity stays
        // on the existing complete ReaderBook until the new complete page table is ready.
        fontChangeRunnable?.let(mainHandler::removeCallbacks)
        fontChangeRunnable = null
        fontChangeRequestId += 1L
        val requestId = fontChangeRequestId
        if (pendingFontRollback == null) {
            pendingFontRollback = FontRollback(
                readerTextSizeSp,
                paged,
                layoutSettings,
                currentPageIndex,
                currentOffset
            )
        }

        // Supersede old whole-book work immediately. PageEngine now cooperatively observes this
        // cancellation, so changing books or adjusting size repeatedly cannot leave CPU-heavy stale
        // pagination running to completion.
        paginationEpoch += 1L
        paginationJob?.cancel()
        readerTextSizeSp = requested
        applyReaderContentPadding()
        savePreferences()
        updateSettingsLabels()

        // Public-reader pattern: apply the visual typography to the current stable page sequence
        // immediately. This is display-only: it never creates a partial ReaderBook, changes global
        // page identity, or replaces RecyclerView data with a local window.
        applyTransientFontPreview(currentOffset, requestId)

        // Rebuild the authoritative complete page table in the background after the adjustment
        // burst settles. Its commit is guarded by book/generation/request/epoch checks below.
        fontChangeRunnable = Runnable {
            fontChangeRunnable = null
            if (requestId != fontChangeRequestId || isFinishing || isDestroyed || !ownsReaderSession()) return@Runnable
            paginateAndDisplay(currentOffset, requestId)
        }.also { mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS) }
    }

    private fun applyTransientFontPreview(anchorOffset: Int, requestId: Long) {
        val paged = readerBook ?: return
        if (requestId != fontChangeRequestId || isFinishing || isDestroyed || !ownsReaderSession()) return
        val safeOffset = anchorOffset.coerceIn(0, paged.text.length)
        layoutSettings = createLayoutSettings()
        lastStableSourceOffset = safeOffset
        currentPageIndex = paged.pageForOffset(safeOffset).globalPageIndex

        if (pageTurnMode == TURN_MODE_VERTICAL) {
            ensureVerticalReader()
            val recycler = verticalRecyclerView
            recycler?.stopScroll()
            verticalProgrammaticScroll = true
            verticalAdapter?.refresh()
            verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)
            scheduleVerticalStateUnlockGuard()
            recycler?.postOnAnimation {
                if (requestId != fontChangeRequestId || readerBook !== paged || !ownsReaderSession()) {
                    verticalProgrammaticScroll = false
                    if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()
                    return@postOnAnimation
                }
                verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)
                verticalProgrammaticScroll = false
                if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()
            }
        } else {
            pagedReaderView.cancelNavigation()
            configurePagedReaderStyle()
            bindHorizontalPages()
        }
        updateProgressUi()
        CrashLogStore.recordEvent(
            this,
            "font_preview:applied book=$bookId request=$requestId size=$readerTextSizeSp anchor=$safeOffset page=$currentPageIndex"
        )
    }
'''
if 'private fun applyTransientFontPreview(anchorOffset: Int, requestId: Long)' not in s:
    if old_font not in s:
        raise SystemExit('v780 changeTextSize anchor missing')
    s = s.replace(old_font, new_font, 1)

sig_old = '    private fun paginateAndDisplay(preserveOffset: Int?) {\n'
sig_new = '    private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null) {\n'
if sig_new not in s:
    if sig_old not in s:
        raise SystemExit('v780 paginate signature anchor missing')
    s = s.replace(sig_old, sig_new, 1)

post_old = '            pagedReaderView.post { paginateAndDisplay(preserveOffset) }\n'
post_new = '            pagedReaderView.post { paginateAndDisplay(preserveOffset, fontRequestId) }\n'
if post_new not in s:
    if post_old not in s:
        raise SystemExit('v780 delayed paginate anchor missing')
    s = s.replace(post_old, post_new, 1)

start_old = '''        paginationJob?.cancel()
        paginationInProgress = true
        progressLabel.text = "分页中…"
        CrashLogStore.recordEvent(this, "paginate:start book=$bookId preserve=$preserveOffset stable=$lastStableSourceOffset")
        paginationJob = lifecycleScope.launch {
            try {
                val settings = createLayoutSettings()
'''
start_new = '''        paginationJob?.cancel()
        paginationEpoch += 1L
        val epoch = paginationEpoch
        val generation = readerGeneration
        val expectedBookId = selectedBook.id
        paginationInProgress = true
        if (fontRequestId == null) progressLabel.text = "分页中…"
        CrashLogStore.recordEvent(this, "paginate:start book=$bookId preserve=$preserveOffset fontRequest=$fontRequestId epoch=$epoch stable=$lastStableSourceOffset")
        paginationJob = lifecycleScope.launch {
            val runningJob = coroutineContext[Job]
            try {
                val settings = createLayoutSettings()
'''
if start_new not in s:
    if start_old not in s:
        raise SystemExit('v780 paginate start anchor missing')
    s = s.replace(start_old, start_new, 1)

paginate_call_old = '''                val paged = cached ?: withContext(Dispatchers.Default) {
                    PageEngine.paginate(
                        loaded.text,
                        loaded.chapters,
                        settings,
                        Typeface.DEFAULT
                    ) { href, width, height -> imageRepository?.span(href, width, height) }
                }
                readerBook = paged
                pendingFontRollback = null
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val stableOffset = preserveOffset
                    ?: lastStableSourceOffset
                    ?: CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)
                    ?: progress?.startOffset
                    ?: progress?.txtCharOffset
                    ?: progress?.position?.toIntOrNull()
'''
paginate_call_new = '''                val paged = cached ?: withContext(Dispatchers.Default) {
                    PageEngine.paginate(
                        text = loaded.text,
                        sourceChapters = loaded.chapters,
                        settings = settings,
                        typeface = Typeface.DEFAULT,
                        imageSpanProvider = { href, width, height -> imageRepository?.span(href, width, height) },
                        shouldCancel = {
                            runningJob?.isActive != true ||
                                paginationEpoch != epoch ||
                                !ReaderRuntimeState.isOwner(generation)
                        }
                    )
                }

                if (
                    epoch != paginationEpoch ||
                    generation != readerGeneration ||
                    expectedBookId != bookId ||
                    !ownsReaderSession() ||
                    (fontRequestId != null && fontRequestId != fontChangeRequestId)
                ) {
                    throw CancellationException("stale pagination result suppressed")
                }

                // For a font rebuild, the user may have kept reading while the background work ran.
                // Resolve against the latest visible source position immediately before the atomic
                // ReaderBook swap, never the page that was visible when the font button was tapped.
                val liveFontOffset = if (fontRequestId != null) currentVisibleSourceOffset() else null
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val stableOffset = liveFontOffset
                    ?: preserveOffset
                    ?: lastStableSourceOffset
                    ?: CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)
                    ?: progress?.startOffset
                    ?: progress?.txtCharOffset
                    ?: progress?.position?.toIntOrNull()
                readerBook = paged
                layoutSettings = settings
                pendingFontRollback = null
'''
if paginate_call_new not in s:
    if paginate_call_old not in s:
        raise SystemExit('v780 PageEngine call/commit anchor missing')
    s = s.replace(paginate_call_old, paginate_call_new, 1)

catch_old = '''            } catch (cancelled: CancellationException) {
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:cancelled book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset generation=$readerGeneration")
                throw cancelled
            } catch (error: Throwable) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset")
                if (isFinishing || isDestroyed || !ownsReaderSession()) return@launch
                val rollback = pendingFontRollback
                if (rollback != null) {
                    pendingFontRollback = null
                    readerTextSizeSp = rollback.textSizeSp
                    readerBook = rollback.readerBook
                    layoutSettings = rollback.settings
                    currentPageIndex = rollback.pageIndex.coerceIn(0, rollback.readerBook.pages.lastIndex)
                    lastStableSourceOffset = rollback.sourceOffset.coerceIn(0, rollback.readerBook.text.length)
                    applyReaderContentPadding()
                    savePreferences()
                    updateSettingsLabels()
                    showActiveReader()
                    Toast.makeText(this@ReaderActivity, "字号分页失败，已恢复原字号和位置", Toast.LENGTH_SHORT).show()
                } else {
                    showContinuousFallback(error.message ?: "分页失败")
                }
            }
        }
    }
'''
catch_new = '''            } catch (cancelled: CancellationException) {
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:cancelled book=$bookId fontRequest=$fontRequestId epoch=$epoch page=$currentPageIndex stable=$lastStableSourceOffset generation=$readerGeneration")
                throw cancelled
            } catch (error: Throwable) {
                releaseShelfCacheReaderClaim(markCompleted = false)
                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:failure book=$bookId fontRequest=$fontRequestId epoch=$epoch type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset")
                val stale = epoch != paginationEpoch || generation != readerGeneration || expectedBookId != bookId ||
                    !ownsReaderSession() || (fontRequestId != null && fontRequestId != fontChangeRequestId)
                if (stale || isFinishing || isDestroyed) return@launch
                val rollback = pendingFontRollback
                if (rollback != null) {
                    pendingFontRollback = null
                    readerTextSizeSp = rollback.textSizeSp
                    readerBook = rollback.readerBook
                    layoutSettings = rollback.settings
                    currentPageIndex = rollback.pageIndex.coerceIn(0, rollback.readerBook.pages.lastIndex)
                    lastStableSourceOffset = rollback.sourceOffset.coerceIn(0, rollback.readerBook.text.length)
                    applyReaderContentPadding()
                    savePreferences()
                    updateSettingsLabels()
                    showActiveReader()
                    Toast.makeText(this@ReaderActivity, "字号分页失败，已恢复原字号和位置", Toast.LENGTH_SHORT).show()
                } else {
                    showContinuousFallback(error.message ?: "分页失败")
                }
            } finally {
                if (epoch == paginationEpoch) paginationInProgress = false
            }
        }
    }
'''
if catch_new not in s:
    if catch_old not in s:
        raise SystemExit('v780 paginate catch/finally anchor missing')
    s = s.replace(catch_old, catch_new, 1)

required_reader = [
    'private var paginationEpoch: Long = 0L',
    'private fun applyTransientFontPreview(anchorOffset: Int, requestId: Long)',
    'applyTransientFontPreview(currentOffset, requestId)',
    'paginateAndDisplay(currentOffset, requestId)',
    'private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null)',
    'val epoch = paginationEpoch',
    'val generation = readerGeneration',
    'val expectedBookId = selectedBook.id',
    'shouldCancel = {',
    'ReaderRuntimeState.isOwner(generation)',
    'stale pagination result suppressed',
    'val liveFontOffset = if (fontRequestId != null) currentVisibleSourceOffset() else null',
    'if (epoch == paginationEpoch) paginationInProgress = false',
    'font_preview:applied',
]
missing = [m for m in required_reader if m not in s]
if missing:
    raise SystemExit('v780 reader patch incomplete: ' + repr(missing))
if 'progressLabel.text = "字体分页中…"' in s:
    raise SystemExit('v780 must not block visible font feedback behind a pagination label')
reader.write_text(s, encoding='utf-8')

engine = root / 'app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt'
e = engine.read_text(encoding='utf-8')

sig_old = '''        settings: ReaderLayoutSettings,
        typeface: Typeface = Typeface.DEFAULT,
        imageSpanProvider: ImageSpanProvider? = null
    ): ReaderBook {
        val chapters = normalizeChapters(text, sourceChapters)
'''
sig_new = '''        settings: ReaderLayoutSettings,
        typeface: Typeface = Typeface.DEFAULT,
        imageSpanProvider: ImageSpanProvider? = null,
        shouldCancel: (() -> Boolean)? = null
    ): ReaderBook {
        throwIfPaginationCancelled(shouldCancel)
        val chapters = normalizeChapters(text, sourceChapters)
'''
if sig_new not in e:
    if sig_old not in e:
        raise SystemExit('v780 PageEngine signature anchor missing')
    e = e.replace(sig_old, sig_new, 1)

chapter_loop_old = '''        chapters.forEachIndexed { chapterIndex, chapter ->
            val chapterPages = paginateChapter(
                text = text,
                chapter = chapter,
                settings = settings,
                typeface = typeface,
                imageSpanProvider = imageSpanProvider
            )
'''
chapter_loop_new = '''        chapters.forEachIndexed { chapterIndex, chapter ->
            throwIfPaginationCancelled(shouldCancel)
            val chapterPages = paginateChapter(
                text = text,
                chapter = chapter,
                settings = settings,
                typeface = typeface,
                imageSpanProvider = imageSpanProvider,
                shouldCancel = shouldCancel
            )
'''
if chapter_loop_new not in e:
    if chapter_loop_old not in e:
        raise SystemExit('v780 chapter cancellation anchor missing')
    e = e.replace(chapter_loop_old, chapter_loop_new, 1)

private_sig_old = '''        settings: ReaderLayoutSettings,
        typeface: Typeface,
        imageSpanProvider: ImageSpanProvider?
    ): List<Pair<Int, Int>> {
'''
private_sig_new = '''        settings: ReaderLayoutSettings,
        typeface: Typeface,
        imageSpanProvider: ImageSpanProvider?,
        shouldCancel: (() -> Boolean)?
    ): List<Pair<Int, Int>> {
'''
if private_sig_new not in e:
    if private_sig_old not in e:
        raise SystemExit('v780 paginateChapter signature anchor missing')
    e = e.replace(private_sig_old, private_sig_new, 1)

while_old = '''        var cursor = chapter.startOffset
        while (cursor < chapter.endOffset) {
            val windowEnd = chooseWindowEnd(text, cursor, chapter.endOffset)
'''
while_new = '''        var cursor = chapter.startOffset
        while (cursor < chapter.endOffset) {
            throwIfPaginationCancelled(shouldCancel)
            val windowEnd = chooseWindowEnd(text, cursor, chapter.endOffset)
'''
if while_new not in e:
    if while_old not in e:
        raise SystemExit('v780 window cancellation anchor missing')
    e = e.replace(while_old, while_new, 1)

line_loop_old = '''                var firstLine = 0
                while (firstLine < layout.lineCount) {
                    val pageTop = layout.getLineTop(firstLine)
'''
line_loop_new = '''                var firstLine = 0
                while (firstLine < layout.lineCount) {
                    throwIfPaginationCancelled(shouldCancel)
                    val pageTop = layout.getLineTop(firstLine)
'''
if line_loop_new not in e:
    if line_loop_old not in e:
        raise SystemExit('v780 page cancellation anchor missing')
    e = e.replace(line_loop_old, line_loop_new, 1)

helper_anchor = '''    private fun chooseWindowEnd(text: String, start: Int, chapterEnd: Int): Int {
'''
helper = '''    private fun throwIfPaginationCancelled(shouldCancel: (() -> Boolean)?) {
        if (shouldCancel?.invoke() == true) {
            throw java.util.concurrent.CancellationException("PageEngine pagination cancelled")
        }
    }

'''
if 'private fun throwIfPaginationCancelled' not in e:
    if helper_anchor not in e:
        raise SystemExit('v780 cancellation helper anchor missing')
    e = e.replace(helper_anchor, helper + helper_anchor, 1)

required_engine = [
    'shouldCancel: (() -> Boolean)? = null',
    'throwIfPaginationCancelled(shouldCancel)',
    'shouldCancel = shouldCancel',
    'java.util.concurrent.CancellationException("PageEngine pagination cancelled")',
]
missing = [m for m in required_engine if m not in e]
if missing:
    raise SystemExit('v780 PageEngine patch incomplete: ' + repr(missing))
engine.write_text(e, encoding='utf-8')

print('v780 applied: live font preview on stable full-page data + guarded/cooperative background repagination')
