package com.simplereader.app.ui

import com.simplereader.app.parser.TxtWindowResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtContinuousBufferTest {
    @Test
    fun `adjacent windows become one uninterrupted text`() {
        val buffer = TxtContinuousBuffer(maxChars = 1000)
        buffer.reset(TxtWindowResult("第一段\n", 0, 9))
        assertTrue(buffer.append(TxtWindowResult("第二段\n", 9, 18)).accepted)
        assertTrue(buffer.prepend(TxtWindowResult("序言\n", 0, 0)).accepted.not())
        assertEquals("第一段\n第二段\n", buffer.content)
        assertEquals(0L, buffer.startByte)
        assertEquals(18L, buffer.endByte)
    }

    @Test
    fun `non adjacent window is rejected instead of hiding a gap`() {
        val buffer = TxtContinuousBuffer(maxChars = 1000)
        buffer.reset(TxtWindowResult("a", 10, 20))
        assertFalse(buffer.append(TxtWindowResult("b", 21, 30)).accepted)
        assertFalse(buffer.prepend(TxtWindowResult("c", 0, 9)).accepted)
        assertEquals("a", buffer.content)
    }

    @Test
    fun `rolling buffer stays bounded and keeps byte mapping`() {
        val buffer = TxtContinuousBuffer(maxChars = 12, minimumChunksToKeep = 2)
        buffer.reset(TxtWindowResult("aaaa", 0, 4))
        buffer.append(TxtWindowResult("bbbb", 4, 8))
        val mutation = buffer.append(TxtWindowResult("cccccccc", 8, 16))
        assertTrue(mutation.removedPrefixChars > 0)
        assertEquals(4L, buffer.startByte)
        assertEquals(16L, buffer.endByte)
        assertEquals(10L, buffer.byteForFraction(0.5f))
    }
}
