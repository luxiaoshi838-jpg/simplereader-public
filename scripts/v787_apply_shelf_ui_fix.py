#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
BUILD = ROOT / "app/build.gradle.kts"
CRASH_TEST = ROOT / "app/src/test/java/com/simplereader/app/ui/CrashLogAndCoverContractTest.kt"
V787_TEST = ROOT / "app/src/test/java/com/simplereader/app/ui/ShelfV787ContractTest.kt"
LOG = ROOT / "maintenance/V787_SHELF_ORDER_LAYOUT_MODE.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str, flags: int = 0) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    return updated


main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    "import androidx.recyclerview.widget.GridLayoutManager\nimport androidx.recyclerview.widget.RecyclerView",
    "import androidx.recyclerview.widget.GridLayoutManager\nimport androidx.recyclerview.widget.LinearLayoutManager\nimport androidx.recyclerview.widget.RecyclerView",
    "LinearLayoutManager import",
)
main = replace_once(
    main,
    "    private var showingHistory = false\n    private var shelfSearchQuery = \"\"",
    "    private var showingHistory = false\n    private var shelfListMode = false\n    private var shelfSearchQuery = \"\"",
    "shelfListMode field",
)

old_layout_setup = '''        shelfGrid = findViewById(R.id.shelfGrid)\n        val shelfLayoutManager = GridLayoutManager(this, 3)\n        shelfLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {\n            override fun getSpanSize(position: Int): Int = if (shelfAdapter.isFullSpan(position)) 3 else 1\n        }\n        shelfGrid.layoutManager = shelfLayoutManager\n        shelfGrid.adapter = shelfAdapter\n        shelfGrid.itemAnimator = null\n        shelfGrid.setItemViewCacheSize(12)\n        ShelfFastScroller.attach(shelfGrid)'''
new_layout_setup = '''        shelfGrid = findViewById(R.id.shelfGrid)\n        shelfGrid.adapter = shelfAdapter\n        shelfGrid.itemAnimator = null\n        shelfGrid.setItemViewCacheSize(12)\n        shelfListMode = getSharedPreferences(SHELF_UI_PREFS, Context.MODE_PRIVATE)\n            .getBoolean(SHELF_LIST_MODE_KEY, false)\n        applyShelfLayoutMode(updateButton = false)\n        ShelfFastScroller.attach(shelfGrid)'''
main = replace_once(main, old_layout_setup, new_layout_setup, "RecyclerView layout setup")

old_edit = '''        editButton = findViewById<TextView>(R.id.editButton).apply {\n            text = \"编辑\"\n            setOnClickListener {\n                if (shelfSelectionMode) {\n                    handleShelfSelectionPrimaryAction()\n                } else {\n                    Toast.makeText(this@MainActivity, \"长按书籍或分组可批量选择\", Toast.LENGTH_SHORT).show()\n                }\n            }\n        }'''
new_edit = '''        editButton = findViewById<TextView>(R.id.editButton).apply {\n            setOnClickListener {\n                if (shelfSelectionMode) {\n                    handleShelfSelectionPrimaryAction()\n                } else {\n                    toggleShelfLayoutMode()\n                }\n            }\n        }\n        updateShelfModeButton()'''
main = replace_once(main, old_edit, new_edit, "edit button -> layout mode toggle")

