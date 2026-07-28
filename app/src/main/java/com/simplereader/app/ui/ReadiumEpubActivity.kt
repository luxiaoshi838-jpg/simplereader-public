package com.simplereader.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Bookmark
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.ReadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.util.Locale

class ReadiumEpubActivity : AppCompatActivity() {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var webView: WebView
    private lateinit var loadingText: TextView
    private lateinit var readerControls: LinearLayout
    private lateinit var readerSettingsPanel: LinearLayout
    private lateinit var readerProgressLabel: TextView
    private lateinit var progressSeekBar: SeekBar
    private lateinit var fontSizeLabel: TextView

    private var bookId: Long = 0L
    private var book: Book? = null
    private var currentCfi: String = ""
    private var currentHref: String = ""
    private var currentTitle: String = ""
    private var currentFraction: Double = 0.0
    private var initialCfi: String = ""
    private var initialFraction: Double = 0.0
    private var tocItems: List<TocItem> = emptyList()
    private var chromeVisible = false
    private var userDraggingProgress = false
    private var saveJob: Job? = null
    private var fontScale = 100
    private var nightMode = false
    private var readerBackground = Color.rgb(245, 233, 200)
    private var readerForeground = Color.rgb(59, 52, 40)
    private var searchDialog: AlertDialog? = null
    private var searchResults: List<EpubSearchHit> = emptyList()
    private var activeSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_readium_epub)
        supportActionBar?.hide()

        database = SimpleReaderDatabase.getDatabase(this)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        bindViews()
        configureWebView()
        setupControls()
        loadBook()
    }

    override fun onStop() {
        super.onStop()
        saveProgressNow()
    }

    override fun onDestroy() {
        saveProgressNow()
        webView.destroy()
        super.onDestroy()
    }

    private fun bindViews() {
        webView = findViewById(R.id.epubWebView)
        loadingText = findViewById(R.id.loadingText)
        readerControls = findViewById(R.id.readerControls)
        readerSettingsPanel = findViewById(R.id.readerSettingsPanel)
        readerProgressLabel = findViewById(R.id.readerProgressLabel)
        progressSeekBar = findViewById(R.id.fontSizeSeekBar)
        fontSizeLabel = findViewById(R.id.fontSizeLabel)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.domStorageEnabled = true
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        webView.addJavascriptInterface(ReaderBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url ?: return null
                if (url.scheme == "https" && url.host == BOOK_HOST && url.path == "/book.epub") {
                    return serveBook()
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        webView.setOnTouchListener { _, event ->
            if (chromeVisible) {
                if (event.action == MotionEvent.ACTION_UP && isCenterTap(event)) {
                    setChromeVisible(false)
                }
                true
            } else if (event.action == MotionEvent.ACTION_UP && isCenterTap(event)) {
                setChromeVisible(true)
                true
            } else {
                false
            }
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
    }

    private fun setupControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { showCatalog() }
        findViewById<TextView>(R.id.nightButton).setOnClickListener { toggleTheme() }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener { toggleReaderSettingsPanel() }
        findViewById<TextView>(R.id.previousChapterButton).setOnClickListener { runJs("SimpleReader.goChapter(-1)") }
        findViewById<TextView>(R.id.nextChapterButton).setOnClickListener { runJs("SimpleReader.goChapter(1)") }
        findViewById<TextView>(R.id.fontDecreaseButton).setOnClickListener { changeFont(-8) }
        findViewById<TextView>(R.id.fontIncreaseButton).setOnClickListener { changeFont(8) }
        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40), night = false)
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            applyReaderPalette(Color.rgb(218, 238, 205), Color.rgb(45, 61, 42), night = false)
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            applyReaderPalette(Color.WHITE, Color.rgb(38, 38, 38), night = false)
        }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            applyReaderPalette(Color.rgb(31, 31, 31), Color.rgb(222, 218, 209), night = true)
        }
        findViewById<TextView>(R.id.volumeKeyToggleButton).setOnClickListener {
            Toast.makeText(this, "EPUB 连续滚动模式下音量键翻页沿用系统滚动", Toast.LENGTH_SHORT).show()
        }
        listOf(
            R.id.turnModeOverlapButton,
            R.id.turnModeSimulateButton,
            R.id.turnModeHorizontalButton,
            R.id.turnModeFadeButton
        ).forEach { id ->
            findViewById<TextView>(id).setOnClickListener {
                Toast.makeText(this, "EPUB 图片阅读当前使用上下连续滑动", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.turnModeVerticalButton).setOnClickListener {
            Toast.makeText(this, "阅读模式：连续滚动", Toast.LENGTH_SHORT).show()
        }
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) readerProgressLabel.text = progressLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userDraggingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userDraggingProgress = false
                val fraction = (seekBar?.progress ?: 0) / 1000.0
                runJs("SimpleReader.goToProgress($fraction)")
                setChromeVisible(false)
            }
        })
    }

    private fun loadBook() {
        lifecycleScope.launch {
            book = withContext(Dispatchers.IO) { database.bookDao().getBook(bookId) }
            val selected = book
            if (selected == null) {
                showFatalError("书籍不存在")
                return@launch
            }
            title = selected.title
            supportActionBar?.title = selected.title
            val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
            initialCfi = progress?.position?.takeIf { progress.locatorType == LOCATOR_TYPE } ?: ""
            initialFraction = progress?.epubProgressFraction?.toDouble() ?: 0.0
            webView.loadUrl(READER_URL)
        }
    }

    private fun serveBook(): WebResourceResponse? {
        val stream = openBookInputStream() ?: return null
        return WebResourceResponse("application/epub+zip", null, stream).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            )
        }
    }

    private fun openBookInputStream(): InputStream? {
        val filePath = book?.filePath ?: return null
        return runCatching {
            when {
                filePath.startsWith("content://", ignoreCase = true) ->
                    contentResolver.openInputStream(Uri.parse(filePath))
                filePath.startsWith("file://", ignoreCase = true) ->
                    contentResolver.openInputStream(Uri.parse(filePath))
                else -> File(filePath).inputStream()
            }
        }.getOrNull()
    }

    private fun startReaderIfReady() {
        val cfi = initialCfi.replace("\\", "\\\\").replace("'", "\\'")
        val night = if (nightMode) "true" else "false"
        runJs("SimpleReader.openBook('$BOOK_URL', '$cfi', $initialFraction, $fontScale, $night)")
    }

    private fun runJs(script: String) {
        webView.evaluateJavascript(script, null)
    }

    private fun updateLocation(cfi: String, href: String, title: String, fraction: Double) {
        currentCfi = cfi
        currentHref = href
        currentTitle = title.ifBlank { titleForHref(href) }.ifBlank { book?.title.orEmpty() }
        currentFraction = fraction.coerceIn(0.0, 1.0)
        supportActionBar?.title = currentTitle
        if (!userDraggingProgress) {
            val progress = (currentFraction * 1000).toInt().coerceIn(0, 1000)
            progressSeekBar.progress = progress
            readerProgressLabel.text = progressLabel(progress)
        }
        showChapterTitleBriefly()
        scheduleProgressSave()
    }

    private fun showChapterTitleBriefly() {
        supportActionBar?.show()
        readerControls.postDelayed({
            if (!chromeVisible) supportActionBar?.hide()
        }, 1400)
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        readerControls.visibility = if (visible) View.VISIBLE else View.GONE
        readerProgressLabel.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) supportActionBar?.show() else supportActionBar?.hide()
    }

    private fun toggleReaderSettingsPanel() {
        readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun showCatalog() {
        showCatalogBookmarkPanel(showBookmarksFirst = false)
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
            val tabs = LinearLayout(this@ReadiumEpubActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
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
                    val labels = if (tocItems.isEmpty()) {
                        listOf("暂无目录")
                    } else {
                        tocItems.map { "${"  ".repeat(it.depth)}${it.title}" }
                    }
                    listView.adapter = ArrayAdapter(this@ReadiumEpubActivity, android.R.layout.simple_list_item_1, labels)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        tocItems.getOrNull(which)?.href?.let { href ->
                            runJs("SimpleReader.goToHref('${escapeJs(href)}')")
                            setChromeVisible(false)
                        }
                        dialog?.dismiss()
                    }
                    listView.setOnItemLongClickListener(null)
                } else {
                    val labels = if (bookmarks.isEmpty()) {
                        listOf("暂无书签")
                    } else {
                        bookmarks.map { bookmarkListLabel(it) }
                    }
                    listView.adapter = ArrayAdapter(this@ReadiumEpubActivity, android.R.layout.simple_list_item_1, labels)
                    listView.setOnItemClickListener { _, _, which, _ ->
                        bookmarks.getOrNull(which)?.let { bookmark ->
                            runJs("SimpleReader.goToCfi('${escapeJs(bookmark.position)}')")
                            setChromeVisible(false)
                        }
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
                .setTitle(if (showingCatalog) "目录" else "书签")
                .setView(container)
                .create()
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
            dialog.window?.let { window ->
                window.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                window.setLayout(
                    (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    private fun toggleTheme() {
        if (nightMode) {
            applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40), night = false)
        } else {
            applyReaderPalette(Color.rgb(31, 31, 31), Color.rgb(222, 218, 209), night = true)
        }
    }

    private fun applyReaderPalette(background: Int, foreground: Int, night: Boolean) {
        nightMode = night
        readerBackground = background
        readerForeground = foreground
        findViewById<TextView>(R.id.nightButton).text = if (nightMode) "☀" else "☾"
        window.decorView.setBackgroundColor(readerBackground)
        webView.setBackgroundColor(readerBackground)
        readerProgressLabel.setTextColor(if (nightMode) Color.rgb(210, 206, 196) else Color.rgb(107, 98, 87))
        runJs("SimpleReader.setNight(${if (nightMode) "true" else "false"})")
        runJs("SimpleReader.setPalette('${colorHex(readerBackground)}', '${colorHex(readerForeground)}')")
    }

    private fun changeFont(delta: Int) {
        fontScale = (fontScale + delta).coerceIn(80, 180)
        fontSizeLabel.text = ((fontScale / 100f) * 20f).toInt().toString()
        runJs("SimpleReader.setFontScale($fontScale)")
    }

    private fun progressLabel(progress: Int): String {
        return "${(progress / 10).coerceIn(0, 100)}%"
    }

    private fun colorHex(color: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and color)
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
                searchResults.mapIndexed { index, hit ->
                    "${index + 1}. ${hit.title.ifBlank { "匹配位置" }}\n${hit.preview}"
                }
            }
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
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
            searchResults = emptyList()
            renderResults("正在搜索……")
            runJs("SimpleReader.searchBook('${escapeJs(query)}')")
        }
        listView.setOnItemClickListener { _, _, which, _ ->
            val hit = searchResults.getOrNull(which) ?: return@setOnItemClickListener
            runJs("SimpleReader.goToSearchResult('${escapeJs(hit.href)}', '${escapeJs(activeSearchQuery)}')")
            searchDialog?.dismiss()
            setChromeVisible(false)
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

    private fun updateSearchResults(json: String) {
        searchResults = runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                EpubSearchHit(
                    title = item.optString("title", ""),
                    href = item.optString("href", ""),
                    preview = item.optString("preview", "")
                )
            }
        }.getOrDefault(emptyList())
        val dialog = searchDialog ?: return
        val listView = dialog.findViewById<ListView>(android.R.id.list)
        if (listView == null) {
            dialog.dismiss()
            showContentSearch()
        }
    }

    private fun addBookmark() {
        val cfi = currentCfi.takeIf { it.isNotBlank() }
        if (cfi == null) {
            Toast.makeText(this, "当前位置还未加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        val label = "${currentTitle.ifBlank { book?.title.orEmpty() }}\n${progressLabel((currentFraction * 1000).toInt())}"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookmarkDao().insert(
                    Bookmark(
                        bookId = bookId,
                        position = cfi,
                        content = label
                    )
                )
            }
            Toast.makeText(this@ReadiumEpubActivity, "已添加书签", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bookmarkListLabel(bookmark: Bookmark): String {
        val preview = bookmark.content.replace(Regex("\\s+"), " ").trim().ifBlank { "书签" }
        return preview.take(80)
    }

    private fun tabText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setTextColor(Color.rgb(42, 39, 31))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun titleForHref(href: String): String {
        val normalized = href.substringBefore('#')
        return tocItems.firstOrNull { it.href.substringBefore('#') == normalized }?.title.orEmpty()
    }

    private fun scheduleProgressSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(600)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val cfi = currentCfi.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            database.readProgressDao().insert(
                ReadProgress(
                    bookId = bookId,
                    position = cfi,
                    locatorType = LOCATOR_TYPE,
                    epubChapterHref = currentHref,
                    epubProgressFraction = currentFraction.toFloat(),
                    updateTime = System.currentTimeMillis()
                )
            )
            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
        }
    }

    private fun showFatalError(message: String) {
        loadingText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun isCenterTap(event: MotionEvent): Boolean {
        val width = webView.width.toFloat().coerceAtLeast(1f)
        val height = webView.height.toFloat().coerceAtLeast(1f)
        return event.x in (width * 0.25f)..(width * 0.75f) &&
            event.y in (height * 0.25f)..(height * 0.75f)
    }

    inner class ReaderBridge {
        @JavascriptInterface fun onShellReady() {
            runOnUiThread { startReaderIfReady() }
        }

        @JavascriptInterface fun onEngineReady() {
            runOnUiThread { loadingText.visibility = View.GONE }
        }

        @JavascriptInterface fun onBookReady(title: String) {
            runOnUiThread {
                if (title.isNotBlank()) supportActionBar?.title = title
            }
        }

        @JavascriptInterface fun onTocReady(json: String) {
            runOnUiThread {
                tocItems = runCatching {
                    val array = JSONArray(json)
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        TocItem(
                            title = item.optString("title", "未命名章节"),
                            href = item.optString("href", ""),
                            depth = item.optInt("depth", 0)
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }

        @JavascriptInterface fun onLocationChanged(cfi: String, href: String, title: String, fraction: Double) {
            runOnUiThread { updateLocation(cfi, href, title, fraction) }
        }

        @JavascriptInterface fun onReaderError(message: String) {
            runOnUiThread { showFatalError(message) }
        }

        @JavascriptInterface fun onCenterTap() {
            runOnUiThread { setChromeVisible(!chromeVisible) }
        }

        @JavascriptInterface fun onSearchReady(json: String) {
            runOnUiThread {
                searchResults = runCatching {
                    val array = JSONArray(json)
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        EpubSearchHit(
                            title = item.optString("title", ""),
                            href = item.optString("href", ""),
                            preview = item.optString("preview", "")
                        )
                    }
                }.getOrDefault(emptyList())
                val existing = searchDialog
                if (existing != null) {
                    existing.dismiss()
                    showContentSearch()
                }
            }
        }
    }

    data class TocItem(val title: String, val href: String, val depth: Int)
    data class EpubSearchHit(val title: String, val href: String, val preview: String)

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val BOOK_HOST = "simplereader.local"
        private const val BOOK_URL = "https://simplereader.local/book.epub"
        private const val READER_URL = "file:///android_asset/epubjs/reader.html"
        private const val LOCATOR_TYPE = "EPUB_CFI"
        private const val MENU_ADD_BOOKMARK = 1
        private const val MENU_SEARCH = 5
    }
}
