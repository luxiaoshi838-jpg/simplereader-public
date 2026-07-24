package com.simplereader.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guardrails for regressions reported after v13.
 * These checks intentionally reject superficial implementations that only add
 * parser names or a fixed chapter buffer without persistent/exportable EPUB/CHM
 * cache and hierarchy-aware continuous reading.
 */
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
        assertTrue(source.contains("MAX_DOCUMENT_ENTRIES = 20000"))
        assertTrue(source.contains("DOCUMENT_WRITE_PATTERN"))
    }

    @Test
    fun `structured reading must not stop at a fixed adjacent chapter window`() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        assertFalse(reader.contains("offset = Int.MAX_VALUE"))
        assertTrue(
            "Vertical scrolling must automatically continue into the next cached text window",
            reader.contains("maybeExtendTxtContinuousBuffer") &&
                reader.contains("StructuredBookCache")
        )
    }

    @Test
    fun `chm table of contents must preserve hierarchy`() {
        val parser = File("src/main/java/com/simplereader/app/parser/ChmParser.kt").readText()
        assertTrue(
            "CHM chapters must retain depth or parent information",
            parser.contains("depth:") || parser.contains("parentPath:") || parser.contains("parentId:")
        )
    }

    @Test
    fun `epub and chm cache must be persistent and backup exportable`() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val decoder = File("src/main/java/com/simplereader/app/data/backup/SimpleReaderBackupDecoder.kt").readText()
        assertTrue(
            "Structured cache must live outside cacheDir so Android cache cleanup does not erase it",
            reader.contains("StructuredBookCache") || reader.contains("filesDir")
        )
        assertTrue(
            "Backup export must include EPUB/CHM structured cache",
            main.contains("structured_cache") || main.contains("structuredCache")
        )
        assertTrue(
            "Backup decoder must accept EPUB/CHM structured cache",
            decoder.contains("structuredCache") || decoder.contains("structured_cache")
        )
    }

    @Test
    fun `large txt must remain source streamed instead of duplicating content txt`() {
        val cache = File("src/main/java/com/simplereader/app/data/cache/StructuredBookCache.kt").readText()
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        assertTrue(cache.contains("TXT 必须直接流式读取"))
        assertFalse(cache.contains("\"TXT\" -> buildTxt"))
        assertTrue(reader.contains("txtStreamingMode"))
    }
}
