package com.simplereader.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ShelfSelectionActionsContractTest {
    private val source = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()

    @Test
    fun singleBookSelectionOffersRenameAndGroupMove() {
        val block = source.substringAfter("private fun showShelfSelectionActions()")
            .substringBefore("private fun showMoveSelectedBooksToGroup()")
        assertContains(block, "selectedBooks.size == 1")
        assertContains(block, "修改书名")
        assertContains(block, "导入分组")
        assertContains(block, "showRenameBookDialog(book)")
    }

    @Test
    fun multiBookSelectionKeepsGroupMoveButHidesRename() {
        val block = source.substringAfter("} else {
            AlertDialog.Builder(this)
                .setTitle("已选择 ${'$'}{selectedBooks.size} 本书")")
            .substringBefore("private fun showMoveSelectedBooksToGroup()")
        assertContains(block, "导入分组")
        assertContains(block, "删除")
        assertFalse(block.contains("修改书名"))
    }

    @Test
    fun selectionToolbarUsesActionsInsteadOfDeleteOnly() {
        assertContains(source, "showShelfSelectionActions()")
        assertContains(source, "操作(${'$'}count)")
        assertContains(source, "showMoveSelectedBooksToGroup()")
    }
}
