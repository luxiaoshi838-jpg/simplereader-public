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
        assertTrue(TxtParser.CATALOG_RULE_VERSION >= 117)
    }
    @Test
    fun pureArabicAndChineseNumeralsAreAllowedButNumericPercentOrPunctuationNoiseIsRejected() {
        val accepted = listOf(
            "123",
            "１２３",
            "001",
            "００１",
            "一",
            "十二",
            "一百零二",
            "壹佰贰拾叁",
            "（十二）",
            "【十二】",
            "「十二」",
            "《十二》",
            "（12）",
            "【12】",
            "「12」",
            "《12》",
            "(12)"
        )
        accepted.forEach { line ->
            assertEquals("pure digits may be a chapter: $line", line, TxtParser.extractStructuredChapterTitle(line))
        }

        val rejected = listOf(
            "123%",
            "123％",
            "123。",
            "123！",
            "123？？",
            "123.456%",
            "123.456%！",
            "１２３．４５６％。",
            "(123)",
            "【123】",
            "123——",
            "十二%",
            "十二％",
            "十二。",
            "十二！",
            "十二——"
        )
        rejected.forEach { line ->
            assertNull("numeric punctuation/percent noise must not be a chapter: $line", TxtParser.extractChapterTitle(line))
        }

        assertEquals("1、标题", TxtParser.extractStructuredChapterTitle("1、标题"))
        assertEquals("12.标题", TxtParser.extractStructuredChapterTitle("12.标题"))
        assertEquals("一、归来", TxtParser.extractStructuredChapterTitle("一、归来"))
        assertEquals("十二.标题", TxtParser.extractStructuredChapterTitle("十二.标题"))

        val source = listOf(
            "123",
            "正文一。",
            "123.456%！",
            "第1章 正常章节",
            "正文二。",
            "１２３。",
            "第2章 第二章"
        ).joinToString("\n")
        val hits = TxtParser.scanChapters(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8.name()
        ).map { it.title }
        assertEquals(listOf("123", "第1章 正常章节", "第2章 第二章"), hits)
    }

    @Test
    fun consecutiveChapterHeadingsWithoutBodyKeepOnlyTheFirst() {
        val source = listOf(
            "第1章 第一标题",
            "",
            "第2章 第二标题",
            "   ",
            "前缀——第3章：第三标题！",
            "这里开始是真正正文。",
            "第4章 第四标题",
            "正文四。",
            "第5章 第五标题"
        ).joinToString("\n")

        val direct = com.simplereader.app.reader.DirectTxtCatalogV100.detect(source)
            .filter { it.catalogVisible }
            .map { it.title }
        assertEquals(
            listOf("第1章 第一标题", "第4章 第四标题", "第5章 第五标题"),
            direct
        )

        val scanned = TxtParser.scanChapters(
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8.name()
        ).map { it.title }
        assertEquals(
            listOf("第1章 第一标题", "第4章 第四标题", "第5章 第五标题"),
            scanned
        )
    }

    @Test
    fun nonblankBodyResetsConsecutiveHeadingSuppression() {
        val source = "第1章 A\n正文。\n第2章 B\n正文。\n第3章 C"
        val direct = com.simplereader.app.reader.DirectTxtCatalogV100.detect(source)
            .filter { it.catalogVisible }
            .map { it.title }
        assertEquals(listOf("第1章 A", "第2章 B", "第3章 C"), direct)
    }
}
