package com.simplereader.app.parser

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

data class TxtReadResult(
    val text: String,
    val charsetName: String,
    val hadBom: Boolean,
    val confidence: String
)

data class TxtWindowResult(
    val text: String,
    val startByte: Long,
    val nextByte: Long
)

data class TxtChapterHit(
    val title: String,
    val byteOffset: Long
)

data class TxtSearchHit(
    val byteOffset: Long,
    val preview: String
)

data class TxtSearchPage(
    val hits: List<TxtSearchHit>,
    val nextByte: Long,
    val endReached: Boolean
)

object TxtParser {
    private const val CHARSET_SAMPLE_BYTES = 256 * 1024
    private val chapterPrefixPatterns by lazy {
        listOf(
            Regex("^\\s*[【\\[]?第\\s*[0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*[章节卷回部集篇].*"),
            Regex("^\\s*[卷部篇]\\s*[0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+.*"),
            Regex("^\\s*[0-9]{1,5}\\s*[、.．]\\s*\\S+.*"),
            Regex("^\\s*(Chapter|CHAPTER|chapter)\\s+[0-9IVXLCDMivxlcdm]+\\b.*"),
            Regex("^\\s*(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|番外篇).*"),
        )
    }

    fun readText(inputStream: InputStream, preferredCharsetName: String? = null): TxtReadResult {
        val bytes = inputStream.use { it.readBytes() }
        val charset = preferredCharsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: CharsetDetector.detectCharset(bytes)
        val hadBom = hasUtf8Bom(bytes) || hasUtf16Bom(bytes)
        val text = String(bytes, charset).removePrefix("\uFEFF")
        return TxtReadResult(
            text = text,
            charsetName = charset.name(),
            hadBom = hadBom,
            confidence = if (preferredCharsetName != null) "SAVED" else "AUTO"
        )
    }

    fun detectCharset(inputStream: InputStream, preferredCharsetName: String? = null): String {
        preferredCharsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?.let { return it.name() }
        val sample = inputStream.use { it.readUpTo(CHARSET_SAMPLE_BYTES) }
        return CharsetDetector.detectCharset(sample).name()
    }

    fun readWindow(
        inputStream: InputStream,
        charsetName: String,
        startByte: Long,
        maxBytes: Int
    ): TxtWindowResult {
        val safeStart = startByte.coerceAtLeast(0L)
        inputStream.use { stream ->
            stream.skipFully(safeStart)
            val bytes = stream.readUpTo(maxBytes)
            val charset = Charset.forName(charsetName)
            val text = String(bytes, charset).removePrefix("\uFEFF")
            return TxtWindowResult(
                text = text,
                startByte = safeStart,
                nextByte = safeStart + bytes.size
            )
        }
    }

    fun scanChapters(
        inputStream: InputStream,
        charsetName: String,
        maxChapters: Int = 5000,
        maxScanBytes: Long = 256L * 1024L * 1024L
    ): List<TxtChapterHit> {
        val charset = Charset.forName(charsetName)
        val chapters = mutableListOf<TxtChapterHit>()
        inputStream.use { stream ->
            val lineBytes = ByteArrayOutputStream()
            var absoluteOffset = 0L
            var lineStartOffset = 0L
            var read: Int
            while (chapters.size < maxChapters && absoluteOffset < maxScanBytes) {
                read = stream.read()
                if (read == -1) break
                absoluteOffset += 1L
                if (read == '\n'.code) {
                    val line = String(lineBytes.toByteArray(), charset).trim()
                    val title = extractChapterTitle(line)
                    if (title != null) {
                        val last = chapters.lastOrNull()
                        if (last == null || lineStartOffset - last.byteOffset > 80L) {
                            chapters += TxtChapterHit(title, lineStartOffset)
                        }
                    }
                    lineBytes.reset()
                    lineStartOffset = absoluteOffset
                } else if (read != '\r'.code && lineBytes.size() < 512) {
                    lineBytes.write(read)
                }
            }
        }
        return chapters
    }

    fun findTextOffset(
        inputStream: InputStream,
        charsetName: String,
        query: String,
        startByte: Long
    ): Long? {
        if (query.isBlank()) return null
        val charset = Charset.forName(charsetName)
        val normalizedQuery = query.lowercase()
        inputStream.use { stream ->
            stream.skipFully(startByte.coerceAtLeast(0L))
            val lineBytes = ByteArrayOutputStream()
            var absoluteOffset = startByte.coerceAtLeast(0L)
            var lineStartOffset = absoluteOffset
            while (true) {
                val read = stream.read()
                if (read == -1) {
                    val tail = String(lineBytes.toByteArray(), charset)
                    if (tail.lowercase().contains(normalizedQuery)) return lineStartOffset
                    return null
                }
                absoluteOffset += 1L
                if (read == '\n'.code) {
                    val line = String(lineBytes.toByteArray(), charset)
                    if (line.lowercase().contains(normalizedQuery)) return lineStartOffset
                    lineBytes.reset()
                    lineStartOffset = absoluteOffset
                } else if (read != '\r'.code && lineBytes.size() < 8192) {
                    lineBytes.write(read)
                }
            }
        }
    }

