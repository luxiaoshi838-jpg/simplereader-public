#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re
import subprocess

build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
manifest = Path('app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
layout = Path('app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
main = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
search_sheet = Path('app/src/main/java/com/simplereader/app/ui/ReaderSearchSheet.kt').read_text(encoding='utf-8')
runtime = Path('app/src/main/java/com/simplereader/app/runtime/ReaderRuntimeState.kt').read_text(encoding='utf-8')
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')
worker = Path('app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt').read_text(encoding='utf-8')
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')

assert '2098000764' in build and '?: "764"' in build

# Duplicate ReaderActivity launches must be blocked at Android, caller, and persistence-owner layers.
for token in [
    'android:launchMode="singleTop"',
]:
    assert token in manifest, token
for token in [
    'private var readerLaunchInFlight = false',
    'reader_launch_suppressed',
    'Intent.FLAG_ACTIVITY_SINGLE_TOP',
    'readerLaunchInFlight = false\n        shelfUiVisible = true',
]:
    assert token in main, token
for token in [
    'readerGeneration = ReaderRuntimeState.claim()',
    'private fun ownsReaderSession(): Boolean',
    'stale_reader_pause_write_suppressed',
    'stale_reader_finish_suppressed',
    'if (!ReaderRuntimeState.isOwner(generation)) return@launch',
    'override fun onNewIntent(intent: Intent)',
]:
    assert token in reader, token
for token in ['fun claim(): Long', 'fun isOwner(generation: Long)', 'fun markResumed', 'fun markPaused']:
    assert token in runtime, token

# 5715-book shelf must be virtualized; old ScrollView+GridLayout full-tree rendering may not return.
assert '<androidx.recyclerview.widget.RecyclerView' in layout
assert '<com.simplereader.app.ui.FastScrollView' not in layout
for token in [
    'private lateinit var shelfGrid: RecyclerView',
    'GridLayoutManager(this, 3)',
    'private inner class ShelfAdapter',
    'shelfAdapter.submit(renderItems)',
    'shelfGrid.recycledViewPool.clear()',
    'shelfCoverJobs.toList().forEach(Job::cancel)',
    'shelfCoverSemaphore.withPermit',
]:
    assert token in main, token
assert 'shelfGrid.removeAllViews()' not in main, 'old non-virtualized shelf clearing path returned'

# Full-shelf background pagination must yield while the user is actively reading/searching.
assert 'awaitForegroundReaderIdle()' in worker
assert 'while (ReaderRuntimeState.isReaderForeground())' in worker

# Delayed memory samples from old reader sessions must not pile up and run hours later.
for token in [
    'memoryScheduleGeneration.incrementAndGet()',
    'schedulePostReaderMemorySnapshots(appContext, scheduleGeneration)',
    'memoryScheduleGeneration.get() != scheduleGeneration',
    'processSession=',
    'pid=',
    'memory version=',
]:
    assert token in crash, token

# Reading-page search is a hard compatibility lock for V764.
# ReaderSearchSheet itself must be byte-for-byte unchanged from source-v763.
base_sheet = subprocess.check_output([
    'git', 'show', 'origin/source-v763:app/src/main/java/com/simplereader/app/ui/ReaderSearchSheet.kt'
], text=True)
assert search_sheet == base_sheet, 'ReaderSearchSheet changed from V763'

# The ReaderActivity search implementation must also remain byte-for-byte unchanged.
def block(text: str, start: str, end: str) -> str:
    a = text.index(start)
    b = text.index(end, a)
    return text[a:b]

base_reader = subprocess.check_output([
    'git', 'show', 'origin/source-v763:app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
], text=True)
cur_search = block(reader, '    private fun showContentSearch() {', '    private fun confirmDeleteBookmark(')
base_search = block(base_reader, '    private fun showContentSearch() {', '    private fun confirmDeleteBookmark(')
assert cur_search == base_search, 'reading-page search/find/highlight block changed from V763'
for token in [
    'MENU_SEARCH -> { showContentSearch(); true }',
    'ReaderSearchSheet.show(',
    'findAllHits(paged, keyword)',
    'onHit = { hit -> jumpToPage(hit.globalPageIndex, false, hit) }',
    'private fun applyContinuousHighlight()',
    'BackgroundColorSpan(Color.rgb(255, 226, 105))',
]:
    assert token in reader, token

# Existing scroll performance / zero-reset protections stay in place.
for token in [
    'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)',
    'zeroTeleport = index == 0 && baseline >= 4 && dy >= 0',
    'visibleIndex == 0 && currentPageIndex >= 4',
    'vertical_idle_recovered_zero',
    'LruCache<Int, CharSequence>(32)',
    'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE',
    'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE',
]:
    assert token in (reader + adapter), token

visible = re.search(r'internal fun verticalOnPageVisible\(index: Int\) \{(.*?)\n    \}', reader, re.S)
assert visible, 'verticalOnPageVisible missing'
for forbidden in ('Debug.getMemoryInfo', 'releaseReaderMemory', 'ReaderSearchSheet.show'):
    assert forbidden not in visible.group(1), f'vertical hot path contains {forbidden}'

print('v764 reader ownership + search lock + shelf virtualization gates: PASS')
PY
