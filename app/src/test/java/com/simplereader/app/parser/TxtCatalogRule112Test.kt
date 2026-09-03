package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kept under the historical file/class name so inherited CI still executes it; assertions are rule113. */
class TxtCatalogRule112Test {
    @Test fun keepsRequiredTitles() {
        assertEquals("大道之上（一）", TxtParser.extractStructuredChapterTitle("大道之上（一）"))
        assertEquals("大道之上（1）", TxtParser.extractStructuredChapterTitle("大道之上（1）"))
        assertEquals("一章 重逢", TxtParser.extractStructuredChapterTitle("一章 重逢"))
        assertEquals("十二章 归来", TxtParser.extractStructuredChapterTitle("十二章 归来"))
        assertEquals("3节", TxtParser.extractStructuredChapterTitle("3节"))
        assertEquals("3节：课程安排", TxtParser.extractStructuredChapterTitle("3节：课程安排"))
        assertEquals("第5回", TxtParser.extractStructuredChapterTitle("第5回"))
        assertEquals("12、归来", TxtParser.extractStructuredChapterTitle("12、归来"))
        assertEquals("一、归来", TxtParser.extractStructuredChapterTitle("一、归来"))
        assertEquals("（十二）篇 初遇", TxtParser.extractStructuredChapterTitle("（十二）篇 初遇"))
    }

    @Test fun rejectsNumeralPlusOrdinaryClassifierOrNoun() {
        val falseTitles = listOf(
            "一条", "一朵", "一天", "两人", "三次", "十年", "百里",
            "1天", "2人", "3次", "12公里",
            "3节课", "12章鱼", "2回家", "5部队",
            "第3节课", "第12章鱼", "第2回家", "第5部队"
        )
        falseTitles.forEach { line ->
            assertNull("structured false-positive: $line", TxtParser.extractStructuredChapterTitle(line))
            assertNull("fallback false-positive: $line", TxtParser.extractFallbackChapterTitle(line))
            assertNull("combined false-positive: $line", TxtParser.extractChapterTitle(line))
        }
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
