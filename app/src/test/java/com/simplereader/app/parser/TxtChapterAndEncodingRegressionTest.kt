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

    @Test
    fun mappedRangeUsesExactGb18030AndCrLfByteAnchors() {
        val source = "第1章 标题\r\n甲A乙\r\n第2章 标题"
        val charset = Charset.forName("GB18030")
        val bytes = source.toByteArray(charset)
        val mapped = TxtParser.readRangeMapped(
            inputStream = ByteArrayInputStream(bytes),
            charsetName = charset.name(),
            startByte = 0L,
            endByte = bytes.size.toLong()
        )

        assertEquals(source.replace("\r\n", "\n"), mapped.text)
        assertEquals(mapped.text.length + 1, mapped.sourceOffsets.size)
        val secondChapter = mapped.text.indexOf("第2章")
        val expectedSecondChapterByte = source.indexOf("第2章")
            .let { source.substring(0, it).toByteArray(charset).size.toLong() }
        assertEquals(expectedSecondChapterByte, mapped.sourceOffsets[secondChapter])
        assertEquals(bytes.size.toLong(), mapped.sourceOffsets.last())
        assertTrue(
            (1 until mapped.sourceOffsets.size).all { index ->
                mapped.sourceOffsets[index] >= mapped.sourceOffsets[index - 1]
            }
        )
    }

    @Test
    fun mappedRangePreservesUtf8FourByteCharacterBoundary() {
        val source = "第1章 𠀀测试\n正文"
        val bytes = source.toByteArray(Charsets.UTF_8)
        val mapped = TxtParser.readRangeMapped(
            inputStream = ByteArrayInputStream(bytes),
            charsetName = Charsets.UTF_8.name(),
            startByte = 0L,
            endByte = bytes.size.toLong()
        )
        val afterRareCharacter = source.indexOf("𠀀") + "𠀀".length
        val expected = source.substring(0, afterRareCharacter).toByteArray(Charsets.UTF_8).size.toLong()
        assertEquals(expected, mapped.sourceOffsets[afterRareCharacter])
    }
}
