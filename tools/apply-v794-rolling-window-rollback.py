from pathlib import Path

reader_path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
reader = reader_path.read_text(encoding='utf-8')

# Add a timestamped rolling-window sample store. Keep VerticalLocation unchanged so rollback
# restoration still uses stable sourceOffset + viewportOffsetPx.
anchor = '''    private data class VerticalLocation(
        val sourceOffset: Int,
        val pageIndex: Int,
        val viewportOffsetPx: Int
    )
'''
insert = anchor + '''    private data class TimedVerticalLocation(
        val uptimeMs: Long,
        val location: VerticalLocation
    )
'''
if 'private data class TimedVerticalLocation' not in reader:
    if anchor not in reader:
        raise SystemExit('missing VerticalLocation anchor')
    reader = reader.replace(anchor, insert, 1)

reader = reader.replace(
    '    private var verticalRollbackDismissRunnable: Runnable? = null\n    private var verticalRollbackBurstResetRunnable: Runnable? = null\n    private var verticalSettlingGuardRunnable: Runnable? = null',
    '    private var verticalRollbackDismissRunnable: Runnable? = null\n    private val verticalRollbackSamples = java.util.ArrayDeque<TimedVerticalLocation>()\n    private var verticalSettlingGuardRunnable: Runnable? = null',
    1
)

old_visible = '''    internal fun verticalOnPageVisible(index: Int) {
        val pages = readerBook?.pages.orEmpty()
        // RecyclerView emits pixel-level onScrolled callbacks. Reader state changes only when a
        // different ReaderPage becomes the first visible page, so do no UI work inside one page.
        if (index !in pages.indices || currentPageIndex == index) return
        currentPageIndex = index
        lastStableSourceOffset = pages[index].startOffset
        continuousWindowStartOffset = pages[index].startOffset
        continuousWindowEndOffset = pages[index].endOffset
        updateProgressUi()
        scheduleProgressCheckpoint(pages[index].startOffset)
    }
'''
new_visible = '''    internal fun verticalOnPageVisible(index: Int) {
        val pages = readerBook?.pages.orEmpty()
        // RecyclerView emits pixel-level onScrolled callbacks. Reader state changes only when a
        // different ReaderPage becomes the first visible page, so do no UI work inside one page.
        if (index !in pages.indices || currentPageIndex == index) return
        currentPageIndex = index
        lastStableSourceOffset = pages[index].startOffset
        continuousWindowStartOffset = pages[index].startOffset
        continuousWindowEndOffset = pages[index].endOffset
        if (!verticalShouldIgnoreScroll() && !autoReading) {
            verticalLocationForIndex(index)?.let {
                recordVerticalRollbackSample(it, "rolling_scroll", allowOffer = true)
            }
        }
        updateProgressUi()
        scheduleProgressCheckpoint(pages[index].startOffset)
    }
'''
if old_visible not in reader:
    raise SystemExit('missing verticalOnPageVisible block')
reader = reader.replace(old_visible, new_visible, 1)

