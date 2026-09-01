package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxtCatalogRule112Test {
    @Test fun keepsRequiredTitles() {
        assertEquals("大道之上（一）", TxtParser.extractStructuredChapterTitle("大道之上（一）"))
        assertEquals("大道之上（1）", TxtParser.extractStructuredChapterTitle("大道之上（1）"))
        assertEquals("一章 重逢", TxtParser.extractStructuredChapterTitle("一章 重逢"))
        assertEquals("12、归来", TxtParser.extractStructuredChapterTitle("12、归来"))
        assertEquals("（十二）篇 初遇", TxtParser.extractStructuredChapterTitle("（十二）篇 初遇"))
    }

    @Test fun rejectsFalseCatalogLines() {
        assertNull(TxtParser.extractStructuredChapterTitle("Road（1）"))
        assertNull(TxtParser.extractStructuredChapterTitle("大道 之上（一）"))
        assertNull(TxtParser.extractStructuredChapterTitle("这是正文（2026）吗"))
        assertNull(TxtParser.extractStructuredChapterTitle("2026"))
        assertNull(TxtParser.extractStructuredChapterTitle("这是正文。第12回 继续说"))
        assertNull(TxtParser.extractStructuredChapterTitle("第12章。这是正文"))
    }
}
