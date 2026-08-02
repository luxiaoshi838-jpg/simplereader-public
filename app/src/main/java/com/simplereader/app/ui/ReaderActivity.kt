package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.Bookmark
import com.simplereader.app.data.entity.ReadProgress
import com.simplereader.app.reader.ReaderDocument
import com.simplereader.app.reader.ReaderDocumentLoader
import com.simplereader.app.reader.ReaderImageRepository
import com.simplereader.app.reader.page.PageCacheStore
import com.simplereader.app.reader.page.PageEngine
import com.simplereader.app.reader.page.ReaderBook
import com.simplereader.app.reader.page.ReaderLayoutSettings
import com.simplereader.app.reader.page.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ReaderActivity : AppCompatActivity() {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var readerScrollView: NestedScrollView
    private lateinit var fallbackTextView: TextView
    private lateinit var pageList: RecyclerView
    private lateinit var progressLabel: TextView
    private lateinit var readerControls: LinearLayout
    private lateinit var readerSettingsPanel: LinearLayout
    private lateinit var progressSeekBar: SeekBar
    private lateinit var layoutManager: LinearLayoutManager

    private var pageAdapter: ReaderPageAdapter? = null
    private var snapHelper: PagerSnapHelper? = null
    private var paginationJob: Job? = null
    private var bookId: Long = 0L
    private var book: Book? = null
    private var document: ReaderDocument? = null
    private var readerBook: ReaderBook? = null
    private var layoutSettings: ReaderLayoutSettings? = null
    private var imageRepository: ReaderImageRepository? = null
    private var currentPageIndex: Int = 0
    private var readerTextSizeSp: Float = 18f
    private var pageTurnMode: String = TURN_MODE_OVERLAP
    private var volumeKeyTurnEnabled: Boolean = true
    private var chromeVisible: Boolean = false
    private var suppressScrollCallback: Boolean = false
    private var searchKeyword: String = ""
    private var searchHits: List<SearchPageHit> = emptyList()
    private var pageAnimationRunning = false

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val width = pageList.width.coerceAtLeast(1)
                val height = pageList.height.coerceAtLeast(1)
                val centerLeft = width / 4f
                val centerRight = width * 3f / 4f
                val centerTop = height / 4f
                val centerBottom = height * 3f / 4f
                val centerTap = e.x in centerLeft..centerRight && e.y in centerTop..centerBottom
                return when {
                    centerTap -> {
                        setReaderChromeVisible(!chromeVisible)
                        true
                    }
                    chromeVisible -> true
                    e.x < width * 0.28f -> {
                        turnPage(-1)
                        true
                    }
                    e.x > width * 0.72f -> {
                        turnPage(1)
                        true
                    }
                    else -> false
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.rgb(72, 67, 58)))
        supportActionBar?.hide()

        database = SimpleReaderDatabase.getDatabase(this)
        readerScrollView = findViewById(R.id.readerScrollView)
        fallbackTextView = findViewById(R.id.contentView)
        progressLabel = findViewById(R.id.readerProgressLabel)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        progressSeekBar = findViewById(R.id.fontSizeSeekBar)
        bookId = intent.getLongExtra("bookId", 0L)

        loadPreferences()
        installPageList()
        bindControls()
        applyPalette(rebuildPages = false)
        pageList.post { loadBook() }
    }

    override fun onPause() {
        saveProgress()
        super.onPause()
    }

    override fun onDestroy() {
        paginationJob?.cancel()
        super.onDestroy()
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
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { addBookmark() }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SEARCH -> { showContentSearch(); true }
        MENU_ADD_BOOKMARK -> { addBookmark(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyTurnEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            turnPage(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun installPageList() {
        val root = readerScrollView.parent as FrameLayout
        pageList = RecyclerView(this).apply {
            id = View.generateViewId()
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = null
            setHasFixedSize(true)
            background = ReaderSurfaceDrawable(ReaderAppearance.palette(this@ReaderActivity).backgroundColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (!suppressScrollCallback && newState == RecyclerView.SCROLL_STATE_IDLE) {
                        updateCurrentPageFromViewport()
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!suppressScrollCallback && pageTurnMode == TURN_MODE_VERTICAL) {
                        updateCurrentPageFromViewport()
                    }
                }
            })
        }
        root.addView(pageList, 0)
        readerScrollView.visibility = View.GONE
        fallbackTextView.visibility = View.GONE
        configureLayoutManager()
    }

    private fun configureLayoutManager() {
        val vertical = pageTurnMode == TURN_MODE_VERTICAL
        snapHelper?.attachToRecyclerView(null)
        snapHelper = null
        layoutManager = LinearLayoutManager(
            this,
            if (vertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL,
            false
        )
        pageList.layoutManager = layoutManager
        pageList.isVerticalScrollBarEnabled = vertical
        pageList.isHorizontalScrollBarEnabled = !vertical
        if (!vertical) {
            snapHelper = PagerSnapHelper().also { it.attachToRecyclerView(pageList) }
        }
    }

    private fun bindControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { showCatalogBookmarks() }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener {
            readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.nightButton).setOnClickListener {
            ReaderAppearance.toggleMode(this)
            applyPalette(rebuildPages = false)
        }
        findViewById<TextView>(R.id.previousChapterButton).setOnClickListener { jumpChapter(-1) }
        findViewById<TextView>(R.id.nextChapterButton).setOnClickListener { jumpChapter(1) }
        findViewById<TextView>(R.id.fontDecreaseButton).setOnClickListener { changeTextSize(-1f) }
        findViewById<TextView>(R.id.fontIncreaseButton).setOnClickListener { changeTextSize(1f) }
        findViewById<TextView>(R.id.volumeKeyToggleButton).setOnClickListener {
            volumeKeyTurnEnabled = !volumeKeyTurnEnabled
            savePreferences()
            updateSettingsLabels()
        }
        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            ReaderAppearance.saveDayPalette(this, 0xFFF5E9C8.toInt(), 0xFF3B3428.toInt())
            applyPalette(false)
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            ReaderAppearance.saveDayPalette(this, 0xFFDAEECD.toInt(), 0xFF2F432C.toInt())
            applyPalette(false)
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            ReaderAppearance.saveDayPalette(this, Color.WHITE, 0xFF202020.toInt())
            applyPalette(false)
        }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            ReaderAppearance.setMode(this, ReaderAppearance.MODE_NIGHT)
            applyPalette(false)
        }
        mapOf(
            R.id.turnModeOverlapButton to TURN_MODE_OVERLAP,
            R.id.turnModeSimulateButton to TURN_MODE_SIMULATE,
            R.id.turnModeHorizontalButton to TURN_MODE_HORIZONTAL,
            R.id.turnModeVerticalButton to TURN_MODE_VERTICAL,
            R.id.turnModeFadeButton to TURN_MODE_FADE
        ).forEach { (id, mode) ->
            findViewById<TextView>(id).setOnClickListener { setTurnMode(mode) }
        }
        progressSeekBar.max = 1000
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var pending = 0
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) pending = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val pages = readerBook?.pages.orEmpty()
                if (pages.isNotEmpty()) {
                    val index = ((pending / 1000f) * (pages.size - 1)).toInt()
                    jumpToPage(index, false)
                }
            }
        })
        updateSettingsLabels()
    }

    private fun loadBook() {
        if (bookId <= 0L) {
            showFatal("书籍记录不存在")
            return
        }
        lifecycleScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { database.bookDao().getBook(bookId) }
                    ?: error("书籍记录不存在")
                book = selected
                title = selected.title
                supportActionBar?.title = selected.title
                if (selected.format.equals("CHM", ignoreCase = true)) {
                    error("当前版本已停止支持 CHM：请改用 TXT 或 EPUB")
                }
                val loaded = withContext(Dispatchers.IO) { ReaderDocumentLoader.load(this@ReaderActivity, selected) }
                document = loaded
                imageRepository = ReaderImageRepository(this@ReaderActivity, selected.id)
                if (loaded.charsetName != null && !loaded.charsetName.equals(selected.txtCharset, true)) {
                    withContext(Dispatchers.IO) { database.bookDao().updateTxtCharset(selected.id, loaded.charsetName) }
                }
                paginateAndDisplay(preserveOffset = null)
                if (loaded.fromCacheOnly) {
                    Toast.makeText(this@ReaderActivity, "原文件不可访问，正在使用 EPUB 可读缓存", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                showFatal(error.message ?: "打开书籍失败")
            }
        }
    }

    private fun paginateAndDisplay(preserveOffset: Int?) {
        val selectedBook = book ?: return
        val loaded = document ?: return
        if (pageList.width <= 0 || pageList.height <= 0) {
            pageList.post { paginateAndDisplay(preserveOffset) }
            return
        }
        paginationJob?.cancel()
        progressLabel.text = "分页中…"
        paginationJob = lifecycleScope.launch {
            try {
                val settings = createLayoutSettings()
                layoutSettings = settings
                val identity = PageCacheStore.CacheIdentity(
                    bookId = selectedBook.id,
                    filePath = selectedBook.filePath,
                    fileSize = loaded.sourceSize,
                    lastModified = loaded.sourceModified,
                    settingsHash = settings.stableHash()
                )
                val paged = withContext(Dispatchers.Default) {
                    PageCacheStore.loadPages(this@ReaderActivity, identity, loaded.text)
                        ?: PageEngine.paginate(
                            text = loaded.text,
                            sourceChapters = loaded.chapters,
                            settings = settings,
                            typeface = Typeface.DEFAULT,
                            imageSpanProvider = { href, width, height ->
                                imageRepository?.span(href, width, height)
                            }
                        ).also { PageCacheStore.savePages(this@ReaderActivity, identity, it) }
                }
                readerBook = paged
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val target = when {
                    preserveOffset != null -> paged.pageForOffset(preserveOffset).globalPageIndex
                    progress?.globalPageIndex != null && progress.globalPageIndex in paged.pages.indices -> progress.globalPageIndex
                    progress?.startOffset != null -> paged.pageForOffset(progress.startOffset).globalPageIndex
                    else -> paged.pageForOffset(progress?.position?.toIntOrNull() ?: 0).globalPageIndex
                }
                showPagedBook(target)
            } catch (error: Throwable) {
                showPlainTextFallback(error.message ?: "分页失败")
            }
        }
    }

    private fun createLayoutSettings(): ReaderLayoutSettings {
        val density = resources.displayMetrics.density
        return ReaderLayoutSettings(
            viewportWidthPx = pageList.width.coerceAtLeast(1),
            viewportHeightPx = pageList.height.coerceAtLeast(1),
            contentPaddingLeftPx = fallbackTextView.paddingLeft,
            contentPaddingTopPx = fallbackTextView.paddingTop,
            contentPaddingRightPx = fallbackTextView.paddingRight,
            contentPaddingBottomPx = dp(26),
            textSizePx = readerTextSizeSp * resources.displayMetrics.scaledDensity,
            typefaceKey = "default",
            lineSpacingExtraPx = 0f,
            lineSpacingMultiplier = 1.75f
        )
    }

    private fun showPagedBook(targetIndex: Int) {
        val paged = readerBook ?: return
        pageList.visibility = View.VISIBLE
        readerScrollView.visibility = View.GONE
        val palette = ReaderAppearance.palette(this)
        val settings = layoutSettings ?: return
        pageAdapter = ReaderPageAdapter(
            pages = paged.pages,
            pageWidth = pageList.width,
            pageHeight = pageList.height,
            paddingLeft = settings.contentPaddingLeftPx,
            paddingTop = settings.contentPaddingTopPx,
            paddingRight = settings.contentPaddingRightPx,
            paddingBottom = settings.contentPaddingBottomPx,
            textSizeSp = readerTextSizeSp,
            lineSpacingExtra = 0f,
            lineSpacingMultiplier = settings.lineSpacingMultiplier,
            backgroundFactory = { ReaderSurfaceDrawable(palette.backgroundColor) },
            textColor = palette.textColor,
            renderer = ::renderPage
        )
        pageList.adapter = pageAdapter
        jumpToPage(targetIndex, false)
    }

    private fun renderPage(page: ReaderPage): CharSequence {
        val paged = readerBook ?: return ""
        val settings = layoutSettings ?: return ""
        val start = page.startOffset.coerceIn(0, paged.text.length)
        val end = page.endOffset.coerceIn(start, paged.text.length)
        return PageEngine.styledText(
            text = paged.text.substring(start, end),
            settings = settings,
            titleStartsAtZero = page.pageIndexInChapter == 0,
            imageSpanProvider = { href, width, height -> imageRepository?.span(href, width, height) }
        )
    }

    private fun showPlainTextFallback(reason: String) {
        val loaded = document ?: return showFatal(reason)
        pageList.visibility = View.GONE
        readerScrollView.visibility = View.VISIBLE
        fallbackTextView.visibility = View.VISIBLE
        fallbackTextView.text = loaded.text
        fallbackTextView.textSize = readerTextSizeSp
        applyPalette(false)
        progressLabel.text = "可读模式"
        Toast.makeText(this, "分页缓存已失效，已切换纯文本可读模式：$reason", Toast.LENGTH_LONG).show()
    }

    private fun updateCurrentPageFromViewport() {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        val position = if (pageTurnMode == TURN_MODE_VERTICAL) {
            val first = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
            val candidates = listOf(first, first + 1).filter { it in pages.indices }
            candidates.minByOrNull { index ->
                kotlin.math.abs(layoutManager.findViewByPosition(index)?.top ?: Int.MAX_VALUE)
            } ?: first
        } else {
            val snap = snapHelper?.findSnapView(layoutManager)
            if (snap != null) layoutManager.getPosition(snap) else layoutManager.findFirstVisibleItemPosition()
        }
        setCurrentPage(position.coerceIn(0, pages.lastIndex))
    }

    private fun setCurrentPage(index: Int) {
        val pages = readerBook?.pages ?: return
        currentPageIndex = index.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        progressLabel.text = pageCountLabel()
        progressSeekBar.progress = if (pages.size <= 1) 0 else ((currentPageIndex * 1000f) / (pages.size - 1)).toInt()
    }

    private fun jumpToPage(index: Int, animate: Boolean, highlight: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        val target = index.coerceIn(0, pages.lastIndex)
        if (animate && target != currentPageIndex) {
            animatePageChange(target, if (target > currentPageIndex) 1 else -1, highlight)
            return
        }
        applyPagePosition(target, highlight)
    }

    private fun applyPagePosition(target: Int, highlight: SearchPageHit? = null) {
        suppressScrollCallback = true
        layoutManager.scrollToPositionWithOffset(target, 0)
        setCurrentPage(target)
        highlight?.let { pageAdapter?.setHighlight(it.startOffset, it.endOffset) }
            ?: pageAdapter?.clearHighlight()
        pageList.postDelayed({
            suppressScrollCallback = false
            updateCurrentPageFromViewport()
        }, 120L)
    }

    private fun animatePageChange(target: Int, direction: Int, highlight: SearchPageHit?) {
        if (pageAnimationRunning) return
        pageAnimationRunning = true
        suppressScrollCallback = true
        pageList.animate().cancel()
        resetPageTransform()
        val width = pageList.width.coerceAtLeast(1).toFloat()

        fun switchPage() {
            layoutManager.scrollToPositionWithOffset(target, 0)
            setCurrentPage(target)
            highlight?.let { pageAdapter?.setHighlight(it.startOffset, it.endOffset) }
                ?: pageAdapter?.clearHighlight()
        }
        fun finish() {
            resetPageTransform()
            pageAnimationRunning = false
            suppressScrollCallback = false
            updateCurrentPageFromViewport()
        }

        when (pageTurnMode) {
            TURN_MODE_VERTICAL -> {
                pageList.smoothScrollToPosition(target)
                setCurrentPage(target)
                pageList.postDelayed({ finish() }, 220L)
            }
            TURN_MODE_HORIZONTAL -> {
                pageList.smoothScrollToPosition(target)
                setCurrentPage(target)
                highlight?.let { pageAdapter?.setHighlight(it.startOffset, it.endOffset) }
                    ?: pageAdapter?.clearHighlight()
                pageList.postDelayed({ finish() }, 240L)
            }
            TURN_MODE_SIMULATE -> {
                pageList.cameraDistance = width * 9f
                pageList.pivotX = if (direction > 0) 0f else width
                pageList.animate()
                    .rotationY(-direction * 17f)
                    .alpha(0.62f)
                    .setDuration(125L)
                    .withEndAction {
                        switchPage()
                        pageList.rotationY = direction * 17f
                        pageList.alpha = 0.62f
                        pageList.animate()
                            .rotationY(0f)
                            .alpha(1f)
                            .setDuration(165L)
                            .withEndAction { finish() }
                            .start()
                    }
                    .start()
            }
            TURN_MODE_FADE -> {
                pageList.animate()
                    .alpha(0f)
                    .setDuration(95L)
                    .withEndAction {
                        switchPage()
                        pageList.animate().alpha(1f).setDuration(135L).withEndAction { finish() }.start()
                    }
                    .start()
            }
            else -> {
                pageList.animate()
                    .translationX(-direction * width * 0.10f)
                    .alpha(0.82f)
                    .setDuration(105L)
                    .withEndAction {
                        switchPage()
                        pageList.translationX = direction * width * 0.045f
                        pageList.alpha = 0.92f
                        pageList.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(135L)
                            .withEndAction { finish() }
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun resetPageTransform() {
        pageList.translationX = 0f
        pageList.translationY = 0f
        pageList.rotationY = 0f
        pageList.alpha = 1f
        pageList.pivotX = pageList.width / 2f
    }

    private fun turnPage(direction: Int) {
        val pages = readerBook?.pages ?: return
        val target = (currentPageIndex + direction).coerceIn(0, pages.lastIndex)
        if (target != currentPageIndex) animatePageChange(target, direction, null)
    }

    private fun jumpChapter(direction: Int) {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        val targetChapter = (page.chapterIndex + direction).coerceIn(0, paged.chapters.lastIndex)
        jumpToPage(paged.firstPageOfChapter(targetChapter), false)
    }

    private fun showCatalogBookmarks(startWithBookmarks: Boolean = false) {
        val paged = readerBook ?: return
        lifecycleScope.launch {
            val bookmarks = withContext(Dispatchers.IO) { database.bookmarkDao().getBookmarks(bookId).first() }
            val catalog = paged.chapters.mapIndexedNotNull { index, chapter ->
                if (!chapter.catalogVisible) return@mapIndexedNotNull null
                val pageIndex = paged.firstPageOfChapter(index)
                CatalogPageRow(chapter.title, index, pageIndex, "${pageIndex + 1}/${paged.pages.size}")
            }
            val bookmarkRows = bookmarks.map { bookmark ->
                val pageIndex = resolveBookmarkPage(bookmark, paged)
                BookmarkPageRow(bookmark, "${pageIndex + 1}/${paged.pages.size}")
            }
            ReaderCatalogSheet.show(
                activity = this@ReaderActivity,
                catalog = catalog,
                bookmarks = bookmarkRows,
                startWithBookmarks = startWithBookmarks,
                onCatalog = { jumpToPage(it.globalPageIndex, false) },
                onBookmark = { jumpToPage(resolveBookmarkPage(it.bookmark, paged), false) },
                onDeleteBookmark = { row -> confirmDeleteBookmark(row.bookmark) }
            )
        }
    }

    private fun resolveBookmarkPage(bookmark: Bookmark, paged: ReaderBook): Int = when {
        bookmark.globalPageIndex != null && bookmark.globalPageIndex in paged.pages.indices -> bookmark.globalPageIndex
        bookmark.startOffset != null -> paged.pageForOffset(bookmark.startOffset).globalPageIndex
        else -> paged.pageForOffset(bookmark.position.toIntOrNull() ?: 0).globalPageIndex
    }

    private fun addBookmark() {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        val preview = paged.text.substring(
            page.startOffset.coerceIn(0, paged.text.length),
            page.endOffset.coerceIn(page.startOffset.coerceAtMost(paged.text.length), paged.text.length)
        ).replace(Regex("\\s+"), " ").trim().take(180)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookmarkDao().insert(
                    Bookmark(
                        bookId = bookId,
                        position = page.startOffset.toString(),
                        content = preview,
                        globalPageIndex = page.globalPageIndex,
                        chapterIndex = page.chapterIndex,
                        pageIndexInChapter = page.pageIndexInChapter,
                        startOffset = page.startOffset
                    )
                )
            }
            Toast.makeText(this@ReaderActivity, "书签已添加 ${page.globalPageIndex + 1}/${page.totalPageCount}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showContentSearch() = showSearch()

    private fun showSearch() {
        val paged = readerBook ?: return
        ReaderSearchSheet.show(
            activity = this,
            initialKeyword = searchKeyword,
            initialHits = searchHits,
            onSearch = { keyword, callback ->
                searchKeyword = keyword
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) { findAllHits(paged, keyword) }
                    searchHits = result
                    callback(result)
                }
            },
            onHit = { hit -> jumpToPage(hit.globalPageIndex, false, hit) }
        )
    }

    private fun findAllHits(paged: ReaderBook, keyword: String): List<SearchPageHit> {
        if (keyword.isBlank()) return emptyList()
        val output = mutableListOf<SearchPageHit>()
        var cursor = 0
        while (cursor <= paged.text.length - keyword.length) {
            val index = paged.text.indexOf(keyword, cursor, ignoreCase = true)
            if (index < 0) break
            val end = (index + keyword.length).coerceAtMost(paged.text.length)
            val page = paged.pageForOffset(index)
            val previewStart = (index - 36).coerceAtLeast(0)
            val previewEnd = (end + 72).coerceAtMost(paged.text.length)
            output += SearchPageHit(
                keyword = keyword,
                chapterIndex = page.chapterIndex,
                globalPageIndex = page.globalPageIndex,
                startOffset = index,
                endOffset = end,
                previewText = paged.text.substring(previewStart, previewEnd).replace(Regex("\\s+"), " ").trim(),
                pageLabel = "${page.globalPageIndex + 1}/${page.totalPageCount}"
            )
            cursor = end.coerceAtLeast(index + 1)
        }
        return output
    }

    private fun pageCountLabel(): String {
        val page = readerBook?.pages?.getOrNull(currentPageIndex) ?: return "1/1"
        val currentPage = page.globalPageIndex + 1
        val totalPages = page.totalPageCount
        return "$currentPage/$totalPages"
    }

    private fun confirmDeleteBookmark(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setMessage("确定删除这个书签吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { database.bookmarkDao().delete(bookmark) }
                    Toast.makeText(this@ReaderActivity, "书签已删除", Toast.LENGTH_SHORT).show()
                    showCatalogBookmarks(startWithBookmarks = true)
                }
            }
            .show()
    }

    private fun saveProgress() {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            database.readProgressDao().insert(
                ReadProgress(
                    bookId = bookId,
                    position = page.startOffset.toString(),
                    locatorType = "PAGE_ENGINE_V2",
                    txtCharOffset = page.startOffset,
                    txtTotalLength = paged.text.length,
                    epubSpineIndex = page.chapterIndex,
                    epubChapterOffset = page.startOffset - paged.chapters[page.chapterIndex].startOffset,
                    epubProgressFraction = if (paged.pages.size <= 1) 0f else currentPageIndex.toFloat() / (paged.pages.size - 1),
                    globalPageIndex = page.globalPageIndex,
                    chapterIndex = page.chapterIndex,
                    pageIndexInChapter = page.pageIndexInChapter,
                    startOffset = page.startOffset
                )
            )
            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
        }
    }

    private fun changeTextSize(delta: Float) {
        val offset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset ?: 0
        readerTextSizeSp = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        savePreferences()
        updateSettingsLabels()
        paginateAndDisplay(offset)
    }

    private fun setTurnMode(mode: String) {
        if (pageTurnMode == mode) return
        pageTurnMode = mode
        savePreferences()
        configureLayoutManager()
        pageList.adapter = pageAdapter
        jumpToPage(currentPageIndex, false)
        updateSettingsLabels()
    }

    private fun applyPalette(rebuildPages: Boolean) {
        val palette = ReaderAppearance.palette(this)
        pageList.background = ReaderSurfaceDrawable(palette.backgroundColor)
        fallbackTextView.background = ReaderSurfaceDrawable(palette.backgroundColor)
        fallbackTextView.setTextColor(palette.textColor)
        (readerScrollView.parent as? View)?.background = ReaderSurfaceDrawable(palette.backgroundColor)
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
        if (readerBook != null) showPagedBook(currentPageIndex)
        if (rebuildPages) paginateAndDisplay(readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset)
    }

    private fun setReaderChromeVisible(visible: Boolean) {
        chromeVisible = visible
        readerControls.visibility = if (visible) View.VISIBLE else View.GONE
        progressLabel.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) supportActionBar?.show() else supportActionBar?.hide()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSizeSp = prefs.getFloat(PREF_TEXT_SIZE, ReaderAppearance.textSize(this))
        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP
        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
    }

    private fun savePreferences() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE).edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSizeSp)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .apply()
    }

    private fun updateSettingsLabels() {
        findViewById<TextView>(R.id.fontSizeLabel).text = String.format(Locale.US, "%.0f", readerTextSizeSp)
        findViewById<TextView>(R.id.volumeKeyToggleButton).text = "音量键翻页 ${if (volumeKeyTurnEnabled) "开" else "关"}"
        mapOf(
            R.id.turnModeOverlapButton to TURN_MODE_OVERLAP,
            R.id.turnModeSimulateButton to TURN_MODE_SIMULATE,
            R.id.turnModeHorizontalButton to TURN_MODE_HORIZONTAL,
            R.id.turnModeVerticalButton to TURN_MODE_VERTICAL,
            R.id.turnModeFadeButton to TURN_MODE_FADE
        ).forEach { (id, mode) -> findViewById<TextView>(id).alpha = if (pageTurnMode == mode) 1f else 0.62f }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showFatal(message: String) {
        AlertDialog.Builder(this)
            .setTitle("无法打开书籍")
            .setMessage("$message\n\n请恢复文件访问权限或重新导入该书。")
            .setCancelable(false)
            .setPositiveButton("返回书架") { _, _ -> finish() }
            .show()
    }

    companion object {
        private const val READER_PREFS = "reader_prefs"
        private const val PREF_TEXT_SIZE = "text_size"
        private const val PREF_TURN_MODE = "turn_mode"
        private const val PREF_VOLUME_KEY = "volume_key_turn"
        private const val TURN_MODE_OVERLAP = "overlap"
        private const val TURN_MODE_SIMULATE = "simulate"
        private const val TURN_MODE_HORIZONTAL = "horizontal"
        private const val TURN_MODE_VERTICAL = "vertical"
        private const val TURN_MODE_FADE = "fade"
        private const val MENU_ADD_BOOKMARK = 2
        private const val MENU_SEARCH = 5
    }
}
