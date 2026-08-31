package com.simplereader.app.reader

/**
 * V681/V745 catalog-title normalizer. Display/recognition cleanup only: source text and offsets
 * are never rewritten.
 */
object CatalogTitleNormalizerV103 {
    private const val NUM = "0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"
    private val chapterMark = Regex("第\\s*((?:[$NUM]\\s*)+)(章|话|回|节)")
    private val wrapped = Regex("([（(])\\s*([$NUM]+)\\s*([）)])")

    @JvmStatic
    fun recognizeNormalized(raw: String?): String? = DirectTxtCatalogV100.recognize(raw)?.let(::normalize)

    @JvmStatic
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val whitespace = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x200B || cp == 0xFEFF
            if (whitespace) {
                if (out.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            out.appendCodePoint(cp)
            pendingSpace = false
        }
        var value = out.toString().trim()
        value = chapterMark.replace(value) { m ->
            "第${m.groupValues[1].replace(" ", "")}${m.groupValues[2]}"
        }
        value = wrapped.replace(value) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}"
        }
        return value
    }
}
