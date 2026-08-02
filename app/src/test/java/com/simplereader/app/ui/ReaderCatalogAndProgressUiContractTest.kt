package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatalogAndProgressUiContractTest {
    private fun readerSource(): String =
        File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    private fun readerLayout(): String =
        File("src/main/res/layout/activity_reader.xml").readText()

    @Test
    fun topSearchUsesMagnifyingGlassIcon() {
        assertTrue(readerSource().contains("setIcon(android.R.drawable.ic_menu_search)"))
    }

    @Test
    fun bottomReaderProgressUsesTheGlobalReaderPageSequence() {
        val source = readerSource()
        val layout = readerLayout()
        assertFalse(source.contains("readerProgressBar"))
        assertFalse(layout.contains("@+id/readerProgressBar"))
        assertTrue(layout.contains("android:text=\"1/1\""))
        assertTrue(source.contains("private fun updateProgressUi()"))
        assertTrue(source.contains("currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)"))
        assertTrue(source.contains("progressLabel.text = \"\${currentPageIndex + 1}/\${pages.size}\""))
        assertTrue(source.contains("page.globalPageIndex + 1"))
        assertTrue(source.contains("page.totalPageCount"))
    }
}
