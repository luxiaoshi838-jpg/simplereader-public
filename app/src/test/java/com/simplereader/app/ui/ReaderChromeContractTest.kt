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
    fun topAndBottomBarsAreHiddenTogetherAndCenterTapWorksInBothModes() {
        val text = source()
        assertTrue(text.contains("supportActionBar?.hide()"))
        assertTrue(text.contains("private fun setReaderChromeVisible(visible: Boolean)"))
        assertTrue(text.contains("e.x in readerScrollView.width * 0.25f..readerScrollView.width * 0.75f"))
        assertTrue(text.contains("e.y in readerScrollView.height * 0.2f..readerScrollView.height * 0.8f"))
        assertTrue(text.contains("pagedReaderView.onCenterTap = { setReaderChromeVisible(!chromeVisible) }"))
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
