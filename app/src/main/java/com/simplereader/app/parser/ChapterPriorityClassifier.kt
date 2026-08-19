package com.simplereader.app.parser

/**
 * v655 TXT catalog classifier.
 *
 * Explicit 第X章/话/节… headings and all supported numbered-heading shapes are
 * intentionally one structured priority. Callers must keep every structured hit
 * in source order; there is no family winner and no cross-family exclusion.
 */
object ChapterPriorityClassifier {
    const val INVALID = 0
    const val UNIFIED_STRUCTURED = 1
    const val PURE_TEXT_FALLBACK = 2

    private const val NUM = "[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+"
    private const val ARABIC = "[0-9０-９]+"
    private const val HIERARCHY = "$ARABIC(?:[.．]$ARABIC)*"
    private const val NUMBER_BLOCK = "(?:$HIERARCHY|$NUM)"
    private const val STRUCTURE = "(?:单元|章|节|篇|部|卷|回|集|话)"

    private val explicitAtStart = Regex(
        "^\\s*第\\s*($NUM)\\s*($STRUCTURE)(?:\\s*[:：、.．—-]?\\s*\\S.*)?$"
    )
    private val explicitAfterPrefix = Regex(
        "^\\s*[^\\r\\n]{1,40}?[：:、.．—-]\\s*第\\s*($NUM)\\s*($STRUCTURE)(?:\\s*[:：、.．—-]?\\s*\\S.*)?$"
    )
    private val explicitAfterWrappedPrefix = Regex(
        "^\\s*[（(][^（）()\\r\\n]{1,40}[）)]\\s*第\\s*($NUM)\\s*($STRUCTURE)(?:\\s*[:：、.．—-]?\\s*\\S.*)?$"
    )
    private val englishExplicit = Regex(
        "^\\s*(?:Chapter|CHAPTER|chapter)\\s+[0-9IVXLCDMivxlcdm]+\\b.*$"
    )

    private val pureNumber = Regex("^\\s*$NUMBER_BLOCK\\s*$")
    private val wrappedPureNumber = listOf(
        Regex("^\\s*\\(\\s*$NUMBER_BLOCK\\s*\\)\\s*[^\\p{L}\\p{N}\\s]*\\s*$"),
        Regex("^\\s*（\\s*$NUMBER_BLOCK\\s*）\\s*[^\\p{L}\\p{N}\\s]*\\s*$"),
        Regex("^\\s*\\[\\s*$NUMBER_BLOCK\\s*]\\s*[^\\p{L}\\p{N}\\s]*\\s*$"),
        Regex("^\\s*【\\s*$NUMBER_BLOCK\\s*】\\s*[^\\p{L}\\p{N}\\s]*\\s*$"),
        Regex("^\\s*《\\s*$NUMBER_BLOCK\\s*》\\s*[^\\p{L}\\p{N}\\s]*\\s*$"),
        Regex("^\\s*「\\s*$NUMBER_BLOCK\\s*」\\s*[^\\p{L}\\p{N}\\s]*\\s*$")
    )

