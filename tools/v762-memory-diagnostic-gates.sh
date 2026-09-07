#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re

crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
app = Path('app/src/main/java/com/simplereader/app/App.kt').read_text(encoding='utf-8')
build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')

assert '2098000762' in build and '?: "762"' in build

# V761 ANR/zero-reset protections must remain unchanged.
for token in [
    'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)',
    'val current = currentPageIndex',
    'zeroTeleport = index == 0 && baseline >= 4 && dy >= 0',
    'visibleIndex == 0 && currentPageIndex >= 4',
    'vertical_idle_recovered_zero',
    'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE',
    'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE',
    'LruCache<Int, CharSequence>(32)',
]:
    assert token in (reader + adapter), token

# Memory attribution must be split into useful PSS buckets and be persisted for next-process exit reports.
for token in [
    'process_memory_diagnostic.txt',
    'Debug.MemoryInfo()',
    'Debug.getMemoryInfo(debug)',
    'summary.java-heap',
    'summary.native-heap',
    'summary.graphics',
    'summary.code',
    'summary.stack',
    'summary.private-other',
    'summary.system',
    'summary.total-swap',
    'Debug.getNativeHeapAllocatedSize()',
    '进程退出前内存诊断流水：',
    'schedulePostReaderMemorySnapshots',
    '5L to TimeUnit.SECONDS',
    '30L to TimeUnit.SECONDS',
    '2L to TimeUnit.MINUTES',
    '10L to TimeUnit.MINUTES',
    '30L to TimeUnit.MINUTES',
]:
    assert token in crash, token

for token in ['override fun onTrimMemory(level: Int)', 'override fun onLowMemory()', 'process_start']:
    assert token in app, token

for token in ['paginate_success', 'reader_onPause', 'reader_onDestroy', 'memoryDiagnosticDetails()', 'textChars=', 'rvChildren=', 'rvItems=']:
    assert token in reader, token

# Diagnostics must never be reintroduced into RecyclerView pixel-scroll hot paths.
vertical_visible = re.search(r'internal fun verticalOnPageVisible\(index: Int\) \{(.*?)\n    \}', reader, re.S)
assert vertical_visible, 'verticalOnPageVisible missing'
for forbidden in ('recordMemorySnapshot', 'Debug.getMemoryInfo', 'recordEvent(', 'recordReaderPosition('):
    assert forbidden not in vertical_visible.group(1), f'vertical hot path contains {forbidden}'

listener = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
on_scrolled = re.search(r'override fun onScrolled\(.*?\n    \}', listener, re.S)
assert on_scrolled, 'onScrolled missing'
for forbidden in ('recordMemorySnapshot', 'Debug.getMemoryInfo', 'CrashLogStore'):
    assert forbidden not in on_scrolled.group(0), f'onScrolled contains {forbidden}'

print('v762 memory attribution + no-hotpath diagnostics gates: PASS')
PY
