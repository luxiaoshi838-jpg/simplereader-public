package com.simplereader.app.reader.page

import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageEngineFirstPagePreviewTest {
    private fun settings() = ReaderLayoutSettings(
        viewportWidthPx = 1080,
        viewportHeightPx = 1920,
        contentPaddingLeftPx = 84,
        contentPaddingTopPx = 0,
        contentPaddingRightPx = 84,
        contentPaddingBottomPx = 0,
        textSizePx = 40f,
        typefaceKey = "default",
        lineSpacingExtraPx = 0f,
        lineSpacingMultiplier = 1.75f
    )

    @Test
    fun firstPagePreviewMatchesAuthoritativeFirstPageWithoutBuildingPartialBook() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = "第一章 开始\n" + "这是用于验证首次打开即时第一页的正文内容。".repeat(1800)
        val chapters = listOf(BookChapter("第一章 开始", 0, text.length))
        val preview = PageEngine.layoutFirstPage(text, chapters, settings(), Typeface.DEFAULT)
        val full = PageEngine.paginate(text, chapters, settings(), Typeface.DEFAULT)

        assertEquals(0, preview.chapterIndex)
        assertEquals(0, preview.startOffset)
        assertTrue(preview.endOffset > preview.startOffset)
        assertEquals(full.pages.first().startOffset, preview.startOffset)
        assertEquals(full.pages.first().endOffset, preview.endOffset)
        assertEquals(preview.endOffset - preview.startOffset, preview.content.length)
    }
}
