from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
reader_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
adapter_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt'
build_path = ROOT / 'app/build.gradle.kts'

r = reader_path.read_text(encoding='utf-8')
a = adapter_path.read_text(encoding='utf-8')
b = build_path.read_text(encoding='utf-8')

# Version 757.
b = b.replace('"2098000756"', '"2098000757"')
b = b.replace('?: 2098000756', '?: 2098000757')
b = b.replace('?: "756"', '?: "757"')
if '2098000757' not in b or '"757"' not in b:
    raise SystemExit('version patch failed')

# Reader state: checkpoint runnable is lightweight and never mutates current reader position.
needle = '    private var verticalStateUnlockRunnable: Runnable? = null\n'
if 'private var progressCheckpointRunnable: Runnable? = null' not in r:
    if needle not in r:
        raise SystemExit('reader state insertion point missing')
    r = r.replace(needle, needle + '    private var progressCheckpointRunnable: Runnable? = null\n', 1)

# Lifecycle: do not leave delayed checkpoint callbacks attached to a dying Activity.
r = r.replace(
'''        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        cancelVerticalStateUnlockGuard()\n''',
'''        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable = null\n        cancelVerticalStateUnlockGuard()\n''', 1)

# Vertical page commit is the only routine scroll path allowed to advance the stable source anchor.
old_visible = '''    internal fun verticalOnPageVisible(index: Int) {\n        val pages = readerBook?.pages.orEmpty()\n        if (index !in pages.indices) return\n        currentPageIndex = index\n        lastStableSourceOffset = pages[index].startOffset\n        continuousWindowStartOffset = pages[index].startOffset\n        continuousWindowEndOffset = pages[index].endOffset\n        updateProgressUi()\n    }\n    internal fun verticalOnUserDrag() { clearSearchHighlight() }\n'''
new_visible = '''    internal fun verticalOnPageVisible(index: Int) {\n        val pages = readerBook?.pages.orEmpty()\n        if (index !in pages.indices) return\n        val changed = currentPageIndex != index\n        currentPageIndex = index\n        lastStableSourceOffset = pages[index].startOffset\n        continuousWindowStartOffset = pages[index].startOffset\n        continuousWindowEndOffset = pages[index].endOffset\n        updateProgressUi()\n        if (changed) scheduleProgressCheckpoint(pages[index].startOffset)\n    }\n    internal fun verticalOnUserDrag(): Int? {\n        val hitPage = activeSearchHit?.globalPageIndex\n        clearSearchHighlight()\n        return hitPage\n    }\n'''
if old_visible not in r:
    raise SystemExit('vertical page-visible block not found')
r = r.replace(old_visible, new_visible, 1)

# Progress persistence: build an immutable snapshot from an explicit source offset. Checkpoints use
# lifecycle IO; final onPause save uses application scope so Activity destruction cannot cancel it.
pattern = re.compile(r'''    private fun stableProgressSourceOffset\(\): Int\? \{.*?\n    \}\n\n    private fun saveProgress\(\) \{.*?\n    \}\n''', re.S)
m = pattern.search(r)
if not m:
    raise SystemExit('stable/save progress block not found')
