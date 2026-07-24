package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class TxtContinuousReadingTest {
    @Test
    fun `sequential windows preserve every line without mojibake or blocking`() {
        val utf8 = Charsets.UTF_8
        val gb = Charset.forName("GB18030")
        val expected = StringBuilder()
        val raw = ByteArrayOutputStream().apply {
            repeat(900) { index ->
                val line = when {
                    index % 11 == 0 -> "第${index}章 UTF-8正文：山河湖海，继续向下阅读。\n"
                    index % 7 == 0 -> "第${index}章 GB正文：没有乱码，也不能假装到达文末。\n"
                    else -> "普通段落${index}：连续滑动测试内容。\n"
                }
                expected.append(line)
                write(line.toByteArray(if (index % 7 == 0 && index % 11 != 0) gb else utf8))
            }
        }.toByteArray()

        var offset = 0L
        val joined = StringBuilder()
        var guard = 0
        while (offset < raw.size && guard++ < 2000) {
            val window = TxtParser.readWindow(
                ByteArrayInputStream(raw),
                "UTF-8",
                offset,
                1024
            )
            assertTrue("reader must always advance", window.nextByte > offset)
            joined.append(window.text)
            offset = window.nextByte
        }

        val text = joined.toString()
        assertEquals(raw.size.toLong(), offset)
        assertEquals(expected.toString(), text)
        assertTrue(text.contains("第0章 UTF-8正文"))
        assertTrue(text.contains("第896章 GB正文"))
        assertTrue(text.contains("普通段落899"))
        assertFalse(text.contains('\uFFFD'))
        assertFalse(text.contains("锟斤拷"))
    }

    @Test
    fun `previous window ends at current safe boundary`() {
        val raw = buildString {
            repeat(300) { append("第${it}章 这是完整的一行中文内容。\n") }
        }.toByteArray(Charsets.UTF_8)
        val current = TxtParser.readWindow(ByteArrayInputStream(raw), "UTF-8", 3200, 1500)
        val previous = TxtParser.readWindowBefore(
            ByteArrayInputStream(raw),
            "UTF-8",
            current.startByte,
            1500
        )
        assertEquals(current.startByte, previous.nextByte)
        assertFalse(previous.text.contains('\uFFFD'))
        assertTrue(previous.startByte < previous.nextByte)
    }
}
