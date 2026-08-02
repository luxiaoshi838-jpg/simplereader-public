from pathlib import Path


def require_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


layout_path = Path("app/src/main/res/layout/activity_reader.xml")
layout = layout_path.read_text()
layout = require_replace(
    layout,
    '<com.simplereader.app.ui.ReaderViewportFrameLayout xmlns:android="http://schemas.android.com/apk/res/android"',
    '<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"',
    "reader root",
)
layout = require_replace(
    layout,
    '</com.simplereader.app.ui.ReaderViewportFrameLayout>',
    '</FrameLayout>',
    "reader root close",
)
layout = layout.replace('android:scrollbars="vertical"', 'android:scrollbars="none"')
layout_path.write_text(layout)

reader_path = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
reader = reader_path.read_text()
reader = require_replace(
    reader,
    '''        val listView = ListView(this).apply {
            adapter = listAdapter
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
''',
    '''        val listView = ListView(this).apply {
            adapter = listAdapter
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
''',
    "reader search ListView scrollbars",
)
reader = require_replace(
    reader,
    '''        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            this.layoutManager = layoutManager
            itemAnimator = null
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
''',
    '''        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            this.layoutManager = layoutManager
            itemAnimator = null
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
''',
    "reader search RecyclerView scrollbars",
)
reader_path.write_text(reader)

test_path = Path("app/src/test/java/com/simplereader/app/ui/ReaderStartupContractTest.kt")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text('''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStartupContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `reader layout uses an existing root and never enables framework scrollbars`() {
        val layout = source("src/main/res/layout/activity_reader.xml")
        assertTrue(layout.contains("<FrameLayout"))
        assertFalse(layout.contains("ReaderViewportFrameLayout"))
        assertFalse(layout.contains("android:scrollbars=\\\"vertical\\\""))
        assertTrue(layout.contains("android:scrollbars=\\\"none\\\""))
    }

    @Test
    fun `reader code never initializes framework scrollbar drawables`() {
        val reader = source("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        val vertical = source("src/main/java/com/simplereader/app/ui/VerticalPageFlowView.kt")
        assertFalse(reader.contains("isScrollbarFadingEnabled = false"))
        assertFalse(reader.contains("isVerticalScrollBarEnabled = true"))
        assertFalse(vertical.contains("isScrollbarFadingEnabled = false"))
        assertFalse(vertical.contains("isVerticalScrollBarEnabled = true"))
    }
}
''')
