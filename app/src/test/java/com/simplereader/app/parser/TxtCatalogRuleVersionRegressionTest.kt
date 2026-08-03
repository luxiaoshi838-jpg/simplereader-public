package com.simplereader.app.parser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtCatalogRuleVersionRegressionTest {
    @Test
    fun recognizesPrefixedAndExpandedStructureTitles() {
        assertEquals("770.第767章 宝瓶", TxtParser.extractStructuredChapterTitle("770.第767章 宝瓶"))
        assertEquals("前传：第十章 故人", TxtParser.extractStructuredChapterTitle("前传：第十章 故人"))
        assertEquals("第十二单元 归途", TxtParser.extractStructuredChapterTitle("第十二单元 归途"))
        assertEquals("卷十二 风起", TxtParser.extractStructuredChapterTitle("卷十二 风起"))
        assertEquals("宝瓶（七）", TxtParser.extractStructuredChapterTitle("宝瓶（七）"))
    }

    @Test
    fun structuredTitlesSuppressPlainLineFallbacks() {
        val body = "这是正文内容，有标点，因此不能成为目录。".repeat(12)
        val source = "山海之约\n$body\n770.第767章 宝瓶\n$body"
        val hits = TxtParser.scanChapters(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8.name()
        )
        assertEquals(listOf("770.第767章 宝瓶"), hits.map { it.title })
    }

    @Test
    fun plainUnpunctuatedLinesAreUsedOnlyAsLastResort() {
        val body = "这是正文内容，有标点，因此不能成为目录。".repeat(12)
        val source = "山海之约\n$body\n故人之女\n$body"
        val hits = TxtParser.scanChapters(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8.name()
        )
        assertEquals(listOf("山海之约", "故人之女"), hits.map { it.title })
        assertTrue(TxtParser.CATALOG_RULE_VERSION > 0)
    }
}
