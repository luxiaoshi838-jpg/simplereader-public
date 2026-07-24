package com.simplereader.app.parser

import android.text.Html
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.epub.EpubReader
import java.io.InputStream

/**
 * EPUB 2/3 parser backed by documentnode/epub4j.
 * Reading order comes from the OPF spine rather than ZIP entry order.
 */
data class EpubChapter(
    val name: String,
    val text: String,
    val content: String = ""
)

object EpubParser {
    fun readChapterIndex(inputStream: InputStream): List<EpubChapter> {
        val book = EpubReader().readEpub(inputStream)
        return spineResources(book)
            .map { resource ->
                val href = normalizedHref(resource.href)
                EpubChapter(name = href, text = chapterTitle(resource, href))
            }
            .distinctBy { it.name.lowercase() }
    }

    fun readChapterText(inputStream: InputStream, chapterName: String): String {
        val book = EpubReader().readEpub(inputStream)
        val target = normalizedHref(chapterName)
        val resource = spineResources(book).firstOrNull {
            normalizedHref(it.href).equals(target, ignoreCase = true)
        } ?: return ""
        return htmlToText(readResource(resource))
    }

    /**
     * Reads the whole spine once. This is used for EPUB continuous reading so a
     * directory jump does not discard the chapters before or after the target.
     */
    fun readChapters(inputStream: InputStream): List<EpubChapter> {
        val book = EpubReader().readEpub(inputStream)
        return spineResources(book).map { resource ->
            val href = normalizedHref(resource.href)
            val html = runCatching { readResource(resource) }.getOrDefault("")
            EpubChapter(
                name = href,
                text = chapterTitle(resource, href, html),
                content = htmlToText(html)
            )
        }.distinctBy { it.name.lowercase() }
    }

    /** Returns the real cover image declared by the EPUB package, if present. */
    fun readCoverImage(inputStream: InputStream): ByteArray? {
        val book = EpubReader().readEpub(inputStream)
        val cover = book.coverImage ?: return null
        if (cover.size <= 0L || cover.size > MAX_COVER_BYTES) return null
        return runCatching { cover.data }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
    }

    private fun spineResources(book: io.documentnode.epub4j.domain.Book): List<Resource> {
        return book.spine.spineReferences
            .mapNotNull { it.resource }
            .filter { isChapterResource(it.href) }
    }

    private fun readResource(resource: Resource): String = resource.reader.use { it.readText() }

    private fun chapterTitle(resource: Resource, href: String, knownHtml: String? = null): String {
        resource.title?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val html = knownHtml ?: runCatching { readResource(resource) }.getOrDefault("")
        Regex("(?is)<title[^>]*>(.*?)</title>")
            .find(html)?.groupValues?.getOrNull(1)?.let(::htmlToText)
            ?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>")
            .find(html)?.groupValues?.getOrNull(1)?.let(::htmlToText)
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return href.substringAfterLast('/').substringBeforeLast('.').ifBlank { href }
    }

    private fun normalizedHref(value: String): String =
        value.substringBefore('#').replace('\\', '/').trimStart('/')

    private fun isChapterResource(href: String): Boolean {
        val normalized = normalizedHref(href).lowercase()
        return normalized.endsWith(".xhtml") || normalized.endsWith(".html") || normalized.endsWith(".htm")
    }

    private fun htmlToText(html: String): String {
        val body = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), "")
        return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private const val MAX_COVER_BYTES = 24 * 1024 * 1024
}
