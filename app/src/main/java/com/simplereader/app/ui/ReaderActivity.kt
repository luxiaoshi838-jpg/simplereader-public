package com.simplereader.app.ui

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
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
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Bookmark
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.ReadProgress
import com.simplereader.app.parser.EpubChapter
import com.simplereader.app.parser.EpubParser
import com.simplereader.app.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ReaderActivity : AppCompatActivity(), GestureDetector.OnGestureListener {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var contentView: TextView
    private lateinit var readerScrollView: NestedScrollView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var readerProgressBar: SeekBar
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
    private var epubChapters: List<EpubChapter> = emptyList()
    private var epubChapterStartPositions: List<Int> = emptyList()
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

        database = SimpleReaderDatabase.getDatabase(this)
        contentView = findViewById(R.id.contentView)
        readerScrollView = findViewById(R.id.readerScrollView)
        fontSizeSeekBar = findViewById(R.id.fontSizeSeekBar)
        readerProgressBar = findViewById(R.id.readerProgressBar)
        readerProgressLabel = findViewById(R.id.readerProgressLabel)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        loadReaderPrefs()
        applyReaderPalette(currentBackgroundColor, currentTextColor)
        gestureDetector = GestureDetector(this, this)
        readerScrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            pageTurnMode != TURN_MODE_VERTICAL
        }
        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateVerticalScrollProgress(scrollY)
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
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, MENU_PANEL, Menu.NONE, "目录/书签")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_ADD_BOOKMARK, Menu.NONE, "添加书签")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_BOOKMARKS, Menu.NONE, "书签列表")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_TOC, Menu.NONE, "目录")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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

                val documentFile = withContext(Dispatchers.IO) {
                    resolveReadableDocument(selectedBook)
                }
                if (documentFile == null) {
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
            name.endsWith(".epub", ignoreCase = true) ||
            name.endsWith(".chm", ignoreCase = true)
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

    private fun loadBookContent(documentFile: DocumentFile, format: String) {
        currentReadableDocument = documentFile
        lifecycleScope.launch {
            try {
                val loadedContent = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(documentFile.uri)?.use { input ->
                        when (format.uppercase()) {
                            "TXT" -> {
                                val fileSize = documentFile.length().takeIf { it > 0L } ?: book?.fileSize ?: 0L
                                input.close()
                                val charsetName = contentResolver.openInputStream(documentFile.uri)?.let { stream ->
                                    TxtParser.detectCharset(stream, book?.txtCharset)
                                } ?: book?.txtCharset ?: Charsets.UTF_8.name()
                                database.bookDao().updateTxtCharset(bookId, charsetName)
                                val savedProgress = database.readProgressDao().getProgress(bookId)
                                val savedOffset = savedProgress?.position?.toLongOrNull()
                                    ?: savedProgress?.txtCharOffset?.toLong()
                                    ?: 0L
                                val window = contentResolver.openInputStream(documentFile.uri)?.let { stream ->
                                    TxtParser.readWindow(
                                        inputStream = stream,
                                        charsetName = charsetName,
                                        startByte = savedOffset.coerceIn(0L, fileSize.coerceAtLeast(0L)),
                                        maxBytes = TXT_STREAM_WINDOW_BYTES
                                    )
                                } ?: error("Cannot open TXT stream")
                                val cachedChapters = readTxtChapterCache(fileSize, documentFile.lastModified(), charsetName)
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
                                    txtNextByte = window.nextByte
                                )
                            }
                            "EPUB" -> {
                                val chapters = EpubParser.readChapters(input)
                                LoadedContent(
                                    text = chapters.joinToString(EPUB_CHAPTER_SEPARATOR) { chapter -> chapter.text },
                                    epubChapters = chapters,
                                    epubChapterStartPositions = chapterStartPositions(chapters)
                                )
                            }
                            "CHM" -> LoadedContent(
                                text = readChmTextIsolated(input)
                            )
                            else -> LoadedContent("")
                        }
                    } ?: LoadedContent("")
                }
                currentContent = loadedContent.text
                txtStreamingMode = loadedContent.isStreamingTxt
                txtCharsetName = loadedContent.txtCharsetName
                txtTotalBytes = loadedContent.txtTotalBytes
                txtCurrentPageStartByte = loadedContent.txtStartByte
                txtCurrentPageEndByte = loadedContent.txtNextByte
                epubChapters = loadedContent.epubChapters
                epubChapterStartPositions = loadedContent.epubChapterStartPositions

                val progress = withContext(Dispatchers.IO) {
                    database.readProgressDao().getProgress(bookId)
                }
                progressLoaded = true
                currentPosition = if (txtStreamingMode) {
                    loadedContent.txtStartByte.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else when (format.uppercase()) {
                    "TXT" -> progress?.txtCharOffset ?: progress?.position?.toIntOrNull()
                    "EPUB" -> restoredEpubPosition(progress)
                    else -> progress?.position?.toIntOrNull()
                }?.coerceIn(0, currentContent.length) ?: 0

                if (currentContent.isBlank()) {
                    showError("没有读取到可显示内容")
                } else {
                    contentLoaded = true
                    openSucceeded = true
                    progressDirty = false
                    lastSavedPosition = currentPosition
                    withContext(Dispatchers.IO) {
                        database.bookDao().updateFileStatus(bookId, stableStatusFor(book, documentFile.uri))
                    }
                    displayContent()
                }
            } catch (e: Throwable) {
                showError("打开书籍失败：${e.message ?: e.javaClass.simpleName}")
            }
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
            applyReaderPalette(ReaderAppearance.NIGHT_BACKGROUND, ReaderAppearance.NIGHT_TEXT)
        }
        findViewById<TextView>(R.id.moreReaderButton).setOnClickListener {
            showReaderMoreActions()
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
        readerProgressBar.setOnSeekBarChangeListener(progressListener)
    }

    private fun displayContent() {
        val continuous = pageTurnMode == TURN_MODE_VERTICAL
        if (txtStreamingMode) {
            contentView.text = currentContent
            contentView.textSize = readerTextSize
            configureVerticalScrollIfNeeded()
            if (continuous) {
                readerScrollView.post {
                    val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                    val windowBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
                    val fraction = ((currentPosition.toLong() - txtCurrentPageStartByte).toDouble() / windowBytes)
                        .coerceIn(0.0, 1.0)
                    readerScrollView.scrollTo(0, (maxScroll * fraction).toInt().coerceIn(0, maxScroll))
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
            return
        }

        if (continuous) {
            contentView.text = currentContent
            contentView.post {
                val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                if (maxScroll > 0 && currentContent.isNotEmpty()) {
                    val fraction = (currentPosition.toFloat() / currentContent.length).coerceIn(0f, 1f)
                    readerScrollView.scrollTo(0, (maxScroll * fraction).toInt().coerceIn(0, maxScroll))
                }
            }
        } else {
            val endPosition = (currentPosition + pageSize).coerceAtMost(currentContent.length)
            val safeStart = currentPosition.coerceIn(0, endPosition)
            contentView.text = currentContent.substring(safeStart, endPosition)
            readerScrollView.scrollTo(0, 0)
        }
        contentView.textSize = readerTextSize
        configureVerticalScrollIfNeeded()
        if (currentContent.isNotEmpty()) {
            updateProgressViews(
                ((currentPosition.toFloat() / currentContent.length) * 1000).toInt().coerceIn(0, 1000)
            )
        }
    }

    private fun updateProgressViews(progress: Int) {
        fontSizeSeekBar.progress = progress
        readerProgressBar.progress = progress
        readerProgressLabel.text = "${(progress / 10f).toInt()}%"
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
        if (pageTurnMode != TURN_MODE_VERTICAL || !openSucceeded || currentContent.isBlank()) return
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        if (maxScroll <= 0) return
        val scrollProgress = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        currentPosition = if (txtStreamingMode) {
            val windowStart = txtCurrentPageStartByte
            val windowBytes = (txtCurrentPageEndByte - windowStart).coerceAtLeast(1L)
            (windowStart + (windowBytes * scrollProgress).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            (currentContent.length * scrollProgress).toInt().coerceIn(0, currentContent.length)
        }
        updateProgressViews(progressForCurrentPosition())
        markProgressDirty()
        scheduleProgressSave()
    }

    private fun progressForCurrentPosition(): Int {
        return if (txtStreamingMode && txtTotalBytes > 0L) {
            ((currentPosition.toFloat() / txtTotalBytes) * 1000).toInt().coerceIn(0, 1000)
        } else if (currentContent.isNotEmpty()) {
            ((currentPosition.toFloat() / currentContent.length) * 1000).toInt().coerceIn(0, 1000)
        } else {
            0
        }
    }

    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
        currentBackgroundColor = backgroundColor
        currentTextColor = textColor
        contentView.setBackgroundColor(backgroundColor)
        contentView.setTextColor(textColor)
        window.decorView.setBackgroundColor(backgroundColor)
        ReaderAppearance.savePalette(this, backgroundColor, textColor)
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
            .putInt(PREF_BACKGROUND, currentBackgroundColor)
            .putInt(PREF_TEXT_COLOR, currentTextColor)
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

    private fun toggleReaderControls() {
        readerControls.visibility = if (readerControls.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun showContentSearch() {
        if (currentContent.isBlank()) {
            Toast.makeText(this, "当前没有可搜索的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "输入要查找的文字"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("搜索书内内容")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("显示全部结果") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                } else {
                    showAllContentSearchResults(query)
                }
            }
            .show()
    }

    private fun showAllContentSearchResults(query: String) {
        val density = resources.displayMetrics.density
        fun localDp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(localDp(8), localDp(4), localDp(8), localDp(4))
        }
        val statusView = TextView(this).apply {
            text = "Searching..."
            textSize = 14f
            setTextColor(Color.rgb(110, 100, 84))
            setPadding(localDp(12), localDp(8), localDp(12), localDp(8))
        }
        val hits = mutableListOf<ReaderSearchHit>()
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = hits.size
            override fun getItem(position: Int): ReaderSearchHit = hits[position]
            override fun getItemId(position: Int): Long = hits[position].stableKey.hashCode().toLong()

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? TextView) ?: TextView(parent.context).apply {
                    textSize = 15f
                    setTextColor(Color.rgb(38, 35, 31))
                    setPadding(localDp(14), localDp(10), localDp(14), localDp(10))
                    maxLines = 4
                }
                val hit = hits[position]
                row.text = "${position + 1}. ${hit.positionLabel}\n${hit.preview}"
                return row
            }
        }
        val listView = ListView(this).apply {
            adapter = listAdapter
            isVerticalScrollBarEnabled = true
        }

        container.addView(statusView)
        container.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.68f).toInt()
            )
        )

        var dialog: AlertDialog? = null
        var loadJob: Job? = null

        fun refreshRows(ordered: List<ReaderSearchHit>, message: String) {
            hits.clear()
            hits.addAll(ordered)
            listAdapter.notifyDataSetChanged()
            statusView.text = message
        }

        fun openHit(hit: ReaderSearchHit) {
            pageTurnMode = TURN_MODE_VERTICAL
            saveReaderPrefs()
            updateSettingsLabels()
            if (txtStreamingMode) {
                showStreamingTxtPage(hit.position, saveImmediately = true)
            } else {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
                readerControls.visibility = View.GONE
            }
            statusView.text = "Selected ${hits.indexOf(hit) + 1} / ${hits.size}"
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            hits.getOrNull(position)?.let { openHit(it) }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Search results")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { loadJob?.cancel() }
        dialog.show()

        loadJob = lifecycleScope.launch {
            val results = mutableListOf<ReaderSearchHit>()
            try {
                withContext(Dispatchers.IO) {
                    if (txtStreamingMode) {
                        val selectedBook = book
                        val charsetName = txtCharsetName ?: selectedBook?.txtCharset ?: Charsets.UTF_8.name()
                        var nextPosition = 0L
                        var finished = selectedBook == null
                        while (!finished) {
                            val page = contentResolver.openInputStream(Uri.parse(selectedBook!!.filePath))?.use { stream ->
                                val parsed = TxtParser.findTextPage(
                                    inputStream = stream,
                                    charsetName = charsetName,
                                    query = query,
                                    startByte = nextPosition,
                                    pageSize = SEARCH_RESULT_PAGE_SIZE
                                )
                                ReaderSearchPage(
                                    hits = parsed.hits.map { hit ->
                                        val percent = if (txtTotalBytes > 0L) {
                                            ((hit.byteOffset.toDouble() / txtTotalBytes) * 100).toInt().coerceIn(0, 100)
                                        } else {
                                            0
                                        }
                                        ReaderSearchHit(
                                            stableKey = "byte:${hit.byteOffset}",
                                            position = hit.byteOffset,
                                            positionLabel = "about $percent% - byte ${hit.byteOffset}",
                                            preview = hit.preview
                                        )
                                    },
                                    nextPosition = parsed.nextByte,
                                    endReached = parsed.endReached
                                )
                            }
                            if (page == null) {
                                finished = true
                            } else {
                                results += page.hits
                                val ordered = results.distinctBy { it.stableKey }
                                    .sortedBy { it.position }
                                    .take(MAX_SEARCH_RESULTS)
                                withContext(Dispatchers.Main) {
                                    refreshRows(ordered, "Searching... ${ordered.size} results")
                                }
                                finished = page.endReached ||
                                    page.nextPosition <= nextPosition ||
                                    ordered.size >= MAX_SEARCH_RESULTS
                                nextPosition = page.nextPosition
                            }
                        }
                    } else {
                        results += findInMemorySearchAll(query)
                    }
                }

                val ordered = results.distinctBy { it.stableKey }
                    .sortedBy { it.position }
                    .take(MAX_SEARCH_RESULTS)
                val message = when {
                    ordered.isEmpty() -> "No results for \"$query\""
                    ordered.size >= MAX_SEARCH_RESULTS -> "Showing first $MAX_SEARCH_RESULTS results. Use a longer keyword."
                    else -> "${ordered.size} results"
                }
                refreshRows(ordered, message)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = "Search failed: ${error.message ?: error.javaClass.simpleName}"
                statusView.text = message
                Toast.makeText(this@ReaderActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findInMemorySearchAll(query: String): List<ReaderSearchHit> {
        if (query.isBlank() || currentContent.isEmpty()) return emptyList()
        val hits = mutableListOf<ReaderSearchHit>()
        var cursor = 0
        while (cursor < currentContent.length && hits.size < MAX_SEARCH_RESULTS) {
            val index = currentContent.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (index < 0) break
            val previewStart = (index - 45).coerceAtLeast(0)
            val previewEnd = (index + query.length + 90).coerceAtMost(currentContent.length)
            val preview = currentContent.substring(previewStart, previewEnd)
                .replace(Regex("\\s+"), " ")
                .trim()
            val percent = ((index.toDouble() / currentContent.length) * 100).toInt().coerceIn(0, 100)
            hits += ReaderSearchHit(
                stableKey = "char:$index",
                position = index.toLong(),
                positionLabel = "about $percent% - position $index",
                preview = preview.ifBlank { "position $index" }
            )
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(currentContent.length)
        }
        return hits
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
                showStreamingTxtPage(hit.position, saveImmediately = true)
            } else {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
                readerControls.visibility = View.GONE
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
        val currentIndex = epubChapterStartPositions.indexOfLast { it <= currentPosition }
            .coerceAtLeast(0)
        val targetIndex = (currentIndex + direction).coerceIn(0, epubChapterStartPositions.lastIndex)
        if (targetIndex == currentIndex) {
            Toast.makeText(this, if (direction < 0) "已经是第一章" else "已经是最后一章", Toast.LENGTH_SHORT).show()
            return
        }
        currentPosition = epubChapterStartPositions[targetIndex]
        if (txtStreamingMode) {
            showStreamingTxtPage(currentPosition.toLong(), saveImmediately = true, direction = direction)
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
            val bookmarks = withContext(Dispatchers.IO) {
                database.bookmarkDao().getBookmarks(bookId).first()
            }
            var showingCatalog = !showBookmarksFirst
            var downX = 0f
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

            fun twoLineAdapter(labels: List<CharSequence>, highlightedIndex: Int = -1): ArrayAdapter<CharSequence> {
                return object : ArrayAdapter<CharSequence>(
                    this@ReaderActivity,
                    android.R.layout.simple_list_item_1,
                    labels
                ) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as? TextView)?.apply {
                            maxLines = 2
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

            fun currentChapterIndex(): Int {
                if (epubChapterStartPositions.isEmpty()) return -1
                val current = currentPosition.coerceAtLeast(0)
                return epubChapterStartPositions.indexOfLast { it <= current }
                    .coerceAtLeast(0)
            }

            fun render() {
                catalogButton.isEnabled = !showingCatalog
                bookmarkButton.isEnabled = showingCatalog
                if (showingCatalog) {
                    val currentChapter = currentChapterIndex()
                    val labels = if (epubChapters.isEmpty()) {
                        requestTxtCatalogScan { render() }
                        if (txtStreamingMode) listOf("正在识别目录...") else listOf("暂无目录")
                    } else {
                        epubChapters.mapIndexed { index, chapter ->
                            val title = chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
                            val position = epubChapterStartPositions.getOrElse(index) { 0 }
                            catalogLabel(index, title, position, index == currentChapter)
                        }
                    }
                    listView.adapter = twoLineAdapter(labels, currentChapter)
                    if (currentChapter >= 0) {
                        listView.post { listView.setSelection(currentChapter) }
                    }
                    listView.setOnItemClickListener { _, _, which, _ ->
                        if (epubChapters.isNotEmpty()) {
                            currentPosition = epubChapterStartPositions.getOrElse(which) { 0 }
                            if (txtStreamingMode) {
                                showStreamingTxtPage(currentPosition.toLong(), saveImmediately = true)
                            } else {
                                displayContent()
                                markProgressDirty()
                                saveProgressNow()
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
                    listView.adapter = twoLineAdapter(labels)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let {
                            jumpToBookmark(it)
                            dialog?.dismiss()
                        }
                    }
                    listView.setOnItemLongClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let { confirmDeleteBookmark(it) }
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
            listView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> downX = event.x
                    MotionEvent.ACTION_UP -> {
                        val delta = event.x - downX
                        if (delta > 120) {
                            showingCatalog = true
                            render()
                        } else if (delta < -120) {
                            showingCatalog = false
                            render()
                        }
                    }
                }
                false
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
        val items = arrayOf("字号减小", "字号增大", "浅色背景", "护眼背景", "黑色夜间", "白色背景")
        AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        readerTextSize = (readerTextSize - 2f).coerceAtLeast(14f)
                        displayContent()
                    }
                    1 -> {
                        readerTextSize = (readerTextSize + 2f).coerceAtMost(34f)
                        displayContent()
                    }
                    2 -> applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40))
                    3 -> applyReaderPalette(Color.rgb(218, 238, 205), Color.rgb(48, 60, 42))
                    4 -> applyReaderPalette(ReaderAppearance.NIGHT_BACKGROUND, ReaderAppearance.NIGHT_TEXT)
                    5 -> applyReaderPalette(Color.WHITE, Color.rgb(35, 35, 35))
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmDeleteBookmark(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setMessage(bookmark.content.ifBlank { "位置 ${bookmark.position}" })
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        database.bookmarkDao().delete(bookmark)
                    }
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
            val title = chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
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
            showStreamingTxtPage(target, saveImmediately = true)
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
        } else {
            Toast.makeText(this, "已经到末尾", Toast.LENGTH_SHORT).show()
        }
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
        } else {
            Toast.makeText(this, "已经到开头", Toast.LENGTH_SHORT).show()
        }
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
            applyReaderPalette(ReaderAppearance.NIGHT_BACKGROUND, ReaderAppearance.NIGHT_TEXT)
            saveReaderPrefs()
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

    private fun updateSettingsLabels() {
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
            showStreamingTxtPage(targetByte, saveImmediately = true)
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

    private fun showStreamingTxtPage(byteOffset: Long, saveImmediately: Boolean = false, direction: Int = 0) {
        val selectedBook = book ?: return
        val targetUri = Uri.parse(selectedBook.filePath)
        val charsetName = txtCharsetName ?: selectedBook.txtCharset ?: Charsets.UTF_8.name()
        lifecycleScope.launch {
            try {
                val window = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(targetUri)?.let { stream ->
                        TxtParser.readWindow(
                            inputStream = stream,
                            charsetName = charsetName,
                            startByte = byteOffset.coerceIn(0L, txtTotalBytes.coerceAtLeast(0L)),
                            maxBytes = TXT_STREAM_WINDOW_BYTES
                        )
                    }
                } ?: return@launch showError("无法读取当前位置")
                currentContent = window.text
                txtCurrentPageStartByte = window.startByte
                txtCurrentPageEndByte = window.nextByte
                currentPosition = byteOffset
                    .coerceIn(window.startByte, window.nextByte)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                displayContent()
                if (direction != 0) {
                    animatePageTurn(direction)
                }
                markProgressDirty()
                if (saveImmediately) {
                    saveProgressNow()
                } else {
                    scheduleProgressSave()
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
                showStreamingTxtPage(target, saveImmediately = true)
                readerControls.visibility = View.GONE
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
            val progressFraction = if (txtStreamingMode && txtTotalBytes > 0L) {
                positionToSave.toFloat() / txtTotalBytes
            } else if (currentContent.isNotEmpty()) {
                positionToSave.toFloat() / currentContent.length
            } else {
                0f
            }
            val epubLocation = if (format == "EPUB") epubLocationFor(positionToSave) else null
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
                    epubProgressFraction = if (format == "EPUB") progressFraction else null
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
            position += chapter.text.length
            if (index < chapters.lastIndex) {
                position += EPUB_CHAPTER_SEPARATOR.length
            }
            start
        }
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
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            toggleReaderControls()
            return true
        }
        val width = readerScrollView.width.takeIf { it > 0 } ?: return false
        val leftBoundary = width / 3
        val rightBoundary = width * 2 / 3
        if (e.x < leftBoundary) {
            previousPage()
        } else if (e.x > rightBoundary) {
            nextPage()
        } else {
            toggleReaderControls()
        }
        return true
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

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 500L
        private const val EPUB_CHAPTER_SEPARATOR = "\n\n"
        private const val TXT_STREAM_WINDOW_BYTES = 64 * 1024
        private const val MAX_CACHED_TXT_CHAPTERS = 5000
        private const val SEARCH_RESULT_PAGE_SIZE = 200
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

    private data class LoadedContent(
        val text: String,
        val epubChapters: List<EpubChapter> = emptyList(),
        val epubChapterStartPositions: List<Int> = emptyList(),
        val isStreamingTxt: Boolean = false,
        val txtCharsetName: String? = null,
        val txtTotalBytes: Long = 0L,
        val txtStartByte: Long = 0L,
        val txtNextByte: Long = 0L
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
