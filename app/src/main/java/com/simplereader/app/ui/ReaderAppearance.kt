package com.simplereader.app.ui

import android.content.Context
import android.graphics.Color

object ReaderAppearance {
    private const val READER_PREFS = "reader_prefs"
    private const val PREF_BACKGROUND = "background"
    private const val PREF_TEXT_COLOR = "text_color"
    private const val PREF_TEXT_SIZE = "text_size"

    const val DAY_BACKGROUND = 0xFFF5E9C8.toInt()
    const val DAY_TEXT = 0xFF3B3428.toInt()
    const val NIGHT_BACKGROUND = 0xFF202124.toInt()
    const val NIGHT_TEXT = 0xFFE8EAED.toInt()

    data class Palette(
        val backgroundColor: Int,
        val textColor: Int
    )

    fun palette(context: Context): Palette {
        val prefs = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
        val savedBackground = prefs.getInt(PREF_BACKGROUND, DAY_BACKGROUND)
        val savedText = prefs.getInt(PREF_TEXT_COLOR, DAY_TEXT)
        return Palette(
            backgroundColor = if (savedBackground == Color.BLACK) NIGHT_BACKGROUND else savedBackground,
            textColor = if (savedBackground == Color.BLACK && savedText == Color.WHITE) NIGHT_TEXT else savedText
        )
    }

    fun savePalette(context: Context, backgroundColor: Int, textColor: Int) {
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_BACKGROUND, backgroundColor)
            .putInt(PREF_TEXT_COLOR, textColor)
            .apply()
    }

    fun nextDayNightPalette(currentBackgroundColor: Int): Palette {
        return if (isDark(currentBackgroundColor)) {
            Palette(DAY_BACKGROUND, DAY_TEXT)
        } else {
            Palette(NIGHT_BACKGROUND, NIGHT_TEXT)
        }
    }

    fun dayNightButtonLabel(currentBackgroundColor: Int): String {
        return if (isDark(currentBackgroundColor)) "日间" else "夜间"
    }

    fun textSize(context: Context): Float {
        return context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .getFloat(PREF_TEXT_SIZE, 18f)
    }

    fun shelfTextColor(context: Context): Int {
        val palette = palette(context)
        return if (isDark(palette.backgroundColor)) Color.WHITE else Color.rgb(30, 30, 30)
    }

    fun shelfSecondaryTextColor(context: Context): Int {
        val palette = palette(context)
        return if (isDark(palette.backgroundColor)) Color.rgb(180, 180, 180) else Color.rgb(130, 126, 118)
    }

    fun isDark(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 96
    }
}
