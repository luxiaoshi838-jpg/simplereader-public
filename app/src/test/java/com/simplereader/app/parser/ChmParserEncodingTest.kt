package com.simplereader.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChmParserEncodingTest {
    @Test
    fun `decodes declared gb2312 page as gb18030`() {
        val html = "<html><head><meta charset=\"gb2312\"><title>第一章</title></head><body>绯梦之都正文测试</body></html>"
        val bytes = html.toByteArray(charset("GB18030"))
        val decoded = ChmParser.decodeDocumentBytes(bytes, "GBK")
        assertTrue(decoded.contains("第一章"))
        assertTrue(decoded.contains("绯梦之都正文测试"))
    }

    @Test
    fun `prefers clean gb18030 when archive hint is absent`() {
        val bytes = "第一章：中文内容".toByteArray(charset("GB18030"))
        val decoded = ChmParser.decodeDocumentBytes(bytes)
        assertTrue(decoded.contains("第一章"))
        assertTrue(decoded.contains("中文内容"))
    }

    @Test
    fun `extracts document write chapters and html entities`() {
        val source = """document.write("第一章\n"); document.write('正文&nbsp;内容\n下一行');"""

        val text = ChmParser.extractReadableText(source)

        assertTrue(text.contains("第一章"))
        assertTrue(text.contains("正文 内容"))
        assertTrue(text.contains("下一行"))
        assertFalse(text.contains("document.write"))
    }
}
