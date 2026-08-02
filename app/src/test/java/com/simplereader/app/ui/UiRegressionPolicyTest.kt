package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiRegressionPolicyTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `forbidden cover implementations are absent`() {
        val cover = source("src/main/java/com/simplereader/app/ui/PaperCoverDrawable.kt")
        assertFalse(cover.contains("TXT"))
        assertFalse(cover.contains("txtPaint"))
        assertFalse(cover.contains("verticalCount"))
        assertFalse(cover.contains("lineCount"))
        assertTrue(cover.contains("cubicTo"))
    }

    @Test
    fun `reader background always contains colour texture and material layers`() {
        val surface = source("src/main/java/com/simplereader/app/ui/ReaderSurfaceDrawable.kt")
        assertTrue(surface.contains("basePaint"))
        assertTrue(surface.contains("materialPaint"))
        assertTrue(surface.contains("fibrePaint"))
        assertTrue(surface.contains("grainPaint"))
    }

    @Test
    fun `normal pages do not inherit old toolbar bottom gap`() {
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val layout = source("src/main/res/layout/activity_reader.xml")
        assertTrue(reader.contains("contentPaddingBottomPx = dp(26)"))
        assertFalse(layout.contains("paddingBottom=\"118dp\""))
    }

    @Test
    fun `page turn modes remain distinct`() {
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        assertTrue(reader.contains("TURN_MODE_SIMULATE ->"))
        assertTrue(reader.contains("rotationY"))
        assertTrue(reader.contains("TURN_MODE_HORIZONTAL ->"))
        assertTrue(reader.contains("smoothScrollToPosition"))
        assertTrue(reader.contains("TURN_MODE_FADE ->"))
        assertTrue(reader.contains("translationX"))
    }
}
