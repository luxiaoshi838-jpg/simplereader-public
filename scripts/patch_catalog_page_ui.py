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
    "    private lateinit var readerProgressBar: SeekBar\n",
    "",
    "remove bottom progress seekbar field",
)
reader = replace_once(
    reader,
    "        readerProgressBar = findViewById(R.id.readerProgressBar)\n",
    "",
    "remove bottom progress seekbar lookup",
)
reader = replace_once(
    reader,
    "        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, \"搜索\")\n            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)\n",
    "        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, \"搜索\")\n            .setIcon(android.R.drawable.ic_menu_search)\n            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)\n",
    "magnifying glass menu icon",
)
reader = replace_once(
    reader,
    "        fontSizeSeekBar.setOnSeekBarChangeListener(progressListener)\n        readerProgressBar.setOnSeekBarChangeListener(progressListener)\n",
    "        fontSizeSeekBar.setOnSeekBarChangeListener(progressListener)\n",
    "remove bottom progress seekbar listener",
)
reader = replace_once(
    reader,
    "    private fun updateProgressViews(progress: Int) {\n        fontSizeSeekBar.progress = progress\n        readerProgressBar.progress = progress\n        readerProgressLabel.text = \"${(progress / 10f).toInt()}%\"\n    }\n",
    """    private fun updateProgressViews(progress: Int) {
        fontSizeSeekBar.progress = progress
        readerProgressLabel.text = pageCountLabel()
    }

    private fun pageCountLabel(): String {
        val (positionUnits, totalUnits) = readerPageUnits()
        val unitsPerPage = if (txtStreamingMode) {
            estimatedTxtBytesPerPage()
        } else {
            pageSize.toLong()
        }.coerceAtLeast(1L)
        val totalPages = ((totalUnits + unitsPerPage - 1L) / unitsPerPage).coerceAtLeast(1L)
        val currentPage = (positionUnits / unitsPerPage + 1L).coerceIn(1L, totalPages)
        return "$currentPage/$totalPages"
    }

    private fun readerPageUnits(): Pair<Long, Long> {
        return when {
            txtStreamingMode -> {
                currentPosition.toLong().coerceAtLeast(0L) to txtTotalBytes.coerceAtLeast(1L)
            }
            isStructuredChapterDocument() && structuredWholeText != null -> {
                val wholeText = structuredWholeText.orEmpty()
                val location = currentStructuredLocation()
                val chapterStart = epubChapterStartPositions.getOrElse(location.chapterIndex) { 0 }
                (chapterStart + location.offset).toLong().coerceAtLeast(0L) to
                    wholeText.length.toLong().coerceAtLeast(1L)
            }
            else -> {
                currentPosition.toLong().coerceAtLeast(0L) to
                    currentContent.length.toLong().coerceAtLeast(1L)
            }
        }
    }

    private fun estimatedTxtBytesPerPage(): Long {
        val visibleBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
        val visibleCharacters = currentContent.length.coerceAtLeast(1)
        val averageBytesPerCharacter = visibleBytes.toDouble() / visibleCharacters.toDouble()
        return (pageSize * averageBytesPerCharacter)
            .toLong()
            .coerceAtLeast(pageSize.toLong())
    }
""",
    "page count label",
)
reader_path.write_text(reader, encoding="utf-8")

parser = parser_path.read_text(encoding="utf-8")
parser = replace_once(
    parser,
    '            Regex("^\\\\s*[0-9]{1,5}\\\\s*[、.．]\\\\s*\\\\S+.*"),\n',
    '            Regex("^\\\\s*[（(【\\\\[]?[0-9]{1,5}[）)】\\\\]]?\\\\s*[、.．:：—-]\\\\s*\\\\S+.*"),\n'
    '            Regex("^\\\\s*[0-9]{1,5}\\\\s+\\\\S.{0,100}$"),\n'
    '            Regex("^\\\\s*[一二三四五六七八九十百千万]+\\\\s*[、.．:：—-]\\\\s*\\\\S+.*"),\n',
    "expanded chapter title patterns",
)
parser = replace_once(
    parser,
    "                val read = stream.read()\n                if (read == -1) break\n                absoluteOffset += 1L\n",
    """                val read = stream.read()
                if (read == -1) {
                    if (lineBytes.size() > 0 && chapters.size < maxChapters) {
                        val line = decodeBestEffort(lineBytes.toByteArray(), charset.name()).trim()
                        val title = extractChapterTitle(line)
                        if (title != null) {
                            val last = chapters.lastOrNull()
                            if (last == null || lineStartOffset - last.byteOffset > 80L) {
                                chapters += TxtChapterHit(title, lineStartOffset)
                            }
                        }
                    }
                    break
                }
                absoluteOffset += 1L
""",
    "scan final chapter line",
)
parser_path.write_text(parser, encoding="utf-8")

