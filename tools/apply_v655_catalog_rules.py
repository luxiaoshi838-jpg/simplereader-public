#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}: found {count}\n--- OLD ---\n{old[:500]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


txt = ROOT / "app/src/main/java/com/simplereader/app/parser/TxtParser.kt"
replace_once(txt, "const val CATALOG_RULE_VERSION = 3", "const val CATALOG_RULE_VERSION = 20")

old_structured = '''    fun extractStructuredChapterTitle(line: String): String? {\n        val normalized = line.trim()\n        if (normalized.length !in 2..120) return null\n        if (normalized.contains("http", ignoreCase = true)) return null\n        if (normalized.count { it in "，,。；;！？!?" } > 2) return null\n        return normalized.take(100).takeIf { candidate ->\n            structuredChapterPatterns.any { it.matches(candidate) }\n        }\n    }'''
new_structured = '''    fun extractStructuredChapterTitle(line: String): String? {\n        val normalized = line.trim()\n        if (normalized.length !in 1..120) return null\n        return normalized.take(100).takeIf {\n            ChapterPriorityClassifier.isUnifiedStructured(normalized)\n        }\n    }'''
replace_once(txt, old_structured, new_structured)

old_fallback = '''    fun extractFallbackChapterTitle(line: String): String? {\n        val normalized = line.trim()\n        if (normalized.length !in 2..40) return null\n        if (normalized.contains("http", ignoreCase = true)) return null\n        if (normalized.all(Char::isDigit)) return null\n        if (normalized.any(::isTitlePunctuation)) return null\n        if (normalized.count(Char::isWhitespace) > 4) return null\n        if (normalized.none { it.isLetterOrDigit() }) return null\n        if (fallbackSentenceMarkers.any { marker -> normalized.contains(marker) }) return null\n        return normalized.take(80)\n    }'''
new_fallback = '''    fun extractFallbackChapterTitle(line: String): String? {\n        val normalized = line.trim()\n        if (normalized.length !in 2..40) return null\n        if (ChapterPriorityClassifier.priority(normalized) != ChapterPriorityClassifier.PURE_TEXT_FALLBACK) return null\n        if (normalized.all(Char::isDigit)) return null\n        // v654 allowed Chinese/ASCII comma in the pure-text fallback; keep the\n        // remaining punctuation guard and the v655 sentence-terminator guard.\n        if (normalized.any { isTitlePunctuation(it) && it !in "，," }) return null\n        if (normalized.count(Char::isWhitespace) > 4) return null\n        if (normalized.none { it.isLetterOrDigit() }) return null\n        if (fallbackSentenceMarkers.any { marker -> normalized.contains(marker) }) return null\n        return normalized.take(80)\n    }'''
replace_once(txt, old_fallback, new_fallback)

# Include 话 in the visible current-chapter title extractor.
reader = ROOT / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
replace_once(reader, "(?:单元|章|节|篇|部|卷|回|集)", "(?:单元|章|节|篇|部|卷|回|集|话)")

# Rule 20 must make recognition metadata from v654 stale as well, not only page manifests.
cache = ROOT / "app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt"
old_current = '''        root.optString("fileName").equals(fileName, ignoreCase = true) &&\n            root.optLong("fileSize", Long.MIN_VALUE) == (fileSize ?: Long.MIN_VALUE) &&\n            root.optBoolean("completed", false)'''
new_current = '''        root.optString("fileName").equals(fileName, ignoreCase = true) &&\n            root.optLong("fileSize", Long.MIN_VALUE) == (fileSize ?: Long.MIN_VALUE) &&\n            root.optInt("catalogRuleVersion", 0) == TxtParser.CATALOG_RULE_VERSION &&\n            root.optBoolean("completed", false)'''
# The same prefix occurs in isRecognitionCurrent and hasCurrentCatalog; patch both.
text = cache.read_text(encoding="utf-8")
count = text.count(old_current)
if count != 2:
    raise SystemExit(f"expected two recognition predicates, found {count}")
cache.write_text(text.replace(old_current, new_current), encoding="utf-8")

# Build an in-place-upgradable v655.
gradle = ROOT / "app/build.gradle.kts"
replace_once(gradle, '(System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000615")', '(System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000655")')
replace_once(gradle, '?: 2098000615', '?: 2098000655')
replace_once(gradle, 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "615"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "655"')

print("v655 catalog patch applied")
