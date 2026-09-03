from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app = root / 'app/src/main/java/com/simplereader/app/App.kt'
reader = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'

# Application-scoped short IO jobs: progress persistence must survive Activity destruction.
a = app.read_text(encoding='utf-8')
expected_app = '''package com.simplereader.app\n\nimport android.app.Application\nimport com.simplereader.app.crash.CrashLogStore\n\nclass App : Application() {\n    companion object {\n        lateinit var instance: App\n            private set\n    }\n\n    override fun onCreate() {\n        super.onCreate()\n        instance = this\n        CrashLogStore.install(this)\n    }\n}\n'''
replacement_app = '''package com.simplereader.app\n\nimport android.app.Application\nimport com.simplereader.app.crash.CrashLogStore\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.SupervisorJob\n\nclass App : Application() {\n    companion object {\n        lateinit var instance: App\n            private set\n    }\n\n    /** Short application-lifetime IO jobs that must survive an Activity finishing. */\n    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)\n\n    override fun onCreate() {\n        super.onCreate()\n        instance = this\n        CrashLogStore.install(this)\n    }\n}\n'''
if a == expected_app:
    app.write_text(replacement_app, encoding='utf-8')
elif 'val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)' not in a:
    raise SystemExit('unexpected App.kt baseline')

r = reader.read_text(encoding='utf-8')

def once(old: str, new: str, label: str):
    global r
    if old in r:
        if r.count(old) != 1:
            raise SystemExit(f'{label}: expected one match, got {r.count(old)}')
        r = r.replace(old, new, 1)
    elif new not in r:
        raise SystemExit(f'{label}: baseline not found')

once('import com.simplereader.app.R\n', 'import com.simplereader.app.App\nimport com.simplereader.app.R\n', 'App import')
once('    private var lastStableSourceOffset: Int? = null\n', '    private var lastStableSourceOffset: Int? = null\n    private var verticalStateUnlockRunnable: Runnable? = null\n', 'unlock field')
once('        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        stopAutoReading(false)\n        super.onDestroy()\n', '        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        cancelVerticalStateUnlockGuard()\n        stopAutoReading(false)\n        super.onDestroy()\n', 'destroy guard cleanup')

old_show = '''        verticalRecyclerView?.visibility = View.VISIBLE\n        verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)\n        verticalRecyclerView?.post {\n            verticalProgrammaticScroll = false\n            verticalOnPageVisible(currentPageIndex)\n            onComplete?.invoke(true)\n        }\n'''
new_show = '''        verticalRecyclerView?.visibility = View.VISIBLE\n        verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)\n        scheduleVerticalStateUnlockGuard()\n        verticalRecyclerView?.post {\n            verticalProgrammaticScroll = false\n            if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()\n            verticalOnPageVisible(currentPageIndex)\n            onComplete?.invoke(true)\n        }\n'''
once(old_show, new_show, 'showContinuousBook unlock guard')

old_touch = '''    internal fun verticalOnUserDrag() { clearSearchHighlight() }\n    internal fun verticalHandleTouch(event: MotionEvent): Boolean {\n        if (event.actionMasked == MotionEvent.ACTION_DOWN && autoReading) stopAutoReading(false)\n        continuousGesture.onTouchEvent(event)\n        return false\n    }\n'''
new_touch = '''    internal fun verticalOnUserDrag() { clearSearchHighlight() }\n    internal fun verticalHandleTouch(event: MotionEvent): Boolean {\n        if (event.actionMasked == MotionEvent.ACTION_DOWN) {\n            if (autoReading) stopAutoReading(false)\n            // A stale focus/programmatic lock must never make the live RecyclerView untouchable.\n            if (hasWindowFocus() && (verticalWindowSuspended || verticalProgrammaticScroll)) {\n                releaseVerticalStateLock(clearAnchor = true)\n            }\n        }\n        continuousGesture.onTouchEvent(event)\n        return false\n    }\n'''
once(old_touch, new_touch, 'touch stale-lock release')

old_jump = '''            verticalProgrammaticScroll = true\n            verticalAdapter?.refresh()\n            verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)\n            verticalRecyclerView?.post { verticalProgrammaticScroll = false }\n'''
new_jump = '''            verticalProgrammaticScroll = true\n            verticalAdapter?.refresh()\n            verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)\n            scheduleVerticalStateUnlockGuard()\n            verticalRecyclerView?.post {\n                verticalProgrammaticScroll = false\n                if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()\n            }\n'''
once(old_jump, new_jump, 'jump unlock guard')

