package com.simplereader.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.ReadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var chapterTitleText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressSeekBar: SeekBar

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
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        chapterTitleText = findViewById(R.id.chapterTitleText)
        progressText = findViewById(R.id.progressText)
        progressSeekBar = findViewById(R.id.progressSeekBar)
        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
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
            if (event.action == MotionEvent.ACTION_UP && isCenterTap(event)) {
                setChromeVisible(!chromeVisible)
                true
            } else {
                false
            }
        }
    }

    private fun setupControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { showCatalog() }
        findViewById<TextView>(R.id.nightButton).setOnClickListener { toggleTheme() }
        findViewById<TextView>(R.id.fontMinusButton).setOnClickListener { changeFont(-8) }
        findViewById<TextView>(R.id.fontPlusButton).setOnClickListener { changeFont(8) }
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) progressText.text = "${progress / 10}%"
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
            chapterTitleText.text = selected.title
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
        chapterTitleText.text = currentTitle
        if (!userDraggingProgress) {
            val progress = (currentFraction * 1000).toInt().coerceIn(0, 1000)
            progressSeekBar.progress = progress
            progressText.text = "${progress / 10}%"
        }
        showChapterTitleBriefly()
        scheduleProgressSave()
    }

    private fun showChapterTitleBriefly() {
        topBar.visibility = View.VISIBLE
        topBar.postDelayed({
            if (!chromeVisible) topBar.visibility = View.GONE
        }, 1400)
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        progressText.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun showCatalog() {
        if (tocItems.isEmpty()) {
            Toast.makeText(this, "暂无目录", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = tocItems.map { "${"  ".repeat(it.depth)}${it.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { _, which ->
                val href = tocItems[which].href.replace("\\", "\\\\").replace("'", "\\'")
                runJs("SimpleReader.goToHref('$href')")
                setChromeVisible(false)
            }
            .show()
    }

    private fun toggleTheme() {
        nightMode = !nightMode
        findViewById<TextView>(R.id.nightButton).text = if (nightMode) "☀" else "☾"
        val bg = if (nightMode) Color.rgb(31, 31, 31) else Color.rgb(245, 233, 200)
        val fg = if (nightMode) Color.rgb(222, 218, 209) else Color.rgb(59, 52, 40)
        window.decorView.setBackgroundColor(bg)
        chapterTitleText.setTextColor(fg)
        progressText.setTextColor(if (nightMode) Color.rgb(210, 206, 196) else Color.rgb(107, 98, 87))
        runJs("SimpleReader.setNight(${if (nightMode) "true" else "false"})")
    }

    private fun changeFont(delta: Int) {
        fontScale = (fontScale + delta).coerceIn(80, 180)
        runJs("SimpleReader.setFontScale($fontScale)")
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
                if (title.isNotBlank()) chapterTitleText.text = title
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
    }

    data class TocItem(val title: String, val href: String, val depth: Int)

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val BOOK_HOST = "simplereader.local"
        private const val BOOK_URL = "https://simplereader.local/book.epub"
        private const val READER_URL = "file:///android_asset/epubjs/reader.html"
        private const val LOCATOR_TYPE = "EPUB_CFI"
    }
}
