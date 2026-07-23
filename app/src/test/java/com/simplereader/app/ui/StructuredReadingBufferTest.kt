package com.simplereader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReadingBufferTest {
    @Test
    fun `keeps previous current and next chapters in one continuous string`() {
        val buffer = StructuredReadingBuffer.build(
            centerChapterIndex = 4,
            chapters = listOf(3 to "上一章内容", 4 to "当前章内容", 5 to "下一章内容")
        )
        assertTrue(buffer.content.indexOf("上一章内容") < buffer.content.indexOf("当前章内容"))
        assertTrue(buffer.content.indexOf("当前章内容") < buffer.content.indexOf("下一章内容"))
        assertEquals(3, buffer.firstChapterIndex)
        assertEquals(5, buffer.lastChapterIndex)
    }

    @Test
    fun `maps positions back to chapter local offsets`() {
        val buffer = StructuredReadingBuffer.build(1, listOf(0 to "abc", 1 to "12345", 2 to "xyz"))
        val position = requireNotNull(buffer.positionFor(1, 3))
        val location = buffer.locationFor(position)
        assertEquals(1, location.chapterIndex)
        assertEquals(3, location.offset)
    }
}
