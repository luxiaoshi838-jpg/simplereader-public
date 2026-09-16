from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
reader_path = ROOT / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
adapter_path = ROOT / "app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt"
build_path = ROOT / "app/build.gradle.kts"
log_path = ROOT / "TXT_READER_RENDERING_MAINTENANCE_LOG.md"
test_path = ROOT / "app/src/test/java/com/simplereader/app/ui/ReaderV791VerticalRollbackContractTest.kt"
maintenance_path = ROOT / "maintenance/V791_VERTICAL_FLING_ROLLBACK.md"

reader = reader_path.read_text(encoding="utf-8")
adapter = adapter_path.read_text(encoding="utf-8")
build = build_path.read_text(encoding="utf-8")
log = log_path.read_text(encoding="utf-8")

# Material Snackbar is already an app dependency. Use its mature anchored transient-bar lifecycle.
if "import com.google.android.material.snackbar.Snackbar\n" not in reader:
    anchor = "import com.simplereader.app.runtime.ShelfCacheHandoff\n"
    if anchor not in reader:
        raise SystemExit("v791: Snackbar import anchor missing")
    reader = reader.replace(anchor, anchor + "import com.google.android.material.snackbar.Snackbar\n", 1)

state_anchor = "    private var pendingVerticalDiagnosticEvent: String? = null\n"
if state_anchor not in reader:
    raise SystemExit("v791: vertical state anchor missing")
state_block = '''    private var pendingVerticalDiagnosticEvent: String? = null
    private data class VerticalLocation(
        val sourceOffset: Int,
        val pageIndex: Int,
        val viewportOffsetPx: Int
    )
    private var verticalGestureStartLocation: VerticalLocation? = null
    private var verticalRollbackLocation: VerticalLocation? = null
    private var verticalRollbackSnackbar: Snackbar? = null
    private var verticalRollbackDismissRunnable: Runnable? = null
    private var verticalSettlingGuardRunnable: Runnable? = null
'''
if "private var verticalGestureStartLocation" not in reader:
    reader = reader.replace(state_anchor, state_block, 1)

# Lifecycle cleanup: no transient bar or fling guard may survive the reader Activity.
old_destroy = "        cancelVerticalStateUnlockGuard()\n        stopAutoReading(false)\n"
new_destroy = "        cancelVerticalStateUnlockGuard()\n        cancelVerticalSettlingGuard()\n        dismissVerticalRollback(clearLocation = true)\n        stopAutoReading(false)\n"
if old_destroy not in reader:
    raise SystemExit("v791: onDestroy cleanup anchor missing")
reader = reader.replace(old_destroy, new_destroy, 1)

# Physical volume keys: one page movement per physical key-down; Android long-press repeat must not
# stack multiple smoothScrollBy animations. Stop an existing fling before starting the page move.
on_key_pattern = re.compile(
    r'''    override fun onKeyDown\(keyCode: Int, event: KeyEvent\?\): Boolean \{\n        if \(volumeKeyTurnEnabled && \(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN \|\| keyCode == KeyEvent.KEYCODE_VOLUME_UP\)\) \{.*?\n        return super\.onKeyDown\(keyCode, event\)\n    \}\n''',
    re.S,
)
if not on_key_pattern.search(reader):
    raise SystemExit("v791: onKeyDown block missing")
new_on_key = '''    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyTurnEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if ((event?.repeatCount ?: 0) > 0) return true
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
            if (pageTurnMode == TURN_MODE_VERTICAL) {
                val rv = verticalRecyclerView
                rv?.stopScroll()
                if (rv != null) rv.smoothScrollBy(0, direction * rv.height.coerceAtLeast(1))
            } else {
                pagedReaderView.turn(direction)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
'''
reader = on_key_pattern.sub(new_on_key, reader, count=1)

