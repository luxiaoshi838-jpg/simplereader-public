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
        assertTrue(worker.countOccurrences("setProgress(") >= 4)
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
