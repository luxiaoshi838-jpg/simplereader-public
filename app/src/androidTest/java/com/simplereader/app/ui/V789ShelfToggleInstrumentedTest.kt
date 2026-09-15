package com.simplereader.app.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplereader.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V789ShelfToggleInstrumentedTest {
    @Test
    fun gridAndListCanBeToggledRepeatedlyWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            repeat(12) { index ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.TextView>(R.id.editButton).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val expected = if (index % 2 == 0) "宫格" else "列表"
                    assertEquals(expected, activity.findViewById<android.widget.TextView>(R.id.editButton).text.toString())
                }
            }
        }
    }
}