new_progress = '''    private fun stableProgressSourceOffset(): Int? {\n        val paged = readerBook ?: return null\n        val current = paged.pages.getOrNull(currentPageIndex)?.startOffset\n        val stable = lastStableSourceOffset?.coerceIn(0, paged.text.length)\n        if (pageTurnMode != TURN_MODE_VERTICAL) return current ?: stable\n\n        val suspended = suspendedAnchorOffset?.coerceIn(0, paged.text.length)\n        val visibleIndex = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n        val visible = paged.pages.getOrNull(visibleIndex)?.startOffset\n        if (verticalWindowSuspended || verticalProgrammaticScroll) {\n            return stable ?: suspended ?: visible ?: current\n        }\n\n        // Stable source is authoritative if RecyclerView is transiently reporting an older/zero row\n        // during layout. A genuine user move commits through verticalOnPageVisible first.\n        return when {\n            visible == 0 && stable != null && stable > 0 -> stable\n            visible != null -> visible\n            stable != null -> stable\n            suspended != null -> suspended\n            else -> current\n        }\n    }\n\n    private fun progressSnapshotForOffset(sourceOffset: Int): ReadProgress? {\n        val paged = readerBook ?: return null\n        if (paged.pages.isEmpty()) return null\n        val page = paged.pageForOffset(sourceOffset.coerceIn(0, paged.text.length))\n        return ReadProgress(\n            bookId = bookId,\n            position = page.startOffset.toString(),\n            locatorType = "PAGE_ENGINE_V3",\n            txtCharOffset = page.startOffset,\n            txtTotalLength = paged.text.length,\n            epubSpineIndex = page.chapterIndex,\n            epubChapterOffset = page.startOffset - paged.chapters[page.chapterIndex].startOffset,\n            epubProgressFraction = if (paged.pages.size <= 1) 0f else page.globalPageIndex.toFloat() / (paged.pages.size - 1),\n            globalPageIndex = page.globalPageIndex,\n            chapterIndex = page.chapterIndex,\n            pageIndexInChapter = page.pageIndexInChapter,\n            startOffset = page.startOffset\n        )\n    }\n\n    private fun scheduleProgressCheckpoint(sourceOffset: Int) {\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable = Runnable {\n            progressCheckpointRunnable = null\n            val snapshot = progressSnapshotForOffset(sourceOffset) ?: return@Runnable\n            lifecycleScope.launch(Dispatchers.IO) {\n                database.readProgressDao().insert(snapshot)\n                database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())\n            }\n        }.also { mainHandler.postDelayed(it, PROGRESS_CHECKPOINT_DELAY_MS) }\n    }\n\n    private fun saveProgress() {\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable = null\n        val sourceOffset = stableProgressSourceOffset() ?: return\n        val snapshot = progressSnapshotForOffset(sourceOffset) ?: return\n        // Final write survives Activity destruction. The snapshot is immutable and does not rewrite\n        // currentPageIndex/lastStableSourceOffset, so persistence cannot move the live reader.\n        (application as App).applicationScope.launch {\n            database.readProgressDao().insert(snapshot)\n            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())\n        }\n    }\n'''
r = r[:m.start()] + new_progress + r[m.end():]

# During a transient focus/programmatic lock, currentVisibleSourceOffset must prefer the committed
# source anchor, not a potentially stale RecyclerView child position.
old_current = '''    private fun currentVisibleSourceOffset(): Int {\n        val paged = readerBook ?: return 0\n        if (pageTurnMode == TURN_MODE_VERTICAL) {\n            val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n            if (index in paged.pages.indices) return paged.pages[index].startOffset\n        }\n        return paged.pages.getOrNull(currentPageIndex)?.startOffset\n            ?: lastStableSourceOffset?.coerceIn(0, paged.text.length)\n            ?: 0\n    }\n'''
new_current = '''    private fun currentVisibleSourceOffset(): Int {\n        val paged = readerBook ?: return 0\n        if (pageTurnMode == TURN_MODE_VERTICAL) {\n            if (verticalWindowSuspended || verticalProgrammaticScroll) {\n                lastStableSourceOffset?.coerceIn(0, paged.text.length)?.let { return it }\n            }\n            val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n            if (index in paged.pages.indices) return paged.pages[index].startOffset\n        }\n        return lastStableSourceOffset?.coerceIn(0, paged.text.length)\n            ?: paged.pages.getOrNull(currentPageIndex)?.startOffset\n            ?: 0\n    }\n'''
if old_current not in r:
    raise SystemExit('currentVisibleSourceOffset block not found')
r = r.replace(old_current, new_current, 1)

# Focus loss/gain must never promote a transient visual anchor into the committed source anchor.
old_focus_capture = '''                val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n                if (index in pages.indices) {\n                    suspendedAnchorOffset = pages[index].startOffset\n                    suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0\n                    lastStableSourceOffset = suspendedAnchorOffset\n                }\n'''
new_focus_capture = '''                val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n                val visibleOffset = pages.getOrNull(index)?.startOffset\n                val anchorOffset = lastStableSourceOffset?.coerceIn(0, readerBook?.text?.length ?: Int.MAX_VALUE)\n                    ?: visibleOffset\n                if (anchorOffset != null) {\n                    suspendedAnchorOffset = anchorOffset\n                    val anchorPageIndex = readerBook?.pageForOffset(anchorOffset)?.globalPageIndex\n                    suspendedAnchorViewportPx = if (anchorPageIndex == index) {\n                        verticalLayoutManager?.findViewByPosition(index)?.top ?: 0\n                    } else 0\n                }\n'''
if old_focus_capture not in r:
    raise SystemExit('focus capture block not found')
