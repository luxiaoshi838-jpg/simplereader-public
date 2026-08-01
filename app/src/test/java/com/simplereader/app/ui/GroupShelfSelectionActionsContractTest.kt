package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroupShelfSelectionActionsContractTest {
    private val source = File("src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt").readText()

    @Test
    fun singleSelectionOffersRenameMoveAndDelete() {
        val block = source.substringAfter("private fun showSelectionActions()")
            .substringBefore("private fun moveSelectedBooksToGroup()")
        assertTrue(block.contains("selectedBooks.size == 1"))
        assertTrue(block.contains("修改书名"))
        assertTrue(block.contains("导入分组"))
        assertTrue(block.contains("showRenameBookDialog(book)"))
    }

    @Test
    fun multipleSelectionHidesRename() {
        val marker = ".setTitle(\"已选择 \${selectedBooks.size} 本书\")"
        val block = source.substringAfter(marker)
            .substringBefore("private fun moveSelectedBooksToGroup()")
        assertTrue(block.contains("导入分组"))
        assertTrue(block.contains("删除"))
        assertFalse(block.contains("修改书名"))
    }

    @Test
    fun groupToolbarUsesActionEntry() {
        assertTrue(source.contains("showSelectionActions()"))
        assertTrue(source.contains("操作(\$count)"))
        assertTrue(source.contains("moveSelectedBooksToGroup()"))
    }
}
