package com.simplereader.app.operation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OperationLogStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("operation_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun historyIsPhysicallyLimitedToTenEntries() {
        repeat(12) { index ->
            OperationLogStore.beginShelfCache(context, "work-$index", "全书架目录缓存", 20)
        }
        assertEquals(10, OperationLogStore.list(context).size)
        val raw = context.getSharedPreferences("operation_history", Context.MODE_PRIVATE)
            .getString("entries", "[]") ?: "[]"
        assertEquals(10, JSONArray(raw).length())
    }

    @Test
    fun repeatedProgressForOneWorkIdUpdatesSameEntry() {
        OperationLogStore.beginShelfCache(context, "same-work", "全书架目录缓存", 326)
        repeat(25) { index ->
            OperationLogStore.updateShelfCache(
                context = context,
                workId = "same-work",
                modeTitle = "全书架目录缓存",
                state = "运行中",
                currentIndex = index + 1,
                total = 326,
                currentTitle = "书${index + 1}",
                completed = index,
                failed = 0,
                skipped = 0
            )
        }
        val entries = OperationLogStore.list(context)
        assertEquals(1, entries.size)
        assertTrue(entries.single().body.contains("进度：25 / 326"))
        assertTrue(entries.single().body.contains("当前：书25"))
    }
}
