#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
assert '2098000760' in build and '?: "760"' in build
listener = re.search(r'class VerticalScrollListener\(.*?\n\}', adapter, re.S)
assert listener, 'VerticalScrollListener missing'
body = listener.group(0)
on_scrolled = re.search(r'override fun onScrolled\(.*?\n    \}', body, re.S)
assert on_scrolled, 'onScrolled missing'
hot = on_scrolled.group(0)
for forbidden in ('CrashLogStore', 'recordReaderPosition', 'recordEvent', 'saveProgress('):
    assert forbidden not in hot, f'hot-path forbidden call: {forbidden}'
assert 'verticalShouldSuppressReportedIndex' in hot
assert 'verticalOnPageVisible(index)' in hot
visible = re.search(r'internal fun verticalOnPageVisible\(index: Int\) \{(.*?)\n    \}', reader, re.S)
assert visible, 'verticalOnPageVisible missing'
for forbidden in ('CrashLogStore', 'recordReaderPosition', 'recordEvent'):
    assert forbidden not in visible.group(1), f'visible hot-path forbidden call: {forbidden}'
assert 'currentPageIndex == index' in visible.group(1)
assert 'updateProgressUi()' in visible.group(1)
assert 'scheduleProgressCheckpoint' in visible.group(1)
suppress = re.search(r'internal fun verticalShouldSuppressReportedIndex\(.*?\n    \}', reader, re.S)
assert suppress
assert 'CrashLogStore' not in suppress.group(0)
assert 'pendingVerticalDiagnosticEvent' in suppress.group(0)
idle = re.search(r'internal fun verticalOnScrollIdle\(\) \{(.*?)\n    \}', reader, re.S)
assert idle
assert 'persistVerticalDiagnosticState("vertical_idle", force = false)' in idle.group(1)
state_change = re.search(r'override fun onScrollStateChanged\(.*?\n    \}', body, re.S)
assert state_change
assert 'RecyclerView.SCROLL_STATE_IDLE' in state_change.group(0)
assert 'activity.verticalOnScrollIdle()' in state_change.group(0)
persist = re.search(r'private fun persistVerticalDiagnosticState\(.*?\n    \}', reader, re.S)
assert persist
assert 'CrashLogStore.recordReaderPosition' in persist.group(0)
assert 'CrashLogStore.recordEvent' in persist.group(0)
assert 'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE' in adapter
assert 'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE' in adapter
assert 'LruCache<Int, CharSequence>(32)' in adapter
assert 'setItemViewCacheSize(12)' in reader
assert 'initialPrefetchItemCount = 8' in reader
print('v760 scroll hot-path isolation gates: PASS')
PY
