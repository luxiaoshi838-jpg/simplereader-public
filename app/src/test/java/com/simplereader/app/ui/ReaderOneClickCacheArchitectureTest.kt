package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderOneClickCacheArchitectureTest {
    private val activity = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val mainActivity = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val worker = File("src/main/java/com/simplereader/app/reader/cache/ReaderPageCacheWorker.kt").readText()
    private val manager = File("src/main/java/com/simplereader/app/reader/cache/ReaderPageCacheManager.kt").readText()
    private val model = File("src/main/java/com/simplereader/app/ui/ReaderPageModel.kt").readText()
    private val indexStore = File("src/main/java/com/simplereader/app/ui/ReaderPageIndexStore.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun openingABookNeverStartsWholeBookLayoutInsideTheActivity() {
        val method = activity.substringAfter("private fun ensureWholeBookPageIndex")
            .substringBefore("private fun ensureVerticalPageIndex")
        assertTrue(method.contains("preparePagedIndexSignature(signature)"))
        assertFalse(method.contains("for (chapter in"))
        assertFalse(method.contains("pagedPagesForChapter(chapter"))
    }

    @Test
    fun bookshelfManagementMenuStartsPersistentOneClickCache() {
        assertTrue(mainActivity.contains("arrayOf(\"批量管理分组\", \"同步书架\", \"一键缓存\")"))
        assertTrue(mainActivity.contains("confirmCacheBookshelf"))
        assertTrue(mainActivity.contains("ReaderPageCacheManager.currentLayoutSignature"))
        assertTrue(mainActivity.contains("ReaderPageCacheManager.enqueue"))
        assertTrue(mainActivity.contains("离开书架或切到后台后任务仍会继续"))
        assertFalse(activity.contains("MENU_CACHE_BOOK"))
        assertTrue(manager.contains("OneTimeWorkRequestBuilder<ReaderPageCacheWorker>"))
        assertTrue(manager.contains("enqueueUniqueWork"))
        assertTrue(manager.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("CACHE_MUTEX.withLock"))
    }

    @Test
    fun workerIsForegroundAndPersistsOnlyPageStarts() {
        assertTrue(worker.contains("CoroutineWorker"))
        assertTrue(worker.contains("setForeground("))
        assertTrue(worker.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(worker.contains("ReaderPageBoundaryCalculator.calculate"))
        assertTrue(worker.contains("ReaderPageIndexStore"))
        assertFalse(worker.contains("ReaderPageSnapshot("))
        assertTrue(model.contains("data class ReaderPageBoundaries"))
        assertTrue(model.contains("page start anchors"))
    }

    @Test
    fun completedUnchangedBookSkipsBeforeDirectoryRecognition() {
        val txtMethod = worker.substringAfter("private suspend fun cacheTxt")
            .substringBefore("private suspend fun cacheStructured")
        assertTrue(txtMethod.indexOf("completeChapterCount") < txtMethod.indexOf("TxtParser.scanChapters"))
        assertTrue(txtMethod.indexOf("completeChapterCount") < txtMethod.indexOf("TxtParser.detectCharset"))
        assertTrue(worker.contains("原文件未变化，已跳过"))
        assertTrue(indexStore.contains("fun completeChapterCount"))
        assertTrue(indexStore.contains("optBoolean(\"complete\", false)"))
        assertFalse(indexStore.substringAfter("fun completeChapterCount")
            .substringBefore("fun load(").contains("optJSONArray(\"chapters\")"))
    }

    @Test
    fun directoryRecognitionNeverForcesVisiblePageRelayout() {
        assertTrue(activity.contains("TXT_INITIAL_WINDOW_BYTES = 32 * 1024"))
        assertTrue(activity.contains("txtCatalogChapters"))
        val scanMethod = activity.substringAfter("private fun scanStreamingTxtChapters")
            .substringBefore("private fun txtChapterCacheFile")
        assertFalse(scanMethod.contains("displayContent()"))
        assertFalse(scanMethod.contains("readerPageCache.clear()"))
        assertFalse(scanMethod.contains("resetPagedPageIndex()"))
    }

    @Test
    fun target35ForegroundServiceTypeIsDeclared() {
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("SystemForegroundService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
    }

    @Test
    fun txtDirectoryCacheLivesInFilesDirInsteadOfEvictableCacheDir() {
        assertTrue(activity.contains("TxtChapterIndexStore.file(this, bookId)"))
        val store = File("src/main/java/com/simplereader/app/ui/TxtChapterIndexStore.kt").readText()
        assertTrue(store.contains("context.filesDir"))
        assertFalse(store.contains("context.cacheDir"))
    }
}
