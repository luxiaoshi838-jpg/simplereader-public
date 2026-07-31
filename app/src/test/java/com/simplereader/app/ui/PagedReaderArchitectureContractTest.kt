package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PagedReaderArchitectureContractTest {
    private val activity = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val model = File("src/main/java/com/simplereader/app/ui/ReaderPageModel.kt").readText()
    private val view = File("src/main/java/com/simplereader/app/ui/PagedReaderView.kt").readText()
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
    fun verticalIsContinuousFlowNotFixedPageAnimation() {
        assertTrue(layout.contains("android:id=\"@+id/readerScrollView\""))
        assertTrue(activity.contains("private fun isPagedReaderMode(): Boolean = pageTurnMode != TURN_MODE_VERTICAL"))
        assertTrue(activity.contains("readerScrollView.isSmoothScrollingEnabled = true"))
        assertTrue(activity.contains("maybeExtendTxtContinuousBuffer(scrollY)"))
        assertTrue(activity.contains("maybeExtendStructuredContinuousBuffer(scrollY)"))
        assertFalse(view.contains("TurnMode.VERTICAL"))
        assertFalse(view.contains("outgoing.translationY = if (direction > 0) -height"))
    }

    @Test
    fun modeSwitchPreservesOneLogicalAnchorAndCancelsOldGesture() {
        val method = activity.substringAfter("private fun setTurnMode(mode: String)")
            .substringBefore("private fun updateThemeControls()")
        assertTrue(method.contains("val anchor ="))
        assertTrue(method.contains("pagedReaderView.cancelNavigation()"))
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
}
