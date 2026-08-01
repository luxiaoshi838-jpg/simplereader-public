package com.simplereader.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderViewportMetricsTest {
    @After
    fun resetViewport() {
        ReaderViewportMetrics.resetForTests()
    }

    @Test
    fun displayDimensionsAreOnlyUsedBeforeTheReaderRootIsMeasured() {
        ReaderViewportMetrics.resetForTests()
        val signature = signature(width = 1080, height = 2400)

        assertEquals(1080, signature.viewportWidthPx)
        assertEquals(2400, signature.viewportHeightPx)
    }

    @Test
    fun measuredReaderRootOverridesThePhysicalDisplayForPagination() {
        ReaderViewportMetrics.resetForTests()
        val fallback = signature(width = 1080, height = 2400)
        ReaderViewportMetrics.record(widthPx = 900, heightPx = 1760)
        val measured = signature(width = 1080, height = 2400)

        assertEquals(900, measured.viewportWidthPx)
        assertEquals(1760, measured.viewportHeightPx)
        assertNotEquals(fallback, measured)
        assertTrue(measured.stableKey().startsWith("900:1760:"))
    }

    @Test
    fun copiedSignatureKeepsTheCapturedViewportForTheSameLayoutPass() {
        ReaderViewportMetrics.record(widthPx = 930, heightPx = 1810)
        val original = signature(width = 1080, height = 2400)
        val copied = original.copy(bottomPaddingPx = original.bottomPaddingPx + 40)

        assertEquals(930, copied.viewportWidthPx)
        assertEquals(1810, copied.viewportHeightPx)
        assertNotEquals(original.stableKey(), copied.stableKey())
    }

    @Test
    fun activityLayoutRootRecordsItsActualLaidOutSize() {
        val layout = File("src/main/res/layout/activity_reader.xml").readText()
        val view = File("src/main/java/com/simplereader/app/ui/ReaderViewportFrameLayout.kt").readText()

        assertTrue(layout.contains("<com.simplereader.app.ui.ReaderViewportFrameLayout"))
        assertTrue(layout.contains("</com.simplereader.app.ui.ReaderViewportFrameLayout>"))
        assertTrue(view.contains("override fun onSizeChanged"))
        assertTrue(view.contains("ReaderViewportMetrics.record(w, h)"))
    }

    private fun signature(width: Int, height: Int) = ReaderLayoutSignature(
        widthPx = width,
        heightPx = height,
        textSizePx = 48,
        lineSpacingMultiplierX100 = 175,
        horizontalPaddingPx = 84,
        topPaddingPx = 150,
        bottomPaddingPx = 180
    )
}
