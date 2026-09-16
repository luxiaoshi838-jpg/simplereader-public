package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV790LayoutSafetyContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()

    @Test fun `selection wrapper never restores nullable unattached layout params`() {
        assertFalse(main.contains("val originalParams = card.layoutParams"))
        assertFalse(main.contains("layoutParams = originalParams"))
        assertTrue(main.contains("card.layoutParams = FrameLayout.LayoutParams("))
        assertTrue(main.contains("child.layoutParams = FrameLayout.LayoutParams("))
    }

    @Test fun `list grid change recreates shelf instead of hot swapping active recycler hierarchy`() {
        val start = main.indexOf("private fun toggleShelfLayoutMode()")
        val end = main.indexOf("private fun statusBarHeight()", start)
        assertTrue(start >= 0 && end > start)
        val toggle = main.substring(start, end)
        assertTrue(toggle.contains("shelfGrid.stopScroll()"))
        assertTrue(toggle.contains("shelfGrid.recycledViewPool.clear()"))
        assertTrue(toggle.contains("putBoolean(SHELF_LIST_MODE_KEY, shelfListMode)"))
        assertTrue(toggle.contains("recreate()"))
        assertFalse(toggle.contains("applyShelfLayoutMode()"))
        assertFalse(toggle.contains("shelfGrid.post"))
    }

    @Test fun `mode button continues to describe current mode`() {
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"列表\" else \"宫格\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }
}