old_helpers = '''    private fun captureVerticalLocation(): VerticalLocation? {
        val pages = readerBook?.pages.orEmpty()
        if (pages.isEmpty()) return null
        val index = (verticalLayoutManager?.findFirstVisibleItemPosition() ?: currentPageIndex)
            .coerceIn(0, pages.lastIndex)
        val page = pages[index]
        val top = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0
        return VerticalLocation(page.startOffset, index, top)
    }

    private fun maybeOfferVerticalRollback(visibleIndex: Int) {
        val start = verticalGestureStartLocation ?: return
        if (visibleIndex !in readerBook?.pages.orEmpty().indices) {
            scheduleVerticalRollbackBurstReset()
            return
        }
        val pageDelta = kotlin.math.abs(visibleIndex - start.pageIndex)
        if (pageDelta > VERTICAL_ROLLBACK_MIN_PAGE_DELTA) {
            cancelVerticalRollbackBurstReset()
            verticalGestureStartLocation = null
            showVerticalRollback(start, "short_burst")
            return
        }
        scheduleVerticalRollbackBurstReset()
    }

    private fun scheduleVerticalRollbackBurstReset() {
        verticalRollbackBurstResetRunnable?.let(mainHandler::removeCallbacks)
        verticalRollbackBurstResetRunnable = Runnable {
            verticalRollbackBurstResetRunnable = null
            verticalGestureStartLocation = null
        }.also { mainHandler.postDelayed(it, VERTICAL_ROLLBACK_BURST_IDLE_MS) }
    }

    private fun cancelVerticalRollbackBurstReset() {
        verticalRollbackBurstResetRunnable?.let(mainHandler::removeCallbacks)
        verticalRollbackBurstResetRunnable = null
    }
'''
new_helpers = '''    private fun verticalLocationForIndex(index: Int): VerticalLocation? {
        val pages = readerBook?.pages.orEmpty()
        if (index !in pages.indices) return null
        val page = pages[index]
        val top = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0
        return VerticalLocation(page.startOffset, index, top)
    }

    private fun captureVerticalLocation(): VerticalLocation? {
        val pages = readerBook?.pages.orEmpty()
        if (pages.isEmpty()) return null
        val index = (verticalLayoutManager?.findFirstVisibleItemPosition() ?: currentPageIndex)
            .coerceIn(0, pages.lastIndex)
        return verticalLocationForIndex(index)
    }

    private fun maybeOfferVerticalRollback(visibleIndex: Int) {
        verticalLocationForIndex(visibleIndex)?.let {
            recordVerticalRollbackSample(it, "rolling_idle", allowOffer = true)
        }
    }

    private fun recordVerticalRollbackSample(
        location: VerticalLocation?,
        reason: String,
        allowOffer: Boolean
    ) {
        if (location == null || pageTurnMode != TURN_MODE_VERTICAL || autoReading) return
        if (verticalRollbackSnackbar != null) return
        val now = android.os.SystemClock.uptimeMillis()
        while (verticalRollbackSamples.isNotEmpty() &&
            now - verticalRollbackSamples.peekFirst().uptimeMs > VERTICAL_ROLLBACK_WINDOW_MS) {
            verticalRollbackSamples.removeFirst()
        }
        if (allowOffer) {
            val origin = verticalRollbackSamples.firstOrNull { sample ->
                kotlin.math.abs(location.pageIndex - sample.location.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA
            }
            if (origin != null) {
                verticalRollbackSamples.clear()
                showVerticalRollback(origin.location, reason)
                return
            }
        }
        val last = verticalRollbackSamples.peekLast()
        if (last == null || last.location.pageIndex != location.pageIndex ||
            last.location.viewportOffsetPx != location.viewportOffsetPx) {
            verticalRollbackSamples.addLast(TimedVerticalLocation(now, location))
        }
    }

    private fun clearVerticalRollbackSamples() {
        verticalRollbackSamples.clear()
        verticalGestureStartLocation = null
    }
'''
if old_helpers not in reader:
    raise SystemExit('missing v793 rollback helper block')
reader = reader.replace(old_helpers, new_helpers, 1)

old_touch = '''            MotionEvent.ACTION_DOWN -> {
                if (autoReading) stopAutoReading(false)
                // v791 fling safety is unchanged: a real touch always brakes ViewFlinger first.
                // v793 only groups nearby user actions into one rollback burst.
                cancelVerticalRollbackBurstReset()
                rv?.stopScroll()
                // stopScroll() can synchronously dispatch IDLE, which may schedule a burst reset.
                // Cancel that newly scheduled reset too before this real gesture continues.
                cancelVerticalRollbackBurstReset()
                cancelVerticalSettlingGuard()
                if (pageTurnMode == TURN_MODE_VERTICAL && verticalGestureStartLocation == null) {
                    verticalGestureStartLocation = captureVerticalLocation()
                }
'''
new_touch = '''            MotionEvent.ACTION_DOWN -> {
                if (autoReading) stopAutoReading(false)
                // v791 fling safety is unchanged: a real touch always brakes ViewFlinger first.
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
                if (pageTurnMode == TURN_MODE_VERTICAL) {
                    recordVerticalRollbackSample(captureVerticalLocation(), "touch_origin", allowOffer = false)
                }
'''
if old_touch not in reader:
    raise SystemExit('missing v793 ACTION_DOWN block')
