from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
main_path = ROOT / "app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
build_path = ROOT / "app/build.gradle.kts"
v788_test_path = ROOT / "app/src/test/java/com/simplereader/app/ui/ShelfV788LayoutSwitchContractTest.kt"
test_path = ROOT / "app/src/test/java/com/simplereader/app/ui/ShelfV790LayoutSafetyContractTest.kt"
maintenance_path = ROOT / "maintenance/V790_SHELF_LAYOUT_ARCH_FIX.md"

main = main_path.read_text(encoding="utf-8")

old_wrap = '''    private fun wrapSelectableShelfCard(card: LinearLayout, selected: Boolean): FrameLayout {
        val originalParams = card.layoutParams
        card.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        return FrameLayout(this).apply {
            layoutParams = originalParams
            addView(card)
            if (shelfSelectionMode) {
'''
new_wrap = '''    private fun wrapSelectableShelfCard(card: LinearLayout, selected: Boolean): FrameLayout {
        // A freshly constructed card is not attached to a parent yet, so card.layoutParams may be null.
        // The outer wrapper receives its RecyclerView child params in ShelfAdapter.onBindViewHolder().
        // Never copy an unattached child's layoutParams onto the wrapper (Android 16 rejects null params).
        card.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        return FrameLayout(this).apply {
            addView(card)
            if (shelfSelectionMode) {
'''
if old_wrap not in main:
    raise SystemExit("wrapSelectableShelfCard source block not found; aborting")
main = main.replace(old_wrap, new_wrap, 1)

pattern = re.compile(
    r'''    private fun toggleShelfLayoutMode\(\) \{.*?\n    \}\n\n    private fun statusBarHeight\(\): Int \{''',
    re.S,
)
replacement = '''    private fun toggleShelfLayoutMode() {
        if (!::shelfGrid.isInitialized || shelfLayoutSwitchInFlight) return
        shelfLayoutSwitchInFlight = true
        shelfGrid.stopScroll()
        shelfGrid.recycledViewPool.clear()
        shelfListMode = !shelfListMode
        getSharedPreferences(SHELF_UI_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SHELF_LIST_MODE_KEY, shelfListMode)
            .commit()

        // Follow the mature bookshelf pattern used by projects such as Legado:
        // a layout-mode change recreates the shelf so the new Activity constructs exactly one
        // matching LayoutManager/view hierarchy. Do not hot-swap list/grid cards in-place.
        recreate()
    }

    private fun statusBarHeight(): Int {'''
main, count = pattern.subn(replacement, main, count=1)
if count != 1:
    raise SystemExit(f"toggleShelfLayoutMode replacement count={count}; aborting")

if "layoutParams = originalParams" in main or "val originalParams = card.layoutParams" in main:
    raise SystemExit("unsafe layoutParams copy still present")

