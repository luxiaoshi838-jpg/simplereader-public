from pathlib import Path

p = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = p.read_text(encoding='utf-8')

def once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit('missing anchor: ' + label)
    s = s.replace(old, new, 1)

once('import android.view.GestureDetector\n', 'import android.view.Choreographer\nimport android.view.GestureDetector\n', 'Choreographer import')
once('import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\n', 'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\n', 'delay import')

once('    private var paginationJob: Job? = null\n    private var continuousRenderJob: Job? = null\n', '''    private var paginationJob: Job? = null
    private var continuousRenderJob: Job? = null
    private var autoReadJob: Job? = null
    private var autoReadSpeedCpm: Int = DEFAULT_AUTO_READ_CPM
    private var isAutoReading: Boolean = false
    private var verticalAutoLastFrameNs: Long = 0L
    private var verticalAutoPixelRemainder: Float = 0f
    private val verticalAutoFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onAutomaticVerticalFrame(frameTimeNanos)
        }
    }
''', 'auto fields')

once('''    override fun onPause() {
        saveProgress()
        super.onPause()
    }
''', '''    override fun onPause() {
        stopAutoReading()
        saveProgress()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stopAutoReading()
    }
''', 'pause/focus')

once('''    override fun onDestroy() {
        paginationJob?.cancel()
        continuousRenderJob?.cancel()
''', '''    override fun onDestroy() {
        stopAutoReading()
        paginationJob?.cancel()
        continuousRenderJob?.cancel()
''', 'destroy')

once('''    private fun bindControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener { showCatalogBookmarkPanelV600() }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener {
            readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
''', '''    private fun bindControls() {
        findViewById<TextView>(R.id.catalogButton).setOnClickListener {
            stopAutoReading()
            showCatalogBookmarkPanelV600()
        }
        findViewById<TextView>(R.id.readerSearchButton).setOnClickListener {
            stopAutoReading()
            readerSettingsPanel.visibility = if (readerSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.autoReadButton).setOnClickListener { showAutoReadDialog() }
        findViewById<TextView>(R.id.autoReadStopButton).setOnClickListener { stopAutoReading() }
''', 'control listeners')

once('''    private fun changeTextSize(delta: Float) {
        val offset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset ?: 0
''', '''    private fun changeTextSize(delta: Float) {
        stopAutoReading()
        val offset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset ?: 0
''', 'font stop')

once('''    private fun setTurnMode(mode: String) {
        if (pageTurnMode == mode) return
''', '''    private fun setTurnMode(mode: String) {
        if (pageTurnMode == mode) return
        stopAutoReading()
''', 'turn stop')

