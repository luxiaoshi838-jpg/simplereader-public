package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFirstLoadContractTest {
    @Test
    fun pagedReaderIsMeasuredBeforeInitialPagination() {
        val layout = File("src/main/res/layout/activity_reader.xml").readText()
        val pagedReaderBlock = layout.substringAfter("@+id/pagedReaderView").substringBefore("/>")
        assertTrue(pagedReaderBlock.contains("android:visibility=\"invisible\""))
        assertFalse(pagedReaderBlock.contains("android:visibility=\"gone\""))
    }
}