    private val separatedPrefix = Regex(
        "^\\s*$NUMBER_BLOCK\\s*(?:[、:：—-]|[.．](?![0-9０-９]))\\s*\\S.*$"
    )
    private val hierarchyPrefixWithSpace = Regex(
        "^\\s*$HIERARCHY\\s+\\S.*$"
    )
    private val wrappedPrefix = listOf(
        Regex("^\\s*\\(\\s*$NUMBER_BLOCK\\s*\\)\\s*(?:[、.．:：—-]\\s*)?\\S.*$"),
        Regex("^\\s*（\\s*$NUMBER_BLOCK\\s*）\\s*(?:[、.．:：—-]\\s*)?\\S.*$"),
        Regex("^\\s*\\[\\s*$NUMBER_BLOCK\\s*]\\s*(?:[、.．:：—-]\\s*)?\\S.*$"),
        Regex("^\\s*【\\s*$NUMBER_BLOCK\\s*】\\s*(?:[、.．:：—-]\\s*)?\\S.*$"),
        Regex("^\\s*《\\s*$NUMBER_BLOCK\\s*》\\s*(?:[、.．:：—-]\\s*)?\\S.*$"),
        Regex("^\\s*「\\s*$NUMBER_BLOCK\\s*」\\s*(?:[、.．:：—-]\\s*)?\\S.*$")
    )
    private val wrappedSuffix = listOf(
        Regex("^\\s*\\S.*?\\(\\s*$NUMBER_BLOCK\\s*\\)\\s*$"),
        Regex("^\\s*\\S.*?（\\s*$NUMBER_BLOCK\\s*）\\s*$"),
        Regex("^\\s*\\S.*?\\[\\s*$NUMBER_BLOCK\\s*]\\s*$"),
        Regex("^\\s*\\S.*?【\\s*$NUMBER_BLOCK\\s*】\\s*$"),
        Regex("^\\s*\\S.*?《\\s*$NUMBER_BLOCK\\s*》\\s*$"),
        Regex("^\\s*\\S.*?「\\s*$NUMBER_BLOCK\\s*」\\s*$")
    )

    private val barePrefix = Regex("^\\s*$NUM\\s*[^\\s、.．:：—-].{0,100}$")
    private val bareSuffix = Regex("^\\s*\\S.{0,100}?$NUM\\s*$")

    fun priority(line: String): Int {
        val normalized = line.trim()
        if (normalized.length !in 1..120) return INVALID
        if (normalized.contains("http", ignoreCase = true)) return INVALID
        if (containsForbiddenCurlyQuote(normalized)) return INVALID

        explicitStructure(normalized)?.let { structure ->
            // User rule: only explicit 第X节 is blocked by sentence-ending punctuation.
            // 第X章 / 第X话 (and the other explicit structures) remain permissive.
            if (structure == "节" && hasSentenceTerminator(normalized)) return INVALID
            return UNIFIED_STRUCTURED
        }
        if (englishExplicit.matches(normalized)) return UNIFIED_STRUCTURED

        if (pureNumber.matches(normalized) || wrappedPureNumber.any { it.matches(normalized) }) {
            return UNIFIED_STRUCTURED
        }
        if (separatedPrefix.matches(normalized) ||
            hierarchyPrefixWithSpace.matches(normalized) ||
            wrappedPrefix.any { it.matches(normalized) } ||
            wrappedSuffix.any { it.matches(normalized) }
        ) {
            return UNIFIED_STRUCTURED
        }

        // Keep v654 safety on unseparated/bare number forms.
        if (barePrefix.matches(normalized)) {
            return if (hasSentenceTerminator(normalized)) INVALID else UNIFIED_STRUCTURED
        }
        if (bareSuffix.matches(normalized)) {
            return if (hasSentenceTerminator(normalized)) INVALID else UNIFIED_STRUCTURED
        }

        // A number in the middle is never allowed to fall through as a text-only heading.
        if (containsNumericMarker(normalized)) return INVALID
        if (hasSentenceTerminator(normalized)) return INVALID
        return PURE_TEXT_FALLBACK
    }

    fun isUnifiedStructured(line: String): Boolean = priority(line) == UNIFIED_STRUCTURED

    fun containsNumericMarker(text: String): Boolean = text.any { ch ->
        ch.isDigit() || ch in "０１２３４５６７８９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
    }

    fun hasSentenceTerminator(text: String): Boolean = text.any { it in "。.？?！!" }

    private fun containsForbiddenCurlyQuote(text: String): Boolean = text.any { it == '“' || it == '”' }

    private fun explicitStructure(text: String): String? {
        val match = explicitAtStart.matchEntire(text)
            ?: explicitAfterPrefix.matchEntire(text)
            ?: explicitAfterWrappedPrefix.matchEntire(text)
            ?: return null
        return match.groupValues[2]
    }
}
