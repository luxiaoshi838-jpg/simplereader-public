package com.simplereader.app.reader.page

import android.graphics.Typeface
import java.util.concurrent.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageEngineCancellationTest {
    private fun settings() = ReaderLayoutSettings(
        viewportWidthPx = 1080,
        viewportHeightPx = 1920,
        contentPaddingLeftPx = 84,
        contentPaddingTopPx = 78,
        contentPaddingRightPx = 84,
        contentPaddingBottomPx = 0,
        textSizePx = 40f,
        typefaceKey = "default",
        lineSpacingExtraPx = 0f,
        lineSpacingMultiplier = 1.75f
    )

    @Test
    fun cooperativeProbeCanAbortWholeBookPaginationBeforeLayoutFinishes() {
        val text = "第一章\n" + "正文内容。".repeat(250_000)
        var probes = 0
        var cancelled = false
        try {
            PageEngine.paginate(
                text = text,
                sourceChapters = listOf(BookChapter("第一章", 0, text.length)),
                settings = settings(),
                typeface = Typeface.DEFAULT,
                shouldCancel = {
                    probes += 1
                    probes >= 2
                }
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("pagination must observe the cooperative cancellation probe", cancelled)
        assertTrue("probe must be checked more than once during pagination setup", probes >= 2)
    }
}
