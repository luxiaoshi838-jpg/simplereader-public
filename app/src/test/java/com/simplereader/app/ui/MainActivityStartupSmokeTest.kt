package com.simplereader.app.ui

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
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
        // Robolectric does not execute AndroidX Startup's WorkManager provider the same way a
        // packaged app process does. Initialize it explicitly so the test reaches MainActivity's
        // real layout/database/UI startup path instead of failing in the test harness first.
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { WorkManager.getInstance(context) }
            .getOrElse {
                WorkManager.initialize(context, Configuration.Builder().build())
                WorkManager.getInstance(context)
            }

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
