package com.simplereader.app.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class EpubParserTest {
    @Test
    fun `uses OPF spine order instead of zip entry order`() {
        val bytes = sampleEpub()
        val chapters = EpubParser.readChapterIndex(ByteArrayInputStream(bytes))
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.text })
        assertTrue(EpubParser.readChapterText(ByteArrayInputStream(bytes), chapters[0].name).contains("第一章正文"))
        assertTrue(EpubParser.readChapterText(ByteArrayInputStream(bytes), chapters[1].name).contains("第二章正文"))
    }

    @Test
    fun `reads all spine text for continuous navigation`() {
        val chapters = EpubParser.readChapters(ByteArrayInputStream(sampleEpub()))
        assertEquals(2, chapters.size)
        assertTrue(chapters[0].content.contains("第一章正文"))
        assertTrue(chapters[1].content.contains("第二章正文"))
    }

    @Test
    fun `returns OPF declared cover image`() {
        assertArrayEquals(COVER_BYTES, EpubParser.readCoverImage(ByteArrayInputStream(sampleEpub())))
    }

    private fun sampleEpub(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun entry(name: String, value: String) = entry(name, value.toByteArray(Charsets.UTF_8))

            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent())
            entry("OEBPS/ch2.xhtml", "<html><head><title>第二章</title></head><body><h1>第二章</h1><p>第二章正文</p></body></html>")
            entry("OEBPS/ch1.xhtml", "<html><head><title>第一章</title></head><body><h1>第一章</h1><p>第一章正文</p></body></html>")
            entry("OEBPS/cover.jpg", COVER_BYTES)
            entry("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">test</dc:identifier><dc:title>测试</dc:title><dc:language>zh</dc:language>
                    <meta name="cover" content="cover"/>
                  </metadata>
                  <manifest>
                    <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>""".trimIndent())
        }
        return output.toByteArray()
    }

    companion object {
        private val COVER_BYTES = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46
        )
    }
}
