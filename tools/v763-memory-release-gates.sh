#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re

build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
main = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
backgrounds = Path('app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt').read_text(encoding='utf-8')
bookcovers = Path('app/src/main/java/com/simplereader/app/ui/BookCoverAssets.kt').read_text(encoding='utf-8')
image_repo = Path('app/src/main/java/com/simplereader/app/reader/ReaderImageRepository.kt').read_text(encoding='utf-8')
paged = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt').read_text(encoding='utf-8')
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
app = Path('app/src/main/java/com/simplereader/app/App.kt').read_text(encoding='utf-8')
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')

assert '2098000763' in build and '?: "763"' in build

# Shelf memory must be released before ReaderActivity is created and must not rebuild while hidden.
for token in [
    'private var shelfUiVisible = false',
    'shelfUiVisible = false\n        releaseShelfUiMemory("before_open_reader")',
    'override fun onStop()',
    'releaseShelfUiMemory("main_onStop")',
    'if (!shelfUiVisible) return',
    'shelfGrid.removeAllViews()',
    'coverBitmapCache.evictAll()',
    'BookCoverAssets.clearMemoryCache()',
    'decodeShelfCoverFile',
    'decodeShelfCoverBytes',
    'while (width / sample > 384 || height / sample > 512)',
    'bitmap != null && shelfUiVisible && !isFinishing && !isDestroyed',
]:
    assert token in main, token

assert 'fun clearMemoryCache() = CoverBitmapCache.clear()' in bookcovers
assert 'fun clear() = synchronized(bitmaps) { bitmaps.clear() }' in bookcovers

# Reader background cache: one full bitmap, bounded sampled previews, explicit pressure cleanup.
for token in [
    'private var fullResId: Int = 0',
    'private var fullBitmap: Bitmap? = null',
    'LruCache<Int, Bitmap>(PREVIEW_CACHE_KB)',
    'while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 256)',
    'fun clearMemoryCaches() = ReaderBackgroundBitmapCache.clear()',
    'fun trimMemory(level: Int) = ReaderBackgroundBitmapCache.trim(level)',
    'ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN',
    'FullPageReaderBackgroundDrawable(context, option, preview = true)',
]:
    assert token in backgrounds, token
assert 'private val cache = mutableMapOf<Int, Bitmap>()' not in backgrounds, 'old unlimited background bitmap map returned'
assert 'ReaderBackgrounds.trimMemory(level)' in app
assert 'ReaderBackgrounds.clearMemoryCaches()' in app

# No duplicate full-screen reader background layer.
assert 'findViewById<View>(android.R.id.content).background = null' in reader
assert 'findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()' not in reader

# Reader destruction must drop strong references immediately.
for token in [
    'private fun releaseReaderMemory()',
    'verticalAdapter?.release()',
    'clearOnScrollListeners()',
    'adapter = null',
    'recycledViewPool.clear()',
    'pagedReaderView.release()',
    'continuousTextView.text = ""',
    'imageRepository?.clear()',
    'readerBook = null',
    'document = null',
    'ReaderBackgrounds.clearMemoryCaches()',
    'reader_onDestroy_after_release',
]:
    assert token in reader, token
assert 'fun clear() = synchronized(cache) { cache.clear() }' in image_repo
assert 'fun release() {' in paged and 'previousPage = null' in paged and 'page.background = null' in paged
assert 'fun release() {' in adapter and 'rendered.evictAll()' in adapter

# V762 attribution must remain so the fix can be verified on the same device.
for token in [
    'process_memory_diagnostic.txt',
    'summary.java-heap',
    'summary.native-heap',
    'summary.graphics',
    'summary.private-other',
    '进程退出前内存诊断流水：',
]:
    assert token in crash, token

# V761/V760 scroll and zero-reset safeguards must be unchanged and memory cleanup must not enter the hot path.
for token in [
    'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)',
    'zeroTeleport = index == 0 && baseline >= 4 && dy >= 0',
    'visibleIndex == 0 && currentPageIndex >= 4',
    'vertical_idle_recovered_zero',
    'setItemViewCacheSize(12)',
    'initialPrefetchItemCount = 8',
]:
    assert token in (reader + adapter), token

visible = re.search(r'internal fun verticalOnPageVisible\(index: Int\) \{(.*?)\n    \}', reader, re.S)
assert visible, 'verticalOnPageVisible missing'
for forbidden in ('releaseReaderMemory', 'clearMemoryCaches', 'recordMemorySnapshot', 'Debug.getMemoryInfo'):
    assert forbidden not in visible.group(1), f'vertical hot path contains {forbidden}'

on_scrolled = re.search(r'override fun onScrolled\(.*?\n    \}', adapter, re.S)
assert on_scrolled, 'onScrolled missing'
for forbidden in ('releaseReaderMemory', 'clearMemoryCaches', 'recordMemorySnapshot', 'CrashLogStore'):
    assert forbidden not in on_scrolled.group(0), f'onScrolled contains {forbidden}'

assert 'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE' in adapter
assert 'hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE' in adapter
assert 'LruCache<Int, CharSequence>(32)' in adapter

print('v763 memory release + scroll isolation gates: PASS')
PY
