from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8-sig")


def write(path: str, text: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


reader_path = "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
reader = read(reader_path)

reader = replace_once(
    reader,
    '''                            "CHM" -> {
                                val entries = ChmParser.readChapterIndex(input)
                                val chapters = entries.map { path ->
                                    EpubChapter(
                                        name = path,
                                        text = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path }
                                    )
                                }
''',
    '''                            "CHM" -> {
                                val chapters = ChmParser.readChapterIndex(input).map { chapter ->
                                    EpubChapter(
                                        name = chapter.path,
                                        text = chapter.title
                                    )
                                }
''',
    "ReaderActivity CHM chapter mapping",
)

reader = replace_once(
    reader,
    '''        findViewById<TextView>(R.id.nightButton).setOnClickListener {
            applyReaderPalette(ReaderAppearance.NIGHT_BACKGROUND, ReaderAppearance.NIGHT_TEXT)
        }
''',
    '''        findViewById<TextView>(R.id.nightButton).setOnClickListener {
            val palette = ReaderAppearance.nextDayNightPalette(currentBackgroundColor)
            applyReaderPalette(palette.backgroundColor, palette.textColor)
        }
''',
    "ReaderActivity day/night click",
)

reader = replace_once(
    reader,
    '''    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
        currentBackgroundColor = backgroundColor
        currentTextColor = textColor
        contentView.setBackgroundColor(backgroundColor)
        contentView.setTextColor(textColor)
        window.decorView.setBackgroundColor(backgroundColor)
        ReaderAppearance.savePalette(this, backgroundColor, textColor)
    }
''',
    '''    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
        currentBackgroundColor = backgroundColor
        currentTextColor = textColor
        contentView.setBackgroundColor(backgroundColor)
        contentView.setTextColor(textColor)
        readerScrollView.setBackgroundColor(backgroundColor)
        window.decorView.setBackgroundColor(backgroundColor)
        ReaderAppearance.savePalette(this, backgroundColor, textColor)
        findViewById<TextView>(R.id.nightButton).apply {
            text = ReaderAppearance.dayNightButtonLabel(backgroundColor)
            contentDescription = "${text}模式"
        }
    }
''',
    "ReaderActivity apply palette",
)

old_title = '''                            val title = chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
'''
new_title = '''                            val title = chapter.text.ifBlank {
                                chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
                            }
'''
count = reader.count(old_title)
if count != 1:
    raise SystemExit(f"ReaderActivity catalog chapter title: expected 1 match, found {count}")
reader = reader.replace(old_title, new_title, 1)

old_toc = '''            val title = chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
'''
new_toc = '''            val title = chapter.text.ifBlank {
                chapter.name.substringAfterLast('/').ifBlank { "章节 ${index + 1}" }
            }
'''
count = reader.count(old_toc)
if count != 1:
    raise SystemExit(f"ReaderActivity table-of-contents title: expected 1 match, found {count}")
reader = reader.replace(old_toc, new_toc, 1)
write(reader_path, reader)

main_path = "app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
main = read(main_path)
main = replace_once(
    main,
    '''        val cover = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(232, 229, 220))
            sortedBooks.take(4).forEach { book ->
                addView(createBookCover(book.title, book.format, compact = true))
            }
            repeat((4 - sortedBooks.take(4).size).coerceAtLeast(0)) {
                addView(TextView(this@MainActivity).apply {
                    setBackgroundColor(Color.rgb(205, 202, 194))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(42)
                        height = dp(48)
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                })
            }
        }
''',
    '''        val cover = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(232, 229, 220))
            repeat(4) { index ->
                val preview = sortedBooks.getOrNull(index)?.let { book ->
                    createBookCover(book.title, book.format, compact = true)
                } ?: TextView(this@MainActivity).apply {
                    setBackgroundColor(Color.rgb(205, 202, 194))
                }
                preview.layoutParams = groupPreviewCellLayoutParams(index)
                addView(preview)
            }
        }
''',
    "MainActivity group preview grid",
)

marker = '''    private fun createBookCover(title: String, format: String, compact: Boolean): TextView {
'''
helper = '''    private fun groupPreviewCellLayoutParams(index: Int): GridLayout.LayoutParams {
        return GridLayout.LayoutParams(
            GridLayout.spec(index / 2, 1, 1f),
            GridLayout.spec(index % 2, 1, 1f)
        ).apply {
            width = 0
            height = 0
            setGravity(Gravity.FILL)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

'''
if main.count(marker) != 1:
    raise SystemExit("MainActivity createBookCover marker missing or duplicated")
main = main.replace(marker, helper + marker, 1)
main = replace_once(
    main,
    '''            maxLines = if (compact) 4 else 5
            setPadding(dp(6), dp(8), dp(6), dp(8))
''',
    '''            maxLines = if (compact) 4 else 5
            includeFontPadding = false
            setPadding(dp(6), dp(8), dp(6), dp(8))
''',
    "MainActivity compact cover typography",
)
write(main_path, main)

write(
    "app/src/test/java/com/simplereader/app/parser/EpubParserTest.kt",
    r'''package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class EpubParserTest {
    @Test
    fun `uses OPF spine order instead of zip entry order`() {
        val bytes = sampleEpub()
        val chapters = EpubParser.readChapterIndex(ByteArrayInputStream(bytes))

        assertEquals(listOf("第一章", "第二章"), chapters.map { it.text })
        assertTrue(EpubParser.readChapterText(ByteArrayInputStream(bytes), chapters[0].name).contains("第一章正文"))
        assertTrue(EpubParser.readChapterText(ByteArrayInputStream(bytes), chapters[1].name).contains("第二章正文"))
    }

    private fun sampleEpub(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".trimIndent()
            )
            // Deliberately store chapter 2 before chapter 1 in the ZIP.
            entry("OEBPS/ch2.xhtml", "<html><head><title>第二章</title></head><body><h1>第二章</h1><p>第二章正文</p></body></html>")
            entry("OEBPS/ch1.xhtml", "<html><head><title>第一章</title></head><body><h1>第一章</h1><p>第一章正文</p></body></html>")
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">test</dc:identifier><dc:title>测试</dc:title><dc:language>zh</dc:language></metadata>
                      <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>""".trimIndent()
            )
        }
        return output.toByteArray()
    }
}
''',
)

write(
    "app/src/test/java/com/simplereader/app/ui/ReaderAppearanceTest.kt",
    r'''package com.simplereader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAppearanceTest {
    @Test
    fun `day-night button toggles in both directions`() {
        val night = ReaderAppearance.nextDayNightPalette(ReaderAppearance.DAY_BACKGROUND)
        assertEquals(ReaderAppearance.NIGHT_BACKGROUND, night.backgroundColor)
        assertEquals(ReaderAppearance.NIGHT_TEXT, night.textColor)

        val day = ReaderAppearance.nextDayNightPalette(ReaderAppearance.NIGHT_BACKGROUND)
        assertEquals(ReaderAppearance.DAY_BACKGROUND, day.backgroundColor)
        assertEquals(ReaderAppearance.DAY_TEXT, day.textColor)
    }
}
''',
)

notice_path = "THIRD_PARTY_NOTICES.md"
notice = read(notice_path)
addition = '''

## Structured document parsers

- `documentnode/epub4j` core, Apache License 2.0: EPUB 2/3 container, OPF manifest and spine parsing on Android.
- `chimenchen/jchmlib` v0.5.4, Apache License 2.0: CHM decompression, detected encoding, topics tree and title mapping.

The application keeps its own database and `SimpleReaderBackup` schema version 1; these parser changes do not alter backup fields.
'''
if "## Structured document parsers" not in notice:
    notice = notice.rstrip() + addition
write(notice_path, notice.rstrip() + "\n")

print("v13 UI and reader patches applied successfully")
