package com.simplereader.app.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupBooksActivityTest {
    @Test
    fun `restored group screen stays open even for legacy zero id`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            GroupBooksActivity::class.java
        )
            .putExtra(GroupBooksActivity.EXTRA_GROUP_ID, 0L)
            .putExtra(GroupBooksActivity.EXTRA_GROUP_NAME, "恢复分组")
        val controller = Robolectric.buildActivity(GroupBooksActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
}
