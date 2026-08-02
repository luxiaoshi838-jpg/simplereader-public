package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStartupContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `reader layout uses an existing root and never enables framework scrollbars`() {
        val layout = source("src/main/res/layout/activity_reader.xml")
        assertTrue(layout.contains("<FrameLayout"))
        assertFalse(layout.contains("ReaderViewportFrameLayout"))
        assertFalse(layout.contains("android:scrollbars=\"vertical\""))
        assertTrue(layout.contains("android:scrollbars=\"none\""))
    }

    @Test
    fun `reader code never initializes framework scrollbar drawables`() {
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val vertical = source("src/main/java/com/simplereader/app/ui/VerticalPageFlowView.kt")
        assertFalse(reader.contains("isScrollbarFadingEnabled = false"))
        assertFalse(reader.contains("isVerticalScrollBarEnabled = true"))
        assertFalse(vertical.contains("isScrollbarFadingEnabled = false"))
        assertFalse(vertical.contains("isVerticalScrollBarEnabled = true"))
    }
}
