package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V747AutoReadContractTest {
    private fun root() = File(System.getProperty("user.dir") ?: ".")

    @Test
    fun sourceNativeAutomaticReadingIsRestored() {
        val r = File(root(), "src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val l = File(root(), "src/main/res/layout/activity_reader.xml").readText()
        listOf("showAutoReadDialog", "startAutoReading", "scheduleAutomaticPageTurn", "startAutomaticVerticalScroll", "updateAutoReadSpeed", "stopAutoReading").forEach { assertTrue(r.contains(it)) }
        assertTrue(r.contains("MIN_AUTO_READ_CPM = 200"))
        assertTrue(r.contains("MAX_AUTO_READ_CPM = 2000"))
        assertTrue(r.contains("AUTO_READ_STEP_CPM = 50"))
        assertTrue(r.contains("effectiveChars.toLong() * 60_000L"))
        assertTrue(r.contains("verticalAutoPixelRemainder"))
        assertTrue(r.contains("onWindowFocusChanged"))
        assertTrue(l.contains("@+id/autoReadButton"))
        assertTrue(l.contains("@+id/autoReadStopButton"))
        assertTrue(l.contains("@drawable/bg_auto_read_stop"))
    }
}
