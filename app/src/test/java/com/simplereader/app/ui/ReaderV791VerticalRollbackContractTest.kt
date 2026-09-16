package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV791VerticalRollbackContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val adapter = File("src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt").readText()

    @Test fun `real touch brakes stale vertical fling before child dispatch`() {
        val start = reader.indexOf("internal fun verticalHandleTouch")
        val end = reader.indexOf("Vertical mode renders", start)
        assertTrue(start >= 0 && end > start)
        val block = reader.substring(start, end)
        assertTrue(block.contains("MotionEvent.ACTION_DOWN"))
        assertTrue(block.contains("rv?.stopScroll()"))
        assertTrue(block.contains("cancelVerticalSettlingGuard()"))
    }

    @Test fun `settling has a bounded fail safe and listener forwards state`() {
        assertTrue(adapter.contains("activity.verticalOnScrollStateChanged(newState)"))
        assertTrue(reader.contains("RecyclerView.SCROLL_STATE_SETTLING -> scheduleVerticalSettlingGuard()"))
        assertTrue(reader.contains("VERTICAL_SETTLING_GUARD_MS = 5_000L"))
        assertTrue(reader.contains("vertical_settling_forced_stop"))
    }

    @Test fun `large jump rollback follows mature previous location pattern`() {
        assertTrue(reader.contains("private data class VerticalLocation"))
        assertTrue(reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("Snackbar.make(readerRoot, \"位置已移动\", Snackbar.LENGTH_INDEFINITE)"))
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
        assertTrue(reader.contains("restoreVerticalLocation(target)"))
    }

    @Test fun `rollback uses source offset plus viewport offset and does not create a new rollback`() {
        assertTrue(reader.contains("val sourceOffset: Int"))
        assertTrue(reader.contains("val viewportOffsetPx: Int"))
        assertTrue(reader.contains("scrollToPositionWithOffset(index, location.viewportOffsetPx)"))
        val restoreStart = reader.indexOf("private fun restoreVerticalLocation")
        val restoreEnd = reader.indexOf("internal fun verticalOnUserDrag", restoreStart)
        val restore = reader.substring(restoreStart, restoreEnd)
        assertFalse(restore.contains("showVerticalRollback("))
    }

    @Test fun `volume key long press cannot stack vertical smooth scrolls`() {
        assertTrue(reader.contains("if ((event?.repeatCount ?: 0) > 0) return true"))
        assertTrue(reader.contains("rv?.stopScroll()"))
    }
}
