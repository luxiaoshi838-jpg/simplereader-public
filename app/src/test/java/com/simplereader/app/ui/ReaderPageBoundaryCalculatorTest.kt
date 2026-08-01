package com.simplereader.app.ui

import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPageBoundaryCalculatorTest {
    private val signature = ReaderLayoutSignature(
        widthPx = 1080,
        heightPx = 2400,
        textSizePx = 48,
        lineSpacingMultiplierX100 = 175,
        horizontalPaddingPx = 84,
        topPaddingPx = 150,
        bottomPaddingPx = 180,
        viewportWidthPx = 1080,
        viewportHeightPx = 2400
    )

    @Before
    fun initializeAndroidLayoutRuntime() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
    }

    @Test
    fun lowMemoryBoundariesMatchVisiblePaginatorStarts() {
        val text = buildString {
            append("第695章 文始真人\n")
            repeat(500) { index -> append("用于固定分页边界的正文第${index}段。\n") }
        }
        val visible = ReaderTextPaginator.paginate(
            chapterIndex = 694,
            text = text,
            signature = signature,
            typeface = Typeface.DEFAULT,
            lineSpacingMultiplier = 1.75f,
            sourceOffsetForCharacter = { 9_000_000L + it * 3L }
        )
        val cached = ReaderPageBoundaryCalculator.calculate(
            text = text,
            signature = signature,
            typeface = Typeface.DEFAULT,
            lineSpacingMultiplier = 1.75f,
            sourceOffsetForCharacter = { 9_000_000L + it * 3L }
        )

        assertTrue(visible.size > 1)
        assertArrayEquals(
            visible.map { it.startAnchor.chapterOffset }.toIntArray(),
            cached.chapterOffsets
        )
        assertArrayEquals(
            visible.map { it.startAnchor.sourceOffset }.toLongArray(),
            cached.sourceOffsets
        )
        assertEquals(visible.size, cached.pageCount)
    }

    @Test
    fun boundaryResultContainsNoPageTextSnapshots() {
        val text = "第1章 标题\n" + "正文。\n".repeat(1200)
        val result = ReaderPageBoundaryCalculator.calculate(text, signature)

        assertTrue(result.chapterOffsets.isNotEmpty())
        assertEquals(result.chapterOffsets.size, result.sourceOffsets.size)
        assertTrue(ReaderPageBoundaries::class.java.declaredFields.none { field ->
            CharSequence::class.java.isAssignableFrom(field.type)
        })
    }
}
