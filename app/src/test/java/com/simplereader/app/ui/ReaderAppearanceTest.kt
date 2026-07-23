package com.simplereader.app.ui

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderAppearanceTest {
    @Test
    fun `day and night settings remain isolated`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        ReaderAppearance.saveDayPalette(context, Color.WHITE, Color.rgb(35, 35, 35))
        assertEquals(ReaderAppearance.MODE_DAY, ReaderAppearance.currentMode(context))
        assertEquals(Color.WHITE, ReaderAppearance.palette(context).backgroundColor)

        val night = ReaderAppearance.toggleMode(context)
        assertEquals(ReaderAppearance.NIGHT_BACKGROUND, night.backgroundColor)

        ReaderAppearance.saveNightText(context, Color.LTGRAY)
        assertEquals(ReaderAppearance.NIGHT_BACKGROUND, ReaderAppearance.palette(context).backgroundColor)

        val day = ReaderAppearance.toggleMode(context)
        assertEquals(Color.WHITE, day.backgroundColor)
        assertFalse(ReaderAppearance.isDark(day.backgroundColor))
    }

    @Test
    fun `black is rejected as a day background`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ReaderAppearance.saveDayPalette(context, Color.BLACK, Color.WHITE)
        assertEquals(ReaderAppearance.DAY_BACKGROUND, ReaderAppearance.palette(context).backgroundColor)
    }
}
