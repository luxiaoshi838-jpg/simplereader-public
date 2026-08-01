package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtChapterSequenceStabilityTest {
    @Test
    fun `dense toc duplicate is not treated as the book body chapter axis`() {
        val hits = listOf(
            TxtChapterHit("第1章 开始", 20),
            TxtChapterHit("第2章 相逢", 45),
            TxtChapterHit("第3章 远行", 70),
            TxtChapterHit("第4章 归来", 95),
            TxtChapterHit("第1章 开始", 10_000),
            TxtChapterHit("第一卷", 12_000),
            TxtChapterHit("第2章 相逢", 24_000),
            TxtChapterHit("第3章 远行", 39_000),
            TxtChapterHit("第4章 归来", 58_000)
        )

        val stable = TxtParser.stabilizeChapterHits(hits)

        assertEquals(listOf(0L, 10_000L, 24_000L, 39_000L, 58_000L), stable.map { it.byteOffset })
        assertTrue(stable.none { it.title == "第一卷" })
    }

    @Test
    fun `fallback headings remain when no explicit numbered chapter run exists`() {
        val hits = listOf(
            TxtChapterHit("1 开始", 0),
            TxtChapterHit("2 相逢", 1_000),
            TxtChapterHit("3 归来", 2_000)
        )
        assertEquals(hits, TxtParser.stabilizeChapterHits(hits))
    }
}
