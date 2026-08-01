package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PagedReaderArchitectureContractTest {
    private val activity = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val model = File("src/main/java/com/simplereader/app/ui/ReaderPageModel.kt").readText()
    private val view = File("src/main/java/com/simplereader/app/ui/PagedReaderView.kt").readText()
    private val vertical = File("src/main/java/com/simplereader/app/ui/VerticalPageFlowView.kt").readText()
    private val parser = File("src/main/java/com/simplereader/app/parser/TxtParser.kt").readText()
    private val layout = File("src/main/res/layout/activity_reader.xml").readText()

    @Test
    fun pagePositionUsesOneStableAnchor() {
        assertTrue(model.contains("data class ReaderPageAnchor"))
        assertTrue(model.contains("val chapterIndex: Int"))
        assertTrue(model.contains("val chapterOffset: Int"))
        assertTrue(model.contains("val sourceOffset: Long"))
        assertTrue(activity.contains("applyPagedAnchor(current.startAnchor)"))
    }

    @Test
    fun verticalScrollUsesTheSameFixedChapterPages() {
        assertTrue(layout.contains("android:id=\"@+id/verticalPageFlowView\""))
        assertTrue(activity.contains("refreshVerticalReader(pagedAnchorFromCurrentPosition())"))
        assertTrue(activity.contains("val pages = pagedPagesForChapter(safeChapter, signature)"))
        assertTrue(activity.contains("verticalPageFlowView.bind(pages, current, preserveOffset)"))
        assertTrue(activity.contains("verticalPageFlowView.prepend(pages)"))
        assertTrue(activity.contains("verticalPageFlowView.append(pages)"))
        assertTrue(vertical.contains("Each adapter cell is exactly one *content-page* high"))
        assertTrue(vertical.contains("pageHeightPx"))
        assertTrue(vertical.contains("RecyclerView"))
        assertTrue(vertical.contains("topViewportPaddingPx"))
        assertTrue(vertical.contains("bottomViewportPaddingPx"))
        assertFalse(vertical.contains("TxtContinuousBuffer"))
    }

    @Test
    fun modeSwitchPreservesOneLogicalAnchorAndCancelsOldGesture() {
        val method = activity.substringAfter("private fun setTurnMode(mode: String)")
            .substringBefore("private fun updateThemeControls()")
        assertTrue(method.contains("val anchor ="))
        assertTrue(method.contains("pagedReaderView.cancelNavigation()"))
        assertTrue(method.contains("verticalPageFlowView.cancelNavigation()"))
        assertTrue(method.contains("applyPagedAnchor(anchor)"))
        assertTrue(method.contains("refreshPagedReader(anchor = anchor"))
        assertFalse(method.contains("readerPageCache.clear()"))
    }

    @Test
    fun horizontalAnimationsHaveOneCancelableStateMachine() {
        assertTrue(view.contains("ValueAnimator"))
        assertTrue(view.contains("cancelNavigation"))
        assertTrue(view.contains("TurnMode.OVERLAP"))
        assertTrue(view.contains("TurnMode.SLIDE"))
        assertTrue(view.contains("TurnMode.FADE"))
        assertTrue(view.contains("TurnMode.SIMULATE"))
        assertTrue(view.contains("updateAdjacent"))
        assertFalse(view.contains("withEndAction"))
    }

    @Test
    fun firstScreenDoesNotWaitForAdjacentChapters() {
        val refresh = activity.substringAfter("private fun refreshPagedReader(")
            .substringBefore("private fun cachedAdjacentPage(")
        assertTrue(refresh.contains("buildVisiblePagedWindow(anchor, signature)"))
        assertTrue(refresh.contains("prefetchPagedAdjacent(current)"))
        assertTrue(refresh.contains("ensureWholeBookPageIndex(signature)"))
        val visibleWindow = activity.substringAfter("private suspend fun buildVisiblePagedWindow")
            .substringBefore("private suspend fun buildPagedWindow")
        assertFalse(visibleWindow.contains("pagedPagesForChapter(safeChapter + 1"))
        assertFalse(visibleWindow.contains("pagedPagesForChapter(safeChapter - 1"))
    }

    @Test
    fun paginatorBuildsOneLayoutInsteadOfOneLayoutPerPage() {
        val paginate = model.substringAfter("fun paginate(")
        assertTrue(paginate.contains("val layout = StaticLayout.Builder.obtain"))
        assertFalse(paginate.substringAfter("while (firstLine").contains("StaticLayout.Builder.obtain"))
    }

    @Test
    fun pageNumberComesFromActualLayoutPages() {
        assertTrue(activity.contains("pagedChapterPageCounts"))
        assertTrue(activity.contains("pagedChapterPageStarts"))
        assertTrue(activity.contains("actualPageLabel(page)"))
        assertTrue(activity.contains("page.pageIndexInChapter"))
        val label = activity.substringAfter("private fun pageCountLabel()")
            .substringBefore("private fun renderedPageCountLabel")
        assertFalse(label.contains("pageSize"))
        assertFalse(label.contains("unitsPerPage"))
    }

    @Test
    fun chapterJumpIsAVisiblePageTransaction() {
        assertTrue(activity.contains("pagedReaderView.cancelNavigation()"))
        assertTrue(activity.contains("refreshPagedReader("))
        assertTrue(activity.contains("saveImmediately = saveImmediately"))
        assertTrue(activity.contains("pendingBoundaryTurnDirection = 0"))
    }
    @Test
    fun everyChapterOwnsASeparateFixedPageList() {
        val chapterSource = activity.substringAfter("private suspend fun pagedChapterSource")
            .substringBefore("private fun pagedAnchorFromCurrentPosition")
        assertTrue(chapterSource.contains("startByte"))
        assertTrue(chapterSource.contains("endByte"))
        assertTrue(chapterSource.contains("TxtParser.readRangeMapped"))

        val chapterPages = activity.substringAfter("private suspend fun pagedPagesForChapter")
            .substringBefore("private fun pageContaining")
        assertTrue(chapterPages.contains("pagedChapterSource(chapterIndex)"))
        assertTrue(chapterPages.contains("ReaderTextPaginator.paginate"))
        assertTrue(chapterPages.contains("registerChapterPageCount(chapterIndex, pages.size"))

        val crossChapter = activity.substringAfter("private suspend fun buildPagedWindow")
            .substringBefore("private fun refreshPagedReader")
        assertTrue(crossChapter.contains("pagedPagesForChapter(safeChapter - 1, signature).lastOrNull()"))
        assertTrue(crossChapter.contains("pagedPagesForChapter(safeChapter + 1, signature).firstOrNull()"))
    }

    @Test
    fun visiblePagesAndPageCounterUseTheSameExactMetrics() {
        assertTrue(view.contains("TypedValue.COMPLEX_UNIT_PX"))
        assertTrue(vertical.contains("TypedValue.COMPLEX_UNIT_PX"))
        assertTrue(view.contains("Layout.BREAK_STRATEGY_HIGH_QUALITY"))
        assertTrue(vertical.contains("Layout.BREAK_STRATEGY_HIGH_QUALITY"))
        assertTrue(model.contains("signature.textSizePx.toFloat()"))
        assertTrue(activity.contains("textSizePx = readerTextSizePx()"))
    }

    @Test
    fun txtPageAnchorsUseExactSourceBytesInsteadOfCharacterRatios() {
        assertTrue(parser.contains("fun readRangeMapped("))
        assertTrue(parser.contains("val sourceOffsets: LongArray"))
        assertTrue(activity.contains("sourceOffsets = mapped.sourceOffsets.copyOf(text.length + 1)"))
        val mapper = activity.substringAfter("val sourceMapper: (Int) -> Long")
            .substringBefore("val pages = withContext")
        assertTrue(mapper.contains("val exact = requireNotNull(source.sourceOffsets)"))
        assertFalse(mapper.contains("byteSpan * characterOffset"))
    }

    @Test
    fun visibleReaderControlsBlockMovementButCenterTapCanDismissThem() {
        val turn = view.substringAfter("fun turn(direction: Int)")
            .substringBefore("override fun onTouchEvent")
        assertTrue(turn.contains("isReaderChromeVisible()"))
        assertTrue(turn.contains("cancelNavigation()"))
        val pagedTouch = view.substringAfter("override fun onTouchEvent(event: MotionEvent)")
            .substringBefore("private fun animateTurn")
        assertTrue(pagedTouch.contains("isReaderChromeVisible()"))
        assertTrue(pagedTouch.contains("onCenterTap?.invoke()"))
        assertTrue(vertical.contains("if (isReaderChromeVisible() || direction == 0) return"))
        assertTrue(vertical.contains("if (chromeVisible && e.actionMasked == MotionEvent.ACTION_DOWN) rv.stopScroll()"))
        assertTrue(vertical.contains("if (e.x in width * 0.33f..width * 0.67f) onCenterTap?.invoke()"))
    }

}
