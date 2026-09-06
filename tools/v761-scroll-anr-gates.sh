#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re

reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
build = Path('app/build.gradle.kts').read_text(encoding='utf-8')

assert '2098000761' in build and '?: "761"' in build

listener = re.search(r'class VerticalScrollListener\(.*?\n\}', adapter, re.S)
assert listener, 'VerticalScrollListener missing'
body = listener.group(0)
on_scrolled = re.search(r'override fun onScrolled\(.*?\n    \}', body, re.S)
assert on_scrolled, 'onScrolled missing'
hot = on_scrolled.group(0)
for forbidden in ('CrashLogStore', 'recordReaderPosition', 'recordEvent', 'saveProgress('):
    assert forbidden not in hot, f'hot-path forbidden call: {forbidden}'
assert 'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)' in hot

suppress = re.search(r'internal fun verticalShouldSuppressReportedIndex\(.*?\n    \}', reader, re.S)
assert suppress, 'suppression guard missing'
s = suppress.group(0)
assert 'val current = currentPageIndex' in s
assert 'val baseline = when' in s
assert 'previousIndex >= 0 && kotlin.math.abs(previousIndex - current) <= 2' in s
assert 'zeroTeleport = index == 0 && baseline >= 4 && dy >= 0' in s
assert 'previousIndex < 0 ||' not in s, 'NO_POSITION must not disable zero-teleport guard'
assert 'CrashLogStore' not in s

idle = re.search(r'internal fun verticalOnScrollIdle\(\) \{(.*?)\n    \}', reader, re.S)
assert idle, 'verticalOnScrollIdle missing'
i = idle.group(1)
assert 'visibleIndex == 0 && currentPageIndex >= 4' in i
assert 'scrollToPositionWithOffset(restoreIndex, 0)' in i
assert 'vertical_idle_recovered_zero' in i
assert 'persistVerticalDiagnosticState("vertical_idle", force = false)' in i

# Keep the validated V758/V760 light text path and bounded caches.
assert 'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE' in adapter
assert 'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE' in adapter
assert 'LruCache<Int, CharSequence>(32)' in adapter
assert 'setItemViewCacheSize(12)' in reader
assert 'initialPrefetchItemCount = 8' in reader

print('v761 scroll/zero-reset ANR gates: PASS')
PY
