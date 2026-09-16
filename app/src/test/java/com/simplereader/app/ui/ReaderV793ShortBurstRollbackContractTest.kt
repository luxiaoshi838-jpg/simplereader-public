package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV793ShortBurstRollbackContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `short burst accumulates from one origin and requires over twenty pages`() {
        assertTrue(reader.contains("VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_WINDOW_MS = 1_000L"))
        assertTrue(reader.contains("now - verticalRollbackSamples.peekFirst().uptimeMs > VERTICAL_ROLLBACK_WINDOW_MS"))
        assertTrue(reader.contains("kotlin.math.abs(location.pageIndex - sample.location.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("verticalRollbackSamples"))
        assertTrue(reader.contains("clearVerticalRollbackSamples()"))
        assertTrue(reader.contains("showVerticalRollback(origin.location, reason)"))
    }

    @Test fun `chapter search catalog jumps share the same burst origin`() {
        assertTrue(reader.contains("recordVerticalRollbackSample(rollbackOrigin, \"explicit_jump_origin\", allowOffer = false)"))
        assertTrue(reader.contains("recordVerticalRollbackSample("))
        assertTrue(reader.contains("\"explicit_jump_window\""))
        assertTrue(reader.contains("private fun jumpChapter(direction: Int)"))
        assertTrue(reader.contains("jumpToPage(targetPage, false)"))
    }

    @Test fun `v791 vertical fling insurance remains unchanged`() {
        val start = reader.indexOf("internal fun verticalHandleTouch")
        val end = reader.indexOf("Vertical mode renders", start)
        val touchBlock = reader.substring(start, end)
        assertTrue(touchBlock.contains("rv?.stopScroll()"))
        assertTrue(touchBlock.contains("cancelVerticalSettlingGuard()"))
        assertTrue(reader.contains("RecyclerView.SCROLL_STATE_SETTLING -> scheduleVerticalSettlingGuard()"))
        assertTrue(reader.contains("VERTICAL_SETTLING_GUARD_MS = 5_000L"))
        assertTrue(reader.contains("if ((event?.repeatCount ?: 0) > 0) return true"))
    }

    @Test fun `rollback ui remains three seconds above reader controls`() {
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
    }
}
