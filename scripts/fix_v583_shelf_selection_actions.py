from pathlib import Path

MAIN = Path("app/src/main/java/com/simplereader/app/ui/MainActivity.kt")
TEST = Path("app/src/test/java/com/simplereader/app/ui/ShelfSelectionActionsContractTest.kt")

text = MAIN.read_text(encoding="utf-8")

old_click = '''        editButton = findViewById<TextView>(R.id.editButton).apply {
            text = "编辑"
            setOnClickListener {
                if (shelfSelectionMode) {
                    confirmDeleteShelfSelection()
                } else {
                    Toast.makeText(this@MainActivity, "长按书籍或分组可批量选择", Toast.LENGTH_SHORT).show()
                }
            }
        }
'''
new_click = '''        editButton = findViewById<TextView>(R.id.editButton).apply {
            text = "编辑"
            setOnClickListener {
                if (shelfSelectionMode) {
                    showShelfSelectionActions()
                } else {
                    Toast.makeText(this@MainActivity, "长按书籍或分组可批量选择", Toast.LENGTH_SHORT).show()
                }
            }
        }
'''
if old_click in text:
    text = text.replace(old_click, new_click, 1)
elif "showShelfSelectionActions()" not in text:
    raise SystemExit("edit button selection handler not found")

text = text.replace('''            editButton.text = "\\u5220\\u9664"
            moreButton.text = "\\u53d6\\u6d88"
''', '''            editButton.text = "操作"
            moreButton.text = "\\u53d6\\u6d88"
''', 1)

old_buttons = '''    private fun updateShelfSelectionButtons() {
        if (!shelfSelectionMode) return
        val count = selectedShelfBookIds.size + selectedShelfGroupIds.size
        editButton.text = if (count > 0) "\\u5220\\u9664($count)" else "\\u5220\\u9664"
        moreButton.text = "\\u53d6\\u6d88"
    }
'''
new_buttons = '''    private fun updateShelfSelectionButtons() {
        if (!shelfSelectionMode) return
        val count = selectedShelfBookIds.size + selectedShelfGroupIds.size
        editButton.text = if (count > 0) "操作($count)" else "操作"
        moreButton.text = "\\u53d6\\u6d88"
    }
'''
if old_buttons in text:
    text = text.replace(old_buttons, new_buttons, 1)
elif 'editButton.text = if (count > 0) "操作($count)" else "操作"' not in text:
    raise SystemExit("selection button updater not found")

marker = '''    private fun confirmDeleteShelfSelection() {
'''
insert = '''    private fun showShelfSelectionActions() {
        val bookCount = selectedShelfBookIds.size
        val groupCount = selectedShelfGroupIds.size
        val total = bookCount + groupCount
        if (total == 0) {
            Toast.makeText(this, "请先选择书籍或分组", Toast.LENGTH_SHORT).show()
            return
        }

        if (groupCount > 0) {
            AlertDialog.Builder(this)
                .setTitle("已选择 $total 项")
                .setItems(arrayOf("删除")) { _, _ -> confirmDeleteShelfSelection() }
                .show()
            return
        }

        val selectedBooks = books.filter { it.id in selectedShelfBookIds }
        if (selectedBooks.size == 1) {
            val book = selectedBooks.first()
            AlertDialog.Builder(this)
                .setTitle(book.title)
                .setItems(arrayOf("修改书名", "导入分组", "删除")) { _, which ->
                    when (which) {
                        0 -> {
                            exitShelfSelectionMode()
                            showRenameBookDialog(book)
                        }
                        1 -> showMoveSelectedBooksToGroup()
                        2 -> confirmDeleteShelfSelection()
                    }
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("已选择 ${selectedBooks.size} 本书")
                .setItems(arrayOf("导入分组", "删除")) { _, which ->
                    when (which) {
                        0 -> showMoveSelectedBooksToGroup()
                        1 -> confirmDeleteShelfSelection()
                    }
                }
                .show()
        }
    }

    private fun showMoveSelectedBooksToGroup() {
        val selectedIds = selectedShelfBookIds.toSet()
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "请先选择书籍", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val existingGroups = withContext(Dispatchers.IO) {
                bookGroupRepository.getAllGroups().first()
            }
            val labels = (listOf("未分组") + existingGroups.map { group ->
                group.displayName.ifBlank { group.name }
            }).toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("导入分组 · ${selectedIds.size} 本")
                .setItems(labels) { _, which ->
                    lifecycleScope.launch {
                        val targetGroupId = if (which == 0) null else existingGroups[which - 1].id
                        withContext(Dispatchers.IO) {
                            selectedIds.forEach { bookId ->
                                database.bookDao().getBook(bookId)?.let { entity ->
                                    bookRepository.update(entity.copy(groupId = targetGroupId))
                                }
                            }
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "已将 ${selectedIds.size} 本书导入所选分组",
                            Toast.LENGTH_SHORT
                        ).show()
                        exitShelfSelectionMode()
                    }
                }
                .show()
        }
    }

'''
if "private fun showShelfSelectionActions()" not in text:
    if marker not in text:
        raise SystemExit("delete selection marker not found")
    text = text.replace(marker, insert + marker, 1)

MAIN.write_text(text, encoding="utf-8")

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text('''package com.simplereader.app.ui

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
        val block = source.substringAfter("} else {\n            AlertDialog.Builder(this)\n                .setTitle(\"已选择 ${'$'}{selectedBooks.size} 本书\")")
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
''', encoding="utf-8")

print("v583 shelf selection actions applied")
