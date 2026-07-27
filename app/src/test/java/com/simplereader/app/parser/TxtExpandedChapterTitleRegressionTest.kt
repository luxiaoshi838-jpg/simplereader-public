package com.simplereader.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtExpandedChapterTitleRegressionTest {
    @Test
    fun recognizesChineseNumeralAndPlainStandaloneChapterNames() {
        assertTrue(TxtParser.isLikelyChapterTitle("（一）章"))
        assertTrue(TxtParser.isLikelyChapterTitle("（十二）篇 初遇"))
        assertTrue(TxtParser.isLikelyChapterTitle("一章 重逢"))
        assertTrue(TxtParser.isLikelyChapterTitle("春风化雨篇"))
        assertTrue(TxtParser.isLikelyChapterTitle("山海之约"))
        assertTrue(TxtParser.isLikelyChapterTitle("故人之女"))
    }

    @Test
    fun rejectsPunctuatedSentencesThatOnlyResembleStandaloneTitles() {
        assertFalse(TxtParser.isLikelyChapterTitle("春风化雨篇。"))
        assertFalse(TxtParser.isLikelyChapterTitle("山海之约，后来再见"))
        assertFalse(TxtParser.isLikelyChapterTitle("这是一篇普通正文"))
    }
}