auto_methods = r'''
    private fun showAutoReadDialog() {
        stopAutoReading()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(6))
        }
        val speedLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(45, 42, 35))
        }
        val speedBar = SeekBar(this).apply {
            max = (MAX_AUTO_READ_CPM - MIN_AUTO_READ_CPM) / AUTO_READ_STEP_CPM
            progress = ((autoReadSpeedCpm - MIN_AUTO_READ_CPM) / AUTO_READ_STEP_CPM).coerceIn(0, max)
        }
        fun renderSpeed(progress: Int) {
            val speed = MIN_AUTO_READ_CPM + progress * AUTO_READ_STEP_CPM
            speedLabel.text = "速度：${speed} 字/分"
        }
        renderSpeed(speedBar.progress)
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderSpeed(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(speedLabel)
        content.addView(speedBar)
        AlertDialog.Builder(this)
            .setTitle("自动阅读")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                updateAutoReadSpeed(MIN_AUTO_READ_CPM + speedBar.progress * AUTO_READ_STEP_CPM)
                startAutoReading()
            }
            .show()
    }

    private fun updateAutoReadSpeed(speedCpm: Int) {
        autoReadSpeedCpm = speedCpm.coerceIn(MIN_AUTO_READ_CPM, MAX_AUTO_READ_CPM)
        savePreferences()
    }

    private fun startAutoReading() {
        val paged = readerBook ?: return
        if (paged.pages.isEmpty()) return
        stopAutoReading()
        isAutoReading = true
        verticalAutoLastFrameNs = 0L
        verticalAutoPixelRemainder = 0f
        setReaderChromeVisible(false)
        findViewById<TextView>(R.id.autoReadStopButton).visibility = View.VISIBLE
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            startAutomaticVerticalScroll()
        } else {
            scheduleAutomaticPageTurn()
        }
    }

    private fun scheduleAutomaticPageTurn() {
        autoReadJob?.cancel()
        autoReadJob = lifecycleScope.launch {
            while (isAutoReading && pageTurnMode != TURN_MODE_VERTICAL) {
                val paged = readerBook ?: break
                val page = paged.pages.getOrNull(currentPageIndex) ?: break
                val start = page.startOffset.coerceIn(0, paged.text.length)
                val end = page.endOffset.coerceIn(start, paged.text.length)
                val effectiveChars = paged.text.substring(start, end).count { !it.isWhitespace() }
                val waitMs = if (effectiveChars <= 0) {
                    MIN_AUTO_PAGE_WAIT_MS
                } else {
                    ((effectiveChars.toLong() * 60_000L) / autoReadSpeedCpm.coerceAtLeast(1))
                        .coerceAtLeast(MIN_AUTO_PAGE_WAIT_MS)
                }
                delay(waitMs)
                if (!isAutoReading || pageTurnMode == TURN_MODE_VERTICAL) break
                if (currentPageIndex >= paged.pages.lastIndex) {
                    stopAutoReading()
                    break
                }
                pagedReaderView.turn(1)
                delay(AUTO_TURN_SETTLE_MS)
            }
        }
    }

    private fun startAutomaticVerticalScroll() {
        verticalAutoLastFrameNs = 0L
        verticalAutoPixelRemainder = 0f
        Choreographer.getInstance().removeFrameCallback(verticalAutoFrameCallback)
        Choreographer.getInstance().postFrameCallback(verticalAutoFrameCallback)
    }

    private fun onAutomaticVerticalFrame(frameTimeNanos: Long) {
        if (!isAutoReading || pageTurnMode != TURN_MODE_VERTICAL || isFinishing || isDestroyed) return
        val paged = readerBook ?: run { stopAutoReading(); return }
        val layout = continuousTextView.layout
        if (layout == null || continuousTextView.text.isEmpty()) {
            Choreographer.getInstance().postFrameCallback(verticalAutoFrameCallback)
            return
        }
        if (verticalAutoLastFrameNs == 0L) {
            verticalAutoLastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(verticalAutoFrameCallback)
            return
        }
        val deltaMs = ((frameTimeNanos - verticalAutoLastFrameNs).coerceAtLeast(0L) / 1_000_000f)
            .coerceAtMost(100f)
        verticalAutoLastFrameNs = frameTimeNanos
        val effectiveChars = continuousTextView.text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val pixelsPerChar = layout.height.toFloat().coerceAtLeast(1f) / effectiveChars
        val distance = deltaMs * autoReadSpeedCpm / 60_000f * pixelsPerChar + verticalAutoPixelRemainder
        val wholePixels = distance.toInt()
        verticalAutoPixelRemainder = distance - wholePixels
        if (wholePixels > 0) readerScrollView.scrollBy(0, wholePixels)

        val atBookEnd = currentPageIndex >= paged.pages.lastIndex &&
            continuousWindowEndOffset >= paged.text.length &&
            readerScrollView.scrollY + readerScrollView.height >= continuousTextView.height - 2
        if (atBookEnd) {
            stopAutoReading()
            return
        }
        Choreographer.getInstance().postFrameCallback(verticalAutoFrameCallback)
    }

    private fun stopAutoReading() {
        isAutoReading = false
        autoReadJob?.cancel()
        autoReadJob = null
        verticalAutoLastFrameNs = 0L
        verticalAutoPixelRemainder = 0f
        Choreographer.getInstance().removeFrameCallback(verticalAutoFrameCallback)
        if (::readerRoot.isInitialized) {
            findViewById<TextView>(R.id.autoReadStopButton).visibility = View.GONE
        }
    }

'''
once('    private fun selectQuickColor(colorId: String) {\n', auto_methods + '    private fun selectQuickColor(colorId: String) {\n', 'auto methods')

once('''        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
        val category = prefs.getString(PREF_BACKGROUND_CATEGORY, null)
''', '''        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
        autoReadSpeedCpm = prefs.getInt(PREF_AUTO_READ_SPEED, DEFAULT_AUTO_READ_CPM)
            .coerceIn(MIN_AUTO_READ_CPM, MAX_AUTO_READ_CPM)
        val category = prefs.getString(PREF_BACKGROUND_CATEGORY, null)
''', 'load speed')

