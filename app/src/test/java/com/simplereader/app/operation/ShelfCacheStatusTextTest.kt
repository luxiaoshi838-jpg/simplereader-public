package com.simplereader.app.operation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ShelfCacheStatusTextTest {
    @Test
    fun preparingTextMatchesRequiredTwoLineFormat() {
        assertEquals(
            "0 / 0（0.0%）\n当前：《准备中》　成功 0｜失败 0｜跳过 0",
            ShelfCacheStatusText.preparing()
        )
    }

    @Test
    fun activeTextMatchesRequiredTwoLineFormat() {
        Locale.setDefault(Locale.CHINA)
        assertEquals(
            "137 / 326（42.0%）\n当前：《测试书籍》　成功 128｜失败 2｜跳过 7",
            ShelfCacheStatusText.active(
                current = 137,
                total = 326,
                title = "测试书籍",
                completed = 128,
                failed = 2,
                skipped = 7
            )
        )
    }

    @Test
    fun completedTextKeepsTotalsVisible() {
        assertEquals(
            "326 / 326（100.0%）\n当前：《已完成》　成功 317｜失败 2｜跳过 7",
            ShelfCacheStatusText.completed(326, 317, 2, 7)
        )
    }
}
