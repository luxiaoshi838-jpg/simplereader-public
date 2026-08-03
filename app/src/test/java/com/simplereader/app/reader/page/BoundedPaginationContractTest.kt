package com.simplereader.app.reader.page

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPaginationContractTest {
    @Test
    fun longChaptersUseBoundedLayoutWindowsWithoutArtificialFinalPages() {
        val engine = File("src/main/java/com/simplereader/app/reader/page/PageEngine.kt").readText()
        assertTrue(engine.contains("MAX_LAYOUT_WINDOW_CHARS"))
        assertTrue(engine.contains("localPages.dropLast(1)"))
        assertTrue(engine.contains("cursor + localPages.last().first"))
        assertTrue(engine.contains("Layout.BREAK_STRATEGY_SIMPLE"))
        assertTrue(engine.contains("Layout.HYPHENATION_FREQUENCY_NONE"))
    }
}
