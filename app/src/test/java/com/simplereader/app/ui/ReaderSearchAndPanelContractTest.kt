package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSearchAndPanelContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `reader keeps current book search results and exact page mapping`() {
        val text = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        assertTrue(text.contains("menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, \"搜索\")"))
        assertTrue(text.contains("ReaderSearchSheet.show"))
        assertTrue(text.contains("searchKeyword"))
        assertTrue(text.contains("searchHits"))
        assertTrue(text.contains("paged.pageForOffset(index)"))
        assertTrue(text.contains("globalPageIndex = page.globalPageIndex"))
    }

    @Test
    fun `catalog sheet has only catalog and bookmark tabs`() {
        val text = source("src/main/java/com/simplereader/app/ui/ReaderCatalogSheet.kt")
        assertTrue(text.contains("tab(\"目录\")"))
        assertTrue(text.contains("tab(\"书签\")"))
        assertFalse(text.contains("onAddBookmark"))
        assertFalse(text.contains("添加书签"))
        assertFalse(File("src/main/java/com/simplereader/app/ui/ReaderPanels.kt").exists())
    }
}