layout_helpers = '''    private fun applyShelfLayoutMode(updateButton: Boolean = true) {\n        shelfGrid.layoutManager = if (shelfListMode) {\n            LinearLayoutManager(this)\n        } else {\n            GridLayoutManager(this, 3).apply {\n                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {\n                    override fun getSpanSize(position: Int): Int = if (shelfAdapter.isFullSpan(position)) 3 else 1\n                }\n            }\n        }\n        if (updateButton && ::editButton.isInitialized && !shelfSelectionMode) updateShelfModeButton()\n        if (::shelfGrid.isInitialized) shelfAdapter.notifyDataSetChanged()\n    }\n\n    private fun updateShelfModeButton() {\n        if (!::editButton.isInitialized || shelfSelectionMode) return\n        editButton.text = if (shelfListMode) \"宫格\" else \"列表\"\n        editButton.contentDescription = if (shelfListMode) \"切换为宫格模式\" else \"切换为列表模式\"\n    }\n\n    private fun toggleShelfLayoutMode() {\n        shelfListMode = !shelfListMode\n        getSharedPreferences(SHELF_UI_PREFS, Context.MODE_PRIVATE)\n            .edit()\n            .putBoolean(SHELF_LIST_MODE_KEY, shelfListMode)\n            .apply()\n        applyShelfLayoutMode()\n    }\n\n'''
main = replace_once(
    main,
    "    private fun statusBarHeight(): Int {",
    layout_helpers + "    private fun statusBarHeight(): Int {",
    "layout mode helper functions",
)

old_bind = '''            if (boundItem is ShelfRenderItem.EmptyItem) {\n                holder.container.setPadding(0, 0, 0, 0)\n            } else {\n                // The historical createShelfCard margins were lost when its LayoutParams were replaced.\n                // Put the intended normal-shelf spacing on the stable ViewHolder container instead.\n                holder.container.setPadding(dp(3), 0, dp(3), dp(18))\n            }\n            val child = when (val item = boundItem) {\n                is ShelfRenderItem.GroupItem -> buildGroupCard(item.group, item.books)\n                is ShelfRenderItem.BookItem -> buildBookCard(item.book)\n                is ShelfRenderItem.EmptyItem -> buildEmptyText(item.message)\n            }'''
new_bind = '''            if (boundItem is ShelfRenderItem.EmptyItem) {\n                holder.container.setPadding(0, 0, 0, 0)\n            } else if (shelfListMode) {\n                holder.container.setPadding(0, 0, 0, dp(10))\n            } else {\n                // Keep the same three-column spacing as the books shown inside a group.\n                holder.container.setPadding(dp(3), 0, dp(3), dp(18))\n            }\n            val child = when (val item = boundItem) {\n                is ShelfRenderItem.GroupItem -> if (shelfListMode) buildGroupListCard(item.group, item.books) else buildGroupCard(item.group, item.books)\n                is ShelfRenderItem.BookItem -> if (shelfListMode) buildBookListCard(item.book) else buildBookCard(item.book)\n                is ShelfRenderItem.EmptyItem -> buildEmptyText(item.message)\n            }'''
main = replace_once(main, old_bind, new_bind, "adapter grid/list binding")

old_top_level = '''        val booksByGroup = visibleBooks.groupBy { it.groupId }\n        groups.mapNotNull { group ->\n            val groupBooks = booksByGroup[group.id].orEmpty().sortedByDescending(::activityTime)\n            if (groupBooks.isEmpty()) null else group to groupBooks\n        }.sortedByDescending { (_, groupBooks) -> groupBooks.maxOf(::activityTime) }\n            .forEach { (group, groupBooks) -> renderItems += ShelfRenderItem.GroupItem(group, groupBooks) }\n\n        renderItems += booksByGroup[null].orEmpty()\n            .sortedByDescending(::activityTime)\n            .map(ShelfRenderItem::BookItem)'''
new_top_level = '''        val booksByGroup = visibleBooks.groupBy { it.groupId }\n        val topLevelItems = mutableListOf<Pair<Long, ShelfRenderItem>>()\n        groups.mapNotNull { group ->\n            val groupBooks = booksByGroup[group.id].orEmpty().sortedByDescending(::activityTime)\n            if (groupBooks.isEmpty()) null else group to groupBooks\n        }.forEach { (group, groupBooks) ->\n            topLevelItems += groupBooks.maxOf(::activityTime) to ShelfRenderItem.GroupItem(group, groupBooks)\n        }\n        booksByGroup[null].orEmpty().forEach { book ->\n            topLevelItems += activityTime(book) to ShelfRenderItem.BookItem(book)\n        }\n        renderItems += topLevelItems\n            .sortedWith(\n                compareByDescending<Pair<Long, ShelfRenderItem>> { it.first }\n                    .thenBy { (_, item) ->\n                        when (item) {\n                            is ShelfRenderItem.GroupItem -> item.group.displayName.ifBlank { item.group.name }\n                            is ShelfRenderItem.BookItem -> item.book.title\n                            is ShelfRenderItem.EmptyItem -> item.message\n                        }\n                    }\n            )\n            .map { it.second }'''
main = replace_once(main, old_top_level, new_top_level, "unified group/book activity sorting")

