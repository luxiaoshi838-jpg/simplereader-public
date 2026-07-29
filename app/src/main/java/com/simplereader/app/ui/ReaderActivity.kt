package com.simplereader.app.ui

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.simplereader.app.R
import com.simplereader.app.data.cache.CachedBook
import com.simplereader.app.data.cache.StructuredBookCache
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Bookmark
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.ReadProgress
import com.simplereader.app.parser.ChmParser
import com.simplereader.app.parser.EpubChapter
import com.simplereader.app.parser.EpubParser
import com.simplereader.app.parser.TxtParser
import com.simplereader.app.parser.TxtWindowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ReaderActivity : AppCompatActivity(), GestureDetector.OnGestureListener {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var contentView: TextView
    private lateinit var readerScrollView: NestedScrollView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var readerProgressLabel: TextView
    private lateinit var readerControls: LinearLayout
    private lateinit var readerSettingsPanel: LinearLayout
    private lateinit var gestureDetector: GestureDetector
    private var bookId: Long = 0L
    private var book: Book? = null
    private var currentContent: String = ""
    private var currentPosition: Int = 0
    private var txtStreamingMode: Boolean = false
    private var txtCharsetName: String? = null
    private var txtTotalBytes: Long = 0L
    private var txtCurrentPageStartByte: Long = 0L
    private var txtCurrentPageEndByte: Long = 0L
    private val txtContinuousBuffer = TxtContinuousBuffer()
    private var txtBufferLoadJob: Job? = null
    private var txtReachedStart: Boolean = false
    private var txtReachedEnd: Boolean = false
    private var suppressNextScrollProgress: Boolean = false
    private var epubChapters: List<EpubChapter> = emptyList()
    private var epubChapterStartPositions: List<Int> = emptyList()
    private var structuredCatalogEntries: List<StructuredCatalogEntry> = emptyList()
    private var structuredChapterIndex: Int = 0
    private var structuredWholeBookMode: Boolean = false
    private var structuredReadingBuffer: StructuredReadingBuffer? = null
    private var structuredWholeText: String? = null
    private var structuredBufferLoadJob: Job? = null
    private var chmCachedFile: File? = null
    private val pageSize: Int = 2000
    private var contentLoaded: Boolean = false
    private var progressLoaded: Boolean = false
    private var openSucceeded: Boolean = false
    private var progressDirty: Boolean = false
    private var lastSavedPosition: Int? = null
    private var saveProgressJob: Job? = null
    private var readerTextSize: Float = 18f
    private var currentBackgroundColor: Int = Color.rgb(245, 233, 200)
    private var currentTextColor: Int = Color.rgb(59, 52, 40)
    private var pageTurnMode: String = TURN_MODE_OVERLAP
    private var volumeKeyTurnEnabled: Boolean = true
    private var pendingSeekProgress: Int? = null
    private var currentReadableDocument: DocumentFile? = null
    private var chapterScanJob: Job? = null
    private var readerSearchSession: ReaderSearchSession? = null
    private var pendingReaderSearchHighlight: ReaderSearchHighlight? = null
    private var activeReaderSearchHighlight: Boolean = false
    private var readerChromeVisible: Boolean = false

    private val recoverSourceFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            recoverCurrentBookFromSourceFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.rgb(72, 67, 58)))
        supportActionBar?.hide()

        database = SimpleReaderDatabase.getDatabase(this)
        contentView = findViewById(R.id.contentView)
        readerScrollView = findViewById(R.id.readerScrollView)
        fontSizeSeekBar = findViewById(R.id.fontSizeSeekBar)
        readerProgressLabel = findViewById(R.id.readerProgressLabel)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        loadReaderPrefs()
        applyActiveReaderMode(ReaderAppearance.palette(this))
        gestureDetector = GestureDetector(this, this)
        readerScrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            pageTurnMode != TURN_MODE_VERTICAL
        }
        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            clearReaderSearchHighlightOnUserScroll()
            updateVerticalScrollProgress(scrollY)
            maybeExtendTxtContinuousBuffer(scrollY)
            maybeExtendStructuredContinuousBuffer(scrollY)
        }

        bookId = intent.getLongExtra("bookId", 0L)
        setupUI()
        val diagnosticText = intent.getStringExtra("readerDiagnosticText")
        if (diagnosticText != null) {
            val parsed = TxtParser.readText(
                java.io.ByteArrayInputStream(diagnosticText.toByteArray(Charsets.UTF_8)),
                Charsets.UTF_8.name()
            )
            currentContent = parsed.text
            currentPosition = 0
            txtStreamingMode = false
            progressLoaded = true
            contentLoaded = true
            openSucceeded = true
            displayContent()
        } else {
            loadBook()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索")
            .setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        val addItem = menu.add(Menu.NONE, MENU_ADD_BOOKMARK, Menu.NONE, "添加书签")
        addItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        addItem.actionView = TextView(this).apply {
            text = "添"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            contentDescription = "添加书签"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(239, 122, 40))
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { addBookmark() }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ADD_BOOKMARK -> {
                addBookmark()
                true
            }
            MENU_BOOKMARKS -> {
                showCatalogBookmarkPanelV2(showBookmarksFirst = true)
                true
            }
            MENU_TOC, MENU_PANEL -> {
                showCatalogBookmarkPanelV2()
                true
            }
            MENU_SEARCH -> {
                showContentSearch()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadBook() {
        lifecycleScope.launch {
            try {
                val selectedBook = withContext(Dispatchers.IO) {
                    database.bookDao().getBook(bookId)
                } ?: run {
                    showError("书籍记录不存在")
                    return@launch
                }

                book = selectedBook
                title = selectedBook.title
                supportActionBar?.title = selectedBook.title

                if (selectedBook.format.equals("CHM", ignoreCase = true)) {
                    showError("当前版本已停止支持 CHM：真实样本无法稳定提取目录和正文，请改用 TXT 或 EPUB")
                    return@launch
                }

                val cachedStructured = if (selectedBook.format.equals("EPUB", ignoreCase = true)) {
                    withContext(Dispatchers.IO) { StructuredBookCache.loadAny(this@ReaderActivity, bookId) }
                } else {
                    null
                }
                val documentFile = withContext(Dispatchers.IO) {
                    resolveReadableDocument(selectedBook)
                }
                if (documentFile == null) {
                    if (cachedStructured != null) {
                        val progress = withContext(Dispatchers.IO) {
                            database.readProgressDao().getProgress(bookId)
                        }
                        val cachedContent = withContext(Dispatchers.IO) {
                            loadedContentFromCache(cachedStructured, progress)
                        }
                        applyLoadedContent(cachedContent, selectedBook.format, null)
                        Toast.makeText(
                            this@ReaderActivity,
                            "正在使用已同步的可读缓存；重新授权原文件后可校验更新",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    markBookUnavailableSafely("PERMISSION_LOST")
                    showRecoverableAccessError("无法访问原书籍文件。请选择包含该书及其他小说文件的总文件夹以恢复访问权限。")
                    return@launch
                }

                loadBookContent(documentFile, selectedBook.format)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                markBookUnavailableSafely("PERMISSION_LOST")
                showRecoverableAccessError("书籍文件访问权限已失效。请选择包含该书及其他小说文件的总文件夹。")
            } catch (error: Exception) {
                markBookUnavailableSafely("OPEN_FAILED")
                showError("打开书籍失败：${error.message ?: "未知错误"}")
            } catch (linkage: LinkageError) {
                markBookUnavailableSafely("OPEN_FAILED")
                showError("阅读组件加载失败，请安装修复版本后重试。")
            }
        }
    }

    private fun resolveReadableDocument(selectedBook: Book): DocumentFile? {
        val directUri = runCatching { Uri.parse(selectedBook.filePath) }.getOrNull()
        val direct = directUri?.let { uri ->
            runCatching { DocumentFile.fromSingleUri(this, uri) }.getOrNull()
        }
        if (direct != null && runCatching { direct.exists() && direct.isFile }.getOrDefault(false)) {
            return direct
        }

        val treeUriText = selectedBook.sourceTreeUri?.takeIf { it.isNotBlank() } ?: return null
        val treeRoot = runCatching {
            DocumentFile.fromTreeUri(this, Uri.parse(treeUriText))
        }.getOrNull() ?: return null
        if (!runCatching { treeRoot.exists() && treeRoot.isDirectory }.getOrDefault(false)) {
            return null
        }

        var parent = treeRoot
        val rootName = runCatching { treeRoot.name }.getOrNull()
        val pathSegments = selectedBook.relativePath
            .orEmpty()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .let { segments ->
                if (rootName != null && segments.firstOrNull().equals(rootName, ignoreCase = true)) {
                    segments.drop(1)
                } else {
                    segments
                }
            }

        for (segment in pathSegments) {
            parent = runCatching { parent.findFile(segment) }.getOrNull() ?: return null
            if (!runCatching { parent.exists() && parent.isDirectory }.getOrDefault(false)) {
                return null
            }
        }

        val fileName = selectedBook.fileName.ifBlank {
            directUri?.lastPathSegment?.substringAfterLast('/') ?: selectedBook.title
        }
        val recovered = runCatching { parent.findFile(fileName) }.getOrNull() ?: return null
        return recovered.takeIf {
            runCatching { it.exists() && it.isFile }.getOrDefault(false)
        }
    }

    private fun showRecoverableAccessError(message: String) {
        contentView.text = "$message\n\n点击“选择总文件夹”后，程序会按备份里的相对路径和文件名恢复当前书。"
        AlertDialog.Builder(this)
            .setTitle("恢复书籍访问")
            .setMessage(contentView.text)
            .setNegativeButton("取消", null)
            .setPositiveButton("选择总文件夹") { _, _ ->
                recoverSourceFolderLauncher.launch(null)
            }
            .show()
    }

    private fun recoverCurrentBookFromSourceFolder(rootUri: Uri) {
        val selectedBook = book
        if (selectedBook == null) {
            Toast.makeText(this, "书籍记录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(rootUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val root = DocumentFile.fromTreeUri(this, rootUri)
        if (root == null || !root.isDirectory) {
            Toast.makeText(this, "无法读取所选总文件夹", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val indexedFiles = scanRecoverableFiles(root)
                val allBooks = database.bookDao().getAllBooks().first()
                var updatedCount = 0
                var currentBookAfterUpdate: Book? = null

                allBooks.forEach { candidateBook ->
                    val recovered = findRecoveredDocument(candidateBook, indexedFiles) ?: return@forEach
                    val recoveredUri = recovered.file.uri.toString()
                    val existing = database.bookDao().getByFilePath(recoveredUri)
                    if (existing != null && existing.id != candidateBook.id) return@forEach

                    val updatedBook = candidateBook.copy(
                        filePath = recoveredUri,
                        fileName = recovered.file.name ?: candidateBook.fileName,
                        fileSize = recovered.file.length().takeIf { it >= 0L },
                        lastModified = recovered.file.lastModified().takeIf { it > 0L },
                        sourceTreeUri = rootUri.toString(),
                        relativePath = recovered.relativeParentPath,
                        fileStatus = "AVAILABLE"
                    )
                    database.bookDao().update(updatedBook)
                    updatedCount++
                    if (candidateBook.id == selectedBook.id) {
                        currentBookAfterUpdate = updatedBook
                    }
                }
                updatedCount to currentBookAfterUpdate
            }
            val (updatedCount, recoveredBook) = result
            if (recoveredBook == null) {
                Toast.makeText(
                    this@ReaderActivity,
                    "未在所选总文件夹内找到《${selectedBook.title}》",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            book = recoveredBook
            Toast.makeText(this@ReaderActivity, "已恢复 $updatedCount 本，正在打开当前书", Toast.LENGTH_SHORT).show()
            loadBook()
        }
    }

    private data class RecoveredDocument(
        val file: DocumentFile,
        val relativeParentPath: String
    )

    private fun scanRecoverableFiles(root: DocumentFile): List<RecoveredDocument> {
        val result = mutableListOf<RecoveredDocument>()
        val queue = java.util.ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && result.size < RECOVER_SCAN_LIMIT) {
            val (folder, relativePath) = queue.removeFirst()
            folder.listFiles().forEach { child ->
                if (result.size >= RECOVER_SCAN_LIMIT) return@forEach
                val childName = child.name.orEmpty()
                if (childName.isBlank() || childName.startsWith('.')) return@forEach
                if (child.isDirectory) {
                    queue.add(child to joinPath(relativePath, childName))
                } else if (child.isFile && isSupportedBookName(childName)) {
                    result += RecoveredDocument(child, relativePath)
                }
            }
        }
        return result
    }

    private fun findRecoveredDocument(
        selectedBook: Book,
        files: List<RecoveredDocument>
    ): RecoveredDocument? {
        val targetFileName = selectedBook.fileName.ifBlank {
            Uri.parse(selectedBook.filePath).lastPathSegment?.substringAfterLast('/') ?: selectedBook.title
        }
        val pathSegments = selectedBook.relativePath
            .orEmpty()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
        files.firstOrNull { file ->
            file.file.name.equals(targetFileName, ignoreCase = true) &&
                pathMatches(pathSegments, file.relativeParentPath)
        }?.let { return it }

        val sameName = files.filter { file ->
            file.file.name.equals(targetFileName, ignoreCase = true)
        }
        if (sameName.size == 1) return sameName.single()

        val expectedSize = selectedBook.fileSize
        if (expectedSize != null) {
            val sameSize = sameName.filter { it.file.length() == expectedSize }
            if (sameSize.size == 1) return sameSize.single()
        }
        return null
    }

    private fun pathMatches(savedSegments: List<String>, currentRelativePath: String): Boolean {
        if (savedSegments.isEmpty()) return true
        val saved = savedSegments.joinToString("/").lowercase()
        val current = currentRelativePath.replace('\\', '/').trim('/').lowercase()
        return current == saved || current.endsWith("/$saved") || saved.endsWith("/$current")
    }

    private fun isSupportedBookName(name: String): Boolean {
        return name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".epub", ignoreCase = true)
    }

    private fun joinPath(parent: String, child: String): String =
        listOf(parent.trim('/'), child.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")

    private suspend fun markBookUnavailableSafely(status: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                database.bookDao().updateFileStatus(bookId, status)
            }
        }
    }

    private fun streamingWindowStartForTarget(targetByte: Long): Long {
        return (targetByte - TXT_STREAM_WINDOW_BYTES / 3L).coerceAtLeast(0L)
    }

    private fun isStructuredChapterDocument(): Boolean {
        val format = book?.format?.uppercase().orEmpty()
        return !txtStreamingMode && format == "EPUB" && epubChapters.isNotEmpty()
    }

    private fun loadStructuredChapter(
        chapterIndex: Int,
        offset: Int = 0,
        saveImmediately: Boolean = false,
        direction: Int = 0,
        openAtEnd: Boolean = false,
        offsetFraction: Float? = null
    ) {
        val targetIndex = chapterIndex.coerceIn(0, epubChapters.lastIndex)
        if (structuredWholeBookMode) {
            val chapterStart = epubChapterStartPositions.getOrElse(targetIndex) { 0 }
            val chapterEnd = epubChapterStartPositions.getOrNull(targetIndex + 1)
                ?.minus(EPUB_CHAPTER_SEPARATOR.length)
                ?.coerceAtLeast(chapterStart)
                ?: currentContent.length
            val chapterLength = (chapterEnd - chapterStart).coerceAtLeast(0)
            val localOffset = when {
                openAtEnd -> (chapterLength - if (pageTurnMode == TURN_MODE_VERTICAL) 1 else pageSize)
                    .coerceAtLeast(0)
                offsetFraction != null -> (chapterLength * offsetFraction.coerceIn(0f, 1f)).toInt()
                else -> offset.coerceAtLeast(0)
            }
            structuredChapterIndex = targetIndex
            currentPosition = (chapterStart + localOffset).coerceIn(chapterStart, chapterEnd)
            displayContent()
            if (direction != 0) animatePageTurn(direction)
            markProgressDirty()
            if (saveImmediately) saveProgressNow() else scheduleProgressSave()
            return
        }

        val wholeText = structuredWholeText
        if (wholeText == null) {
            Toast.makeText(this, "EPUB 缓存不可用，请重新打开书籍", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val buffer = withContext(Dispatchers.Default) {
                    buildEpubReadingBuffer(
                        wholeText = wholeText,
                        chapters = epubChapters,
                        starts = epubChapterStartPositions,
                        centerIndex = targetIndex
                    )
                }
                if (buffer.content.isBlank()) {
                    Toast.makeText(this@ReaderActivity, "该章节没有可显示内容", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                structuredReadingBuffer = buffer
                structuredChapterIndex = targetIndex
                currentContent = buffer.content
                val chapterLength = buffer.chapterLength(targetIndex)
                val targetOffset = when {
                    openAtEnd -> (chapterLength - if (pageTurnMode == TURN_MODE_VERTICAL) 1 else pageSize)
                        .coerceAtLeast(0)
                    offsetFraction != null -> (chapterLength * offsetFraction.coerceIn(0f, 1f)).toInt()
                    else -> offset.coerceIn(0, chapterLength)
                }
                currentPosition = buffer.positionFor(targetIndex, targetOffset) ?: 0
                displayContent()
                if (direction != 0) animatePageTurn(direction)
                markProgressDirty()
                if (saveImmediately) saveProgressNow() else scheduleProgressSave()
            } catch (error: Throwable) {
                showError("读取章节失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun buildEpubReadingBuffer(
        wholeText: String,
        chapters: List<EpubChapter>,
        starts: List<Int>,
        centerIndex: Int
    ): StructuredReadingBuffer {
        val safeCenter = centerIndex.coerceIn(0, chapters.lastIndex)
        val indices = (safeCenter - 1..safeCenter + 1).filter { it in chapters.indices }
        val texts = indices.map { index ->
            val start = starts.getOrElse(index) { 0 }.coerceIn(0, wholeText.length)
            val end = starts.getOrNull(index + 1)
                ?.coerceIn(start, wholeText.length)
                ?: wholeText.length
            index to wholeText.substring(start, end).trimEnd()
        }
        return StructuredReadingBuffer.build(safeCenter, texts)
    }

    private fun maybeExtendStructuredContinuousBuffer(scrollY: Int) {
        if (
            suppressNextScrollProgress ||
            pageTurnMode != TURN_MODE_VERTICAL ||
            !isStructuredChapterDocument() ||
            structuredWholeBookMode ||
            structuredBufferLoadJob?.isActive == true
        ) return
        val buffer = structuredReadingBuffer ?: return
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        if (maxScroll <= 0) return
        val fraction = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        val nextCenter = when {
            fraction >= STRUCTURED_PREFETCH_FORWARD_FRACTION && buffer.lastChapterIndex < epubChapters.lastIndex ->
                (buffer.centerChapterIndex + 1).coerceAtMost(epubChapters.lastIndex)
            fraction <= STRUCTURED_PREFETCH_BACKWARD_FRACTION && buffer.firstChapterIndex > 0 ->
                (buffer.centerChapterIndex - 1).coerceAtLeast(0)
            else -> return
        }
        val wholeText = structuredWholeText ?: return
        val oldLocation = currentStructuredLocation()
        structuredBufferLoadJob = lifecycleScope.launch {
            val newBuffer = withContext(Dispatchers.Default) {
                buildEpubReadingBuffer(
                    wholeText = wholeText,
                    chapters = epubChapters,
                    starts = epubChapterStartPositions,
                    centerIndex = nextCenter
                )
            }
            val preservedPosition = newBuffer.positionFor(oldLocation.chapterIndex, oldLocation.offset)
                ?: return@launch
            structuredReadingBuffer = newBuffer
            structuredChapterIndex = oldLocation.chapterIndex.coerceIn(0, epubChapters.lastIndex)
            currentContent = newBuffer.content
            currentPosition = preservedPosition
            suppressNextScrollProgress = true
            contentView.text = currentContent
            contentView.textSize = readerTextSize
            configureVerticalScrollIfNeeded()
            contentView.post {
                val newMaxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                val targetFraction = if (currentContent.isEmpty()) 0f else {
                    (currentPosition.toFloat() / currentContent.length).coerceIn(0f, 1f)
                }
                readerScrollView.scrollTo(0, (newMaxScroll * targetFraction).toInt().coerceIn(0, newMaxScroll))
                readerScrollView.post {
                    suppressNextScrollProgress = false
                    updateVerticalScrollProgress(readerScrollView.scrollY)
                }
            }
        }
    }

    private fun buildChmReadingBuffer(chmFile: File, centerIndex: Int): StructuredReadingBuffer {
        val indices = (centerIndex - 1..centerIndex + 1)
            .filter { it in epubChapters.indices }
        val texts = indices.map { index ->
            val chapter = epubChapters[index]
            index to ChmParser.readChapterText(chmFile, chapter.name)
        }
        return StructuredReadingBuffer.build(centerIndex, texts)
    }

    private fun structuredLocationFor(position: Int): StructuredReadingBuffer.Location {
        if (structuredWholeBookMode) {
            val index = epubChapterStartPositions.indexOfLast { it <= position }
                .coerceAtLeast(0)
                .coerceAtMost(epubChapters.lastIndex.coerceAtLeast(0))
            val start = epubChapterStartPositions.getOrElse(index) { 0 }
            return StructuredReadingBuffer.Location(index, (position - start).coerceAtLeast(0))
        }
        return structuredReadingBuffer?.locationFor(position)
            ?: StructuredReadingBuffer.Location(structuredChapterIndex, position.coerceAtLeast(0))
    }

    private fun currentStructuredLocation(): StructuredReadingBuffer.Location =
        structuredLocationFor(currentPosition)

    private fun updateStructuredLocationFromCurrentPosition() {
        if (!isStructuredChapterDocument()) return
        structuredChapterIndex = currentStructuredLocation().chapterIndex
    }

    private fun loadBookContent(documentFile: DocumentFile, format: String) {
        currentReadableDocument = documentFile
        lifecycleScope.launch {
            try {
                val loadedContent = withContext(Dispatchers.IO) {
                    when (format.uppercase()) {
                        "TXT" -> {
                            val fileSize = documentFile.length().takeIf { it > 0L }
                                ?: book?.fileSize
                                ?: 0L
                            val charsetName = contentResolver.openInputStream(documentFile.uri)?.let { stream ->
                                TxtParser.detectCharset(stream, book?.txtCharset)
                            } ?: book?.txtCharset ?: Charsets.UTF_8.name()
                            database.bookDao().updateTxtCharset(bookId, charsetName)
                            val savedProgress = database.readProgressDao().getProgress(bookId)
                            val savedOffset = savedProgress?.position?.toLongOrNull()
                                ?: savedProgress?.txtCharOffset?.toLong()
                                ?: 0L
                            val targetOffset = savedOffset.coerceIn(0L, fileSize.coerceAtLeast(0L))
                            val window = contentResolver.openInputStream(documentFile.uri)?.let { stream ->
                                TxtParser.readWindow(
                                    inputStream = stream,
                                    charsetName = charsetName,
                                    startByte = streamingWindowStartForTarget(targetOffset),
                                    maxBytes = TXT_STREAM_WINDOW_BYTES
                                )
                            } ?: error("Cannot open TXT stream")
                            val cachedChapters = readTxtChapterCache(
                                fileSize,
                                documentFile.lastModified(),
                                charsetName
                            )
                            LoadedContent(
                                text = window.text,
                                epubChapters = cachedChapters.map { chapter ->
                                    EpubChapter(name = chapter.title, text = "")
                                },
                                epubChapterStartPositions = cachedChapters.map {
                                    it.start.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                },
                                isStreamingTxt = true,
                                txtCharsetName = charsetName,
                                txtTotalBytes = fileSize,
                                txtStartByte = window.startByte,
                                txtNextByte = window.nextByte,
                                txtTargetByte = targetOffset
                            )
                        }
                        "EPUB", "CHM" -> {
                            val sourceSize = documentFile.length().takeIf { it > 0L }
                                ?: book?.fileSize
                                ?: 0L
                            val sourceModified = documentFile.lastModified()
                            val cached = StructuredBookCache.openOrBuild(
                                context = this@ReaderActivity,
                                bookId = bookId,
                                format = format,
                                sourceSize = sourceSize,
                                sourceModified = sourceModified,
                                sourceProvider = {
                                    contentResolver.openInputStream(documentFile.uri)
                                        ?: error("无法重新打开原书文件")
                                }
                            )
                            val progress = database.readProgressDao().getProgress(bookId)
                            loadedContentFromCache(cached, progress)
                        }
                        else -> LoadedContent("")
                    }
                }
                applyLoadedContent(loadedContent, format, documentFile)
            } catch (e: Throwable) {
                showError("打开书籍失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun loadedContentFromCache(cached: CachedBook, progress: ReadProgress?): LoadedContent {
        val wholeText = cached.textFile.readText(Charsets.UTF_8)
        val chapters = cached.chapters.map { chapter ->
            EpubChapter(name = chapter.source, text = chapter.title)
        }
        require(chapters.isNotEmpty()) { "缓存中没有可读取的章节" }
        val starts = cached.chapters.map { it.startChar.coerceIn(0, wholeText.length) }
        val hrefIndex = progress?.epubChapterHref
            ?.takeIf { it.isNotBlank() }
            ?.let { href -> cached.chapters.indexOfFirst { it.source.equals(href, ignoreCase = true) } }
            ?.takeIf { it >= 0 }
        val targetIndex = (hrefIndex ?: progress?.epubSpineIndex ?: 0)
            .coerceIn(0, chapters.lastIndex)
        val chapterStart = starts.getOrElse(targetIndex) { 0 }
        val chapterEnd = cached.chapters.getOrNull(targetIndex)
            ?.endChar
            ?.coerceIn(chapterStart, wholeText.length)
            ?: starts.getOrNull(targetIndex + 1)?.coerceAtLeast(chapterStart)
            ?: wholeText.length
        val targetOffset = (progress?.epubChapterOffset ?: 0)
            .coerceIn(0, (chapterEnd - chapterStart).coerceAtLeast(0))
        structuredWholeText = wholeText
        val initialPosition = (chapterStart + targetOffset).coerceIn(0, wholeText.length)
        return LoadedContent(
            text = wholeText,
            epubChapters = chapters,
            epubChapterStartPositions = starts,
            structuredCatalogEntries = cached.catalog.map { entry ->
                StructuredCatalogEntry(
                    title = entry.title,
                    depth = entry.depth,
                    targetChapterIndex = entry.targetChapterIndex,
                    isSection = entry.isSection
                )
            },
            structuredChapterIndex = targetIndex,
            structuredInitialPosition = initialPosition,
            structuredWholeBookMode = true,
            structuredReadingBuffer = null
        )
    }

    private suspend fun applyLoadedContent(
        loadedContent: LoadedContent,
        format: String,
        documentFile: DocumentFile?
    ) {
        currentContent = loadedContent.text
        txtStreamingMode = loadedContent.isStreamingTxt
        txtCharsetName = loadedContent.txtCharsetName
        txtTotalBytes = loadedContent.txtTotalBytes
        txtCurrentPageStartByte = loadedContent.txtStartByte
        txtCurrentPageEndByte = loadedContent.txtNextByte
        if (txtStreamingMode) {
            txtContinuousBuffer.reset(
                TxtWindowResult(
                    text = loadedContent.text,
                    startByte = loadedContent.txtStartByte,
                    nextByte = loadedContent.txtNextByte
                )
            )
            txtReachedStart = loadedContent.txtStartByte <= 0L
            txtReachedEnd = loadedContent.txtNextByte >= loadedContent.txtTotalBytes
        } else {
            txtContinuousBuffer.reset(TxtWindowResult("", 0L, 0L))
            txtReachedStart = false
            txtReachedEnd = false
        }
        epubChapters = loadedContent.epubChapters
        epubChapterStartPositions = loadedContent.epubChapterStartPositions
        structuredCatalogEntries = loadedContent.structuredCatalogEntries
        structuredChapterIndex = loadedContent.structuredChapterIndex
        structuredWholeBookMode = loadedContent.structuredWholeBookMode
        structuredReadingBuffer = loadedContent.structuredReadingBuffer
        if (!format.equals("EPUB", ignoreCase = true)) structuredWholeText = null
        chmCachedFile = loadedContent.chmCachePath?.let(::File)
        if (format.equals("EPUB", ignoreCase = true) && pageTurnMode != TURN_MODE_VERTICAL) {
            pageTurnMode = TURN_MODE_VERTICAL
            saveReaderPrefs()
            updateSettingsLabels()
        }

        val progress = withContext(Dispatchers.IO) {
            database.readProgressDao().getProgress(bookId)
        }
        progressLoaded = true
        currentPosition = if (txtStreamingMode) {
            loadedContent.txtTargetByte
                .coerceIn(loadedContent.txtStartByte, loadedContent.txtNextByte)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            val restoredPosition = when (format.uppercase()) {
                "TXT" -> progress?.txtCharOffset ?: progress?.position?.toIntOrNull()
                "EPUB", "CHM" -> loadedContent.structuredInitialPosition
                else -> progress?.position?.toIntOrNull()
            } ?: 0
            restoredPosition.coerceIn(0, currentContent.length)
        }

        if (currentContent.isBlank()) {
            showError("没有读取到可显示内容")
            return
        }
        contentLoaded = true
        openSucceeded = true
        progressDirty = false
        lastSavedPosition = currentPosition
        if (documentFile != null) {
            withContext(Dispatchers.IO) {
                database.bookDao().updateFileStatus(
                    bookId,
                    stableStatusFor(book, documentFile.uri)
                )
            }
        }
        displayContent()
        if (txtStreamingMode && pageTurnMode == TURN_MODE_VERTICAL) {
            readerScrollView.post { maybeExtendTxtContinuousBuffer(readerScrollView.scrollY) }
        }
    }

    private fun readChmTextIsolated(input: java.io.InputStream): String {
        return try {
            val parserClass = Class.forName("com.simplereader.app.parser.ChmParser")
            val parserInstance = parserClass.getField("INSTANCE").get(null)
            val method = parserClass.getMethod("readText", java.io.InputStream::class.java)
            method.invoke(parserInstance, input) as? String
                ?: error("CHM 解析器未返回文本")
        } catch (error: Throwable) {
            val cause = if (error is java.lang.reflect.InvocationTargetException) {
                error.targetException ?: error
            } else {
                error
            }
            throw IllegalStateException(
                "CHM 解析组件不可用：${cause.message ?: cause.javaClass.simpleName}",
                cause
            )
        }
    }

    private fun setupUI() {
        fontSizeSeekBar.max = 1000
        findViewById<TextView>(R.id.catalogButton).setOnClickListener {
            showCatalogBookmarkPanelV2()
        }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener {
            toggleReaderSettingsPanel()
        }
        findViewById<TextView>(R.id.nightButton).setOnClickListener {
            applyActiveReaderMode(ReaderAppearance.toggleMode(this))
        }
        findViewById<TextView>(R.id.previousChapterButton).setOnClickListener {
            jumpChapter(-1)
        }
        findViewById<TextView>(R.id.nextChapterButton).setOnClickListener {
            jumpChapter(1)
        }
        setupReaderSettingsPanel()
        val progressListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentContent.isNotEmpty()) {
                    pendingSeekProgress = progress
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = pendingSeekProgress ?: seekBar?.progress ?: return
                pendingSeekProgress = null
                seekToProgress(progress)
            }
        }
        fontSizeSeekBar.setOnSeekBarChangeListener(progressListener)
    }

    private fun displayContent() {
        activeReaderSearchHighlight = false
        val continuous = pageTurnMode == TURN_MODE_VERTICAL
        updateStructuredLocationFromCurrentPosition()
        if (txtStreamingMode) {
            contentView.text = styledReadingText(currentContent)
            contentView.textSize = readerTextSize
            configureVerticalScrollIfNeeded()
            if (continuous) {
                readerScrollView.post {
                    val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                    val windowBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
                    val fraction = ((currentPosition.toLong() - txtCurrentPageStartByte).toDouble() / windowBytes)
                        .coerceIn(0.0, 1.0)
                    suppressNextScrollProgress = true
                    readerScrollView.scrollTo(0, (maxScroll * fraction).toInt().coerceIn(0, maxScroll))
                    readerScrollView.post {
                        suppressNextScrollProgress = false
                        updateProgressViews(progressForCurrentPosition())
                    }
                }
            } else {
                readerScrollView.scrollTo(0, 0)
            }
            val progress = if (txtTotalBytes > 0L) {
                ((currentPosition.toFloat() / txtTotalBytes) * 1000).toInt()
            } else {
                0
            }
            updateProgressViews(progress.coerceIn(0, 1000))
            scheduleReaderSearchHighlight()
            return
        }

        if (continuous) {
            contentView.text = styledReadingText(currentContent)
            contentView.post {
                val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                if (maxScroll > 0 && currentContent.isNotEmpty()) {
                    val fraction = (currentPosition.toFloat() / currentContent.length).coerceIn(0f, 1f)
                    suppressNextScrollProgress = true
                    readerScrollView.scrollTo(0, (maxScroll * fraction).toInt().coerceIn(0, maxScroll))
                    readerScrollView.post {
                        suppressNextScrollProgress = false
                        updateProgressViews(progressForCurrentPosition())
                    }
                }
            }
        } else {
            val endPosition = (currentPosition + pageSize).coerceAtMost(currentContent.length)
            val safeStart = currentPosition.coerceIn(0, endPosition)
            contentView.text = styledReadingText(currentContent.substring(safeStart, endPosition))
            readerScrollView.scrollTo(0, 0)
        }
        contentView.textSize = readerTextSize
        configureVerticalScrollIfNeeded()
        if (currentContent.isNotEmpty()) {
            updateProgressViews(progressForCurrentPosition())
        }
        scheduleReaderSearchHighlight()
    }

    private fun updateProgressViews(progress: Int) {
        fontSizeSeekBar.progress = progress
        readerProgressLabel.text = pageCountLabel()
    }

    private fun pageCountLabel(): String {
        val (positionUnits, totalUnits) = readerPageUnits()
        val unitsPerPage = pageSize.toLong().coerceAtLeast(1L)
        val totalPages = ((totalUnits + unitsPerPage - 1L) / unitsPerPage).coerceAtLeast(1L)
        val currentPage = (positionUnits / unitsPerPage + 1L).coerceIn(1L, totalPages)
        return "$currentPage/$totalPages"
    }

    private fun renderedPageCountLabel(): String? {
        if (pageTurnMode != TURN_MODE_VERTICAL) return null
        val viewportHeight = readerScrollView.height.coerceAtLeast(0)
        val contentHeight = contentView.height.coerceAtLeast(0)
        if (viewportHeight <= 0 || contentHeight <= 0) return null
        val pageHeight = viewportHeight.coerceAtLeast(1)
        val totalPages = ((contentHeight + pageHeight - 1) / pageHeight).coerceAtLeast(1)
        val currentPage = (readerScrollView.scrollY / pageHeight + 1).coerceIn(1, totalPages)
        return "$currentPage/$totalPages"
    }

    private fun styledReadingText(text: String): CharSequence {
        if (text.isBlank()) return text
        val spannable = SpannableString(text)
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
            val line = text.substring(lineStart, lineEnd).trim()
            if (TxtParser.isLikelyChapterTitle(line)) {
                spannable.setSpan(
                    RelativeSizeSpan(1.12f),
                    lineStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            lineStart = lineEnd + 1
        }
        return spannable
    }

    private fun readerPageUnits(): Pair<Long, Long> {
        return when {
            txtStreamingMode -> {
                currentPosition.toLong().coerceAtLeast(0L) to txtTotalBytes.coerceAtLeast(1L)
            }
            isStructuredChapterDocument() && structuredWholeText != null -> {
                val wholeText = structuredWholeText.orEmpty()
                val location = currentStructuredLocation()
                val chapterStart = epubChapterStartPositions.getOrElse(location.chapterIndex) { 0 }
                (chapterStart + location.offset).toLong().coerceAtLeast(0L) to
                    wholeText.length.toLong().coerceAtLeast(1L)
            }
            else -> {
                currentPosition.toLong().coerceAtLeast(0L) to
                    currentContent.length.toLong().coerceAtLeast(1L)
            }
        }
    }

    private fun estimatedTxtBytesPerPage(): Long {
        val visibleBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
        val visibleCharacters = currentContent.length.coerceAtLeast(1)
        val averageBytesPerCharacter = visibleBytes.toDouble() / visibleCharacters.toDouble()
        return (pageSize * averageBytesPerCharacter)
            .toLong()
            .coerceAtLeast(pageSize.toLong())
    }

    private fun configureVerticalScrollIfNeeded() {
        val continuous = pageTurnMode == TURN_MODE_VERTICAL
        contentView.movementMethod = null
        contentView.isVerticalScrollBarEnabled = false
        readerScrollView.isVerticalScrollBarEnabled = continuous
        readerScrollView.isScrollbarFadingEnabled = false
        readerScrollView.isSmoothScrollingEnabled = true
        readerScrollView.overScrollMode = if (continuous) {
            View.OVER_SCROLL_IF_CONTENT_SCROLLS
        } else {
            View.OVER_SCROLL_NEVER
        }
    }

    private fun updateVerticalScrollProgress(scrollY: Int) {
        if (suppressNextScrollProgress) return
        if (pageTurnMode != TURN_MODE_VERTICAL || !openSucceeded || currentContent.isBlank()) return
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        if (maxScroll <= 0) return
        val scrollProgress = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        currentPosition = if (txtStreamingMode) {
            txtContinuousBuffer.byteForFraction(scrollProgress)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            (currentContent.length * scrollProgress).toInt().coerceIn(0, currentContent.length)
        }
        updateStructuredLocationFromCurrentPosition()
        updateProgressViews(progressForCurrentPosition())
        markProgressDirty()
        scheduleProgressSave()
    }

    private fun maybeExtendTxtContinuousBuffer(scrollY: Int) {
        if (
            suppressNextScrollProgress ||
            pageTurnMode != TURN_MODE_VERTICAL ||
            !txtStreamingMode ||
            !openSucceeded ||
            txtBufferLoadJob?.isActive == true ||
            txtContinuousBuffer.isEmpty
        ) return
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        if (maxScroll <= 0) {
            if (!txtReachedEnd) extendTxtContinuousBuffer(forward = true)
            return
        }
        val fraction = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        when {
            fraction >= TXT_PREFETCH_FORWARD_FRACTION && !txtReachedEnd ->
                extendTxtContinuousBuffer(forward = true)
            fraction <= TXT_PREFETCH_BACKWARD_FRACTION && !txtReachedStart ->
                extendTxtContinuousBuffer(forward = false)
        }
    }

    private fun extendTxtContinuousBuffer(forward: Boolean) {
        val selectedBook = book ?: return
        val targetUri = Uri.parse(selectedBook.filePath)
        val charsetName = txtCharsetName ?: selectedBook.txtCharset ?: Charsets.UTF_8.name()
        val anchorByte = currentPosition.toLong()
        val oldScrollY = readerScrollView.scrollY
        txtBufferLoadJob = lifecycleScope.launch {
            try {
                val window = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(targetUri)?.let { stream ->
                        if (forward) {
                            TxtParser.readWindow(
                                inputStream = stream,
                                charsetName = charsetName,
                                startByte = txtContinuousBuffer.endByte,
                                maxBytes = TXT_STREAM_WINDOW_BYTES
                            )
                        } else {
                            TxtParser.readWindowBefore(
                                inputStream = stream,
                                charsetName = charsetName,
                                endByte = txtContinuousBuffer.startByte,
                                maxBytes = TXT_STREAM_WINDOW_BYTES
                            )
                        }
                    }
                } ?: return@launch
                val mutation = if (forward) {
                    txtContinuousBuffer.append(window)
                } else {
                    txtContinuousBuffer.prepend(window)
                }
                if (!mutation.accepted) {
                    if (forward) txtReachedEnd = true else txtReachedStart = true
                    return@launch
                }
                txtReachedStart = txtContinuousBuffer.startByte <= 0L
                txtReachedEnd = txtContinuousBuffer.endByte >= txtTotalBytes
                txtCurrentPageStartByte = txtContinuousBuffer.startByte
                txtCurrentPageEndByte = txtContinuousBuffer.endByte
                currentContent = txtContinuousBuffer.content
                renderExtendedTxtBuffer(
                    anchorByte = anchorByte,
                    oldScrollY = oldScrollY,
                    preserveAbsoluteAnchor = true
                )
            } catch (_: Throwable) {
                // Keep the already visible buffer usable. A later scroll retries.
            }
        }
    }

    private fun renderExtendedTxtBuffer(
        anchorByte: Long,
        oldScrollY: Int,
        preserveAbsoluteAnchor: Boolean
    ) {
        suppressNextScrollProgress = true
        contentView.text = currentContent
        contentView.textSize = readerTextSize
        configureVerticalScrollIfNeeded()
        contentView.post {
            val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
            val targetScroll = if (preserveAbsoluteAnchor) {
                (maxScroll * txtContinuousBuffer.fractionForByte(anchorByte)).toInt()
            } else {
                oldScrollY
            }.coerceIn(0, maxScroll)
            readerScrollView.scrollTo(0, targetScroll)
            readerScrollView.post {
                suppressNextScrollProgress = false
                updateVerticalScrollProgress(readerScrollView.scrollY)
                maybeExtendTxtContinuousBuffer(readerScrollView.scrollY)
            }
        }
    }

    private fun progressForCurrentPosition(): Int {
        return if (txtStreamingMode && txtTotalBytes > 0L) {
            ((currentPosition.toFloat() / txtTotalBytes) * 1000).toInt().coerceIn(0, 1000)
        } else if (isStructuredChapterDocument()) {
            if (structuredWholeBookMode && currentContent.isNotEmpty()) {
                ((currentPosition.toFloat() / currentContent.length) * 1000)
                    .toInt()
                    .coerceIn(0, 1000)
            } else {
                val chapterCount = epubChapters.size.coerceAtLeast(1)
                val location = currentStructuredLocation()
                val chapterLength = structuredReadingBuffer
                    ?.chapterLength(location.chapterIndex)
                    ?.coerceAtLeast(1)
                    ?: currentContent.length.coerceAtLeast(1)
                val chapterFraction = (location.offset.toFloat() / chapterLength).coerceIn(0f, 1f)
                (((location.chapterIndex + chapterFraction) / chapterCount) * 1000)
                    .toInt()
                    .coerceIn(0, 1000)
            }
        } else if (currentContent.isNotEmpty()) {
            ((currentPosition.toFloat() / currentContent.length) * 1000).toInt().coerceIn(0, 1000)
        } else {
            0
        }
    }

    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
        ReaderAppearance.saveDayPalette(this, backgroundColor, textColor)
        applyActiveReaderMode(ReaderAppearance.palette(this))
    }

    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        contentView.setBackgroundColor(palette.backgroundColor)
        contentView.setTextColor(palette.textColor)
        window.decorView.setBackgroundColor(palette.backgroundColor)
        updateThemeControls()
    }

    private fun loadReaderPrefs() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSize = prefs.getFloat(PREF_TEXT_SIZE, 18f)
        val palette = ReaderAppearance.palette(this)
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP
        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
    }

    private fun saveReaderPrefs() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSize)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .apply()
    }

    private fun turnModeLabel(mode: String): String {
        return when (mode) {
            TURN_MODE_SIMULATE -> "仿真"
            TURN_MODE_HORIZONTAL -> "平移"
            TURN_MODE_VERTICAL -> "上下"
            TURN_MODE_FADE -> "淡入"
            else -> "覆盖"
        }
    }

    private fun setReaderChromeVisible(visible: Boolean) {
        readerChromeVisible = visible
        readerControls.visibility = if (visible) View.VISIBLE else View.GONE
        readerProgressLabel.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) supportActionBar?.show() else supportActionBar?.hide()
    }

    private fun toggleReaderChrome() {
        setReaderChromeVisible(!readerChromeVisible)
    }

    private fun showContentSearch() {
        if (currentContent.isBlank()) {
            Toast.makeText(this, "当前没有可搜索的内容", Toast.LENGTH_SHORT).show()
            return
        }
        showReaderSearchPanel()
    }

    private fun showReaderSearchPanel() {
        val density = resources.displayMetrics.density
        fun localDp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(localDp(8), localDp(6), localDp(8), localDp(4))
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "输入当前书内关键词"
            setSingleLine(true)
            setText(readerSearchSession?.query.orEmpty())
            setSelection(text.length)
        }
        val searchButton = Button(this).apply {
            text = "搜索"
            isAllCaps = false
        }
        searchRow.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        searchRow.addView(
            searchButton,
            LinearLayout.LayoutParams(
                localDp(88),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(110, 100, 84))
            setPadding(localDp(12), localDp(6), localDp(12), localDp(6))
        }
        val rows = mutableListOf<ReaderSearchHit>().apply {
            addAll(readerSearchSession?.hits.orEmpty())
        }
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): ReaderSearchHit = rows[position]
            override fun getItemId(position: Int): Long = rows[position].stableKey.hashCode().toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View {
                val row = (convertView as? TextView) ?: TextView(parent.context).apply {
                    textSize = 15f
                    setTextColor(Color.rgb(38, 35, 31))
                    setPadding(localDp(14), localDp(10), localDp(14), localDp(10))
                    maxLines = 4
                }
                val hit = rows[position]
                row.text = "${position + 1}. ${hit.positionLabel}\n${hit.preview}"
                return row
            }
        }
        val listView = ListView(this).apply {
            adapter = listAdapter
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }

        container.addView(searchRow)
        container.addView(statusView)
        container.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.63f).toInt()
            )
        )

        var dialog: AlertDialog? = null
        var loadJob: Job? = null

        fun saveListPosition() {
            val session = readerSearchSession ?: return
            session.listFirstVisible = listView.firstVisiblePosition.coerceAtLeast(0)
            session.listTopOffset = listView.getChildAt(0)?.top ?: 0
        }

        fun updateStatus() {
            val session = readerSearchSession
            statusView.text = when {
                session == null || session.query.isBlank() ->
                    "输入关键词后点击搜索"
                session.loading ->
                    "正在搜索……已加载 ${rows.size} 条"
                session.endReached && rows.isEmpty() ->
                    "没有找到结果 · 到底了"
                session.endReached ->
                    "共 ${rows.size} 条结果 · 到底了"
                else ->
                    "已加载 ${rows.size} 条，继续向下滑动加载更多"
            }
        }

        fun refreshRows() {
            rows.clear()
            rows.addAll(readerSearchSession?.hits.orEmpty())
            listAdapter.notifyDataSetChanged()
            updateStatus()
        }

        fun loadNextPage() {
            val session = readerSearchSession ?: return
            if (session.loading || session.endReached || session.query.isBlank()) return
            val remaining = MAX_SEARCH_RESULTS - session.hits.size
            if (remaining <= 0) {
                session.endReached = true
                updateStatus()
                return
            }
            session.loading = true
            updateStatus()
            val pageSize = minOf(SEARCH_RESULT_PAGE_SIZE, remaining)
            loadJob?.cancel()
            loadJob = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        when {
                            txtStreamingMode -> {
                                val selectedBook = book
                                val charsetName = txtCharsetName
                                    ?: selectedBook?.txtCharset
                                    ?: Charsets.UTF_8.name()
                                val parsed = selectedBook?.let { activeBook ->
                                    contentResolver.openInputStream(Uri.parse(activeBook.filePath))?.use { stream ->
                                        TxtParser.findTextPage(
                                            inputStream = stream,
                                            charsetName = charsetName,
                                            query = session.query,
                                            startByte = session.nextPosition,
                                            pageSize = pageSize
                                        )
                                    }
                                }
                                if (parsed == null) {
                                    ReaderSearchPage(emptyList(), session.nextPosition, true)
                                } else {
                                    ReaderSearchPage(
                                        hits = parsed.hits.map { hit ->
                                            val percent = if (txtTotalBytes > 0L) {
                                                ((hit.byteOffset.toDouble() / txtTotalBytes) * 100)
                                                    .toInt()
                                                    .coerceIn(0, 100)
                                            } else {
                                                0
                                            }
                                            ReaderSearchHit(
                                                stableKey = "byte:${hit.byteOffset}",
                                                position = hit.byteOffset,
                                                positionLabel = "约 $percent% · 字节 ${hit.byteOffset}",
                                                preview = hit.preview
                                            )
                                        },
                                        nextPosition = parsed.nextByte,
                                        endReached = parsed.endReached
                                    )
                                }
                            }
                            isStructuredChapterDocument() && structuredWholeText != null ->
                                findWholeStructuredSearchPage(
                                    query = session.query,
                                    startIndex = session.nextPosition.toInt(),
                                    pageSize = pageSize
                                )
                            else ->
                                findInMemorySearchPage(
                                    query = session.query,
                                    startIndex = session.nextPosition.toInt(),
                                    pageSize = pageSize
                                )
                        }
                    }
                    if (readerSearchSession !== session) return@launch
                    val merged = (session.hits + page.hits)
                        .distinctBy { it.stableKey }
                        .sortedBy { it.position }
                        .take(MAX_SEARCH_RESULTS)
                    session.hits.clear()
                    session.hits.addAll(merged)
                    val madeProgress = page.nextPosition > session.nextPosition
                    session.nextPosition = page.nextPosition
                    session.endReached = page.endReached ||
                        !madeProgress ||
                        session.hits.size >= MAX_SEARCH_RESULTS
                    refreshRows()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (readerSearchSession === session) {
                        session.endReached = true
                        Toast.makeText(
                            this@ReaderActivity,
                            "搜索失败：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    if (readerSearchSession === session) {
                        session.loading = false
                        updateStatus()
                    }
                }
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val session = readerSearchSession ?: return@setOnItemClickListener
            val hit = rows.getOrNull(position) ?: return@setOnItemClickListener
            saveListPosition()
            pendingReaderSearchHighlight = ReaderSearchHighlight(
                query = session.query,
                stableKey = hit.stableKey,
                position = hit.position
            )
            activeReaderSearchHighlight = false
            dialog?.dismiss()
            jumpToReaderSearchHit(hit)
        }

        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(
                view: android.widget.AbsListView?,
                scrollState: Int
            ) = Unit

            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                val session = readerSearchSession ?: return
                session.listFirstVisible = firstVisibleItem.coerceAtLeast(0)
                session.listTopOffset = listView.getChildAt(0)?.top ?: 0
                if (
                    totalItemCount > 0 &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - 4
                ) {
                    loadNextPage()
                }
            }
        })

        searchButton.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            clearReaderSearchHighlight()
            readerSearchSession = ReaderSearchSession(query = query)
            refreshRows()
            loadNextPage()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("书内搜索")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.setOnDismissListener {
            saveListPosition()
            loadJob?.cancel()
            readerSearchSession?.loading = false
        }
        dialog?.show()

        val session = readerSearchSession
        updateStatus()
        if (session != null) {
            listView.post {
                listView.setSelectionFromTop(
                    session.listFirstVisible.coerceIn(0, rows.lastIndex.coerceAtLeast(0)),
                    session.listTopOffset
                )
                if (rows.isEmpty() && !session.endReached) {
                    loadNextPage()
                }
            }
        }
    }

    private fun jumpToReaderSearchHit(hit: ReaderSearchHit) {
        setReaderChromeVisible(false)
        when {
            txtStreamingMode -> {
                showStreamingTxtPage(
                    hit.position,
                    saveImmediately = true,
                    keepContextBeforeTarget = true
                )
            }
            isStructuredChapterDocument() &&
                structuredWholeText != null &&
                hit.stableKey.startsWith("whole:") -> {
                val wholeText = structuredWholeText ?: return
                val globalPosition = hit.position.toInt().coerceIn(0, wholeText.length)
                val chapterIndex = epubChapterStartPositions
                    .indexOfLast { it <= globalPosition }
                    .coerceAtLeast(0)
                    .coerceAtMost(epubChapters.lastIndex)
                val chapterStart = epubChapterStartPositions.getOrElse(chapterIndex) { 0 }
                loadStructuredChapter(
                    chapterIndex = chapterIndex,
                    offset = (globalPosition - chapterStart).coerceAtLeast(0),
                    saveImmediately = true
                )
            }
            else -> {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
            }
        }
    }

    private fun findWholeStructuredSearchPage(
        query: String,
        startIndex: Int,
        pageSize: Int
    ): ReaderSearchPage {
        val wholeText = structuredWholeText
            ?: return ReaderSearchPage(emptyList(), startIndex.toLong(), true)
        if (query.isBlank() || wholeText.isEmpty()) {
            return ReaderSearchPage(emptyList(), startIndex.toLong(), true)
        }
        val hits = mutableListOf<ReaderSearchHit>()
        var cursor = startIndex.coerceIn(0, wholeText.length)
        var endReached = false
        while (hits.size < pageSize) {
            val index = wholeText.indexOf(
                query,
                startIndex = cursor,
                ignoreCase = true
            )
            if (index < 0) {
                endReached = true
                break
            }
            val previewStart = (index - 45).coerceAtLeast(0)
            val previewEnd = (index + query.length + 90).coerceAtMost(wholeText.length)
            val preview = wholeText.substring(previewStart, previewEnd)
                .replace(Regex("\\s+"), " ")
                .trim()
            val chapterIndex = epubChapterStartPositions
                .indexOfLast { it <= index }
                .coerceAtLeast(0)
                .coerceAtMost(epubChapters.lastIndex)
            val chapterTitle = epubChapters.getOrNull(chapterIndex)
                ?.name
                ?.substringAfterLast('/')
                .orEmpty()
                .ifBlank { "第 ${chapterIndex + 1} 章" }
            val percent = ((index.toDouble() / wholeText.length) * 100)
                .toInt()
                .coerceIn(0, 100)
            hits += ReaderSearchHit(
                stableKey = "whole:$index",
                position = index.toLong(),
                positionLabel = "$chapterTitle · 约 $percent%",
                preview = preview.ifBlank { "位置 $index" }
            )
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(wholeText.length)
            if (cursor >= wholeText.length) {
                endReached = true
                break
            }
        }
        return ReaderSearchPage(
            hits = hits,
            nextPosition = cursor.toLong(),
            endReached = endReached
        )
    }

    private fun scheduleReaderSearchHighlight() {
        val pending = pendingReaderSearchHighlight ?: return
        contentView.post {
            if (pendingReaderSearchHighlight == pending) {
                applyReaderSearchHighlight(pending)
            }
        }
    }

    private fun applyReaderSearchHighlight(highlight: ReaderSearchHighlight) {
        val displayedText = contentView.text.toString()
        if (displayedText.isEmpty() || highlight.query.isBlank()) {
            pendingReaderSearchHighlight = null
            return
        }
        val anchor = readerSearchDisplayAnchor(highlight, displayedText.length)
        val start = ReaderSearchSupport.nearestMatch(
            text = displayedText,
            query = highlight.query,
            anchor = anchor,
            ignoreCase = true
        )
        if (start < 0) {
            pendingReaderSearchHighlight = null
            return
        }
        val end = (start + highlight.query.length).coerceAtMost(displayedText.length)
        val styled = SpannableString(displayedText).apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                BackgroundColorSpan(Color.rgb(255, 232, 125)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        pendingReaderSearchHighlight = null
        activeReaderSearchHighlight = true
        contentView.text = styled
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            contentView.post {
                val layout = contentView.layout ?: return@post
                val line = layout.getLineForOffset(start.coerceIn(0, displayedText.length))
                val lineTop = layout.getLineTop(line)
                val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                val targetScroll = (lineTop - readerScrollView.height / 3).coerceIn(0, maxScroll)
                suppressNextScrollProgress = true
                readerScrollView.scrollTo(0, targetScroll)
                readerScrollView.post { suppressNextScrollProgress = false }
            }
        }
    }

    private fun readerSearchDisplayAnchor(
        highlight: ReaderSearchHighlight,
        displayLength: Int
    ): Int {
        val bufferPosition = when {
            txtStreamingMode -> {
                val byteSpan = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
                val fraction = (
                    (highlight.position - txtCurrentPageStartByte).toDouble() /
                        byteSpan.toDouble()
                    ).coerceIn(0.0, 1.0)
                (currentContent.length * fraction).toInt()
            }
            highlight.stableKey.startsWith("whole:") &&
                isStructuredChapterDocument() -> {
                val globalPosition = highlight.position.toInt().coerceAtLeast(0)
                if (structuredWholeBookMode) {
                    globalPosition
                } else {
                    val chapterIndex = epubChapterStartPositions
                        .indexOfLast { it <= globalPosition }
                        .coerceAtLeast(0)
                        .coerceAtMost(epubChapters.lastIndex)
                    val chapterStart = epubChapterStartPositions.getOrElse(chapterIndex) { 0 }
                    structuredReadingBuffer
                        ?.positionFor(chapterIndex, (globalPosition - chapterStart).coerceAtLeast(0))
                        ?: currentPosition
                }
            }
            else -> highlight.position.toInt().coerceAtLeast(0)
        }
        val displayAnchor = if (
            txtStreamingMode ||
            pageTurnMode == TURN_MODE_VERTICAL
        ) {
            bufferPosition
        } else {
            bufferPosition - currentPosition
        }
        return displayAnchor.coerceIn(0, displayLength)
    }

    private fun clearReaderSearchHighlightOnUserScroll() {
        if (suppressNextScrollProgress || !activeReaderSearchHighlight) return
        clearReaderSearchHighlight()
    }

    private fun clearReaderSearchHighlight() {
        if (!activeReaderSearchHighlight) return
        val oldScrollY = readerScrollView.scrollY
        activeReaderSearchHighlight = false
        contentView.text = contentView.text.toString()
        contentView.post {
            val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
            readerScrollView.scrollTo(0, oldScrollY.coerceIn(0, maxScroll))
        }
    }

    private fun showContentSearchResults(query: String) {
        val density = resources.displayMetrics.density
        fun localDp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(localDp(8), localDp(4), localDp(8), localDp(4))
        }
        val statusView = TextView(this).apply {
            text = "正在搜索…"
            textSize = 14f
            setTextColor(Color.rgb(110, 100, 84))
            setPadding(localDp(12), localDp(8), localDp(12), localDp(8))
        }
        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            this.layoutManager = layoutManager
            itemAnimator = null
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        container.addView(statusView)
        container.addView(
            recyclerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.68f).toInt()
            )
        )

        var dialog: AlertDialog? = null
        var loading = false
        var endReached = false
        var nextPosition = 0L
        var loadJob: Job? = null

        val adapter = ReaderSearchResultAdapter { hit ->
            dialog?.dismiss()
            if (txtStreamingMode) {
                showStreamingTxtPage(hit.position, saveImmediately = true, keepContextBeforeTarget = true)
            } else {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
                setReaderChromeVisible(false)
            }
        }
        recyclerView.adapter = adapter

        fun updateStatus() {
            statusView.text = when {
                adapter.currentList.isEmpty() && endReached -> "没有找到“$query”"
                endReached -> "共 ${adapter.itemCount} 条结果"
                loading -> "正在加载更多结果…"
                else -> "已加载 ${adapter.itemCount} 条，继续下拉加载"
            }
        }

        fun loadNextPage() {
            if (loading || endReached) return
            loading = true
            updateStatus()
            loadJob = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        if (txtStreamingMode) {
                            val selectedBook = book
                            val charsetName = txtCharsetName
                                ?: selectedBook?.txtCharset
                                ?: Charsets.UTF_8.name()
                            val parsed = selectedBook?.let { activeBook ->
                                contentResolver.openInputStream(Uri.parse(activeBook.filePath))?.let { stream ->
                                    TxtParser.findTextPage(
                                        inputStream = stream,
                                        charsetName = charsetName,
                                        query = query,
                                        startByte = nextPosition,
                                        pageSize = 40
                                    )
                                }
                            }
                            if (parsed == null) {
                                ReaderSearchPage(emptyList(), nextPosition, true)
                            } else {
                                ReaderSearchPage(
                                    hits = parsed.hits.map { hit ->
                                        val percent = if (txtTotalBytes > 0L) {
                                            ((hit.byteOffset.toDouble() / txtTotalBytes) * 100).toInt()
                                                .coerceIn(0, 100)
                                        } else 0
                                        ReaderSearchHit(
                                            stableKey = "byte:${hit.byteOffset}",
                                            position = hit.byteOffset,
                                            positionLabel = "约 $percent% · 字节 ${hit.byteOffset}",
                                            preview = hit.preview
                                        )
                                    },
                                    nextPosition = parsed.nextByte,
                                    endReached = parsed.endReached
                                )
                            }
                        } else {
                            findInMemorySearchPage(query, nextPosition.toInt(), 40)
                        }
                    }

                    val merged = (adapter.currentList + page.hits)
                        .distinctBy { it.stableKey }
                    adapter.submitList(merged)
                    nextPosition = page.nextPosition
                    endReached = page.endReached
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    endReached = true
                    Toast.makeText(
                        this@ReaderActivity,
                        "搜索失败：${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    loading = false
                    updateStatus()
                    recyclerView.post {
                        if (!endReached && !recyclerView.canScrollVertically(1)) {
                            loadNextPage()
                        }
                    }
                }
            }
        }

        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(view: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(view, dx, dy)
                if (dy < 0 || loading || endReached) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - 6) {
                    loadNextPage()
                }
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("“$query”的搜索结果")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.setOnDismissListener { loadJob?.cancel() }
        dialog?.show()
        loadNextPage()
    }

    private fun findInMemorySearchPage(
        query: String,
        startIndex: Int,
        pageSize: Int
    ): ReaderSearchPage {
        if (query.isBlank() || currentContent.isEmpty()) {
            return ReaderSearchPage(emptyList(), startIndex.toLong(), true)
        }
        val hits = mutableListOf<ReaderSearchHit>()
        var cursor = startIndex.coerceIn(0, currentContent.length)
        var endReached = false
        while (hits.size < pageSize) {
            val index = currentContent.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (index < 0) {
                endReached = true
                break
            }
            val previewStart = (index - 45).coerceAtLeast(0)
            val previewEnd = (index + query.length + 90).coerceAtMost(currentContent.length)
            val preview = currentContent.substring(previewStart, previewEnd)
                .replace(Regex("\\s+"), " ")
                .trim()
            val percent = if (currentContent.isNotEmpty()) {
                ((index.toDouble() / currentContent.length) * 100).toInt().coerceIn(0, 100)
            } else 0
            hits += ReaderSearchHit(
                stableKey = "char:$index",
                position = index.toLong(),
                positionLabel = "约 $percent% · 位置 $index",
                preview = preview.ifBlank { "位置 $index" }
            )
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(currentContent.length)
            if (cursor >= currentContent.length) {
                endReached = true
                break
            }
        }
        return ReaderSearchPage(
            hits = hits,
            nextPosition = cursor.toLong(),
            endReached = endReached
        )
    }

    private fun jumpChapter(direction: Int) {
        if (epubChapterStartPositions.isEmpty()) {
            Toast.makeText(this, "当前书籍没有章节目录", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIndex = if (isStructuredChapterDocument()) {
            structuredChapterIndex
        } else {
            epubChapterStartPositions.indexOfLast { it <= currentPosition }.coerceAtLeast(0)
        }
        val targetIndex = (currentIndex + direction).coerceIn(0, epubChapterStartPositions.lastIndex)
        if (targetIndex == currentIndex) {
            Toast.makeText(this, if (direction < 0) "已经是第一章" else "已经是最后一章", Toast.LENGTH_SHORT).show()
            return
        }
        if (isStructuredChapterDocument()) {
            loadStructuredChapter(targetIndex, offset = 0, saveImmediately = true, direction = direction)
            return
        }
        currentPosition = epubChapterStartPositions[targetIndex]
        if (txtStreamingMode) {
            showStreamingTxtPage(
                currentPosition.toLong(),
                saveImmediately = true,
                direction = direction,
                keepContextBeforeTarget = direction == 0
            )
        } else {
            displayContent()
            animatePageTurn(direction)
            markProgressDirty()
            saveProgressNow()
        }
    }

    private fun showCatalogBookmarkPanel() {
        showCatalogBookmarkPanelV2()
    }

    private fun showReaderSettings() {
        showReaderSettingsV2()
    }

    private fun showCatalogBookmarkPanelV2(showBookmarksFirst: Boolean = false) {
        lifecycleScope.launch {
            var bookmarks = withContext(Dispatchers.IO) {
                database.bookmarkDao().getBookmarks(bookId).first()
            }
            var showingCatalog = !showBookmarksFirst
            var catalogInitialPositionApplied = false
            val container = LinearLayout(this@ReaderActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 8, 12, 8)
            }
            val tabs = LinearLayout(this@ReaderActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val catalogButton = ButtonLikeText("目录")
            val bookmarkButton = ButtonLikeText("书签")
            tabs.addView(catalogButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabs.addView(bookmarkButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val listView = ListView(this@ReaderActivity)
            container.addView(tabs)
            container.addView(listView)
            var dialog: AlertDialog? = null

            fun requestTxtCatalogScan(onChanged: () -> Unit) {
                val document = currentReadableDocument ?: return
                if (!txtStreamingMode || epubChapters.isNotEmpty()) return
                if (chapterScanJob?.isActive == true) return
                chapterScanJob = lifecycleScope.launch {
                    val chapters = try {
                        val charsetName = txtCharsetName ?: book?.txtCharset ?: Charsets.UTF_8.name()
                        withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(document.uri)?.let { stream ->
                                TxtParser.scanChapters(stream, charsetName)
                            }.orEmpty()
                        }
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (chapters.isNotEmpty()) {
                        epubChapters = chapters.map { chapter ->
                            EpubChapter(name = chapter.title, text = "")
                        }
                        epubChapterStartPositions = chapters.map {
                            it.byteOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        }
                        withContext(Dispatchers.IO) {
                            writeTxtChapterCache(
                                totalBytes = txtTotalBytes,
                                lastModified = document.lastModified(),
                                charsetName = txtCharsetName ?: book?.txtCharset ?: Charsets.UTF_8.name(),
                                chapters = chapters.map { TxtChapterIndexLong(it.title, it.byteOffset) }
                            )
                        }
                    }
                    onChanged()
                }
            }

            fun boundedLineAdapter(
                labels: List<CharSequence>,
                highlightedIndex: Int = -1,
                maxLines: Int = 2
            ): ArrayAdapter<CharSequence> {
                return object : ArrayAdapter<CharSequence>(
                    this@ReaderActivity,
                    android.R.layout.simple_list_item_1,
                    labels
                ) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as? TextView)?.apply {
                            this.maxLines = maxLines
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            if (position == highlightedIndex) {
                                textSize = 15f
                                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                                setTextColor(Color.rgb(230, 112, 42))
                            } else {
                                textSize = 15f
                                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                                setTextColor(Color.rgb(42, 39, 31))
                            }
                        }
                        return view
                    }
                }
            }

            fun positionMeta(position: Int): String {
                if (isStructuredChapterDocument()) {
                    val index = epubChapterStartPositions.indexOfLast { it <= position }
                        .coerceAtLeast(0)
                        .coerceAtMost(epubChapters.lastIndex.coerceAtLeast(0))
                    val percent = if (currentContent.isNotEmpty()) {
                        (position.toDouble() / currentContent.length.toDouble() * 100.0).coerceIn(0.0, 100.0)
                    } else {
                        0.0
                    }
                    return "第 ${index + 1} 章 · ${String.format(java.util.Locale.US, "%.1f%%", percent)}"
                }
                val safePosition = position.coerceAtLeast(0).toLong()
                val total = if (txtStreamingMode) txtTotalBytes else currentContent.length.toLong()
                val safeTotal = total.coerceAtLeast(1L)
                val page = (safePosition / pageSize.coerceAtLeast(1)).coerceAtLeast(0L) + 1L
                val percent = (safePosition.toDouble() / safeTotal.toDouble() * 100.0).coerceIn(0.0, 100.0)
                return "第 ${page} 页 · ${String.format(java.util.Locale.US, "%.1f%%", percent)}"
            }

            fun catalogLabel(index: Int, title: String, position: Int, highlighted: Boolean): CharSequence {
                val label = "${index + 1}. $title\n${positionMeta(position)}"
                if (!highlighted) return label
                return SpannableString(label).apply {
                    val titleEnd = label.indexOf('\n').let { if (it >= 0) it else label.length }
                    setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(1.18f), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            fun structuredCatalogLabel(
                entry: StructuredCatalogEntry,
                position: Int,
                highlighted: Boolean
            ): CharSequence {
                val indent = "　".repeat(entry.depth.coerceIn(0, 8))
                val markerText = if (entry.depth == 0 || entry.isSection) "◆ " else "└ "
                val title = "$indent$markerText${entry.title}"
                val label = "$title\n${positionMeta(position)}"
                if (!highlighted) return label
                return SpannableString(label).apply {
                    val titleEnd = label.indexOf('\n').let { if (it >= 0) it else label.length }
                    setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(
                        RelativeSizeSpan(if (entry.depth == 0 || entry.isSection) 1.22f else 1.12f),
                        0,
                        titleEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            fun currentChapterIndex(): Int {
                if (epubChapterStartPositions.isEmpty()) return -1
                if (isStructuredChapterDocument()) return structuredChapterIndex.coerceIn(0, epubChapterStartPositions.lastIndex)
                val current = currentPosition.coerceAtLeast(0)
                return epubChapterStartPositions.indexOfLast { it <= current }
                    .coerceAtLeast(0)
            }

            fun render() {
                catalogButton.isEnabled = !showingCatalog
                bookmarkButton.isEnabled = showingCatalog
                if (showingCatalog) {
                    val currentChapter = currentChapterIndex()
                    val useStructuredCatalog = isStructuredChapterDocument() && structuredCatalogEntries.isNotEmpty()
                    val highlightedCatalogIndex = if (useStructuredCatalog) {
                        structuredCatalogEntries.indexOfFirst { entry ->
                            !entry.isSection && entry.targetChapterIndex == currentChapter
                        }
                    } else {
                        currentChapter
                    }
                    val labels = if (epubChapters.isEmpty()) {
                        requestTxtCatalogScan { render() }
                        if (txtStreamingMode) listOf("正在识别目录...") else listOf("暂无目录")
                    } else if (useStructuredCatalog) {
                        structuredCatalogEntries.map { entry ->
                            val position = epubChapterStartPositions.getOrElse(entry.targetChapterIndex) { 0 }
                            structuredCatalogLabel(
                                entry = entry,
                                position = position,
                                highlighted = !entry.isSection && entry.targetChapterIndex == currentChapter
                            )
                        }
                    } else {
                        epubChapters.mapIndexed { index, chapter ->
                            val title = chapter.text.ifBlank {
                                chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
                            }
                            val position = epubChapterStartPositions.getOrElse(index) { 0 }
                            catalogLabel(index, title, position, index == currentChapter)
                        }
                    }
                    listView.adapter = boundedLineAdapter(labels, highlightedCatalogIndex, maxLines = 2)
                    if (!catalogInitialPositionApplied && highlightedCatalogIndex >= 0) {
                        catalogInitialPositionApplied = true
                        listView.post { listView.setSelection(highlightedCatalogIndex) }
                    }
                    listView.setOnItemClickListener { _, _, which, _ ->
                        if (epubChapters.isNotEmpty()) {
                            if (isStructuredChapterDocument()) {
                                val targetChapter = if (useStructuredCatalog) {
                                    structuredCatalogEntries.getOrNull(which)?.targetChapterIndex
                                } else {
                                    which
                                }
                                targetChapter?.let {
                                    loadStructuredChapter(it, offset = 0, saveImmediately = true)
                                }
                            } else {
                                currentPosition = epubChapterStartPositions.getOrElse(which) { 0 }
                                if (txtStreamingMode) {
                                    showStreamingTxtPage(
                                        currentPosition.toLong(),
                                        saveImmediately = true,
                                        keepContextBeforeTarget = true
                                    )
                                } else {
                                    displayContent()
                                    markProgressDirty()
                                    saveProgressNow()
                                }
                            }
                            dialog?.dismiss()
                        }
                    }
                    listView.setOnItemLongClickListener(null)
                } else {
                    val labels = if (bookmarks.isEmpty()) {
                        listOf("暂无书签")
                    } else {
                        bookmarks.map(::bookmarkListLabel)
                    }
                    listView.adapter = boundedLineAdapter(labels, maxLines = 3)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let {
                            jumpToBookmark(it)
                            dialog?.dismiss()
                        }
                    }
                    listView.setOnItemLongClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let { bookmark ->
                            confirmDeleteBookmark(bookmark) {
                                bookmarks = bookmarks.filterNot { it.id == bookmark.id }
                                render()
                            }
                        }
                        true
                    }
                }
            }

            catalogButton.setOnClickListener {
                showingCatalog = true
                render()
            }
            bookmarkButton.setOnClickListener {
                showingCatalog = false
                render()
            }
            render()
            dialog = AlertDialog.Builder(this@ReaderActivity)
                .setTitle(if (showingCatalog) "目录" else "书签")
                .setView(container)
                .create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.rgb(250, 246, 232)))
                window.setGravity(android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
                window.setLayout(
                    (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    private fun ButtonLikeText(label: String): TextView {
        return TextView(this).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            textSize = 18f
            setPadding(0, 16, 0, 16)
        }
    }

    private fun showReaderSettingsV2() {
        val items = arrayOf("字号减小", "字号增大", "纸张背景（日间）", "护眼背景（日间）", "白色背景（日间）", "切换日间 / 夜间")
        AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        readerTextSize = (readerTextSize - 2f).coerceAtLeast(14f)
                        saveReaderPrefs()
                        displayContent()
                    }
                    1 -> {
                        readerTextSize = (readerTextSize + 2f).coerceAtMost(34f)
                        saveReaderPrefs()
                        displayContent()
                    }
                    2 -> applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40))
                    3 -> applyReaderPalette(Color.rgb(218, 238, 205), Color.rgb(48, 60, 42))
                    4 -> applyReaderPalette(Color.WHITE, Color.rgb(35, 35, 35))
                    5 -> applyActiveReaderMode(ReaderAppearance.toggleMode(this))
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmDeleteBookmark(bookmark: Bookmark, onDeleted: () -> Unit = {}) {
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setMessage(bookmark.content.ifBlank { "位置 ${bookmark.position}" })
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        database.bookmarkDao().delete(bookmark)
                    }
                    onDeleted()
                    Toast.makeText(this@ReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showReaderMoreActions() {
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(arrayOf("搜索书内内容", "添加书签", "目录 / 书签")) { _, which ->
                when (which) {
                    0 -> showContentSearch()
                    1 -> addBookmark()
                    2 -> showCatalogBookmarkPanelV2()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showTableOfContents() {
        if (epubChapters.isEmpty()) {
            Toast.makeText(this, "未识别到目录", Toast.LENGTH_SHORT).show()
            return
        }
        val items = epubChapters.mapIndexed { index, chapter ->
            val title = chapter.text.ifBlank { chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" } }
            "${index + 1}. $title"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(items) { _, which ->
                currentPosition = epubChapterStartPositions.getOrElse(which) { 0 }
                displayContent()
                markProgressDirty()
                scheduleProgressSave()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun addBookmark() {
        if (!openSucceeded || currentContent.isBlank() || bookId <= 0L) {
            Toast.makeText(this, "当前没有可添加书签的内容", Toast.LENGTH_SHORT).show()
            return
        }

        val positionToSave = currentPosition.coerceAtLeast(0)
        val preview = bookmarkPreviewAt(positionToSave)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        check(database.bookDao().getBook(bookId) != null) { "书籍记录不存在" }
                        database.bookmarkDao().insert(
                            Bookmark(
                                bookId = bookId,
                                position = positionToSave.toString(),
                                content = preview
                            )
                        )
                    }
                }
                Toast.makeText(this@ReaderActivity, "已添加书签", Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    this@ReaderActivity,
                    "添加书签失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun bookmarkPreviewAt(globalPosition: Int): String {
        if (currentContent.isEmpty()) return "位置 $globalPosition"
        val localStart = if (txtStreamingMode) {
            val windowBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
            val fraction = ((globalPosition.toLong() - txtCurrentPageStartByte).toDouble() / windowBytes)
                .coerceIn(0.0, 1.0)
            (currentContent.length * fraction).toInt()
        } else {
            globalPosition
        }.coerceIn(0, currentContent.length)

        val readableStart = if (localStart >= currentContent.length && currentContent.isNotEmpty()) {
            (currentContent.length - 1).coerceAtLeast(0)
        } else {
            localStart
        }
        val end = (readableStart + 120).coerceAtMost(currentContent.length)
        return currentContent.substring(readableStart, end)
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "位置 $globalPosition" }
    }

    private fun bookmarkListLabel(bookmark: Bookmark): String {
        val position = bookmark.position.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val total = if (txtStreamingMode) {
            txtTotalBytes
        } else {
            currentContent.length.toLong()
        }.coerceAtLeast(1L)
        val page = (position / pageSize.coerceAtLeast(1)).coerceAtLeast(0L) + 1L
        val percent = (position.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
        val progress = String.format(java.util.Locale.US, "%.1f%%", percent)
        val preview = bookmark.content
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "无预览" }
            .let { if (it.length > 64) "${it.take(64)}..." else it }
        return "第 ${page} 页 · $progress\n$preview"
    }

    private fun showBookmarks() {
        lifecycleScope.launch {
            val bookmarks = withContext(Dispatchers.IO) {
                database.bookmarkDao().getBookmarks(bookId).first()
            }
            if (bookmarks.isEmpty()) {
                Toast.makeText(this@ReaderActivity, "暂无书签", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = bookmarks.map(::bookmarkListLabel).toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("书签")
                .setItems(labels) { _, which ->
                    jumpToBookmark(bookmarks[which])
                }
                .setNeutralButton("删除书签", null)
                .setNegativeButton("关闭", null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            showDeleteBookmarkDialog(bookmarks)
                            dialog.dismiss()
                        }
                    }
                }
                .show()
        }
    }

    private fun jumpToBookmark(bookmark: Bookmark) {
        if (txtStreamingMode) {
            val target = bookmark.position.toLongOrNull()
                ?: return Toast.makeText(this, "书签位置无效", Toast.LENGTH_SHORT).show()
            showStreamingTxtPage(target, saveImmediately = true, keepContextBeforeTarget = true)
            return
        }
        val target = bookmark.position.toIntOrNull()
            ?.coerceIn(0, currentContent.length)
            ?: return Toast.makeText(this, "书签位置无效", Toast.LENGTH_SHORT).show()
        currentPosition = target
        displayContent()
        markProgressDirty()
        saveProgressNow()
    }

    private fun showDeleteBookmarkDialog(bookmarks: List<Bookmark>) {
        val labels = bookmarks.map(::bookmarkListLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        database.bookmarkDao().delete(bookmarks[which])
                    }
                    Toast.makeText(this@ReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun nextPage() {
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            scrollContinuousPage(1)
            return
        }
        if (txtStreamingMode) {
            if (txtCurrentPageEndByte < txtTotalBytes) {
                showStreamingTxtPage(txtCurrentPageEndByte, direction = 1)
            } else {
                Toast.makeText(this, "已经到末尾", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (currentPosition + pageSize < currentContent.length) {
            currentPosition += pageSize
            displayContent()
            animatePageTurn(1)
            markProgressDirty()
            scheduleProgressSave()
            return
        }

        if (isStructuredChapterDocument() && !structuredWholeBookMode) {
            val lastBuffered = structuredReadingBuffer?.lastChapterIndex ?: structuredChapterIndex
            if (lastBuffered < epubChapters.lastIndex) {
                loadStructuredChapter(lastBuffered + 1, offset = 0, saveImmediately = true, direction = 1)
            } else {
                Toast.makeText(this, "已经到末尾", Toast.LENGTH_SHORT).show()
            }
            return
        }
        Toast.makeText(this, "已经到末尾", Toast.LENGTH_SHORT).show()
    }

    private fun previousPage() {
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            scrollContinuousPage(-1)
            return
        }
        if (txtStreamingMode) {
            if (currentPosition > 0) {
                showStreamingTxtPage(
                    (currentPosition.toLong() - TXT_STREAM_WINDOW_BYTES).coerceAtLeast(0L),
                    direction = -1
                )
            } else {
                Toast.makeText(this, "已经到开头", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (currentPosition > 0) {
            currentPosition = (currentPosition - pageSize).coerceAtLeast(0)
            displayContent()
            animatePageTurn(-1)
            markProgressDirty()
            scheduleProgressSave()
            return
        }

        if (isStructuredChapterDocument() && !structuredWholeBookMode) {
            val firstBuffered = structuredReadingBuffer?.firstChapterIndex ?: structuredChapterIndex
            if (firstBuffered > 0) {
                loadStructuredChapter(
                    firstBuffered - 1,
                    saveImmediately = true,
                    direction = -1,
                    openAtEnd = true
                )
            } else {
                Toast.makeText(this, "已经到开头", Toast.LENGTH_SHORT).show()
            }
            return
        }
        Toast.makeText(this, "已经到开头", Toast.LENGTH_SHORT).show()
    }

    private fun scrollContinuousPage(direction: Int) {
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        val distance = (readerScrollView.height * 0.78f).toInt().coerceAtLeast(1)
        val target = (readerScrollView.scrollY + direction * distance).coerceIn(0, maxScroll)
        if (target != readerScrollView.scrollY) {
            readerScrollView.smoothScrollTo(0, target)
            return
        }

        if (txtStreamingMode && direction > 0 && txtCurrentPageEndByte < txtTotalBytes) {
            showStreamingTxtPage(txtCurrentPageEndByte, direction = 1)
        } else if (txtStreamingMode && direction < 0 && txtCurrentPageStartByte > 0L) {
            showStreamingTxtPage(
                (txtCurrentPageStartByte - TXT_STREAM_WINDOW_BYTES).coerceAtLeast(0L),
                direction = -1
            )
        } else if (isStructuredChapterDocument() && !structuredWholeBookMode && direction > 0) {
            val lastBuffered = structuredReadingBuffer?.lastChapterIndex ?: structuredChapterIndex
            if (lastBuffered < epubChapters.lastIndex) {
                loadStructuredChapter(lastBuffered + 1, offset = 0, saveImmediately = true, direction = 1)
            } else {
                Toast.makeText(this, "已经到末尾", Toast.LENGTH_SHORT).show()
            }
        } else if (isStructuredChapterDocument() && !structuredWholeBookMode && direction < 0) {
            val firstBuffered = structuredReadingBuffer?.firstChapterIndex ?: structuredChapterIndex
            if (firstBuffered > 0) {
                loadStructuredChapter(
                    firstBuffered - 1,
                    saveImmediately = true,
                    direction = -1,
                    openAtEnd = true
                )
            } else {
                Toast.makeText(this, "已经到开头", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                this,
                if (direction > 0) "已经到末尾" else "已经到开头",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupReaderSettingsPanel() {
        findViewById<TextView>(R.id.fontDecreaseButton).setOnClickListener {
            readerTextSize = (readerTextSize - 2f).coerceAtLeast(14f)
            saveReaderPrefs()
            updateSettingsLabels()
            displayContent()
        }
        findViewById<TextView>(R.id.fontIncreaseButton).setOnClickListener {
            readerTextSize = (readerTextSize + 2f).coerceAtMost(34f)
            saveReaderPrefs()
            updateSettingsLabels()
            displayContent()
        }
        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40))
            saveReaderPrefs()
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            applyReaderPalette(Color.rgb(218, 238, 205), Color.rgb(48, 60, 42))
            saveReaderPrefs()
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            applyReaderPalette(Color.WHITE, Color.rgb(35, 35, 35))
            saveReaderPrefs()
        }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            applyActiveReaderMode(ReaderAppearance.toggleMode(this))
        }
        findViewById<TextView>(R.id.turnModeOverlapButton).setOnClickListener { setTurnMode(TURN_MODE_OVERLAP) }
        findViewById<TextView>(R.id.turnModeSimulateButton).setOnClickListener { setTurnMode(TURN_MODE_SIMULATE) }
        findViewById<TextView>(R.id.turnModeHorizontalButton).setOnClickListener { setTurnMode(TURN_MODE_HORIZONTAL) }
        findViewById<TextView>(R.id.turnModeVerticalButton).setOnClickListener { setTurnMode(TURN_MODE_VERTICAL) }
        findViewById<TextView>(R.id.turnModeFadeButton).setOnClickListener { setTurnMode(TURN_MODE_FADE) }
        findViewById<TextView>(R.id.volumeKeyToggleButton).setOnClickListener {
            volumeKeyTurnEnabled = !volumeKeyTurnEnabled
            saveReaderPrefs()
            updateSettingsLabels()
        }
        updateSettingsLabels()
    }

    private fun toggleReaderSettingsPanel() {
        readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun setTurnMode(mode: String) {
        pageTurnMode = mode
        saveReaderPrefs()
        updateSettingsLabels()
        displayContent()
        val label = if (mode == TURN_MODE_VERTICAL) "连续滚动" else turnModeLabel(mode)
        Toast.makeText(this, "阅读模式：$label", Toast.LENGTH_SHORT).show()
    }

    private fun updateThemeControls() {
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        listOf(R.id.themePaperButton, R.id.themeEyeButton, R.id.themeWhiteButton).forEach { id ->
            findViewById<TextView>(id).apply {
                isEnabled = !night
                alpha = if (night) 0.35f else 1f
            }
        }
        findViewById<TextView>(R.id.themeNightButton).apply {
            text = if (night) "☀" else "☾"
            setTextColor(Color.WHITE)
        }
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
    }

    private fun updateSettingsLabels() {
        updateThemeControls()
        findViewById<TextView>(R.id.fontSizeLabel).text = readerTextSize.toInt().toString()
        findViewById<TextView>(R.id.volumeKeyToggleButton).text =
            if (volumeKeyTurnEnabled) "音量键翻页 开" else "音量键翻页 关"
        val buttons = listOf(
            R.id.turnModeOverlapButton to TURN_MODE_OVERLAP,
            R.id.turnModeSimulateButton to TURN_MODE_SIMULATE,
            R.id.turnModeHorizontalButton to TURN_MODE_HORIZONTAL,
            R.id.turnModeVerticalButton to TURN_MODE_VERTICAL,
            R.id.turnModeFadeButton to TURN_MODE_FADE
        )
        buttons.forEach { (id, mode) ->
            val button = findViewById<TextView>(id)
            button.setBackgroundColor(if (mode == pageTurnMode) Color.rgb(239, 122, 40) else Color.rgb(74, 72, 66))
        }
    }

    private fun seekToProgress(progress: Int) {
        if (txtStreamingMode) {
            val targetByte = ((progress / 1000f) * txtTotalBytes).toLong()
                .coerceIn(0L, txtTotalBytes.coerceAtLeast(0L))
            showStreamingTxtPage(targetByte, saveImmediately = true, keepContextBeforeTarget = true)
            return
        }
        if (isStructuredChapterDocument() && !structuredWholeBookMode) {
            val normalized = (progress / 1000f).coerceIn(0f, 1f)
            val scaled = normalized * epubChapters.size.coerceAtLeast(1)
            val targetIndex = scaled.toInt().coerceIn(0, epubChapters.lastIndex)
            val localFraction = (scaled - targetIndex).coerceIn(0f, 1f)
            loadStructuredChapter(
                chapterIndex = targetIndex,
                saveImmediately = true,
                offsetFraction = localFraction
            )
            return
        }
        if (currentContent.isEmpty()) return
        currentPosition = ((progress / 1000f) * currentContent.length).toInt()
            .coerceIn(0, currentContent.length)
        displayContent()
        markProgressDirty()
        saveProgressNow()
    }

    private fun animatePageTurn(direction: Int) {
        when (pageTurnMode) {
            TURN_MODE_FADE -> {
                contentView.alpha = 0.25f
                contentView.animate().alpha(1f).setDuration(180L).start()
            }
            TURN_MODE_HORIZONTAL, TURN_MODE_SIMULATE -> {
                contentView.translationX = if (direction >= 0) 60f else -60f
                contentView.animate().translationX(0f).setDuration(180L).start()
            }
            TURN_MODE_VERTICAL -> {
                contentView.translationY = if (direction >= 0) 60f else -60f
                contentView.animate().translationY(0f).setDuration(180L).start()
            }
            else -> {
                contentView.alpha = 1f
                contentView.translationX = 0f
                contentView.translationY = 0f
            }
        }
    }

    private fun showStreamingTxtPage(
        byteOffset: Long,
        saveImmediately: Boolean = false,
        direction: Int = 0,
        keepContextBeforeTarget: Boolean = false
    ) {
        val selectedBook = book ?: return
        val targetUri = Uri.parse(selectedBook.filePath)
        val charsetName = txtCharsetName ?: selectedBook.txtCharset ?: Charsets.UTF_8.name()
        txtBufferLoadJob?.cancel()
        txtBufferLoadJob = lifecycleScope.launch {
            try {
                val targetByte = byteOffset.coerceIn(0L, txtTotalBytes.coerceAtLeast(0L))
                val windowStart = if (keepContextBeforeTarget || pageTurnMode == TURN_MODE_VERTICAL) {
                    streamingWindowStartForTarget(targetByte)
                } else {
                    targetByte
                }
                val window = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(targetUri)?.let { stream ->
                        TxtParser.readWindow(
                            inputStream = stream,
                            charsetName = charsetName,
                            startByte = windowStart,
                            maxBytes = TXT_STREAM_WINDOW_BYTES
                        )
                    }
                } ?: return@launch showError("无法读取当前位置")
                txtContinuousBuffer.reset(window)
                currentContent = txtContinuousBuffer.content
                txtCurrentPageStartByte = txtContinuousBuffer.startByte
                txtCurrentPageEndByte = txtContinuousBuffer.endByte
                txtReachedStart = txtContinuousBuffer.startByte <= 0L
                txtReachedEnd = txtContinuousBuffer.endByte >= txtTotalBytes
                currentPosition = targetByte
                    .coerceIn(txtContinuousBuffer.startByte, txtContinuousBuffer.endByte)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                displayContent()
                if (direction != 0) animatePageTurn(direction)
                markProgressDirty()
                if (saveImmediately) saveProgressNow() else scheduleProgressSave()
                if (pageTurnMode == TURN_MODE_VERTICAL) {
                    readerScrollView.post { maybeExtendTxtContinuousBuffer(readerScrollView.scrollY) }
                }
            } catch (e: Exception) {
                showError("读取失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun scanStreamingTxtChapters(documentFile: DocumentFile) {
        val charsetName = txtCharsetName ?: return
        lifecycleScope.launch {
            val chapters = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(documentFile.uri)?.let { stream ->
                        TxtParser.scanChapters(stream, charsetName)
                    }.orEmpty()
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (chapters.isEmpty()) return@launch
            epubChapters = chapters.map { chapter ->
                EpubChapter(name = chapter.title, text = "")
            }
            epubChapterStartPositions = chapters.map {
                it.byteOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            withContext(Dispatchers.IO) {
                writeTxtChapterCache(
                    totalBytes = txtTotalBytes,
                    lastModified = documentFile.lastModified(),
                    charsetName = charsetName,
                    chapters = chapters.map { TxtChapterIndexLong(it.title, it.byteOffset) }
                )
            }
        }
    }

    private fun txtChapterCacheFile() = cacheDir.resolve("txt_chapters").resolve("$bookId.json")

    private fun readTxtChapterCache(
        totalBytes: Long,
        lastModified: Long,
        charsetName: String
    ): List<TxtChapterIndexLong> {
        return try {
            val file = txtChapterCacheFile()
            if (!file.exists()) return emptyList()
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optLong("totalBytes") != totalBytes) return emptyList()
            if (json.optLong("lastModified") != lastModified) return emptyList()
            if (json.optString("charset") != charsetName) return emptyList()
            val items = json.optJSONArray("chapters") ?: return emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val title = item.optString("title")
                    val offset = item.optLong("offset", -1L)
                    if (title.isNotBlank() && offset >= 0L) {
                        add(TxtChapterIndexLong(title, offset))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeTxtChapterCache(
        totalBytes: Long,
        lastModified: Long,
        charsetName: String,
        chapters: List<TxtChapterIndexLong>
    ) {
        try {
            val file = txtChapterCacheFile()
            file.parentFile?.mkdirs()
            val items = JSONArray()
            chapters.take(MAX_CACHED_TXT_CHAPTERS).forEach { chapter ->
                items.put(
                    JSONObject()
                        .put("title", chapter.title)
                        .put("offset", chapter.start)
                )
            }
            val json = JSONObject()
                .put("bookId", bookId)
                .put("totalBytes", totalBytes)
                .put("lastModified", lastModified)
                .put("charset", charsetName)
                .put("chapters", items)
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Cache failure must never block reading.
        }
    }

    private fun searchStreamingTxt(query: String) {
        val selectedBook = book ?: return
        val targetUri = Uri.parse(selectedBook.filePath)
        val charsetName = txtCharsetName ?: selectedBook.txtCharset ?: Charsets.UTF_8.name()
        lifecycleScope.launch {
            val startOffset = (currentPosition.toLong() + 1L).coerceAtMost(txtTotalBytes)
            val found = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(targetUri)?.let { stream ->
                    TxtParser.findTextOffset(stream, charsetName, query, startOffset)
                } ?: contentResolver.openInputStream(targetUri)?.let { stream ->
                    TxtParser.findTextOffset(stream, charsetName, query, 0L)
                }
            }
            val target = found ?: withContext(Dispatchers.IO) {
                contentResolver.openInputStream(targetUri)?.let { stream ->
                    TxtParser.findTextOffset(stream, charsetName, query, 0L)
                }
            }
            if (target != null) {
                showStreamingTxtPage(target, saveImmediately = true, keepContextBeforeTarget = true)
                setReaderChromeVisible(false)
            } else {
                Toast.makeText(this@ReaderActivity, "没有找到：$query", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scheduleProgressSave() {
        if (!canSaveProgress()) return
        val positionToSave = currentPosition
        saveProgressJob?.cancel()
        saveProgressJob = lifecycleScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            persistProgress(positionToSave)
        }
    }

    private fun saveProgressNow() {
        if (!canSaveProgress()) return
        val positionToSave = currentPosition
        saveProgressJob?.cancel()
        lifecycleScope.launch {
            persistProgress(positionToSave)
        }
    }

    private suspend fun persistProgress(positionToSave: Int) {
        if (!canSaveProgress()) return
        withContext(Dispatchers.IO) {
            val selectedBook = book ?: database.bookDao().getBook(bookId)
            val format = selectedBook?.format?.uppercase().orEmpty()
            val structured = format in setOf("EPUB", "CHM") && isStructuredChapterDocument()
            val progressFraction = if (txtStreamingMode && txtTotalBytes > 0L) {
                positionToSave.toFloat() / txtTotalBytes
            } else if (structured) {
                progressForCurrentPosition() / 1000f
            } else if (currentContent.isNotEmpty()) {
                positionToSave.toFloat() / currentContent.length
            } else {
                0f
            }
            val epubLocation = when {
                structured -> {
                    val location = structuredLocationFor(positionToSave)
                    EpubLocation(
                        index = location.chapterIndex,
                        href = epubChapters.getOrNull(location.chapterIndex)?.name.orEmpty(),
                        offset = location.offset
                    )
                }
                format == "EPUB" -> epubLocationFor(positionToSave)
                else -> null
            }
            database.readProgressDao().insert(
                ReadProgress(
                    bookId = bookId,
                    position = positionToSave.toString(),
                    locatorType = format.ifBlank { "TXT" },
                    txtCharOffset = if (format == "TXT" && !txtStreamingMode) positionToSave else null,
                    txtTotalLength = if (format == "TXT") {
                        if (txtStreamingMode) txtTotalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else currentContent.length
                    } else null,
                    epubSpineIndex = epubLocation?.index,
                    epubChapterHref = epubLocation?.href,
                    epubChapterOffset = epubLocation?.offset,
                    epubProgressFraction = if (format in setOf("EPUB", "CHM")) progressFraction else null
                )
            )
            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
        }
        lastSavedPosition = positionToSave
        progressDirty = false
    }

    private fun canSaveProgress(): Boolean {
        return openSucceeded &&
            progressLoaded &&
            contentLoaded &&
            progressDirty &&
            currentContent.isNotEmpty()
    }

    private fun markProgressDirty() {
        if (openSucceeded && contentLoaded && lastSavedPosition != currentPosition) {
            progressDirty = true
        }
    }

    private fun showError(message: String) {
        contentView.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun stableStatusFor(book: Book?, fileUri: Uri): String {
        val persistedReadUris = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val hasPersistedReadPermission = when {
            book?.sourceTreeUri != null -> persistedReadUris.contains(book.sourceTreeUri)
            else -> persistedReadUris.contains(fileUri.toString())
        }
        return if (hasPersistedReadPermission) {
            "AVAILABLE"
        } else {
            "SESSION_ONLY"
        }
    }

    private fun chapterStartPositions(chapters: List<EpubChapter>): List<Int> {
        var position = 0
        return chapters.mapIndexed { index, chapter ->
            val start = position
            position += chapter.content.length
            if (index < chapters.lastIndex) {
                position += EPUB_CHAPTER_SEPARATOR.length
            }
            start
        }
    }

    private fun chapterIndexPositions(chapters: List<EpubChapter>): List<Int> {
        return chapters.indices.map { index -> index * STRUCTURED_CHAPTER_POSITION_STRIDE }
    }

    private fun detectTxtChapters(content: String): List<TxtChapterIndex> {
        if (content.isBlank()) return emptyList()
        val chapters = mutableListOf<TxtChapterIndex>()
        Regex("(?m)^.*$").findAll(content).forEach { match ->
            val line = match.value.trim().trimEnd('\r')
            val title = TxtParser.extractChapterTitle(line)
            if (title != null) {
                chapters += TxtChapterIndex(title, match.range.first)
            }
        }
        val distinct = chapters.distinctBy { it.start }
        return distinct
            .filterIndexed { index, chapter -> index == 0 || chapter.start - distinct[index - 1].start > 80 }
            .take(5000)
    }

    private fun isLikelyChapterTitle(line: String): Boolean {
        if (line.length !in 2..80) return false
        if (line.contains("http", ignoreCase = true)) return false
        if (line.count { it == '，' || it == ',' || it == '。' || it == '！' || it == '？' } > 2) return false
        val patterns = listOf(
            Regex("^第\\s*[0-9零〇一二两三四五六七八九十百千万]+\\s*[章节卷回部集篇].{0,45}$"),
            Regex("^[0-9]{1,5}\\s*[、.．]\\s*\\S.{0,45}$"),
            Regex("^(Chapter|CHAPTER)\\s*[0-9IVXLCDM]+\\b.{0,45}$"),
            Regex("^(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|番外篇).{0,45}$")
        )
        return patterns.any { it.matches(line) }
    }

    private fun restoredEpubPosition(progress: ReadProgress?): Int? {
        if (progress == null) return null
        val spineIndex = progress.epubSpineIndex
        val chapterOffset = progress.epubChapterOffset
        if (
            spineIndex != null &&
            chapterOffset != null &&
            spineIndex in epubChapterStartPositions.indices
        ) {
            return epubChapterStartPositions[spineIndex] + chapterOffset
        }
        return progress.position.toIntOrNull()
    }

    private fun epubLocationFor(position: Int): EpubLocation? {
        if (epubChapters.isEmpty() || epubChapterStartPositions.isEmpty()) return null
        val index = epubChapterStartPositions.indexOfLast { start -> start <= position }
            .takeIf { it >= 0 }
            ?: 0
        val start = epubChapterStartPositions[index]
        return EpubLocation(
            index = index,
            href = epubChapters[index].name,
            offset = (position - start).coerceAtLeast(0)
        )
    }

    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val width = readerScrollView.width.takeIf { it > 0 } ?: return false
        val height = readerScrollView.height.takeIf { it > 0 } ?: return false
        val centerLeft = width / 4f
        val centerRight = width * 3f / 4f
        val centerTop = height / 4f
        val centerBottom = height * 3f / 4f
        val inCenter = e.x in centerLeft..centerRight && e.y in centerTop..centerBottom
        if (inCenter) {
            toggleReaderChrome()
            return true
        }
        if (pageTurnMode == TURN_MODE_VERTICAL) return false
        return when {
            e.x < centerLeft -> {
                previousPage()
                true
            }
            e.x > centerRight -> {
                nextPage()
                true
            }
            else -> false
        }
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean = false

    override fun onLongPress(e: MotionEvent) {}

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        if (pageTurnMode == TURN_MODE_VERTICAL) return false
        val start = e1 ?: return false
        val delta = e2.x - start.x
        val threshold = 100
        if (delta > threshold) {
            previousPage()
        } else if (delta < -threshold) {
            nextPage()
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (volumeKeyTurnEnabled) {
                    previousPage()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyTurnEnabled) {
                    nextPage()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        readerSearchSession = null
        pendingReaderSearchHighlight = null
        activeReaderSearchHighlight = false
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        val positionToSave = currentPosition
        saveProgressJob?.cancel()
        if (canSaveProgress()) {
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    persistProgress(positionToSave)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 500L
        private const val EPUB_CHAPTER_SEPARATOR = "\n\n"
        private const val TXT_STREAM_WINDOW_BYTES = 192 * 1024
        private const val TXT_PREFETCH_FORWARD_FRACTION = 0.62f
        private const val TXT_PREFETCH_BACKWARD_FRACTION = 0.12f
        private const val STRUCTURED_PREFETCH_FORWARD_FRACTION = 0.86f
        private const val STRUCTURED_PREFETCH_BACKWARD_FRACTION = 0.14f
        private const val STRUCTURED_CHAPTER_POSITION_STRIDE = 1_000_000
        private const val MAX_CACHED_TXT_CHAPTERS = 5000
        private const val SEARCH_RESULT_PAGE_SIZE = 40
        private const val MAX_SEARCH_RESULTS = 5000
        private const val RECOVER_SCAN_LIMIT = 10000
        private const val READER_PREFS = "reader_prefs"
        private const val PREF_TEXT_SIZE = "text_size"
        private const val PREF_BACKGROUND = "background"
        private const val PREF_TEXT_COLOR = "text_color"
        private const val PREF_TURN_MODE = "turn_mode"
        private const val PREF_VOLUME_KEY = "volume_key"
        private const val TURN_MODE_OVERLAP = "overlap"
        private const val TURN_MODE_SIMULATE = "simulate"
        private const val TURN_MODE_HORIZONTAL = "horizontal"
        private const val TURN_MODE_VERTICAL = "vertical"
        private const val TURN_MODE_FADE = "fade"
        private const val MENU_ADD_BOOKMARK = 1
        private const val MENU_BOOKMARKS = 2
        private const val MENU_TOC = 3
        private const val MENU_PANEL = 4
        private const val MENU_SEARCH = 5
    }

    private data class ReaderSearchSession(
        val query: String,
        val hits: MutableList<ReaderSearchHit> = mutableListOf(),
        var nextPosition: Long = 0L,
        var endReached: Boolean = false,
        var loading: Boolean = false,
        var listFirstVisible: Int = 0,
        var listTopOffset: Int = 0
    )

    private data class ReaderSearchHighlight(
        val query: String,
        val stableKey: String,
        val position: Long
    )

    private data class LoadedContent(
        val text: String,
        val epubChapters: List<EpubChapter> = emptyList(),
        val epubChapterStartPositions: List<Int> = emptyList(),
        val structuredCatalogEntries: List<StructuredCatalogEntry> = emptyList(),
        val isStreamingTxt: Boolean = false,
        val txtCharsetName: String? = null,
        val txtTotalBytes: Long = 0L,
        val txtStartByte: Long = 0L,
        val txtNextByte: Long = 0L,
        val txtTargetByte: Long = 0L,
        val structuredChapterIndex: Int = 0,
        val structuredInitialPosition: Int = 0,
        val structuredWholeBookMode: Boolean = false,
        val structuredReadingBuffer: StructuredReadingBuffer? = null,
        val chmCachePath: String? = null
    )

    private data class StructuredCatalogEntry(
        val title: String,
        val depth: Int,
        val targetChapterIndex: Int,
        val isSection: Boolean
    )

    private data class TxtChapterIndex(
        val title: String,
        val start: Int
    )

    private data class TxtChapterIndexLong(
        val title: String,
        val start: Long
    )

    private data class EpubLocation(
        val index: Int,
        val href: String,
        val offset: Int
    )
}
