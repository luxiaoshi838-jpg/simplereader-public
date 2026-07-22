package com.simplereader.app

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplereader.app.parser.ChmParser
import com.simplereader.app.ui.ReaderActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderRuntimeTest {

    @Test
    fun minifiedReleaseReaderActivityRendersTxt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = "简阅阅读页运行测试：TXT 正常显示"
        val intent = Intent(context, ReaderActivity::class.java).apply {
            putExtra("readerDiagnosticText", expected)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val displayed = activity.findViewById<TextView>(R.id.contentView).text.toString()
                assertTrue("ReaderActivity did not render diagnostic TXT", displayed.contains(expected))
            }
        }
    }

    @Test
    fun androidNativeChmEngineExtractsKnownChm() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val text = testContext.assets.open("testChm.chm").use(ChmParser::readText)

        assertTrue("CHM extraction returned too little text", text.length > 500)
        assertTrue(
            "Known CHM content was not extracted",
            text.contains("If you do not specify a window type", ignoreCase = true) ||
                text.contains("HTML Help ActiveX Control Reference", ignoreCase = true)
        )
    }
}
