package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V599RequirementsContractTest {
    private fun source(path: String) = File(path).readText()

    @Test
    fun `fixed requirements cannot regress`() {
        val cover = source("src/main/java/com/simplereader/app/ui/PaperCoverDrawable.kt")
        val layout = source("src/main/res/layout/activity_reader.xml")
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val vertical = source("src/main/java/com/simplereader/app/ui/VerticalPageFlowView.kt")
        val main = source("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        val worker = source("src/main/java/com/simplereader/app/reader/cache/ReaderPageCacheWorker.kt")

        assertFalse(cover.contains("drawText(\"TXT\""))
        assertFalse(layout.contains("android:background=\"#33FFFFFF\""))
        assertTrue(reader.contains("readerProgressLabel.setTextColor(currentTextColor)"))

        assertTrue(vertical.contains("PagerSnapHelper"))
        assertTrue(vertical.contains("smoothScrollToPosition(target)"))
        assertFalse(vertical.contains("isScrollbarFadingEnabled = false"))
        assertFalse(reader.contains("readerScrollView.isScrollbarFadingEnabled = false"))

        assertTrue(reader.contains("actualPageLabelForDocumentPosition(safePosition)"))
        assertTrue(reader.contains("actualPageLabelForAnchor"))
        assertFalse(reader.contains("safePosition / pageSize.coerceAtLeast(1)"))

        assertTrue(reader.contains("象牙色（日间）"))
        assertTrue(reader.contains("护眼背景（日间）"))
        assertTrue(reader.contains("白色背景（日间）"))

        assertTrue(main.contains("一键缓存"))
        assertTrue(main.contains("ReaderPageCacheManager.enqueue"))
        assertFalse(worker.contains("setForeground("))
        assertFalse(worker.contains("ForegroundInfo"))
        assertTrue(worker.contains("CHECKPOINT_CHAPTERS"))
        assertTrue(worker.contains("completeChapterCount"))
    }
}
