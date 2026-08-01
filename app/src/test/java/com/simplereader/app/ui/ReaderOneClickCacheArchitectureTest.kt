package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderOneClickCacheArchitectureTest {
    private val activity = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val worker = File("src/main/java/com/simplereader/app/reader/cache/ReaderPageCacheWorker.kt").readText()
    private val manager = File("src/main/java/com/simplereader/app/reader/cache/ReaderPageCacheManager.kt").readText()
    private val model = File("src/main/java/com/simplereader/app/ui/ReaderPageModel.kt").readText()
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
    fun overflowMenuStartsPersistentOneClickCache() {
        assertTrue(activity.contains("MENU_CACHE_BOOK"))
        assertTrue(activity.contains("一键缓存"))
        assertTrue(activity.contains("ReaderPageCacheManager.enqueue"))
        assertTrue(activity.contains("离开阅读页后仍会继续"))
        assertTrue(manager.contains("OneTimeWorkRequestBuilder<ReaderPageCacheWorker>"))
        assertTrue(manager.contains("enqueueUniqueWork"))
        assertTrue(manager.contains("ExistingWorkPolicy.KEEP"))
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