once('''            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .putString(PREF_BACKGROUND_CATEGORY, currentBackgroundSelection().category.name)
''', '''            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .putInt(PREF_AUTO_READ_SPEED, autoReadSpeedCpm)
            .putString(PREF_BACKGROUND_CATEGORY, currentBackgroundSelection().category.name)
''', 'save speed')

once('''        private const val PREF_VOLUME_KEY = "volume_key_turn"
        private const val PREF_BACKGROUND_CATEGORY = "reader_background_category"
''', '''        private const val PREF_VOLUME_KEY = "volume_key_turn"
        private const val PREF_AUTO_READ_SPEED = "auto_read_speed_cpm"
        private const val PREF_BACKGROUND_CATEGORY = "reader_background_category"
''', 'pref const')

once('''        private const val MENU_SEARCH = 5
        private const val CONTINUOUS_PAGES_BEFORE = 24
''', '''        private const val MENU_SEARCH = 5
        private const val MIN_AUTO_READ_CPM = 200
        private const val MAX_AUTO_READ_CPM = 2000
        private const val AUTO_READ_STEP_CPM = 50
        private const val DEFAULT_AUTO_READ_CPM = 600
        private const val MIN_AUTO_PAGE_WAIT_MS = 500L
        private const val AUTO_TURN_SETTLE_MS = 320L
        private const val CONTINUOUS_PAGES_BEFORE = 24
''', 'auto consts')

p.write_text(s, encoding='utf-8')

q = Path('app/src/main/res/layout/activity_reader.xml')
x = q.read_text(encoding='utf-8')
old = '''            <TextView
                android:id="@+id/readerSearchButton"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="⬡\\n设置"
                android:textColor="#EEE9DD"
                android:textSize="17sp" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>'''
new = '''            <TextView
                android:id="@+id/readerSearchButton"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="⬡\\n设置"
                android:textColor="#EEE9DD"
                android:textSize="17sp" />

            <TextView
                android:id="@+id/autoReadButton"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="▶\\n自动"
                android:textColor="#EEE9DD"
                android:textSize="17sp" />
        </LinearLayout>
    </LinearLayout>

    <TextView
        android:id="@+id/autoReadStopButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_gravity="top|end"
        android:layout_marginTop="14dp"
        android:layout_marginEnd="18dp"
        android:background="@drawable/bg_auto_read_stop"
        android:elevation="12dp"
        android:gravity="center"
        android:text="停"
        android:textColor="#FFFFFF"
        android:textSize="17sp"
        android:visibility="gone" />
</FrameLayout>'''
if old not in x:
    raise SystemExit('missing layout toolbar anchor')
q.write_text(x.replace(old, new, 1), encoding='utf-8')

Path('app/src/main/res/drawable/bg_auto_read_stop.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#D96A2B" />
    <stroke android:width="2dp" android:color="#F4EEE2" />
</shape>
''', encoding='utf-8')

g = Path('app/build.gradle.kts')
b = g.read_text(encoding='utf-8')
b = b.replace('"2098000746"', '"2098000747"').replace('?: 2098000746', '?: 2098000747').replace('?: "746"', '?: "747"')
g.write_text(b, encoding='utf-8')

t = Path('app/src/test/java/com/simplereader/app/ui/V747AutoReadContractTest.kt')
t.parent.mkdir(parents=True, exist_ok=True)
t.write_text('''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V747AutoReadContractTest {
    private fun root() = File(System.getProperty("user.dir") ?: ".")

    @Test
    fun sourceNativeAutomaticReadingIsRestored() {
        val r = File(root(), "src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val l = File(root(), "src/main/res/layout/activity_reader.xml").readText()
        listOf("showAutoReadDialog", "startAutoReading", "scheduleAutomaticPageTurn", "startAutomaticVerticalScroll", "updateAutoReadSpeed", "stopAutoReading").forEach { assertTrue(r.contains(it)) }
        assertTrue(r.contains("MIN_AUTO_READ_CPM = 200"))
        assertTrue(r.contains("MAX_AUTO_READ_CPM = 2000"))
        assertTrue(r.contains("AUTO_READ_STEP_CPM = 50"))
        assertTrue(r.contains("effectiveChars.toLong() * 60_000L"))
        assertTrue(r.contains("verticalAutoPixelRemainder"))
        assertTrue(r.contains("onWindowFocusChanged"))
        assertTrue(l.contains("@+id/autoReadButton"))
        assertTrue(l.contains("@+id/autoReadStopButton"))
        assertTrue(l.contains("@drawable/bg_auto_read_stop"))
    }
}
''', encoding='utf-8')
