package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.Bookmark
import com.simplereader.app.data.entity.ReadProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.Search
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.asset.FileAsset
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.streamer.Streamer

@OptIn(ExperimentalReadiumApi::class, Search::class)
class ReadiumEpubActivity :
    AppCompatActivity(),
    EpubNavigatorFragment.Listener,
    EpubNavigatorFragment.PaginationListener {

    private lateinit var database: SimpleReaderDatabase
    private lateinit var readiumContainer: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var readerControls: LinearLayout
    private lateinit var readerSettingsPanel: LinearLayout
    private lateinit var readerProgressLabel: TextView
    private lateinit var progressSeekBar: SeekBar
    private lateinit var fontSizeLabel: TextView

    private var bookId: Long = 0L
    private var book: Book? = null
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var positions: List<Locator> = emptyList()
    private var currentLocator: Locator? = null
    private var chromeVisible = false
    private var userDraggingProgress = false
    private var saveJob: Job? = null
    private var fontScale = 1.0
    private var nightMode = false
    private var searchDialog: AlertDialog? = null
    private var searchResults: List<Locator> = emptyList()
    private var activeSearchQuery = ""
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartedInReader = false
    private var chromeTouchConsumed = false
    private var boundaryNavigationUntil = 0L
    private var boundaryNavigationHref = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        setContentView(R.layout.activity_readium_epub)
        supportActionBar?.hide()

        database = SimpleReaderDatabase.getDatabase(this)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        bindViews()
        setupControls()
        loadBook()
    }

    override fun onStop() {
        super.onStop()
        saveProgressNow()
    }

    override fun onDestroy() {
        saveProgressNow()
        publication?.close()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isReaderArea(event.rawX.toInt(), event.rawY.toInt())) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    touchStartedInReader = true
                    chromeTouchConsumed = chromeVisible && !isVisibleChromeArea(event.rawX.toInt(), event.rawY.toInt())
                    if (chromeTouchConsumed) return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (chromeTouchConsumed) return true
                }

                MotionEvent.ACTION_UP -> {
                    if (chromeTouchConsumed) {
                        if (isCentralReaderPoint(event.rawX, event.rawY)) {
                            setChromeVisible(false)
                        }
                        chromeTouchConsumed = false
                        return true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    chromeTouchConsumed = false
                }
            }
        }

        val handled = super.dispatchTouchEvent(event)

        if (!chromeVisible && touchStartedInReader && event.actionMasked == MotionEvent.ACTION_UP) {
            handleVerticalChapterBoundarySwipe(event.rawX - touchStartX, event.rawY - touchStartY)
            touchStartedInReader = false
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchStartedInReader = false
        }

        return handled
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索")
            .setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        val addItem = menu.add(Menu.NONE, MENU_ADD_BOOKMARK, Menu.NONE, "添加书签")
        addItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        addItem.actionView = TextView(this).apply {
            text = "签"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            contentDescription = "添加书签"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(239, 122, 40))
            }
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { addBookmark() }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            MENU_SEARCH -> {
                showContentSearch()
                true
            }
            MENU_ADD_BOOKMARK -> {
                addBookmark()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun bindViews() {
        readiumContainer = findViewById(R.id.readiumContainer)
        loadingText = findViewById(R.id.loadingText)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        readerProgressLabel = findViewById(R.id.readerProgressLabel)
        progressSeekBar = findViewById(R.id.fontSizeSeekBar)
        fontSizeLabel = findViewById(R.id.fontSizeLabel)
    }

    private fun setupControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { showCatalogBookmarkPanel(false) }
        findViewById<TextView>(R.id.nightButton).setOnClickListener { toggleTheme() }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener { toggleReaderSettingsPanel() }
        findViewById<TextView>(R.id.previousChapterButton).setOnClickListener { goChapter(-1) }
        findViewById<TextView>(R.id.nextChapterButton).setOnClickListener { goChapter(1) }
        findViewById<TextView>(R.id.fontDecreaseButton).setOnClickListener { changeFont(-0.08) }
        findViewById<TextView>(R.id.fontIncreaseButton).setOnClickListener { changeFont(0.08) }
        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40), false)
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            applyReaderPalette(Color.rgb(218, 238, 205), Color.rgb(45, 61, 42), false)
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            applyReaderPalette(Color.WHITE, Color.rgb(38, 38, 38), false)
        }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            applyReaderPalette(Color.rgb(35, 35, 35), Color.rgb(222, 218, 209), true)
        }
        findViewById<TextView>(R.id.volumeKeyToggleButton).setOnClickListener {
            Toast.makeText(this, "EPUB 连续滑动模式下音量键沿用系统滚动", Toast.LENGTH_SHORT).show()
        }
        listOf(
            R.id.turnModeOverlapButton,
            R.id.turnModeSimulateButton,
            R.id.turnModeHorizontalButton,
            R.id.turnModeFadeButton
        ).forEach { id ->
            findViewById<TextView>(id).setOnClickListener {
                Toast.makeText(this, "EPUB 当前使用上下连续滑动", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.turnModeVerticalButton).setOnClickListener {
            Toast.makeText(this, "阅读模式：上下连续滑动", Toast.LENGTH_SHORT).show()
        }
        progressSeekBar.max = 1000
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) readerProgressLabel.text = pageLabelForProgress(progress / 1000.0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userDraggingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userDraggingProgress = false
                val fraction = (seekBar?.progress ?: 0) / 1000.0
                lifecycleScope.launch {
                    val locator = publication?.locateProgression(fraction)
                    if (locator != null) navigator?.go(locator, animated = false)
                    setChromeVisible(false)
                }
            }
        })
    }

    private fun loadBook() {
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) { database.bookDao().getBook(bookId) }
            book = selected
            if (selected == null) {
                showFatalError("书籍不存在")
                return@launch
            }

            title = selected.title
            supportActionBar?.title = selected.title
            loadingText.text = "正在打开 EPUB..."

            val result = runCatching {
                val file = withContext(Dispatchers.IO) { materializeEpubFile(selected) }
                val opened = withContext(Dispatchers.IO) {
                    Streamer(this@ReadiumEpubActivity)
                        .open(FileAsset(file), allowUserInteraction = false)
                        .getOrThrow()
                }
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val initialLocator = initialLocatorFor(opened, progress)
                positions = withContext(Dispatchers.IO) { opened.positions() }
                opened to initialLocator
            }

            val (openedPublication, initialLocator) = result.getOrElse {
                showFatalError("EPUB 打开失败：${it.message ?: "未知错误"}")
                return@launch
            }

            publication = openedPublication
            supportFragmentManager.fragmentFactory =
                EpubNavigatorFactory(openedPublication).createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = currentPreferences(),
                    listener = this@ReadiumEpubActivity,
                    paginationListener = this@ReadiumEpubActivity,
                    configuration = EpubNavigatorFragment.Configuration(
                        shouldApplyInsetsPadding = false
                    )
                )
            supportFragmentManager.commitNow {
                replace(R.id.readiumContainer, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            }
            navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
            observeNavigator()
            loadingText.visibility = View.GONE
            readerProgressLabel.text = pageLabelForLocator(initialLocator)
            invalidateOptionsMenu()
        }
    }

    private suspend fun initialLocatorFor(opened: Publication, progress: ReadProgress?): Locator? {
        val saved = progress?.position
            ?.takeIf { progress.locatorType == LOCATOR_TYPE }
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        if (saved != null) return saved
        return progress?.epubProgressFraction
            ?.toDouble()
            ?.coerceIn(0.0, 1.0)
            ?.let { opened.locateProgression(it) }
    }

    private fun observeNavigator() {
        navigator?.currentLocator
            ?.onEach { updateLocation(it) }
            ?.launchIn(lifecycleScope)
    }

    private fun updateLocation(locator: Locator) {
        currentLocator = locator
        supportActionBar?.title = locator.title?.takeIf { it.isNotBlank() } ?: book?.title.orEmpty()
        if (!userDraggingProgress) {
            val fraction = locator.locations.totalProgression
                ?: locator.locations.progression
                ?: 0.0
            progressSeekBar.progress = (fraction.coerceIn(0.0, 1.0) * 1000).toInt()
            readerProgressLabel.text = pageLabelForLocator(locator)
        }
        scheduleProgressSave()
    }

    private fun currentPreferences(): EpubPreferences =
        EpubPreferences(
            scroll = true,
            publisherStyles = true,
            fontSize = fontScale,
            theme = if (nightMode) Theme.DARK else Theme.SEPIA,
            verticalText = false,
            backgroundColor = ReadiumColor(if (nightMode) Color.rgb(35, 35, 35) else Color.rgb(245, 233, 200)),
            textColor = ReadiumColor(if (nightMode) Color.rgb(222, 218, 209) else Color.rgb(59, 52, 40))
        )

    private fun applyPreferences() {
        navigator?.submitPreferences(currentPreferences())
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        readerControls.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) {
            supportActionBar?.title = currentLocator?.title?.takeIf { it.isNotBlank() } ?: book?.title.orEmpty()
            supportActionBar?.show()
        } else {
            supportActionBar?.hide()
        }
    }

    override fun onTap(point: PointF): Boolean {
        val width = readiumContainer.width.toFloat().coerceAtLeast(1f)
        val height = readiumContainer.height.toFloat().coerceAtLeast(1f)
        if (point.x in width * 0.25f..width * 0.75f && point.y in height * 0.25f..height * 0.75f) {
            setChromeVisible(!chromeVisible)
            return true
        }
        return false
    }

    private fun isCentralReaderPoint(rawX: Float, rawY: Float): Boolean {
        val rect = android.graphics.Rect()
        if (!readiumContainer.getGlobalVisibleRect(rect)) return false
        val x = rawX - rect.left
        val y = rawY - rect.top
        val width = rect.width().toFloat().coerceAtLeast(1f)
        val height = rect.height().toFloat().coerceAtLeast(1f)
        return x in width * 0.25f..width * 0.75f && y in height * 0.25f..height * 0.75f
    }

    private fun isReaderArea(rawX: Int, rawY: Int): Boolean {
        val rect = android.graphics.Rect()
        return readiumContainer.getGlobalVisibleRect(rect) && rect.contains(rawX, rawY)
    }

    private fun isVisibleChromeArea(rawX: Int, rawY: Int): Boolean {
        val rect = android.graphics.Rect()
        return (readerControls.visibility == View.VISIBLE &&
            readerControls.getGlobalVisibleRect(rect) &&
            rect.contains(rawX, rawY)) ||
            (readerSettingsPanel.visibility == View.VISIBLE &&
                readerSettingsPanel.getGlobalVisibleRect(rect) &&
                rect.contains(rawX, rawY))
    }

    private fun handleVerticalChapterBoundarySwipe(deltaX: Float, deltaY: Float) {
        val now = System.currentTimeMillis()
        if (now < boundaryNavigationUntil) {
            return
        }
        if (kotlin.math.abs(deltaY) < dp(140) || kotlin.math.abs(deltaY) < kotlin.math.abs(deltaX) * 1.8f) {
            return
        }
        val locator = currentLocator ?: return
        val href = normalizedHref(locator.href)
        if (href.isBlank() || href == boundaryNavigationHref) {
            return
        }
        val progression = locator.locations.progression ?: return
        when {
            deltaY < 0 && progression >= 0.985 -> {
                boundaryNavigationHref = href
                boundaryNavigationUntil = now + BOUNDARY_NAVIGATION_COOLDOWN_MS
                navigator?.goForward(animated = true) {
                    boundaryNavigationHref = ""
                }
            }
            deltaY > 0 && progression <= 0.015 -> {
                boundaryNavigationHref = href
                boundaryNavigationUntil = now + BOUNDARY_NAVIGATION_COOLDOWN_MS
                navigator?.goBackward(animated = true) {
                    boundaryNavigationHref = ""
                }
            }
        }
    }

    override fun onJumpToLocator(locator: Locator) {
        updateLocation(locator)
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        updateLocation(locator)
    }

    override fun onPageLoaded() {
        currentLocator?.let { updateLocation(it) }
    }

    private fun toggleReaderSettingsPanel() {
        readerSettingsPanel.visibility =
            if (readerSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleTheme() {
        if (nightMode) {
            applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40), false)
        } else {
            applyReaderPalette(Color.rgb(35, 35, 35), Color.rgb(222, 218, 209), true)
        }
    }

    private fun applyReaderPalette(background: Int, foreground: Int, night: Boolean) {
        nightMode = night
        findViewById<TextView>(R.id.nightButton).text = if (nightMode) "☀" else "☾"
        window.decorView.setBackgroundColor(background)
        readiumContainer.setBackgroundColor(background)
        readerProgressLabel.setTextColor(if (nightMode) Color.rgb(210, 206, 196) else Color.rgb(107, 98, 87))
        applyPreferences()
    }

    private fun changeFont(delta: Double) {
        fontScale = (fontScale + delta).coerceIn(0.8, 1.8)
        fontSizeLabel.text = ((fontScale * 20).toInt()).toString()
        applyPreferences()
    }

    private fun showCatalogBookmarkPanel(showBookmarksFirst: Boolean = false) {
        lifecycleScope.launch {
            var bookmarks = withContext(Dispatchers.IO) {
                database.bookmarkDao().getBookmarks(bookId).first()
            }
            var showingCatalog = !showBookmarksFirst
            val container = LinearLayout(this@ReadiumEpubActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val tabs = LinearLayout(this@ReadiumEpubActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val catalogButton = tabText("目录")
            val bookmarkButton = tabText("书签")
            val listView = ListView(this@ReadiumEpubActivity)
            var dialog: AlertDialog? = null
            tabs.addView(catalogButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabs.addView(bookmarkButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(tabs)
            container.addView(listView)

            fun render() {
                catalogButton.isEnabled = !showingCatalog
                bookmarkButton.isEnabled = showingCatalog
                if (showingCatalog) {
                    val toc = flatToc(publication?.tableOfContents.orEmpty())
                    val currentHref = currentLocator?.href.orEmpty()
                    val highlightedIndex = toc.indexOfLast { it.link.href == currentHref }.coerceAtLeast(0)
                    val labels = if (toc.isEmpty()) {
                        listOf("暂无目录")
                    } else {
                        toc.mapIndexed { index, item -> tocLabel(index, item, index == highlightedIndex) }
                    }
                    listView.adapter = boundedLineAdapter(labels, highlightedIndex, 2)
                    if (highlightedIndex >= 0) listView.post { listView.setSelection(highlightedIndex) }
                    listView.setOnItemClickListener { _, _, which, _ ->
                        toc.getOrNull(which)?.let { item ->
                            publication?.locatorFromLink(item.link)?.let { navigator?.go(it, animated = false) }
                            setChromeVisible(false)
                            dialog?.dismiss()
                        }
                    }
                    listView.setOnItemLongClickListener(null)
                } else {
                    val labels = if (bookmarks.isEmpty()) listOf("暂无书签") else bookmarks.map(::bookmarkListLabel)
                    listView.adapter = boundedLineAdapter(labels, maxLines = 3)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)
                            ?.position
                            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                            ?.let { navigator?.go(it, animated = false) }
                        setChromeVisible(false)
                        dialog?.dismiss()
                    }
                    listView.setOnItemLongClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let { bookmark ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { database.bookmarkDao().delete(bookmark) }
                                bookmarks = bookmarks.filterNot { it.id == bookmark.id }
                                render()
                                Toast.makeText(this@ReadiumEpubActivity, "已删除书签", Toast.LENGTH_SHORT).show()
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
            dialog = AlertDialog.Builder(this@ReadiumEpubActivity)
                .setView(container)
                .create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(250, 246, 232)))
                window.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                window.setLayout(
                    (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    private fun showContentSearch() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "输入当前书内关键词"
            setSingleLine(true)
            setText(activeSearchQuery)
            setSelection(text.length)
        }
        val searchButton = TextView(this).apply {
            text = "搜索"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.rgb(239, 122, 40))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(110, 100, 84))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val listView = ListView(this)
        fun renderResults(message: String? = null) {
            status.text = message ?: if (searchResults.isEmpty()) "输入关键词后点击搜索" else "共 ${searchResults.size} 条结果"
            val labels = if (searchResults.isEmpty()) {
                listOf("暂无结果")
            } else {
                searchResults.mapIndexed { index, locator ->
                    val text = listOfNotNull(locator.text.before, locator.text.highlight, locator.text.after)
                        .joinToString("")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    "${index + 1}. ${locator.title ?: "匹配位置"}\n$text"
                }
            }
            listView.adapter = boundedLineAdapter(labels, maxLines = 2)
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(searchButton, LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(row)
        container.addView(status)
        container.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.63f).toInt()
            )
        )
        searchButton.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activeSearchQuery = query
            renderResults("正在搜索...")
            lifecycleScope.launch {
                searchResults = searchPublication(query)
                renderResults()
            }
        }
        listView.setOnItemClickListener { _, _, which, _ ->
            searchResults.getOrNull(which)?.let { locator ->
                navigator?.go(locator, animated = false)
                setChromeVisible(false)
            }
        }
        searchDialog = AlertDialog.Builder(this)
            .setTitle("书内搜索")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()
        searchDialog?.setOnDismissListener { searchDialog = null }
        renderResults()
        searchDialog?.show()
    }

    private suspend fun searchPublication(query: String): List<Locator> = withContext(Dispatchers.IO) {
        val pub = publication ?: return@withContext emptyList()
        val iterator = pub.search(query).getOrNull() ?: return@withContext emptyList()
        val results = mutableListOf<Locator>()
        try {
            while (results.size < SEARCH_LIMIT) {
                val page = iterator.next().getOrNull() ?: break
                results += page.locators
                if (page.locators.isEmpty()) break
            }
        } finally {
            iterator.close()
        }
        results
    }

    private fun addBookmark() {
        val locator = currentLocator ?: return
        val label = "${locator.title ?: book?.title.orEmpty()}\n${pageLabelForLocator(locator)}\n${locator.text.highlight.orEmpty()}"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookmarkDao().insert(
                    Bookmark(
                        bookId = bookId,
                        position = locator.toJSON().toString(),
                        content = label
                    )
                )
            }
            Toast.makeText(this@ReadiumEpubActivity, "已添加书签", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goChapter(direction: Int) {
        val toc = flatToc(publication?.tableOfContents.orEmpty())
        if (toc.isEmpty()) return
        val currentHref = currentLocator?.href.orEmpty()
        val currentIndex = toc.indexOfLast { it.link.href == currentHref }.coerceAtLeast(0)
        val target = toc.getOrNull((currentIndex + direction).coerceIn(0, toc.lastIndex)) ?: return
        publication?.locatorFromLink(target.link)?.let { navigator?.go(it, animated = false) }
    }

    private fun scheduleProgressSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(600)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val locator = currentLocator ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            database.readProgressDao().insert(
                ReadProgress(
                    bookId = bookId,
                    position = locator.toJSON().toString(),
                    locatorType = LOCATOR_TYPE,
                    epubChapterHref = locator.href,
                    epubProgressFraction = (locator.locations.totalProgression ?: 0.0).toFloat(),
                    updateTime = System.currentTimeMillis()
                )
            )
            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
        }
    }

    private fun pageLabelForLocator(locator: Locator?): String {
        val current = pageNumberForLocator(locator)
        val total = positions.size.coerceAtLeast(1)
        return "${current.coerceIn(1, total)}/$total"
    }

    private fun pageLabelForProgress(progress: Double): String {
        val total = positions.size.coerceAtLeast(1)
        val current = pageIndexForProgress(progress)
        return "${current.coerceIn(1, total)}/$total"
    }

    private fun pageIndexForProgress(progress: Double): Int {
        if (positions.isEmpty()) return 1
        val target = progress.coerceIn(0.0, 1.0)
        return positions.indexOfLast { (it.locations.totalProgression ?: 0.0) <= target }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: 1
    }

    private fun pageNumberForLocator(locator: Locator?): Int {
        if (locator == null || positions.isEmpty()) return 1
        val directPosition = locator.locations.position
        if (directPosition != null && directPosition > 1) return directPosition

        val href = normalizedHref(locator.href)
        val progression = locator.locations.progression ?: 0.0
        val sameResource = positions.withIndex()
            .filter { normalizedHref(it.value.href) == href }

        if (sameResource.isNotEmpty()) {
            val targetIndex = (progression.coerceIn(0.0, 1.0) * (sameResource.size - 1))
                .let { kotlin.math.ceil(it).toInt().coerceIn(0, sameResource.lastIndex) }
            return sameResource[targetIndex].value.locations.position
                ?: (sameResource[targetIndex].index + 1)
        }

        return directPosition ?: pageIndexForProgress(locator.locations.totalProgression ?: progression)
    }

    private fun pageNumberForLink(link: Link): Int {
        if (positions.isEmpty()) return 1
        val href = normalizedHref(link.href)
        val index = positions.indexOfFirst { normalizedHref(it.href) == href }
        return if (index >= 0) {
            positions[index].locations.position ?: (index + 1)
        } else {
            pageIndexForProgress(0.0)
        }
    }

    private fun normalizedHref(href: String): String =
        href.substringBefore('#').substringBefore('?').trimStart('/')

    private fun flatToc(links: List<Link>, depth: Int = 0): List<TocItem> =
        links.flatMap { link ->
            listOf(TocItem(link, depth)) + flatToc(link.children, depth + 1)
        }

    private fun tocLabel(index: Int, item: TocItem, highlighted: Boolean): CharSequence {
        val total = positions.size.coerceAtLeast(1)
        val page = "${pageNumberForLink(item.link).coerceIn(1, total)}/$total"
        val label = "${"  ".repeat(item.depth.coerceIn(0, 8))}${item.link.title ?: "章节 ${index + 1}"}\n$page"
        if (!highlighted) return label
        return SpannableString(label).apply {
            val titleEnd = label.indexOf('\n').let { if (it >= 0) it else label.length }
            setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.18f), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun bookmarkListLabel(bookmark: Bookmark): String =
        bookmark.content
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "书签" }
            .take(90)

    private fun boundedLineAdapter(
        labels: List<CharSequence>,
        highlightedIndex: Int = -1,
        maxLines: Int = 2
    ): ArrayAdapter<CharSequence> {
        return object : ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    this.maxLines = maxLines
                    ellipsize = TextUtils.TruncateAt.END
                    if (position == highlightedIndex) {
                        textSize = 16f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
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

    private fun tabText(textValue: String): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setTextColor(Color.rgb(42, 39, 31))
        }

    private fun materializeEpubFile(selected: Book): File {
        val cache = File(cacheDir, "readium_epub_cache").apply { mkdirs() }
        val target = File(cache, "${selected.id}_${selected.fileSize ?: 0}_${selected.lastModified ?: 0}.epub")
        if (target.exists() && target.length() > 0) return target

        val path = selected.filePath
        if (!path.startsWith("content://", ignoreCase = true) && !path.startsWith("file://", ignoreCase = true)) {
            val file = File(path)
            if (file.exists()) return file
        }

        val uri = Uri.parse(path)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法访问原 EPUB 文件")
        return target
    }

    private fun showFatalError(message: String) {
        loadingText.visibility = View.VISIBLE
        loadingText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    data class TocItem(val link: Link, val depth: Int)

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val LOCATOR_TYPE = "READIUM_EPUB_LOCATOR"
        private const val NAVIGATOR_TAG = "readium_epub_navigator"
        private const val MENU_ADD_BOOKMARK = 1
        private const val MENU_SEARCH = 5
        private const val SEARCH_LIMIT = 500
        private const val BOUNDARY_NAVIGATION_COOLDOWN_MS = 1100L
    }
}