list_cards = '''    private fun buildGroupListCard(group: BookGroup, groupBooks: List<ShelfBookItem>): View {\n        val sortedBooks = groupBooks.sortedByDescending(::activityTime)\n        val card = LinearLayout(this).apply {\n            orientation = LinearLayout.HORIZONTAL\n            gravity = Gravity.CENTER_VERTICAL\n            minimumHeight = dp(104)\n        }\n        val preview = GridLayout(this).apply {\n            columnCount = 2\n            rowCount = 2\n            setPadding(dp(4), dp(4), dp(4), dp(4))\n            setBackgroundColor(Color.rgb(232, 229, 220))\n            val previewBooks = sortedBooks.take(4)\n            repeat(4) { index ->\n                val book = previewBooks.getOrNull(index)\n                val child = if (book != null) {\n                    createBookCover(book, compact = true).apply { layoutParams = groupPreviewLayoutParams(index) }\n                } else {\n                    TextView(this@MainActivity).apply {\n                        setBackgroundColor(Color.rgb(205, 202, 194))\n                        layoutParams = groupPreviewLayoutParams(index)\n                    }\n                }\n                addView(child)\n            }\n        }\n        card.addView(preview, LinearLayout.LayoutParams(dp(72), dp(104)))\n        card.addView(LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            gravity = Gravity.CENTER_VERTICAL\n            setPadding(dp(14), 0, dp(8), 0)\n            addView(TextView(this@MainActivity).apply {\n                text = group.displayName.ifBlank { group.name }\n                textSize = 17f\n                maxLines = 2\n                setTextColor(ReaderAppearance.shelfTextColor(this@MainActivity))\n            })\n            addView(TextView(this@MainActivity).apply {\n                text = \"共 ${sortedBooks.size} 本\"\n                textSize = 13f\n                setTextColor(ReaderAppearance.shelfSecondaryTextColor(this@MainActivity))\n            })\n        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))\n        card.setOnClickListener {\n            if (shelfSelectionMode) toggleShelfGroupSelection(group.id) else showGroupBooksV2(group, sortedBooks)\n        }\n        card.setOnLongClickListener {\n            enterShelfSelectionMode()\n            toggleShelfGroupSelection(group.id)\n            true\n        }\n        return wrapSelectableShelfCard(card, selectedShelfGroupIds.contains(group.id))\n    }\n\n    private fun buildBookListCard(book: ShelfBookItem): View {\n        val card = LinearLayout(this).apply {\n            orientation = LinearLayout.HORIZONTAL\n            gravity = Gravity.CENTER_VERTICAL\n            minimumHeight = dp(104)\n        }\n        val cover = createBookCover(book, compact = false).apply {\n            layoutParams = LinearLayout.LayoutParams(dp(72), dp(104))\n        }\n        card.addView(cover)\n        card.addView(LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            gravity = Gravity.CENTER_VERTICAL\n            setPadding(dp(14), 0, dp(8), 0)\n            addView(TextView(this@MainActivity).apply {\n                text = book.title\n                textSize = 17f\n                maxLines = 2\n                setTextColor(ReaderAppearance.shelfTextColor(this@MainActivity))\n            })\n            addView(TextView(this@MainActivity).apply {\n                val status = if (book.fileStatus == \"AVAILABLE\") \"\" else \" · ${book.fileStatus}\"\n                text = \"已读 ${book.progressPercent()}%$status\"\n                textSize = 13f\n                setTextColor(ReaderAppearance.shelfSecondaryTextColor(this@MainActivity))\n            })\n        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))\n        card.setOnClickListener {\n            if (shelfSelectionMode) toggleShelfBookSelection(book.id) else openBook(book.id)\n        }\n        card.setOnLongClickListener {\n            enterShelfSelectionMode()\n            toggleShelfBookSelection(book.id)\n            true\n        }\n        return wrapSelectableShelfCard(card, selectedShelfBookIds.contains(book.id))\n    }\n\n'''
main = replace_once(main, "    private fun buildGroupCard(group: BookGroup, groupBooks: List<ShelfBookItem>): View {", list_cards + "    private fun buildGroupCard(group: BookGroup, groupBooks: List<ShelfBookItem>): View {", "list card builders")

