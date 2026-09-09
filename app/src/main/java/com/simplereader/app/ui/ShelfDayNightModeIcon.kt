package com.simplereader.app.ui

import android.content.Context
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.simplereader.app.R

/** Shelf-only day/night icon binding.
 *
 * v776 intentionally changes only the bookshelf daytime icon:
 * DAY = black outline + white fill sun; NIGHT = existing v775 moon.
 * ReaderActivity continues using DayNightModeIcon unchanged.
 */
object ShelfDayNightModeIcon {
    fun apply(button: ImageView, context: Context, tintColor: Int) {
        val mode = ReaderAppearance.currentMode(context)
        val isDay = mode == ReaderAppearance.MODE_DAY
        val drawableRes = if (isDay) R.drawable.ic_shelf_mode_day_outline else R.drawable.ic_mode_night_a
        val drawable = AppCompatResources.getDrawable(context, drawableRes)?.mutate()

        // The approved shelf DAY icon has fixed black outline + white fill and must not be tinted.
        // NIGHT keeps the existing v775 behavior so it remains visible on the dark shelf palette.
        if (!isDay && drawable != null) {
            DrawableCompat.setTint(drawable, tintColor)
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