# Replace saveProgress with a stable source-anchor snapshot and application-lifetime persistence.
pat_save = re.compile(r'''    private fun saveProgress\(\) \{.*?\n    \}\n\n    private fun changeTextSize''', re.S)
new_save = '''    private fun stableProgressSourceOffset(): Int? {\n        val paged = readerBook ?: return null\n        val current = paged.pages.getOrNull(currentPageIndex)?.startOffset\n        val stable = lastStableSourceOffset?.coerceIn(0, paged.text.length)\n        if (pageTurnMode != TURN_MODE_VERTICAL) return current ?: stable\n\n        val suspended = suspendedAnchorOffset?.coerceIn(0, paged.text.length)\n        val visibleIndex = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n        val visible = paged.pages.getOrNull(visibleIndex)?.startOffset\n        if (verticalWindowSuspended) return suspended ?: visible ?: stable ?: current\n\n        // If RecyclerView transiently reports position 0 while our last committed source anchor is\n        // later in the book, keep the committed anchor. A real user return to page 1 updates\n        // lastStableSourceOffset to 0 through verticalOnPageVisible before pause.\n        return when {\n            visible == 0 && stable != null && stable > 0 -> stable\n            visible != null -> visible\n            suspended != null -> suspended\n            stable != null -> stable\n            else -> current\n        }\n    }\n\n    private fun saveProgress() {\n        val paged = readerBook ?: return\n        val sourceOffset = stableProgressSourceOffset() ?: return\n        val page = paged.pageForOffset(sourceOffset.coerceIn(0, paged.text.length))\n        currentPageIndex = page.globalPageIndex\n        lastStableSourceOffset = page.startOffset\n        val snapshot = ReadProgress(\n            bookId = bookId,\n            position = page.startOffset.toString(),\n            locatorType = "PAGE_ENGINE_V3",\n            txtCharOffset = page.startOffset,\n            txtTotalLength = paged.text.length,\n            epubSpineIndex = page.chapterIndex,\n            epubChapterOffset = page.startOffset - paged.chapters[page.chapterIndex].startOffset,\n            epubProgressFraction = if (paged.pages.size <= 1) 0f else page.globalPageIndex.toFloat() / (paged.pages.size - 1),\n            globalPageIndex = page.globalPageIndex,\n            chapterIndex = page.chapterIndex,\n            pageIndexInChapter = page.pageIndexInChapter,\n            startOffset = page.startOffset\n        )\n        // Do not bind this final write to Activity lifecycle: a user may leave immediately after a\n        // freeze/focus transition and onDestroy must not cancel the last valid progress snapshot.\n        (application as App).applicationScope.launch {\n            database.readProgressDao().insert(snapshot)\n            database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())\n        }\n    }\n\n    private fun changeTextSize'''
m = pat_save.search(r)
if not m:
    if 'private fun stableProgressSourceOffset()' not in r:
        raise SystemExit('saveProgress baseline not found')
else:
    r = r[:m.start()] + new_save + r[m.end():]

# Replace focus lock with a one-frame normal release plus a timed fail-safe.
pat_focus = re.compile(r'''    override fun onWindowFocusChanged\(hasFocus: Boolean\) \{.*?\n    \}\n\n\n    private fun showAutoReadDialog''', re.S)
new_focus = '''    private fun cancelVerticalStateUnlockGuard() {\n        verticalStateUnlockRunnable?.let(mainHandler::removeCallbacks)\n        verticalStateUnlockRunnable = null\n    }\n\n    private fun scheduleVerticalStateUnlockGuard() {\n        cancelVerticalStateUnlockGuard()\n        verticalStateUnlockRunnable = Runnable {\n            verticalStateUnlockRunnable = null\n            if (!isFinishing && !isDestroyed && hasWindowFocus() && pageTurnMode == TURN_MODE_VERTICAL) {\n                // Fail-safe only: normal focus/programmatic restoration releases on the next frame.\n                // If that callback is lost because RecyclerView was detached/relaid out, never keep\n                // the reader permanently suspended.\n                verticalRecyclerView?.stopScroll()\n                verticalProgrammaticScroll = false\n                verticalWindowSuspended = false\n                suspendedAnchorOffset = null\n                suspendedAnchorViewportPx = null\n            }\n        }.also { mainHandler.postDelayed(it, VERTICAL_STATE_UNLOCK_GUARD_MS) }\n    }\n\n    private fun releaseVerticalStateLock(clearAnchor: Boolean) {\n        cancelVerticalStateUnlockGuard()\n        verticalRecyclerView?.stopScroll()\n        verticalProgrammaticScroll = false\n        verticalWindowSuspended = false\n        if (clearAnchor) {\n            suspendedAnchorOffset = null\n            suspendedAnchorViewportPx = null\n        }\n    }\n\n    override fun onWindowFocusChanged(hasFocus: Boolean) {\n        super.onWindowFocusChanged(hasFocus)\n        val rv = verticalRecyclerView\n        if (!hasFocus) {\n            cancelVerticalStateUnlockGuard()\n            if (rv != null && pageTurnMode == TURN_MODE_VERTICAL) {\n                val pages = readerBook?.pages.orEmpty()\n                val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1\n                if (index in pages.indices) {\n                    suspendedAnchorOffset = pages[index].startOffset\n                    suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0\n                    lastStableSourceOffset = suspendedAnchorOffset\n                }\n            }\n            verticalWindowSuspended = true\n            stopAutoReading(false)\n            rv?.stopScroll()\n        } else if (rv != null && pageTurnMode == TURN_MODE_VERTICAL) {\n            verticalWindowSuspended = true\n            rv.stopScroll()\n            val anchorOffset = suspendedAnchorOffset\n            val viewportOffset = suspendedAnchorViewportPx ?: 0\n            if (anchorOffset != null) {\n                val pageIndex = readerBook?.pageForOffset(anchorOffset)?.globalPageIndex\n                if (pageIndex != null) {\n                    verticalProgrammaticScroll = true\n                    currentPageIndex = pageIndex\n                    lastStableSourceOffset = anchorOffset\n                    verticalLayoutManager?.scrollToPositionWithOffset(pageIndex, viewportOffset)\n                }\n            }\n            scheduleVerticalStateUnlockGuard()\n            rv.postOnAnimation {\n                releaseVerticalStateLock(clearAnchor = true)\n            }\n        } else {\n            releaseVerticalStateLock(clearAnchor = true)\n        }\n    }\n\n\n    private fun showAutoReadDialog'''
m = pat_focus.search(r)
if not m:
    if 'private fun scheduleVerticalStateUnlockGuard()' not in r:
        raise SystemExit('focus baseline not found')
else:
    r = r[:m.start()] + new_focus + r[m.end():]

once('        private const val AUTO_READ_MIN_PAGE_DELAY_MS = 700L\n', '        private const val AUTO_READ_MIN_PAGE_DELAY_MS = 700L\n        private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L\n', 'unlock constant')

reader.write_text(r, encoding='utf-8')
print('v756 reader stability patch applied')