reader = reader.replace(old_touch, new_touch, 1)

old_jump = '''    private fun jumpToPage(index: Int, animated: Boolean, hit: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        val rollbackOrigin = if (pageTurnMode == TURN_MODE_VERTICAL) {
            cancelVerticalRollbackBurstReset()
            verticalGestureStartLocation ?: captureVerticalLocation()?.also {
                verticalGestureStartLocation = it
            }
        } else null
        activeSearchHit = hit
        currentPageIndex = index.coerceIn(0, pages.lastIndex)
        val targetPage = pages[currentPageIndex]
        val offerRollback = rollbackOrigin != null &&
            kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA
        lastStableSourceOffset = targetPage.startOffset
'''
new_jump = '''    private fun jumpToPage(index: Int, animated: Boolean, hit: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        val rollbackOrigin = if (pageTurnMode == TURN_MODE_VERTICAL) captureVerticalLocation() else null
        if (rollbackOrigin != null) {
            recordVerticalRollbackSample(rollbackOrigin, "explicit_jump_origin", allowOffer = false)
        }
        activeSearchHit = hit
        currentPageIndex = index.coerceIn(0, pages.lastIndex)
        val targetPage = pages[currentPageIndex]
        lastStableSourceOffset = targetPage.startOffset
'''
if old_jump not in reader:
    raise SystemExit('missing v793 jump header')
reader = reader.replace(old_jump, new_jump, 1)

old_jump_body = '''            ensureVerticalReader()
            verticalRecyclerView?.stopScroll()
            // Same synchronous-IDLE protection for rapid chapter/catalog/search jumps.
            cancelVerticalRollbackBurstReset()
            cancelVerticalSettlingGuard()
            verticalProgrammaticScroll = true
'''
new_jump_body = '''            ensureVerticalReader()
            verticalRecyclerView?.stopScroll()
            cancelVerticalSettlingGuard()
            verticalProgrammaticScroll = true
'''
if old_jump_body not in reader:
    raise SystemExit('missing v793 jump body')
reader = reader.replace(old_jump_body, new_jump_body, 1)

old_post = '''                if (offerRollback && rollbackOrigin != null) {
                    cancelVerticalRollbackBurstReset()
                    verticalGestureStartLocation = null
                    showVerticalRollback(rollbackOrigin, "explicit_jump_burst")
                } else if (rollbackOrigin != null) {
                    scheduleVerticalRollbackBurstReset()
                }
'''
new_post = '''                if (rollbackOrigin != null) {
                    recordVerticalRollbackSample(
                        VerticalLocation(targetPage.startOffset, currentPageIndex, 0),
                        "explicit_jump_window",
                        allowOffer = true
                    )
                }
'''
if old_post not in reader:
    raise SystemExit('missing v793 jump post block')
reader = reader.replace(old_post, new_post, 1)

# Remaining v793 reset calls are lifecycle/rollback cleanup points. They must clear the rolling
# one-second sample history rather than manage an idle-gap timer.
reader = reader.replace('cancelVerticalRollbackBurstReset()', 'clearVerticalRollbackSamples()')
if 'scheduleVerticalRollbackBurstReset()' in reader or 'verticalRollbackBurstResetRunnable' in reader:
    raise SystemExit('old idle-gap burst machinery still present')

# The snackbar lifetime is unchanged, but once it disappears start a fresh rolling window.
old_dismiss = '''                    verticalRollbackDismissRunnable = null
                    verticalRollbackLocation = null
'''
new_dismiss = '''                    verticalRollbackDismissRunnable = null
                    verticalRollbackLocation = null
                    clearVerticalRollbackSamples()
'''
if old_dismiss not in reader:
    raise SystemExit('missing snackbar dismiss cleanup')
reader = reader.replace(old_dismiss, new_dismiss, 1)

