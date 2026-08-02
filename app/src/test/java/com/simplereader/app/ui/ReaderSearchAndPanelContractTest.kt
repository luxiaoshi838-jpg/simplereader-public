package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSearchAndPanelContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `reader keeps current book search and exact page mapping`() {
        val text = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        assertTrue(text.contains("ReaderSearchSheet.show"))
        assertTrue(text.contains("searchKeyword"))
        assertTrue(text.contains("searchHits"))
        assertTrue(text.contains("paged.pageForOffset(index)"))
    }

    @Test
    fun `v600 catalog and bookmark panel has no bookmark creation action`() {
        val text = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        assertTrue(text.contains("showCatalogBookmarkPanelV600"))
        assertTrue(text.contains("buttonLikeText(\"目录\")"))
        assertTrue(text.contains("buttonLikeText(\"书签\")"))
        val panelStart = text.indexOf("private fun showCatalogBookmarkPanelV600")
        val panelEnd = text.indexOf("private fun buttonLikeText", panelStart)
        val panel = text.substring(panelStart, panelEnd)
        assertFalse(panel.contains("addBookmark()"))
        assertFalse(File("src/main/java/com/simplereader/app/ui/ReaderCatalogSheet.kt").exists())
    }
}
