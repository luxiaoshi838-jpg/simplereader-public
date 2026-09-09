package com.simplereader.app.ui

import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityStartupSmokeTest {

    @Test
    fun mainActivityColdStartReachesShelf() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()
        val shelf = activity.findViewById<RecyclerView>(R.id.shelfGrid)
        assertNotNull(shelf)
        assertTrue(shelf.javaClass == RecyclerView::class.java)
        controller.pause().stop().destroy()
    }
}
