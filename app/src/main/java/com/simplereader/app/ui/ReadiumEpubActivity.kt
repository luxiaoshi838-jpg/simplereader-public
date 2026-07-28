package com.simplereader.app.ui

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.simplereader.app.R
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.ReadProgress
import com.simplereader.app.readium.ReadiumEngine
import com.simplereader.app.readium.ReadiumSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.toAbsoluteUrl
import kotlin.math.roundToInt

@OptIn(ExperimentalReadiumApi::class)
class ReadiumEpubActivity : AppCompatActivity(), ReadiumEpubFragment.Host {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var engine: ReadiumEngine
    private lateinit var book: Book
    private lateinit var publication: Publication

    private var readerFragment: ReadiumEpubFragment? = null
    private var currentLocator: Locator? = null
    private var saveJob: Job? = null
    private var chromeVisible = false
    private var readerFontScale = 1.0f
    private var nightMode = false

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var touchBlocker: View
    private lateinit var loadingText: TextView
    private lateinit var bookTitleText: TextView
    private lateinit var chapterTitleText: TextView
    private lateinit var progressText: TextView

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
        engine = ReadiumEngine(this)
        loadReaderPreferences()
        bindViews()
        setupControls()
        setChromeVisible(false)

        if (bookId <= 0L) {
            showFatalError("书籍记录不存在")
            return
        }
        openPublication()
    }

    private fun bindViews() {
        topBar = findViewById(R.id.epubTopBar)
        bottomBar = findViewById(R.id.epubBottomBar)
        touchBlocker = findViewById(R.id.epubTouchBlocker)
        loadingText = findViewById(R.id.epubLoadingText)
        bookTitleText = findViewById(R.id.epubBookTitle)
        chapterTitleText = findViewById(R.id.epubChapterTitle)
        progressText = findViewById(R.id.epubProgressLabel)
        touchBlocker.setOnClickListener { setChromeVisible(false) }
    }

    private fun setupControls() {
        findViewById<View>(R.id.epubBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.epubPreviousChapterButton).setOnClickListener { jumpChapter(-1) }
        findViewById<View>(R.id.epubNextChapterButton).setOnClickListener { jumpChapter(1) }
        findViewById<View>(R.id.epubCatalogButton).setOnClickListener { showCatalog() }
        findViewById<View>(R.id.epubFontDecreaseButton).setOnClickListener { changeFontScale(-0.08f) }
        findViewById<View>(R.id.epubFontIncreaseButton).setOnClickListener { changeFontScale(0.08f) }
        findViewById<View>(R.id.epubThemeButton).setOnClickListener { toggleTheme() }
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

            val sourceUrl = Uri.parse(loadedBook.filePath).toAbsoluteUrl()
            if (sourceUrl == null) {
                showFatalError("无法解析 EPUB 文件位置")
                return@launch
            }

            val asset = engine.assetRetriever.retrieve(sourceUrl).getOrElse { error ->
                showFatalError("无法读取 EPUB：$error")
                return@launch
            }
            val opened = engine.publicationOpener.open(
                asset = asset,
                allowUserInteraction = false
            ).getOrElse { error ->
                showFatalError("无法解析 EPUB：$error")
                return@launch
            }
            publication = opened

            val initialLocator = withContext(Dispatchers.IO) {
                database.readProgressDao().getProgress(bookId)
            }?.takeIf { it.locatorType == LOCATOR_TYPE }
                ?.position
                ?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }

            ReadiumSessionStore.put(
                bookId,
                ReadiumSessionStore.Session(
                    publication = opened,
                    initialLocator = initialLocator,
                    navigatorFactory = EpubNavigatorFactory(opened)
                )
            )

            supportFragmentManager.commitNow {
                replace(
                    R.id.epubReaderFragmentHost,
                    ReadiumEpubFragment.newInstance(bookId),
                    READER_FRAGMENT_TAG
                )
            }
            readerFragment = supportFragmentManager.findFragmentByTag(READER_FRAGMENT_TAG)
                as? ReadiumEpubFragment
            loadingText.visibility = View.GONE
            withContext(Dispatchers.IO) {
                database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
            }
        }
    }

    override fun onReadiumNavigatorReady(fragment: ReadiumEpubFragment) {
        readerFragment = fragment
        fragment.applyPresentationCss()
    }

    override fun onReadiumLocatorChanged(locator: Locator) {
        currentLocator = locator
        chapterTitleText.text = locator.title?.takeIf { it.isNotBlank() } ?: book.title
        val fraction = locator.locations.totalProgression
            ?: locator.locations.progression
            ?: 0.0
        progressText.text = "${(fraction.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
        scheduleProgressSave(locator, fraction.toFloat())
    }

    private fun scheduleProgressSave(locator: Locator, fraction: Float) {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(350L)
            saveProgress(locator, fraction)
        }
    }

    private suspend fun saveProgress(locator: Locator, fraction: Float) {
        withContext(Dispatchers.IO) {
            database.readProgressDao().insert(
                ReadProgress(
                    bookId = bookId,
                    position = locator.toJSON().toString(),
                    locatorType = LOCATOR_TYPE,
                    epubChapterHref = locator.href.toString(),
                    epubProgressFraction = fraction.coerceIn(0f, 1f),
                    updateTime = System.currentTimeMillis()
                )
            )
            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())
        }
    }

    override fun toggleReadiumChrome() {
        setChromeVisible(!chromeVisible)
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        touchBlocker.visibility = if (visible) View.VISIBLE else View.GONE
        progressText.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun jumpChapter(direction: Int) {
        val fragment = readerFragment ?: return
        val readingOrder = publication.readingOrder
        if (readingOrder.isEmpty()) {
            Toast.makeText(this, "当前书籍没有可跳转章节", Toast.LENGTH_SHORT).show()
            return
        }
        val currentHref = fragment.navigator.currentLocator.value.href
            .toString()
            .substringBefore('#')
        val currentIndex = readingOrder.indexOfFirst {
            it.url().toString().substringBefore('#') == currentHref
        }.takeIf { it >= 0 } ?: 0
        val targetIndex = currentIndex + direction
        if (targetIndex !in readingOrder.indices) {
            Toast.makeText(
                this,
                if (direction < 0) "已经是第一章" else "已经是最后一章",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        fragment.navigator.go(readingOrder[targetIndex], animated = true)
        setChromeVisible(false)
    }

    private fun showCatalog() {
        val fragment = readerFragment ?: return
        val items = flattenToc(publication.tableOfContents)
            .ifEmpty {
                publication.readingOrder.mapIndexed { index, link ->
                    TocItem(link.title ?: "第 ${index + 1} 章", link, 0)
                }
            }
        if (items.isEmpty()) {
            Toast.makeText(this, "当前书籍没有目录", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { item ->
            "　".repeat(item.depth.coerceAtMost(4)) + item.title
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(labels) { dialog, which ->
                fragment.navigator.go(items[which].link, animated = true)
                dialog.dismiss()
                setChromeVisible(false)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun flattenToc(links: List<Link>, depth: Int = 0): List<TocItem> = buildList {
        links.forEach { link ->
            val title = link.title?.trim().orEmpty()
            if (title.isNotEmpty()) add(TocItem(title, link, depth))
            addAll(flattenToc(link.children, depth + 1))
        }
    }

    private fun changeFontScale(delta: Float) {
        readerFontScale = (readerFontScale + delta).coerceIn(0.75f, 1.8f)
        saveReaderPreferences()
        readerFragment?.applyPresentationCss()
    }

    private fun toggleTheme() {
        nightMode = !nightMode
        saveReaderPreferences()
        readerFragment?.applyPresentationCss()
    }

    override fun readiumPresentationCss(): String {
        val fontPercent = (readerFontScale * 100).roundToInt()
        val background = if (nightMode) "#151515" else "#F5E9C8"
        val foreground = if (nightMode) "#D8D4CC" else "#3B3428"
        return """
            html, body {
                background: $background !important;
                color: $foreground !important;
            }
            body {
                font-size: $fontPercent% !important;
                line-height: 1.75 !important;
            }
            h1, h2, h3,
            [epub\\:type~="title"],
            [epub\\:type~="chapter"] > :first-child {
                font-size: 1.12em !important;
                font-weight: 700 !important;
                line-height: 1.45 !important;
                margin-top: 0.35em !important;
                margin-bottom: 0.8em !important;
            }
            img, svg, video, figure {
                max-width: 100% !important;
                height: auto !important;
            }
            table {
                max-width: 100% !important;
            }
        """.trimIndent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (chromeVisible) return true
            val navigator = readerFragment?.navigator ?: return super.dispatchKeyEvent(event)
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                navigator.goBackward(animated = true)
            } else {
                navigator.goForward(animated = true)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        currentLocator?.let { locator ->
            val fraction = (locator.locations.totalProgression
                ?: locator.locations.progression
                ?: 0.0).toFloat()
            lifecycleScope.launch { saveProgress(locator, fraction) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        saveJob?.cancel()
        if (isFinishing) {
            ReadiumSessionStore.remove(bookId)?.publication?.close()
        }
        super.onDestroy()
    }

    private fun showFatalError(message: String) {
        loadingText.text = message
        loadingText.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class TocItem(val title: String, val link: Link, val depth: Int)

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val READER_FRAGMENT_TAG = "readiumEpubReader"
        private const val LOCATOR_TYPE = "READIUM_EPUB"
        private const val PREFS = "readium_epub_reader"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_NIGHT = "night_mode"
    }
}
