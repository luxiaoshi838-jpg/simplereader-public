package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV788LayoutSwitchContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()

    @Test
    fun `mode button names the current layout`() {
        assertTrue(layout.contains("android:text=\"宫格\""))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"列表\" else \"宫格\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }

    @Test
    fun `list and grid reuse one grid layout manager`() {
        assertTrue(main.contains("private fun createShelfLayoutManager(): GridLayoutManager"))
        assertTrue(main.contains("if (shelfListMode || shelfAdapter.isFullSpan(position)) 3 else 1"))
        val start = main.indexOf("private fun applyShelfLayoutMode")
        val end = main.indexOf("private fun updateShelfModeButton", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("as? GridLayoutManager"))
        assertFalse(block.contains("LinearLayoutManager(this)"))
        assertTrue(block.contains("invalidateSpanIndexCache()"))
        assertTrue(block.contains("recycledViewPool.clear()"))
    }

    @Test
    fun `mode switch is serialized off the click callback`() {
        assertTrue(main.contains("private var shelfLayoutSwitchInFlight = false"))
        val start = main.indexOf("private fun toggleShelfLayoutMode")
        val end = main.indexOf("private fun statusBarHeight", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("shelfLayoutSwitchInFlight"))
        assertTrue(block.contains("shelfGrid.stopScroll()"))
        assertTrue(block.contains("shelfGrid.post"))
        assertTrue(block.contains("shelfListMode = !shelfListMode"))
        assertTrue(block.contains("applyShelfLayoutMode()"))
    }

    @Test
    fun `selection operation mode remains separate from layout mode`() {
        assertTrue(main.contains("handleShelfSelectionPrimaryAction()"))
        assertTrue(main.contains("bookCount > 0 -> \"操作\""))
        assertTrue(main.contains("groupCount == 1 && bookCount == 0 -> \"操作\""))
    }
}
