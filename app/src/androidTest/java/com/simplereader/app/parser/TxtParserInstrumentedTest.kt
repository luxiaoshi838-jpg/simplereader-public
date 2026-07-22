package com.simplereader.app.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TxtParserInstrumentedTest {
    @Test
    fun detectsCommonNovelChapterTitles() {
        val titles = listOf(
            "第1809章 天帝对决天皇",
            "第 1810 章 第三世",
            "第十二回 风起",
            "卷三 天外",
            "12、归来",
            "Chapter 12 The Door",
            "番外 梦醒"
        )

        titles.forEach { title ->
            assertEquals(title, TxtParser.extractChapterTitle(title))
        }
    }

    @Test
    fun rejectsOrdinaryParagraphs() {
        assertNull(TxtParser.extractChapterTitle("他洞悉了仙域本源的秘密。"))
        assertNull(TxtParser.extractChapterTitle("https://example.com/chapter/1"))
        assertNull(TxtParser.extractChapterTitle("这是一个很长的普通句子，它包含很多标点，所以不应该被当成章节标题。"))
    }

    @Test
    fun likelyChapterDelegatesToExtractor() {
        assertNotNull(TxtParser.extractChapterTitle("第1章 开始"))
        assertEquals(true, TxtParser.isLikelyChapterTitle("第1章 开始"))
    }
}
