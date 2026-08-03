package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTopTitleAndCatalogContractTest {
    @Test
    fun readerLowersTextFromV612BaselineWithoutMovingWholePage() {
        val layout = File("src/main/res/layout/activity_reader.xml").readText()
        assertTrue(layout.contains("android:paddingTop=\"50dp\""))
        assertTrue(!layout.substringBefore("<com.simplereader.app.ui.PagedReaderView").contains("android:paddingTop="))
    }

    @Test
    fun chapterTitleIsExactlyTwoSpLargerAndChromeUsesChapter() {
        val profile = File("src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt").readText()
        val activity = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
        assertTrue(profile.contains("TITLE_SIZE_DELTA_SP = 2f"))
        assertTrue(activity.contains("updateCurrentChapterTitle()"))
        assertTrue(activity.contains("目录　${'$'}{book?.title.orEmpty()}"))
        assertTrue(activity.contains("Regex(\"^\\\\s*\\\\d+[.、．]\\\\s*\")"))
    }
}