    /**
     * Incrementally scans a TXT stream and returns one lightweight result page.
     * The caller reopens the stream and passes [nextByte] to load the following page.
     */
    fun findTextPage(
        inputStream: InputStream,
        charsetName: String,
        query: String,
        startByte: Long,
        pageSize: Int = 40
    ): TxtSearchPage {
        if (query.isBlank() || pageSize <= 0) {
            return TxtSearchPage(emptyList(), startByte.coerceAtLeast(0L), true)
        }
        val charset = Charset.forName(charsetName)
        val normalizedQuery = query.lowercase()
        val safeStart = startByte.coerceAtLeast(0L)
        val hits = mutableListOf<TxtSearchHit>()
        var nextByte = safeStart
        var endReached = false

        fun collectLine(lineBytes: ByteArray, lineStartOffset: Long) {
            if (lineBytes.isEmpty()) return
            val line = String(lineBytes, charset).trimEnd('\r')
            val normalizedLine = line.lowercase()
            var fromIndex = 0
            while (fromIndex <= normalizedLine.length - normalizedQuery.length) {
                val index = normalizedLine.indexOf(normalizedQuery, fromIndex)
                if (index < 0) break
                val prefixBytes = line.substring(0, index).toByteArray(charset).size
                val contextStart = (index - 36).coerceAtLeast(0)
                val contextEnd = (index + query.length + 72).coerceAtMost(line.length)
                val preview = line.substring(contextStart, contextEnd)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                hits += TxtSearchHit(
                    byteOffset = lineStartOffset + prefixBytes,
                    preview = preview.ifBlank { "位置 ${lineStartOffset + prefixBytes}" }
                )
                fromIndex = index + query.length.coerceAtLeast(1)
            }
        }

        inputStream.use { stream ->
            stream.skipFully(safeStart)
            val lineBytes = ByteArrayOutputStream()
            var absoluteOffset = safeStart
            var lineStartOffset = safeStart
            while (true) {
                val read = stream.read()
                if (read == -1) {
                    collectLine(lineBytes.toByteArray(), lineStartOffset)
                    nextByte = absoluteOffset
                    endReached = true
                    break
                }
                absoluteOffset += 1L
                if (read == '\n'.code) {
                    collectLine(lineBytes.toByteArray(), lineStartOffset)
                    lineBytes.reset()
                    lineStartOffset = absoluteOffset
                    nextByte = absoluteOffset
                    if (hits.size >= pageSize) break
                } else if (read != '\r'.code && lineBytes.size() < 64 * 1024) {
                    lineBytes.write(read)
                }
            }
        }

        return TxtSearchPage(
            hits = hits,
            nextByte = nextByte,
            endReached = endReached
        )
    }

    fun readChapter(file: File, charset: Charset = Charsets.UTF_8): String {
        return try {
            file.readText(charset)
        } catch (e: Exception) {
            file.readBytes().let { bytes ->
                val detected = CharsetDetector.detectCharset(bytes)
                String(bytes, detected)
            }
        }
    }

    fun getChapterText(content: String, start: Int = 0, end: Int = content.length): String {
        return content.substring(start.coerceIn(0, content.length), end.coerceIn(0, content.length))
    }

    fun getTotalLength(file: File, charset: Charset = Charsets.UTF_8): Int {
        return readChapter(file, charset).length
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean {
        return bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
    }

    private fun hasUtf16Bom(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
    }

    private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = read(buffer, total, maxBytes - total)
            if (read <= 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() == -1) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    fun isLikelyChapterTitle(line: String): Boolean {
        return extractChapterTitle(line) != null
    }

    fun extractChapterTitle(line: String): String? {
        val normalized = line.trim()
        if (normalized.length !in 2..120) return null
        if (normalized.contains("http", ignoreCase = true)) return null
        if (normalized.count { it == '，' || it == ',' || it == '。' || it == '；' || it == ';' } > 2) return null
        if (chapterPrefixPatterns.any { it.matches(normalized) }) {
            return normalized.take(80)
        }
        return null
    }
}