# Replace the vertical idle/touch block with a gesture-scoped location history plus a hard brake.
block_pattern = re.compile(
    r'''    internal fun verticalOnScrollIdle\(\) \{.*?\n    internal fun verticalOnUserDrag\(\): Int\? \{.*?\n    \}\n    internal fun verticalHandleTouch\(event: MotionEvent\): Boolean \{.*?\n    \}\n\n''',
    re.S,
)
if not block_pattern.search(reader):
    raise SystemExit("v791: vertical idle/touch block missing")
new_block = '''    internal fun verticalOnScrollIdle() {
        if (pageTurnMode != TURN_MODE_VERTICAL || verticalShouldIgnoreScroll()) return
        cancelVerticalSettlingGuard()
        val visibleIndex = verticalLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (visibleIndex == 0 && currentPageIndex >= 4) {
            val restoreIndex = currentPageIndex
            pendingVerticalDiagnosticEvent =
                "vertical_idle_recover_zero book=$bookId visible=$visibleIndex restore=$restoreIndex stable=$lastStableSourceOffset"
            verticalProgrammaticScroll = true
            verticalLayoutManager?.scrollToPositionWithOffset(restoreIndex, 0)
            scheduleVerticalStateUnlockGuard()
            verticalRecyclerView?.post {
                verticalProgrammaticScroll = false
                if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()
                persistVerticalDiagnosticState("vertical_idle_recovered_zero", force = false)
            }
            return
        }
        maybeOfferVerticalRollback(visibleIndex)
        persistVerticalDiagnosticState("vertical_idle", force = false)
    }

    internal fun verticalOnScrollStateChanged(newState: Int) {
        when (newState) {
            RecyclerView.SCROLL_STATE_SETTLING -> scheduleVerticalSettlingGuard()
            RecyclerView.SCROLL_STATE_DRAGGING,
            RecyclerView.SCROLL_STATE_IDLE -> cancelVerticalSettlingGuard()
        }
    }

    private fun scheduleVerticalSettlingGuard() {
        cancelVerticalSettlingGuard()
        val startedAt = android.os.SystemClock.uptimeMillis()
        verticalSettlingGuardRunnable = Runnable {
            verticalSettlingGuardRunnable = null
            val rv = verticalRecyclerView ?: return@Runnable
            if (pageTurnMode != TURN_MODE_VERTICAL || rv.scrollState != RecyclerView.SCROLL_STATE_SETTLING) return@Runnable
            rv.stopScroll()
            CrashLogStore.recordEvent(
                this,
                "vertical_settling_forced_stop book=$bookId elapsed=${android.os.SystemClock.uptimeMillis() - startedAt} page=$currentPageIndex stable=$lastStableSourceOffset"
            )
        }.also { mainHandler.postDelayed(it, VERTICAL_SETTLING_GUARD_MS) }
    }

    private fun cancelVerticalSettlingGuard() {
        verticalSettlingGuardRunnable?.let(mainHandler::removeCallbacks)
        verticalSettlingGuardRunnable = null
    }

    private fun captureVerticalLocation(): VerticalLocation? {
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
        verticalGestureStartLocation = null
        if (visibleIndex !in readerBook?.pages.orEmpty().indices) return
        if (kotlin.math.abs(visibleIndex - start.pageIndex) <= 1) return
        showVerticalRollback(start, "gesture")
    }

    private fun showVerticalRollback(location: VerticalLocation, reason: String) {
        if (pageTurnMode != TURN_MODE_VERTICAL || readerBook == null || isFinishing || isDestroyed) return
        dismissVerticalRollback(clearLocation = false)
        verticalRollbackLocation = location
        val snackbar = Snackbar.make(readerRoot, "位置已移动", Snackbar.LENGTH_INDEFINITE)
        snackbar.setAnchorView(readerControls)
        snackbar.setAction("↩︎ 回撤") {
            val target = verticalRollbackLocation ?: return@setAction
            dismissVerticalRollback(clearLocation = true)
            verticalGestureStartLocation = null
            verticalRecyclerView?.stopScroll()
            cancelVerticalSettlingGuard()
            CrashLogStore.recordEvent(
                this,
                "vertical_rollback_apply book=$bookId from=$currentPageIndex to=${target.pageIndex} source=${target.sourceOffset}"
            )
            restoreVerticalLocation(target)
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (verticalRollbackSnackbar === transientBottomBar) {
                    verticalRollbackSnackbar = null
                    verticalRollbackDismissRunnable?.let(mainHandler::removeCallbacks)
                    verticalRollbackDismissRunnable = null
                    verticalRollbackLocation = null
                }
            }
        })
        verticalRollbackSnackbar = snackbar
        snackbar.show()
        verticalRollbackDismissRunnable = Runnable {
            if (verticalRollbackSnackbar === snackbar) snackbar.dismiss()
        }.also { mainHandler.postDelayed(it, VERTICAL_ROLLBACK_VISIBLE_MS) }
        CrashLogStore.recordEvent(
            this,
            "vertical_rollback_offer book=$bookId reason=$reason from=${location.pageIndex} to=$currentPageIndex source=${location.sourceOffset}"
        )
    }

    private fun dismissVerticalRollback(clearLocation: Boolean) {
        verticalRollbackDismissRunnable?.let(mainHandler::removeCallbacks)
        verticalRollbackDismissRunnable = null
        val active = verticalRollbackSnackbar
        verticalRollbackSnackbar = null
        active?.dismiss()
        if (clearLocation) verticalRollbackLocation = null
    }

    private fun restoreVerticalLocation(location: VerticalLocation) {
        val paged = readerBook ?: return
        val index = paged.pageForOffset(location.sourceOffset.coerceIn(0, paged.text.length)).globalPageIndex
        currentPageIndex = index
        lastStableSourceOffset = paged.pages[index].startOffset
        continuousWindowStartOffset = paged.pages[index].startOffset
        continuousWindowEndOffset = paged.pages[index].endOffset
        verticalProgrammaticScroll = true
        verticalLayoutManager?.scrollToPositionWithOffset(index, location.viewportOffsetPx)
        scheduleVerticalStateUnlockGuard()
        verticalRecyclerView?.postOnAnimation {
            verticalProgrammaticScroll = false
            if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()
            verticalOnPageVisible(index)
            updateProgressUi()
            saveProgress()
        }
    }

    internal fun verticalOnUserDrag(): Int? {
        val hitPage = activeSearchHit?.globalPageIndex
        clearSearchHighlight()
        return hitPage
    }

    internal fun verticalHandleTouch(event: MotionEvent): Boolean {
        val rv = verticalRecyclerView
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (autoReading) stopAutoReading(false)
                if (pageTurnMode == TURN_MODE_VERTICAL && verticalGestureStartLocation == null) {
                    verticalGestureStartLocation = captureVerticalLocation()
                }
                // A new real touch must always brake the previous ViewFlinger before RecyclerView or
                // a selectable child TextView gets the event. This closes the stale-settling path.
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
                if (hasWindowFocus() && (verticalWindowSuspended || verticalProgrammaticScroll)) {
                    releaseVerticalStateLock(clearAnchor = true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (rv?.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                    val visible = verticalLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                    maybeOfferVerticalRollback(visible)
                }
            }
        }
        continuousGesture.onTouchEvent(event)
        return false
    }

'''
reader = block_pattern.sub(new_block, reader, count=1)

