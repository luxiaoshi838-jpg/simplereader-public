from pathlib import Path

MAIN = Path("app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt")
TEST = Path("app/src/test/java/com/simplereader/app/ui/GroupShelfSelectionActionsContractTest.kt")

text = MAIN.read_text(encoding="utf-8-sig")

if "import com.simplereader.app.data.repository.BookGroupRepository" not in text:
    text = text.replace(
        "import com.simplereader.app.data.repository.BookRepository\n",
        "import com.simplereader.app.data.repository.BookRepository\nimport com.simplereader.app.data.repository.BookGroupRepository\n",
        1,
    )
if "import kotlinx.coroutines.flow.first" not in text:
    text = text.replace(
        "import kotlinx.coroutines.flow.collectLatest\n",
        "import kotlinx.coroutines.flow.collectLatest\nimport kotlinx.coroutines.flow.first\n",
        1,
    )

if "private lateinit var bookGroupRepository" not in text:
    text = text.replace(
        "    private lateinit var bookRepository: BookRepository\n",
        "    private lateinit var bookRepository: BookRepository\n    private lateinit var bookGroupRepository: BookGroupRepository\n",
        1,
    )
if "private var currentBooks = emptyList<ShelfBookItem>()" not in text:
    text = text.replace(
        "    private val selectedBookIds = linkedSetOf<Long>()\n",
        "    private val selectedBookIds = linkedSetOf<Long>()\n    private var currentBooks = emptyList<ShelfBookItem>()\n",
        1,
    )

if "bookGroupRepository = BookGroupRepository" not in text:
    text = text.replace(
        "        bookRepository = BookRepository(database.bookDao())\n",
        "        bookRepository = BookRepository(database.bookDao())\n        bookGroupRepository = BookGroupRepository(database.bookGroupDao())\n",
        1,
    )

if "currentBooks = books" not in text:
    text = text.replace(
        "            bookRepository.getShelfBooksByGroup(groupId).collectLatest { books ->\n",
        "            bookRepository.getShelfBooksByGroup(groupId).collectLatest { books ->\n                currentBooks = books\n",
        1,
    )

text = text.replace('                    text = "\\u5220\\u9664"\n', '                    text = "操作"\n', 1)
text = text.replace(
    "                    setTextColor(Color.rgb(235, 96, 48))\n",
    "                    setTextColor(ReaderAppearance.shelfTextColor(this@GroupBooksActivity))\n",
    1,
)
text = text.replace(
    "                    setOnClickListener { confirmDeleteSelection() }\n",
    "                    setOnClickListener { showSelectionActions() }\n",
    1,
)
text = text.replace(
    "        deleteButton.setTextColor(Color.rgb(235, 96, 48))\n",
    "        deleteButton.setTextColor(ReaderAppearance.shelfTextColor(this))\n",
    1,
)

old_chrome = '''        titleView.text = if (selectionMode) "\\u5df2\\u9009\\u62e9 $count" else groupName
        deleteButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
        cancelButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
'''
new_chrome = '''        titleView.text = if (selectionMode) "\\u5df2\\u9009\\u62e9 $count" else groupName
        deleteButton.text = if (selectionMode) "操作($count)" else "操作"
        deleteButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
        cancelButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
'''
if old_chrome in text:
    text = text.replace(old_chrome, new_chrome, 1)
elif 'deleteButton.text = if (selectionMode) "操作($count)" else "操作"' not in text:
    raise SystemExit("selection chrome block not found")

marker = "    private fun openBook(bookId: Long) {\n"
insert = '''    private fun showSelectionActions() {
        val selectedBooks = currentBooks.filter { it.id in selectedBookIds }
        if (selectedBooks.isEmpty()) {
            Toast.makeText(this, "请先选择书籍", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedBooks.size == 1) {
            val book = selectedBooks.first()
            AlertDialog.Builder(this)
                .setTitle(book.title)
                .setItems(arrayOf("修改书名", "导入分组", "删除")) { _, which ->
                    when (which) {
                        0 -> {
                            exitSelectionMode()
                            showRenameBookDialog(book)
                        }
                        1 -> moveSelectedBooksToGroup()
                        2 -> confirmDeleteSelection()
                    }
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("已选择 ${selectedBooks.size} 本书")
                .setItems(arrayOf("导入分组", "删除")) { _, which ->
                    when (which) {
                        0 -> moveSelectedBooksToGroup()
                        1 -> confirmDeleteSelection()
                    }
                }
                .show()
        }
    }

    private fun moveSelectedBooksToGroup() {
        val selectedIds = selectedBookIds.toSet()
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
            AlertDialog.Builder(this@GroupBooksActivity)
                .setTitle("导入分组 · ${selectedIds.size} 本")
                .setItems(labels) { _, which ->
                    lifecycleScope.launch {
                        val targetGroupId = if (which == 0) null else existingGroups[which - 1].id
                        withContext(Dispatchers.IO) {
                            selectedIds.forEach { bookId ->
                                bookRepository.getBook(bookId)?.let { entity ->
                                    bookRepository.update(entity.copy(groupId = targetGroupId))
                                }
                            }
                        }
                        Toast.makeText(
                            this@GroupBooksActivity,
                            "已将 ${selectedIds.size} 本书导入所选分组",
                            Toast.LENGTH_SHORT
                        ).show()
                        exitSelectionMode()
                    }
                }
                .show()
        }
    }

'''
if "private fun showSelectionActions()" not in text:
    if marker not in text:
        raise SystemExit("openBook marker not found")
    text = text.replace(marker, insert + marker, 1)

MAIN.write_text(text, encoding="utf-8")

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text('''package com.simplereader.app.ui

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
        val marker = ".setTitle(\\\"已选择 \\${selectedBooks.size} 本书\\\")"
        val block = source.substringAfter(marker)
            .substringBefore("private fun moveSelectedBooksToGroup()")
        assertTrue(block.contains("导入分组"))
        assertTrue(block.contains("删除"))
        assertFalse(block.contains("修改书名"))
    }

    @Test
    fun groupToolbarUsesActionEntry() {
        assertTrue(source.contains("showSelectionActions()"))
        assertTrue(source.contains("操作(\\$count)"))
        assertTrue(source.contains("moveSelectedBooksToGroup()"))
    }
}
''', encoding="utf-8")

print("v584 group selection actions applied")
