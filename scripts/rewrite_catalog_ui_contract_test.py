from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/test/java/com/simplereader/app/ui/ReaderCatalogAndProgressUiContractTest.kt"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(
    r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatalogAndProgressUiContractTest {
    private fun readerSource(): String =
        File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    private fun readerLayout(): String =
        File("src/main/res/layout/activity_reader.xml").readText()

    @Test
    fun topSearchUsesMagnifyingGlassIcon() {
        val text = readerSource()
        assertTrue(text.contains("setIcon(android.R.drawable.ic_menu_search)"))
    }

    @Test
    fun bottomReaderProgressIsPageLabelWithoutSeekbar() {
        val source = readerSource()
        val layout = readerLayout()
        assertFalse(source.contains("readerProgressBar"))
        assertFalse(layout.contains("@+id/readerProgressBar"))
        assertTrue(layout.contains("android:text=\"1/1\""))
        assertTrue(source.contains("private fun pageCountLabel()"))
        assertTrue(source.contains("return \"$currentPage/$totalPages\""))
    }
}
''',
    encoding="utf-8",
)
print("catalog UI contract test escaping fixed")
