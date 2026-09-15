from pathlib import Path

main_path = Path("app/src/main/java/com/simplereader/app/ui/MainActivity.kt")
main = main_path.read_text()

old_state = '    private var shelfListMode = false\n    private var shelfSearchQuery = ""\n'
new_state = '    private var shelfListMode = false\n    private var shelfLayoutSwitchInFlight = false\n    private var shelfSearchQuery = ""\n'
if new_state not in main:
    if old_state not in main:
        raise SystemExit("v788 state anchor missing")
    main = main.replace(old_state, new_state, 1)

old_methods = '''    private fun applyShelfLayoutMode(updateButton: Boolean = true) {
        shelfGrid.layoutManager = if (shelfListMode) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (shelfAdapter.isFullSpan(position)) 3 else 1
                }
            }
        }
        if (updateButton && ::editButton.isInitialized && !shelfSelectionMode) updateShelfModeButton()
        if (::shelfGrid.isInitialized) shelfAdapter.notifyDataSetChanged()
    }

    private fun updateShelfModeButton() {
        if (!::editButton.isInitialized || shelfSelectionMode) return
        editButton.text = if (shelfListMode) "宫格" else "列表"
        editButton.contentDescription = if (shelfListMode) "切换为宫格模式" else "切换为列表模式"
    }

    private fun toggleShelfLayoutMode() {
        shelfListMode = !shelfListMode
        getSharedPreferences(SHELF_UI_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SHELF_LIST_MODE_KEY, shelfListMode)
            .apply()
        applyShelfLayoutMode()
    }
'''
new_methods = '''    private fun createShelfLayoutManager(): GridLayoutManager {
        return GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (shelfListMode || shelfAdapter.isFullSpan(position)) 3 else 1
            }
        }
    }

    private fun applyShelfLayoutMode(updateButton: Boolean = true) {
        if (!::shelfGrid.isInitialized) return
        val layoutManager = (shelfGrid.layoutManager as? GridLayoutManager)
            ?: createShelfLayoutManager().also { shelfGrid.layoutManager = it }
        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
        layoutManager.spanSizeLookup.invalidateSpanGroupIndexCache()
        shelfGrid.recycledViewPool.clear()
        if (updateButton && ::editButton.isInitialized && !shelfSelectionMode) updateShelfModeButton()
        shelfAdapter.notifyDataSetChanged()
        shelfGrid.requestLayout()
    }

    private fun updateShelfModeButton() {
        if (!::editButton.isInitialized || shelfSelectionMode) return
        editButton.text = if (shelfListMode) "列表" else "宫格"
        editButton.contentDescription = if (shelfListMode) {
            "当前列表模式，点击切换为宫格"
        } else {
            "当前宫格模式，点击切换为列表"
        }
    }

    private fun toggleShelfLayoutMode() {
        if (!::shelfGrid.isInitialized || shelfLayoutSwitchInFlight) return
        shelfLayoutSwitchInFlight = true
        shelfGrid.stopScroll()
        shelfGrid.post {
            try {
                if (isFinishing || isDestroyed) return@post
                shelfListMode = !shelfListMode
                getSharedPreferences(SHELF_UI_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(SHELF_LIST_MODE_KEY, shelfListMode)
                    .apply()
                applyShelfLayoutMode()
            } finally {
                shelfLayoutSwitchInFlight = false
            }
        }
    }
'''
if new_methods not in main:
    if old_methods not in main:
        raise SystemExit("v788 shelf mode method anchor missing")
    main = main.replace(old_methods, new_methods, 1)
main_path.write_text(main)

layout_path = Path("app/src/main/res/layout/activity_main.xml")
layout = layout_path.read_text()
old_layout = '''android:id="@+id/editButton"
            android:layout_width="52dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="列表"'''
new_layout = '''android:id="@+id/editButton"
            android:layout_width="52dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="宫格"'''
if new_layout not in layout:
    if old_layout not in layout:
        raise SystemExit("v788 editButton layout anchor missing")
    layout = layout.replace(old_layout, new_layout, 1)
layout_path.write_text(layout)