main = replace_once(
    main,
    "        card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)))",
    "        card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)))",
    "group grid cover height",
)
main = replace_once(
    main,
    "            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112))",
    "            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148))",
    "book grid cover height",
)
main = replace_once(
    main,
    "                        topMargin = dp(84)",
    "                        topMargin = if (shelfListMode) dp(38) else dp(120)",
    "selection marker position",
)

main = regex_once(
    main,
    r'        editButton\.text = "(?:\\u7f16\\u8f91|编辑)"',
    "        updateShelfModeButton()",
    "restore layout-mode label after selection",
)

old_more = '''            .setItems(arrayOf("书架目录缓存", "批量管理分组", "同步书架", "异常日志（最近20条）")) { _, which ->\n                when (which) {\n                    0 -> showShelfCacheOptions()\n                    1 -> showBatchGroupManagement()\n                    2 -> confirmSyncBookshelf()\n                    3 -> showCrashHistoryDialog()\n                }\n            }'''
new_more = '''            .setItems(arrayOf("书架目录缓存", "批量管理分组", "同步书架")) { _, which ->\n                when (which) {\n                    0 -> showShelfCacheOptions()\n                    1 -> showBatchGroupManagement()\n                    2 -> confirmSyncBookshelf()\n                }\n            }'''
main = replace_once(main, old_more, new_more, "remove duplicate crash-history shelf-management entry")

companion_anchor = '''    companion object {\n'''
if companion_anchor in main:
    main = replace_once(
        main,
        companion_anchor,
        '''    companion object {\n        private const val SHELF_UI_PREFS = "shelf_ui"\n        private const val SHELF_LIST_MODE_KEY = "list_mode"\n''',
        "shelf preference constants",
    )
else:
    raise SystemExit("companion object anchor missing")

MAIN.write_text(main, encoding="utf-8")

layout = LAYOUT.read_text(encoding="utf-8")
layout = replace_once(layout, 'android:id="@+id/editButton"', 'android:id="@+id/editButton"', "edit button id presence")
layout = replace_once(layout, 'android:text="编辑"', 'android:text="列表"', "default toggle label")
LAYOUT.write_text(layout, encoding="utf-8")

build = BUILD.read_text(encoding="utf-8")
build = replace_once(build, '"2098000786"', '"2098000787"', "versionCode env default")
build = replace_once(build, '?: 2098000786', '?: 2098000787', "versionCode fallback")
build = replace_once(build, '?: "786"', '?: "787"', "versionName")
BUILD.write_text(build, encoding="utf-8")

crash_test = CRASH_TEST.read_text(encoding="utf-8")
crash_test = replace_once(
    crash_test,
    '        assertTrue(main.contains("异常日志（最近20条）"))',
    '        assertFalse(main.contains("\\\"异常日志（最近20条）\\\""))',
    "crash history menu regression assertion",
)
CRASH_TEST.write_text(crash_test, encoding="utf-8")

