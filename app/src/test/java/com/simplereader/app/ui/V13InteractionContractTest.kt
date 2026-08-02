package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V13InteractionContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `shelf search has right-side action enter trigger and resets to all shelf`() {
        val text = source("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        assertTrue(text.contains("addView(searchAction"))
        assertTrue(text.contains("EditorInfo.IME_ACTION_SEARCH"))
        assertTrue(text.contains("KeyEvent.KEYCODE_ENTER"))
        assertTrue(text.contains("dialog.setOnDismissListener"))
        assertTrue(text.contains("selectedGroupId = null"))
        assertTrue(text.contains("showingHistory = false"))
    }

    @Test
    fun `reader keeps v600 add bookmark action and immediate deletion refresh`() {
        val text = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val panels = source("src/main/java/com/simplereader/app/ui/ReaderPanels.kt")
        assertTrue(text.contains("text = \"添\""))
        assertTrue(text.contains("shape = GradientDrawable.OVAL"))
        assertTrue(text.contains("confirmDeleteBookmark(bookmark)"))
        assertTrue(text.contains("showCatalogBookmarks(startWithBookmarks = true)"))
        assertTrue(panels.contains("dialog.dismiss()"))
        assertFalse(text.contains("menu.add(Menu.NONE, MENU_PANEL"))
    }

    @Test
    fun `group preview uses equal weighted two by two cells`() {
        val text = source("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        assertTrue(text.contains("GridLayout.spec(index / 2, 1, 1f)"))
        assertTrue(text.contains("GridLayout.spec(index % 2, 1, 1f)"))
        assertTrue(text.contains("setGravity(Gravity.FILL)"))
    }
}
