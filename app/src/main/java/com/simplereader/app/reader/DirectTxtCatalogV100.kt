package com.simplereader.app.reader

import com.simplereader.app.reader.page.BookChapter

/**
 * Source restoration of the direct TXT catalog detector introduced in V677 and carried through
 * the V681/V683 -> V697 -> V722 -> V745 reading baseline. The final V745 DEX reports rule 111.
 */
object DirectTxtCatalogV100 {
    const val RULE_VERSION = 111
    private const val MAX_VISIBLE_TITLE_CHARS = 25
    private const val CN = "零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
    private const val NUM = "[0-9０-９$CN]+"

    private val diZhangHuaHui = Regex("第\\s*$NUM\\s*([章话回])")
    private val startJie = Regex("^第\\s*$NUM\\s*节(?:\\s*[:：、．—-]?\\s*.*)?$")
    private val startOther = Regex("^第\\s*$NUM\\s*(?:单元|篇|部|卷|集)(?:\\s*[:：、.．—-]?\\s*.*)?$")
    private val reverseUnit = Regex("^(?:单元|章|节|篇|部|卷|回|集)\\s*$NUM(?:\\s*[:：、.．—-]?\\s*.*)?$")
    private val wrapped = Regex("[（(]\\s*($NUM)\\s*[）)]")
    private val bareArabicPrefix = Regex("^([0-9０-９]+)(.*)$")
    private val bareCnPrefix = Regex("^([$CN]+)(.*)$")
    private val bareArabicSuffix = Regex("^(.*?)([0-9０-９]+)$")
    private val bareCnSuffix = Regex("^(.*?)([$CN]+)$")
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

        // V745: 第N章/话 are allowed at any position; 第N回 at line start follows the same
        // terminator guard, while embedded 第N回 is retained as an explicit marker.
        for (m in diZhangHuaHui.findAll(s)) {
            val unit = m.groupValues[1].firstOrNull() ?: continue
            if (unit == '回' && m.range.first != 0) return s
            if (!hasTerminator(s)) return s
        }
        if (startJie.matches(s) && !hasTerminator(s)) return s
        if (startOther.matches(s)) return s
        if (reverseUnit.matches(s) && !hasTerminatorIgnoringSeparatorAt(s, 0)) return s

        for (m in wrapped.findAll(s)) {
            if (m.range.first == 0) {
                if (!hasTerminator(s)) return s
            } else if (!hasTerminatorIgnoringSeparatorAt(s, m.range.last + 1)) {
                return s
            }
        }

        // V745 deliberately does not classify ellipsis-bearing prose through bare number rules.
        if ('…' !in s) {
            bareArabicPrefix.matchEntire(s)?.let { m ->
                val body = m.groupValues[2]
                if (body.isNotEmpty() && !startsWithNumericChar(body) &&
                    !hasTerminatorIgnoringSeparatorAt(s, m.groupValues[1].length)) return s
            }
            bareCnPrefix.matchEntire(s)?.let { m ->
                val body = m.groupValues[2]
                if (body.isNotEmpty() && !startsWithNumericChar(body) &&
                    !hasTerminatorIgnoringSeparatorAt(s, m.groupValues[1].length)) return s
            }
            bareArabicSuffix.matchEntire(s)?.let { m ->
                if (m.groupValues[1].trim().isNotEmpty() && !hasTerminator(s)) return s
            }
            bareCnSuffix.matchEntire(s)?.let { m ->
                if (m.groupValues[1].trim().isNotEmpty() && !hasTerminator(s)) return s
            }
        }
        if (englishChapter.matches(s)) return s
        if (special.matches(s)) return s
        return null
    }

    private fun hasTerminator(s: String): Boolean = s.any { it in sentenceTerminators }

    private fun hasTerminatorIgnoringSeparatorAt(s: String, markerEnd: Int): Boolean {
        var separatorIndex = markerEnd.coerceIn(0, s.length)
        while (separatorIndex < s.length && s[separatorIndex].isWhitespace()) separatorIndex++
        if (separatorIndex < s.length && s[separatorIndex] in separators) separatorIndex++
        return s.indices.any { i -> i != separatorIndex - 1 && s[i] in sentenceTerminators }
    }

    private fun startsWithNumericChar(s: String): Boolean {
        val c = s.firstOrNull() ?: return false
        return c in '0'..'9' || c in '０'..'９' || c in CN
    }
}
