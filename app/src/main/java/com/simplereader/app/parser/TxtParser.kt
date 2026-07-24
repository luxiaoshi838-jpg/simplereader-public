package com.simplereader.app.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

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

data class TxtTranscodeResult(
    val sourceCharsetName: String,
    val chapters: List<TxtChapterHit>,
    val outputBytes: Long
)

object TxtParser {
    private const val CHARSET_SAMPLE_BYTES = 256 * 1024
    private const val MAX_LINE_BYTES = 1024 * 1024
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
        val text = decodeBestEffort(bytes, charset.name()).removePrefix("\uFEFF")
        return TxtReadResult(
            text = text,
            charsetName = charset.name(),
            hadBom = hadBom,
            confidence = if (preferredCharsetName != null) "SAVED_WITH_LOCAL_FALLBACK" else "AUTO"
        )
    }

    fun detectCharset(inputStream: InputStream, preferredCharsetName: String? = null): String {
        val sample = inputStream.use { it.readUpTo(CHARSET_SAMPLE_BYTES) }
        val preferred = preferredCharsetName
            ?.let { runCatching { Charset.forName(normalizeCharsetName(it)) }.getOrNull() }
        val detected = runCatching { CharsetDetector.detectCharset(sample) }.getOrNull()
        return when {
            detected != null && decodedQuality(String(sample, detected)) >= decodedQuality(
                String(sample, preferred ?: detected)
            ) -> detected.name()
            preferred != null -> preferred.name()
            detected != null -> detected.name()
            else -> Charsets.UTF_8.name()
        }
    }

    /**
     * Converts a source TXT once into a persistent UTF-8 text cache. Each raw line
     * is decoded independently, so a file that switches between UTF-8 and
     * GBK/GB18030 midway does not turn the remaining book into mojibake.
     */
    fun transcodeToUtf8(
        inputStream: InputStream,
        outputStream: OutputStream,
        preferredCharsetName: String? = null
    ): TxtTranscodeResult {
        val sample = inputStream.readUpTo(CHARSET_SAMPLE_BYTES)
        val preferred = preferredCharsetName
            ?.let { runCatching { Charset.forName(normalizeCharsetName(it)) }.getOrNull() }
        val detected = runCatching { CharsetDetector.detectCharset(sample) }.getOrNull()
            ?: preferred
            ?: Charsets.UTF_8
        val combined = SequenceInputStream(ByteArrayInputStream(sample), inputStream)
        val counting = CountingOutputStream(outputStream)
        val chapters = mutableListOf<TxtChapterHit>()

        fun writeLine(textValue: String, appendNewline: Boolean) {
            val text = textValue.removePrefix("\uFEFF").trimEnd('\r')
            val start = counting.count
            extractChapterTitle(text.trim())?.let { title ->
                val last = chapters.lastOrNull()
                if (last == null || start - last.byteOffset > 80L) {
                    chapters += TxtChapterHit(title, start)
                }
            }
            val rendered = if (appendNewline) "$text\n" else text
            counting.write(rendered.toByteArray(Charsets.UTF_8))
        }

        if (detected.name().startsWith("UTF-16", ignoreCase = true)) {
            combined.bufferedReader(detected).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    writeLine(line, appendNewline = true)
                    line = reader.readLine()
                }
            }
        } else {
            combined.use { stream ->
                val lineBytes = ByteArrayOutputStream()
                while (true) {
                    val read = stream.read()
                    if (read == -1) {
                        if (lineBytes.size() > 0) {
                            writeLine(
                                decodeBestEffort(lineBytes.toByteArray(), detected.name()),
                                appendNewline = false
                            )
                        }
                        break
                    }
                    if (read == '\n'.code) {
                        writeLine(
                            decodeBestEffort(lineBytes.toByteArray(), detected.name()),
                            appendNewline = true
                        )
                        lineBytes.reset()
                    } else if (lineBytes.size() < MAX_LINE_BYTES) {
                        lineBytes.write(read)
                    } else {
                        writeLine(
                            decodeBestEffort(lineBytes.toByteArray(), detected.name()),
                            appendNewline = false
                        )
                        lineBytes.reset()
                        lineBytes.write(read)
                    }
                }
            }
        }
        counting.flush()
        return TxtTranscodeResult(
            sourceCharsetName = detected.name(),
            chapters = chapters,
            outputBytes = counting.count
        )
    }

    fun decodeBestEffort(bytes: ByteArray, preferredCharsetName: String? = null): String {
        if (bytes.isEmpty()) return ""
        detectBomCharset(bytes)?.let { charset ->
            return String(bytes, charset).removePrefix("\uFEFF")
        }
        val preferred = preferredCharsetName
            ?.let { runCatching { Charset.forName(normalizeCharsetName(it)) }.getOrNull() }
        val locallyDetected = runCatching { CharsetDetector.detectCharset(bytes) }.getOrNull()
        val candidates = linkedSetOf<Charset>()
        listOfNotNull(locallyDetected, preferred).forEach(candidates::add)
        listOf("UTF-8", "GB18030", "Big5", "windows-1252")
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .forEach(candidates::add)

        val decoded = candidates.mapNotNull { charset ->
            decodeStrict(bytes, charset)?.let { text ->
                var score = decodedQuality(text)
                if (locallyDetected?.name().equals(charset.name(), ignoreCase = true)) score += 5000
                if (preferred?.name().equals(charset.name(), ignoreCase = true)) score += 400
                Triple(score, charset, text)
            }
        }
        return decoded.maxByOrNull { it.first }?.third
            ?: String(bytes, preferred ?: Charset.forName("GB18030"))
    }

    fun readWindow(
        inputStream: InputStream,
        charsetName: String,
        startByte: Long,
        maxBytes: Int
    ): TxtWindowResult {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val charset = Charset.forName(normalizeCharsetName(charsetName))
        val safeStart = startByte.coerceAtLeast(0L)
        if (charset.name().equals("UTF-8", ignoreCase = true)) {
            val probeStart = (safeStart - 4L).coerceAtLeast(0L)
            inputStream.use { stream ->
                stream.skipFully(probeStart)
                val bytes = stream.readUpTo(maxBytes + 12)
                if (bytes.isEmpty()) return TxtWindowResult("", safeStart, safeStart)
                val requestedIndex = (safeStart - probeStart).toInt().coerceIn(0, bytes.size)
                var startIndex = requestedIndex
                while (startIndex < bytes.size && isUtf8Continuation(bytes[startIndex])) startIndex++
                var endIndex = (startIndex + maxBytes).coerceAtMost(bytes.size)
                var decoded: String? = null
                var attempts = 0
                while (endIndex >= startIndex && attempts < 5) {
                    decoded = decodeStrict(bytes.copyOfRange(startIndex, endIndex), Charsets.UTF_8)
                    if (decoded != null) break
                    endIndex--
                    attempts++
                }
                val text = decoded ?: String(bytes, startIndex, (endIndex - startIndex).coerceAtLeast(0), Charsets.UTF_8)
                return TxtWindowResult(
                    text = text.removePrefix("\uFEFF"),
                    startByte = probeStart + startIndex,
                    nextByte = probeStart + endIndex
                )
            }
        }

        val alignedStart = when {
            charset.name().startsWith("UTF-16", ignoreCase = true) -> safeStart - (safeStart % 2L)
            else -> safeStart
        }
        inputStream.use { stream ->
            stream.skipFully(alignedStart)
            val bytes = stream.readUpTo(maxBytes)
            val text = decodeBestEffort(bytes, charset.name()).removePrefix("\uFEFF")
            return TxtWindowResult(
                text = text,
                startByte = alignedStart,
                nextByte = alignedStart + bytes.size
            )
        }
    }

    fun scanChapters(
        inputStream: InputStream,
        charsetName: String,
        maxChapters: Int = 5000,
        maxScanBytes: Long = Long.MAX_VALUE
    ): List<TxtChapterHit> {
        val charset = Charset.forName(normalizeCharsetName(charsetName))
        val chapters = mutableListOf<TxtChapterHit>()
        inputStream.use { stream ->
            val lineBytes = ByteArrayOutputStream()
            var absoluteOffset = 0L
            var lineStartOffset = 0L
            while (chapters.size < maxChapters && absoluteOffset < maxScanBytes) {
                val read = stream.read()
                if (read == -1) break
                absoluteOffset += 1L
                if (read == '\n'.code) {
                    val line = decodeBestEffort(lineBytes.toByteArray(), charset.name()).trim()
                    val title = extractChapterTitle(line)
                    if (title != null) {
                        val last = chapters.lastOrNull()
                        if (last == null || lineStartOffset - last.byteOffset > 80L) {
                            chapters += TxtChapterHit(title, lineStartOffset)
                        }
                    }
                    lineBytes.reset()
                    lineStartOffset = absoluteOffset
                } else if (read != '\r'.code && lineBytes.size() < MAX_LINE_BYTES) {
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
        val charset = Charset.forName(normalizeCharsetName(charsetName))
        val normalizedQuery = query.lowercase()
        inputStream.use { stream ->
            stream.skipFully(startByte.coerceAtLeast(0L))
            val lineBytes = ByteArrayOutputStream()
            var absoluteOffset = startByte.coerceAtLeast(0L)
            var lineStartOffset = absoluteOffset
            while (true) {
                val read = stream.read()
                if (read == -1) {
                    val tail = decodeBestEffort(lineBytes.toByteArray(), charset.name())
                    if (tail.lowercase().contains(normalizedQuery)) return lineStartOffset
                    return null
                }
                absoluteOffset += 1L
                if (read == '\n'.code) {
                    val line = decodeBestEffort(lineBytes.toByteArray(), charset.name())
                    if (line.lowercase().contains(normalizedQuery)) return lineStartOffset
                    lineBytes.reset()
                    lineStartOffset = absoluteOffset
                } else if (read != '\r'.code && lineBytes.size() < MAX_LINE_BYTES) {
                    lineBytes.write(read)
                }
            }
        }
    }

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
        val charset = Charset.forName(normalizeCharsetName(charsetName))
        val normalizedQuery = query.lowercase()
        val safeStart = startByte.coerceAtLeast(0L)
        val hits = mutableListOf<TxtSearchHit>()
        var nextByte = safeStart
        var endReached = false

        fun collectLine(lineBytes: ByteArray, lineStartOffset: Long) {
            if (lineBytes.isEmpty()) return
            val line = decodeBestEffort(lineBytes, charset.name()).trimEnd('\r')
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
                } else if (read != '\r'.code && lineBytes.size() < MAX_LINE_BYTES) {
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
        } catch (_: Exception) {
            file.readBytes().let { bytes -> decodeBestEffort(bytes, charset.name()) }
        }
    }

    fun getChapterText(content: String, start: Int = 0, end: Int = content.length): String {
        return content.substring(start.coerceIn(0, content.length), end.coerceIn(0, content.length))
    }

    fun getTotalLength(file: File, charset: Charset = Charsets.UTF_8): Int {
        return readChapter(file, charset).length
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
                char == '\uFFFD' -> -5000L
                char == '\u0000' -> -1000L
                char.code in 0..31 && char !in setOf('\n', '\r', '\t') -> -500L
                Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN -> 12L
                char.isLetterOrDigit() -> 3L
                char.isWhitespace() -> 1L
                else -> 0L
            }
        }
        listOf("锟斤拷", "烫烫烫", "屯屯屯", "娴嬭瘯", "鐨勭", "鏂囨湰").forEach {
            if (text.contains(it)) score -= 20000L
        }
        return score
    }

    private fun normalizeCharsetName(name: String): String {
        return when (name.trim().uppercase()) {
            "GB2312", "HZ-GB-2312", "GBK", "CP936", "WINDOWS-936" -> "GB18030"
            "UTF8" -> "UTF-8"
            else -> name.trim()
        }
    }

    private fun detectBomCharset(bytes: ByteArray): Charset? {
        return when {
            hasUtf8Bom(bytes) -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charset.forName("UTF-16LE")
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charset.forName("UTF-16BE")
            else -> null
        }
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

    private fun isUtf8Continuation(value: Byte): Boolean =
        (value.toInt() and 0xC0) == 0x80

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

    fun isLikelyChapterTitle(line: String): Boolean = extractChapterTitle(line) != null

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

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
