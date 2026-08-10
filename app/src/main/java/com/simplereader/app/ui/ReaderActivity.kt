package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
    private lateinit var readerScrollView: NestedScrollView
    private lateinit var continuousTextView: TextView
    private lateinit var pagedReaderView: PagedReaderView
    private lateinit var progressLabel: TextView
    private lateinit var readerControls: LinearLayout
    private lateinit var readerSettingsPanel: LinearLayout
    private lateinit var progressSeekBar: SeekBar

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
        readerScrollView = findViewById(R.id.readerScrollView)
        continuousTextView = findViewById(R.id.contentView)
        pagedReaderView = findViewById(R.id.pagedReaderView)
        progressLabel = findViewById(R.id.readerProgressLabel)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        progressSeekBar = findViewById(R.id.fontSizeSeekBar)
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
        saveProgress()
        super.onPause()
    }

    override fun onDestroy() {
        paginationJob?.cancel()
        continuousRenderJob?.cancel()
        pagedReaderView.cancelNavigation()
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
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
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
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
            if (pageTurnMode == TURN_MODE_VERTICAL) {
                readerScrollView.smoothScrollBy(0, direction * readerScrollView.height.coerceAtLeast(1))
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
                bindHorizontalPages()
                updateProgressUi()
                saveProgress()
            } else {
                bindHorizontalPages()
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
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { showCatalogBookmarkPanelV600() }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener {
            readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
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
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val stableOffset = preserveOffset
                    ?: progress?.startOffset
                    ?: progress?.txtCharOffset
                    ?: progress?.position?.toIntOrNull()
                val target = when {
                    stableOffset != null -> paged.pageForOffset(stableOffset).globalPageIndex
                    progress?.globalPageIndex != null && progress.globalPageIndex in paged.pages.indices -> progress.globalPageIndex
                    else -> 0
                }
                currentPageIndex = target.coerceIn(0, paged.pages.lastIndex)
                showActiveReader()
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
                showContinuousFallback(error.message ?: "分页失败")
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

    private fun showContinuousBook(targetOffset: Int? = null) {
        val paged = readerBook ?: return
        val settings = layoutSettings ?: return
        pagedReaderView.cancelNavigation()
        pagedReaderView.visibility = View.GONE
        readerScrollView.visibility = View.VISIBLE
        continuousTextView.textSize = readerTextSizeSp
        continuousTextView.setLineSpacing(0f, settings.lineSpacingMultiplier)
        continuousTextView.setTextColor(activePalette().textColor)
        continuousTextView.background = ColorDrawable(Color.TRANSPARENT)
        readerScrollView.background = activeBackgroundDrawable()
        val offset = targetOffset ?: paged.pages.getOrNull(currentPageIndex)?.startOffset ?: 0
        renderContinuousWindow(offset)
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
        pagedReaderView.visibility = View.GONE
        readerScrollView.visibility = View.VISIBLE
        continuousWindowStartOffset = 0
        continuousWindowEndOffset = loaded.text.length.coerceAtMost(CONTINUOUS_FALLBACK_CHARS)
        continuousTextView.text = loaded.text.substring(continuousWindowStartOffset, continuousWindowEndOffset)
        continuousTextView.textSize = readerTextSizeSp
        continuousTextView.setTextColor(activePalette().textColor)
        readerScrollView.background = activeBackgroundDrawable()
        progressLabel.text = "可读模式"
        Toast.makeText(this, "分页失败，已打开有限文本窗口：$reason", Toast.LENGTH_LONG).show()
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
        if (continuousWindowShiftPosted || continuousTextView.height <= 0 || readerScrollView.height <= 0) return
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
            readerScrollView.smoothScrollBy(0, direction * readerScrollView.height.coerceAtLeast(1))
        } else {
            pagedReaderView.turn(direction)
        }
    }

    private fun jumpToPage(index: Int, animated: Boolean, hit: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        activeSearchHit = hit
        currentPageIndex = index.coerceIn(0, pages.lastIndex)
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            val targetOffset = pages[currentPageIndex].startOffset
            if (targetOffset !in continuousWindowStartOffset until continuousWindowEndOffset) {
                renderContinuousWindow(targetOffset)
            } else {
                applyContinuousHighlight()
                scrollContinuousToOffset(targetOffset)
            }
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
            val listView = ListView(this@ReaderActivity)
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
                    val labels = visible.mapIndexed { rowIndex, (chapterIndex, chapter) ->
                        val page = paged.firstPageOfChapter(chapterIndex)
                        val label = "${rowIndex + 1}.${chapter.title}\n第 ${page + 1}/${paged.pages.size} 页"
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

    private fun resolveBookmarkPage(bookmark: Bookmark, paged: ReaderBook): Int = when {
        bookmark.globalPageIndex != null && bookmark.globalPageIndex in paged.pages.indices -> bookmark.globalPageIndex
        bookmark.startOffset != null -> paged.pageForOffset(bookmark.startOffset).globalPageIndex
        else -> paged.pageForOffset(bookmark.position.toIntOrNull() ?: 0).globalPageIndex
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
        val offset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset ?: 0
        readerTextSizeSp = (readerTextSizeSp + delta).coerceIn(12f, 36f)
        applyReaderContentPadding()
        savePreferences()
        updateSettingsLabels()
        paginateAndDisplay(offset)
    }

    private fun setTurnMode(mode: String) {
        if (pageTurnMode == mode) return
        val offset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset ?: 0
        pageTurnMode = mode
        savePreferences()
        updateSettingsLabels()
        if (readerBook != null) {
            if (mode == TURN_MODE_VERTICAL) showContinuousBook(offset) else showHorizontalBook()
        }
    }

    private fun selectQuickColor(colorId: String) {
        backgroundSelection = ReaderBackgrounds.selection(ReaderBackgrounds.Category.COLOR, colorId)
        ReaderAppearance.setMode(this, ReaderAppearance.MODE_DAY)
        savePreferences()
        applyReaderAppearance(rebindPages = true)
    }

    private fun currentBackgroundSelection() = ReaderBackgrounds.validated(backgroundSelection)

    private fun activePalette(): ReaderAppearance.Palette = if (ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT) {
        ReaderAppearance.palette(this)
    } else {
        val selection = currentBackgroundSelection()
        ReaderAppearance.Palette(
            ReaderBackgrounds.representativeColor(selection),
            ReaderBackgrounds.textColor(selection)
        )
    }

    private fun activeBackgroundDrawable() = if (ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT) {
        ReaderBackgrounds.nightDrawable(this)
    } else {
        ReaderBackgrounds.drawable(this, currentBackgroundSelection())
    }

    private fun applyReaderAppearance(rebindPages: Boolean) {
        val palette = activePalette()
        readerScrollView.background = activeBackgroundDrawable()
        continuousTextView.setTextColor(palette.textColor)
        continuousTextView.background = ColorDrawable(Color.TRANSPARENT)
        findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()
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
        val category = prefs.getString(PREF_BACKGROUND_CATEGORY, null)
            ?.let { runCatching { ReaderBackgrounds.Category.valueOf(it) }.getOrNull() }
            ?: ReaderBackgrounds.Category.COLOR
        val optionId = prefs.getString(PREF_BACKGROUND_OPTION, null)
            ?: ReaderBackgrounds.DEFAULT_COLOR_ID
        backgroundSelection = ReaderBackgrounds.validated(ReaderBackgrounds.Selection(category, optionId))
    }

    private fun savePreferences() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE).edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSizeSp)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
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
            .toInt()
            .coerceAtLeast(1)
        // Upper limit = notification/status-bar bottom + one complete text character.
        // This is intentionally not measured from the physical top edge of the screen.
        val topPaddingPx = statusBarInsetPx + oneCharacterPx
        val bottomPaddingPx = navigationBarInsetPx + oneCharacterPx
        continuousTextView.setPadding(
            continuousTextView.paddingLeft,
            topPaddingPx,
            continuousTextView.paddingRight,
            bottomPaddingPx
        )
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
        private const val PREF_BACKGROUND_CATEGORY = "reader_background_category"
        private const val PREF_BACKGROUND_OPTION = "reader_background_option"
        private const val TURN_MODE_OVERLAP = "overlap"
        private const val TURN_MODE_SIMULATE = "simulate"
        private const val TURN_MODE_HORIZONTAL = "horizontal"
        private const val TURN_MODE_VERTICAL = "vertical"
        private const val TURN_MODE_FADE = "fade"
        private const val MENU_ADD_BOOKMARK = 2
        private const val MENU_SEARCH = 5
        private const val CONTINUOUS_PAGES_BEFORE = 24
        private const val CONTINUOUS_PAGES_AFTER = 48
        private const val CONTINUOUS_SHIFT_DELAY_MS = 200L
        private const val CONTINUOUS_SHIFT_IDLE_MS = 180L
        private const val CONTINUOUS_FALLBACK_CHARS = 240_000
    }
}
