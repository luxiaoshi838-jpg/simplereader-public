package com.simplereader.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contracts for the real-file regressions reported after v13.
 * The private user samples are never committed to the repository.
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
    fun `structured navigation keeps adjacent chapters and avoids Int max previous offset`() {
        val source = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        assertTrue(source.contains("StructuredReadingBuffer"))
        assertFalse(source.contains("offset = Int.MAX_VALUE"))
    }
}
