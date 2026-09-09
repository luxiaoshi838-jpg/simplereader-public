package com.simplereader.app.ui

import android.content.Context
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.simplereader.app.R

/** Shared A-style day/night icon binding for shelf and reader chrome.
 *
 * Display semantics are intentionally state-based (not action-based):
 * DAY mode always displays the sun; NIGHT mode always displays the moon.
 */
object DayNightModeIcon {
    fun apply(button: ImageView, context: Context, tintColor: Int) {
        val mode = ReaderAppearance.currentMode(context)
        val isDay = mode == ReaderAppearance.MODE_DAY
        val drawableRes = if (isDay) R.drawable.ic_mode_day_a else R.drawable.ic_mode_night_a
        val drawable = AppCompatResources.getDrawable(context, drawableRes)?.mutate()
        if (drawable != null) {
            DrawableCompat.setTint(drawable, tintColor)
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        button.setImageDrawable(null)
        button.setImageDrawable(drawable)
        button.contentDescription = if (isDay) {
            "当前日间模式，点击切换夜间模式"
        } else {
            "当前夜间模式，点击切换日间模式"
        }
    }
}
