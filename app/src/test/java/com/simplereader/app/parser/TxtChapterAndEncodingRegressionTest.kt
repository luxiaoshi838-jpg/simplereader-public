package com.simplereader.app.parser

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
        val source = "1 第一章标题\n$longBody\n2 第二章标题\n第二段正文。"
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
        val source = "1 第一章标题\n正文内容足够长，用于拉开章节偏移。" + "文字".repeat(60) + "\n2 最后一章"
        val bytes = source.toByteArray(Charsets.UTF_8)
        val chapters = TxtParser.scanChapters(
            inputStream = ByteArrayInputStream(bytes),
            charsetName = Charsets.UTF_8.name()
        )
        assertEquals("2 最后一章", chapters.last().title)
    }
}
