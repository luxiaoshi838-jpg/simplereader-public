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
    private var books = emptyList<ShelfBookItem>()
    private var groups = emptyList<BookGroup>()
    private var showingHistory = false
    private var shelfSearchQuery = ""

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

        findViewById<TextView>(R.id.shelfTabTextView).apply {
            text = "书架"
            setOnClickListener {
                showingHistory = false
                updateUI()
            }
        }
        findViewById<TextView>(R.id.historyTabTextView).apply {
            text = "阅读历史"
            setOnClickListener {
                showingHistory = true
                updateUI()
            }
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
            setOnClickListener { showImportOptions() }
        }

        loadBooks()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean = false

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
                "${title.take(9)}\n\n${format.uppercase()}"
            } else {
                "${title.take(22)}\n\n\n${format.uppercase()}"
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
        val width = resources.displayMetrics.widthPixels
        val cardWidth = ((width - dp(64)) / 3).coerceAtLeast(dp(92))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                this.width = cardWidth
                this.height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(0, 0, dp(12), dp(20))
            }
        }
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
            .setItems(arrayOf("打开分组", "重命名分组", "删除分组", "导入本地书籍")) { _, which ->
                when (which) {
                    0 -> showGroupBooksV2(group, groupBooks)
                    1 -> showRenameGroupDialog(group)
                    2 -> confirmDeleteGroup(group, groupBooks)
                    3 -> showImportOptions()
                }
            }
            .show()
    }

    private fun showRenameGroupDialog(group: BookGroup) {
        val input = EditText(this).apply {
            hint = "分组名称"
            setText(group.displayName.ifBlank { group.name })
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("重命名分组")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank()) {
                    Toast.makeText(this, "分组名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        bookGroupRepository.update(group.copy(name = newName, displayName = newName))
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteGroup(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        AlertDialog.Builder(this)
            .setTitle("删除分组")
            .setMessage("分组内 ${groupBooks.size} 本书将移到未分组。不会删除书籍文件、阅读进度或书签。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除分组") { _, _ ->
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
                        Toast.makeText(this@MainActivity, "分组已删除，书籍已移到未分组", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "删除分组失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun showBookActions(book: ShelfBookItem) {
        showBookActionsV2(book)
    }

    private fun showGroupBooksV2(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        groupBooks.sortedByDescending(::activityTime).forEach { book ->
            val card = createShelfCard()
            val cover = TextView(this).apply {
                text = "${book.title.take(14)}\n\n${book.format}"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(58, 103, 146))
            }
            card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)))
            card.addView(TextView(this).apply {
                text = book.title
                textSize = 17f
                setTextColor(Color.rgb(30, 30, 30))
                maxLines = 2
            })
            card.addView(TextView(this).apply {
                text = "已读 ${book.progressPercent()}%"
                textSize = 13f
                setTextColor(Color.rgb(130, 126, 118))
            })
            card.setOnClickListener { openBook(book.id) }
            card.setOnLongClickListener {
                showBookActionsV2(book)
                true
            }
            grid.addView(card)
        }
        AlertDialog.Builder(this)
            .setTitle(group.displayName.ifBlank { group.name })
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showBookActionsV2(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf("打开", "导入分组", "删除书架", "导入本地书籍")) { _, which ->
                when (which) {
                    0 -> openBook(book.id)
                    1 -> showMoveBookToGroup(book)
                    2 -> confirmDeleteBook(book)
                    3 -> showImportOptions()
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

    private fun showImportOptions() {
        AlertDialog.Builder(this)
            .setTitle("导入本地书籍")
            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件")) { _, which ->
                when (which) {
                    0 -> openFolderLauncher.launch(null)
                    1 -> openFilesLauncher.launch(
                        arrayOf("text/plain", "application/epub+zip", "application/octet-stream")
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
            Toast.makeText(this, "未发现可导入的 TXT 或 EPUB 文件", Toast.LENGTH_SHORT).show()
            return
        }
        val levels = FolderGrouping.levelPreviews(candidates)
        val limitText = if (candidates.size >= FOLDER_IMPORT_LIMIT) "，已达到单次上限 $FOLDER_IMPORT_LIMIT 本" else ""
        val levelItems = levels.map { level ->
            val addedText = if (level.depth == 1) {
                "初始 ${level.groupCount} 个分组"
            } else {
                "共 ${level.groupCount} 个，比上一层新增 ${level.additionalGroupCount} 个"
            }
            "按 ${level.depth} 层文件夹分组（$addedText）"
        }
        val items = (levelItems + "不分组导入").toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择文件夹分组层级")
            .setMessage(
                "发现 ${candidates.size} 本书$limitText，识别到最深 ${levels.last().depth} 层文件夹。" +
                    "可选层数以实际识别深度为准，最多支持 ${FolderGrouping.MAX_SUPPORTED_DEPTH} 层。"
            )
            .setItems(items) { _, which ->
                if (which == levels.size) {
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
                        depth = levels[which].depth,
                        selected = selected,
                        baseOptions = baseOptions
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            .setView(ScrollView(this).apply { addView(previewView) })
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
            Toast.makeText(this, "未发现可导入的 TXT 或 EPUB 文件", Toast.LENGTH_SHORT).show()
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
        val duplicateStatus = when {
            format == null -> DuplicateStatus.INVALID
            bookRepository.getByFilePath(uri) != null -> DuplicateStatus.SAME_URI
            else -> DuplicateStatus.NEW
        }
        val groupName = relativeParentPath?.substringAfterLast("/")?.ifBlank { null }
        val groupKey = sourceTreeUri?.let { "$it|${relativeParentPath.orEmpty()}" }
        return ImportCandidate(
            uri = uri,
            displayName = file.name ?: "未命名书籍",
            format = format ?: "",
            sourceTreeUri = sourceTreeUri,
            relativeParentPath = relativeParentPath,
            suggestedGroupKey = groupKey,
            suggestedGroupName = groupName,
            size = file.length().takeIf { it >= 0L },
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
        val existing = bookRepository.getByFilePath(candidate.uri)
        val resolvedGroupId = resolveGroupId(candidate)

        if (existing != null) {
            return when (options.duplicateStrategy) {
                DuplicateStrategy.SKIP -> ImportItemResult.Skipped("相同 URI 已存在")
                DuplicateStrategy.KEEP_BOTH -> ImportItemResult.Skipped("相同 URI 不能保留两份")
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
                                .removeSuffixIgnoreCase(".epub"),
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
                    ImportItemResult.Updated(existing.id)
                }
            }
        }

        val book = Book(
            title = candidate.displayName
                .removeSuffixIgnoreCase(".txt")
                .removeSuffixIgnoreCase(".epub"),
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
            ImportItemResult.Skipped("导入过程中发现相同 URI 已存在")
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
        private const val FOLDER_IMPORT_LIMIT = 10_000
        private const val GROUP_PREVIEW_LIMIT = 200
    }
}
