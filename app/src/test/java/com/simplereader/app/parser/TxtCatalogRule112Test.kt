package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Historical class name retained so inherited CI keeps executing it; assertions are rule113. */
class TxtCatalogRule112Test {
    @Test fun keepsRequiredTitles() {
        val accepted = listOf(
            "大道之上（一）",
            "大道之上（1）",
            "一章 重逢",
            "十二章 归来",
            "3节",
            "3节 课",
            "3节：课",
            "3节、课",
            "3节-课",
            "第3节 课",
            "第3节：课",
            "12章 鱼",
            "12章：鱼",
            "2回 家",
            "5部：队",
            "第5回",
            "12、归来",
            "一、归来",
            "（十二）篇 初遇"
        )
        accepted.forEach { line ->
            assertEquals("expected structured title: $line", line, TxtParser.extractStructuredChapterTitle(line))
        }
    }

    @Test fun rejectsNumeralPlusOrdinaryClassifierOrNounWithoutChapterStructure() {
        val falseTitles = listOf(
            "一条", "一朵", "一天", "两人", "三次", "十年", "百里",
            "一 条", "三 天", "两 人", "十 年", "百 里",
            "1天", "2人", "3次", "12公里",
            "1 天", "2 人", "3 次", "12 公里",
            "3节课", "12章鱼", "2回家", "5部队",
            "第3节课", "第12章鱼", "第2回家", "第5部队"
        )
        falseTitles.forEach { line ->
            assertNull("structured false-positive: $line", TxtParser.extractStructuredChapterTitle(line))
            assertNull("fallback false-positive: $line", TxtParser.extractFallbackChapterTitle(line))
            assertNull("combined false-positive: $line", TxtParser.extractChapterTitle(line))
        }
    }

    @Test fun structuralUnitRequiresBoundaryNotSpecificFollowingWord() {
        // Same following word, different boundary: glued form is prose; whitespace/punctuation makes
        // the chapter unit independent and therefore a valid heading structure.
        assertNull(TxtParser.extractStructuredChapterTitle("3节课"))
        assertEquals("3节 课", TxtParser.extractStructuredChapterTitle("3节 课"))
        assertEquals("3节：课", TxtParser.extractStructuredChapterTitle("3节：课"))
        assertEquals("3节、课", TxtParser.extractStructuredChapterTitle("3节、课"))

        assertNull(TxtParser.extractStructuredChapterTitle("第3节课"))
        assertEquals("第3节 课", TxtParser.extractStructuredChapterTitle("第3节 课"))
        assertEquals("第3节：课", TxtParser.extractStructuredChapterTitle("第3节：课"))

        assertNull(TxtParser.extractStructuredChapterTitle("12章鱼"))
        assertEquals("12章 鱼", TxtParser.extractStructuredChapterTitle("12章 鱼"))
        assertEquals("12章：鱼", TxtParser.extractStructuredChapterTitle("12章：鱼"))
    }

    @Test fun rejectsOtherFalseCatalogLines() {
        assertNull(TxtParser.extractStructuredChapterTitle("Road（1）"))
        assertNull(TxtParser.extractStructuredChapterTitle("大道 之上（一）"))
        assertNull(TxtParser.extractStructuredChapterTitle("这是正文（2026）吗"))
        assertNull(TxtParser.extractStructuredChapterTitle("2026"))
        assertNull(TxtParser.extractStructuredChapterTitle("这是正文。第12回 继续说"))
        assertNull(TxtParser.extractStructuredChapterTitle("第12章。这是正文"))
    }
}
