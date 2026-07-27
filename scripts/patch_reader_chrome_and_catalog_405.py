from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0:
        if new in text:
            return text
        raise SystemExit(f"missing patch target: {label}")
    if count != 1:
        raise SystemExit(f"non-unique patch target {label}: {count}")
    return text.replace(old, new, 1)


root = Path(__file__).resolve().parents[1]
reader_path = root / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
parser_path = root / "app/src/main/java/com/simplereader/app/parser/TxtParser.kt"
layout_path = root / "app/src/main/res/layout/activity_reader.xml"

reader = reader_path.read_text(encoding="utf-8")
reader = replace_once(
    reader,
    "    private var activeReaderSearchHighlight: Boolean = false\n",
    "    private var activeReaderSearchHighlight: Boolean = false\n    private var readerChromeVisible: Boolean = false\n",
    "reader chrome state",
)
reader = replace_once(
    reader,
    "        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.rgb(72, 67, 58)))\n",
    "        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.rgb(72, 67, 58)))\n        supportActionBar?.hide()\n",
    "hide top bar initially",
)
reader = replace_once(
    reader,
    "        findViewById<TextView>(R.id.moreReaderButton).setOnClickListener {\n            showReaderMoreActions()\n        }\n",
    "",
    "remove more button listener",
)
reader = replace_once(
    reader,
    "    private fun toggleReaderControls() {\n        readerControls.visibility = if (readerControls.visibility == View.VISIBLE) {\n            View.GONE\n        } else {\n            View.VISIBLE\n        }\n    }\n",
    """    private fun setReaderChromeVisible(visible: Boolean) {
        readerChromeVisible = visible
        readerControls.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) supportActionBar?.show() else supportActionBar?.hide()
    }

    private fun toggleReaderChrome() {
        setReaderChromeVisible(!readerChromeVisible)
    }
""",
    "unified reader chrome toggle",
)
reader = replace_once(
    reader,
    "        findViewById<TextView>(R.id.themeNightButton).text = if (night) \"切到日间\" else \"切到夜间\"\n        findViewById<TextView>(R.id.nightButton).text = if (night) \"日间\" else \"夜间\"\n",
    """        findViewById<TextView>(R.id.themeNightButton).apply {
            text = if (night) "☀" else "☾"
            setTextColor(Color.WHITE)
        }
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
""",
    "sun moon labels",
)
reader = replace_once(
    reader,
    "        readerControls.visibility = View.GONE\n        when {\n",
    "        setReaderChromeVisible(false)\n        when {\n",
    "hide both bars after search jump",
)
reader = replace_once(
    reader,
    """    private fun isLikelyChapterTitle(line: String): Boolean {
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
""",
    """    private fun isLikelyChapterTitle(line: String): Boolean =
        TxtParser.isLikelyChapterTitle(line)
""",
    "delegate chapter title rule",
)
reader = replace_once(
    reader,
    """    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            toggleReaderControls()
            return true
        }
        val width = readerScrollView.width.takeIf { it > 0 } ?: return false
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
""",
    """    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val width = readerScrollView.width.takeIf { it > 0 } ?: return false
        val height = readerScrollView.height.takeIf { it > 0 } ?: return false
        val centerLeft = width / 4f
        val centerRight = width * 3f / 4f
        val centerTop = height / 4f
        val centerBottom = height * 3f / 4f
        val inCenter = e.x in centerLeft..centerRight && e.y in centerTop..centerBottom
        if (inCenter) {
            toggleReaderChrome()
            return true
        }
        if (pageTurnMode == TURN_MODE_VERTICAL) return false
        return when {
            e.x < centerLeft -> {
                previousPage()
                true
            }
            e.x > centerRight -> {
                nextPage()
                true
            }
            else -> false
        }
    }
""",
    "center-only chrome trigger",
)
reader_path.write_text(reader, encoding="utf-8")

