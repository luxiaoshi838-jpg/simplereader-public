package com.simplereader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAppearanceTest {
    @Test
    fun `day-night button toggles in both directions`() {
        val night = ReaderAppearance.nextDayNightPalette(ReaderAppearance.DAY_BACKGROUND)
        assertEquals(ReaderAppearance.NIGHT_BACKGROUND, night.backgroundColor)
        assertEquals(ReaderAppearance.NIGHT_TEXT, night.textColor)

        val day = ReaderAppearance.nextDayNightPalette(ReaderAppearance.NIGHT_BACKGROUND)
        assertEquals(ReaderAppearance.DAY_BACKGROUND, day.backgroundColor)
        assertEquals(ReaderAppearance.DAY_TEXT, day.textColor)
    }
}