# Explicit navigation (catalog/search/chapter/progress): save origin before changing currentPageIndex
# and offer the same three-second back action when the jump is larger than one page.
jump_pattern = re.compile(
    r'''    private fun jumpToPage\(index: Int, animated: Boolean, hit: SearchPageHit\? = null\) \{.*?\n    \}\n\n    private fun jumpChapter''',
    re.S,
)
jump_match = jump_pattern.search(reader)
if not jump_match:
    raise SystemExit("v791: jumpToPage block missing")
old_jump = jump_match.group(0)
new_jump = '''    private fun jumpToPage(index: Int, animated: Boolean, hit: SearchPageHit? = null) {
        val pages = readerBook?.pages ?: return
        if (pages.isEmpty()) return
        val rollbackOrigin = if (pageTurnMode == TURN_MODE_VERTICAL) captureVerticalLocation() else null
        activeSearchHit = hit
        currentPageIndex = index.coerceIn(0, pages.lastIndex)
        val targetPage = pages[currentPageIndex]
        val offerRollback = rollbackOrigin != null &&
            kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > 1
        lastStableSourceOffset = targetPage.startOffset
        // v755: explicit navigation while a dialog owns focus replaces the pre-dialog restore anchor.
        if (pageTurnMode == TURN_MODE_VERTICAL && verticalWindowSuspended) {
            suspendedAnchorOffset = targetPage.startOffset
            suspendedAnchorViewportPx = 0
        }
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            ensureVerticalReader()
            verticalRecyclerView?.stopScroll()
            cancelVerticalSettlingGuard()
            verticalGestureStartLocation = null
            verticalProgrammaticScroll = true
            verticalAdapter?.refresh()
            verticalLayoutManager?.scrollToPositionWithOffset(currentPageIndex, 0)
            scheduleVerticalStateUnlockGuard()
            verticalRecyclerView?.post {
                verticalProgrammaticScroll = false
                if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()
                if (offerRollback && rollbackOrigin != null) {
                    showVerticalRollback(rollbackOrigin, "explicit_jump")
                }
            }
        } else {
            pagedReaderView.cancelNavigation()
            bindHorizontalPages()
        }
        updateProgressUi()
        if (!animated) saveProgress()
    }

    private fun jumpChapter'''
