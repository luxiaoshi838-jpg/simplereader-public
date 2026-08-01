package com.simplereader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPageCacheBehaviorTest {
    private val signature = ReaderLayoutSignature(
        widthPx = 1080,
        heightPx = 2400,
        textSizePx = 48,
        lineSpacingMultiplierX100 = 175,
        horizontalPaddingPx = 84,
        topPaddingPx = 150,
        bottomPaddingPx = 180
    )

    @Test
    fun wholeBookIndexDoesNotEvictTheVisibleThreeChapterWindow() {
        val cache = ReaderPageCache(maxChapters = 3)
        listOf(695, 694, 696).forEach { chapter ->
            cache.put(chapter, signature, pages(chapter))
            assertNotNull(cache.get(chapter, signature)) // exact-start registration read
        }

        for (chapter in 0..767) {
            if (chapter in 694..696) continue
            cache.put(chapter, signature, pages(chapter))
            val registrationPages = cache.get(chapter, signature)
            assertEquals("chapter-$chapter", registrationPages?.first()?.content)
        }

        assertEquals("chapter-695", cache.get(695, signature)?.first()?.content)
        assertEquals("chapter-696", cache.get(696, signature)?.first()?.content)
    }

    @Test
    fun distantIndexSlotKeepsOnlyTheLatestRegistrationPage() {
        val cache = ReaderPageCache(maxChapters = 3)
        cache.put(500, signature, pages(500))
        assertNotNull(cache.get(500, signature))

        cache.put(1, signature, pages(1))
        assertNotNull(cache.get(1, signature))
        cache.put(2, signature, pages(2))
        assertNotNull(cache.get(2, signature))

        assertNull(cache.get(1, signature))
        assertEquals("chapter-2", cache.get(2, signature)?.first()?.content)
    }

    @Test
    fun chapterReadAgainByNavigationIsPromotedBeforeBackgroundIndexContinues() {
        val cache = ReaderPageCache(maxChapters = 3)
        listOf(10, 9, 11).forEach { chapter ->
            cache.put(chapter, signature, pages(chapter))
            assertNotNull(cache.get(chapter, signature))
        }

        assertEquals("chapter-11", cache.get(11, signature)?.first()?.content)
        cache.put(12, signature, pages(12))
        assertNotNull(cache.get(12, signature))
        assertEquals("chapter-12", cache.get(12, signature)?.first()?.content)

        for (chapter in 100..140) {
            cache.put(chapter, signature, pages(chapter))
            assertNotNull(cache.get(chapter, signature))
        }
        assertEquals("chapter-12", cache.get(12, signature)?.first()?.content)
    }

    private fun pages(chapter: Int): List<ReaderPageSnapshot> = listOf(
        ReaderPageSnapshot(
            startAnchor = ReaderPageAnchor(chapter, 0, chapter * 10_000L),
            endAnchor = ReaderPageAnchor(chapter, 100, chapter * 10_000L + 300L),
            content = "chapter-$chapter",
            pageIndexInChapter = 0,
            pageCountInChapter = 1
        )
    )
}
