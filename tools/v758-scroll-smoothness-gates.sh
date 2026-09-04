#!/usr/bin/env bash
set -euo pipefail

reader="app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
adapter="app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt"
build="app/build.gradle.kts"
workflow=".github/workflows/android-release-v2.yml"

python3 - <<'PY'
from pathlib import Path
import re

r = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
a = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
b = Path('app/build.gradle.kts').read_text(encoding='utf-8')
w = Path('.github/workflows/android-release-v2.yml').read_text(encoding='utf-8')

# V758 must remain an in-place upgrade from 757.
assert '2098000758' in b
assert '?: "758"' in b
assert 'source-v758' in w
assert "versionCode='2098000758'" in w
assert "versionName='758'" in w

# Pixel-level scroll callbacks must be de-duplicated by page index.
listener = re.search(r'class VerticalScrollListener\(.*?\n\}', a, re.S)
assert listener, 'VerticalScrollListener missing'
body = listener.group(0)
assert 'lastReportedIndex = RecyclerView.NO_POSITION' in body
assert 'index != lastReportedIndex' in body
assert 'lastReportedIndex = index' in body
assert body.count('verticalShowBoundaryHaze()') == 1, 'boundary haze must be page-boundary only'

# ReaderActivity must skip same-page updates, so progress/title UI is not touched every frame.
visible = re.search(r'internal fun verticalOnPageVisible\(index: Int\) \{(.*?)\n    \}', r, re.S)
assert visible, 'verticalOnPageVisible missing'
visible_body = visible.group(1)
assert 'currentPageIndex == index' in visible_body
assert 'updateProgressUi()' in visible_body
assert 'scheduleProgressCheckpoint' in visible_body

# Title parsing must not allocate Regex objects per scroll/page callback.
title = re.search(r'private fun updateCurrentChapterTitle\(\) \{(.*?)\n    \}', r, re.S)
assert title, 'updateCurrentChapterTitle missing'
title_body = title.group(1)
assert 'EXPLICIT_CHAPTER_REGEX.find' in title_body
assert 'LEADING_NUMERIC_TITLE_REGEX.replace' in title_body
assert 'lastDisplayedChapterTitle' in title_body
assert 'Regex(' not in title_body
assert 'private val EXPLICIT_CHAPTER_REGEX' in r
assert 'private val LEADING_NUMERIC_TITLE_REGEX' in r

# Vertical row layout should use the cheap, deterministic Android text-break path.
assert 'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE' in a
assert 'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE' in a

# Preserve V757's validated bounded-cache contract rather than increasing memory pressure.
assert 'LruCache<Int, CharSequence>(32)' in a
assert 'LruCache<Int, CharSequence>(64)' not in a

# V757's critical anti-freeze behavior must remain intact.
clear = re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView, position: Int\) \{(.*?)\n    \}', a, re.S)
assert clear, 'target-only highlight clear missing'
for forbidden in ('evictAll()', 'notifyItemRangeChanged', 'notifyDataSetChanged', 'recyclerView.post'):
    assert forbidden not in clear.group(1), f'V757 regression in highlight clear: {forbidden}'
assert 'rendered.remove(position)' in clear.group(1)

print('v758 scroll smoothness gates: PASS')
PY

grep -Fq 'versionCode = generatedVersionCode' "$build"
grep -Fq 'applicationId = "com.simplereader.app"' "$build"
grep -Fq 'bash tools/v757-52-gates.sh' "$workflow"

echo 'v758 all static gates passed'
