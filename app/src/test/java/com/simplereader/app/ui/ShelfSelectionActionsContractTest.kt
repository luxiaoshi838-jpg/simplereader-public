package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShelfSelectionActionsContractTest {
    private val source = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()

    @Test
    fun singleBookSelectionOffersRenameAndGroupMove() {
        val block = source.substringAfter("private fun showShelfSelectionActions()")
            .substringBefore("private fun showMoveSelectedBooksToGroup()")
        assertTrue(block.contains("selectedBooks.size == 1"))
        assertTrue(block.contains("修改书名"))
        assertTrue(block.contains("导入分组"))
        assertTrue(block.contains("showRenameBookDialog(book)"))
    }

    @Test
    fun multiBookSelectionKeepsGroupMoveButHidesRename() {
        val marker = ".setTitle(\"已选择 \${selectedBooks.size} 本书\")"
        val block = source.substringAfter(marker)
            .substringBefore("private fun showMoveSelectedBooksToGroup()")
        assertTrue(block.contains("导入分组"))
        assertTrue(block.contains("删除"))
        assertFalse(block.contains("修改书名"))
    }

    @Test
    fun selectionToolbarUsesActionsInsteadOfDeleteOnly() {
        assertTrue(source.contains("showShelfSelectionActions()"))
        assertTrue(source.contains("操作(\$count)"))
        assertTrue(source.contains("showMoveSelectedBooksToGroup()"))
    }
}
