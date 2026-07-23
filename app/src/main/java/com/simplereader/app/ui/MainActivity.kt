package com.simplereader.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.simplereader.app.R
import com.simplereader.app.data.backup.LocalLibraryScanner
import com.simplereader.app.data.backup.SimpleReaderBackupDecoder
import com.simplereader.app.data.backup.SimpleReaderBackupRestorer
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.BookGroup
import com.simplereader.app.data.model.DuplicateStatus
import com.simplereader.app.data.model.DuplicateStrategy
import com.simplereader.app.data.model.ImportCandidate
import com.simplereader.app.data.model.ImportItemResult
import com.simplereader.app.data.model.ImportPlanOptions
import com.simplereader.app.data.model.ImportSummary
import com.simplereader.app.data.model.PermissionStatus
import com.simplereader.app.data.model.ShelfBookItem
import com.simplereader.app.data.model.TargetGroupMode
import com.simplereader.app.data.repository.BookGroupRepository
import com.simplereader.app.data.repository.BookRepository
import com.simplereader.app.data.repository.FolderGrouping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var bookGroupRepository: BookGroupRepository
    private lateinit var shelfGrid: GridLayout
    private lateinit var readingStatsTextView: TextView
    private lateinit var shelfTabTextView: TextView
    private var books = emptyList<ShelfBookItem>()
    private var groups = emptyList<BookGroup>()
    private var showingHistory = false
    private var shelfSearchQuery = ""
    private var selectedGroupId: Long? = null
    private var pendingBackup: SimpleReaderBackupDecoder.DecodedBackup? = null

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val permissionStatus = persistReadPermission(uri)
            DocumentFile.fromTreeUri(this, uri)?.let { root ->
                scanAndImportFolder(root, uri.toString(), permissionStatus)
            }
        }
    }

    private val openFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val permissionByUri = uris.associateWith(::persistReadPermission)
            importSelectedFiles(permissionByUri)
        }
    }

    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            rememberBackupLocation(uri)
            exportReaderData(uri)
        }
    }

    private val importDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prepareBackupRestore(uri)
        }
    }

    private val backupRootLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val backup = pendingBackup
        pendingBackup = null
        if (backup == null) {
            return@registerForActivityResult
        }
        if (uri == null) {
            Toast.makeText(this, "已取消恢复，未写入任何备份数据", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        val permissionStatus = persistReadPermission(uri)
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) {
            Toast.makeText(this, "无法读取所选原书总文件夹，未写入任何备份数据", Toast.LENGTH_LONG).show()
        } else {
            restoreBackupFromRoot(
                backup = backup,
                root = root,
                sourceTreeUri = uri.toString(),
                permissionStatus = permissionStatus
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        database = SimpleReaderDatabase.getDatabase(this)
        bookRepository = BookRepository(database.bookDao())
        bookGroupRepository = BookGroupRepository(database.bookGroupDao())
        shelfGrid = findViewById(R.id.shelfGrid)
        readingStatsTextView = findViewById(R.id.readingStatsTextView)
        shelfTabTextView = findViewById(R.id.shelfTabTextView)

        shelfTabTextView.apply {
            text = "书架"
            setOnClickListener {
                showingHistory = false
                selectedGroupId = null
                updateUI()
            }
        }
        findViewById<TextView>(R.id.historyTabTextView).apply {
            text = "阅读历史"
            setOnClickListener {
                showingHistory = true
                selectedGroupId = null
                updateUI()
            }
        }
        findViewById<TextView>(R.id.exportButton).apply {
            text = "数据导出"
            setOnClickListener { showDataExportOptions() }
        }

        findViewById<TextView>(R.id.importButton).apply {
            text = "导入"
            setOnClickListener { showImportOptions() }
        }
        findViewById<TextView>(R.id.editButton).apply {
            text = "编辑"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "长按书籍或分组可管理", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.searchButton).apply {
            text = "⌕"
            setOnClickListener { showShelfSearch() }
        }
        findViewById<TextView>(R.id.moreButton).apply {
            text = "⋮"
            contentDescription = "批量管理分组"
            setOnClickListener { showBatchGroupManagement() }
        }

        loadBooks()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedGroupId != null) {
            selectedGroupId = null
            shelfSearchQuery = ""
            updateUI()
        } else {
            super.onBackPressed()
        }
    }

    private fun loadBooks() {
        lifecycleScope.launch {
            combine(
                bookRepository.getShelfBooks(),
                bookGroupRepository.getAllGroups()
            ) { bookList, groupList ->
                bookList to groupList
            }.collectLatest { (bookList, groupList) ->
                books = bookList
                groups = groupList
                updateUI()
            }
        }
    }

    private fun updateUI() {
        shelfGrid.removeAllViews()
        val filteredBooks = books.filter {
            shelfSearchQuery.isBlank() || it.title.contains(shelfSearchQuery, ignoreCase = true)
        }
        val visibleBooks = if (showingHistory) {
            filteredBooks.filter { it.lastReadTime > 0L }
        } else {
            filteredBooks
        }.sortedByDescending(::activityTime)

        val activeGroup = selectedGroupId?.let { id -> groups.firstOrNull { it.id == id } }
        if (selectedGroupId != null && activeGroup == null) {
            selectedGroupId = null
        }
        shelfTabTextView.text = if (selectedGroupId == null) "书架" else "‹ 全部书架"

        if (!showingHistory && selectedGroupId != null) {
            val group = groups.firstOrNull { it.id == selectedGroupId }
            val groupBooks = visibleBooks.filter { it.groupId == selectedGroupId }
            readingStatsTextView.text = "${group?.displayName?.ifBlank { group.name } ?: "分组"} · ${groupBooks.size} 本"
            groupBooks.forEach { addBookCard(it) }
            if (groupBooks.isEmpty()) addEmptyText("该分组暂无书籍")
            return
        }

        readingStatsTextView.text = "累计导入 ${books.size} 本"

        if (showingHistory) {
            visibleBooks.forEach { addBookCard(it) }
            if (visibleBooks.isEmpty()) addEmptyText("暂无阅读历史")
            return
        }

        val booksByGroup = visibleBooks.groupBy { it.groupId }
        groups.mapNotNull { group ->
            val groupBooks = booksByGroup[group.id].orEmpty().sortedByDescending(::activityTime)
            if (groupBooks.isEmpty()) null else group to groupBooks
        }.sortedByDescending { (_, groupBooks) ->
            groupBooks.maxOf(::activityTime)
        }.forEach { (group, groupBooks) ->
            addGroupCard(group, groupBooks)
        }

        booksByGroup[null].orEmpty()
            .sortedByDescending(::activityTime)
            .forEach { addBookCard(it) }

        if (visibleBooks.isEmpty()) {
            addEmptyText(if (shelfSearchQuery.isBlank()) "点击导入选择小说文件夹" else "没有匹配的书籍")
        }
    }

    private fun addGroupCard(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        val sortedBooks = groupBooks.sortedByDescending(::activityTime)
        val card = createShelfCard()
        val cover = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(232, 229, 220))
            sortedBooks.take(4).forEach { book ->
                addView(createBookCover(book.title, book.format, compact = true))
            }
            repeat((4 - sortedBooks.take(4).size).coerceAtLeast(0)) {
                addView(TextView(this@MainActivity).apply {
                    setBackgroundColor(Color.rgb(205, 202, 194))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(42)
                        height = dp(48)
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                })
            }
        }
        card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)))
        card.addView(TextView(this).apply {
            text = group.displayName.ifBlank { group.name }
            textSize = 18f
            setTextColor(Color.rgb(30, 30, 30))
            maxLines = 2
        })
        card.addView(TextView(this).apply {
            text = "共 ${sortedBooks.size} 本"
            textSize = 14f
            setTextColor(Color.rgb(130, 126, 118))
        })
        card.setOnClickListener { showGroupBooksV2(group, sortedBooks) }
        card.setOnLongClickListener {
            showGroupActions(group, sortedBooks)
            true
        }
        shelfGrid.addView(card)
    }

    private fun addBookCard(book: ShelfBookItem) {
        val card = createShelfCard()
        card.addView(createBookCover(book.title, book.format, compact = false))
        card.addView(TextView(this).apply {
            text = book.title
            textSize = 18f
            setTextColor(Color.rgb(30, 30, 30))
            maxLines = 2
        })
        card.addView(TextView(this).apply {
            val status = if (book.fileStatus == "AVAILABLE") "" else " · ${book.fileStatus}"
            text = "已读 ${book.progressPercent()}%$status"
            textSize = 14f
            setTextColor(Color.rgb(130, 126, 118))
        })
        card.setOnClickListener { openBook(book.id) }
        card.setOnLongClickListener {
            showBookActionsV2(book)
            true
        }
        shelfGrid.addView(card)
    }

    private fun createBookCover(title: String, format: String, compact: Boolean): TextView {
        val cover = TextView(this).apply {
            text = if (compact) {
                "${title.take(9)}"
            } else {
                "${title.take(22)}"
            }
            setTextColor(Color.rgb(232, 238, 244))
            textSize = if (compact) 7.5f else 12f
            gravity = Gravity.CENTER
            maxLines = if (compact) 4 else 5
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(74, 126, 172), Color.rgb(47, 94, 136))
            ).apply {
                cornerRadius = dp(if (compact) 3 else 5).toFloat()
            }
        }
        cover.layoutParams = if (compact) {
            GridLayout.LayoutParams().apply {
                width = dp(42)
                height = dp(48)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112))
        }
        return cover
    }

    private fun createShelfCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = 0
            layoutParams = GridLayout.LayoutParams().apply {
                width = shelfCardWidth()
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1)
                setMargins(dp(3), 0, dp(3), dp(18))
            }
        }
    }

    private fun shelfCardWidth(): Int {
        val horizontalPadding = dp(16 * 2 + 14)
        val itemMargins = dp(3 * 2 * 3)
        return ((resources.displayMetrics.widthPixels - horizontalPadding - itemMargins) / 3)
            .coerceAtLeast(dp(82))
    }

    private fun addEmptyText(message: String) {
        shelfGrid.addView(TextView(this).apply {
            text = message
            textSize = 18f
            setTextColor(Color.rgb(110, 106, 98))
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                this.width = GridLayout.LayoutParams.MATCH_PARENT
                this.height = dp(160)
            }
        })
    }

    private fun openBook(bookId: Long) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bookId))
    }

    private fun showGroupBooks(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        showGroupBooksV2(group, groupBooks)
    }

    private fun showGroupActions(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        AlertDialog.Builder(this)
            .setTitle(group.displayName.ifBlank { group.name })
            .setItems(arrayOf("打开分组", "管理分组", "重命名分组", "删除分组")) { _, which ->
                when (which) {
                    0 -> showGroupBooksV2(group, groupBooks)
                    1 -> showGroupManagement(group, groupBooks)
                    2 -> showRenameGroupDialog(group)
                    3 -> confirmDeleteGroup(group, groupBooks)
                }
            }
            .show()
    }

    private fun showGroupManagement(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        val sortedBooks = groupBooks.sortedByDescending(::activityTime)
        val selected = BooleanArray(sortedBooks.size)
        val labels = sortedBooks.map { book ->
            "${book.title} · 已读 ${book.progressPercent()}%"
        }.toTypedArray()

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(10), dp(6))
        }
        val titleText = TextView(this).apply {
            text = "分组管理 · ${group.displayName.ifBlank { group.name }}"
            textSize = 20f
            setTextColor(Color.rgb(30, 30, 30))
            maxLines = 1
        }
        val selectAllButton = TextView(this).apply {
            text = "全选"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(45, 105, 160))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            contentDescription = "全选分组书籍"
        }
        titleBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleBar.addView(
            selectAllButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleBar)
            .setMultiChoiceItems(labels, selected) { _, which, isChecked ->
                selected[which] = isChecked
            }
            .setNegativeButton("关闭", null)
            .setNeutralButton("移出分组", null)
            .setPositiveButton("删除书架", null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            }
            selectAllButton.setOnClickListener {
                selected.indices.forEach { index ->
                    selected[index] = true
                    dialog.listView.setItemChecked(index, true)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selectedBooks = sortedBooks.filterIndexed { index, _ -> selected[index] }
                if (selectedBooks.isEmpty()) {
                    Toast.makeText(this@MainActivity, "请先选择书籍", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        selectedBooks.forEach { book ->
                            database.bookDao().getBook(book.id)?.let { entity ->
                                bookRepository.update(entity.copy(groupId = null))
                            }
                        }
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "已将 ${selectedBooks.size} 本书移到未分组",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedBooks = sortedBooks.filterIndexed { index, _ -> selected[index] }
                if (selectedBooks.isEmpty()) {
                    Toast.makeText(this@MainActivity, "请先选择书籍", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("批量删除书架")
                    .setMessage("将从书架移除 ${selectedBooks.size} 本书，不删除原文件。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                selectedBooks.forEach { book ->
                                    database.bookDao().deleteById(book.id)
                                }
                            }
                            Toast.makeText(
                                this@MainActivity,
                                "已从书架移除 ${selectedBooks.size} 本书",
                                Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                        }
                    }
                    .show()
            }
        }
        dialog.show()
    }

    private fun showBatchGroupManagement() {
        val manageableGroups = groups.sortedBy { group ->
            group.displayName.ifBlank { group.name }.lowercase()
        }
        if (manageableGroups.isEmpty()) {
            Toast.makeText(this, "暂无可管理的分组", Toast.LENGTH_SHORT).show()
            return
        }

        val selected = BooleanArray(manageableGroups.size)
        val bookCounts = books.groupingBy { it.groupId }.eachCount()
        val labels = manageableGroups.map { group ->
            val name = group.displayName.ifBlank { group.name }
            "$name · ${bookCounts[group.id] ?: 0} 本"
        }.toTypedArray()

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(10), dp(6))
        }
        val titleText = TextView(this).apply {
            text = "批量管理分组"
            textSize = 20f
            setTextColor(Color.rgb(30, 30, 30))
            maxLines = 1
        }
        val selectAllButton = TextView(this).apply {
            text = "全选"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(45, 105, 160))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            contentDescription = "全选分组"
        }
        titleBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleBar.addView(
            selectAllButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleBar)
            .setMultiChoiceItems(labels, selected) { _, which, isChecked ->
                selected[which] = isChecked
            }
            .setNegativeButton("关闭", null)
            .setPositiveButton("删除分组", null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            }
            selectAllButton.setOnClickListener {
                selected.indices.forEach { index ->
                    selected[index] = true
                    dialog.listView.setItemChecked(index, true)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedGroups = manageableGroups.filterIndexed { index, _ -> selected[index] }
                if (selectedGroups.isEmpty()) {
                    Toast.makeText(this@MainActivity, "请先选择分组", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val selectedGroupIds = selectedGroups.map { it.id }.toSet()
                val selectedBooks = books.filter { it.groupId in selectedGroupIds }
                val choiceDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("批量删除 ${selectedGroups.size} 个分组")
                    .setMessage(
                        "请选择处理组内 ${selectedBooks.size} 本书的方式：\n\n" +
                            "回归书架：只删除分组，书籍移到未分组。\n" +
                            "全部删除：删除分组，并从书架移除这些书籍。\n\n" +
                            "两种方式都不会删除手机中的原文件。"
                    )
                    .setNegativeButton("取消", null)
                    .setNeutralButton("回归书架", null)
                    .setPositiveButton("全部删除", null)
                    .create()

                choiceDialog.setOnShowListener {
                    choiceDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        lifecycleScope.launch {
                            val error = withContext(Dispatchers.IO) {
                                runCatching {
                                    database.withTransaction {
                                        selectedGroups.forEach { group ->
                                            database.bookDao().clearGroup(group.id)
                                            database.bookGroupDao().deleteById(group.id)
                                        }
                                    }
                                }.exceptionOrNull()
                            }
                            if (error == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "已删除 ${selectedGroups.size} 个分组，${selectedBooks.size} 本书已回归书架",
                                    Toast.LENGTH_SHORT
                                ).show()
                                choiceDialog.dismiss()
                                dialog.dismiss()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "删除分组失败：${error.message ?: "未知错误"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    choiceDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        lifecycleScope.launch {
                            val error = withContext(Dispatchers.IO) {
                                runCatching {
                                    database.withTransaction {
                                        selectedBooks.distinctBy { it.id }.forEach { book ->
                                            database.bookDao().deleteById(book.id)
                                        }
                                        selectedGroups.forEach { group ->
                                            database.bookGroupDao().deleteById(group.id)
                                        }
                                    }
                                }.exceptionOrNull()
                            }
                            if (error == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "已删除 ${selectedGroups.size} 个分组及 ${selectedBooks.size} 本书",
                                    Toast.LENGTH_SHORT
                                ).show()
                                choiceDialog.dismiss()
                                dialog.dismiss()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "全部删除失败：${error.message ?: "未知错误"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                choiceDialog.show()
            }
        }
        dialog.show()
    }

    private fun showRenameGroupDialog(group: BookGroup) {
        val input = EditText(this).apply {
            hint = "分组名称"
            setText(group.displayName.ifBlank { group.name })
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("重命名分组")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isBlank()) {
                    input.error = "分组名称不能为空"
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            bookGroupRepository.rename(group.id, newName)
                        }.exceptionOrNull()
                    }
                    if (error == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "分组已重命名为：$newName",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "保存失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteGroup(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        val groupName = group.displayName.ifBlank { group.name }
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除分组 · $groupName")
            .setMessage(
                "请选择处理组内 ${groupBooks.size} 本书的方式：\n\n" +
                    "回归书架：只删除分组，书籍移到未分组。\n" +
                    "全部删除：删除分组，并从书架移除组内全部书籍。\n\n" +
                    "两种方式都不会删除手机中的原文件。"
            )
            .setNegativeButton("取消", null)
            .setNeutralButton("回归书架", null)
            .setPositiveButton("全部删除", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            database.withTransaction {
                                database.bookDao().clearGroup(group.id)
                                database.bookGroupDao().deleteById(group.id)
                            }
                        }.exceptionOrNull()
                    }
                    if (error == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "分组已删除，${groupBooks.size} 本书已回归书架",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "删除分组失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            database.withTransaction {
                                groupBooks.forEach { book ->
                                    database.bookDao().deleteById(book.id)
                                }
                                database.bookGroupDao().deleteById(group.id)
                            }
                        }.exceptionOrNull()
                    }
                    if (error == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "分组及 ${groupBooks.size} 本书已从书架删除",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "全部删除失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showBookActions(book: ShelfBookItem) {
        showBookActionsV2(book)
    }

    private fun showGroupBooksV2(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        if (groupBooks.isEmpty()) {
            Toast.makeText(this, "该分组暂无书籍", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, GroupBooksActivity::class.java)
                .putExtra(GroupBooksActivity.EXTRA_GROUP_ID, group.id)
                .putExtra(GroupBooksActivity.EXTRA_GROUP_NAME, group.displayName.ifBlank { group.name })
        )
    }

    private fun showBookActionsV2(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf("打开", "移入分组", "删除书架")) { _, which ->
                when (which) {
                    0 -> openBook(book.id)
                    1 -> showMoveBookToGroup(book)
                    2 -> confirmDeleteBook(book)
                }
            }
            .show()
    }

    private fun showMoveBookToGroup(book: ShelfBookItem) {
        lifecycleScope.launch {
            val existingGroups = withContext(Dispatchers.IO) {
                bookGroupRepository.getAllGroups().first()
            }
            val labels = (listOf("未分组") + existingGroups.map { group ->
                group.displayName.ifBlank { group.name }
            }).toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("导入分组")
                .setItems(labels) { _, which ->
                    lifecycleScope.launch {
                        val targetGroupId = if (which == 0) null else existingGroups[which - 1].id
                        withContext(Dispatchers.IO) {
                            database.bookDao().getBook(book.id)?.let { entity ->
                                bookRepository.update(entity.copy(groupId = targetGroupId))
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun confirmDeleteBook(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle("删除书架")
            .setMessage("只从书架移除记录，不删除原文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        database.bookDao().deleteById(book.id)
                    }
                }
            }
            .show()
    }

    private fun showShelfSearch() {
        val input = EditText(this).apply {
            hint = "搜索书名"
            setText(shelfSearchQuery)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("搜索书架")
            .setView(input)
            .setNegativeButton("清除") { _, _ ->
                shelfSearchQuery = ""
                updateUI()
            }
            .setPositiveButton("搜索") { _, _ ->
                shelfSearchQuery = input.text.toString().trim()
                updateUI()
            }
            .show()
    }

    private fun showSettings() {
        val modes = arrayOf("跟随系统", "浅色模式", "深色模式")
        val values = arrayOf(AppTheme.MODE_SYSTEM, AppTheme.MODE_LIGHT, AppTheme.MODE_DARK)
        val checkedIndex = values.indexOf(AppTheme.currentMode(this)).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setSingleChoiceItems(modes, checkedIndex) { dialog, which ->
                AppTheme.setMode(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDataExportOptions() {
        AlertDialog.Builder(this)
            .setTitle("数据导出")
            .setItems(arrayOf("导出", "同步")) { _, which ->
                when (which) {
                    0 -> launchDataExport()
                    1 -> syncDataExport()
                }
            }
            .show()
    }

    private fun launchDataExport() {
        val stamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        exportDataLauncher.launch("简阅数据备份_${stamp}.json")
    }

    private fun syncDataExport() {
        val savedUri = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE)
            .getString(BACKUP_URI_KEY, null)
        if (savedUri.isNullOrBlank()) {
            Toast.makeText(this, "尚未选择过导出文件，请先执行一次导出", Toast.LENGTH_SHORT).show()
            launchDataExport()
            return
        }
        exportReaderData(Uri.parse(savedUri), isSync = true)
    }

    private fun rememberBackupLocation(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE)
            .edit()
            .putString(BACKUP_URI_KEY, uri.toString())
            .apply()
    }

    private fun exportReaderData(targetUri: Uri, isSync: Boolean = false) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = org.json.JSONObject()
                    val manifest = org.json.JSONObject()
                    val relationships = org.json.JSONObject()
                    val tablesJson = org.json.JSONObject()
                    val databaseHandle = database.openHelper.readableDatabase

                    manifest.put("format", "SimpleReaderBackup")
                    manifest.put("schemaVersion", BACKUP_SCHEMA_VERSION)
                    manifest.put("minimumCompatibleSchemaVersion", 1)
                    manifest.put("appPackage", packageName)
                    manifest.put("databaseVersion", 2)
                    manifest.put("exportedAtEpochMillis", System.currentTimeMillis())
                    manifest.put("encoding", "UTF-8")
                    manifest.put(
                        "includedData",
                        org.json.JSONArray(listOf("books", "book_groups", "bookmarks", "read_progress"))
                    )

                    relationships.put("books.groupId", "book_groups.id")
                    relationships.put("bookmarks.bookId", "books.id")
                    relationships.put("read_progress.bookId", "books.id")
                    relationships.put(
                        "bookLocationFields",
                        org.json.JSONArray(
                            listOf(
                                "books.filePath",
                                "books.sourceTreeUri",
                                "books.relativePath",
                                "books.fileName",
                                "books.format"
                            )
                        )
                    )

                    listOf("book_groups", "books", "bookmarks", "read_progress").forEach { table ->
                        val rows = org.json.JSONArray()
                        databaseHandle.query("SELECT * FROM $table").use { cursor ->
                            while (cursor.moveToNext()) {
                                val row = org.json.JSONObject()
                                for (columnIndex in 0 until cursor.columnCount) {
                                    val value: Any = when (cursor.getType(columnIndex)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> org.json.JSONObject.NULL
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(columnIndex)
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(columnIndex)
                                        android.database.Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(
                                            cursor.getBlob(columnIndex),
                                            android.util.Base64.NO_WRAP
                                        )
                                        else -> cursor.getString(columnIndex)
                                    }
                                    row.put(cursor.getColumnName(columnIndex), value)
                                }
                                rows.put(row)
                            }
                        }
                        tablesJson.put(table, rows)
                    }

                    root.put("manifest", manifest)
                    root.put("relationships", relationships)
                    root.put("tables", tablesJson)

                    val output = contentResolver.openOutputStream(targetUri, "wt")
                        ?: error("无法打开所选保存位置")
                    output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(root.toString(2))
                    }

                    val bookCount = tablesJson.getJSONArray("books").length()
                    val groupCount = tablesJson.getJSONArray("book_groups").length()
                    val bookmarkCount = tablesJson.getJSONArray("bookmarks").length()
                    val progressCount = tablesJson.getJSONArray("read_progress").length()
                    val action = if (isSync) "同步" else "导出"
                    "${action}完成：书籍 $bookCount 本、分组 $groupCount 个、书签 $bookmarkCount 条、阅读进度 $progressCount 条"
                }
            }

            result.onSuccess { summary ->
                rememberBackupLocation(targetUri)
                Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                if (isSync) {
                    getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE)
                        .edit()
                        .remove(BACKUP_URI_KEY)
                        .apply()
                }
                Toast.makeText(
                    this@MainActivity,
                    "数据${if (isSync) "同步" else "导出"}失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun prepareBackupRestore(sourceUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(sourceUri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("无法读取所选备份文件")
                    SimpleReaderBackupDecoder.decode(text)
                }
            }
            result.onSuccess { backup ->
                restoreBackupFromSavedLocations(backup)
            }.onFailure { error ->
                pendingBackup = null
                Toast.makeText(
                    this@MainActivity,
                    "备份识别失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun restoreBackupFromSavedLocations(
        backup: SimpleReaderBackupDecoder.DecodedBackup
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SimpleReaderBackupRestorer(
                        context = this@MainActivity,
                        database = database
                    ).restore(
                        backup = backup,
                        scannedFiles = emptyList()
                    )
                }
            }
            result.onSuccess { summary ->
                pendingBackup = null
                selectedGroupId = null
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("旧备份恢复完成")
                    .setMessage(summary.message())
                    .setPositiveButton("确定", null)
                    .show()
            }.onFailure { error ->
                pendingBackup = null
                Toast.makeText(
                    this@MainActivity,
                    "恢复失败，数据库未完成写入：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun restoreBackupFromRoot(
        backup: SimpleReaderBackupDecoder.DecodedBackup,
        root: DocumentFile,
        sourceTreeUri: String,
        permissionStatus: PermissionStatus
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val scannedFiles = LocalLibraryScanner(FOLDER_IMPORT_LIMIT).scan(
                        root = root,
                        sourceTreeUri = sourceTreeUri,
                        permissionStatus = permissionStatus
                    )
                    SimpleReaderBackupRestorer(
                        context = this@MainActivity,
                        database = database
                    ).restore(
                        backup = backup,
                        scannedFiles = scannedFiles
                    )
                }
            }
            result.onSuccess { summary ->
                selectedGroupId = null
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("旧备份恢复完成")
                    .setMessage(summary.message())
                    .setPositiveButton("确定", null)
                    .show()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "恢复失败，数据库未完成写入：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showImportOptions() {
        AlertDialog.Builder(this)
            .setTitle("导入与恢复")
            .setItems(
                arrayOf(
                    "选择按文件夹导入",
                    "选择单个或多个文件",
                    "从旧备份恢复（自动按备份位置）"
                )
            ) { _, which ->
                when (which) {
                    0 -> openFolderLauncher.launch(null)
                    1 -> openFilesLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "application/epub+zip",
                            "application/vnd.ms-htmlhelp",
                            "application/x-chm",
                            "application/octet-stream"
                        )
                    )
                    2 -> importDataLauncher.launch(
                        arrayOf("application/json", "text/json", "application/octet-stream")
                    )
                }
            }
            .show()
    }

    private fun persistReadPermission(uri: Uri): PermissionStatus {
        return try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PermissionStatus.PERSISTED
        } catch (_: SecurityException) {
            Toast.makeText(this, "该来源无法持久授权，重启后可能需要重新选择", Toast.LENGTH_SHORT).show()
            PermissionStatus.SESSION_ONLY
        }
    }

    private fun scanAndImportFolder(
        root: DocumentFile,
        sourceTreeUri: String,
        permissionStatus: PermissionStatus
    ) {
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                val result = mutableListOf<ImportCandidate>()
                collectFolderCandidates(
                    folder = root,
                    sourceTreeUri = sourceTreeUri,
                    relativePath = root.name ?: "未命名文件夹",
                    permissionStatus = permissionStatus,
                    candidates = result
                )
                result
            }
            showFolderImportMode(candidates)
        }
    }

    private suspend fun collectFolderCandidates(
        folder: DocumentFile,
        sourceTreeUri: String,
        relativePath: String,
        permissionStatus: PermissionStatus,
        candidates: MutableList<ImportCandidate>
    ) {
        if (candidates.size >= FOLDER_IMPORT_LIMIT) return
        folder.listFiles().forEach { file ->
            if (candidates.size >= FOLDER_IMPORT_LIMIT) return@forEach
            if (file.isDirectory) {
                val childName = file.name ?: return@forEach
                collectFolderCandidates(
                    folder = file,
                    sourceTreeUri = sourceTreeUri,
                    relativePath = joinPath(relativePath, childName),
                    permissionStatus = permissionStatus,
                    candidates = candidates
                )
            } else if (file.isSupportedBook()) {
                candidates += buildCandidate(
                    file = file,
                    sourceTreeUri = sourceTreeUri,
                    relativeParentPath = relativePath,
                    permissionStatus = permissionStatus
                )
            }
        }
    }

    private fun importSelectedFiles(permissionByUri: Map<Uri, PermissionStatus>) {
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                permissionByUri.mapNotNull { (uri, permissionStatus) ->
                    DocumentFile.fromSingleUri(this@MainActivity, uri)
                        ?.takeIf { it.isSupportedBook() }
                        ?.let { file ->
                            buildCandidate(
                                file = file,
                                sourceTreeUri = null,
                                relativeParentPath = null,
                                permissionStatus = permissionStatus
                            ).copy(
                                suggestedGroupKey = null,
                                suggestedGroupName = null,
                                suggestedGroupRelativePath = null
                            )
                        }
                }
            }
            showImportPreview(
                candidates = candidates,
                options = ImportPlanOptions(targetGroupMode = TargetGroupMode.UNGROUPED),
                selected = candidates.map { it.selected }.toBooleanArray()
            )
        }
    }

    private fun showFolderImportMode(
        candidates: List<ImportCandidate>,
        selected: BooleanArray = candidates.map { it.selected }.toBooleanArray(),
        baseOptions: ImportPlanOptions = ImportPlanOptions()
    ) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, "未发现可导入的 TXT、EPUB 或 CHM 文件", Toast.LENGTH_SHORT).show()
            return
        }

        val levels = FolderGrouping.levelPreviews(candidates)
        val limitText = if (candidates.size >= FOLDER_IMPORT_LIMIT) {
            "，已达到单次上限 $FOLDER_IMPORT_LIMIT 本"
        } else {
            ""
        }
        val levelItems = levels.map { level ->
            val addedText = if (level.depth == 1) {
                "${level.groupCount} 个分组"
            } else {
                "${level.groupCount} 个分组，比上一层新增 ${level.additionalGroupCount} 个"
            }
            "按 ${level.depth} 层文件夹分组（$addedText）"
        }
        val items = (levelItems + "不分组导入").toTypedArray()
        var selectedIndex = 0

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                "选择分组层级 · ${candidates.size} 本$limitText · 最深 ${levels.last().depth} 层"
            )
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("下一步", null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                if (selectedIndex == levels.size) {
                    showImportPreview(
                        candidates,
                        baseOptions.copy(
                            targetGroupMode = TargetGroupMode.UNGROUPED,
                            targetGroupId = null,
                            targetGroupName = null
                        ),
                        selected
                    )
                } else {
                    showFolderGroupingPreview(
                        candidates = candidates,
                        depth = levels[selectedIndex].depth,
                        selected = selected,
                        baseOptions = baseOptions
                    )
                }
            }
        }
        dialog.show()
    }

    private fun showFolderGroupingPreview(
        candidates: List<ImportCandidate>,
        depth: Int,
        selected: BooleanArray,
        baseOptions: ImportPlanOptions
    ) {
        val groupedCandidates = FolderGrouping.applyDepth(candidates, depth)
        val groups = FolderGrouping.groupPreviews(candidates, depth)
        val previewText = buildString {
            groups.take(GROUP_PREVIEW_LIMIT).forEachIndexed { index, group ->
                append(index + 1)
                append(". ")
                append(group.name)
                append("（")
                append(group.bookCount)
                append(" 本）\n")
            }
            if (groups.size > GROUP_PREVIEW_LIMIT) {
                append("……另有 ")
                append(groups.size - GROUP_PREVIEW_LIMIT)
                append(" 个分组未在此处展开")
            }
        }
        val previewView = TextView(this).apply {
            text = previewText
            textSize = 15f
            setTextColor(Color.rgb(40, 40, 40))
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle("${depth} 层分组预览（${groups.size} 个分组）")
            .setView(FastScrollView(this).apply { addView(previewView) })
            .setNegativeButton("返回选择层级") { _, _ ->
                showFolderImportMode(candidates, selected, baseOptions)
            }
            .setNeutralButton("不分组") { _, _ ->
                showImportPreview(
                    candidates,
                    baseOptions.copy(
                        targetGroupMode = TargetGroupMode.UNGROUPED,
                        targetGroupId = null,
                        targetGroupName = null
                    ),
                    selected
                )
            }
            .setPositiveButton("使用此层级") { _, _ ->
                showImportPreview(
                    groupedCandidates,
                    baseOptions.copy(
                        targetGroupMode = TargetGroupMode.SUGGESTED,
                        targetGroupId = null,
                        targetGroupName = null
                    ),
                    selected
                )
            }
            .show()
    }

    private fun showImportPreview(
        candidates: List<ImportCandidate>,
        options: ImportPlanOptions,
        selected: BooleanArray
    ) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, "未发现可导入的 TXT、EPUB 或 CHM 文件", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { candidate ->
            val duplicate = when (candidate.duplicateStatus) {
                DuplicateStatus.NEW -> "新文件"
                DuplicateStatus.SAME_URI -> "重复 URI"
                DuplicateStatus.POSSIBLE_DUPLICATE -> "可能重复"
                DuplicateStatus.INVALID -> "无效"
            }
            val group = when (options.targetGroupMode) {
                TargetGroupMode.UNGROUPED -> "未分组"
                TargetGroupMode.EXISTING,
                TargetGroupMode.NEW -> options.targetGroupName ?: "未分组"
                TargetGroupMode.SUGGESTED -> candidate.suggestedGroupName ?: "未分组"
            }
            "${candidate.displayName} · ${candidate.format} · $group · $duplicate"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("导入预览（${candidates.size} 本）")
            .setMultiChoiceItems(labels, selected) { _, which, isChecked ->
                selected[which] = isChecked
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全选 / 设置", null)
            .setPositiveButton("开始导入") { _, _ ->
                val plan = candidates.mapIndexed { index, candidate ->
                    candidate.copy(selected = selected[index])
                }
                lifecycleScope.launch {
                    val summary = withContext(Dispatchers.IO) {
                        importCandidates(plan, options)
                    }
                    showImportSummary(summary)
                }
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        showImportQuickActions(candidates, options, selected)
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun showImportQuickActions(
        candidates: List<ImportCandidate>,
        options: ImportPlanOptions,
        selected: BooleanArray
    ) {
        val actions = arrayOf("全选", "取消全选", "选择文件夹层级", "不分组导入", "指定分组", "重复文件处理")
        AlertDialog.Builder(this)
            .setTitle("导入设置")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showImportPreview(candidates, options, BooleanArray(candidates.size) { true })
                    1 -> showImportPreview(candidates, options, BooleanArray(candidates.size) { false })
                    2 -> showFolderImportMode(candidates, selected, options)
                    3 -> showImportPreview(
                        candidates,
                        options.copy(targetGroupMode = TargetGroupMode.UNGROUPED, targetGroupId = null, targetGroupName = null),
                        selected
                    )
                    4 -> showTargetGroupOptions(candidates, options, selected)
                    5 -> showDuplicateStrategyOptions(candidates, options, selected)
                }
            }
            .show()
    }

    private fun showTargetGroupOptions(
        candidates: List<ImportCandidate>,
        options: ImportPlanOptions,
        selected: BooleanArray
    ) {
        lifecycleScope.launch {
            val existingGroups = withContext(Dispatchers.IO) {
                bookGroupRepository.getAllGroups().first()
            }
            val fixedOptions = listOf("新建分组")
            val groupLabels = existingGroups.map { "已有分组：${it.displayName.ifBlank { it.name }}" }
            val items = (fixedOptions + groupLabels).toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("指定分组")
                .setItems(items) { _, which ->
                    if (which == 0) {
                        promptNewGroupName(candidates, options, selected)
                    } else {
                        val group = existingGroups[which - fixedOptions.size]
                        showImportPreview(
                            candidates,
                            options.copy(
                                targetGroupMode = TargetGroupMode.EXISTING,
                                targetGroupId = group.id,
                                targetGroupName = group.displayName.ifBlank { group.name }
                            ),
                            selected
                        )
                    }
                }
                .show()
        }
    }

    private fun promptNewGroupName(
        candidates: List<ImportCandidate>,
        options: ImportPlanOptions,
        selected: BooleanArray
    ) {
        val input = EditText(this).apply {
            hint = "分组名称"
        }
        AlertDialog.Builder(this)
            .setTitle("新建分组")
            .setView(input)
            .setNegativeButton("取消") { _, _ ->
                showImportPreview(candidates, options, selected)
            }
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "新分组" }
                showImportPreview(
                    candidates,
                    options.copy(
                        targetGroupMode = TargetGroupMode.NEW,
                        targetGroupId = null,
                        targetGroupName = name
                    ),
                    selected
                )
            }
            .show()
    }

    private fun showDuplicateStrategyOptions(
        candidates: List<ImportCandidate>,
        options: ImportPlanOptions,
        selected: BooleanArray
    ) {
        val items = arrayOf("跳过重复", "更新已有记录", "保留两份（仅不同 URI）")
        AlertDialog.Builder(this)
            .setTitle("重复文件处理")
            .setItems(items) { _, which ->
                val strategy = when (which) {
                    1 -> DuplicateStrategy.UPDATE_EXISTING
                    2 -> DuplicateStrategy.KEEP_BOTH
                    else -> DuplicateStrategy.SKIP
                }
                showImportPreview(candidates, options.copy(duplicateStrategy = strategy), selected)
            }
            .show()
    }

    private suspend fun buildCandidate(
        file: DocumentFile,
        sourceTreeUri: String?,
        relativeParentPath: String?,
        permissionStatus: PermissionStatus
    ): ImportCandidate {
        val uri = file.uri.toString()
        val format = file.bookFormat()
        val displayName = file.name ?: "未命名书籍"
        val size = file.length().takeIf { it >= 0L }
        val existingByUri = if (format == null) null else bookRepository.getByFilePath(uri)
        val existingByIdentity = if (format == null || existingByUri != null) {
            null
        } else {
            bookRepository.findDuplicate(
                filePath = uri,
                sourceTreeUri = sourceTreeUri,
                relativePath = relativeParentPath,
                fileName = displayName,
                fileSize = size
            )
        }
        val duplicateStatus = when {
            format == null -> DuplicateStatus.INVALID
            existingByUri != null -> DuplicateStatus.SAME_URI
            existingByIdentity != null -> DuplicateStatus.POSSIBLE_DUPLICATE
            else -> DuplicateStatus.NEW
        }
        val groupName = relativeParentPath?.substringAfterLast("/")?.ifBlank { null }
        val groupKey = sourceTreeUri?.let { "$it|${relativeParentPath.orEmpty()}" }
        return ImportCandidate(
            uri = uri,
            displayName = displayName,
            format = format ?: "",
            sourceTreeUri = sourceTreeUri,
            relativeParentPath = relativeParentPath,
            suggestedGroupKey = groupKey,
            suggestedGroupName = groupName,
            size = size,
            lastModified = file.lastModified().takeIf { it > 0L },
            duplicateStatus = duplicateStatus,
            permissionStatus = permissionStatus,
            selected = duplicateStatus == DuplicateStatus.NEW
        )
    }

    private suspend fun importCandidates(
        candidates: List<ImportCandidate>,
        options: ImportPlanOptions
    ): ImportSummary {
        var imported = 0
        var updated = 0
        var skippedDuplicate = 0
        var notSelected = 0
        var invalid = 0
        var failed = 0

        for (candidate in candidates) {
            if (!candidate.selected) {
                notSelected++
                continue
            }
            if (candidate.duplicateStatus == DuplicateStatus.INVALID) {
                invalid++
                continue
            }
            if (candidate.duplicateStatus != DuplicateStatus.NEW && options.duplicateStrategy == DuplicateStrategy.SKIP) {
                skippedDuplicate++
                continue
            }

            val configuredCandidate = applyImportOptions(candidate, options)
            val result = runCatching {
                importCandidate(configuredCandidate, options)
            }.getOrElse { throwable ->
                ImportItemResult.Failed(throwable.message ?: "未知错误")
            }

            when (result) {
                is ImportItemResult.Inserted -> imported++
                is ImportItemResult.Updated -> updated++
                is ImportItemResult.Skipped -> skippedDuplicate++
                is ImportItemResult.Failed -> failed++
            }
        }

        return ImportSummary(
            found = candidates.size,
            imported = imported,
            updated = updated,
            skippedDuplicate = skippedDuplicate,
            notSelected = notSelected,
            invalid = invalid,
            failed = failed
        )
    }

    private fun applyImportOptions(
        candidate: ImportCandidate,
        options: ImportPlanOptions
    ): ImportCandidate {
        return when (options.targetGroupMode) {
            TargetGroupMode.SUGGESTED -> candidate
            TargetGroupMode.UNGROUPED -> candidate.copy(
                suggestedGroupKey = null,
                suggestedGroupName = null,
                suggestedGroupRelativePath = null
            )
            TargetGroupMode.EXISTING -> candidate.copy(
                suggestedGroupKey = options.targetGroupId?.let { "existing:$it" },
                suggestedGroupName = options.targetGroupName,
                suggestedGroupRelativePath = null
            )
            TargetGroupMode.NEW -> candidate.copy(
                suggestedGroupKey = options.targetGroupName?.let { "manual:$it" },
                suggestedGroupName = options.targetGroupName,
                suggestedGroupRelativePath = null
            )
        }
    }

    private suspend fun importCandidate(
        candidate: ImportCandidate,
        options: ImportPlanOptions
    ): ImportItemResult {
        val existing = bookRepository.findDuplicate(
            filePath = candidate.uri,
            sourceTreeUri = candidate.sourceTreeUri,
            relativePath = candidate.relativeParentPath,
            fileName = candidate.displayName,
            fileSize = candidate.size
        )
        val resolvedGroupId = resolveGroupId(candidate)

        if (existing != null) {
            when (options.duplicateStrategy) {
                DuplicateStrategy.SKIP -> {
                    val reason = if (existing.filePath == candidate.uri) {
                        "相同文件 URI 已存在"
                    } else {
                        "已存在相同位置或同名同大小书籍"
                    }
                    return ImportItemResult.Skipped(reason)
                }
                DuplicateStrategy.UPDATE_EXISTING -> {
                    val groupIdForUpdate = if (
                        options.targetGroupMode == TargetGroupMode.SUGGESTED &&
                        candidate.suggestedGroupKey == null
                    ) {
                        existing.groupId
                    } else {
                        resolvedGroupId
                    }
                    bookRepository.update(
                        existing.copy(
                            title = candidate.displayName
                                .removeSuffixIgnoreCase(".txt")
                                .removeSuffixIgnoreCase(".epub")
                                .removeSuffixIgnoreCase(".chm"),
                            filePath = candidate.uri,
                            format = candidate.format,
                            groupId = groupIdForUpdate,
                            fileName = candidate.displayName,
                            fileSize = candidate.size,
                            lastModified = candidate.lastModified,
                            sourceTreeUri = candidate.sourceTreeUri ?: existing.sourceTreeUri,
                            relativePath = candidate.relativeParentPath ?: existing.relativePath,
                            fileStatus = fileStatusFor(candidate.permissionStatus)
                        )
                    )
                    return ImportItemResult.Updated(existing.id)
                }
                DuplicateStrategy.KEEP_BOTH -> {
                    if (existing.filePath == candidate.uri) {
                        return ImportItemResult.Skipped("相同文件 URI 不能保留两份")
                    }
                }
            }
        }

        val book = Book(
            title = candidate.displayName
                .removeSuffixIgnoreCase(".txt")
                .removeSuffixIgnoreCase(".epub")
                .removeSuffixIgnoreCase(".chm"),
            filePath = candidate.uri,
            format = candidate.format,
            groupId = resolvedGroupId,
            fileName = candidate.displayName,
            fileSize = candidate.size,
            lastModified = candidate.lastModified,
            sourceTreeUri = candidate.sourceTreeUri,
            relativePath = candidate.relativeParentPath,
            fileStatus = fileStatusFor(candidate.permissionStatus)
        )
        val insertedId = bookRepository.insert(book)
        return if (insertedId != -1L) {
            ImportItemResult.Inserted(insertedId)
        } else {
            ImportItemResult.Skipped("导入写入前再次查重，书籍已存在")
        }
    }

    private suspend fun resolveGroupId(candidate: ImportCandidate): Long? {
        val sourceKey = candidate.suggestedGroupKey ?: return null
        val groupId = if (sourceKey.startsWith("existing:")) {
            sourceKey.removePrefix("existing:").toLongOrNull()
        } else {
            val existingGroup = bookGroupRepository.getGroupBySourceKey(sourceKey)
            existingGroup?.id ?: bookGroupRepository.insertOrGetExistingId(
                BookGroup(
                    name = candidate.suggestedGroupName ?: "未命名分组",
                    displayName = candidate.suggestedGroupName ?: "未命名分组",
                    sourceTreeUri = candidate.sourceTreeUri,
                    relativePath = candidate.suggestedGroupRelativePath,
                    sourceKey = sourceKey
                )
            )
        }
        return groupId?.takeIf { it > 0L }
    }

    private fun fileStatusFor(permissionStatus: PermissionStatus): String {
        return if (permissionStatus == PermissionStatus.PERSISTED) {
            "AVAILABLE"
        } else {
            "SESSION_ONLY"
        }
    }

    private fun showImportSummary(summary: ImportSummary) {
        Toast.makeText(
            this,
            "发现 ${summary.found} 本，导入 ${summary.imported} 本，更新 ${summary.updated} 本，" +
                "未选择 ${summary.notSelected} 本，重复跳过 ${summary.skippedDuplicate} 本，" +
                "无效 ${summary.invalid} 本，失败 ${summary.failed} 本",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun DocumentFile.isSupportedBook(): Boolean {
        return bookFormat() != null
    }

    private fun DocumentFile.bookFormat(): String? {
        val name = name ?: return null
        return when {
            name.endsWith(".txt", ignoreCase = true) -> "TXT"
            name.endsWith(".epub", ignoreCase = true) -> "EPUB"
            name.endsWith(".chm", ignoreCase = true) -> "CHM"
            else -> null
        }
    }

    private fun activityTime(book: ShelfBookItem): Long {
        return maxOf(book.lastReadTime, book.addTime)
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String {
        return if (endsWith(suffix, ignoreCase = true)) {
            dropLast(suffix.length)
        } else {
            this
        }
    }

    private fun joinPath(parent: String, child: String): String {
        return if (parent.isBlank()) child else "$parent/$child"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val BACKUP_SCHEMA_VERSION = 1
        private const val BACKUP_PREFS = "simple_reader_backup"
        private const val BACKUP_URI_KEY = "backup_uri"
        private const val FOLDER_IMPORT_LIMIT = 10_000
        private const val GROUP_PREVIEW_LIMIT = 200
    }
}
