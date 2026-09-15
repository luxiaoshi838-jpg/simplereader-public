package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V789ExternalOpenAndLogContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val externalStore = File("src/main/java/com/simplereader/app/ui/ExternalBookStore.kt").readText()
    private val logFiles = File("src/main/java/com/simplereader/app/operation/DiagnosticLogFiles.kt").readText()
    private val dao = File("src/main/java/com/simplereader/app/data/dao/BookDao.kt").readText()

    @Test fun `external TXT and EPUB can be offered to SimpleReader`() {
        assertTrue(manifest.contains(".ui.ExternalBookOpenActivity"))
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("android.intent.action.SEND"))
        assertTrue(manifest.contains("text/plain"))
        assertTrue(manifest.contains("application/epub+zip"))
    }

    @Test fun `external book stays hidden until exit decision`() {
        assertTrue(externalStore.contains("EXTERNAL_PENDING"))
        assertTrue(dao.contains("fileStatus != 'EXTERNAL_PENDING'"))
        assertTrue(reader.contains("setTitle(\"加入书架？\")"))
        assertTrue(reader.contains("setPositiveButton(\"加入书架\")"))
        assertTrue(reader.contains("setNegativeButton(\"不加入\")"))
        assertTrue(reader.contains("ExternalBookStore.discard"))
    }

    @Test fun `data export exposes independent crash and operation log locations`() {
        assertTrue(main.contains("\"崩溃日志\", \"操作日志\", \"日志位置设置\""))
        assertTrue(main.contains("crashLogFolderLauncher"))
        assertTrue(main.contains("operationLogFolderLauncher"))
        assertTrue(logFiles.contains("KEY_CRASH_TREE"))
        assertTrue(logFiles.contains("KEY_OPERATION_TREE"))
        assertTrue(logFiles.contains("简阅_操作日志_当前.txt"))
    }
}