reader = reader.replace(
    '        private const val VERTICAL_ROLLBACK_BURST_IDLE_MS = 1_000L\n',
    '        private const val VERTICAL_ROLLBACK_WINDOW_MS = 1_000L\n',
    1
)
if 'VERTICAL_ROLLBACK_BURST_IDLE_MS' in reader:
    raise SystemExit('old idle-gap constant still present')

reader_path.write_text(reader, encoding='utf-8')

# Version fallback bump. CI also injects these values explicitly.
build_path = Path('app/build.gradle.kts')
build = build_path.read_text(encoding='utf-8')
build = build.replace('"2098000793"', '"2098000794"')
build = build.replace('?: 2098000793', '?: 2098000794')
build = build.replace('?: "793"', '?: "794"')
build_path.write_text(build, encoding='utf-8')

# Supersede source-contract assertions from v791-v793 that encoded the older single-origin / idle-gap implementation.
for test_name in [
    'ReaderV791VerticalRollbackContractTest.kt',
    'ReaderV792RollbackThresholdContractTest.kt',
    'ReaderV793ShortBurstRollbackContractTest.kt',
]:
    path = Path('app/src/test/java/com/simplereader/app/ui') / test_name
    text = path.read_text(encoding='utf-8')
    text = text.replace('reader.contains("pageDelta > VERTICAL_ROLLBACK_MIN_PAGE_DELTA")',
                        'reader.contains("kotlin.math.abs(location.pageIndex - sample.location.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA")')
    text = text.replace('reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA")',
                        'reader.contains("recordVerticalRollbackSample(")')
    text = text.replace('reader.contains("VERTICAL_ROLLBACK_BURST_IDLE_MS = 1_000L")',
                        'reader.contains("VERTICAL_ROLLBACK_WINDOW_MS = 1_000L")')
    text = text.replace('reader.contains("val pageDelta = kotlin.math.abs(visibleIndex - start.pageIndex)")',
                        'reader.contains("now - verticalRollbackSamples.peekFirst().uptimeMs > VERTICAL_ROLLBACK_WINDOW_MS")')
    text = text.replace('reader.contains("scheduleVerticalRollbackBurstReset()")',
                        'reader.contains("verticalRollbackSamples")')
    text = text.replace('reader.contains("cancelVerticalRollbackBurstReset()")',
                        'reader.contains("clearVerticalRollbackSamples()")')
    text = text.replace('reader.contains("showVerticalRollback(start, \\\"short_burst\\\")")',
                        'reader.contains("showVerticalRollback(origin.location, reason)")')
    text = text.replace('reader.contains("verticalGestureStartLocation ?: captureVerticalLocation()?.also")',
                        'reader.contains("recordVerticalRollbackSample(rollbackOrigin, \\\"explicit_jump_origin\\\", allowOffer = false)")')
    text = text.replace('reader.contains("showVerticalRollback(rollbackOrigin, \\\"explicit_jump_burst\\\")")',
                        'reader.contains("\\\"explicit_jump_window\\\"")')
    path.write_text(text, encoding='utf-8')

