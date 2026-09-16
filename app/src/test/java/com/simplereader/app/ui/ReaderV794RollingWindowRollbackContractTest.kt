package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV794RollingWindowRollbackContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `rollback uses a real rolling one second window`() {
        assertTrue(reader.contains("VERTICAL_ROLLBACK_WINDOW_MS = 1_000L"))
        assertTrue(reader.contains("private data class TimedVerticalLocation"))
        assertTrue(reader.contains("verticalRollbackSamples"))
        assertTrue(reader.contains("now - verticalRollbackSamples.peekFirst().uptimeMs > VERTICAL_ROLLBACK_WINDOW_MS"))
        assertTrue(reader.contains("kotlin.math.abs(location.pageIndex - sample.location.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertFalse(reader.contains("VERTICAL_ROLLBACK_BURST_IDLE_MS"))
        assertFalse(reader.contains("scheduleVerticalRollbackBurstReset"))
    }

    @Test fun `ordinary long continuous scrolling cannot accumulate across older than one second`() {
        val start = reader.indexOf("private fun recordVerticalRollbackSample")
        val end = reader.indexOf("private fun clearVerticalRollbackSamples", start)
        val block = reader.substring(start, end)
        assertTrue(block.indexOf("while (verticalRollbackSamples.isNotEmpty()") < block.indexOf("if (allowOffer)"))
        assertTrue(block.contains("removeFirst()"))
    }

    @Test fun `scroll and explicit navigation feed the same rolling window`() {
        assertTrue(reader.contains("recordVerticalRollbackSample(it, \"rolling_scroll\", allowOffer = true)"))
        assertTrue(reader.contains("recordVerticalRollbackSample(rollbackOrigin, \"explicit_jump_origin\", allowOffer = false)"))
        assertTrue(reader.contains("\"explicit_jump_window\""))
        assertTrue(reader.contains("jumpToPage(targetPage, false)"))
    }

    @Test fun `v791 vertical fling insurance stays unchanged`() {
        val start = reader.indexOf("internal fun verticalHandleTouch")
        val end = reader.indexOf("Vertical mode renders", start)
        val touch = reader.substring(start, end)
        assertTrue(touch.contains("rv?.stopScroll()"))
        assertTrue(touch.contains("cancelVerticalSettlingGuard()"))
        assertTrue(reader.contains("VERTICAL_SETTLING_GUARD_MS = 5_000L"))
        assertTrue(reader.contains("if ((event?.repeatCount ?: 0) > 0) return true"))
    }

    @Test fun `rollback ui stays three seconds above controls`() {
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
    }
}
