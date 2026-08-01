package com.simplereader.app.ui

import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTextPaginatorBehaviorTest {

    private val signature = ReaderLayoutSignature(
        widthPx = 1080,
        heightPx = 2400,
        textSizePx = 48,
        lineSpacingMultiplierX100 = 175,
        horizontalPaddingPx = 84,
        topPaddingPx = 150,
        bottomPaddingPx = 180,
        chapterTitleScaleX100 = 130,
        contentKey = 0L
    )

    @Test
    fun identicalLayoutProducesStablePageCountAndPageStarts() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = buildString {
            append("第695章 文始真人\n")
            repeat(320) { index ->
                append("这是用于验证真实排版页边界稳定性的正文第")
                append(index)
                append("段。章节内容不能按固定字符数估算。\n")
            }
        }

        val first = paginate(chapterIndex = 694, text = text)
        val second = paginate(chapterIndex = 694, text = text)

        assertTrue(first.size > 1)
        assertEquals(first.size, second.size)
        assertEquals(
            first.map { it.startAnchor.chapterOffset },
            second.map { it.startAnchor.chapterOffset }
        )
        assertEquals(
            first.map { it.endAnchor.chapterOffset },
            second.map { it.endAnchor.chapterOffset }
        )
        assertTrue(first.all { it.pageCountInChapter == first.size })
        assertEquals((0 until first.size).toList(), first.map { it.pageIndexInChapter })
    }

    @Test
    fun separatelyPaginatedChaptersNeverShareALogicalPage() {
        val previousChapter = "第694章 前章\n" + "前章末尾正文。".repeat(18)
        val nextChapter = "第695章 文始真人\n" + "下一章正文。".repeat(80)

        val previousPages = paginate(chapterIndex = 693, text = previousChapter)
        val nextPages = paginate(chapterIndex = 694, text = nextChapter)

        assertEquals(693, previousPages.last().startAnchor.chapterIndex)
        assertEquals(694, nextPages.first().startAnchor.chapterIndex)
        assertEquals(0, nextPages.first().pageIndexInChapter)
        assertEquals(0, nextPages.first().startAnchor.chapterOffset)
        assertEquals(previousChapter.length, previousPages.last().endAnchor.chapterOffset)
        assertFalse(previousPages.any { page -> page.content.toString().contains("第695章") })
        assertTrue(nextPages.first().content.toString().startsWith("第695章 文始真人"))
    }

    @Test
    fun pageAnchorsUseTheExactSourceOffsetMapper() {
        val text = "第1章 标题\n" + "甲乙丙丁戊己庚辛壬癸。".repeat(900)
        val baseByte = 8_000_000L
        val offsets = LongArray(text.length + 1) { index -> baseByte + index * 3L }

        val pages = ReaderTextPaginator.paginate(
            chapterIndex = 0,
            text = text,
            signature = signature,
            typeface = Typeface.DEFAULT,
            lineSpacingMultiplier = 1.75f,
            sourceOffsetForCharacter = { index -> offsets[index.coerceIn(0, offsets.lastIndex)] }
        )

        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertEquals(offsets[page.startAnchor.chapterOffset], page.startAnchor.sourceOffset)
            assertEquals(offsets[page.endAnchor.chapterOffset], page.endAnchor.sourceOffset)
            assertTrue(page.endAnchor.sourceOffset >= page.startAnchor.sourceOffset)
        }
        assertEquals(baseByte, pages.first().startAnchor.sourceOffset)
        assertEquals(offsets.last(), pages.last().endAnchor.sourceOffset)
    }

    @Test
    fun reducingUsableHeightCannotReducePageCount() {
        val text = "第1章 标题\n" + "用于比较页面高度的正文。".repeat(1600)
        val normal = paginate(chapterIndex = 0, text = text)
        val shorter = ReaderTextPaginator.paginate(
            chapterIndex = 0,
            text = text,
            signature = signature.copy(bottomPaddingPx = signature.bottomPaddingPx + 500),
            typeface = Typeface.DEFAULT,
            lineSpacingMultiplier = 1.75f
        )

        assertTrue(shorter.size >= normal.size)
    }

    private fun paginate(chapterIndex: Int, text: CharSequence): List<ReaderPageSnapshot> =
        ReaderTextPaginator.paginate(
            chapterIndex = chapterIndex,
            text = text,
            signature = signature,
            typeface = Typeface.DEFAULT,
            lineSpacingMultiplier = 1.75f
        )
}
