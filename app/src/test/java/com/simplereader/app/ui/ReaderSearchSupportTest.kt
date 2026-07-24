package com.simplereader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSearchSupportTest {
    @Test
    fun choosesMatchNearestToRequestedAnchor() {
        val text = "目标文字 前文 前文 目标文字 后文"
        assertEquals(0, ReaderSearchSupport.nearestMatch(text, "目标文字", 1))
        assertEquals(11, ReaderSearchSupport.nearestMatch(text, "目标文字", 16))
    }

    @Test
    fun ignoresCaseByDefault() {
        assertEquals(7, ReaderSearchSupport.nearestMatch("before TARGET after", "target", 9))
    }

    @Test
    fun returnsMinusOneWhenMissing() {
        assertEquals(-1, ReaderSearchSupport.nearestMatch("正文", "不存在", 0))
    }
}