v794_test = r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV794RollingWindowRollbackContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `rollback uses a real rolling one second window`() {
        assertTrue(reader.contains("VERTICAL_ROLLBACK_WINDOW_MS = 1_000L"))
        assertTrue(reader.contains("private data class TimedVerticalLocation"))
        assertTrue(reader.contains("verticalRollbackSamples"))
        assertTrue(reader.contains("now - verticalRollbackSamples.peekFirst().uptimeMs > VERTICAL_ROLLBACK_WINDOW_MS"))
        assertTrue(reader.contains("kotlin.math.abs(location.pageIndex - sample.location.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertFalse(reader.contains("VERTICAL_ROLLBACK_BURST_IDLE_MS"))
        assertFalse(reader.contains("scheduleVerticalRollbackBurstReset"))
    }

    @Test fun `ordinary long continuous scrolling cannot accumulate across older than one second`() {
        val start = reader.indexOf("private fun recordVerticalRollbackSample")
        val end = reader.indexOf("private fun clearVerticalRollbackSamples", start)
        val block = reader.substring(start, end)
        assertTrue(block.indexOf("while (verticalRollbackSamples.isNotEmpty()") < block.indexOf("if (allowOffer)"))
        assertTrue(block.contains("removeFirst()"))
    }

    @Test fun `scroll and explicit navigation feed the same rolling window`() {
        assertTrue(reader.contains("recordVerticalRollbackSample(it, \"rolling_scroll\", allowOffer = true)"))
        assertTrue(reader.contains("recordVerticalRollbackSample(rollbackOrigin, \"explicit_jump_origin\", allowOffer = false)"))
        assertTrue(reader.contains("\"explicit_jump_window\""))
        assertTrue(reader.contains("jumpToPage(targetPage, false)"))
    }

    @Test fun `v791 vertical fling insurance stays unchanged`() {
        val start = reader.indexOf("internal fun verticalHandleTouch")
        val end = reader.indexOf("Vertical mode renders", start)
        val touch = reader.substring(start, end)
        assertTrue(touch.contains("rv?.stopScroll()"))
        assertTrue(touch.contains("cancelVerticalSettlingGuard()"))
        assertTrue(reader.contains("VERTICAL_SETTLING_GUARD_MS = 5_000L"))
        assertTrue(reader.contains("if ((event?.repeatCount ?: 0) > 0) return true"))
    }

    @Test fun `rollback ui stays three seconds above controls`() {
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
    }
}
'''
Path('app/src/test/java/com/simplereader/app/ui/ReaderV794RollingWindowRollbackContractTest.kt').write_text(v794_test, encoding='utf-8')

maintenance = '''# V794 回撤触发改为真实 1 秒滚动时间窗

## 实机反馈
V793 在上下模式持续正常滑动时会出现“↩︎ 回撤”，但用户确认实际不存在 1 秒内移动超过 20 页。

## 根因
V793 把“短时间”实现成 idle-gap burst：只要相邻操作之间没有停顿超过 1 秒，就会一直保留最早起点。因此几十秒正常连续滑动也可能最终与旧起点相差超过 20 页，产生误触发。

## V794 修复
- 删除 idle-gap 续命语义。
- 使用 `SystemClock.uptimeMillis()` 维护真正的 1000 ms 滚动时间窗。
- 每次记录新位置前先删除所有早于当前时刻 1000 ms 的位置样本。
- 只有当前页与仍处于最近 1000 ms 内的历史位置前后相差 **超过 20 页** 时才显示回撤。
- 正文滑动、章节/目录/搜索/书签等显式跳转都进入同一个时间窗。
- 自动阅读不参与回撤检测。
- 回撤 Snackbar 仍显示 3 秒，位于阅读下栏上方。

## 不改：正文滑动保险
- ACTION_DOWN 立即 `RecyclerView.stopScroll()`；
- `SCROLL_STATE_SETTLING` 5 秒极端兜底；
- 音量键 repeat 过滤；
- 原分页与位置恢复机制不改。

## 预期行为
持续普通滑动 30 秒、1 分钟都不会因为累计总页数达到 20 而触发；只有任意一个真实的连续 1 秒窗口内前后位移超过 20 页才会触发。一次瞬时章节/目录/搜索跳转若超过 20 页，也会触发。
'''
Path('maintenance/V794_ROLLING_WINDOW_ROLLBACK.md').write_text(maintenance, encoding='utf-8')

log_path = Path('TXT_READER_RENDERING_MAINTENANCE_LOG.md')
log = log_path.read_text(encoding='utf-8').rstrip()
entry = '''

## v794 — 回撤使用真实 1 秒滚动时间窗
- 实机发现 v793 的 idle-gap burst 会让普通持续滑动跨很多秒累计到 20 页，从而误弹回撤。
- 改为基于 `SystemClock.uptimeMillis()` 的真实 1000 ms 滚动窗口；超过 1 秒的旧位置样本先淘汰，再判断页差。
- 正文滑动与章节/目录/搜索/书签跳转共用同一窗口；自动阅读不参与。
- v791 正文滑动保险保持不变。
'''
if '## v794 — 回撤使用真实 1 秒滚动时间窗' not in log:
    log_path.write_text(log + entry + '\n', encoding='utf-8')

print('v794 rolling-window rollback patch applied')
