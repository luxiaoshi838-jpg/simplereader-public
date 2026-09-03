from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/simplereader/app/parser/TxtParser.kt'
s = p.read_text(encoding='utf-8')

needle = '''    private val numeralLeadingOrdinaryText = Regex(\n        "^[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+[^、.．:：—-\\\\s].*$"\n    )\n\n    fun extractFallbackChapterTitle(line: String): String? {\n'''
replacement = '''    private val numeralLeadingOrdinaryText = Regex(\n        "^[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+[^、.．:：—-\\\\s].*$"\n    )\n\n    private fun hasGluedPrefixedStructuralText(value: String): Boolean {\n        var index = 0\n        if (value.getOrNull(index) != '第') return false\n        index++\n        while (value.getOrNull(index)?.isWhitespace() == true) index++\n\n        val numeralChars = "0123456789０１２３４５６７８９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"\n        val numeralStart = index\n        while (value.getOrNull(index)?.let { it in numeralChars } == true) index++\n        if (index == numeralStart) return false\n        while (value.getOrNull(index)?.isWhitespace() == true) index++\n\n        val unit = listOf("单元", "章", "节", "篇", "部", "卷", "回", "集")\n            .firstOrNull { value.startsWith(it, index) } ?: return false\n        val tailIndex = index + unit.length\n        if (tailIndex >= value.length) return false\n        val next = value[tailIndex]\n        return !next.isWhitespace() && next !in "、.．:：—-"\n    }\n\n    fun extractFallbackChapterTitle(line: String): String? {\n'''

if needle not in s:
    if 'private fun hasGluedPrefixedStructuralText(value: String): Boolean' not in s:
        raise SystemExit('fallback insertion point not found')
else:
    s = s.replace(needle, replacement, 1)

needle2 = '''        if (numeralLeadingOrdinaryText.matches(normalized)) return null\n        if (normalized.any(::isTitlePunctuation)) return null\n'''
replacement2 = '''        if (numeralLeadingOrdinaryText.matches(normalized)) return null\n        if (hasGluedPrefixedStructuralText(normalized)) return null\n        if (normalized.any(::isTitlePunctuation)) return null\n'''
if needle2 not in s:
    if 'if (hasGluedPrefixedStructuralText(normalized)) return null' not in s:
        raise SystemExit('fallback guard insertion point not found')
else:
    s = s.replace(needle2, replacement2, 1)

p.write_text(s, encoding='utf-8')
print('v756 fallback structural-boundary guard applied')
