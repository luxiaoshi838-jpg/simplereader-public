package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatiblePageCacheOpenContractTest {
    @Test
    fun cachedBookCanOpenBeforeCurrentFontWholeBookPaginationFinishes() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val cache = File("src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt").readText()

        assertTrue(reader.contains("private suspend fun showCachedBookImmediately(): Int?"))
        assertTrue(reader.contains("PageCacheStore.loadCompatiblePages(this@ReaderActivity, identity, loaded.text)"))
        assertTrue(reader.contains("paginateAndDisplay(previewOffset, null, backgroundOpen = previewOffset != null)"))
        assertTrue(reader.contains("if (fontRequestId == null && !backgroundOpen) progressLabel.text = \"分页中…\""))
        assertTrue(reader.contains("open_cache_refresh:failed_keep_preview"))

        val start = cache.indexOf("fun loadCompatiblePages(")
        val end = cache.indexOf("fun savePages(", start)
        assertTrue(start >= 0 && end > start)
        val fallback = cache.substring(start, end)
        assertTrue(fallback.contains("storedFingerprint != identity.textFingerprint"))
        assertTrue(fallback.contains("catalogRuleVersion"))
        assertTrue(fallback.contains("ReaderBook(text, chapters, pages, storedSettingsHash)"))
        assertFalse(fallback.contains("savePages("))
        assertFalse(fallback.contains("readerSettingsHash\") != identity.settingsHash"))
    }

    @Test
    fun cachePreviewNeverBecomesAPartialReaderBook() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val start = reader.indexOf("private suspend fun showCachedBookImmediately(): Int?")
        val end = reader.indexOf("private fun paginateAndDisplay(", start)
        assertTrue(start >= 0 && end > start)
        val preview = reader.substring(start, end)

        assertTrue(preview.contains("readerBook = cached"))
        assertTrue(preview.contains("showActiveReader()"))
        assertFalse(preview.contains("ReaderBook("))
        assertTrue(reader.contains("val transientLayout = layoutSettings?.stableHash()?.let { it != paged.settingsHash } == true"))
        assertTrue(reader.contains("if (transientLayout) return stable ?: current"))
    }
}
