from pathlib import Path

path = Path('app/src/test/java/com/simplereader/app/ui/ReaderV793BurstRaceContractTest.kt')
path.write_text(r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV793BurstRaceContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `touch still brakes old fling and seeds the real rolling window`() {
        val start = reader.indexOf("MotionEvent.ACTION_DOWN -> {", reader.indexOf("internal fun verticalHandleTouch"))
        val end = reader.indexOf("MotionEvent.ACTION_UP", start)
        val block = reader.substring(start, end)
        val stop = block.indexOf("rv?.stopScroll()")
        assertTrue(stop >= 0)
        assertTrue(block.indexOf("cancelVerticalSettlingGuard()", stop) > stop)
        assertTrue(block.indexOf("recordVerticalRollbackSample(captureVerticalLocation(), \"touch_origin\", allowOffer = false)", stop) > stop)
        assertFalse(block.contains("cancelVerticalRollbackBurstReset()"))
    }

    @Test fun `explicit jump shares the rolling window without restoring idle gap reset`() {
        val start = reader.indexOf("private fun jumpToPage")
        val end = reader.indexOf("private fun jumpChapter", start)
        val block = reader.substring(start, end)
        val stop = block.indexOf("verticalRecyclerView?.stopScroll()")
        assertTrue(stop >= 0)
        assertTrue(block.contains("recordVerticalRollbackSample(rollbackOrigin, \"explicit_jump_origin\", allowOffer = false)"))
        assertTrue(block.contains("\"explicit_jump_window\""))
        assertTrue(block.contains("allowOffer = true"))
        assertFalse(block.contains("cancelVerticalRollbackBurstReset()"))
    }
}
''', encoding='utf-8')
print('v794 obsolete race contract updated')
