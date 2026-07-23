package com.simplereader.app.parser

import java.io.InputStream
import java.util.zip.ZipInputStream

data class EpubChapter(
    val name: String,
    val text: String
)

object EpubParser {
    fun readChapterIndex(inputStream: InputStream): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        ZipInputStream(inputStream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && isChapterEntry(name)) {
                    val title = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }
                    chapters += EpubChapter(name = name, text = title)
                }
                zip.closeEntry()
            }
        }
        return chapters
    }

    fun readChapterText(inputStream: InputStream, chapterName: String): String {
        ZipInputStream(inputStream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && name == chapterName) {
                    return htmlToText(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
        }
        return ""
    }

    fun readChapters(inputStream: InputStream): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()

        ZipInputStream(inputStream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name

                if (!entry.isDirectory && isChapterEntry(name)) {
                    val html = zip.readBytes().toString(Charsets.UTF_8)
                    val text = htmlToText(html)
                    if (text.isNotBlank()) {
                        chapters += EpubChapter(name = name, text = text)
                    }
                }
                zip.closeEntry()
            }
        }

        return chapters
    }

    private fun isChapterEntry(name: String): Boolean {
        return name.endsWith(".xhtml", ignoreCase = true) ||
            name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true)
    }

    private fun htmlToText(html: String): String {
        return html
            .replace(Regex("(?is)<script.*?</script>"), "")
            .replace(Regex("(?is)<style.*?</style>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
