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
    fun pagedModesUseReusableThreePageContainer() {
        assertTrue(layout.contains("com.simplereader.app.ui.PagedReaderView"))
        assertTrue(view.contains("previousView"))
        assertTrue(view.contains("currentView"))
        assertTrue(view.contains("nextView"))
        assertTrue(view.contains("onTurnCommitted"))
        assertTrue(activity.contains("ReaderPageCache(maxChapters = 3)"))
    }

    @Test
    fun allAnimationsAreActuallyDifferent() {
        assertTrue(view.contains("TurnMode.OVERLAP"))
        assertTrue(view.contains("TurnMode.SLIDE"))
        assertTrue(view.contains("TurnMode.FADE"))
        assertTrue(view.contains("TurnMode.SIMULATE"))
        assertTrue(view.contains("rotationY"))
        assertTrue(view.contains("outgoing.translationX"))
        assertTrue(view.contains("incoming.alpha"))
    }

    @Test
    fun chapterCrossingComesFromAdjacentPagesNotChapterJumpDuringAnimation() {
        assertTrue(activity.contains("buildPagedWindow"))
        assertTrue(activity.contains("pagedPagesForChapter(safeChapter + 1"))
        assertTrue(activity.contains("pagedPagesForChapter(safeChapter - 1"))
        assertFalse(view.contains("loadStructuredChapter"))
        assertFalse(view.contains("currentPosition"))
    }

    @Test
    fun verticalModeRemainsSeparate() {
        assertTrue(activity.contains("private fun isPagedReaderMode(): Boolean = pageTurnMode != TURN_MODE_VERTICAL"))
        assertTrue(activity.contains("pagedReaderView.visibility = View.GONE"))
        assertTrue(activity.contains("readerScrollView.visibility = View.VISIBLE"))
    }
}
