package com.simplereader.app.reader.page

import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageEngineTest {
    private fun settings(textSize: Float = 40f, lineMultiplier: Float = 1.75f) = ReaderLayoutSettings(
        viewportWidthPx = 1080,
        viewportHeightPx = 1920,
        contentPaddingLeftPx = 84,
        contentPaddingTopPx = 78,
        contentPaddingRightPx = 84,
        contentPaddingBottomPx = 0,
        textSizePx = textSize,
        typefaceKey = "default",
        lineSpacingExtraPx = 0f,
        lineSpacingMultiplier = lineMultiplier
    )

    @Test
    fun chaptersAlwaysStartOnTheirOwnPage() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val chapterOne = "第一章 开始\n" + "正文内容。".repeat(120)
        val chapterTwo = "第二章 继续\n" + "下一章内容。".repeat(120)
        val text = chapterOne + chapterTwo
        val result = PageEngine.paginate(
            text,
            listOf(
                BookChapter("第一章 开始", 0, chapterOne.length),
                BookChapter("第二章 继续", chapterOne.length, text.length)
            ),
            settings(),
            Typeface.DEFAULT
        )
        val secondFirst = result.pages.first { it.chapterIndex == 1 }
        assertEquals(0, secondFirst.pageIndexInChapter)
        assertEquals(chapterOne.length, secondFirst.startOffset)
        assertEquals(secondFirst.globalPageIndex, result.firstPageOfChapter(1))
        assertTrue(result.pages.filter { it.chapterIndex == 0 }.all { it.endOffset <= chapterOne.length })
    }

    @Test
    fun everyFeatureResolvesTheSameGlobalPageForAnOffset() {
        val text = ("第1章\n" + "abc中文。".repeat(700))
        val result = PageEngine.paginate(
            text,
            listOf(BookChapter("第1章", 0, text.length)),
            settings(),
            Typeface.DEFAULT
        )
        result.pages.forEach { page ->
            val probe = page.startOffset.coerceAtMost((page.endOffset - 1).coerceAtLeast(page.startOffset))
            assertEquals(page.globalPageIndex, result.pageForOffset(probe).globalPageIndex)
            assertEquals(result.pages.size, page.totalPageCount)
        }
    }

    @Test
    fun paginationHashChangesOnlyForLayoutSettings() {
        val base = settings()
        assertNotEquals(base.stableHash(), settings(textSize = 44f).stableHash())
        assertNotEquals(base.stableHash(), settings(lineMultiplier = 1.5f).stableHash())
        assertEquals(base.stableHash(), base.copy().stableHash())
    }
}
