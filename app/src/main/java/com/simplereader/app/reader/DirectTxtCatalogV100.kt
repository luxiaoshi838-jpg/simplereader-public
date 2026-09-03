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

    private val diZhangHuaHui = Regex("第\\s*$NUM\\s*([章话回])")
    private val startJie = Regex("^第\\s*$NUM\\s*节(?:$|\\s+\\S.*|\\s*[:：、．—-]\\s*\\S.*)$")
    private val startOther = Regex("^第\\s*$NUM\\s*(?:单元|篇|部|卷|集)(?:$|\\s+\\S.*|\\s*[:：、.．—-]\\s*\\S.*)$")
    private val reverseUnit = Regex("^(?:单元|章|节|篇|部|卷|回|集)\\s*$NUM(?:$|\\s+\\S.*|\\s*[:：、.．—-]\\s*\\S.*)$")
    private val numberLeadingUnit = Regex("^($NUM)\\s*$STRUCTURE(?:$|\\s+\\S.*|\\s*[:：、.．—-]\\s*\\S.*)$")
    private val wrappedLeadingUnit = Regex("^[（(]\\s*$NUM\\s*[）)]\\s*$STRUCTURE(?:$|\\s+\\S.*|\\s*[:：、.．—-]\\s*\\S.*)$")
    private val wrappedChineseSuffix = Regex("^[\\p{IsHan}]{1,20}[（(]\\s*$NUM\\s*[）)]$")
    private val numericOnly = Regex("^\\s*$NUM\\s*$")
    private val explicitNumberedTitle = Regex("^($NUM)\\s*([、.．:：—-])\\s*(\\S.*)$")
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

        // “章/节/回/卷/篇/部/集/单元” is structural only when it is an independent unit.
        // Immediate ordinary text makes the token part of a normal word/phrase: 第3节课、12章鱼等。
        for (m in diZhangHuaHui.findAll(s)) {
            val unitEnd = m.range.last + 1
            if (hasStructuralBoundaryAfter(s, unitEnd) && !hasTerminator(s)) return s
        }
        if (startJie.matches(s) && !hasTerminator(s)) return s
        if (startOther.matches(s) && !hasTerminator(s)) return s
        if (reverseUnit.matches(s) && !hasTerminatorIgnoringSeparatorAt(s, 0)) return s
        if (numberLeadingUnit.matches(s) && !hasTerminator(s)) return s
        if (wrappedLeadingUnit.matches(s) && !hasTerminator(s)) return s
        if (wrappedChineseSuffix.matches(s) && !hasTerminator(s)) return s

        // Rule 113: bare Arabic/Chinese numerals require an explicit catalog separator.
        // Therefore 1天、2人、一条、三朵、十年等 cannot become chapters by number alone.
        if ('…' !in s) {
            explicitNumberedTitle.matchEntire(s)?.let { m ->
                val markerEnd = m.groupValues[1].length
                if (!hasTerminatorIgnoringSeparatorAt(s, markerEnd)) return s
            }
        }
        if (englishChapter.matches(s)) return s
        if (special.matches(s)) return s
        return null
    }

    private fun hasStructuralBoundaryAfter(s: String, endExclusive: Int): Boolean {
        if (endExclusive >= s.length) return true
        val next = s[endExclusive]
        return next.isWhitespace() || next in separators
    }

    private fun hasTerminator(s: String): Boolean = s.any { it in sentenceTerminators }

    private fun hasTerminatorIgnoringSeparatorAt(s: String, markerEnd: Int): Boolean {
        var separatorIndex = markerEnd.coerceIn(0, s.length)
        while (separatorIndex < s.length && s[separatorIndex].isWhitespace()) separatorIndex++
        if (separatorIndex < s.length && s[separatorIndex] in separators) separatorIndex++
        return s.indices.any { i -> i != separatorIndex - 1 && s[i] in sentenceTerminators }
    }
}
