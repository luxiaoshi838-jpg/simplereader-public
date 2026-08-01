package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderOnlyDuokanPaginationContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_reader.xml").readText()
    private val paginator = File("src/main/java/com/simplereader/app/ui/ReaderPageModel.kt").readText()

    @Test
    fun txtUsesWholeChapterStaticLayoutPagesInsteadOfFixedCharacterWindows() {
        assertTrue(reader.contains("ReaderTextPaginator.paginate"))
        assertTrue(reader.contains("readFixedTxtChapter"))
        assertTrue(reader.contains("txtCurrentChapterPages"))
        assertTrue(paginator.contains("StaticLayout.Builder.obtain"))
        assertTrue(paginator.contains("pageIndexInChapter"))
    }

    @Test
    fun chapterNavigationAlwaysTargetsARealChapterBoundaryPage() {
        assertTrue(reader.contains("requestedPageIndex = 0"))
        assertTrue(reader.contains("requestedPageIndex = Int.MAX_VALUE"))
        assertTrue(reader.contains("showNextFixedTxtPage"))
        assertTrue(reader.contains("showPreviousFixedTxtPage"))
    }

    @Test
    fun exactTotalsAreNotShownUntilEveryChapterHasARealPageCount() {
        assertTrue(reader.contains("txtWholeBookPageIndexComplete"))
        assertTrue(reader.contains("return \"本章 \$chapterPage/\$chapterTotal\""))
        assertTrue(reader.contains("ReaderPageIndexStore"))
    }

    @Test
    fun readingViewportDoesNotUseSystemScrollbarsOrDrawUnderStatusBar() {
        assertTrue(layout.contains("android:fitsSystemWindows=\"true\""))
        assertTrue(layout.contains("android:scrollbars=\"none\""))
        assertTrue(reader.contains("WindowCompat.setDecorFitsSystemWindows(window, true)"))
        assertFalse(reader.contains("readerScrollView.isScrollbarFadingEnabled = false"))
    }
}
