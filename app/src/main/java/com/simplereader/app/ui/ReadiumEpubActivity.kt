package com.simplereader.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.ReadProgress
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * EPUB reader backed by the offline epub.js continuous manager.
 *
 * The historical class name is retained so existing intents and the manifest remain compatible.
 */
class ReadiumEpubActivity : AppCompatActivity() {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var book: Book
    private lateinit var assetLoader: WebViewAssetLoader

    private lateinit var webView: WebView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var touchBlocker: View
    private lateinit var loadingText: TextView
    private lateinit var bookTitleText: TextView
    private lateinit var chapterTitleText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressSeekBar: SeekBar

    private var shellReady = false
    private var bookReady = false
    private var readerStarted = false
    private var readerLoaded = false
    private var chromeVisible = false
    private var userDraggingProgress = false
    private var readerFontScale = 1.0f
    private var nightMode = false

    private var initialCfi = ""
    private var initialFraction = 0.0
    private var currentCfi = ""
    private var currentHref = ""
    private var currentFraction = 0.0
    private var saveJob: Job? = null
    private var tocItems: List<TocItem> = emptyList()

    private val bookId: Long by lazy { intent.getLongExtra(EXTRA_BOOK_ID, 0L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_readium_epub)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        database = SimpleReaderDatabase.getDatabase(this)
        loadReaderPreferences()
        bindViews()
        configureWebView()
        setupControls()
        setChromeVisible(false)

        if (bookId <= 0L) {
            showFatalError("书籍记录不存在")
            return
        }
        openPublication()
    }

