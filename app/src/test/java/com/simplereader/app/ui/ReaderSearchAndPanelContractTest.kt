package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSearchAndPanelContractTest {
    private fun reader(): String = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private fun panels(): String = File("src/main/java/com/simplereader/app/ui/ReaderPanels.kt").readText()

    @Test
    fun `reader keeps current book search entry results and exact page mapping`() {
        val text = reader()
        assertTrue(text.contains("menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, \"搜索\")"))
        assertTrue(text.contains("showContentSearch()"))
        assertTrue(text.contains("searchKeyword"))
        assertTrue(text.contains("searchHits"))
        assertTrue(text.contains("paged.pageForOffset(index)"))
        assertTrue(text.contains("globalPageIndex = page.globalPageIndex"))
    }

    @Test
    fun `catalog and bookmarks switch only by top tab clicks`() {
        val text = panels()
        assertTrue(text.contains("catalogTab.setOnClickListener"))
        assertTrue(text.contains("bookmarkTab.setOnClickListener"))
        assertFalse(text.contains("list.setOnTouchListener"))
        assertFalse(text.contains("MotionEvent.ACTION_DOWN"))
    }
}
