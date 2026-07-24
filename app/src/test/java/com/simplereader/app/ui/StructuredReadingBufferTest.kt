package com.simplereader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReadingBufferTest {
    @Test
    fun `keeps ordered chapter content in one continuous string`() {
        val buffer = StructuredReadingBuffer.build(
            centerChapterIndex = 4,
            chapters = listOf(2 to "前两章内容", 3 to "上一章内容", 4 to "当前章内容", 5 to "下一章内容", 6 to "后两章内容")
        )
        assertTrue(buffer.content.indexOf("前两章内容") < buffer.content.indexOf("上一章内容"))
        assertTrue(buffer.content.indexOf("上一章内容") < buffer.content.indexOf("当前章内容"))
        assertTrue(buffer.content.indexOf("当前章内容") < buffer.content.indexOf("下一章内容"))
        assertTrue(buffer.content.indexOf("下一章内容") < buffer.content.indexOf("后两章内容"))
        assertEquals(2, buffer.firstChapterIndex)
        assertEquals(6, buffer.lastChapterIndex)
    }

    @Test
    fun `maps positions back to chapter local offsets`() {
        val buffer = StructuredReadingBuffer.build(1, listOf(0 to "abc", 1 to "12345", 2 to "xyz"))
        val position = requireNotNull(buffer.positionFor(1, 3))
        val location = buffer.locationFor(position)
        assertEquals(1, location.chapterIndex)
        assertEquals(3, location.offset)
    }

    @Test
    fun `position mapping remains stable when the buffer recenters`() {
        val first = StructuredReadingBuffer.build(4, listOf(2 to "aa", 3 to "bbb", 4 to "cccc", 5 to "ddddd", 6 to "eeeeee"))
        val location = first.locationFor(requireNotNull(first.positionFor(5, 4)))
        val shifted = StructuredReadingBuffer.build(5, listOf(3 to "bbb", 4 to "cccc", 5 to "ddddd", 6 to "eeeeee", 7 to "fffffff"))
        val shiftedPosition = requireNotNull(shifted.positionFor(location.chapterIndex, location.offset))
        assertEquals(location, shifted.locationFor(shiftedPosition))
    }
}
