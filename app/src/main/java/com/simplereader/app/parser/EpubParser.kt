package com.simplereader.app.parser

import android.text.Html
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.epub.EpubReader
import java.io.InputStream
import java.util.ArrayDeque

/**
 * EPUB 2/3 parser backed by documentnode/epub4j.
 * Reading order comes from the OPF spine rather than ZIP entry order.
 */
data class EpubChapter(
    val name: String,
    val text: String,
    val content: String = ""
)

data class EpubImage(
    val href: String,
    val data: ByteArray
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
                content = htmlToText(html, href)
            )
        }.distinctBy { it.name.lowercase() }
    }

    fun readImages(inputStream: InputStream): List<EpubImage> {
        val book = EpubReader().readEpub(inputStream)
        return book.resources.all
            .filter { resource -> isImageResource(resource.href) && resource.size in 1..MAX_IMAGE_BYTES }
            .mapNotNull { resource ->
                runCatching {
                    EpubImage(
                        href = normalizedHref(resource.href),
                        data = resource.data
                    )
                }.getOrNull()
            }
            .filter { it.data.isNotEmpty() && it.data.size <= MAX_IMAGE_BYTES }
            .distinctBy { it.href.lowercase() }
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

    private fun isImageResource(href: String): Boolean {
        val normalized = normalizedHref(href).lowercase()
        return normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.endsWith(".png") ||
            normalized.endsWith(".gif") ||
            normalized.endsWith(".webp")
    }

    private fun htmlToText(html: String, chapterHref: String? = null): String {
        val chapterBase = chapterHref?.substringBeforeLast('/', missingDelimiterValue = "").orEmpty()
        val body = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), "")
            .replace(Regex("(?is)<img\\b[^>]*(?:src|data-src)\\s*=\\s*(['\"])(.*?)\\1[^>]*>")) { match ->
                val source = match.groupValues.getOrNull(2).orEmpty()
                val href = resolveRelativeHref(chapterBase, source)
                if (href.isBlank()) "" else "\n$IMAGE_MARKER_PREFIX$href$IMAGE_MARKER_SUFFIX\n"
            }
        return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun resolveRelativeHref(base: String, value: String): String {
        val raw = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .substringBefore('#')
            .substringBefore('?')
            .replace('\\', '/')
            .trim()
        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return ""
        if (raw.startsWith("/")) return normalizedHref(raw)
        val path = if (base.isBlank()) raw else "$base/$raw"
        val output = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (!output.isEmpty()) output.removeLast()
                else -> output.addLast(part)
            }
        }
        return output.joinToString("/")
    }

    private const val MAX_COVER_BYTES = 24 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    const val IMAGE_MARKER_PREFIX = "[[SR_IMAGE:"
    const val IMAGE_MARKER_SUFFIX = "]]"
}
