package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.Bookmark
import com.simplereader.app.data.entity.ReadProgress
import com.simplereader.app.parser.TxtParser
import com.simplereader.app.reader.ReaderDocument
import com.simplereader.app.reader.ReaderDocumentLoader
import com.simplereader.app.reader.ReaderImageRepository
import com.simplereader.app.reader.page.PageCacheStore
import com.simplereader.app.reader.page.PageEngine
import com.simplereader.app.reader.page.ReaderBook
import com.simplereader.app.reader.page.ReaderCacheProfile
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
    private lateinit var readerRoot: View
    private lateinit var readerViewport: FrameLayout
    private lateinit var readerTopHaze: View
    private lateinit var readerBottomHaze: View
    private lateinit var readerScrollView: NestedScrollView
    private lateinit var continuousTextView: TextView
    private lateinit var pagedReaderView: PagedReaderView
    private lateinit var progressLabel: TextView
    private lateinit var readerControls: LinearLayout
    private lateinit var readerSettingsPanel: LinearLayout
    private lateinit var progressSeekBar: SeekBar
    private lateinit var autoReadStopView: TextView

    private var paginationJob: Job? = null
    private var continuousRenderJob: Job? = null
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
    private var searchKeyword: String = ""
    private var searchHits: List<SearchPageHit> = emptyList()
    private var activeSearchHit: SearchPageHit? = null
    private var continuousHighlightSpan: BackgroundColorSpan? = null
    private var suppressContinuousScroll = false
    private var continuousWindowStartOffset = 0
    private var continuousWindowEndOffset = 0
    private var continuousWindowShiftPosted = false
    private var continuousTouchActive = false
    private var continuousLastScrollUptime = 0L
    private var statusBarInsetPx = 0
    private var navigationBarInsetPx = 0
    private var readerInsetsApplied = false
    private var backgroundSelection: ReaderBackgrounds.Selection = ReaderBackgrounds.Selection()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fontChangeRunnable: Runnable? = null
    private var pendingFontRollback: FontRollback? = null
    private var autoReadSpeedCpm: Int = 500
    private var autoReading = false
    private var autoReadPageRunnable: Runnable? = null
    private var autoReadAwaitingPageCommit = false
    private var verticalAutoFrameCallback: Choreographer.FrameCallback? = null
    private var verticalAutoLastFrameNanos: Long = 0L
    private var verticalAutoPixelRemainder: Double = 0.0
    private var verticalRecyclerView: RecyclerView? = null
    private var verticalLayoutManager: LinearLayoutManager? = null
    private var verticalAdapter: VerticalPageAdapter? = null
    private var verticalProgrammaticScroll = false
    private var verticalWindowSuspended = false
    private var modeSwitchInProgress = false
    private var paginationInProgress = false
    private var pendingTurnMode: String? = null
    private var suspendedAnchorOffset: Int? = null
    private var suspendedAnchorViewportPx: Int? = null
    private var lastStableSourceOffset: Int? = null

    private data class FontRollback(
        val textSizeSp: Float,
        val readerBook: ReaderBook,
        val settings: ReaderLayoutSettings?,
        val pageIndex: Int,
        val sourceOffset: Int
    )

    private val continuousGesture by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val center = e.x in readerScrollView.width * 0.25f..readerScrollView.width * 0.75f &&
                    e.y in readerScrollView.height * 0.2f..readerScrollView.height * 0.8f
                if (center) {
                    setReaderChromeVisible(!chromeVisible)
                    return true
                }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        // Root spans the screen; text starts below the notification bar plus one character.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportRequestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY)
        setContentView(R.layout.activity_reader)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.rgb(72, 67, 58)))
        supportActionBar?.hide()

        database = SimpleReaderDatabase.getDatabase(this)
        readerRoot = findViewById(R.id.readerRoot)
        readerViewport = findViewById(R.id.readerViewport)
        readerTopHaze = findViewById(R.id.readerTopHaze)
        readerBottomHaze = findViewById(R.id.readerBottomHaze)
        readerScrollView = findViewById(R.id.readerScrollView)
        continuousTextView = findViewById(R.id.contentView)
        pagedReaderView = findViewById(R.id.pagedReaderView)
        progressLabel = findViewById(R.id.readerProgressLabel)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        progressSeekBar = findViewById(R.id.fontSizeSeekBar)
        autoReadStopView = findViewById(R.id.autoReadStopButton)
        autoReadStopView.setOnClickListener { stopAutoReading(false) }
        autoReadStopView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 48, 47, 42))
        }
        bookId = intent.getLongExtra("bookId", 0L)

        loadPreferences()
        bindReaderInsets()
        applyReaderContentPadding()
        bindPagedReader()
        bindContinuousReader()
        bindControls()
        applyReaderAppearance(rebindPages = false)
        pagedReaderView.post { loadBook() }
    }

    override fun onPause() {
        stopAutoReading(false)
        saveProgress()
        super.onPause()
    }

    override fun onDestroy() {
        paginationJob?.cancel()
        continuousRenderJob?.cancel()
        pagedReaderView.cancelNavigation()
        verticalRecyclerView?.stopScroll()
        fontChangeRunnable?.let(mainHandler::removeCallbacks)
        stopAutoReading(false)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索")
            .setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        val addItem = menu.add(Menu.NONE, MENU_ADD_BOOKMARK, Menu.NONE, "添加书签")
        addItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        addItem.actionView = ImageButton(this).apply {
            setImageResource(R.drawable.ic_reader_add_bookmark)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "添加书签"
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(8)
                marginEnd = dp(16)
            }
            // v752: move the bookmark glyph left by exactly one third of the active reading character.
            translationX = -bookmarkOneThirdCharacterPx()
            setOnClickListener { addBookmark() }
        }
        return true
    }

    private fun bookmarkOneThirdCharacterPx(): Float =
        readerTextSizeSp * resources.displayMetrics.scaledDensity / 3f

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SEARCH -> { showContentSearch(); true }
        MENU_ADD_BOOKMARK -> { addBookmark(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyTurnEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
            if (pageTurnMode == TURN_MODE_VERTICAL) {
                val rv = verticalRecyclerView
                if (rv != null) rv.smoothScrollBy(0, direction * rv.height.coerceAtLeast(1))
            } else {
                pagedReaderView.turn(direction)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun bindPagedReader() {
        pagedReaderView.onTurnCommitted = { direction ->
            clearSearchHighlight()
            val pages = readerBook?.pages.orEmpty()
            val target = (currentPageIndex + direction).coerceIn(0, pages.lastIndex.coerceAtLeast(0))
            if (target != currentPageIndex) {
                currentPageIndex = target
                lastStableSourceOffset = pages[target].startOffset
                bindHorizontalPages()
                updateProgressUi()
                saveProgress()
                autoReadAwaitingPageCommit = false
                if (autoReading && pageTurnMode != TURN_MODE_VERTICAL) scheduleAutomaticPageTurn()
            } else {
                bindHorizontalPages()
                autoReadAwaitingPageCommit = false
                if (autoReading) stopAutoReading(true)
            }
        }
        pagedReaderView.onBoundaryTurn = { direction ->
            val pages = readerBook?.pages.orEmpty()
            if (pages.isNotEmpty()) {
                currentPageIndex = (currentPageIndex + direction).coerceIn(0, pages.lastIndex)
                bindHorizontalPages()
            }
        }
        pagedReaderView.onCenterTap = { setReaderChromeVisible(!chromeVisible) }
    }

    private fun bindContinuousReader() {
        readerScrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> continuousTouchActive = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    continuousTouchActive = false
                    scheduleContinuousWindowShift(readerScrollView.scrollY)
                }
            }
            continuousGesture.onTouchEvent(event)
            false
        }
        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (suppressContinuousScroll || pageTurnMode != TURN_MODE_VERTICAL) return@setOnScrollChangeListener
            continuousLastScrollUptime = android.os.SystemClock.uptimeMillis()
            clearSearchHighlight()
            updateContinuousPosition(scrollY)
            scheduleContinuousWindowShift(scrollY)
        }
    }

    private fun bindControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { stopAutoReading(false); showCatalogBookmarkPanelV600() }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener {
            stopAutoReading(false)
            readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.autoReadButton).setOnClickListener { showAutoReadDialog() }
        findViewById<TextView>(R.id.nightButton).setOnClickListener {
            ReaderAppearance.toggleMode(this)
            applyReaderAppearance(rebindPages = true)
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
        findViewById<TextView>(R.id.themePaperButton).setOnClickListener { selectQuickColor("scene_yellow") }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener { selectQuickColor("scene_green") }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener { selectQuickColor("scene_white") }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            ReaderAppearance.setMode(this, ReaderAppearance.MODE_NIGHT)
            applyReaderAppearance(rebindPages = true)
        }
        findViewById<TextView>(R.id.themeMoreButton).setOnClickListener {
            ReaderBackgroundPicker.show(this, currentBackgroundSelection()) { selection ->
                backgroundSelection = ReaderBackgrounds.validated(selection)
                ReaderAppearance.setMode(this, ReaderAppearance.MODE_DAY)
                savePreferences()
                applyReaderAppearance(rebindPages = true)
            }
        }
        mapOf(
            R.id.turnModeOverlapButton to TURN_MODE_OVERLAP,
            R.id.turnModeSimulateButton to TURN_MODE_SIMULATE,
            R.id.turnModeHorizontalButton to TURN_MODE_HORIZONTAL,
            R.id.turnModeVerticalButton to TURN_MODE_VERTICAL,
            R.id.turnModeFadeButton to TURN_MODE_FADE
        ).forEach { (id, mode) -> findViewById<TextView>(id).setOnClickListener { setTurnMode(mode) } }

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
                    jumpToPage(((pending / 1000f) * (pages.size - 1)).toInt(), false)
                }
            }
        })
        updateSettingsLabels()
    }

    private fun loadBook() {
        if (bookId <= 0L) return showFatal("书籍记录不存在")
        lifecycleScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { database.bookDao().getBook(bookId) }
                    ?: error("书籍记录不存在")
                book = selected
                title = ""
                supportActionBar?.title = ""
                if (selected.format.equals("CHM", ignoreCase = true)) {
                    error("当前版本已停止支持 CHM：请改用 TXT 或 EPUB")
                }
                val loaded = withContext(Dispatchers.IO) { ReaderDocumentLoader.load(this@ReaderActivity, selected) }
                document = loaded
                imageRepository = ReaderImageRepository(this@ReaderActivity, selected.id)
                if (loaded.charsetName != null && !loaded.charsetName.equals(selected.txtCharset, true)) {
                    withContext(Dispatchers.IO) { database.bookDao().updateTxtCharset(selected.id, loaded.charsetName) }
                }
                paginateAndDisplay(null)
                if (loaded.fromCacheOnly) {
                    Toast.makeText(this@ReaderActivity, "原文件不可访问，正在使用本地可读缓存", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                showFatal(error.message ?: "打开书籍失败")
            }
        }
    }

    private fun paginateAndDisplay(preserveOffset: Int?) {
        val selectedBook = book ?: return
        val loaded = document ?: return
        if (pagedReaderView.width <= 0 || pagedReaderView.height <= 0) {
            pagedReaderView.post { paginateAndDisplay(preserveOffset) }
            return
        }
        paginationJob?.cancel()
        paginationInProgress = true
        progressLabel.text = "分页中…"
        paginationJob = lifecycleScope.launch {
            try {
                val settings = createLayoutSettings()
                layoutSettings = settings
                val identity = PageCacheStore.CacheIdentity(
                    selectedBook.id,
                    selectedBook.filePath,
                    loaded.sourceSize,
                    loaded.sourceModified,
                    settings.stableHash(),
                    PageCacheStore.textFingerprint(loaded.text),
                    TxtParser.CATALOG_RULE_VERSION
                )
                val cached = withContext(Dispatchers.IO) {
                    PageCacheStore.loadPages(this@ReaderActivity, identity, loaded.text)
                }
                val paged = cached ?: withContext(Dispatchers.Default) {
                    PageEngine.paginate(
                        loaded.text,
                        loaded.chapters,
                        settings,
                        Typeface.DEFAULT
                    ) { href, width, height -> imageRepository?.span(href, width, height) }
                }
                readerBook = paged
                pendingFontRollback = null
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val stableOffset = preserveOffset
                    ?: lastStableSourceOffset
                    ?: progress?.startOffset
                    ?: progress?.txtCharOffset
                    ?: progress?.position?.toIntOrNull()
                val target = when {
                    stableOffset != null -> paged.pageForOffset(stableOffset).globalPageIndex
                    progress?.globalPageIndex != null && progress.globalPageIndex in paged.pages.indices -> progress.globalPageIndex
                    else -> 0
                }
                currentPageIndex = target.coerceIn(0, paged.pages.lastIndex)
                lastStableSourceOffset = paged.pages.getOrNull(currentPageIndex)?.startOffset
                paginationInProgress = false
                showActiveReader()
                pendingTurnMode?.let { queued -> pendingTurnMode = null; setTurnMode(queued) }
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        if (cached == null) {
                            PageCacheStore.savePages(this@ReaderActivity, identity, paged)
                        }
                        PageCacheStore.markRecognitionComplete(
                            context = this@ReaderActivity,
                            bookId = selectedBook.id,
                            fileName = selectedBook.fileName,
                            fileSize = selectedBook.fileSize,
                            chapterCount = paged.chapters.count { it.catalogVisible },
                            pageCount = paged.pages.size
                        )
                    }
                }
            } catch (error: Throwable) {
                val rollback = pendingFontRollback
                if (rollback != null) {
                    pendingFontRollback = null
                    readerTextSizeSp = rollback.textSizeSp
                    readerBook = rollback.readerBook
                    layoutSettings = rollback.settings
                    currentPageIndex = rollback.pageIndex.coerceIn(0, rollback.readerBook.pages.lastIndex)
                    applyReaderContentPadding()
                    savePreferences()
                    updateSettingsLabels()
                    showActiveReader()
                    Toast.makeText(this@ReaderActivity, "字号分页失败，已恢复原字号和位置", Toast.LENGTH_SHORT).show()
                } else {
                    showContinuousFallback(error.message ?: "分页失败")
                }
            }
        }
    }

    private fun createLayoutSettings(): ReaderLayoutSettings {
        ReaderCacheProfile.rememberViewport(this, pagedReaderView.width, pagedReaderView.height)
        return ReaderCacheProfile.createSettings(
            context = this,
            textSizeSp = readerTextSizeSp,
            viewportWidthPx = pagedReaderView.width,
            viewportHeightPx = pagedReaderView.height,
            contentPaddingLeftPx = continuousTextView.paddingLeft,
            contentPaddingTopPx = continuousTextView.paddingTop,
            contentPaddingRightPx = continuousTextView.paddingRight,
            contentPaddingBottomPx = continuousTextView.paddingBottom
        )
    }

    private fun showActiveReader() {
        if (pageTurnMode == TURN_MODE_VERTICAL) showContinuousBook() else showHorizontalBook()
        updateProgressUi()
    }

    private fun showHorizontalBook() {
        verticalRecyclerView?.apply { stopScroll(); visibility = View.GONE }
        readerTopHaze.visibility = View.GONE
        readerBottomHaze.visibility = View.GONE
        readerScrollView.visibility = View.GONE
        configurePagedReaderStyle()
        bindHorizontalPages()
        pagedReaderView.visibility = View.VISIBLE
    }

    private fun configurePagedReaderStyle() {
        val settings = layoutSettings ?: return
        val palette = activePalette()
        pagedReaderView.configure(
            PagedReaderView.Style(
                textSizePx = settings.textSizePx,
                textColor = palette.textColor,
                horizontalPaddingPx = settings.contentPaddingLeftPx,
                topPaddingPx = settings.contentPaddingTopPx,
                bottomPaddingPx = settings.contentPaddingBottomPx,
                lineSpacingMultiplier = settings.lineSpacingMultiplier,
                typeface = Typeface.DEFAULT,
                backgroundFactory = { activeBackgroundDrawable() }
            )
        )
        pagedReaderView.setTurnMode(
            when (pageTurnMode) {
                TURN_MODE_SIMULATE -> PagedReaderView.TurnMode.SIMULATE
                TURN_MODE_HORIZONTAL -> PagedReaderView.TurnMode.SLIDE
                TURN_MODE_FADE -> PagedReaderView.TurnMode.FADE
                else -> PagedReaderView.TurnMode.OVERLAP
            }
        )
    }

    private fun bindHorizontalPages() {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val previous = pages.getOrNull(currentPageIndex - 1)?.let(::snapshotFor)
        val current = snapshotFor(pages[currentPageIndex])
        val next = pages.getOrNull(currentPageIndex + 1)?.let(::snapshotFor)
        pagedReaderView.bind(previous, current, next)
    }

    private fun snapshotFor(page: ReaderPage): ReaderPageSnapshot = ReaderPageSnapshot(
        startAnchor = ReaderPageAnchor(page.chapterIndex, page.startOffset - readerBook!!.chapters[page.chapterIndex].startOffset, page.startOffset.toLong()),
        endAnchor = ReaderPageAnchor(page.chapterIndex, page.endOffset - readerBook!!.chapters[page.chapterIndex].startOffset, page.endOffset.toLong()),
        content = renderPage(page),
        pageIndexInChapter = page.pageIndexInChapter,
        pageCountInChapter = page.chapterPageCount
    )

    private fun renderPage(page: ReaderPage): CharSequence {
        val paged = readerBook ?: return ""
        val settings = layoutSettings ?: return ""
        val start = page.startOffset.coerceIn(0, paged.text.length)
        val end = page.endOffset.coerceIn(start, paged.text.length)
        val rendered = PageEngine.styledText(
            paged.text.substring(start, end),
            settings,
            page.pageIndexInChapter == 0
        ) { href, width, height -> imageRepository?.span(href, width, height) }
        // v745: keep renderPage() source-layout faithful. V104 is horizontal-snapshot-only
        // and is applied by PagedReaderView.bind/updateAdjacent, never by vertical RecyclerView.
        val hit = activeSearchHit ?: return rendered
        if (hit.startOffset !in start until end) return rendered
        val result = SpannableString(rendered)
        val localStart = (hit.startOffset - start).coerceIn(0, result.length)
        val localEnd = (hit.endOffset - start).coerceIn(localStart, result.length)
        if (localEnd > localStart) {
            result.setSpan(BackgroundColorSpan(Color.rgb(255, 226, 105)), localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return result
    }

    private fun ensureVerticalReader() {
        if (verticalRecyclerView != null) return
        val recycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = null
            setItemViewCacheSize(12)
            visibility = View.INVISIBLE
        }
        val manager = LinearLayoutManager(this, RecyclerView.VERTICAL, false).apply {
            initialPrefetchItemCount = 8
        }
        val adapter = VerticalPageAdapter(this)
        recycler.layoutManager = manager
        recycler.adapter = adapter
        recycler.addOnScrollListener(VerticalScrollListener(this, manager))
        recycler.setOnTouchListener(VerticalTouchListener(this))
        readerViewport.addView(recycler, 2)
        verticalRecyclerView = recycler
        verticalLayoutManager = manager
        verticalAdapter = adapter
    }

    private fun showContinuousBook(targetOffset: Int? = null, onComplete: ((Boolean) -> Unit)? = null) {
        val paged = readerBook ?: return onComplete?.invoke(false) ?: Unit
        ensureVerticalReader()
        pagedReaderView.cancelNavigation()
        pagedReaderView.visibility = View.GONE
        readerScrollView.visibility = View.GONE
        readerTopHaze.visibility = View.GONE
        readerBottomHaze.visibility = View.GONE
        verticalProgrammaticScroll = true
        verticalAdapter?.setPages(paged.pages)
        val offset = targetOffset ?: paged.pages.getOrNull(currentPageIndex)?.startOffset ?: 0
        val page = paged.pageForOffset(offset)
        currentPageIndex = page.globalPageIndex
        lastStableSourceOffset = page.startOffset
        continuousWindowStartOffset = page.startOffset
        continuousWindowEndOffset = page.endOffset
        verticalRecyclerView?.visibility = View.VISIBLE
        verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)
        verticalRecyclerView?.post {
            verticalProgrammaticScroll = false
            verticalOnPageVisible(currentPageIndex)
            onComplete?.invoke(true)
        }
        updateProgressUi()
    }

    internal fun verticalRenderPage(page: ReaderPage): CharSequence = renderPage(page)
    internal fun verticalTextSizeSp(): Float = readerTextSizeSp
    internal fun verticalLineSpacingMultiplier(): Float = layoutSettings?.lineSpacingMultiplier ?: 1.75f
    internal fun verticalTextColor(): Int = activePalette().textColor
    internal fun verticalPaddingLeft(): Int = continuousTextView.paddingLeft
    internal fun verticalPaddingRight(): Int = continuousTextView.paddingRight
    internal fun verticalShouldIgnoreScroll(): Boolean = verticalWindowSuspended || verticalProgrammaticScroll
    internal fun verticalShowBoundaryHaze() = showBoundaryHaze()

    private fun showBoundaryHaze() {
        if (pageTurnMode != TURN_MODE_VERTICAL) return
        readerViewport.removeCallbacks(hideBoundaryHazeRunnable)
        listOf(readerTopHaze, readerBottomHaze).forEach { haze ->
            haze.animate().cancel()
            haze.visibility = View.VISIBLE
            haze.alpha = 0.86f
        }
        readerViewport.postDelayed(hideBoundaryHazeRunnable, 130L)
    }

    private val hideBoundaryHazeRunnable = Runnable {
        if (!::readerTopHaze.isInitialized || !::readerBottomHaze.isInitialized) return@Runnable
        listOf(readerTopHaze, readerBottomHaze).forEach { haze ->
            haze.animate().cancel()
            haze.visibility = View.VISIBLE
            haze.animate().alpha(0.52f).setDuration(260L).start()
        }
    }

    private fun updateBoundaryHazeStyle() {
        val backgroundColor = activePalette().backgroundColor
        readerTopHaze.background = ReaderBoundaryFogDrawable(backgroundColor, true)
        readerBottomHaze.background = ReaderBoundaryFogDrawable(backgroundColor, false)
        val alpha = if (pageTurnMode == TURN_MODE_VERTICAL) 0.48f else 0.58f
        listOf(readerTopHaze, readerBottomHaze).forEach { haze ->
            haze.visibility = View.VISIBLE
            haze.alpha = alpha
        }
    }
    internal fun verticalOnPageVisible(index: Int) {
        val pages = readerBook?.pages.orEmpty()
        if (index !in pages.indices) return
        currentPageIndex = index
        lastStableSourceOffset = pages[index].startOffset
        continuousWindowStartOffset = pages[index].startOffset
        continuousWindowEndOffset = pages[index].endOffset
        updateProgressUi()
    }
    internal fun verticalOnUserDrag() { clearSearchHighlight() }
    internal fun verticalHandleTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && autoReading) stopAutoReading(false)
        continuousGesture.onTouchEvent(event)
        return false
    }


    /**
     * Vertical mode renders only a bounded page neighborhood. A multi-megabyte novel is never
     * assigned to one TextView/StaticLayout, preventing blank jumps, frozen scrolling and OOM exits.
     */
    private fun renderContinuousWindow(targetOffset: Int, anchorViewportOffsetPx: Int? = null) {
        val paged = readerBook ?: return
        val settings = layoutSettings ?: return
        if (paged.pages.isEmpty()) return
        val targetPage = paged.pageForOffset(targetOffset).globalPageIndex
        val firstPage = (targetPage - CONTINUOUS_PAGES_BEFORE).coerceAtLeast(0)
        val lastPage = (targetPage + CONTINUOUS_PAGES_AFTER).coerceAtMost(paged.pages.lastIndex)
        val startOffset = paged.pages[firstPage].startOffset.coerceIn(0, paged.text.length)
        val endOffset = paged.pages[lastPage].endOffset.coerceIn(startOffset, paged.text.length)
        val windowText = paged.text.substring(startOffset, endOffset)
        val localChapters = paged.chapters.mapNotNull { chapter ->
            if (chapter.startOffset !in startOffset until endOffset) return@mapNotNull null
            chapter.copy(
                startOffset = chapter.startOffset - startOffset,
                endOffset = (chapter.endOffset - startOffset).coerceIn(0, windowText.length)
            )
        }
        continuousRenderJob?.cancel()
        continuousRenderJob = lifecycleScope.launch {
            val styled = withContext(Dispatchers.Default) {
                PageEngine.styledWholeText(
                    windowText,
                    localChapters,
                    settings
                ) { href, width, height -> imageRepository?.span(href, width, height) }
            }
            continuousWindowStartOffset = startOffset
            continuousWindowEndOffset = endOffset
            suppressContinuousScroll = true
            continuousTextView.setText(styled, TextView.BufferType.SPANNABLE)
            applyContinuousHighlight()
            scrollContinuousToOffset(targetOffset, anchorViewportOffsetPx)
        }
    }

    private fun showContinuousFallback(reason: String) {
        val loaded = document ?: return showFatal(reason)
        val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset()).coerceIn(0, loaded.text.length)
        var start = (anchor - CONTINUOUS_FALLBACK_CHARS / 2).coerceAtLeast(0)
        val end = (start + CONTINUOUS_FALLBACK_CHARS).coerceAtMost(loaded.text.length)
        start = (end - CONTINUOUS_FALLBACK_CHARS).coerceAtLeast(0)
        pagedReaderView.visibility = View.GONE
        readerScrollView.visibility = View.VISIBLE
        continuousWindowStartOffset = start
        continuousWindowEndOffset = end
        continuousTextView.text = loaded.text.substring(start, end)
        continuousTextView.textSize = readerTextSizeSp
        continuousTextView.setTextColor(activePalette().textColor)
        readerScrollView.background = activeBackgroundDrawable()
        progressLabel.text = "可读模式"
        continuousTextView.post {
            val layout = continuousTextView.layout ?: return@post
            val localOffset = (anchor - start).coerceIn(0, continuousTextView.text.length)
            val line = layout.getLineForOffset(localOffset)
            readerScrollView.scrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
        }
        Toast.makeText(this, "分页失败，已在当前位置打开有限文本窗口：$reason", Toast.LENGTH_LONG).show()
    }

    private fun sourceOffsetAtScroll(scrollY: Int): Int {
        val layout = continuousTextView.layout ?: return continuousWindowStartOffset
        if (layout.lineCount <= 0) return continuousWindowStartOffset
        val localY = (scrollY - continuousTextView.top).coerceAtLeast(0)
        val line = layout.getLineForVertical(localY.coerceAtMost(layout.height))
        val localOffset = layout.getLineStart(line).coerceIn(0, continuousTextView.text.length)
        return (continuousWindowStartOffset + localOffset)
            .coerceIn(continuousWindowStartOffset, continuousWindowEndOffset)
    }

    private fun updateContinuousPosition(scrollY: Int) {
        val offset = sourceOffsetAtScroll(scrollY)
        readerBook?.let {
            currentPageIndex = it.pageForOffset(offset).globalPageIndex
            updateProgressUi()
        }
    }

    private fun scheduleContinuousWindowShift(scrollY: Int) {
        val paged = readerBook ?: return
        if (verticalWindowSuspended || continuousWindowShiftPosted || continuousTextView.height <= 0 || readerScrollView.height <= 0) return
        val threshold = readerScrollView.height * 4
        val nearTop = scrollY < threshold && continuousWindowStartOffset > 0
        val nearBottom = scrollY + readerScrollView.height > continuousTextView.height - threshold &&
            continuousWindowEndOffset < paged.text.length
        if (!nearTop && !nearBottom) return

        continuousWindowShiftPosted = true
        readerScrollView.postDelayed({
            continuousWindowShiftPosted = false
            if (pageTurnMode != TURN_MODE_VERTICAL || continuousTouchActive) return@postDelayed
            val idleMs = android.os.SystemClock.uptimeMillis() - continuousLastScrollUptime
            if (idleMs < CONTINUOUS_SHIFT_IDLE_MS) {
                scheduleContinuousWindowShift(readerScrollView.scrollY)
                return@postDelayed
            }
            val stableScrollY = readerScrollView.scrollY
            val anchorOffset = sourceOffsetAtScroll(stableScrollY)
            val anchorViewportOffsetPx = viewportOffsetForSourceOffset(anchorOffset, stableScrollY)
            renderContinuousWindow(anchorOffset, anchorViewportOffsetPx)
        }, CONTINUOUS_SHIFT_DELAY_MS)
    }

    private fun viewportOffsetForSourceOffset(offset: Int, scrollY: Int): Int {
        val layout = continuousTextView.layout ?: return 0
        if (layout.lineCount <= 0) return 0
        val localOffset = (offset - continuousWindowStartOffset).coerceIn(0, continuousTextView.text.length)
        val line = layout.getLineForOffset(localOffset)
        val absoluteLineTop = continuousTextView.top + layout.getLineTop(line)
        return absoluteLineTop - scrollY
    }

    private fun scrollContinuousToOffset(offset: Int, anchorViewportOffsetPx: Int? = null) {
        suppressContinuousScroll = true
        continuousTextView.post {
            val layout = continuousTextView.layout
            if (layout == null) {
                suppressContinuousScroll = false
                return@post
            }
            val localOffset = (offset - continuousWindowStartOffset).coerceIn(0, continuousTextView.text.length)
            val line = layout.getLineForOffset(localOffset)
            val absoluteLineTop = continuousTextView.top + layout.getLineTop(line)
            val targetScrollY = if (anchorViewportOffsetPx == null) {
                absoluteLineTop
            } else {
                absoluteLineTop - anchorViewportOffsetPx
            }
            readerScrollView.scrollTo(0, targetScrollY.coerceAtLeast(0))
            readerScrollView.post { suppressContinuousScroll = false }
        }
    }

    private fun turnPage(direction: Int) {
        clearSearchHighlight()
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            val rv = verticalRecyclerView
            rv?.smoothScrollBy(0, direction * rv.height.coerceAtLeast(1))
        } else {
            pagedReaderView.turn(direction)
        }
    }

    private fun jumpToPage(index: Int, animated: Boolean, hit: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        activeSearchHit = hit
        currentPageIndex = index.coerceIn(0, pages.lastIndex)
        lastStableSourceOffset = pages[currentPageIndex].startOffset
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            ensureVerticalReader()
            verticalProgrammaticScroll = true
            verticalAdapter?.refresh()
            verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)
            verticalRecyclerView?.post { verticalProgrammaticScroll = false }
        } else {
            pagedReaderView.cancelNavigation()
            bindHorizontalPages()
        }
        updateProgressUi()
        if (!animated) saveProgress()
    }

    private fun jumpChapter(direction: Int) {
        val paged = readerBook ?: return
        val current = paged.pages.getOrNull(currentPageIndex) ?: return
        val targetChapter = (current.chapterIndex + direction).coerceIn(0, paged.chapters.lastIndex)
        val targetPage = paged.firstPageOfChapter(targetChapter)
        jumpToPage(targetPage, false)
    }

    private fun showCatalogBookmarkPanelV600(showBookmarksFirst: Boolean = false) {
        val paged = readerBook ?: return
        lifecycleScope.launch {
            var bookmarks = withContext(Dispatchers.IO) { database.bookmarkDao().getBookmarks(bookId).first() }
            var showingCatalog = !showBookmarksFirst
            val container = LinearLayout(this@ReaderActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val tabs = LinearLayout(this@ReaderActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val catalogButton = buttonLikeText("目录")
            val bookmarkButton = buttonLikeText("书签")
            tabs.addView(catalogButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabs.addView(bookmarkButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val listView = ListView(this@ReaderActivity).apply {
                isFastScrollEnabled = true
                isFastScrollAlwaysVisible = true
            }
            container.addView(tabs)
            container.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            var dialog: AlertDialog? = null

            fun adapter(labels: List<CharSequence>, highlighted: Int = -1, maxLines: Int = 2) =
                object : ArrayAdapter<CharSequence>(this@ReaderActivity, android.R.layout.simple_list_item_1, labels) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        return super.getView(position, convertView, parent).also { view ->
                            (view as? TextView)?.apply {
                                this.maxLines = maxLines
                                ellipsize = TextUtils.TruncateAt.END
                                textSize = 15f
                                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                                setTextColor(if (position == highlighted) Color.rgb(230, 112, 42) else Color.rgb(42, 39, 31))
                            }
                        }
                    }
                }

            fun render() {
                catalogButton.isEnabled = !showingCatalog
                bookmarkButton.isEnabled = showingCatalog
                dialog?.setTitle(
                    if (showingCatalog) "目录　${book?.title.orEmpty()}" else "书签"
                )
                if (showingCatalog) {
                    val visible = paged.chapters.mapIndexedNotNull { index, chapter ->
                        chapter.takeIf { it.catalogVisible }?.let { index to it }
                    }
                    val currentChapter = paged.pages.getOrNull(currentPageIndex)?.chapterIndex ?: 0
                    val highlighted = visible.indexOfFirst { it.first == currentChapter }
                    val labels = visible.map { (chapterIndex, chapter) ->
                        val page = paged.firstPageOfChapter(chapterIndex)
                        val label = "${chapter.title}\n第 ${page + 1}/${paged.pages.size} 页"
                        if (chapterIndex != currentChapter) label else SpannableString(label).apply {
                            val end = label.indexOf('\n').let { if (it < 0) label.length else it }
                            setSpan(StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(RelativeSizeSpan(1.18f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }.ifEmpty { listOf("暂无目录") }
                    listView.adapter = adapter(labels, highlighted)
                    if (highlighted >= 0) listView.post { listView.setSelection(highlighted) }
                    listView.setOnItemClickListener { _, _, which, _ ->
                        visible.getOrNull(which)?.let { (chapterIndex, _) ->
                            dialog?.dismiss()
                            jumpToPage(paged.firstPageOfChapter(chapterIndex), false)
                        }
                    }
                    listView.setOnItemLongClickListener(null)
                } else {
                    val labels = bookmarks.map { bookmark ->
                        val page = resolveBookmarkPage(bookmark, paged)
                        "${bookmark.content.ifBlank { "书签" }}\n第 ${page + 1}/${paged.pages.size} 页"
                    }.ifEmpty { listOf("暂无书签") }
                    listView.adapter = adapter(labels, maxLines = 3)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let {
                            dialog?.dismiss()
                            jumpToPage(resolveBookmarkPage(it, paged), false)
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

            catalogButton.setOnClickListener { showingCatalog = true; render() }
            bookmarkButton.setOnClickListener { showingCatalog = false; render() }
            render()
            dialog = AlertDialog.Builder(this@ReaderActivity)
                .setTitle(if (showingCatalog) "目录　${book?.title.orEmpty()}" else "书签")
                .setView(container)
                .create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.rgb(250, 246, 232)))
                window.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                window.setLayout((resources.displayMetrics.widthPixels * 0.72f).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            }
        }
    }

    private fun buttonLikeText(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 18f
        setPadding(0, dp(16), 0, dp(16))
    }

    private fun resolveBookmarkPage(bookmark: Bookmark, paged: ReaderBook): Int {
        // V636-V638 compatibility order. New bookmarks always prefer stable source positions; an
        // obsolete global page number is only the last fallback. Schema-v1 backups have no
        // globalPageIndex and require preview/legacy-stream-position recovery.
        bookmark.startOffset?.takeIf { it in 0..paged.text.length }?.let {
            return paged.pageForOffset(it).globalPageIndex
        }

        val numeric = bookmark.position.toLongOrNull()
        val isLegacyV1 = bookmark.globalPageIndex == null
        if (!isLegacyV1) {
            numeric?.takeIf { it in 0..paged.text.length.toLong() }?.let {
                return paged.pageForOffset(it.toInt()).globalPageIndex
            }
            bookmark.globalPageIndex?.takeIf { it in paged.pages.indices }?.let { return it }
            return 0
        }

        val format = book?.format.orEmpty()
        val estimatedSourceOffset = when {
            format.equals("TXT", ignoreCase = true) && numeric != null -> {
                val fileBytes = book?.fileSize?.takeIf { it > 0L }
                if (fileBytes != null) {
                    ((numeric.toDouble() / fileBytes.toDouble()) * paged.text.length.toDouble())
                        .toInt().coerceIn(0, paged.text.length)
                } else null
            }
            numeric != null && numeric in 0..paged.text.length.toLong() -> numeric.toInt()
            else -> null
        }

        resolveBookmarkTextAnchor(bookmark.content, paged.text, estimatedSourceOffset)?.let {
            return paged.pageForOffset(it).globalPageIndex
        }

        // Old streaming TXT position was a byte position, not a current character offset. If the
        // preview cannot be found, map its byte ratio to the current real page sequence.
        if (format.equals("TXT", ignoreCase = true) && numeric != null) {
            val fileBytes = book?.fileSize?.takeIf { it > 0L }
            if (fileBytes != null && paged.pages.isNotEmpty()) {
                val ratio = (numeric.toDouble() / fileBytes.toDouble()).coerceIn(0.0, 1.0)
                return kotlin.math.round(ratio * paged.pages.lastIndex.toDouble()).toInt()
                    .coerceIn(0, paged.pages.lastIndex)
            }
        }
        // Legacy EPUB/other combined-text positions are valid only when they still fit the
        // current source text. Invalid old offsets deliberately fall back to page 0.
        if (!format.equals("TXT", ignoreCase = true) && numeric != null &&
            numeric in 0..paged.text.length.toLong()) {
            return paged.pageForOffset(numeric.toInt()).globalPageIndex
        }
        return 0
    }

    private fun resolveBookmarkTextAnchor(preview: String, source: String, estimated: Int?): Int? {
        val needle = preview.replace(Regex("\\s+"), " ").trim().take(48)
        if (needle.length < 12) return null
        val normalized = StringBuilder(source.length)
        val sourceMap = ArrayList<Int>(source.length.coerceAtMost(1_000_000))
        var lastSpace = false
        source.forEachIndexed { index, char ->
            val space = char.isWhitespace()
            if (space) {
                if (!lastSpace) { normalized.append(' '); sourceMap.add(index) }
            } else {
                normalized.append(char); sourceMap.add(index)
            }
            lastSpace = space
        }
        var from = 0
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        while (from < normalized.length) {
            val hit = normalized.indexOf(needle, from)
            if (hit < 0) break
            val sourceOffset = sourceMap.getOrNull(hit) ?: break
            val distance = estimated?.let { kotlin.math.abs(sourceOffset - it) } ?: 0
            if (best == null || distance < bestDistance) {
                best = sourceOffset
                bestDistance = distance
            }
            from = hit + 1
        }
        return best
    }

    private fun addBookmark() {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        val preview = paged.text.substring(page.startOffset, page.endOffset)
            .replace(Regex("\\s+"), " ").trim().take(180)
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

    private fun showContentSearch() {
        val paged = readerBook ?: return
        ReaderSearchSheet.show(
            this,
            searchKeyword,
            searchHits,
            onSearch = { keyword, callback ->
                searchKeyword = keyword
                lifecycleScope.launch {
                    searchHits = withContext(Dispatchers.Default) { findAllHits(paged, keyword) }
                    callback(searchHits)
                }
            },
            onHit = { hit -> jumpToPage(hit.globalPageIndex, false, hit) },
            backgroundDrawable = activeBackgroundDrawable(),
            textColor = activePalette().textColor
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
            output += SearchPageHit(
                keyword,
                page.chapterIndex,
                page.globalPageIndex,
                index,
                end,
                paged.text.substring((index - 36).coerceAtLeast(0), (end + 72).coerceAtMost(paged.text.length))
                    .replace(Regex("\\s+"), " ").trim(),
                "${page.globalPageIndex + 1}/${page.totalPageCount}"
            )
            cursor = end.coerceAtLeast(index + 1)
        }
        return output
    }

    private fun applyContinuousHighlight() {
        val text = continuousTextView.text as? Spannable ?: return
        continuousHighlightSpan?.let { text.removeSpan(it) }
        continuousHighlightSpan = null
        val hit = activeSearchHit ?: return
        if (hit.endOffset <= continuousWindowStartOffset || hit.startOffset >= continuousWindowEndOffset) return
        val start = (hit.startOffset - continuousWindowStartOffset).coerceIn(0, text.length)
        val end = (hit.endOffset - continuousWindowStartOffset).coerceIn(start, text.length)
        if (end > start) {
            val span = BackgroundColorSpan(Color.rgb(255, 226, 105))
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            continuousHighlightSpan = span
        }
    }

    private fun clearSearchHighlight() {
        if (activeSearchHit == null && continuousHighlightSpan == null) return
        activeSearchHit = null
        val text = continuousTextView.text as? Spannable
        continuousHighlightSpan?.let { text?.removeSpan(it) }
        continuousHighlightSpan = null
    }

    private fun confirmDeleteBookmark(bookmark: Bookmark, onDeleted: () -> Unit = {}) {
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setMessage(bookmark.content.ifBlank { "位置 ${bookmark.position}" })
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { database.bookmarkDao().delete(bookmark) }
                    onDeleted()
                    Toast.makeText(this@ReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
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
                    locatorType = "PAGE_ENGINE_V3",
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
        stopAutoReading(false)
        val paged = readerBook ?: return
        val currentOffset = currentVisibleSourceOffset()
        val requested = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        if (requested == readerTextSizeSp) return
        fontChangeRunnable?.let(mainHandler::removeCallbacks)
        val oldSize = readerTextSizeSp
        val oldBook = paged
        val oldSettings = layoutSettings
        val oldPage = currentPageIndex
        readerTextSizeSp = requested
        applyReaderContentPadding()
        savePreferences()
        updateSettingsLabels()
        fontChangeRunnable = Runnable {
            pendingFontRollback = FontRollback(oldSize, oldBook, oldSettings, oldPage, currentOffset)
            paginateAndDisplay(currentOffset)
        }.also { mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS) }
    }

    private fun setTurnMode(mode: String) {
        stopAutoReading(false)
        if (pageTurnMode == mode || modeSwitchInProgress) return
        if (paginationInProgress) {
            pendingTurnMode = mode
            Toast.makeText(this, "字体分页完成后自动切换翻页模式", Toast.LENGTH_SHORT).show()
            return
        }
        val paged = readerBook
        val sourceOffset = currentVisibleSourceOffset()
        val previousMode = pageTurnMode
        pageTurnMode = mode
        modeSwitchInProgress = true
        savePreferences()
        updateSettingsLabels()
        if (paged == null) { modeSwitchInProgress = false; return }
        runCatching {
            if (mode == TURN_MODE_VERTICAL) {
                showContinuousBook(sourceOffset) { ok ->
                    modeSwitchInProgress = false
                    if (!ok) {
                        pageTurnMode = previousMode
                        savePreferences(); updateSettingsLabels(); showHorizontalBook()
                    }
                }
            } else {
                currentPageIndex = paged.pageForOffset(sourceOffset).globalPageIndex
                verticalRecyclerView?.stopScroll()
                showHorizontalBook()
                modeSwitchInProgress = false
                updateProgressUi()
            }
        }.onFailure {
            modeSwitchInProgress = false
            pageTurnMode = previousMode
            savePreferences(); updateSettingsLabels()
            if (previousMode == TURN_MODE_VERTICAL) showContinuousBook(sourceOffset) else showHorizontalBook()
            Toast.makeText(this, "翻页模式切换失败，已恢复原模式", Toast.LENGTH_SHORT).show()
        }
    }


    private fun currentVisibleSourceOffset(): Int {
        val paged = readerBook ?: return 0
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1
            if (index in paged.pages.indices) return paged.pages[index].startOffset
        }
        return paged.pages.getOrNull(currentPageIndex)?.startOffset
            ?: lastStableSourceOffset?.coerceIn(0, paged.text.length)
            ?: 0
    }


    private fun selectQuickColor(colorId: String) {
        backgroundSelection = ReaderBackgrounds.selection(ReaderBackgrounds.Category.COLOR, colorId)
        ReaderAppearance.setMode(this, ReaderAppearance.MODE_DAY)
        savePreferences()
        applyReaderAppearance(rebindPages = true)
    }

    private fun currentBackgroundSelection() = ReaderBackgrounds.validated(backgroundSelection)

    private fun activeBackgroundSelection(): ReaderBackgrounds.Selection =
        if (ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT) {
            ReaderBackgrounds.nightSelection()
        } else {
            currentBackgroundSelection()
        }

    private fun activePalette(): ReaderAppearance.Palette {
        val selection = activeBackgroundSelection()
        return ReaderAppearance.Palette(
            ReaderBackgrounds.representativeColor(selection),
            ReaderBackgrounds.textColor(selection)
        )
    }

    private fun activeBackgroundDrawable() = ReaderBackgrounds.drawable(this, activeBackgroundSelection())

    private fun applyReaderAppearance(rebindPages: Boolean) {
        val palette = activePalette()
        readerRoot.background = activeBackgroundDrawable()
        verticalAdapter?.refresh()
        readerScrollView.background = ColorDrawable(Color.TRANSPARENT)
        continuousTextView.setTextColor(palette.textColor)
        continuousTextView.background = ColorDrawable(Color.TRANSPARENT)
        findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        updateBoundaryHazeStyle()
        val lightSystemBars = !ReaderAppearance.isDark(palette.backgroundColor)
        WindowCompat.getInsetsController(window, readerRoot).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
        findViewById<TextView>(R.id.themeNightButton).text = if (night) "☀" else "☾"
        updateThemePreviews()
        if (rebindPages && readerBook != null) {
            if (pageTurnMode == TURN_MODE_VERTICAL) showContinuousBook() else showHorizontalBook()
        }
    }

    private fun updateThemePreviews() {
        mapOf(
            R.id.themePaperButton to "scene_yellow",
            R.id.themeEyeButton to "scene_green",
            R.id.themeWhiteButton to "scene_white"
        ).forEach { (id, colorId) ->
            val preview = ReaderBackgrounds.selection(ReaderBackgrounds.Category.COLOR, colorId)
            findViewById<TextView>(id).background = ReaderBackgrounds.previewDrawable(
                this,
                preview,
                currentBackgroundSelection() == preview && ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_DAY
            )
        }
    }

    private fun updateProgressUi() {
        val pages = readerBook?.pages.orEmpty()
        if (pages.isEmpty()) return
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        progressLabel.text = "${currentPageIndex + 1}/${pages.size}"
        progressSeekBar.progress = if (pages.size <= 1) 0 else ((currentPageIndex.toFloat() / (pages.size - 1)) * 1000).toInt()
        updateCurrentChapterTitle()
    }

    private fun updateCurrentChapterTitle() {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        val rawTitle = paged.chapters.getOrNull(page.chapterIndex)?.title.orEmpty().trim()
        val explicitChapter = Regex(
            "第\\s*[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*(?:单元|章|节|篇|部|卷|回|集)"
        ).find(rawTitle)
        val chapterTitle = (explicitChapter?.let { rawTitle.substring(it.range.first) }
            ?: rawTitle.replace(Regex("^\\s*\\d+[.、．]\\s*"), ""))
            .trim()
            .ifBlank { book?.title.orEmpty() }
        title = chapterTitle
        supportActionBar?.title = chapterTitle
    }

    private fun setReaderChromeVisible(visible: Boolean) {
        chromeVisible = visible
        readerControls.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        progressLabel.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) {
            updateCurrentChapterTitle()
            supportActionBar?.show()
        } else {
            supportActionBar?.hide()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSizeSp = prefs.getFloat(PREF_TEXT_SIZE, ReaderAppearance.textSize(this))
        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP
        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
        autoReadSpeedCpm = prefs.getInt(PREF_AUTO_READ_SPEED, 500).coerceIn(AUTO_READ_MIN_CPM, AUTO_READ_MAX_CPM)
        val storedCategory = prefs.getString(PREF_BACKGROUND_CATEGORY, null)
            ?.let { runCatching { ReaderBackgrounds.Category.valueOf(it) }.getOrNull() }
        val storedOptionId = prefs.getString(PREF_BACKGROUND_OPTION, null)
        backgroundSelection = if (storedCategory != null && storedOptionId != null) {
            ReaderBackgrounds.selection(storedCategory, storedOptionId)
        } else {
            ReaderBackgrounds.selectionFromLegacy(
                legacyColorId = prefs.getString("reader_background_color_id", null),
                legacyTextureId = prefs.getString("reader_background_texture_id", null),
                legacyMaterialId = prefs.getString("reader_background_material_id", null),
                legacyBackgroundColor = ReaderAppearance.palette(this).backgroundColor
            )
        }
    }

    private fun savePreferences() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE).edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSizeSp)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .putInt(PREF_AUTO_READ_SPEED, autoReadSpeedCpm)
            .putString(PREF_BACKGROUND_CATEGORY, currentBackgroundSelection().category.name)
            .putString(PREF_BACKGROUND_OPTION, currentBackgroundSelection().optionId)
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

    private fun bindReaderInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(readerRoot) { _, insets ->
            if (!readerInsetsApplied) {
                readerInsetsApplied = true
                statusBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                navigationBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                applyReaderContentPadding()
            }
            insets
        }
        ViewCompat.requestApplyInsets(readerRoot)
    }

    private fun applyReaderContentPadding() {
        val oneCharacterPx = (readerTextSizeSp * resources.displayMetrics.scaledDensity + 0.5f)
            .toInt().coerceAtLeast(1)
        val topGuardPx = statusBarInsetPx + oneCharacterPx
        val bottomGuardPx = navigationBarInsetPx + oneCharacterPx * 3
        val params = readerViewport.layoutParams as? FrameLayout.LayoutParams
        if (params != null && (params.topMargin != topGuardPx || params.bottomMargin != bottomGuardPx)) {
            params.topMargin = topGuardPx
            params.bottomMargin = bottomGuardPx
            readerViewport.layoutParams = params
        }
        // v722 cache identity uses the guarded viewport and zero internal vertical body padding.
        continuousTextView.setPadding(
            continuousTextView.paddingLeft, 0, continuousTextView.paddingRight, 0
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val rv = verticalRecyclerView
        if (!hasFocus) {
            if (rv != null && pageTurnMode == TURN_MODE_VERTICAL) {
                val pages = readerBook?.pages.orEmpty()
                val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1
                if (index in pages.indices) {
                    suspendedAnchorOffset = pages[index].startOffset
                    suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0
                    lastStableSourceOffset = suspendedAnchorOffset
                }
            }
            verticalWindowSuspended = true
            stopAutoReading(false)
            rv?.stopScroll()
        } else if (rv != null && pageTurnMode == TURN_MODE_VERTICAL) {
            verticalWindowSuspended = true
            rv.stopScroll()
            val anchorOffset = suspendedAnchorOffset
            val viewportOffset = suspendedAnchorViewportPx ?: 0
            if (anchorOffset != null) {
                val pageIndex = readerBook?.pageForOffset(anchorOffset)?.globalPageIndex
                if (pageIndex != null) {
                    verticalProgrammaticScroll = true
                    currentPageIndex = pageIndex
                    lastStableSourceOffset = anchorOffset
                    verticalLayoutManager?.scrollToPositionWithOffset(pageIndex, viewportOffset)
                }
            }
            rv.postOnAnimation {
                rv.stopScroll()
                verticalProgrammaticScroll = false
                verticalWindowSuspended = false
                suspendedAnchorOffset = null
                suspendedAnchorViewportPx = null
            }
        } else {
            verticalWindowSuspended = false
            suspendedAnchorOffset = null
            suspendedAnchorViewportPx = null
        }
    }


    private fun showAutoReadDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
        }
        val label = TextView(this).apply {
            textSize = 16f
            text = "$autoReadSpeedCpm 字/分"
        }
        val seek = SeekBar(this).apply {
            min = AUTO_READ_MIN_CPM
            max = AUTO_READ_MAX_CPM
            progress = autoReadSpeedCpm
        }
        content.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        var selected = autoReadSpeedCpm
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val snapped = ((progress.coerceIn(AUTO_READ_MIN_CPM, AUTO_READ_MAX_CPM) +
                    AUTO_READ_STEP_CPM / 2) / AUTO_READ_STEP_CPM) * AUTO_READ_STEP_CPM
                selected = snapped.coerceIn(AUTO_READ_MIN_CPM, AUTO_READ_MAX_CPM)
                label.text = "$selected 字/分"
                if (fromUser && seekBar?.progress != selected) seekBar?.progress = selected
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val builder = AlertDialog.Builder(this)
            .setTitle("自动阅读")
            .setView(content)
            .setNegativeButton("取消", null)
        if (autoReading) {
            builder.setNeutralButton("停止") { _, _ -> stopAutoReading(false) }
            builder.setPositiveButton("应用速度") { _, _ -> updateAutoReadSpeed(selected) }
        } else {
            builder.setNeutralButton("关闭", null)
            builder.setPositiveButton("开启") { _, _ ->
                updateAutoReadSpeed(selected)
                startAutoReading()
            }
        }
        builder.show()
    }

    private fun updateAutoReadSpeed(cpm: Int) {
        val snapped = ((cpm.coerceIn(AUTO_READ_MIN_CPM, AUTO_READ_MAX_CPM) +
            AUTO_READ_STEP_CPM / 2) / AUTO_READ_STEP_CPM) * AUTO_READ_STEP_CPM
        autoReadSpeedCpm = snapped.coerceIn(AUTO_READ_MIN_CPM, AUTO_READ_MAX_CPM)
        savePreferences()
        if (autoReading) {
            if (pageTurnMode == TURN_MODE_VERTICAL) {
                verticalAutoLastFrameNanos = 0L
                verticalAutoPixelRemainder = 0.0
            } else {
                autoReadAwaitingPageCommit = false
                scheduleAutomaticPageTurn()
            }
        }
    }

    private fun startAutoReading() {
        if (readerBook?.pages.isNullOrEmpty()) return
        stopAutoReading(false)
        autoReading = true
        autoReadAwaitingPageCommit = false
        setReaderChromeVisible(false)
        showAutoReadStopButton()
        if (pageTurnMode == TURN_MODE_VERTICAL) startAutomaticVerticalScroll() else scheduleAutomaticPageTurn()
    }

    private fun scheduleAutomaticPageTurn() {
        if (!autoReading || pageTurnMode == TURN_MODE_VERTICAL || autoReadAwaitingPageCommit) return
        val page = readerBook?.pages?.getOrNull(currentPageIndex) ?: return stopAutoReading(false)
        val text = readerBook?.text.orEmpty()
        val chars = text.substring(page.startOffset.coerceAtLeast(0), page.endOffset.coerceAtMost(text.length))
            .count { !it.isWhitespace() }.coerceAtLeast(1)
        val delay = ((chars.toDouble() / autoReadSpeedCpm.toDouble()) * 60_000.0)
            .toLong().coerceAtLeast(AUTO_READ_MIN_PAGE_DELAY_MS)
        autoReadPageRunnable?.let(mainHandler::removeCallbacks)
        autoReadPageRunnable = Runnable {
            if (!autoReading) return@Runnable
            val pages = readerBook?.pages.orEmpty()
            if (currentPageIndex >= pages.lastIndex) {
                stopAutoReading(true)
            } else {
                autoReadAwaitingPageCommit = true
                if (!pagedReaderView.turn(1)) {
                    autoReadAwaitingPageCommit = false
                    if (currentPageIndex >= pages.lastIndex) stopAutoReading(true)
                    else scheduleAutomaticPageTurn()
                }
            }
        }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun startAutomaticVerticalScroll() {
        verticalAutoLastFrameNanos = 0L
        verticalAutoPixelRemainder = 0.0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!autoReading || pageTurnMode != TURN_MODE_VERTICAL) return
                if (verticalAutoLastFrameNanos != 0L) {
                    val dtSeconds = (frameTimeNanos - verticalAutoLastFrameNanos).coerceAtMost(100_000_000L) / 1_000_000_000.0
                    val linePx = readerTextSizeSp * resources.displayMetrics.scaledDensity * 1.75
                    val charsPerLine = ((verticalRecyclerView?.width ?: readerViewport.width) / (readerTextSizeSp * resources.displayMetrics.scaledDensity * 0.95f)).coerceAtLeast(8f)
                    val linesPerSecond = autoReadSpeedCpm / 60.0 / charsPerLine
                    verticalAutoPixelRemainder += linesPerSecond * linePx * dtSeconds
                    val pixels = verticalAutoPixelRemainder.toInt()
                    if (pixels > 0) {
                        verticalAutoPixelRemainder -= pixels
                        if (!verticalAutoScrollBy(pixels)) return
                    }
                }
                verticalAutoLastFrameNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        verticalAutoFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun verticalAutoScrollBy(pixels: Int): Boolean {
        val rv = verticalRecyclerView ?: return false
        if (!rv.canScrollVertically(1)) {
            stopAutoReading(true)
            return false
        }
        rv.scrollBy(0, pixels.coerceAtLeast(1))
        return true
    }


    private fun stopAutoReading(showToast: Boolean) {
        val wasActive = autoReading
        autoReading = false
        autoReadPageRunnable?.let(mainHandler::removeCallbacks)
        autoReadPageRunnable = null
        autoReadAwaitingPageCommit = false
        verticalAutoFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        verticalAutoFrameCallback = null
        verticalAutoLastFrameNanos = 0L
        verticalAutoPixelRemainder = 0.0
        if (::autoReadStopView.isInitialized) autoReadStopView.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (showToast && wasActive) Toast.makeText(this, "自动阅读已关闭", Toast.LENGTH_SHORT).show()
    }


    private fun showAutoReadStopButton() {
        if (::autoReadStopView.isInitialized) autoReadStopView.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

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
        private const val PREF_AUTO_READ_SPEED = "auto_read_speed_cpm"
        private const val PREF_BACKGROUND_CATEGORY = "reader_background_category"
        private const val PREF_BACKGROUND_OPTION = "reader_background_option"
        private const val TURN_MODE_OVERLAP = "overlap"
        private const val TURN_MODE_SIMULATE = "simulate"
        private const val TURN_MODE_HORIZONTAL = "horizontal"
        private const val TURN_MODE_VERTICAL = "vertical"
        private const val TURN_MODE_FADE = "fade"
        private const val MENU_ADD_BOOKMARK = 2
        private const val MENU_SEARCH = 5
        private const val CONTINUOUS_PAGES_BEFORE = 18
        private const val CONTINUOUS_PAGES_AFTER = 18
        private const val CONTINUOUS_SHIFT_DELAY_MS = 200L
        private const val CONTINUOUS_SHIFT_IDLE_MS = 180L
        private const val CONTINUOUS_FALLBACK_CHARS = 240_000
        private const val FONT_CHANGE_DEBOUNCE_MS = 320L
        private const val AUTO_READ_MIN_CPM = 200
        private const val AUTO_READ_MAX_CPM = 2000
        private const val AUTO_READ_STEP_CPM = 50
        private const val AUTO_READ_MIN_PAGE_DELAY_MS = 700L
    }
}
