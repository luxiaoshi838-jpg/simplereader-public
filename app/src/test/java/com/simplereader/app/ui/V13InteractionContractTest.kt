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
    fun `bookmark creation stays in reader top bar with source vector and page uniqueness`() {
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val dao = source("src/main/java/com/simplereader/app/data/dao/BookmarkDao.kt")
        val vector = source("src/main/res/drawable/ic_bookmark_add.xml")
        assertTrue(reader.contains("setImageResource(R.drawable.ic_bookmark_add)"))
        assertTrue(reader.contains("background = null"))
        assertTrue(reader.contains("database.withTransaction"))
        assertTrue(reader.contains("getBookmarkByPage(bookId, page.globalPageIndex)"))
        assertTrue(reader.contains("本页已有书签"))
        assertTrue(reader.contains("MENU_ADD_BOOKMARK"))
        assertTrue(dao.contains("WHERE bookId = :bookId AND globalPageIndex = :globalPageIndex"))
        assertTrue(vector.contains("android:strokeColor=\"#FFFFFFFF\""))
        assertTrue(vector.contains("M12,7 L12,13 M9,10 L15,10"))
        val panelStart = reader.indexOf("private fun showCatalogBookmarkPanelV600")
        val panelEnd = reader.indexOf("private fun buttonLikeText", panelStart)
        assertFalse(reader.substring(panelStart, panelEnd).contains("addBookmark()"))
    }

    @Test
    fun `group preview uses equal weighted two by two cells`() {
        val text = source("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        assertTrue(text.contains("GridLayout.spec(index / 2, 1, 1f)"))
        assertTrue(text.contains("GridLayout.spec(index % 2, 1, 1f)"))
        assertTrue(text.contains("setGravity(Gravity.FILL)"))
    }
}
