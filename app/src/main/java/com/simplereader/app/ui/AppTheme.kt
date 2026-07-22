package com.simplereader.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppTheme {
    private const val PREFS_NAME = "simple_reader_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(delegateMode(currentMode(context)))
    }

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode)
            .apply()
        AppCompatDelegate.setDefaultNightMode(delegateMode(mode))
    }

    fun currentMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, MODE_SYSTEM)
            ?: MODE_SYSTEM
    }

    private fun delegateMode(mode: String): Int {
        return when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