main_path.write_text(main, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = build.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000789"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000790"')
build = build.replace('?: 2098000789', '?: 2098000790')
build = build.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "789"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "790"')
if '2098000790' not in build or '\"790\"' not in build:
    raise SystemExit("version bump failed")
build_path.write_text(build, encoding="utf-8")

v788_test = v788_test_path.read_text(encoding="utf-8")
old_v788 = '''    @Test fun `mode switch is serialized off the click callback`() {
        val start = main.indexOf("private fun toggleShelfLayoutMode")
        val end = main.indexOf("private fun statusBarHeight", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("shelfLayoutSwitchInFlight"))
        assertTrue(block.contains("shelfGrid.stopScroll()"))
        assertTrue(block.contains("shelfGrid.post"))
        assertTrue(block.contains("shelfListMode = !shelfListMode"))
        assertTrue(block.contains("applyShelfLayoutMode()"))
    }
'''
new_v788 = '''    @Test fun `mode switch is serialized and rebuilds the shelf hierarchy`() {
        val start = main.indexOf("private fun toggleShelfLayoutMode")
        val end = main.indexOf("private fun statusBarHeight", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("shelfLayoutSwitchInFlight"))
        assertTrue(block.contains("shelfGrid.stopScroll()"))
        assertTrue(block.contains("shelfGrid.recycledViewPool.clear()"))
        assertTrue(block.contains("shelfListMode = !shelfListMode"))
        assertTrue(block.contains("recreate()"))
        assertTrue(!block.contains("shelfGrid.post"))
        assertTrue(!block.contains("applyShelfLayoutMode()"))
    }
'''
if old_v788 not in v788_test:
    raise SystemExit("v788 hot-switch contract block not found; aborting")
v788_test_path.write_text(v788_test.replace(old_v788, new_v788, 1), encoding="utf-8")

test_path.write_text(r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV790LayoutSafetyContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()

    @Test fun `selection wrapper never restores nullable unattached layout params`() {
        assertFalse(main.contains("val originalParams = card.layoutParams"))
        assertFalse(main.contains("layoutParams = originalParams"))
        assertTrue(main.contains("card.layoutParams = FrameLayout.LayoutParams("))
        assertTrue(main.contains("child.layoutParams = FrameLayout.LayoutParams("))
    }

    @Test fun `list grid change recreates shelf instead of hot swapping active recycler hierarchy`() {
        val start = main.indexOf("private fun toggleShelfLayoutMode()")
        val end = main.indexOf("private fun statusBarHeight()", start)
        assertTrue(start >= 0 && end > start)
        val toggle = main.substring(start, end)
        assertTrue(toggle.contains("shelfGrid.stopScroll()"))
        assertTrue(toggle.contains("shelfGrid.recycledViewPool.clear()"))
        assertTrue(toggle.contains("putBoolean(SHELF_LIST_MODE_KEY, shelfListMode)"))
        assertTrue(toggle.contains("recreate()"))
        assertFalse(toggle.contains("applyShelfLayoutMode()"))
        assertFalse(toggle.contains("shelfGrid.post"))
    }

    @Test fun `mode button continues to describe current mode`() {
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"列表\" else \"宫格\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }
}
''', encoding="utf-8")

maintenance_path.parent.mkdir(parents=True, exist_ok=True)
maintenance_path.write_text('''# v790 书架列表/宫格崩溃修复\n\n## 实机根因\n\nv787、v788、v789 在 Android 16/API 36 的实机日志均指向同一异常：`java.lang.NullPointerException: Layout parameters cannot be null`，调用链为 `wrapSelectableShelfCard -> buildBookListCard -> ShelfAdapter.onBindViewHolder`。根因不是 `GridLayoutManager` 或 `LinearLayoutManager` 本身，而是 `wrapSelectableShelfCard()` 读取尚未挂到父容器的 `card.layoutParams`（允许为 null），随后把它赋给新 `FrameLayout`。Android 16 在 `View.setLayoutParams(null)` 处直接崩溃。\n\n## v790 修复\n\n1. 删除 `originalParams` 的读取与恢复。内部 card 显式获得 `FrameLayout.LayoutParams`；外层 wrapper 的参数继续只由 `ShelfAdapter.onBindViewHolder()` 在加入真实父容器前设置。\n2. 列表/宫格切换不再对正在显示的 RecyclerView 热替换层级。保存新模式、停止滚动、清空 recycled pool 后调用 `Activity.recreate()`，由新 Activity 一次性创建匹配模式的 `LinearLayoutManager` 或 `GridLayoutManager`。\n3. 新增 `ShelfV790LayoutSafetyContractTest`，硬性禁止恢复 nullable layoutParams，并禁止切换函数再次调用 `applyShelfLayoutMode()` 做热切换。\n4. 同步 v788 历史契约：旧测试不再要求已经被实机证明不可靠的 `post + applyShelfLayoutMode()` 热切换。\n\n## 成熟实现参考\n\n参考 `LegadoTeam/legado` 的书架实现：列表/宫格分别创建匹配的 Adapter/LayoutManager；书架布局配置改变时清理对应 recycled pool 并触发界面 recreate，而不是在活跃 RecyclerView 上复用不同模式的 item 层级。\n\n## 验收要求\n\n- Debug/Release 定向测试通过。\n- 全量单测相对历史 known-failure baseline 不新增失败。\n- Debug/Release APK 均可构建。\n- Android 35 自动验收必须预置真实书籍/分组后执行连续模式切换；空书架按钮点击不再视为充分验证。\n''', encoding="utf-8")

print("v790 shelf fix applied")
