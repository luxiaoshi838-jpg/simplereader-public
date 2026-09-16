package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV792RollbackThresholdContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `rollback is offered only after more than twenty pages`() {
        assertTrue(reader.contains("VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20"))
        assertTrue(reader.contains("kotlin.math.abs(location.pageIndex - sample.location.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("recordVerticalRollbackSample("))
    }

    @Test fun `rollback placement and lifetime stay unchanged`() {
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
    }
}
