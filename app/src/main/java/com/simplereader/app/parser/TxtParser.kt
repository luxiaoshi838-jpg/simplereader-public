package com.simplereader.app.parser

import com.simplereader.app.reader.DirectTxtCatalogV100

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
    /** Bump this whenever TXT catalog recognition rules change. */
    const val CATALOG_RULE_VERSION = 113

    private const val CHARSET_SAMPLE_BYTES = 256 * 1024
    private const val MAX_LINE_BYTES = 1024 * 1024
    private const val WINDOW_LINE_CONTEXT_BYTES = 16 * 1024
    private const val CHAPTER_NUMBER =
        "[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+"
    private const val CHAPTER_STRUCTURE = "(?:单元|章|节|篇|部|卷|回|集)"

    /**
     * Explicit rules are safe enough to mix in the same book. They cover normal
     * "第X章" titles, catalog-number prefixes such as "770.第767章 宝瓶",
     * structural headings and numbered headings.
     */
    private val structuredChapterPatterns by lazy {
        listOf(
            Regex("""^\s*第\s*$CHAPTER_NUMBER\s*$CHAPTER_STRUCTURE(?:\s*[:：、.．—-]?\s*\S.*)?$"""),
            Regex("""^\s*$CHAPTER_NUMBER\s*[.、．:：—-]\s*第\s*$CHAPTER_NUMBER\s*$CHAPTER_STRUCTURE(?:\s*[:：、.．—-]?\s*\S.*)?$"""),
            Regex("""^\s*[（(【\[]\s*$CHAPTER_NUMBER\s*[）)】\]]\s*$CHAPTER_STRUCTURE(?:\s*[:：、.．—-]?\s*\S.*)?$"""),
            Regex("""^\s*[^\s，。！？；;]{1,20}\s*[：:—-]\s*第\s*$CHAPTER_NUMBER\s*$CHAPTER_STRUCTURE(?:\s+\S.*)?$"""),
            Regex("""^\s*[^\s，。！？；;]{1,16}\s+第\s*$CHAPTER_NUMBER\s*$CHAPTER_STRUCTURE(?:\s+\S.*)?$"""),
            Regex("""^\s*[（(][^（）()\r\n]{1,40}[）)]\s*第\s*$CHAPTER_NUMBER\s*$CHAPTER_STRUCTURE(?:\s*[:：、.．—-]?\s*\S.*)?$"""),
            Regex("""^\s*[卷部篇单元]\s*$CHAPTER_NUMBER(?:\s*[:：、.．—-]?\s*\S.*)?$"""),
            Regex("""^\s*$CHAPTER_NUMBER\s*$CHAPTER_STRUCTURE(?:\s*[:：、.．—-]?\s*\S.*)?$"""),
            Regex("""^\s*[上中下前后终]\s*[卷部篇](?:\s+\S.*)?$"""),
            Regex("""^\s*[（(【\[]?\s*$CHAPTER_NUMBER\s*[）)】\]]?\s*[、.．:：—-]\s*\S+.*$"""),
            Regex("""^\s*[（(【\[]\s*$CHAPTER_NUMBER\s*[）)】\]]\s+\S+.*$"""),
            Regex("""^\s*[0-9０-９]{1,6}\s+(?![年月日点时分秒个次米])\S.{0,100}$"""),
            Regex("""^\s*[零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[、.．:：—-]\s*\S+.*$"""),
            Regex("""^\s*[\p{script=Han}]{1,40}[（(]\s*$CHAPTER_NUMBER\s*[）)]\s*$"""),
            Regex("""^\s*[\p{L}\p{N}]{2,39}(?:单元|章|节|篇|部|卷|回|集)$"""),
            Regex("""^\s*(?:Chapter|CHAPTER|chapter)\s+[0-9IVXLCDMivxlcdm]+\b.*$"""),
            Regex("""^\s*(?:正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|番外篇)(?:\s+\S.*)?$""")
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
        val structuredChapters = mutableListOf<TxtChapterHit>()
        val fallbackChapters = mutableListOf<TxtChapterHit>()

        fun collectChapter(line: String, start: Long) {
            val structured = extractStructuredChapterTitle(line)
            val target = if (structured != null) structuredChapters else fallbackChapters
            val title = structured ?: extractFallbackChapterTitle(line) ?: return
            val last = target.lastOrNull()
            if (last == null || start - last.byteOffset > 80L) {
                target += TxtChapterHit(title, start)
            }
        }

        fun writeLine(textValue: String, appendNewline: Boolean) {
            val text = textValue.removePrefix("\uFEFF").trimEnd('\r')
            val start = counting.count
            collectChapter(text.trim(), start)
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
                            writeLine(decodeBestEffort(lineBytes.toByteArray(), detected.name()), appendNewline = false)
                        }
                        break
                    }
                    if (read == '\n'.code) {
                        writeLine(decodeBestEffort(lineBytes.toByteArray(), detected.name()), appendNewline = true)
                        lineBytes.reset()
                    } else if (lineBytes.size() < MAX_LINE_BYTES) {
                        lineBytes.write(read)
                    } else {
                        writeLine(decodeBestEffort(lineBytes.toByteArray(), detected.name()), appendNewline = false)
                        lineBytes.reset()
                        lineBytes.write(read)
                    }
                }
            }
        }
        counting.flush()
        return TxtTranscodeResult(
            sourceCharsetName = detected.name(),
            chapters = structuredChapters.ifEmpty { fallbackChapters },
            outputBytes = counting.count
        )
    }

    fun decodeBestEffort(bytes: ByteArray, preferredCharsetName: String? = null): String {
        if (bytes.isEmpty()) return ""
        detectBomCharset(bytes)?.let { charset ->
            return String(bytes, charset).removePrefix("\uFEFF")
        }
        decodeStrict(bytes, Charsets.UTF_8)?.let { utf8 ->
            if (!looksMojibake(utf8)) return utf8
        }
        val preferred = preferredCharsetName
            ?.let { runCatching { Charset.forName(normalizeCharsetName(it)) }.getOrNull() }
        preferred?.let { charset ->
            decodeStrict(bytes, charset)?.let { decoded ->
                if (!looksMojibake(decoded)) return decoded
            }
        }
        val locallyDetected = runCatching { CharsetDetector.detectCharset(bytes) }.getOrNull()
        val candidates = linkedSetOf<Charset>()
        listOfNotNull(locallyDetected, preferred).forEach(candidates::add)
        listOf("GB18030", "Big5", "windows-1252")
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .forEach(candidates::add)
        val decoded = candidates.mapNotNull { charset ->
            decodeStrict(bytes, charset)?.let { text ->
                var score = normalizedDecodedQuality(text)
                if (locallyDetected?.name().equals(charset.name(), ignoreCase = true)) score += 100_000L
                if (preferred?.name().equals(charset.name(), ignoreCase = true)) score += 10_000L
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
        if (charset.name().startsWith("UTF-16", ignoreCase = true)) {
            val alignedStart = safeStart - (safeStart % 2L)
            inputStream.use { stream ->
                stream.skipFully(alignedStart)
                val bytes = stream.readUpTo(maxBytes - (maxBytes % 2))
                return TxtWindowResult(
                    text = decodeBestEffort(bytes, charset.name()).removePrefix("\uFEFF"),
                    startByte = alignedStart,
                    nextByte = alignedStart + bytes.size
                )
            }
        }
        val probeStart = (safeStart - WINDOW_LINE_CONTEXT_BYTES).coerceAtLeast(0L)
        inputStream.use { stream ->
            stream.skipFully(probeStart)
            val bytes = stream.readUpTo(maxBytes + WINDOW_LINE_CONTEXT_BYTES * 2)
            if (bytes.isEmpty()) return TxtWindowResult("", safeStart, safeStart)
            val requestedIndex = (safeStart - probeStart).toInt().coerceIn(0, bytes.size)
            val startIndex = alignWindowStart(bytes, requestedIndex, safeStart == 0L, charset)
            if (startIndex >= bytes.size) {
                val absolute = probeStart + startIndex
                return TxtWindowResult("", absolute, absolute)
            }
            val desiredEnd = (startIndex + maxBytes).coerceAtMost(bytes.size)
            val endIndex = alignWindowEnd(bytes, startIndex, desiredEnd, charset).coerceIn(startIndex, bytes.size)
            val raw = bytes.copyOfRange(startIndex, endIndex)
            return TxtWindowResult(
                text = decodeLineWise(raw, charset.name()).removePrefix("\uFEFF"),
                startByte = probeStart + startIndex,
                nextByte = probeStart + endIndex
            )
        }
    }

    fun readWindowBefore(
        inputStream: InputStream,
        charsetName: String,
        endByte: Long,
        maxBytes: Int
    ): TxtWindowResult {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val safeEnd = endByte.coerceAtLeast(0L)
        if (safeEnd == 0L) return TxtWindowResult("", 0L, 0L)
        val charset = Charset.forName(normalizeCharsetName(charsetName))
        val probeStart = (safeEnd - maxBytes - WINDOW_LINE_CONTEXT_BYTES).coerceAtLeast(0L)
        val probeLength = (safeEnd - probeStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        inputStream.use { stream ->
            stream.skipFully(probeStart)
            val bytes = stream.readUpTo(probeLength)
            if (bytes.isEmpty()) return TxtWindowResult("", safeEnd, safeEnd)
            val desiredStart = (bytes.size - maxBytes).coerceAtLeast(0)
            val alignedStart = alignWindowStart(bytes, desiredStart, probeStart == 0L, charset).coerceIn(0, bytes.size)
            val raw = bytes.copyOfRange(alignedStart, bytes.size)
            return TxtWindowResult(
                text = if (charset.name().startsWith("UTF-16", ignoreCase = true)) {
                    decodeBestEffort(raw, charset.name())
                } else {
                    decodeLineWise(raw, charset.name())
                }.removePrefix("\uFEFF"),
                startByte = probeStart + alignedStart,
                nextByte = probeStart + bytes.size
            )
        }
    }

    private fun alignWindowStart(bytes: ByteArray, requestedIndex: Int, atFileStart: Boolean, charset: Charset): Int {
        if (atFileStart || requestedIndex <= 0) return 0
        if (bytes.getOrNull(requestedIndex - 1) == '\n'.code.toByte()) return requestedIndex
        val limit = (requestedIndex + WINDOW_LINE_CONTEXT_BYTES).coerceAtMost(bytes.size)
        for (index in requestedIndex until limit) {
            if (bytes[index] == '\n'.code.toByte()) return index + 1
        }
        return alignCharacterStart(bytes, requestedIndex, charset)
    }

    private fun alignWindowEnd(bytes: ByteArray, startIndex: Int, desiredEnd: Int, charset: Charset): Int {
        if (desiredEnd >= bytes.size) return bytes.size
        val limit = (desiredEnd + WINDOW_LINE_CONTEXT_BYTES).coerceAtMost(bytes.size)
        for (index in desiredEnd until limit) {
            if (bytes[index] == '\n'.code.toByte()) return index + 1
        }
        var end = desiredEnd.coerceAtLeast(startIndex)
        if (charset.name().equals("UTF-8", ignoreCase = true)) {
            while (end > startIndex && end < bytes.size && isUtf8Continuation(bytes[end])) end--
        }
        return end
    }

    private fun alignCharacterStart(bytes: ByteArray, requestedIndex: Int, charset: Charset): Int {
        var start = requestedIndex.coerceIn(0, bytes.size)
        if (charset.name().equals("UTF-8", ignoreCase = true)) {
            while (start < bytes.size && isUtf8Continuation(bytes[start])) start++
        }
        return start
    }

    private fun decodeLineWise(bytes: ByteArray, preferredCharsetName: String): String {
        if (bytes.isEmpty()) return ""
        val output = StringBuilder(bytes.size)
        var lineStart = 0
        bytes.forEachIndexed { index, value ->
            if (value == '\n'.code.toByte()) {
                val lineEnd = if (index > lineStart && bytes[index - 1] == '\r'.code.toByte()) index - 1 else index
                output.append(decodeBestEffort(bytes.copyOfRange(lineStart, lineEnd), preferredCharsetName))
                output.append('\n')
                lineStart = index + 1
            }
        }
        if (lineStart < bytes.size) output.append(decodeBestEffort(bytes.copyOfRange(lineStart, bytes.size), preferredCharsetName))
        return output.toString()
    }

    fun scanChapters(
        inputStream: InputStream,
        charsetName: String,
        maxChapters: Int = 5000,
        maxScanBytes: Long = Long.MAX_VALUE
    ): List<TxtChapterHit> {
        val charset = Charset.forName(normalizeCharsetName(charsetName))
        val structured = mutableListOf<TxtChapterHit>()
        val fallback = mutableListOf<TxtChapterHit>()
        fun collect(line: String, lineStartOffset: Long) {
            val structuredTitle = extractStructuredChapterTitle(line)
            val target = if (structuredTitle != null) structured else fallback
            val title = structuredTitle ?: extractFallbackChapterTitle(line) ?: return
            if (target.size >= maxChapters) return
            val last = target.lastOrNull()
            if (last == null || lineStartOffset - last.byteOffset > 80L) target += TxtChapterHit(title, lineStartOffset)
        }
        inputStream.use { stream ->
            val lineBytes = ByteArrayOutputStream()
            var absoluteOffset = 0L
            var lineStartOffset = 0L
            while (structured.size < maxChapters && absoluteOffset < maxScanBytes) {
                val read = stream.read()
                if (read == -1) {
                    if (lineBytes.size() > 0) collect(decodeBestEffort(lineBytes.toByteArray(), charset.name()).trim(), lineStartOffset)
                    break
                }
                absoluteOffset += 1L
                if (read == '\n'.code) {
                    collect(decodeBestEffort(lineBytes.toByteArray(), charset.name()).trim(), lineStartOffset)
                    lineBytes.reset()
                    lineStartOffset = absoluteOffset
                } else if (read != '\r'.code && lineBytes.size() < MAX_LINE_BYTES) {
                    lineBytes.write(read)
                }
            }
        }
        return structured.ifEmpty { fallback }.take(maxChapters)
    }

    fun findTextOffset(inputStream: InputStream, charsetName: String, query: String, startByte: Long): Long? {
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
                } else if (read != '\r'.code && lineBytes.size() < MAX_LINE_BYTES) lineBytes.write(read)
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
        if (query.isBlank() || pageSize <= 0) return TxtSearchPage(emptyList(), startByte.coerceAtLeast(0L), true)
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
                val preview = line.substring(contextStart, contextEnd).replace(Regex("\\s+"), " ").trim()
                hits += TxtSearchHit(lineStartOffset + prefixBytes, preview.ifBlank { "位置 ${lineStartOffset + prefixBytes}" })
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
                } else if (read != '\r'.code && lineBytes.size() < MAX_LINE_BYTES) lineBytes.write(read)
            }
        }
        return TxtSearchPage(hits, nextByte, endReached)
    }

    fun readChapter(file: File, charset: Charset = Charsets.UTF_8): String = try {
        file.readText(charset)
    } catch (_: Exception) {
        file.readBytes().let { bytes -> decodeBestEffort(bytes, charset.name()) }
    }

    fun getChapterText(content: String, start: Int = 0, end: Int = content.length): String =
        content.substring(start.coerceIn(0, content.length), end.coerceIn(0, content.length))

    fun getTotalLength(file: File, charset: Charset = Charsets.UTF_8): Int = readChapter(file, charset).length

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private val commonSimplifiedChineseCharacters: Set<Char> by lazy {
        "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处理世车价育口更标保治尔找文末乱码假装到达继续阅读章节正文".toSet()
    }

    private fun normalizedDecodedQuality(text: String): Long {
        if (text.isEmpty()) return Long.MIN_VALUE / 4
        return decodedQuality(text) * 1000L / text.length.coerceAtLeast(1)
    }

    private fun looksMojibake(text: String): Boolean {
        if (text.contains('\uFFFD') || text.contains('\u0000')) return true
        if (text.any { it.code in 0x3100..0x312F || it.code in 0xFE30..0xFE4F }) return true
        return listOf("锟斤拷", "烫烫烫", "屯屯屯", "娴嬭瘯", "鐨勭", "鏂囨湰").any(text::contains)
    }

    private fun decodedQuality(text: String): Long {
        var score = 0L
        text.forEach { char ->
            score += when {
                char == '\uFFFD' -> -5000L
                char == '\u0000' -> -1000L
                char.code in 0..31 && char !in setOf('\n', '\r', '\t') -> -500L
                char.code in 0x3100..0x312F -> -400L
                char.code in 0xFE30..0xFE4F -> -300L
                Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN -> if (char in commonSimplifiedChineseCharacters) 24L else 8L
                char.code in 0x80..0xFF -> -6L
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

    private fun normalizeCharsetName(name: String): String = when (name.trim().uppercase()) {
        "GB2312", "HZ-GB-2312", "GBK", "CP936", "WINDOWS-936" -> "GB18030"
        "UTF8" -> "UTF-8"
        else -> name.trim()
    }

    private fun detectBomCharset(bytes: ByteArray): Charset? = when {
        hasUtf8Bom(bytes) -> Charsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charset.forName("UTF-16LE")
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charset.forName("UTF-16BE")
        else -> null
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

    private fun hasUtf16Bom(bytes: ByteArray): Boolean =
        bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))

    private fun isUtf8Continuation(value: Byte): Boolean = (value.toInt() and 0xC0) == 0x80

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
            } else remaining -= skipped
        }
    }

    fun isLikelyChapterTitle(line: String): Boolean = extractChapterTitle(line) != null

    fun extractStructuredChapterTitle(line: String): String? = DirectTxtCatalogV100.recognize(line)

    private val numeralLeadingOrdinaryText = Regex(
        "^[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+[^、.．:：—-\\s].*$"
    )

    private fun hasGluedPrefixedStructuralText(value: String): Boolean {
        var index = 0
        if (value.getOrNull(index) != '第') return false
        index++
        while (value.getOrNull(index)?.isWhitespace() == true) index++

        val numeralChars = "0123456789０１２３４５６７８９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
        val numeralStart = index
        while (value.getOrNull(index)?.let { it in numeralChars } == true) index++
        if (index == numeralStart) return false
        while (value.getOrNull(index)?.isWhitespace() == true) index++

        val unit = listOf("单元", "章", "节", "篇", "部", "卷", "回", "集")
            .firstOrNull { value.startsWith(it, index) } ?: return false
        val tailIndex = index + unit.length
        if (tailIndex >= value.length) return false
        val next = value[tailIndex]
        return !next.isWhitespace() && next !in "、.．:：—-"
    }

    private fun startsWithNumeralToken(value: String): Boolean {
        val numeralChars = "0123456789０１２３４５６７８９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
        var index = 0
        while (value.getOrNull(index)?.isWhitespace() == true) index++
        return value.getOrNull(index)?.let { it in numeralChars } == true
    }

    fun extractFallbackChapterTitle(line: String): String? {
        val normalized = CatalogTitleNormalizerV103.normalize(line)
        if (normalized.length !in 2..40) return null
        if (normalized.contains("http", ignoreCase = true)) return null
        if (normalized.all(Char::isDigit)) return null
        // Rule 113: if a line begins with Arabic/Chinese numerals and then ordinary text without
        // a catalog separator, it is prose/quantity wording, not a fallback chapter title.
        if (startsWithNumeralToken(normalized)) return null
        if (numeralLeadingOrdinaryText.matches(normalized)) return null
        if (hasGluedPrefixedStructuralText(normalized)) return null
        if (normalized.any(::isTitlePunctuation)) return null
        if (normalized.count(Char::isWhitespace) > 4) return null
        if (normalized.none { it.isLetterOrDigit() }) return null
        if (fallbackSentenceMarkers.any { marker -> normalized.contains(marker) }) return null
        return normalized.take(80)
    }

    private val fallbackSentenceMarkers = listOf(
        "这是一", "这是", "那是", "一个", "一种", "普通正文", "正文内容",
        "我们", "你们", "他们", "因为", "所以", "但是", "然后", "已经", "正在",
        "可以", "需要", "应该", "如果", "并且", "以及"
    )

    fun extractChapterTitle(line: String): String? =
        extractStructuredChapterTitle(line) ?: extractFallbackChapterTitle(line)

    private fun isTitlePunctuation(character: Char): Boolean {
        if (character in "，,。；;！？!?：:、.．—-（）()【】[]《》〈〉“”‘’\"'…") return true
        return when (Character.getType(character)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
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
