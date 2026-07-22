package com.simplereader.app.ui

import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.text.method.ScrollingMovementMethod
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.rgb(72, 67, 58)))

        database = SimpleReaderDatabase.getDatabase(this)
        contentView = findViewById(R.id.contentView)
        fontSizeSeekBar = findViewById(R.id.fontSizeSeekBar)
        readerProgressBar = findViewById(R.id.readerProgressBar)
        readerProgressLabel = findViewById(R.id.readerProgressLabel)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        loadReaderPrefs()
        applyReaderPalette(currentBackgroundColor, currentTextColor)
        gestureDetector = GestureDetector(this, this)
        contentView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            pageTurnMode != TURN_MODE_VERTICAL || readerControls.visibility == View.VISIBLE
        }
        contentView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateVerticalScrollProgress(scrollY)
        }

        bookId = intent.getLongExtra("bookId", 0L)
        setupUI()
        loadBook()
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
            book = withContext(Dispatchers.IO) {
                database.bookDao().getBook(bookId)
            }
            val selectedBook = book ?: return@launch showError("书籍记录不存在")
            title = selectedBook.title
            supportActionBar?.title = selectedBook.title
            val documentFile = DocumentFile.fromSingleUri(this@ReaderActivity, Uri.parse(selectedBook.filePath))
            if (documentFile == null || !documentFile.exists()) {
                withContext(Dispatchers.IO) {
                    database.bookDao().updateFileStatus(bookId, "MISSING")
                }
                showError("文件失效或权限已丢失")
                return@launch
            }
            loadBookContent(documentFile, selectedBook.format)
        }
    }

    private fun loadBookContent(documentFile: DocumentFile, format: String) {
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
                    if (txtStreamingMode && epubChapters.isEmpty()) {
                        scanStreamingTxtChapters(documentFile)
                    }
                }
            } catch (e: Exception) {
                showError("打开书籍失败：${e.message ?: "未知错误"}")
            }
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
            applyReaderPalette(Color.BLACK, Color.WHITE)
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
        if (txtStreamingMode) {
            contentView.text = currentContent
            contentView.textSize = readerTextSize
            configureVerticalScrollIfNeeded()
            val progress = if (txtTotalBytes > 0L) {
                ((currentPosition.toFloat() / txtTotalBytes) * 1000).toInt()
            } else {
                0
            }
            updateProgressViews(progress.coerceIn(0, 1000))
            return
        }
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            contentView.text = currentContent
            contentView.scrollTo(0, 0)
            contentView.post {
                val maxScroll = (contentView.layout?.height ?: 0) - contentView.height
                if (maxScroll > 0 && currentContent.isNotEmpty()) {
                    val progress = currentPosition.toFloat() / currentContent.length
                    contentView.scrollTo(0, (maxScroll * progress).toInt().coerceIn(0, maxScroll))
                }
            }
        } else {
            val endPosition = (currentPosition + pageSize).coerceAtMost(currentContent.length)
            contentView.text = currentContent.substring(currentPosition, endPosition)
            contentView.scrollTo(0, 0)
        }
        contentView.textSize = readerTextSize
        configureVerticalScrollIfNeeded()
        if (currentContent.isNotEmpty()) {
            updateProgressViews(((currentPosition.toFloat() / currentContent.length) * 1000).toInt()
                .coerceIn(0, 1000))
        }
    }

    private fun updateProgressViews(progress: Int) {
        fontSizeSeekBar.progress = progress
        readerProgressBar.progress = progress
        readerProgressLabel.text = "${(progress / 10f).toInt()}%"
    }

    private fun configureVerticalScrollIfNeeded() {
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            contentView.movementMethod = ScrollingMovementMethod.getInstance()
            contentView.isVerticalScrollBarEnabled = true
        } else {
            contentView.movementMethod = null
            contentView.isVerticalScrollBarEnabled = false
        }
    }

    private fun updateVerticalScrollProgress(scrollY: Int) {
        if (pageTurnMode != TURN_MODE_VERTICAL || !openSucceeded || currentContent.isBlank()) return
        val maxScroll = ((contentView.layout?.height ?: 0) - contentView.height).coerceAtLeast(0)
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
    }

    private fun loadReaderPrefs() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSize = prefs.getFloat(PREF_TEXT_SIZE, 18f)
        currentBackgroundColor = prefs.getInt(PREF_BACKGROUND, Color.rgb(245, 233, 200))
        currentTextColor = prefs.getInt(PREF_TEXT_COLOR, Color.rgb(59, 52, 40))
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
            hint = "搜索书内文字"
        }
        AlertDialog.Builder(this)
            .setTitle("搜索书内内容")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("定位") { _, _ ->
                val query = input.text.toString()
                if (query.isBlank()) return@setPositiveButton
                if (txtStreamingMode) {
                    searchStreamingTxt(query)
                    return@setPositiveButton
                }
                val start = (currentPosition + 1).coerceAtMost(currentContent.length)
                val forwardIndex = currentContent.indexOf(query, startIndex = start, ignoreCase = true)
                val wrappedIndex = if (forwardIndex >= 0) {
                    forwardIndex
                } else {
                    currentContent.indexOf(query, startIndex = 0, ignoreCase = true)
                }
                if (wrappedIndex >= 0) {
                    currentPosition = wrappedIndex
                    displayContent()
                    markProgressDirty()
                    scheduleProgressSave()
                    readerControls.visibility = View.GONE
                } else {
                    Toast.makeText(this, "没有找到：$query", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
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

            fun render() {
                catalogButton.isEnabled = !showingCatalog
                bookmarkButton.isEnabled = showingCatalog
                if (showingCatalog) {
                    val labels = if (epubChapters.isEmpty()) {
                        listOf("暂无目录")
                    } else {
                        epubChapters.mapIndexed { index, chapter ->
                            val title = chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
                            "${index + 1}. $title"
                        }
                    }
                    listView.adapter = ArrayAdapter(this@ReaderActivity, android.R.layout.simple_list_item_1, labels)
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
                        }
                    }
                    listView.setOnItemLongClickListener(null)
                } else {
                    val labels = if (bookmarks.isEmpty()) {
                        listOf("暂无书签")
                    } else {
                        bookmarks.mapIndexed { index, bookmark ->
                            "${index + 1}. ${bookmark.content.ifBlank { "位置 ${bookmark.position}" }}"
                        }
                    }
                    listView.adapter = ArrayAdapter(this@ReaderActivity, android.R.layout.simple_list_item_1, labels)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let { jumpToBookmark(it) }
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
            val dialog = AlertDialog.Builder(this@ReaderActivity)
                .setTitle(if (showingCatalog) "目录" else "书签")
                .setView(container)
                .setNegativeButton("关闭", null)
                .show()
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.rgb(250, 246, 232)))
                window.setGravity(android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
                window.setLayout(
                    (resources.displayMetrics.widthPixels * 0.9f).toInt(),
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
                    4 -> applyReaderPalette(Color.BLACK, Color.WHITE)
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
        if (!openSucceeded || currentContent.isBlank()) {
            Toast.makeText(this, "当前没有可添加书签的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val endPosition = (currentPosition + 80).coerceAtMost(currentContent.length)
        val preview = currentContent.substring(currentPosition, endPosition)
            .replace(Regex("\\s+"), " ")
            .trim()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookmarkDao().insert(
                    Bookmark(
                        bookId = bookId,
                        position = currentPosition.toString(),
                        content = preview
                    )
                )
            }
            Toast.makeText(this@ReaderActivity, "已添加书签", Toast.LENGTH_SHORT).show()
        }
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
            val labels = bookmarks.map { bookmark ->
                val position = bookmark.position.toIntOrNull() ?: 0
                "位置 $position · ${bookmark.content.ifBlank { "无预览" }}"
            }.toTypedArray()
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
        val labels = bookmarks.map { bookmark ->
            val position = bookmark.position.toIntOrNull() ?: 0
            "位置 $position · ${bookmark.content.ifBlank { "无预览" }}"
        }.toTypedArray()
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
        val maxScroll = ((contentView.layout?.height ?: 0) - contentView.height).coerceAtLeast(0)
        val target = (contentView.scrollY + direction * (contentView.height * 0.85f).toInt())
            .coerceIn(0, maxScroll)
        if (target == contentView.scrollY) {
            if (txtStreamingMode && direction > 0 && txtCurrentPageEndByte < txtTotalBytes) {
                showStreamingTxtPage(txtCurrentPageEndByte, direction = 1)
            } else if (txtStreamingMode && direction < 0 && txtCurrentPageStartByte > 0L) {
                showStreamingTxtPage((txtCurrentPageStartByte - TXT_STREAM_WINDOW_BYTES).coerceAtLeast(0L), direction = -1)
            } else {
                Toast.makeText(this, if (direction > 0) "已经到末尾" else "已经到开头", Toast.LENGTH_SHORT).show()
            }
            return
        }
        contentView.scrollTo(0, target)
        updateVerticalScrollProgress(target)
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
            applyReaderPalette(Color.BLACK, Color.WHITE)
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
        Toast.makeText(this, "翻页模式：${turnModeLabel(mode)}", Toast.LENGTH_SHORT).show()
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
                currentPosition = window.startByte.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                txtCurrentPageEndByte = window.nextByte
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
        val width = contentView.width.takeIf { it > 0 } ?: return false
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
