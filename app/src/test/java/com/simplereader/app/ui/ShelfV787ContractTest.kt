package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV787ContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()
    private val group = File("src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt").readText()

    @Test fun `top-level groups and books share one activity sort`() {
        assertTrue(main.contains("val topLevelItems = mutableListOf<Pair<Long, ShelfRenderItem>>()"))
        assertTrue(main.contains("topLevelItems += groupBooks.maxOf(::activityTime) to ShelfRenderItem.GroupItem"))
        assertTrue(main.contains("topLevelItems += activityTime(book) to ShelfRenderItem.BookItem(book)"))
        assertTrue(main.contains("compareByDescending<Pair<Long, ShelfRenderItem>> { it.first }"))
    }

    @Test fun `grid-list control remains distinct from selection operation`() {
        assertTrue(layout.contains("android:text=\"列表\""))
        assertTrue(main.contains("private var shelfListMode = false"))
        assertTrue(main.contains("toggleShelfLayoutMode()"))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"宫格\" else \"列表\""))
        assertTrue(main.contains("bookCount > 0 -> \"操作\""))
        assertTrue(main.contains("groupCount == 1 && bookCount == 0 -> \"操作\""))
    }

    @Test fun `main shelf grid uses same portrait height as books inside groups`() {
        assertTrue(group.contains("LinearLayout.LayoutParams.MATCH_PARENT, dp(148)"))
        assertTrue(main.contains("LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148))"))
        assertTrue(main.contains("card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)))"))
        assertTrue(main.contains("LinearLayout.LayoutParams(dp(72), dp(104))"))
    }

    @Test fun `shelf management no longer duplicates recent crash history`() {
        assertFalse(main.contains("\"异常日志（最近20条）\""))
        assertTrue(main.contains("CrashLogStore.consumePendingIntoHistory(this)"))
        assertTrue(main.contains("setNeutralButton(\"最近20条\")"))
    }
}
