package com.simplereader.app.operation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V725FeatureContractTest {
    @Test
    fun statusBoxKeepsRequiredTwoLineLayout() {
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(layout.contains("android:layout_height=\"56dp\""))
        val status = layout.substring(
            layout.indexOf("android:id=\"@+id/readingStatsTextView\""),
            layout.indexOf("android:id=\"@+id/exportButton\"")
        )
        assertTrue(status.contains("android:layout_height=\"52dp\""))
        assertTrue(status.contains("android:maxLines=\"2\""))
        assertTrue(status.contains("android:ellipsize=\"end\""))
        assertTrue(status.contains("android:lineSpacingExtra=\"1dp\""))
        assertTrue(status.contains("android:paddingTop=\"5dp\""))
        assertTrue(status.contains("android:paddingBottom=\"5dp\""))
        assertTrue(status.contains("android:textSize=\"12sp\""))
        assertTrue(status.contains("android:background=\"@drawable/bg_shelf_stat\""))
    }

    @Test
    fun mainActivityKeepsOriginalGroupActionsAndAddsLogWithoutReplacingThem() {
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val groupStart = main.indexOf("private fun showGroupActions")
        val groupEnd = main.indexOf("private fun showGroupManagement", groupStart)
        val groupBlock = main.substring(groupStart, groupEnd)
        assertTrue(groupBlock.contains("打开分组"))
        assertTrue(groupBlock.contains("管理分组"))
        assertTrue(groupBlock.contains("重命名分组"))
        assertTrue(groupBlock.contains("删除分组"))
        assertFalse(groupBlock.contains("OperationLogDialogs"))

        val exportStart = main.indexOf("private fun showDataExportOptions")
        val exportEnd = main.indexOf("private fun", exportStart + 10)
        val exportBlock = main.substring(exportStart, exportEnd)
        assertTrue(exportBlock.contains("arrayOf(\"导出\", \"同步\", \"日志\")"))
        assertTrue(exportBlock.contains("OperationLogDialogs.showLogHub(this)"))
    }

    @Test
    fun statusOwnershipPreventsUpdateUiFromOverwritingActiveCacheTask() {
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        assertTrue(main.contains("ShelfCacheUiController.attach(this, readingStatsTextView) { updateUI() }"))
        assertTrue(main.contains("ShelfCacheUiController.showPreparing(this, readingStatsTextView)"))
        assertTrue(main.contains("if (!ShelfCacheUiController.isLocked(this))"))
    }

    @Test
    fun workerPublishesRealBookTitleAndFreshCountersThroughoutTheTask() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        assertTrue(worker.contains("val displayedIndex = index + 1"))
        assertTrue(worker.contains("currentTitle = book.title"))
        assertTrue(worker.contains("KEY_TITLE to title"))
        assertTrue(worker.contains("KEY_COMPLETED to completed"))
        assertTrue(worker.contains("KEY_SKIPPED to skipped"))
        assertTrue(worker.contains("KEY_FAILED to failed"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.countOccurrences("OperationLogStore.updateShelfCache(") >= 4)
        assertTrue(worker.countOccurrences("setProgress(") >= 2)
    }

    @Test
    fun shelfCatalogTaskAlsoBuildsAndVerifiesReusablePagination() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val profile = File("src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt").readText()

        assertTrue(worker.contains("PageEngine.paginate("))
        assertTrue(worker.contains("PageCacheStore.savePages(applicationContext, identity, paged)"))
        assertTrue(worker.contains("PageCacheStore.loadPages(applicationContext, identity, document.text)"))
        assertTrue(worker.contains("require(verified != null)"))
        assertTrue(worker.contains("require(verified.pages.size == paged.pages.size)"))
        assertTrue(worker.contains("PageCacheStore.markRecognitionComplete("))
        assertTrue(worker.indexOf("PageCacheStore.markRecognitionComplete(") > worker.indexOf("require(verified.pages.size == paged.pages.size)"))

        assertTrue(profile.contains("const val CONTENT_TOP_PADDING_DP = 0"))
        assertTrue(profile.contains("const val CONTENT_BOTTOM_PADDING_DP = 0"))
    }

    @Test
    fun noCatalogModeSkipsOnlyWhenCatalogAndReusablePagesBothExist() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val reusableStart = worker.indexOf("private suspend fun hasReusableCurrentCache")
        val reusableEnd = worker.indexOf("private fun cacheIdentity", reusableStart)
        val reusableBlock = worker.substring(reusableStart, reusableEnd)

        assertTrue(reusableBlock.contains("PageCacheStore.hasCurrentCatalog("))
        assertTrue(reusableBlock.contains("PageCacheStore.loadPages(applicationContext, identity, document.text)"))
        assertTrue(reusableBlock.contains("cached != null && cached.pages.isNotEmpty()"))
    }

    @Test
    fun shelfCatalogWorkIsIndependentForegroundWorkManagerTask() {
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()

        assertTrue(worker.contains("class ShelfCacheWorker("))
        assertTrue(worker.contains(": CoroutineWorker("))
        assertTrue(worker.contains("setForeground(foregroundInfo(0, 0, \"正在读取书架…\"))"))
        assertTrue(worker.contains("WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(main.contains("可继续阅读或切换到其他应用"))
        assertFalse(main.contains("lifecycleScope.launch {\n            ShelfCacheWorker"))
    }

    @Test
    fun logListHasNoCopyButtonButDetailHasCopyAndDraggableSeekBar() {
        val dialogs = File("src/main/java/com/simplereader/app/operation/OperationLogDialogs.kt").readText()
        val listStart = dialogs.indexOf("fun showOperationList")
        val listEnd = dialogs.indexOf("private fun showOperationDetail", listStart)
        val listBlock = dialogs.substring(listStart, listEnd)
        assertFalse(listBlock.contains("setPositiveButton(\"复制\")"))
        assertTrue(listBlock.contains("entries.map { it.title }.toTypedArray()"))
        assertTrue(listBlock.contains("showOperationDetail(activity, it)"))

        val detailStart = listEnd
        val detailEnd = dialogs.indexOf("private fun showPendingCrashLog", detailStart)
        val detailBlock = dialogs.substring(detailStart, detailEnd)
        assertTrue(detailBlock.contains("SeekBar(activity)"))
        assertTrue(detailBlock.contains("max = 1000"))
        assertTrue(detailBlock.contains("setOnSeekBarChangeListener"))
        assertTrue(detailBlock.contains("scrollView.scrollTo(0, target)"))
        assertTrue(detailBlock.contains("setPositiveButton(\"复制\")"))
    }

    @Test
    fun oneShelfCacheWorkIdProducesOneBoundedOperationLogEntry() {
        val store = File("src/main/java/com/simplereader/app/operation/OperationLogStore.kt").readText()
        assertTrue(store.contains("val existing = current.indexOfFirst { it.id == workId }"))
        assertTrue(store.contains("if (existing >= 0) current[existing] = entry else current.add(0, entry)"))
        assertTrue(store.contains("val index = entries.indexOfFirst { it.id == workId }"))
        assertTrue(store.contains("distinctBy { it.id }"))
        assertTrue(store.contains("const val MAX_ENTRIES = 10"))
        assertTrue(store.contains("take(MAX_ENTRIES)"))
    }

    @Test
    fun legacyV722OperationXmlIsDeletedDirectlyWithoutParsingIt() {
        val store = File("src/main/java/com/simplereader/app/operation/OperationLogStore.kt").readText()
        assertTrue(store.contains("File(prefsDir, \"${'$'}LEGACY_PREFS.xml\").delete()"))
        assertTrue(store.contains("File(prefsDir, \"${'$'}LEGACY_PREFS.xml.bak\").delete()"))
        assertFalse(store.contains("getSharedPreferences(LEGACY_PREFS"))
        assertTrue(store.contains("const val MAX_ENTRIES = 10"))
        assertTrue(store.contains("take(MAX_ENTRIES)"))
    }

    @Test
    fun applicationStartupChainDoesNotInstallOperationLogHooks() {
        val app = File("src/main/java/com/simplereader/app/App.kt").readText()
        assertTrue(app.contains("CrashLogStore.install(this)"))
        assertFalse(app.contains("OperationLog"))
        assertFalse(app.contains("purgeLegacyV722StoreBeforeLoad"))
    }

    private fun String.countOccurrences(token: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(token, index)
            if (index < 0) return count
            count++
            index += token.length
        }
    }
}