parser = parser_path.read_text(encoding="utf-8")
parser = replace_once(
    parser,
    '            Regex("^\\\\s*[一二三四五六七八九十百千万]+\\\\s*[、.．:：—-]\\\\s*\\\\S+.*"),\n',
    '            Regex("^\\\\s*[一二三四五六七八九十百千万]+\\\\s*[、.．:：—-]\\\\s*\\\\S+.*"),\n'
    '            Regex("^\\\\s*[（(【\\\\[]\\\\s*[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\\\s*[）)】\\\\]]\\\\s*[章篇](?:\\\\s*\\\\S.*)?$"),\n'
    '            Regex("^\\\\s*[零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\\\s*[章篇](?:\\\\s*\\\\S.*)?$"),\n',
    "parenthesized chinese numeral chapters",
)
parser = replace_once(
    parser,
    "    private val chapterPrefixPatterns by lazy {\n",
    """    private val standaloneChapterPatterns by lazy {
        listOf(
            Regex("^[\\p{L}\\p{N}]{2,39}篇$"),
            Regex("^[\\p{L}\\p{N}]{1,20}之[\\p{L}\\p{N}]{1,20}$")
        )
    }
    private val chapterPrefixPatterns by lazy {
""",
    "standalone chapter patterns",
)
parser = replace_once(
    parser,
    """        if (chapterPrefixPatterns.any { it.matches(normalized) }) {
            return normalized.take(80)
        }
        return null
""",
    """        if (chapterPrefixPatterns.any { it.matches(normalized) }) {
            return normalized.take(80)
        }
        if (standaloneChapterPatterns.any { it.matches(normalized) }) {
            return normalized.take(80)
        }
        return null
""",
    "standalone chapter recognition",
)
parser_path.write_text(parser, encoding="utf-8")

layout = layout_path.read_text(encoding="utf-8")
layout = replace_once(
    layout,
    '                android:text="◑\\n夜间"\n                android:textColor="#EEE9DD"\n                android:textSize="17sp" />\n',
    '                android:text="☾"\n                android:textColor="#EEE9DD"\n                android:textSize="28sp" />\n',
    "moon button symbol",
)
more_block = '''
            <TextView
                android:id="@+id/moreReaderButton"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="⋯\\n更多"
                android:textColor="#EEE9DD"
                android:textSize="17sp" />
'''
layout = replace_once(layout, more_block, "", "remove more button")
layout_path.write_text(layout, encoding="utf-8")

parser_test = root / "app/src/test/java/com/simplereader/app/parser/TxtExpandedChapterTitleRegressionTest.kt"
parser_test.parent.mkdir(parents=True, exist_ok=True)
parser_test.write_text(
    '''package com.simplereader.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtExpandedChapterTitleRegressionTest {
    @Test
    fun recognizesChineseNumeralAndPlainStandaloneChapterNames() {
        assertTrue(TxtParser.isLikelyChapterTitle("（一）章"))
        assertTrue(TxtParser.isLikelyChapterTitle("（十二）篇 初遇"))
        assertTrue(TxtParser.isLikelyChapterTitle("一章 重逢"))
        assertTrue(TxtParser.isLikelyChapterTitle("春风化雨篇"))
        assertTrue(TxtParser.isLikelyChapterTitle("山海之约"))
        assertTrue(TxtParser.isLikelyChapterTitle("故人之女"))
    }

    @Test
    fun rejectsPunctuatedSentencesThatOnlyResembleStandaloneTitles() {
        assertFalse(TxtParser.isLikelyChapterTitle("春风化雨篇。"))
        assertFalse(TxtParser.isLikelyChapterTitle("山海之约，后来再见"))
        assertFalse(TxtParser.isLikelyChapterTitle("这是一篇普通正文"))
    }
}
''',
    encoding="utf-8",
)

ui_test = root / "app/src/test/java/com/simplereader/app/ui/ReaderChromeContractTest.kt"
ui_test.parent.mkdir(parents=True, exist_ok=True)
ui_test.write_text(
    '''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChromeContractTest {
    private fun source(): String =
        File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    private fun layout(): String =
        File("src/main/res/layout/activity_reader.xml").readText()

    @Test
    fun topAndBottomBarsAreHiddenTogetherAndOnlyCenterTapTogglesThem() {
        val text = source()
        assertTrue(text.contains("supportActionBar?.hide()"))
        assertTrue(text.contains("private fun setReaderChromeVisible(visible: Boolean)"))
        assertTrue(text.contains("val centerLeft = width / 4f"))
        assertTrue(text.contains("val centerRight = width * 3f / 4f"))
        assertTrue(text.contains("val centerTop = height / 4f"))
        assertTrue(text.contains("val centerBottom = height * 3f / 4f"))
        assertTrue(text.contains("e.x in centerLeft..centerRight && e.y in centerTop..centerBottom"))
        assertFalse(text.contains("toggleReaderControls()"))
    }

    @Test
    fun bottomBarUsesSunMoonSymbolsAndHasNoMoreButton() {
        val text = source()
        val xml = layout()
        assertTrue(text.contains("if (night) \"☀\" else \"☾\""))
        assertTrue(xml.contains("android:text=\"☾\""))
        assertFalse(xml.contains("@+id/moreReaderButton"))
        assertFalse(text.contains("findViewById<TextView>(R.id.moreReaderButton)"))
    }
}
''',
    encoding="utf-8",
)
