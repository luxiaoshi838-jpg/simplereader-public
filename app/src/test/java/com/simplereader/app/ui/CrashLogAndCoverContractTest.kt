package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogAndCoverContractTest {
    @Test
    fun `plain text uses generic cover without txt badge while epub real cover still wins`() {
        val assets = File("src/main/java/com/simplereader/app/ui/BookCoverAssets.kt").readText()
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val group = File("src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt").readText()
        assertTrue(assets.contains("book_cover_default_generic"))
        assertFalse(assets.contains("book_cover_default_txt"))
        assertTrue(assets.contains("book_cover_default_epub"))
        assertTrue(main.contains("BookCoverAssets.drawable("))
        assertTrue(group.contains("BookCoverAssets.drawable("))
        assertTrue(main.contains("EpubParser.readCoverImage"))
        assertTrue(main.contains("fallback.visibility = View.GONE"))
        assertTrue(group.contains("coverFallback.visibility = View.GONE"))
        assertFalse(main.contains("PaperCoverDrawable"))
        assertFalse(group.contains("PaperCoverDrawable"))
    }

    @Test
    fun `uncaught crash is presented once and retained in twenty entry history`() {
        val app = File("src/main/java/com/simplereader/app/App.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val store = File("src/main/java/com/simplereader/app/crash/CrashLogStore.kt").readText()
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        assertTrue(app.contains("CrashLogStore.install(this)"))
        assertTrue(manifest.contains("android:name=\".App\""))
        assertTrue(store.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(store.contains("pending_crash_log.txt"))
        assertTrue(store.contains("CRASH_HISTORY_LIMIT = 20"))
        assertTrue(store.contains("consumePendingIntoHistory"))
        assertTrue(store.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(store.contains("pending.delete()"))
        assertTrue(main.contains("CrashLogStore.consumePendingIntoHistory(this)"))
        assertTrue(main.contains("新异常退出/闪退/崩溃日志"))
        assertFalse(main.contains("\"异常日志（最近20条）\""))
        assertTrue(main.contains("日志已复制；历史记录仍保留"))
        assertFalse(main.contains("复制并清除"))
        assertFalse(main.contains("CrashLogStore.clear(this)"))
    }

    @Test
    fun `normalized txt cache is not read twice on reopen`() {
        val store = File("src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt").readText()
        val loader = File("src/main/java/com/simplereader/app/reader/ReaderDocumentLoader.kt").readText()
        assertTrue(store.contains("val text: String"))
        val txtSection = loader.substring(
            loader.indexOf("private fun loadTxt"),
            loader.indexOf("private fun loadEpub")
        )
        assertTrue(txtSection.contains("val text = cached.text"))
        assertFalse(txtSection.contains("cached.textFile.readText"))
    }
}
