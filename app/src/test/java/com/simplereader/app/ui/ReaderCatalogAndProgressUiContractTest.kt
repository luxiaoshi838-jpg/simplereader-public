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
        val text = readerSource()
        assertTrue(text.contains("setIcon(android.R.drawable.ic_menu_search)"))
    }

    @Test
    fun bottomReaderProgressIsPageLabelWithoutSeekbar() {
        val source = readerSource()
        val layout = readerLayout()
        assertFalse(source.contains("readerProgressBar"))
        assertFalse(layout.contains("@+id/readerProgressBar"))
        assertTrue(layout.contains("android:id=\"@+id/readerProgressLabel\""))
        assertTrue(layout.substringAfter("android:id=\"@+id/readerProgressLabel\"")
            .substringBefore("<LinearLayout")
            .contains("android:visibility=\"gone\""))
        assertTrue(source.contains("private fun pageCountLabel()"))
        assertTrue(source.contains("return \"\$currentPage/\$totalPages\""))
    }
}
