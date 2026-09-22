package com.simplereader.app.parser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtCatalogRule116Test {
    @Test
    fun independentChapterMarkerIgnoresWholeLineLengthAndPunctuation() {
        val longPrefix = "前置说明，含有各种标点？！——".repeat(4)
        val longSuffix = "这是一个很长很长的章节标题，允许继续包含句号。问号？感叹号！以及其他标点……".repeat(3)
        val accepted = listOf(
            "第12章 $longSuffix",
            "$longPrefix 第十二章 $longSuffix",
            "$longPrefix；第001章：$longSuffix",
            "卷外说明——第 0 1 章【特别篇】：$longSuffix",
            "第九十九章。",
            "任意前缀！！！第十章★特别篇"
        )

        accepted.forEach { line ->
            assertEquals("Rule116 should accept: $line", CatalogTitleNormalizerV103.normalize(line), TxtParser.extractStructuredChapterTitle(line))
        }
    }

    @Test
    fun gluedChapterWordsRemainRejected() {
        val rejected = listOf(
            "第12章鱼",
            "第12章鱼很好吃。",
            "第3章程",
            "第3章程必须遵守。",
            "前言 第12章鱼 后记",
            "说明：第3章程，正文继续",
            "章鱼",
            "公司章程"
        )
        rejected.forEach { line ->
            assertNull("glued ordinary word must stay prose: $line", TxtParser.extractStructuredChapterTitle(line))
        }
    }

    @Test
    fun scanChaptersUsesRule116InFinalChapterList() {
        val longPrefix = "很长的前缀，带标点。".repeat(8)
        val chapter = "$longPrefix 第十八章：真正目录标题？！" + "继续很长".repeat(20)
        val source = listOf(
            "普通正文。",
            "第12章鱼很好吃。",
            chapter,
            "正文继续。"
        ).joinToString("\n")

        val hits = TxtParser.scanChapters(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8.name()
        )

        assertEquals(listOf(CatalogTitleNormalizerV103.normalize(chapter)), hits.map { it.title })
        assertTrue(TxtParser.CATALOG_RULE_VERSION >= 116)
    }
}
