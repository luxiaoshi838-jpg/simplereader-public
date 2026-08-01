package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderOpenOverflowRegressionTest {
    private val activity = File(
        "src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
    ).readText()

    @Test
    fun existingActionBarOverflowAlwaysContainsOneClickCache() {
        assertTrue(activity.contains("override fun onCreateOptionsMenu(menu: Menu)"))
        assertTrue(activity.contains("override fun onPrepareOptionsMenu(menu: Menu)"))
        assertTrue(activity.contains("menu.findItem(MENU_CACHE_BOOK)"))
        assertTrue(activity.contains("item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)"))
        assertTrue(activity.contains("supportActionBar?.show()"))
        assertTrue(activity.contains("invalidateOptionsMenu()"))
        assertFalse(activity.contains("readerOverflowButton"))
        assertFalse(activity.contains("showReaderOverflowMenu"))
    }

    @Test
    fun txtDirectoryIsEstablishedBeforeTheFirstVisibleLayout() {
        val scan = activity.indexOf("val scannedChapters =")
        val bootstrap = activity.indexOf("startByte = initialWindowStartForTarget(targetOffset)")
        val loaded = activity.indexOf("isStreamingTxt = true", bootstrap)
        val display = activity.indexOf("displayContent()", loaded)

        assertTrue(scan > 0)
        assertTrue(bootstrap > scan)
        assertTrue(loaded > bootstrap)
        assertTrue(display > loaded)
        assertTrue(activity.contains("maxBytes = TXT_INITIAL_WINDOW_BYTES"))
        assertTrue(activity.contains("if (txtStreamingMode && epubChapters.isEmpty()"))
    }

    @Test
    fun visibleChapterReadsThroughTheResolvedDocumentUri() {
        assertTrue(activity.contains("val sourceUri = currentReadableDocument?.uri"))
        assertTrue(activity.contains("contentResolver.openInputStream(sourceUri)?.use"))
    }
}
