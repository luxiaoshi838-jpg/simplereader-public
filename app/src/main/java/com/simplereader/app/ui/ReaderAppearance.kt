package com.simplereader.app.ui

import android.content.Context
import android.graphics.Color

object ReaderAppearance {
    private const val READER_PREFS = "reader_prefs"
    private const val PREF_BACKGROUND = "background"
    private const val PREF_TEXT_COLOR = "text_color"
    private const val PREF_TEXT_SIZE = "text_size"
    private const val DEFAULT_BACKGROUND = 0xFFF5E9C8.toInt()
    private const val DEFAULT_TEXT = 0xFF3B3428.toInt()

    data class Palette(
        val backgroundColor: Int,
        val textColor: Int
    )

    fun palette(context: Context): Palette {
        val prefs = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
        return Palette(
            backgroundColor = prefs.getInt(PREF_BACKGROUND, DEFAULT_BACKGROUND),
            textColor = prefs.getInt(PREF_TEXT_COLOR, DEFAULT_TEXT)
        )
    }

    fun savePalette(context: Context, backgroundColor: Int, textColor: Int) {
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_BACKGROUND, backgroundColor)
            .putInt(PREF_TEXT_COLOR, textColor)
            .apply()
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
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 96
    }
}