reader = reader.replace(old_jump, new_jump, 1)

# Leaving vertical mode removes any stale transient action.
show_horizontal_anchor = "    private fun showHorizontalBook() {\n        verticalRecyclerView?.apply { stopScroll(); visibility = View.GONE }\n"
if show_horizontal_anchor not in reader:
    raise SystemExit("v791: showHorizontalBook anchor missing")
reader = reader.replace(
    show_horizontal_anchor,
    "    private fun showHorizontalBook() {\n        dismissVerticalRollback(clearLocation = true)\n        verticalGestureStartLocation = null\n        cancelVerticalSettlingGuard()\n        verticalRecyclerView?.apply { stopScroll(); visibility = View.GONE }\n",
    1,
)

# RecyclerView state listener forwards SETTLING/DRAGGING/IDLE to the Activity guard.
adapter_state_anchor = '''    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
'''
if adapter_state_anchor not in adapter:
    raise SystemExit("v791: VerticalScrollListener state anchor missing")
adapter = adapter.replace(
    adapter_state_anchor,
    '''    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        activity.verticalOnScrollStateChanged(newState)
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
''',
    1,
)

# Version bump.
build = build.replace('?: "2098000790"', '?: "2098000791"')
build = build.replace('?: 2098000790', '?: 2098000791')
build = build.replace('?: "790"', '?: "791"')
if "2098000791" not in build or '?: "791"' not in build:
    raise SystemExit("v791: build version bump failed")

# Constants: 3-second user-visible rollback, 5-second fail-safe for a genuinely stuck settling state.
constant_anchor = "        private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L\n"
if constant_anchor not in reader:
    raise SystemExit("v791: constants anchor missing")
if "VERTICAL_ROLLBACK_VISIBLE_MS" not in reader:
    reader = reader.replace(
        constant_anchor,
        constant_anchor +
        "        private const val VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L\n" +
        "        private const val VERTICAL_SETTLING_GUARD_MS = 5_000L\n",
        1,
    )

