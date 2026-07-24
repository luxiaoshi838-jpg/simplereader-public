package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSearchAndPanelContractTest {
    private fun source(): String = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test
    fun `reader restores previous search entry and handler`() {
        val text = source()
        assertTrue(text.contains("menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, \"搜索\")"))
        assertTrue(text.contains("MENU_SEARCH ->"))
        assertTrue(text.contains("showContentSearch()"))
    }

    @Test
    fun `catalog and bookmarks switch only by top tab clicks`() {
        val text = source()
        assertTrue(text.contains("catalogButton.setOnClickListener"))
        assertTrue(text.contains("bookmarkButton.setOnClickListener"))
        assertFalse(text.contains("listView.setOnTouchListener"))
        assertFalse(text.contains("MotionEvent.ACTION_DOWN -> downX"))
    }
}