r = r.replace(old_focus_capture, new_focus_capture, 1)
r = r.replace('                    lastStableSourceOffset = anchorOffset\n                    verticalLayoutManager?.scrollToPositionWithOffset(pageIndex, viewportOffset)\n',
              '                    verticalLayoutManager?.scrollToPositionWithOffset(pageIndex, viewportOffset)\n', 1)

# Add checkpoint delay constant next to the existing vertical unlock guard.
if 'PROGRESS_CHECKPOINT_DELAY_MS' not in r:
    r = r.replace('        private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L\n',
                  '        private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L\n        private const val PROGRESS_CHECKPOINT_DELAY_MS = 600L\n', 1)

# Adapter: never evict the whole rendered cache or notify/rebind during DRAGGING. Remove only the
# search-hit page cache entry and strip the visual BackgroundColorSpan directly from its attached view.
if 'import android.graphics.Color' not in a:
    a = a.replace('package com.simplereader.app.ui\n\n', 'package com.simplereader.app.ui\n\nimport android.graphics.Color\nimport android.text.Spannable\nimport android.text.style.BackgroundColorSpan\n', 1)

old_clear = '''    fun clearTransientSearchHighlight(recyclerView: RecyclerView) {\n        rendered.evictAll()\n        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return\n        val first = manager.findFirstVisibleItemPosition()\n        val last = manager.findLastVisibleItemPosition()\n        if (first >= 0 && last >= first) {\n            notifyItemRangeChanged(first, last - first + 1)\n        }\n    }\n'''
new_clear = '''    fun clearTransientSearchHighlight(recyclerView: RecyclerView, position: Int) {\n        if (position < 0) return\n        rendered.remove(position)\n        val holder = recyclerView.findViewHolderForAdapterPosition(position) as? VerticalPageHolder ?: return\n        val text = holder.textView.text as? Spannable ?: return\n        val searchColor = Color.rgb(255, 226, 105)\n        text.getSpans(0, text.length, BackgroundColorSpan::class.java)\n            .filter { it.backgroundColor == searchColor }\n            .forEach(text::removeSpan)\n        holder.textView.invalidate()\n    }\n'''
if old_clear not in a:
    raise SystemExit('old highlight clear block not found')
a = a.replace(old_clear, new_clear, 1)

old_state = '''        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {\n            activity.verticalOnUserDrag()\n            // Post the adapter rebind outside RecyclerView's scroll callback to avoid mutating\n            // adapter state while RecyclerView may still be computing a layout.\n            recyclerView.post {\n                (recyclerView.adapter as? VerticalPageAdapter)?.clearTransientSearchHighlight(recyclerView)\n            }\n        }\n'''
new_state = '''        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {\n            val hitPage = activity.verticalOnUserDrag()\n            if (hitPage != null) {\n                (recyclerView.adapter as? VerticalPageAdapter)\n                    ?.clearTransientSearchHighlight(recyclerView, hitPage)\n            }\n        }\n'''
if old_state not in a:
    raise SystemExit('scroll-state highlight block not found')
a = a.replace(old_state, new_state, 1)

# Hard invariants for this fix.
clear_body = re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView, position: Int\) \{(.*?)\n    \}', a, re.S)
if not clear_body:
    raise SystemExit('new highlight clear function missing')
for forbidden in ['evictAll()', 'notifyItemRangeChanged', 'notifyDataSetChanged', 'recyclerView.post']:
    if forbidden in clear_body.group(1):
        raise SystemExit(f'freeze-prone highlight behavior remains: {forbidden}')
if 'rendered.remove(position)' not in clear_body.group(1):
    raise SystemExit('target-only cache eviction missing')

for forbidden in [
    'suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0\n                    lastStableSourceOffset = suspendedAnchorOffset',
    'lastStableSourceOffset = anchorOffset\n                    verticalLayoutManager?.scrollToPositionWithOffset'
]:
    if forbidden in r:
        raise SystemExit('focus path still poisons stable anchor')

reader_path.write_text(r, encoding='utf-8')
adapter_path.write_text(a, encoding='utf-8')
build_path.write_text(b, encoding='utf-8')
print('v757 reader freeze/progress stability patch applied')
