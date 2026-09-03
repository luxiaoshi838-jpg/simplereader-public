from pathlib import Path
import re

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/simplereader/app/parser/TxtParser.kt'
s = p.read_text(encoding='utf-8')

# Rule113 no longer needs the old numeralLeadingOrdinaryText regex. The fallback path already
# rejects every remaining Arabic/Chinese numeral-leading phrase via startsWithNumeralToken(),
# after DirectTxtCatalogV100 has had the first chance to accept a real chapter structure.
# Removing the redundant regex also avoids a JVM PatternSyntaxException from its hyphen class.
s, removed = re.subn(
    r'\n    private val numeralLeadingOrdinaryText = Regex\(\n        ".*?"\n    \)\n',
    '\n',
    s,
    count=1,
)
s = s.replace('        if (numeralLeadingOrdinaryText.matches(normalized)) return null\n', '')

# Keep the glued-prefixed structural guard: 第3节课 / 第12章鱼 are prose-like glued phrases,
# while 第3节 课 / 第3节：课 have an independent structural-unit boundary and are recognized
# earlier by DirectTxtCatalogV100.
if 'private fun hasGluedPrefixedStructuralText(value: String): Boolean' not in s:
    needle = '''    fun extractFallbackChapterTitle(line: String): String? {\n'''
    replacement = '''    private fun hasGluedPrefixedStructuralText(value: String): Boolean {\n        var index = 0\n        if (value.getOrNull(index) != '第') return false\n        index++\n        while (value.getOrNull(index)?.isWhitespace() == true) index++\n\n        val numeralChars = "0123456789０１２３４５６７８９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"\n        val numeralStart = index\n        while (value.getOrNull(index)?.let { it in numeralChars } == true) index++\n        if (index == numeralStart) return false\n        while (value.getOrNull(index)?.isWhitespace() == true) index++\n\n        val unit = listOf("单元", "章", "节", "篇", "部", "卷", "回", "集")\n            .firstOrNull { value.startsWith(it, index) } ?: return false\n        val tailIndex = index + unit.length\n        if (tailIndex >= value.length) return false\n        val next = value[tailIndex]\n        return !next.isWhitespace() && next !in "、.．:：—-"\n    }\n\n    fun extractFallbackChapterTitle(line: String): String? {\n'''
    if needle not in s:
        raise SystemExit('fallback insertion point not found')
    s = s.replace(needle, replacement, 1)

if 'private fun startsWithNumeralToken(value: String): Boolean' not in s:
    needle = '''    fun extractFallbackChapterTitle(line: String): String? {\n'''
    replacement = '''    private fun startsWithNumeralToken(value: String): Boolean {\n        val numeralChars = "0123456789０１２３４５６７８９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"\n        var index = 0\n        while (value.getOrNull(index)?.isWhitespace() == true) index++\n        return value.getOrNull(index)?.let { it in numeralChars } == true\n    }\n\n    fun extractFallbackChapterTitle(line: String): String? {\n'''
    if needle not in s:
        raise SystemExit('fallback function point not found')
    s = s.replace(needle, replacement, 1)

if 'if (startsWithNumeralToken(normalized)) return null' not in s:
    needle = '        if (normalized.all(Char::isDigit)) return null\n'
    replacement = needle + '        if (startsWithNumeralToken(normalized)) return null\n'
    if needle not in s:
        raise SystemExit('fallback numeral guard insertion point not found')
    s = s.replace(needle, replacement, 1)

if 'if (hasGluedPrefixedStructuralText(normalized)) return null' not in s:
    needle = '        if (startsWithNumeralToken(normalized)) return null\n'
    replacement = needle + '        if (hasGluedPrefixedStructuralText(normalized)) return null\n'
    if needle not in s:
        raise SystemExit('fallback structural guard insertion point not found')
    s = s.replace(needle, replacement, 1)

if 'numeralLeadingOrdinaryText' in s:
    raise SystemExit('redundant invalid numeral regex still present')

p.write_text(s, encoding='utf-8')
print('v756 fallback uses code-based numeral/structural boundary guards only')