build_path = Path("app/build.gradle.kts")
build = build_path.read_text()
build = build.replace("2098000787", "2098000788").replace('?: "787"', '?: "788"')
if "2098000788" not in build or '?: "788"' not in build:
    raise SystemExit("v788 version bump failed")
build_path.write_text(build)

old_test_path = Path("app/src/test/java/com/simplereader/app/ui/ShelfV787ContractTest.kt")
old_test = old_test_path.read_text()
old_test = old_test.replace('assertTrue(layout.contains("android:text=\\"列表\\""))', 'assertTrue(layout.contains("android:text=\\"宫格\\""))')
old_test = old_test.replace('assertTrue(main.contains("LinearLayoutManager(this)"))', 'assertTrue(main.contains("GridLayoutManager(this, 3)"))')
old_test = old_test.replace('assertTrue(main.contains("editButton.text = if (shelfListMode) \\"宫格\\" else \\"列表\\""))', 'assertTrue(main.contains("editButton.text = if (shelfListMode) \\"列表\\" else \\"宫格\\""))')
old_test_path.write_text(old_test)

new_test = Path("app/src/test/java/com/simplereader/app/ui/ShelfV788LayoutSwitchContractTest.kt")
new_test.write_text('''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV788LayoutSwitchContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()

    @Test
    fun `mode button names the current layout`() {
        assertTrue(layout.contains("android:text=\\"宫格\\""))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \\"列表\\" else \\"宫格\\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }

    @Test
    fun `list and grid reuse one grid layout manager`() {
        assertTrue(main.contains("private fun createShelfLayoutManager(): GridLayoutManager"))
        assertTrue(main.contains("if (shelfListMode || shelfAdapter.isFullSpan(position)) 3 else 1"))
        val start = main.indexOf("private fun applyShelfLayoutMode")
        val end = main.indexOf("private fun updateShelfModeButton", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("as? GridLayoutManager"))
        assertFalse(block.contains("LinearLayoutManager(this)"))
        assertTrue(block.contains("invalidateSpanIndexCache()"))
        assertTrue(block.contains("recycledViewPool.clear()"))
    }

    @Test
    fun `mode switch is serialized off the click callback`() {
        assertTrue(main.contains("private var shelfLayoutSwitchInFlight = false"))
        val start = main.indexOf("private fun toggleShelfLayoutMode")
        val end = main.indexOf("private fun statusBarHeight", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("shelfLayoutSwitchInFlight"))
        assertTrue(block.contains("shelfGrid.stopScroll()"))
        assertTrue(block.contains("shelfGrid.post"))
        assertTrue(block.contains("shelfListMode = !shelfListMode"))
        assertTrue(block.contains("applyShelfLayoutMode()"))
    }

    @Test
    fun `selection operation mode remains separate from layout mode`() {
        assertTrue(main.contains("handleShelfSelectionPrimaryAction()"))
        assertTrue(main.contains("bookCount > 0 -> \\"操作\\""))
        assertTrue(main.contains("groupCount == 1 && bookCount == 0 -> \\"操作\\""))
    }
}
''')

log = Path("maintenance/V788_SHELF_LAYOUT_SWITCH_CRASH_FIX.md")
log.write_text('''# V788 Shelf Layout Switch Crash Fix

- Baseline: final validated v787 commit `c6576ed83729987ac567d330a971c129df216082`.
- User reproduction: v787 crashes immediately after tapping the shelf list/grid control.
- The supplied Android exit record identifies a Java crash but does not contain the Java stack trace, so v788 does not claim a single proven exception class.
- v788 no longer replaces RecyclerView LayoutManager while the shelf is live. Both modes keep one 3-column GridLayoutManager; grid items span 1 column, list-mode items span all 3 columns and bind the existing full-width list cards.
- Switching is serialized, stops active scrolling, posts the transition to the RecyclerView event queue, invalidates span caches, clears recycled holders, rebinds items, and requests layout.
- Button semantics: current grid = 宫格; current list = 列表. Long-press selection continues to temporarily show 操作.
- Version: 788 / 2098000788.
''')
