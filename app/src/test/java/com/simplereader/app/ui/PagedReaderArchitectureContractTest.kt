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
        assertTrue(activity.contains("applyPagedAnchor(target.startAnchor)"))
    }

    @Test
    fun everyModeUsesOnePersistentPageHost() {
        assertTrue(layout.contains("com.simplereader.app.ui.PagedReaderView"))
        assertTrue(layout.contains("android:id=\"@+id/pagedReaderView\""))
        assertTrue(layout.substringAfter("android:id=\"@+id/pagedReaderView\"")
            .substringBefore("<androidx.core.widget.NestedScrollView")
            .contains("android:visibility=\"visible\""))
        assertTrue(layout.substringAfter("android:id=\"@+id/readerScrollView\"")
            .substringBefore("<TextView")
            .contains("android:visibility=\"gone\""))
        assertTrue(activity.contains("private fun isPagedReaderMode(): Boolean = true"))
        assertTrue(activity.contains("TURN_MODE_VERTICAL -> PagedReaderView.TurnMode.VERTICAL"))
    }

    @Test
    fun modeSwitchChangesOnlyRendererState() {
        val method = activity.substringAfter("private fun setTurnMode(mode: String)")
            .substringBefore("private fun updateThemeControls()")
        assertTrue(method.contains("pagedReaderView.setTurnMode(pagedTurnMode())"))
        assertFalse(method.contains("readerPageCache.clear()"))
        assertFalse(method.contains("displayContent()"))
        assertFalse(method.contains("pagedReaderGeneration++"))
        assertFalse(method.contains("readerScrollView.visibility"))
        assertFalse(method.contains("configurePagedReaderStyle()"))
    }

    @Test
    fun flowAnimationsHaveOneCancelableStateMachine() {
        assertTrue(view.contains("ValueAnimator"))
        assertTrue(view.contains("activeDirection"))
        assertTrue(view.contains("TurnMode.OVERLAP"))
        assertTrue(view.contains("TurnMode.SLIDE"))
        assertTrue(view.contains("TurnMode.VERTICAL"))
        assertTrue(view.contains("TurnMode.FADE"))
        assertTrue(view.contains("TurnMode.SIMULATE"))
        assertTrue(view.contains("updateAdjacent"))
        assertFalse(view.contains("withEndAction"))
    }

    @Test
    fun chapterCrossingComesFromPreparedAdjacentPages() {
        assertTrue(activity.contains("buildPagedWindow"))
        assertTrue(activity.contains("pagedPagesForChapter(safeChapter + 1"))
        assertTrue(activity.contains("pagedPagesForChapter(safeChapter - 1"))
        assertFalse(view.contains("loadStructuredChapter"))
        assertFalse(view.contains("currentPosition"))
    }

    @Test
    fun streamingCacheIdentityDoesNotDependOnTurnMode() {
        assertTrue(model.contains("val contentKey: Long"))
        assertTrue(activity.contains("txtCurrentPageStartByte * 31L + txtCurrentPageEndByte"))
        val signature = activity.substringAfter("private fun pagedLayoutSignature()")
            .substringBefore("private fun pagedChapterCount()")
        assertFalse(signature.contains("pageTurnMode"))
    }

    @Test
    fun pageNumberHasOneSourceSharedWithCatalog() {
        val pagedLabel = activity.substringAfter("private fun updatePagedProgressLabel")
            .substringBefore("private fun nextPage()")
        assertTrue(pagedLabel.contains("readerProgressLabel.text = pageCountLabel()"))
        assertFalse(pagedLabel.contains("pageIndexInChapter"))
        assertTrue(activity.contains("val unitsPerPage = pageSize.toLong().coerceAtLeast(1L)"))
    }
}
