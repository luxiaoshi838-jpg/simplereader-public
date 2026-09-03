package com.simplereader.app.reader

import com.simplereader.app.reader.page.BookChapter

/**
 * Direct TXT catalog detector. Rule 113 tightens numeral-based headings so ordinary
 * numeral + classifier/noun phrases are not promoted to catalog entries.
 */
object DirectTxtCatalogV100 {
    const val RULE_VERSION = 113
    private const val MAX_VISIBLE_TITLE_CHARS = 25
    private const val CN = "零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
    private const val NUM = "[0-9０-９$CN]+"
    private const val STRUCTURE = "(?:单元|章|节|篇|部|卷|回|集)"

    // Rule113 intentionally keeps structural regexes short. Whether the structural unit is
    // independent is checked in code by [hasValidStructuralTail], so "3节课" and "3节 课"
    // cannot accidentally collapse into the same regex case.
    private val prefixedStructural = Regex("第\\s*$NUM\\s*$STRUCTURE")
    private val numberLeadingUnit = Regex("^\\s*$NUM\\s*$STRUCTURE")
    private val reverseUnit = Regex("^\\s*$STRUCTURE\\s*$NUM")
    private val wrappedLeadingUnit = Regex("^\\s*[（(]\\s*$NUM\\s*[）)]\\s*$STRUCTURE")
    private val wrappedChineseSuffix = Regex("^[\\u4E00-\\u9FFF]{1,20}[（(]\\s*$NUM\\s*[）)]$")
    private val numericOnly = Regex("^\\s*$NUM\\s*$")
    private val explicitNumberedTitle = Regex("^\\s*$NUM\\s*[、.．:：—-]\\s*\\S.*$")
    private val englishChapter = Regex("^(?:Chapter|CHAPTER|chapter)\\s+[0-9IVXLCDMivxlcdm]+\\b.*$")
    private val special = Regex("^(?:正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|番外篇)(?:\\s+.*)?$")
    private val sentenceTerminators = charArrayOf('。', '.', '？', '?', '！', '!')
    private val separators = charArrayOf('.', '．', '、', ':', '：', '—', '-')

    data class Hit(val title: String, val start: Int)

    @JvmStatic
    fun detect(text: String?): List<BookChapter> {
        if (text.isNullOrEmpty()) return listOf(BookChapter("正文", 0, text?.length ?: 0))
        val hits = ArrayList<Hit>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = text.substring(start, end)
            val title = CatalogTitleNormalizerV103.recognizeNormalized(line)
            if (title != null && (hits.isEmpty() || hits.last().title != title)) hits += Hit(title, start)
            if (end >= text.length) break
            start = end + 1
        }
        if (hits.isEmpty()) return listOf(BookChapter("正文", 0, text.length))
        val result = ArrayList<BookChapter>(hits.size + 1)
        if (hits.first().start > 0) result += BookChapter("正文", 0, hits.first().start, catalogVisible = false)
        hits.forEachIndexed { i, hit ->
            result += BookChapter(
                title = hit.title,
                startOffset = hit.start,
                endOffset = hits.getOrNull(i + 1)?.start ?: text.length,
                catalogVisible = true
            )
        }
        return result
    }

    @JvmStatic
    fun recognize(raw: String?): String? {
        if (raw == null) return null
        val s = CatalogTitleNormalizerV103.normalize(raw) ?: return null
        val visible = s.count { !it.isWhitespace() }
        if (visible !in 1..MAX_VISIBLE_TITLE_CHARS) return null
        if ('“' in s || '”' in s || s.contains("http", ignoreCase = true)) return null
        if (numericOnly.matches(s)) return null

        // 第N章/节/回... may occur after a short title prefix in historical books, but the
        // structural unit itself must be followed by end-of-line, whitespace, or a catalog
        // separator. Immediate ordinary text means it is part of a normal word: 第3节课/第12章鱼.
        for (m in prefixedStructural.findAll(s)) {
            val end = m.range.last + 1
            if (hasValidStructuralTail(s, end) && !hasTerminatorIgnoringSeparatorAt(s, end)) return s
        }

        listOf(numberLeadingUnit, reverseUnit, wrappedLeadingUnit).forEach { pattern ->
            pattern.find(s)?.let { m ->
                val end = m.range.last + 1
                if (hasValidStructuralTail(s, end) && !hasTerminatorIgnoringSeparatorAt(s, end)) return s
            }
        }

        // Historical "大道之上（一）" style has no structural unit and remains explicitly valid.
        if (wrappedChineseSuffix.matches(s) && !hasTerminator(s)) return s

        // Bare Arabic/Chinese numerals require an explicit catalog separator.
        // Therefore 1天、2人、一条、三朵、十年等 cannot become chapters by number alone.
        if ('…' !in s && explicitNumberedTitle.matches(s)) {
            val firstSeparator = s.indexOfFirst { it in separators }
            if (firstSeparator >= 0 && !hasTerminatorIgnoringSeparatorAt(s, firstSeparator)) return s
        }
        if (englishChapter.matches(s)) return s
        if (special.matches(s)) return s
        return null
    }

    /**
     * The chapter unit is independent only when the next source character is a boundary.
     * Examples: "3节" / "3节 课" / "3节：课" are valid structures; "3节课" is not.
     */
    private fun hasValidStructuralTail(s: String, endExclusive: Int): Boolean {
        if (endExclusive >= s.length) return true
        val next = s[endExclusive]
        if (next.isWhitespace()) return s.substring(endExclusive).trim().isNotEmpty()
        if (next in separators) {
            var index = endExclusive + 1
            while (index < s.length && s[index].isWhitespace()) index++
            return index < s.length
        }
        return false
    }

    private fun hasTerminator(s: String): Boolean = s.any { it in sentenceTerminators }

    /** Ignore at most the single catalog separator immediately following the marker/unit. */
    private fun hasTerminatorIgnoringSeparatorAt(s: String, markerEnd: Int): Boolean {
        var separatorIndex = markerEnd.coerceIn(0, s.length)
        while (separatorIndex < s.length && s[separatorIndex].isWhitespace()) separatorIndex++
        if (separatorIndex < s.length && s[separatorIndex] in separators) separatorIndex++
        return s.indices.any { i -> i != separatorIndex - 1 && s[i] in sentenceTerminators }
    }
}