layout = layout_path.read_text(encoding="utf-8")
old_overlay = """    <LinearLayout
        android:id="@+id/readerProgressOverlay"
        android:layout_width="match_parent"
        android:layout_height="36dp"
        android:layout_gravity="bottom"
        android:background="#22FFFFFF"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="24dp"
        android:paddingEnd="24dp">

        <SeekBar
            android:id="@+id/readerProgressBar"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:max="1000"
            android:progress="0"
            android:progressTint="#D8732B"
            android:thumbTint="#7C7467" />

        <TextView
            android:id="@+id/readerProgressLabel"
            android:layout_width="58dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="0%"
            android:textColor="#6B6257"
            android:textSize="12sp" />
    </LinearLayout>
"""
new_overlay = """    <TextView
        android:id="@+id/readerProgressLabel"
        android:layout_width="wrap_content"
        android:layout_height="28dp"
        android:layout_gravity="bottom|end"
        android:layout_marginEnd="12dp"
        android:layout_marginBottom="6dp"
        android:background="#33FFFFFF"
        android:clickable="false"
        android:focusable="false"
        android:gravity="center"
        android:minWidth="54dp"
        android:paddingStart="8dp"
        android:paddingEnd="8dp"
        android:text="1/1"
        android:textColor="#6B6257"
        android:textSize="12sp" />
"""
layout = replace_once(layout, old_overlay, new_overlay, "bottom page label layout")
layout_path.write_text(layout, encoding="utf-8")

parser_test = root / "app/src/test/java/com/simplereader/app/parser/TxtChapterAndEncodingRegressionTest.kt"
parser_test.parent.mkdir(parents=True, exist_ok=True)
parser_test.write_text(
    '''package com.simplereader.app.parser

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtChapterAndEncodingRegressionTest {
    @Test
    fun recognizesNumberSpaceChapterTitles() {
        assertTrue(TxtParser.isLikelyChapterTitle("1 第一章标题"))
        assertTrue(TxtParser.isLikelyChapterTitle("2 第二章标题"))
        assertTrue(TxtParser.isLikelyChapterTitle("（3） 第三章标题"))
        assertTrue(TxtParser.isLikelyChapterTitle("四、第四章标题"))
        assertFalse(TxtParser.isLikelyChapterTitle("2026 年共有很多变化。"))
    }

    @Test
    fun decodesGb18030AndScansNumberSpaceCatalogWithoutMojibake() {
        val source = "1 第一章标题\\n这是中文正文，不应该出现乱码。\\n2 第二章标题\\n第二段正文。"
        val bytes = source.toByteArray(Charset.forName("GB18030"))
        val detected = TxtParser.detectCharset(ByteArrayInputStream(bytes))
        val window = TxtParser.readWindow(
            inputStream = ByteArrayInputStream(bytes),
            charsetName = detected,
            startByte = 0L,
            maxBytes = bytes.size + 64
        )
        assertEquals(source, window.text)
        assertFalse(window.text.contains("锟斤拷"))
        assertFalse(window.text.contains("�"))

        val chapters = TxtParser.scanChapters(
            inputStream = ByteArrayInputStream(bytes),
            charsetName = detected
        )
        assertEquals(listOf("1 第一章标题", "2 第二章标题"), chapters.map { it.title })
    }

    @Test
    fun scansFinalChapterWithoutTrailingNewline() {
        val source = "1 第一章标题\\n正文内容足够长，用于拉开章节偏移。" + "文字".repeat(60) + "\\n2 最后一章"
        val bytes = source.toByteArray(Charsets.UTF_8)
        val chapters = TxtParser.scanChapters(
            inputStream = ByteArrayInputStream(bytes),
            charsetName = Charsets.UTF_8.name()
        )
        assertEquals("2 最后一章", chapters.last().title)
    }
}
''',
    encoding="utf-8",
)

ui_test = root / "app/src/test/java/com/simplereader/app/ui/ReaderCatalogAndProgressUiContractTest.kt"
ui_test.parent.mkdir(parents=True, exist_ok=True)
ui_test.write_text(
    '''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatalogAndProgressUiContractTest {
    private fun readerSource(): String =
        File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    private fun readerLayout(): String =
        File("src/main/res/layout/activity_reader.xml").readText()

    @Test
    fun topSearchUsesMagnifyingGlassIcon() {
        val text = readerSource()
        assertTrue(text.contains("setIcon(android.R.drawable.ic_menu_search)"))
    }

    @Test
    fun bottomReaderProgressIsPageLabelWithoutSeekbar() {
        val source = readerSource()
        val layout = readerLayout()
        assertFalse(source.contains("readerProgressBar"))
        assertFalse(layout.contains("@+id/readerProgressBar"))
        assertTrue(layout.contains("android:text=\"1/1\""))
        assertTrue(source.contains("private fun pageCountLabel()"))
        assertTrue(source.contains("return \"$currentPage/$totalPages\""))
    }
}
''',
    encoding="utf-8",
)

print("catalog, encoding, search icon, and page label patch applied")
