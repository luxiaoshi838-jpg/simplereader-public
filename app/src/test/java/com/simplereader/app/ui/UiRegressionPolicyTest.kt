package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiRegressionPolicyTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `confirmed layered background is restored`() {
        val background = source("src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt")
        val picker = source("src/main/java/com/simplereader/app/ui/ReaderBackgroundPicker.kt")
        assertTrue(background.contains("data class Selection"))
        assertTrue(background.contains("colorId"))
        assertTrue(background.contains("textureId"))
        assertTrue(background.contains("materialId"))
        assertTrue(picker.contains("纯色"))
        assertTrue(picker.contains("纹理"))
        assertTrue(picker.contains("质感"))
    }

    @Test
    fun `horizontal modes use distinct confirmed renderer`() {
        val renderer = source("src/main/java/com/simplereader/app/ui/PagedReaderView.kt")
        assertTrue(renderer.contains("TurnMode.OVERLAP"))
        assertTrue(renderer.contains("TurnMode.SIMULATE"))
        assertTrue(renderer.contains("TurnMode.SLIDE"))
        assertTrue(renderer.contains("rotationY"))
        assertTrue(renderer.contains("outgoing.translationX"))
    }

    @Test
    fun `vertical mode is one continuous document and pages reserve no bottom gap`() {
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val layout = source("src/main/res/layout/activity_reader.xml")
        assertTrue(reader.contains("showContinuousBook"))
        assertTrue(reader.contains("contentPaddingBottomPx = 0"))
        assertFalse(reader.contains("PagerSnapHelper"))
        assertFalse(reader.contains("RecyclerView"))
        assertTrue(layout.contains("android:paddingBottom=\"0dp\""))
        assertFalse(layout.contains("paddingBottom=\"118dp\""))
    }
}
