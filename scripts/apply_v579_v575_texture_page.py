from pathlib import Path

reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = reader.read_text(encoding="utf-8-sig")

old_theme = """    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        contentView.setBackgroundColor(palette.backgroundColor)
        contentView.setTextColor(palette.textColor)
        window.decorView.setBackgroundColor(palette.backgroundColor)
        updateThemeControls()
    }
"""
new_theme = """    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        contentView.background = PaperPageDrawable(palette.backgroundColor)
        contentView.setTextColor(palette.textColor)
        readerScrollView.setBackgroundColor(palette.backgroundColor)
        window.decorView.setBackgroundColor(palette.backgroundColor)
        updateThemeControls()
    }
"""

old_rendered_pages = """    private fun renderedPageCountLabel(): String? {
        if (pageTurnMode != TURN_MODE_VERTICAL) return null
        val viewportHeight = readerScrollView.height.coerceAtLeast(0)
        val contentHeight = contentView.height.coerceAtLeast(0)
        if (viewportHeight <= 0 || contentHeight <= 0) return null
        val pageHeight = viewportHeight.coerceAtLeast(1)
        val totalPages = ((contentHeight + pageHeight - 1) / pageHeight).coerceAtLeast(1)
        val currentPage = (readerScrollView.scrollY / pageHeight + 1).coerceIn(1, totalPages)
        return "$currentPage/$totalPages"
    }
"""
new_rendered_pages = """    private fun renderedPageCountLabel(): String? {
        // 右下角与目录只使用 pageSize=2000 的同一分页结果，不按屏幕高度另算。
        return pageCountLabel()
    }
"""

for old, new, name in [
    (old_theme, new_theme, "阅读页纹理"),
    (old_rendered_pages, new_rendered_pages, "目录页码统一"),
]:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"拒绝修改：{name}目标块应匹配 1 次，实际 {count} 次")
    text = text.replace(old, new, 1)

required = [
    "contentView.background = PaperPageDrawable(palette.backgroundColor)",
    "private fun renderedPageCountLabel(): String?",
    "return pageCountLabel()",
    "val unitsPerPage = pageSize.toLong().coerceAtLeast(1L)",
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"修改后缺少门禁标记：{marker}")

for forbidden in [
    "val pageHeight = viewportHeight.coerceAtLeast(1)",
    "readerScrollView.scrollY / pageHeight",
]:
    if forbidden in text:
        raise SystemExit(f"仍存在第二套页码算法：{forbidden}")

reader.write_text(text, encoding="utf-8")
print("v575 patch applied: cover class unchanged elsewhere; reader texture and page-number source only")
