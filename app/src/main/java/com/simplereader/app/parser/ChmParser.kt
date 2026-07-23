package com.simplereader.app.parser

import android.net.Uri
import android.text.Html
import org.jchmlib.ChmFile
import org.jchmlib.ChmTopicsTree
import org.jchmlib.ChmUnitInfo
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CHM reader backed by chimenchen/jchmlib v0.5.4.
 *
 * jchmlib is used for the archive structure and raw object retrieval. HTML is
 * decoded here from the original entry bytes because many Chinese CHM files use
 * GBK/GB2312 even when the archive-level encoding is incomplete or misleading.
 */
data class ChmChapter(val path: String, val title: String)

object ChmParser {
    private const val MAX_DOCUMENT_ENTRIES = 20000
    private const val MAX_TOTAL_TEXT_CHARS = 32 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024L

    /**
     * Creates one reusable CHM source in the app cache. A 200+ MB book must not
     * be copied again every time the user opens a chapter.
     */
    fun prepareCachedFile(inputStream: InputStream, cacheDirectory: File, cacheKey: String): File {
        val directory = cacheDirectory.resolve("chm_sources").apply { mkdirs() }
        val safeKey = cacheKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
        val target = directory.resolve("$safeKey.chm")
        if (target.isFile && target.length() > 0L) return target

        val temporary = directory.resolve("$safeKey.${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().buffered().use { output -> inputStream.copyTo(output) }
            check(temporary.length() > 0L) { "CHM 缓存文件为空" }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun readText(chmFile: File): String = withArchive(chmFile) { archive ->
        val combined = StringBuilder()
        chapterEntries(archive).forEach { chapter ->
            if (combined.length >= MAX_TOTAL_TEXT_CHARS) return@forEach
            val text = readEntryText(archive, chapter.path)
            if (text.isBlank()) return@forEach
            if (combined.isNotEmpty()) combined.append(CHAPTER_SEPARATOR)
            combined.append(text)
        }
        cleanText(combined.toString()).ifBlank { error("CHM 中没有读取到可显示内容") }
    }

    fun readChapterIndex(chmFile: File): List<ChmChapter> = withArchive(chmFile, ::chapterEntries)

    fun readChapterText(chmFile: File, chapterPath: String): String =
        withArchive(chmFile) { archive -> readEntryText(archive, chapterPath) }

    // Compatibility overloads retained for callers/tests that have not migrated.
    fun readText(inputStream: InputStream, cacheDirectory: File): String {
        val file = prepareCachedFile(inputStream, cacheDirectory, "legacy_${System.nanoTime()}")
        return try { readText(file) } finally { file.delete() }
    }

    fun readChapterIndex(inputStream: InputStream, cacheDirectory: File): List<ChmChapter> {
        val file = prepareCachedFile(inputStream, cacheDirectory, "legacy_${System.nanoTime()}")
        return try { readChapterIndex(file) } finally { file.delete() }
    }

    fun readChapterText(inputStream: InputStream, chapterPath: String, cacheDirectory: File): String {
        val file = prepareCachedFile(inputStream, cacheDirectory, "legacy_${System.nanoTime()}")
        return try { readChapterText(file, chapterPath) } finally { file.delete() }
    }

    private fun chapterEntries(archive: ChmFile): List<ChmChapter> {
        val result = mutableListOf<ChmChapter>()
        val seen = linkedSetOf<String>()

        fun add(pathValue: String?, titleValue: String?) {
            val path = normalizePath(pathValue.orEmpty())
            if (path.isBlank() || !isReadableDocument(path)) return
            if (!seen.add(path.lowercase())) return
            val resolved = resolveEntry(archive, path) ?: return
            if (resolved.length !in 1L..MAX_ENTRY_BYTES) return

            val archiveTitle = titleValue?.trim()?.takeIf { it.isNotBlank() && it != "<Top>" }
                ?: archive.getTitleOfObject(resolved.path.orEmpty())
                    ?.trim()?.takeIf { it.isNotBlank() && it != resolved.path }
            val title = if (archiveTitle == null || looksGarbled(archiveTitle)) {
                extractPageTitle(readEntrySource(archive, resolved, TITLE_PREFIX_BYTES))
                    .ifBlank { path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path } }
            } else {
                archiveTitle
            }
            result += ChmChapter(resolved.path.orEmpty(), cleanText(title))
        }

        fun walk(node: ChmTopicsTree?) {
            node?.children?.forEach { child ->
                add(child.path, child.title)
                walk(child)
            }
        }

        runCatching { walk(archive.topicsTree) }
        if (result.isEmpty()) {
            archive.homeFile?.let { add(it, archive.title) }
            readableEntries(archive).forEach { entry ->
                add(entry.path, archive.getTitleOfObject(entry.path.orEmpty()))
            }
        }
        return result.take(MAX_DOCUMENT_ENTRIES)
    }

    private fun readEntryText(archive: ChmFile, path: String): String {
        val entry = resolveEntry(archive, path) ?: return ""
        if (entry.length !in 1L..MAX_ENTRY_BYTES) return ""
        return cleanText(htmlOrPlainText(readEntrySource(archive, entry)))
    }

    private fun readEntrySource(
        archive: ChmFile,
        entry: ChmUnitInfo,
        maxBytes: Long? = null
    ): String {
        val buffer = runCatching {
            if (maxBytes == null || entry.length <= maxBytes) {
                archive.retrieveObject(entry)
            } else {
                archive.retrieveObject(entry, 0L, maxBytes)
            }
        }.getOrNull() ?: return ""
        val bytes = byteBufferBytes(buffer)
        return decodeDocumentBytes(bytes, archive.encoding)
    }

    /** Visible for deterministic unit tests of Chinese CHM page decoding. */
    fun decodeDocumentBytes(bytes: ByteArray, archiveEncoding: String? = null): String {
        if (bytes.isEmpty()) return ""
        val declared = declaredCharset(bytes)
        val candidates = linkedSetOf<Charset>()

        listOfNotNull(declared, archiveEncoding).forEach { name ->
            charsetOrNull(name)?.let(candidates::add)
        }
        runCatching { CharsetDetector.detectCharset(bytes) }.getOrNull()?.let(candidates::add)
        listOf("UTF-8", "GB18030", "Big5").mapNotNull(::charsetOrNull).forEach(candidates::add)

        val decoded = candidates.mapNotNull { charset ->
            decodeStrict(bytes, charset)?.let { text -> charset to text }
        }
        if (decoded.isNotEmpty()) {
            return decoded.maxByOrNull { (_, text) -> decodedQuality(text) }!!.second
        }
        return String(bytes, charsetOrNull("GB18030") ?: Charsets.UTF_8)
    }

    private fun declaredCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 16384), Charsets.ISO_8859_1)
        val patterns = listOf(
            Regex("(?is)<meta[^>]+charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9._-]+)"),
            Regex("(?is)content\\s*=\\s*[\\\"'][^\\\"']*charset\\s*=\\s*([A-Za-z0-9._-]+)")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(head)?.groupValues?.getOrNull(1)?.trim()
        }
    }

    private fun charsetOrNull(name: String): Charset? {
        val normalized = when (name.trim().uppercase()) {
            "GB2312", "HZ-GB-2312", "GBK", "CP936", "WINDOWS-936" -> "GB18030"
            "UTF8" -> "UTF-8"
            else -> name.trim()
        }
        return runCatching { Charset.forName(normalized) }.getOrNull()
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun decodedQuality(text: String): Long {
        var score = 0L
        text.forEach { char ->
            score += when {
                char == '\uFFFD' -> -1000L
                char == '\u0000' -> -200L
                char.code in 0..31 && char !in setOf('\n', '\r', '\t') -> -100L
                Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN -> 8L
                char.isLetterOrDigit() -> 3L
                char.isWhitespace() -> 1L
                else -> 0L
            }
        }
        if (text.contains("锟斤拷")) score -= 5000L
        return score
    }

    private fun byteBufferBytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        copy.rewind()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun extractPageTitle(source: String): String {
        val htmlTitle = Regex("(?is)<title[^>]*>(.*?)</title>").find(source)
            ?: Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(source)
        if (htmlTitle != null) {
            return cleanText(Html.fromHtml(htmlTitle.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString())
        }
        return extractReadableText(source)
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .take(160)
    }

    private fun resolveEntry(archive: ChmFile, rawPath: String): ChmUnitInfo? {
        val normalized = normalizePath(rawPath)
        val candidates = linkedSetOf(
            rawPath.substringBefore('#').substringBefore('?'),
            normalized,
            Uri.decode(normalized)
        ).filter { it.isNotBlank() }
        candidates.forEach { candidate -> archive.resolveObject(candidate)?.let { return it } }
        val target = normalizePath(Uri.decode(normalized)).lowercase()
        return readableEntries(archive).firstOrNull {
            normalizePath(Uri.decode(it.path.orEmpty())).lowercase() == target
        }
    }

    private fun readableEntries(archive: ChmFile): List<ChmUnitInfo> {
        val entries = mutableListOf<ChmUnitInfo>()
        archive.enumerate(ChmFile.CHM_ENUMERATE_NORMAL or ChmFile.CHM_ENUMERATE_FILES) { unit ->
            val path = unit.path.orEmpty()
            if (unit.length in 1L..MAX_ENTRY_BYTES && isReadableDocument(path) && entries.size < MAX_DOCUMENT_ENTRIES) {
                entries += unit
            }
        }
        return entries
    }

    private fun <T> withArchive(chmFile: File, block: (ChmFile) -> T): T {
        require(chmFile.isFile && chmFile.length() > 0L) { "CHM 缓存文件不可用" }
        return block(ChmFile(chmFile.absolutePath))
    }

    private fun htmlOrPlainText(source: String): String = extractReadableText(source)

    /**
     * Some Chinese CHM generators store each chapter as JavaScript-only text:
     * document.write("chapter"); document.write('body'). Reconstruct those
     * string arguments before HTML/entity decoding instead of showing script.
     */
    fun extractReadableText(source: String): String {
        val documentWrites = DOCUMENT_WRITE_PATTERN.findAll(source)
            .map { match ->
                val encoded = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
                decodeJavaScriptString(encoded)
            }
            .filter(String::isNotBlank)
            .toList()
        val readableSource = if (documentWrites.isNotEmpty()) {
            documentWrites.joinToString("\n")
        } else {
            source
                .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
                .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), "")
                .replace(Regex("(?is)<head\\b[^>]*>.*?</head>"), "")
        }
        val sample = readableSource.take(4096).lowercase()
        val hasMarkup = listOf("<html", "<body", "<p", "<div", "<br", "&nbsp;", "&#")
            .any(sample::contains)
        return if (hasMarkup) {
            Html.fromHtml(readableSource, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            readableSource
        }
    }

    private fun decodeJavaScriptString(encoded: String): String {
        val output = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val current = encoded[index]
            if (current != '\\' || index + 1 >= encoded.length) {
                output.append(current)
                index += 1
                continue
            }
            val escaped = encoded[index + 1]
            when (escaped) {
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                '\\' -> output.append('\\')
                '\'' -> output.append('\'')
                '"' -> output.append('"')
                'u' -> {
                    val hexEnd = (index + 6).coerceAtMost(encoded.length)
                    val hex = encoded.substring(index + 2, hexEnd)
                    if (hex.length == 4 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                        output.append(hex.toInt(16).toChar())
                        index += 4
                    } else {
                        output.append('u')
                    }
                }
                'x' -> {
                    val hexEnd = (index + 4).coerceAtMost(encoded.length)
                    val hex = encoded.substring(index + 2, hexEnd)
                    if (hex.length == 2 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                        output.append(hex.toInt(16).toChar())
                        index += 2
                    } else {
                        output.append('x')
                    }
                }
                else -> output.append(escaped)
            }
            index += 2
        }
        return output.toString()
    }

    private fun cleanText(text: String): String = text
        .replace('\u0000', ' ')
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun looksGarbled(text: String): Boolean {
        if (text.contains('\uFFFD') || text.contains("锟斤拷")) return true
        val latinNoise = text.count { it.code in 0x80..0xFF }
        val controls = text.count { it.code in 0..31 && it !in setOf('\n', '\r', '\t') }
        return controls > 0 || (text.isNotEmpty() && latinNoise * 3 > text.length)
    }

    private fun normalizePath(value: String): String {
        val cleaned = value.substringBefore('#').substringBefore('?').replace('\\', '/').trim()
        if (cleaned.isBlank()) return ""
        return if (cleaned.startsWith('/')) cleaned else "/$cleaned"
    }

    private fun isReadableDocument(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/#") || path.startsWith("/$") || path.startsWith("::")) return false
        val normalized = path.substringBefore('?').lowercase()
        return normalized.endsWith(".htm") || normalized.endsWith(".html") || normalized.endsWith(".xhtml") || normalized.endsWith(".txt")
    }

    private const val TITLE_PREFIX_BYTES = 32L * 1024L
    private val DOCUMENT_WRITE_PATTERN = Regex(
        """(?is)document\.write(?:ln)?\s*\(\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)')\s*\)"""
    )
    private const val CHAPTER_SEPARATOR = "\n\n"
}
