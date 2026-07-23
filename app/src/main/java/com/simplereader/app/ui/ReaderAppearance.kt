package com.simplereader.app.ui

import android.content.Context
import android.graphics.Color

object ReaderAppearance {
    private const val READER_PREFS = "reader_prefs"
    private const val PREF_MODE = "reader_color_mode"
    private const val PREF_DAY_BACKGROUND = "day_background"
    private const val PREF_DAY_TEXT = "day_text_color"
    private const val PREF_NIGHT_TEXT = "night_text_color"
    private const val PREF_LEGACY_BACKGROUND = "background"
    private const val PREF_LEGACY_TEXT = "text_color"
    private const val PREF_TEXT_SIZE = "text_size"

    const val MODE_DAY = "day"
    const val MODE_NIGHT = "night"

    const val DAY_BACKGROUND = 0xFFF5E9C8.toInt()
    const val DAY_TEXT = 0xFF3B3428.toInt()
    const val NIGHT_BACKGROUND = 0xFF202124.toInt()
    const val NIGHT_TEXT = 0xFFE8EAED.toInt()

    data class Palette(val backgroundColor: Int, val textColor: Int)

    fun currentMode(context: Context): String {
        val prefs = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_MODE, null)
        if (saved == MODE_DAY || saved == MODE_NIGHT) return saved
        val legacyBackground = prefs.getInt(PREF_LEGACY_BACKGROUND, DAY_BACKGROUND)
        return if (isDark(legacyBackground)) MODE_NIGHT else MODE_DAY
    }

    fun palette(context: Context): Palette {
        val prefs = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
        return if (currentMode(context) == MODE_NIGHT) {
            Palette(
                backgroundColor = NIGHT_BACKGROUND,
                textColor = prefs.getInt(PREF_NIGHT_TEXT, NIGHT_TEXT)
            )
        } else {
            val legacyBackground = prefs.getInt(PREF_LEGACY_BACKGROUND, DAY_BACKGROUND)
            val legacyText = prefs.getInt(PREF_LEGACY_TEXT, DAY_TEXT)
            val dayBackground = prefs.getInt(PREF_DAY_BACKGROUND, legacyBackground)
                .takeUnless(::isDark)
                ?: DAY_BACKGROUND
            Palette(
                backgroundColor = dayBackground,
                textColor = prefs.getInt(PREF_DAY_TEXT, legacyText)
            )
        }
    }

    fun setMode(context: Context, mode: String) {
        require(mode == MODE_DAY || mode == MODE_NIGHT)
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MODE, mode)
            .apply()
    }

    fun toggleMode(context: Context): Palette {
        setMode(context, if (currentMode(context) == MODE_NIGHT) MODE_DAY else MODE_NIGHT)
        return palette(context)
    }

    fun saveDayPalette(context: Context, backgroundColor: Int, textColor: Int) {
        val safeBackground = backgroundColor.takeUnless(::isDark) ?: DAY_BACKGROUND
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MODE, MODE_DAY)
            .putInt(PREF_DAY_BACKGROUND, safeBackground)
            .putInt(PREF_DAY_TEXT, textColor)
            .apply()
    }

    fun saveNightText(context: Context, textColor: Int) {
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MODE, MODE_NIGHT)
            .putInt(PREF_NIGHT_TEXT, textColor)
            .apply()
    }

    /** Compatibility entry point for older call sites. Night background stays fixed. */
    fun savePalette(context: Context, backgroundColor: Int, textColor: Int) {
        if (isDark(backgroundColor)) {
            saveNightText(context, textColor)
        } else {
            saveDayPalette(context, backgroundColor, textColor)
        }
    }

    fun textSize(context: Context): Float =
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .getFloat(PREF_TEXT_SIZE, 18f)

    fun shelfTextColor(context: Context): Int =
        if (currentMode(context) == MODE_NIGHT) Color.WHITE else Color.rgb(30, 30, 30)

    fun shelfSecondaryTextColor(context: Context): Int =
        if (currentMode(context) == MODE_NIGHT) Color.rgb(180, 180, 180) else Color.rgb(130, 126, 118)

    fun isDark(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 96
    }
}
