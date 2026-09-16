from pathlib import Path

reader_path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
reader = reader_path.read_text(encoding='utf-8')

# v793 changes only rollback triggering semantics. v791 fling safety remains intact.
reader = reader.replace(
    'private var verticalRollbackDismissRunnable: Runnable? = null\n    private var verticalSettlingGuardRunnable: Runnable? = null',
    'private var verticalRollbackDismissRunnable: Runnable? = null\n    private var verticalRollbackBurstResetRunnable: Runnable? = null\n    private var verticalSettlingGuardRunnable: Runnable? = null'
)

old_maybe = '''    private fun maybeOfferVerticalRollback(visibleIndex: Int) {
        val start = verticalGestureStartLocation ?: return
        verticalGestureStartLocation = null
        if (visibleIndex !in readerBook?.pages.orEmpty().indices) return
        if (kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA) return
        showVerticalRollback(start, "gesture")
    }
'''
new_maybe = '''    private fun maybeOfferVerticalRollback(visibleIndex: Int) {
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
if old_maybe not in reader:
    raise SystemExit('missing v792 maybeOfferVerticalRollback block')
reader = reader.replace(old_maybe, new_maybe)

old_down = '''            MotionEvent.ACTION_DOWN -> {
                if (autoReading) stopAutoReading(false)
                // A new real touch must always brake the previous ViewFlinger before RecyclerView or
                // a selectable child TextView gets the event. Capture this gesture's origin only
                // after stopScroll(): stopScroll may synchronously emit IDLE and must not consume
                // the origin belonging to the new gesture.
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
                if (pageTurnMode == TURN_MODE_VERTICAL && verticalGestureStartLocation == null) {
                    verticalGestureStartLocation = captureVerticalLocation()
                }
'''
new_down = '''            MotionEvent.ACTION_DOWN -> {
                if (autoReading) stopAutoReading(false)
                // v791 fling safety is unchanged: a real touch always brakes ViewFlinger first.
                // v793 only groups nearby user actions into one rollback burst.
                cancelVerticalRollbackBurstReset()
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
                if (pageTurnMode == TURN_MODE_VERTICAL && verticalGestureStartLocation == null) {
                    verticalGestureStartLocation = captureVerticalLocation()
                }
'''
if old_down not in reader:
    raise SystemExit('missing v792 ACTION_DOWN block')
reader = reader.replace(old_down, new_down)

old_jump = '''    private fun jumpToPage(index: Int, animated: Boolean, hit: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        val rollbackOrigin = if (pageTurnMode == TURN_MODE_VERTICAL) captureVerticalLocation() else null
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
if old_jump not in reader:
    raise SystemExit('missing v792 jumpToPage header')
reader = reader.replace(old_jump, new_jump)

old_jump_body = '''            verticalRecyclerView?.stopScroll()
            cancelVerticalSettlingGuard()
            verticalGestureStartLocation = null
            verticalProgrammaticScroll = true
'''
new_jump_body = '''            verticalRecyclerView?.stopScroll()
            cancelVerticalSettlingGuard()
            verticalProgrammaticScroll = true
'''
if old_jump_body not in reader:
    raise SystemExit('missing v792 jumpToPage body')
reader = reader.replace(old_jump_body, new_jump_body, 1)

old_post = '''                if (offerRollback && rollbackOrigin != null) {
                    showVerticalRollback(rollbackOrigin, "explicit_jump")
                }
'''
new_post = '''                if (offerRollback && rollbackOrigin != null) {
                    cancelVerticalRollbackBurstReset()
                    verticalGestureStartLocation = null
                    showVerticalRollback(rollbackOrigin, "explicit_jump_burst")
                } else if (rollbackOrigin != null) {
                    scheduleVerticalRollbackBurstReset()
                }
'''
if old_post not in reader:
    raise SystemExit('missing v792 explicit jump post block')
reader = reader.replace(old_post, new_post, 1)

reader = reader.replace(
    '            dismissVerticalRollback(clearLocation = true)\n            verticalGestureStartLocation = null\n            verticalRecyclerView?.stopScroll()',
    '            dismissVerticalRollback(clearLocation = true)\n            cancelVerticalRollbackBurstReset()\n            verticalGestureStartLocation = null\n            verticalRecyclerView?.stopScroll()'
)
reader = reader.replace(
    '        cancelVerticalSettlingGuard()\n        dismissVerticalRollback(clearLocation = true)\n        stopAutoReading(false)',
    '        cancelVerticalSettlingGuard()\n        cancelVerticalRollbackBurstReset()\n        dismissVerticalRollback(clearLocation = true)\n        stopAutoReading(false)',
    1
)
reader = reader.replace(
    '        dismissVerticalRollback(clearLocation = true)\n        verticalGestureStartLocation = null\n        cancelVerticalSettlingGuard()',
    '        dismissVerticalRollback(clearLocation = true)\n        cancelVerticalRollbackBurstReset()\n        verticalGestureStartLocation = null\n        cancelVerticalSettlingGuard()',
    1
)

constant_anchor = 'private const val VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20'
if constant_anchor not in reader:
    raise SystemExit('missing v792 rollback delta constant')
if 'private const val VERTICAL_ROLLBACK_BURST_IDLE_MS' not in reader:
    reader = reader.replace(
        constant_anchor,
        constant_anchor + '\n        private const val VERTICAL_ROLLBACK_BURST_IDLE_MS = 1_000L'
    )
reader_path.write_text(reader, encoding='utf-8')

# Keep source defaults aligned with the generated version. CI env values remain authoritative.
build_path = Path('app/build.gradle.kts')
build = build_path.read_text(encoding='utf-8')
build = build.replace('?: "2098000791")', '?: "2098000793")')
build = build.replace('?: 2098000791', '?: 2098000793')
build = build.replace('?: "791"', '?: "793"')
build_path.write_text(build, encoding='utf-8')

v791_path = Path('app/src/test/java/com/simplereader/app/ui/ReaderV791VerticalRollbackContractTest.kt')
v791 = v791_path.read_text(encoding='utf-8')
v791 = v791.replace(
    'reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA")',
    'reader.contains("pageDelta > VERTICAL_ROLLBACK_MIN_PAGE_DELTA")'
)
v791_path.write_text(v791, encoding='utf-8')

v792_path = Path('app/src/test/java/com/simplereader/app/ui/ReaderV792RollbackThresholdContractTest.kt')
v792 = v792_path.read_text(encoding='utf-8')
v792 = v792.replace(
    'reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA")',
    'reader.contains("pageDelta > VERTICAL_ROLLBACK_MIN_PAGE_DELTA")'
)
v792_path.write_text(v792, encoding='utf-8')

v793_test = r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV793ShortBurstRollbackContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `short burst accumulates from one origin and requires over twenty pages`() {
        assertTrue(reader.contains("VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_BURST_IDLE_MS = 1_000L"))
        assertTrue(reader.contains("val pageDelta = kotlin.math.abs(visibleIndex - start.pageIndex)"))
        assertTrue(reader.contains("pageDelta > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("scheduleVerticalRollbackBurstReset()"))
        assertTrue(reader.contains("cancelVerticalRollbackBurstReset()"))
        assertTrue(reader.contains("showVerticalRollback(start, \"short_burst\")"))
    }

    @Test fun `chapter search catalog jumps share the same burst origin`() {
        assertTrue(reader.contains("verticalGestureStartLocation ?: captureVerticalLocation()?.also"))
        assertTrue(reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("showVerticalRollback(rollbackOrigin, \"explicit_jump_burst\")"))
        assertTrue(reader.contains("private fun jumpChapter(direction: Int)"))
        assertTrue(reader.contains("jumpToPage(targetPage, false)"))
    }

    @Test fun `v791 vertical fling insurance remains unchanged`() {
        val start = reader.indexOf("internal fun verticalHandleTouch")
        val end = reader.indexOf("Vertical mode renders", start)
        val touchBlock = reader.substring(start, end)
        assertTrue(touchBlock.contains("rv?.stopScroll()"))
        assertTrue(touchBlock.contains("cancelVerticalSettlingGuard()"))
        assertTrue(reader.contains("RecyclerView.SCROLL_STATE_SETTLING -> scheduleVerticalSettlingGuard()"))
        assertTrue(reader.contains("VERTICAL_SETTLING_GUARD_MS = 5_000L"))
        assertTrue(reader.contains("if ((event?.repeatCount ?: 0) > 0) return true"))
    }

    @Test fun `rollback ui remains three seconds above reader controls`() {
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
    }
}
'''
Path('app/src/test/java/com/simplereader/app/ui/ReaderV793ShortBurstRollbackContractTest.kt').write_text(v793_test, encoding='utf-8')

maintenance = '''# V793 短时间累计移动/跳转回撤

## 用户纠正
V792 仅判断一次位移是否超过 20 页，不符合需求。回撤应针对短时间内连续发生的大范围位置变化，而且章节、目录、搜索等跳转也必须计入。

## V793 行为
- 以一次“短时间连续操作 burst”的起始阅读位置作为唯一回撤原点。
- 正文快速滑动、章节跳转、目录跳转、搜索结果/书签跳转都沿用同一个 burst 原点。
- burst 内当前页与原点前后差距 **超过 20 页** 才显示“↩︎ 回撤”。
- 每次 RecyclerView IDLE 或一次显式跳转结束后，若尚未超过 20 页，则保留原点 1 秒；1 秒内继续操作仍属于同一 burst，超过 1 秒没有后续操作才清空并重新计数。
- 因此连续跳多个短章节，只要短时间内最终与最初位置相差超过 20 页，也会触发；正常停下来阅读后再慢慢翻页不会累计到旧位置。
- 回撤浮层仍在阅读下栏上方，3 秒未点击自动消失。

## 明确不改：V791 正文滑动保险
- ACTION_DOWN 无条件 `RecyclerView.stopScroll()`；
- `SCROLL_STATE_SETTLING` 5 秒极端兜底；
- 音量键长按 repeat 过滤；
- 自动阅读触摸停止逻辑保持不变。

## 验证
新增 `ReaderV793ShortBurstRollbackContractTest`；继续运行 Debug/Release 定向测试、继承全量 baseline、Debug/Release 构建与 APK 版本校验。
'''
Path('maintenance/V793_SHORT_BURST_ROLLBACK.md').write_text(maintenance, encoding='utf-8')

log_path = Path('TXT_READER_RENDERING_MAINTENANCE_LOG.md')
log = log_path.read_text(encoding='utf-8').rstrip()
entry = '''

## v793 — 短时间累计移动/跳转回撤
- 纠正 v792：回撤不再只看单次手势；按短时间连续操作 burst 统一累计。
- 正文快速滑动以及章节/目录/搜索/书签跳转共用 burst 起点；前后差距超过 20 页才触发。
- IDLE/跳转结束后 1 秒内继续操作仍属于同一 burst，停顿超过 1 秒后重新计数。
- v791 正文滑动保险完全保持：触摸即 stopScroll、5 秒 settling 兜底、音量键 repeat 过滤未改变。
'''
if '## v793 — 短时间累计移动/跳转回撤' not in log:
    log_path.write_text(log + entry + '\n', encoding='utf-8')

print('v793 short-burst rollback patch applied')
