package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChmDisabledContractTest {
    @Test
    fun `new imports accept txt and epub but not chm`() {
        val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val formatBlock = main.substringAfter("private fun DocumentFile.bookFormat()")
            .substringBefore("private fun activityTime")
        assertTrue(formatBlock.contains(".txt"))
        assertTrue(formatBlock.contains(".epub"))
        assertFalse(formatBlock.contains(".chm"))
        assertTrue(main.contains("未发现可导入的 TXT 或 EPUB 文件"))
    }

    @Test
    fun `old chm records fail clearly instead of entering broken parser`() {
        val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        assertTrue(reader.contains("当前版本已停止支持 CHM"))
        val supportedNames = reader.substringAfter("private fun isSupportedBookName")
            .substringBefore("private fun joinPath")
        assertFalse(supportedNames.contains(".chm"))
    }
}
