package com.simplereader.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.entity.BookGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupBooksActivityTest {

    @Test
    fun categoryScreenStartsAndVirtualizesThreeThousandBooks() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<Application>()
            application.deleteDatabase("simple_reader_db")
            val database = SimpleReaderDatabase.getDatabase(application)

            val groupId = database.bookGroupDao().insert(
                BookGroup(
                    name = "超大分类",
                    displayName = "超大分类",
                    sourceKey = "robolectric:three-thousand-books"
                )
            )
            database.withTransaction {
                repeat(3_000) { index ->
                    database.bookDao().insert(
                        Book(
                            title = "测试书籍 $index",
                            filePath = "content://test/book-$index.txt",
                            format = "TXT",
                            groupId = groupId,
                            fileName = "book-$index.txt"
                        )
                    )
                }
            }

            val intent = Intent(application, GroupBooksActivity::class.java)
                .putExtra(GroupBooksActivity.EXTRA_GROUP_ID, groupId)
                .putExtra(GroupBooksActivity.EXTRA_GROUP_NAME, "超大分类")
            val controller = Robolectric.buildActivity(GroupBooksActivity::class.java, intent).setup()
            val activity = controller.get()
            val recyclerView = findRecyclerView(activity.window.decorView)

            assertFalse(activity.isFinishing)
            assertNotNull(recyclerView)
            requireNotNull(recyclerView)
            assertTrue(recyclerView.layoutManager is GridLayoutManager)

            withTimeout(20_000) {
                while (recyclerView.adapter?.itemCount != 3_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(25)
                }
            }

            val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            recyclerView.measure(widthSpec, heightSpec)
            recyclerView.layout(0, 0, 1080, 1920)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(3_000, recyclerView.adapter?.itemCount)
            assertTrue("RecyclerView 应只创建可见卡片", recyclerView.childCount in 1..99)

            recyclerView.scrollToPosition(2_999)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3_000, recyclerView.adapter?.itemCount)
            assertTrue("滚动到底部后仍不得创建全部卡片", recyclerView.childCount in 1..99)

            controller.pause().stop().destroy()
        }
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRecyclerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