V787_TEST.write_text(r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV787ContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()
    private val group = File("src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt").readText()

    @Test
    fun `top-level groups and books share one activity sort`() {
        assertTrue(main.contains("val topLevelItems = mutableListOf<Pair<Long, ShelfRenderItem>>()"))
        assertTrue(main.contains("topLevelItems += groupBooks.maxOf(::activityTime) to ShelfRenderItem.GroupItem"))
        assertTrue(main.contains("topLevelItems += activityTime(book) to ShelfRenderItem.BookItem(book)"))
        assertTrue(main.contains("compareByDescending<Pair<Long, ShelfRenderItem>> { it.first }"))
        assertFalse(main.contains(".sortedByDescending { (_, groupBooks) -> groupBooks.maxOf(::activityTime) }"))
    }

    @Test
    fun `edit control is now persistent grid-list toggle while selection still uses operation`() {
        assertTrue(layout.contains("android:text=\"列表\""))
        assertTrue(main.contains("private var shelfListMode = false"))
        assertTrue(main.contains("LinearLayoutManager(this)"))
        assertTrue(main.contains("toggleShelfLayoutMode()"))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"宫格\" else \"列表\""))
        assertTrue(main.contains("bookCount > 0 -> \"操作\""))
        assertTrue(main.contains("groupCount == 1 && bookCount == 0 -> \"操作\""))
    }

    @Test
    fun `main shelf grid uses same portrait height as books inside groups`() {
        assertTrue(group.contains("LinearLayout.LayoutParams.MATCH_PARENT, dp(148)"))
        assertTrue(main.contains("LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148))"))
        assertTrue(main.contains("card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)))"))
        assertTrue(main.contains("LinearLayout.LayoutParams(dp(72), dp(104))"))
    }

    @Test
    fun `shelf management no longer duplicates recent crash history`() {
        assertFalse(main.contains("\"异常日志（最近20条）\""))
        assertTrue(main.contains("CrashLogStore.consumePendingIntoHistory(this)"))
        assertTrue(main.contains("setNeutralButton(\"最近20条\")"))
    }
}
''', encoding="utf-8")

LOG.parent.mkdir(parents=True, exist_ok=True)
LOG.write_text('''# v787 书架排序、外形与宫格/列表修复\n\n## 用户要求\n\n1. 顶层书籍与分组同权排序：不再固定“分组在前、书籍在后”；按最近活动时间统一排序。\n2. 主书架宫格封面比例对齐分组内书籍：主页书籍与分组封面高度从 112dp 调整为 148dp。\n3. “书架管理”去掉“异常日志（最近20条）”入口；崩溃历史存储和新崩溃弹窗仍保留，数据导出日志能力不动。\n4. 顶部原“编辑”按钮改为“列表/宫格”切换：默认宫格；点击切列表；再次点击回宫格；选择模式仍由长按书籍/分组进入，并继续让该位置显示“操作/删除”。\n\n## 实现\n\n- 顶层分组的排序时间 = 该分组中最近活动书籍的 activityTime。\n- 顶层未分组书籍的排序时间 = 书籍 activityTime。两者放入同一个 topLevelItems 后一次排序。\n- activityTime 继续沿用既有定义 max(lastReadTime, addTime)，没有另造排序时间口径。\n- 宫格继续三列；列表使用 LinearLayoutManager，并使用 72x104dp 纵向缩略图 + 右侧文字。\n- 宫格/列表偏好写入 SharedPreferences，首次安装/没有偏好时保持默认宫格。\n- 长按选择逻辑没有改入口；退出选择模式后按钮恢复当前布局对应的“列表/宫格”。\n\n## 回归保护\n\n- 新增 ShelfV787ContractTest。\n- 更新 CrashLogAndCoverContractTest：历史日志仍保留，但不再要求书架管理菜单存在重复入口。\n- 正式构建仍必须满足 SIGNING_POLICY.md：包名 com.simplereader.app、固定 Public V1 证书指纹、R8/资源压缩及版本号校验。\n''', encoding="utf-8")

print("v787 shelf patch applied")
