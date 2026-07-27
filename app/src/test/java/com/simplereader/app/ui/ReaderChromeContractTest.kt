package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChromeContractTest {
    private fun source(): String =
        File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    private fun layout(): String =
        File("src/main/res/layout/activity_reader.xml").readText()

    @Test
    fun topAndBottomBarsAreHiddenTogetherAndOnlyCenterTapTogglesThem() {
        val text = source()
        assertTrue(text.contains("supportActionBar?.hide()"))
        assertTrue(text.contains("private fun setReaderChromeVisible(visible: Boolean)"))
        assertTrue(text.contains("val centerLeft = width / 4f"))
        assertTrue(text.contains("val centerRight = width * 3f / 4f"))
        assertTrue(text.contains("val centerTop = height / 4f"))
        assertTrue(text.contains("val centerBottom = height * 3f / 4f"))
        assertTrue(text.contains("e.x in centerLeft..centerRight && e.y in centerTop..centerBottom"))
        assertFalse(text.contains("toggleReaderControls()"))
    }

    @Test
    fun bottomBarUsesSunMoonSymbolsAndHasNoMoreButton() {
        val text = source()
        val xml = layout()
        assertTrue(text.contains("if (night) \"☀\" else \"☾\""))
        assertTrue(xml.contains("android:text=\"☾\""))
        assertFalse(xml.contains("@+id/moreReaderButton"))
        assertFalse(text.contains("findViewById<TextView>(R.id.moreReaderButton)"))
    }
}
