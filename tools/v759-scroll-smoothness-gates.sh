#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

r = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
a = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
b = Path('app/build.gradle.kts').read_text(encoding='utf-8')

assert '2098000759' in b
assert '?: "759"' in b

listener = re.search(r'class VerticalScrollListener\(.*?\n\}', a, re.S)
assert listener, 'VerticalScrollListener missing'
body = listener.group(0)
assert 'lastReportedIndex = RecyclerView.NO_POSITION' in body
assert 'index != lastReportedIndex' in body
assert 'lastReportedIndex = index' in body
assert body.count('verticalShowBoundaryHaze()') == 1

visible = re.search(r'internal fun verticalOnPageVisible\(index: Int\) \{(.*?)\n    \}', r, re.S)
assert visible, 'verticalOnPageVisible missing'
visible_body = visible.group(1)
assert 'currentPageIndex == index' in visible_body
assert 'updateProgressUi()' in visible_body
assert 'scheduleProgressCheckpoint' in visible_body

# Keep the V758 low-cost text-layout path and V757 bounded cache.
assert 'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE' in a
assert 'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE' in a
assert 'LruCache<Int, CharSequence>(32)' in a
assert 'LruCache<Int, CharSequence>(64)' not in a

# Search-highlight clearing must remain target-only and layout-free.
clear = re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView, position: Int\) \{(.*?)\n    \}', a, re.S)
assert clear
for forbidden in ('evictAll()', 'notifyItemRangeChanged', 'notifyDataSetChanged', 'recyclerView.post'):
    assert forbidden not in clear.group(1), forbidden
assert 'rendered.remove(position)' in clear.group(1)

# V759 may add a rare reset guard, but it must not add per-pixel disk IO in onScrolled.
assert 'verticalShouldSuppressReportedIndex' in body
assert 'CrashLogStore' not in body

print('v759 scroll smoothness regression gates: PASS')
PY

echo 'v759 smoothness gates passed'
