package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV793BurstRaceContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `touch cancels any reset scheduled synchronously by stopScroll`() {
        val start = reader.indexOf("MotionEvent.ACTION_DOWN -> {", reader.indexOf("internal fun verticalHandleTouch"))
        val end = reader.indexOf("if (pageTurnMode == TURN_MODE_VERTICAL", start)
        val block = reader.substring(start, end)
        val stop = block.indexOf("rv?.stopScroll()")
        assertTrue(stop >= 0)
        assertTrue(block.indexOf("cancelVerticalRollbackBurstReset()", stop) > stop)
    }

    @Test fun `explicit jump also clears a reset scheduled by stopScroll`() {
        val start = reader.indexOf("private fun jumpToPage")
        val end = reader.indexOf("private fun jumpChapter", start)
        val block = reader.substring(start, end)
        val stop = block.indexOf("verticalRecyclerView?.stopScroll()")
        assertTrue(stop >= 0)
        assertTrue(block.indexOf("cancelVerticalRollbackBurstReset()", stop) > stop)
    }
}
