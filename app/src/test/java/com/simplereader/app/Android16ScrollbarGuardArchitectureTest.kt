package com.simplereader.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Android16ScrollbarGuardArchitectureTest {
    @Test
    fun applicationDisablesRecyclerViewSystemScrollbarsBeforeRendering() {
        val source = File("src/main/java/com/simplereader/app/SimpleReaderApplication.kt").readText()
        assertTrue(source.contains("registerActivityLifecycleCallbacks"))
        assertTrue(source.contains("view.isVerticalScrollBarEnabled = false"))
        assertTrue(source.contains("view.isHorizontalScrollBarEnabled = false"))
        assertTrue(source.contains("view.isScrollbarFadingEnabled = false"))
        assertFalse(source.contains("view.isVerticalScrollBarEnabled = true"))
    }
}
