package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCacheAndRestoreContractTest {
    @Test
    fun txtAndPageCachesAreUsedAndIncludedInSync() {
        val loader = File("src/main/java/com/simplereader/app/reader/ReaderDocumentLoader.kt").readText()
        val cache = File("src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt").readText()
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val decoder = File("src/main/java/com/simplereader/app/data/backup/SimpleReaderBackupDecoder.kt").readText()
        val restorer = File("src/main/java/com/simplereader/app/data/backup/SimpleReaderBackupRestorer.kt").readText()

        assertTrue(loader.contains("PageCacheStore.loadNormalizedTxt"))
        assertTrue(loader.contains("PageCacheStore.saveNormalizedTxt"))
        assertTrue(cache.contains("fun exportAll(context: Context): JSONArray"))
        assertTrue(cache.contains("fun restoreAll("))
        assertTrue(main.contains("root.put(\"pageCache\", pageCache)"))
        assertTrue(decoder.contains("pageCache = root.objectRows(\"pageCache\")"))
        assertTrue(restorer.contains("PageCacheStore.restoreAll"))
    }

    @Test
    fun stableTextOffsetWinsOverDeviceSpecificPageNumber() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val stableOffset = reader.indexOf("val stableOffset = preserveOffset")
        val globalPageFallback = reader.indexOf("progress?.globalPageIndex != null", stableOffset)
        assertTrue(stableOffset >= 0)
        assertTrue(globalPageFallback > stableOffset)
        assertTrue(reader.contains("stableOffset != null -> paged.pageForOffset(stableOffset).globalPageIndex"))
    }

    @Test
    fun progressIndicatorHasNoTransparentBox() {
        val layout = File("src/main/res/layout/activity_reader.xml").readText()
        val block = layout.substringAfter("@+id/readerProgressLabel").substringBefore("/>")
        assertFalse(block.contains("android:background="))
        assertFalse(block.contains("android:minWidth="))
        assertFalse(layout.contains("#33FFFFFF"))
    }
}
