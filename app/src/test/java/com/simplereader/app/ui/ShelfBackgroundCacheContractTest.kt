package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfBackgroundCacheContractTest {
    @Test
    fun shelfManagementStartsPersistentCatalogCache() {
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val worker = File("src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt").readText()
        val store = File("src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val loader = File("src/main/java/com/simplereader/app/reader/ReaderDocumentLoader.kt").readText()

        assertTrue(main.contains("arrayOf(\"书架目录缓存\", \"批量管理分组\", \"同步书架\")"))
        assertTrue(main.contains("arrayOf(\"全书架目录缓存\", \"全书架无目录书籍缓存\")"))
        assertTrue(main.contains("ShelfCacheWorker.enqueue(this, mode)"))
        assertTrue(worker.contains("CoroutineWorker"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("PageEngine.paginate"))
        assertTrue(worker.contains("MODE_ALL_BOOKS"))
        assertTrue(worker.contains("MODE_BOOKS_WITHOUT_CATALOG"))
        assertTrue(worker.contains("PageCacheStore.hasCurrentCatalog"))
        assertTrue(worker.contains("PageCacheStore.clearDerivedCatalogAndPages"))
        assertTrue(worker.contains("forceCatalogRefresh = true"))
        assertTrue(store.contains("fileName").and(store.contains("fileSize")))
        assertTrue(store.contains("catalogRuleVersion"))
        assertTrue(worker.contains("TxtParser.CATALOG_RULE_VERSION"))
        assertTrue(loader.contains("if (!forceCatalogRefresh)"))
        assertFalse(loader.contains("cached.catalogRuleVersion == TxtParser.CATALOG_RULE_VERSION"))
        assertFalse(store.contains("root.optInt(\"catalogRuleVersion\", 0) == TxtParser.CATALOG_RULE_VERSION"))
        assertFalse(store.contains("root.optInt(\"catalogRuleVersion\", 0) == identity.catalogRuleVersion"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
    }
}
