from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser_path = root / "app/src/main/java/com/simplereader/app/parser/TxtParser.kt"
parser = parser_path.read_text(encoding="utf-8")
anchor = '            Regex("^\\\\s*[（(【\\\\[]?[0-9]{1,5}[）)】\\\\]]?\\\\s*[、.．:：—-]\\\\s*\\\\S+.*"),\n'
inserted = anchor + '            Regex("^\\\\s*[（(【\\\\[][0-9]{1,5}[）)】\\\\]]\\\\s+\\\\S+.*"),\n'
if anchor in parser and inserted not in parser:
    parser = parser.replace(anchor, inserted, 1)
elif inserted not in parser:
    raise SystemExit("parenthesized chapter pattern target missing")
parser_path.write_text(parser, encoding="utf-8")

path = root / "app/src/test/java/com/simplereader/app/parser/TxtChapterAndEncodingRegressionTest.kt"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(
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
        val longBody = "这是中文正文，不应该出现乱码。" + "用于拉开章节距离的中文内容。".repeat(12)
        val source = "1 第一章标题\\n$longBody\\n2 第二章标题\\n第二段正文。"
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
print("parenthesized catalog pattern and realistic fixture applied")