reader_path.write_text(reader, encoding="utf-8")
adapter_path.write_text(adapter, encoding="utf-8")
build_path.write_text(build, encoding="utf-8")

# Contract test locks the behavior that matters on the real device.
test_path.write_text(r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV791VerticalRollbackContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val adapter = File("src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt").readText()

    @Test fun `real touch brakes stale vertical fling before child dispatch`() {
        val start = reader.indexOf("internal fun verticalHandleTouch")
        val end = reader.indexOf("Vertical mode renders", start)
        assertTrue(start >= 0 && end > start)
        val block = reader.substring(start, end)
        assertTrue(block.contains("MotionEvent.ACTION_DOWN"))
        assertTrue(block.contains("rv?.stopScroll()"))
        assertTrue(block.contains("cancelVerticalSettlingGuard()"))
    }

    @Test fun `settling has a bounded fail safe and listener forwards state`() {
        assertTrue(adapter.contains("activity.verticalOnScrollStateChanged(newState)"))
        assertTrue(reader.contains("RecyclerView.SCROLL_STATE_SETTLING -> scheduleVerticalSettlingGuard()"))
        assertTrue(reader.contains("VERTICAL_SETTLING_GUARD_MS = 5_000L"))
        assertTrue(reader.contains("vertical_settling_forced_stop"))
    }

    @Test fun `large jump rollback follows mature previous location pattern`() {
        assertTrue(reader.contains("private data class VerticalLocation"))
        assertTrue(reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= 1"))
        assertTrue(reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > 1"))
        assertTrue(reader.contains("Snackbar.make(readerRoot, \"位置已移动\", Snackbar.LENGTH_INDEFINITE)"))
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\"↩︎ 回撤\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
        assertTrue(reader.contains("restoreVerticalLocation(target)"))
    }

    @Test fun `rollback uses source offset plus viewport offset and does not create a new rollback`() {
        assertTrue(reader.contains("val sourceOffset: Int"))
        assertTrue(reader.contains("val viewportOffsetPx: Int"))
        assertTrue(reader.contains("scrollToPositionWithOffset(index, location.viewportOffsetPx)"))
        val restoreStart = reader.indexOf("private fun restoreVerticalLocation")
        val restoreEnd = reader.indexOf("internal fun verticalOnUserDrag", restoreStart)
        val restore = reader.substring(restoreStart, restoreEnd)
        assertFalse(restore.contains("showVerticalRollback("))
    }

    @Test fun `volume key long press cannot stack vertical smooth scrolls`() {
        assertTrue(reader.contains("if ((event?.repeatCount ?: 0) > 0) return true"))
        assertTrue(reader.contains("rv?.stopScroll()"))
    }
}
''', encoding="utf-8")

section = r'''

## 2026-09-16 — V791

### 用户问题
- 竖向阅读页偶发出现一次甩动后长时间持续滑动，重新触摸时不应继续沿用旧 fling。
- 短时间一次操作造成大位置移动后，需要在阅读下栏上方临时出现“↩︎ 回撤”，3 秒未点击自动消失，可回到移动前阅读位置。

### 成熟公开实现依据
- KOReader `ReaderLink` 使用 location stack 保存跳转前位置并提供 previous-location 返回。
- KOReader 的 `kojump.koplugin` 只把 `abs(newPage-currentPage) > 1` 记录为 jump，普通相邻页阅读不进入历史；V791沿用这一“大跳转而非连续阅读”的判定。
- Android Material `Snackbar` 提供成熟 transient bottom bar 和 anchor view 机制；V791 使用现有 Material 依赖，把回撤浮层 anchor 到 `readerControls` 上方，不自造悬浮窗口生命周期。

### V791 修法
- 任何竖向阅读页真实 `ACTION_DOWN` 在事件继续分发给 RecyclerView/可选 TextView 前先 `stopScroll()`，保证手指重新落下必定刹住旧 ViewFlinger。
- `SCROLL_STATE_SETTLING` 增加 5 秒极端兜底；只有异常长时间仍处于 settling 才强制 stop，并写 `vertical_settling_forced_stop` 诊断事件。
- 音量键长按重复 keyDown 不再叠加多次 `smoothScrollBy()`。
- 一次真实触摸手势开始时记录 `sourceOffset + pageIndex + 当前 item top`；结束进入 IDLE 后若跨越超过 1 页，显示回撤。
- 目录、搜索、章节、进度条等显式 `jumpToPage()` 在跳转前同样保存位置；跨越超过 1 页时显示回撤。
- 回撤 UI 使用 Material Snackbar，锚定 `readerControls`，文案为“位置已移动 / ↩︎ 回撤”；显示后由主线程 3000 ms 定时主动 dismiss，避免 Snackbar 无障碍时长策略改变用户要求的 3 秒。
- 点击回撤按保存的 `sourceOffset` 重新解析目标页，并用保存的 `viewportOffsetPx` 恢复该页在视口中的垂直位置；回撤本身不再次创建回撤提示。

### 禁止回归
- 不允许普通相邻页连续阅读频繁弹出回撤。
- 不允许回撤只保存 RecyclerView adapter position 而丢失 sourceOffset。
- 不允许新触摸继续继承旧 settling/fling。
- 不允许音量键长按堆积多个平滑滚动动画。
- 不修改 V757-V759 的稳定阅读进度、页0保护、checkpoint 与异常恢复优先级。
'''
if "## 2026-09-16 — V791" not in log:
    log += section
log_path.write_text(log, encoding="utf-8")

maintenance_path.parent.mkdir(parents=True, exist_ok=True)
maintenance_path.write_text(r'''# V791 竖向阅读 fling 保险与大跳转回撤

## 范围
本版只修改 TXT/通用 `ReaderActivity` 的竖向 RecyclerView 阅读链，不改书架、不改分页算法、不改 PageEngine offset 定义。

## 问题 1：偶发持续滑动
V790 的 `verticalHandleTouch()` 能停止自动阅读、解除陈旧程序化状态锁，但真实 `ACTION_DOWN` 没有无条件调用 RecyclerView `stopScroll()`；同时 `VerticalScrollListener` 对 `SCROLL_STATE_SETTLING` 没有极端兜底。V791 增加“触摸即刹车”和 5 秒 stuck-settling 保险，并过滤音量键长按重复。

## 问题 2：短时间大位置移动后的回撤
参考 KOReader location history 与公开 `kojump.koplugin`：只把跨越超过 1 页视为 jump；保存移动前 location。Android UI 使用 Material Snackbar 的 anchor 模式，浮在阅读下栏 `readerControls` 上方。显示时间由主线程固定为 3000 ms。

保存位置不是单纯页号，而是：
- `sourceOffset`
- `pageIndex`
- 第一可见页相对视口的 `top` 像素

点击“↩︎ 回撤”后，重新用 `sourceOffset` 定位当前分页中的目标页，再用保存的 top 恢复视口位置，因此比只保存 adapter position 更耐字号/分页状态变化。

## 公开实现参考
- KOReader: `frontend/apps/reader/modules/readerlink.lua` — previous-location stack / back-forward navigation.
- KOReader plugin: `dani84bs/kojump.koplugin/main.lua` — `abs(page_num-current_page) > 1` 才记录 jump，并保存 source page。
- Material Components Android: Snackbar / anchorView transient bottom bar pattern.

## 验证门
- `ReaderV791VerticalRollbackContractTest` Debug + Release。
- V759 全量单测基线门：只允许既有 10 项历史失败，不允许 V791 新增失败。
- Debug + Release 构建。
- APK 包名、versionCode=2098000791、versionName=791 校验。
''', encoding="utf-8")

print("v791 vertical fling + rollback patch applied")
