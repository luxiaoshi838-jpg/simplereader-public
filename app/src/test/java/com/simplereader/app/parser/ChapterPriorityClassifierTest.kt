package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPriorityClassifierTest {
    @Test
    fun requestedFamiliesCoexistAtOnePriority() {
        val cases = listOf(
            "第一章 开始",
            "第一话.开始！",
            "第一节 开始",
            "一标题",
            "一.标题",
            "（一）标题",
            "（一）.标题",
            "标题（一）",
            "标题一"
        )
        cases.forEach { line ->
            assertEquals(line, ChapterPriorityClassifier.UNIFIED_STRUCTURED, ChapterPriorityClassifier.priority(line))
        }
    }

    @Test
    fun onlyExplicitJieRejectsSentenceTerminatorsAmongExplicitForms() {
        listOf("第一节.标题", "第一节。标题", "第一节?标题", "第一节？标题", "第一节!标题", "第一节！标题")
            .forEach { line -> assertEquals(line, ChapterPriorityClassifier.INVALID, ChapterPriorityClassifier.priority(line)) }

        listOf("第一章.标题", "第一章。标题", "第一章?标题", "第一章！标题", "第一话.标题", "第一话？标题", "第一话!标题")
            .forEach { line -> assertEquals(line, ChapterPriorityClassifier.UNIFIED_STRUCTURED, ChapterPriorityClassifier.priority(line)) }
    }

    @Test
    fun v654BareSafetyAndWrappedPunctuationRemain() {
        assertEquals(ChapterPriorityClassifier.INVALID, ChapterPriorityClassifier.priority("一标题！"))
        assertEquals(ChapterPriorityClassifier.INVALID, ChapterPriorityClassifier.priority("标题一？"))
        assertEquals(ChapterPriorityClassifier.UNIFIED_STRUCTURED, ChapterPriorityClassifier.priority("一.标题！"))
        assertEquals(ChapterPriorityClassifier.UNIFIED_STRUCTURED, ChapterPriorityClassifier.priority("（一）标题！"))
        assertEquals(ChapterPriorityClassifier.UNIFIED_STRUCTURED, ChapterPriorityClassifier.priority("《一》！！！"))
    }

    @Test
    fun curlyDoubleQuotesStayRejected() {
        assertEquals(ChapterPriorityClassifier.INVALID, ChapterPriorityClassifier.priority("“第一章”"))
        assertEquals(ChapterPriorityClassifier.INVALID, ChapterPriorityClassifier.priority("“（一）标题”"))
    }

    @Test
    fun numberedMiddleDoesNotFallThroughToPlainText() {
        assertEquals(ChapterPriorityClassifier.INVALID, ChapterPriorityClassifier.priority("新的12个故事"))
        assertTrue(ChapterPriorityClassifier.priority("普通小标题") != ChapterPriorityClassifier.UNIFIED_STRUCTURED)
    }
}
