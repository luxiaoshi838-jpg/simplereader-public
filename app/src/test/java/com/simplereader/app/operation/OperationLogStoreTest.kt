package com.simplereader.app.operation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        context.getSharedPreferences("operation", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("operation_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun operationHistoryIsNoLongerPersisted() {
        repeat(12) { index ->
            OperationLogStore.beginShelfCache(context, "work-$index", "全书架目录缓存", 20)
            OperationLogStore.updateShelfCache(
                context = context,
                workId = "work-$index",
                modeTitle = "全书架目录缓存",
                state = "运行中",
                currentIndex = index + 1,
                total = 20,
                currentTitle = "书${index + 1}",
                completed = index,
                failed = 0,
                skipped = 0
            )
        }
        assertEquals(emptyList<OperationLogStore.Entry>(), OperationLogStore.list(context))
        assertFalse(context.getSharedPreferences("operation_history", Context.MODE_PRIVATE).contains("entries"))
    }

    @Test
    fun legacyOperationPreferenceFilesArePurgedWithoutRestoringHistory() {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        context.getSharedPreferences("operation", Context.MODE_PRIVATE).edit().putString("entries", "legacy").commit()
        context.getSharedPreferences("operation_history", Context.MODE_PRIVATE).edit().putString("entries", "legacy").commit()
        OperationLogStore.purgeLegacyV722StoreBeforeLoad(context)
        assertTrue(OperationLogStore.list(context).isEmpty())
        assertFalse(File(prefsDir, "operation.xml.bak").exists())
        assertFalse(File(prefsDir, "operation_history.xml.bak").exists())
    }
}
