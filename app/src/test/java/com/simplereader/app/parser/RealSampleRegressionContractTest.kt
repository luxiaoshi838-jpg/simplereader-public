package com.simplereader.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guardrails for the unified v608 reader. */
class RealSampleRegressionContractTest {
    @Test
    fun `epub parser exposes cover extraction`() {
        val source = File("src/main/java/com/simplereader/app/parser/EpubParser.kt").readText()
        assertTrue(source.contains("readCoverImage"))
        assertTrue(source.contains("coverImage"))
    }

    @Test
    fun `chm parser decodes raw bytes instead of trusting retrieveObjectAsString`() {
        val source = File("src/main/java/com/simplereader/app/parser/ChmParser.kt").readText()
        assertTrue(source.contains("GB18030"))
        assertTrue(source.contains("charset"))
        assertFalse(source.contains("retrieveObjectAsString(entry)"))
    }

    @Test
    fun `structured reading uses one global real page sequence`() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val engine = File("src/main/java/com/simplereader/app/reader/page/PageEngine.kt").readText()
        assertTrue(reader.contains("ReaderPageAdapter"))
        assertTrue(reader.contains("TURN_MODE_VERTICAL"))
        assertTrue(engine.contains("data class ReaderPage("))
        assertTrue(engine.contains("StaticLayout.Builder"))
        assertFalse(reader.contains("currentPosition + pageSize"))
        assertFalse(reader.contains("LOGICAL_TXT_BYTES_PER_PAGE"))
    }

    @Test
    fun `epub cache remains persistent and backup exportable`() {
        val loader = File("src/main/java/com/simplereader/app/reader/ReaderDocumentLoader.kt").readText()
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val decoder = File("src/main/java/com/simplereader/app/data/backup/SimpleReaderBackupDecoder.kt").readText()
        assertTrue(loader.contains("StructuredBookCache"))
        assertTrue(main.contains("structured_cache") || main.contains("structuredCache"))
        assertTrue(decoder.contains("structuredCache") || decoder.contains("structured_cache"))
    }

    @Test
    fun `txt page count is layout based and cached by source identity`() {
        val engine = File("src/main/java/com/simplereader/app/reader/page/PageEngine.kt").readText()
        val cache = File("src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt").readText()
        assertTrue(engine.contains("viewportWidthPx"))
        assertTrue(engine.contains("viewportHeightPx"))
        assertTrue(engine.contains("lineSpacingMultiplier"))
        assertTrue(cache.contains("fileSize"))
        assertTrue(cache.contains("lastModified"))
        assertTrue(cache.contains("readerSettingsHash"))
        assertFalse(engine.contains("820"))
    }
}