    private fun bindViews() {
        webView = findViewById(R.id.epubWebView)
        topBar = findViewById(R.id.epubTopBar)
        bottomBar = findViewById(R.id.epubBottomBar)
        touchBlocker = findViewById(R.id.epubTouchBlocker)
        loadingText = findViewById(R.id.epubLoadingText)
        bookTitleText = findViewById(R.id.epubBookTitle)
        chapterTitleText = findViewById(R.id.epubChapterTitle)
        progressText = findViewById(R.id.epubProgressLabel)
        progressSeekBar = findViewById(R.id.epubProgressSeekBar)
        touchBlocker.setOnClickListener { setChromeVisible(false) }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/book/", WebViewAssetLoader.PathHandler { path -> serveBook(path) })
            .build()

        webView.setBackgroundColor(Color.rgb(245, 233, 200))
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(ReaderBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean = request.url.host != APP_ASSET_HOST

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == READER_URL) {
                    shellReady = true
                    startReaderIfReady()
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(false)
    }

    private fun serveBook(path: String): WebResourceResponse? {
        if (path.substringBefore('?') != BOOK_FILE_NAME || !::book.isInitialized) return null
        return runCatching {
            val input = openBookInputStream() ?: return null
            WebResourceResponse("application/epub+zip", null, input)
        }.getOrNull()
    }

    private fun openBookInputStream(): InputStream? {
        val rawPath = book.filePath
        val parsed = Uri.parse(rawPath)
        return when (parsed.scheme?.lowercase()) {
            "content", "android.resource" -> contentResolver.openInputStream(parsed)
            "file" -> parsed.path?.let { FileInputStream(File(it)) }
            null, "" -> FileInputStream(File(rawPath))
            else -> contentResolver.openInputStream(parsed)
                ?: rawPath.takeIf { File(it).isFile }?.let { FileInputStream(File(it)) }
        }
    }

    private fun setupControls() {
        findViewById<View>(R.id.epubBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.epubPreviousChapterButton).setOnClickListener {
            if (chromeVisible) runBooleanCommand("SimpleReader.goChapter(-1)", "已经是第一章")
        }
        findViewById<View>(R.id.epubNextChapterButton).setOnClickListener {
            if (chromeVisible) runBooleanCommand("SimpleReader.goChapter(1)", "已经是最后一章")
        }
        findViewById<View>(R.id.epubCatalogButton).setOnClickListener {
            if (chromeVisible) showCatalog()
        }
        findViewById<View>(R.id.epubFontDecreaseButton).setOnClickListener {
            if (chromeVisible) changeFontScale(-0.08f)
        }
        findViewById<View>(R.id.epubFontIncreaseButton).setOnClickListener {
            if (chromeVisible) changeFontScale(0.08f)
        }
        findViewById<View>(R.id.epubThemeButton).setOnClickListener {
            if (chromeVisible) toggleTheme()
        }
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && chromeVisible) {
                    progressText.text = "${(progress / 10.0).roundToInt()}%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userDraggingProgress = chromeVisible
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val canJump = chromeVisible && userDraggingProgress
                userDraggingProgress = false
                if (!canJump) return
                val fraction = ((seekBar?.progress ?: 0).toDouble() / progressSeekBar.max)
                    .coerceIn(0.0, 1.0)
                runBooleanCommand(
                    "SimpleReader.goToProgress($fraction)",
                    "阅读位置仍在准备，请稍后再拖动"
                )
            }
        })
    }

    private fun loadReaderPreferences() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        readerFontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.75f, 1.8f)
        nightMode = prefs.getBoolean(KEY_NIGHT, false)
    }

    private fun saveReaderPreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FONT_SCALE, readerFontScale)
            .putBoolean(KEY_NIGHT, nightMode)
            .apply()
    }

    private fun openPublication() {
        loadingText.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loadedBook = withContext(Dispatchers.IO) {
                database.bookDao().getBook(bookId)
            }
            if (loadedBook == null || !loadedBook.format.equals("EPUB", ignoreCase = true)) {
                showFatalError("当前文件不是可读取的 EPUB")
                return@launch
            }
            book = loadedBook
            bookTitleText.text = loadedBook.title

            val storedProgress = withContext(Dispatchers.IO) {
                database.readProgressDao().getProgress(bookId)
            }
            initialCfi = storedProgress
                ?.takeIf { it.locatorType == LOCATOR_TYPE }
                ?.position
                ?.takeIf { it.startsWith("epubcfi(") }
                .orEmpty()
            initialFraction = storedProgress?.epubProgressFraction
                ?.toDouble()
                ?.coerceIn(0.0, 1.0)
                ?: 0.0

            bookReady = true
            webView.loadUrl(READER_URL)
            withContext(Dispatchers.IO) {
                database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
            }
        }
    }

    private fun startReaderIfReady() {
        if (!shellReady || !bookReady || readerStarted) return
        readerStarted = true
        val fontPercent = (readerFontScale * 100).roundToInt()
        val script = "SimpleReader.openBook(" +
            "${JSONObject.quote(BOOK_URL)}," +
            "${JSONObject.quote(initialCfi)}," +
            "$initialFraction," +
            "$fontPercent," +
            nightMode +
            ")"
        webView.evaluateJavascript(script, null)
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        touchBlocker.visibility = if (visible) View.VISIBLE else View.GONE
        progressText.visibility = if (visible) View.GONE else View.VISIBLE
        progressSeekBar.isEnabled = visible
        if (!visible) userDraggingProgress = false
        if (readerStarted) {
            webView.evaluateJavascript("SimpleReader.setLocked($visible)", null)
        }
    }

    private fun runBooleanCommand(script: String, failureMessage: String) {
        if (!chromeVisible || !readerLoaded) return
        webView.evaluateJavascript(script) { result ->
            if (!chromeVisible) return@evaluateJavascript
            if (result == "true") {
                setChromeVisible(false)
            } else {
                Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCatalog() {
        if (!chromeVisible || tocItems.isEmpty()) {
            if (chromeVisible) Toast.makeText(this, "当前书籍没有目录", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = tocItems.map { item ->
            "　".repeat(item.depth.coerceAtMost(4)) + item.title
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(labels) { dialog, which ->
                if (!chromeVisible) {
                    dialog.dismiss()
                    return@setItems
                }
                val href = JSONObject.quote(tocItems[which].href)
                webView.evaluateJavascript("SimpleReader.goToHref($href)") { result ->
                    if (result == "true") setChromeVisible(false)
                }
                dialog.dismiss()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun changeFontScale(delta: Float) {
        if (!chromeVisible) return
        readerFontScale = (readerFontScale + delta).coerceIn(0.75f, 1.8f)
        saveReaderPreferences()
        webView.evaluateJavascript(
            "SimpleReader.setFontScale(${(readerFontScale * 100).roundToInt()})",
            null
        )
    }

    private fun toggleTheme() {
        if (!chromeVisible) return
        nightMode = !nightMode
        saveReaderPreferences()
        webView.setBackgroundColor(if (nightMode) Color.rgb(21, 21, 21) else Color.rgb(245, 233, 200))
        webView.evaluateJavascript("SimpleReader.setNight($nightMode)", null)
    }

    private fun updateLocation(cfi: String, href: String, title: String, fraction: Double) {
        currentCfi = cfi
        currentHref = href
        currentFraction = fraction.coerceIn(0.0, 1.0)
        chapterTitleText.text = title.takeIf { it.isNotBlank() } ?: book.title
        progressText.text = "${(currentFraction * 100).roundToInt()}%"
        if (!userDraggingProgress) {
            progressSeekBar.progress = (currentFraction * progressSeekBar.max).roundToInt()
        }
        scheduleProgressSave()
    }

    private fun scheduleProgressSave() {
        if (currentCfi.isBlank()) return
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(350L)
            saveProgress()
        }
    }

    private suspend fun saveProgress() {
        if (currentCfi.isBlank() || !::book.isInitialized) return
        val cfi = currentCfi
        val href = currentHref
        val fraction = currentFraction.toFloat()
        withContext(Dispatchers.IO) {
            database.readProgressDao().insert(
                ReadProgress(
                    bookId = bookId,
                    position = cfi,
                    locatorType = LOCATOR_TYPE,
                    epubChapterHref = href,
                    epubProgressFraction = fraction.coerceIn(0f, 1f),
                    updateTime = System.currentTimeMillis()
                )
            )
            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
        }
    }

    private inner class ReaderBridge {
        @JavascriptInterface
        fun onShellReady() {
            runOnUiThread {
                shellReady = true
                startReaderIfReady()
            }
        }

        @JavascriptInterface
        fun onEngineReady() {
            runOnUiThread {
                readerLoaded = true
                loadingText.visibility = View.GONE
                setChromeVisible(false)
            }
        }

        @JavascriptInterface
        fun onBookReady(title: String) {
            runOnUiThread {
                if (title.isNotBlank()) bookTitleText.text = title
            }
        }

        @JavascriptInterface
        fun onTocReady(json: String) {
            runOnUiThread {
                tocItems = runCatching {
                    val array = JSONArray(json)
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.getJSONObject(index)
                            val href = item.optString("href")
                            if (href.isBlank()) continue
                            add(
                                TocItem(
                                    title = item.optString("title", "未命名章节"),
                                    href = href,
                                    depth = item.optInt("depth", 0)
                                )
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }

        @JavascriptInterface
        fun onLocationChanged(cfi: String, href: String, title: String, fraction: Double) {
            runOnUiThread { updateLocation(cfi, href, title, fraction) }
        }

        @JavascriptInterface
        fun onCenterTap() {
            runOnUiThread {
                if (readerLoaded) setChromeVisible(!chromeVisible)
            }
        }

        @JavascriptInterface
        fun onReaderError(message: String) {
            runOnUiThread { showFatalError("无法解析 EPUB：$message") }
        }
    }

    override fun onStop() {
        lifecycleScope.launch { saveProgress() }
        super.onStop()
    }

    override fun onDestroy() {
        saveJob?.cancel()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
        super.onDestroy()
    }

    private fun showFatalError(message: String) {
        loadingText.text = message
        loadingText.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class TocItem(val title: String, val href: String, val depth: Int)

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val APP_ASSET_HOST = "appassets.androidplatform.net"
        private const val READER_URL = "https://appassets.androidplatform.net/assets/epubjs/reader.html"
        private const val BOOK_URL = "https://appassets.androidplatform.net/book/current.epub"
        private const val BOOK_FILE_NAME = "current.epub"
        private const val LOCATOR_TYPE = "EPUBJS_CONTINUOUS"
        private const val PREFS = "epubjs_continuous_reader"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_NIGHT = "night_mode"
    }
}
