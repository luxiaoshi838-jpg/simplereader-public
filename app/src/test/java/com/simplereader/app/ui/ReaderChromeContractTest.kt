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
    fun topAndBottomBarsAreHiddenTogetherAndActivationModeIsConfigurable() {
        val text = source()
        val xml = layout()
        assertTrue(text.contains("supportActionBar?.hide()"))
        assertTrue(text.contains("private fun setReaderChromeVisible(visible: Boolean)"))
        assertTrue(text.contains("private const val CHROME_ACTIVATION_CENTER = \"center_tap\""))
        assertTrue(text.contains("private const val CHROME_ACTIVATION_LONG_PRESS = \"long_press\""))
        assertTrue(text.contains("readerChromeActivationMode == CHROME_ACTIVATION_CENTER"))
        assertTrue(text.contains("readerChromeActivationMode == CHROME_ACTIVATION_LONG_PRESS"))
        assertTrue(text.contains("private fun setReaderChromeActivationMode(mode: String)"))
        assertTrue(xml.contains("@+id/chromeCenterButton"))
        assertTrue(xml.contains("@+id/chromeLongPressButton"))
        assertFalse(text.contains("toggleReaderControls()"))
    }

    @Test
    fun bottomBarUsesSunMoonSymbolsAndHasNoLegacyMoreButton() {
        val text = source()
        val xml = layout()
        assertTrue(text.contains("if (night) \"☀\" else \"☾\""))
        assertTrue(xml.contains("android:text=\"☾\""))
        assertFalse(xml.contains("@+id/moreReaderButton"))
        assertFalse(text.contains("findViewById<TextView>(R.id.moreReaderButton)"))
    }

    @Test
    fun backgroundRowKeepsQuickChoicesAndOpensCategorizedPicker() {
        val text = source()
        val xml = layout()
        assertTrue(xml.contains("@+id/themePaperButton"))
        assertTrue(xml.contains("@+id/themeEyeButton"))
        assertTrue(xml.contains("@+id/themeWhiteButton"))
        assertTrue(xml.contains("@+id/themeNightButton"))
        assertTrue(xml.contains("@+id/themeMoreButton"))
        assertTrue(text.contains("ReaderBackgroundPicker.show("))
    }
}
