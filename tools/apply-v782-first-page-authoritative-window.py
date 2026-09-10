#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt"
s = path.read_text(encoding="utf-8")

new = """        val start = chapter.startOffset
        val windowEnd = chooseWindowEnd(text, start, chapter.endOffset)
        val windowText = text.substring(start, windowEnd)
"""
if new in s:
    print("v782 first-page authoritative window already applied")
    raise SystemExit(0)

old = """        val start = chapter.startOffset
        var windowEnd = (start + FIRST_PAGE_PREVIEW_CHARS).coerceAtMost(chapter.endOffset)
        if (windowEnd > start && windowEnd < text.length && Character.isHighSurrogate(text[windowEnd - 1])) {
            windowEnd -= 1
        }
        windowEnd = windowEnd.coerceAtLeast((start + 1).coerceAtMost(chapter.endOffset))
        val windowText = text.substring(start, windowEnd)
"""
if old not in s:
    raise SystemExit("v782 authoritative first-page window anchor missing")
s = s.replace(old, new, 1)
path.write_text(s, encoding="utf-8")
print("v782 applied: immediate first page uses the exact same bounded window as full pagination")
