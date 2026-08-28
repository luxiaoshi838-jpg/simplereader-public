package com.simplereader.app.reader.page

import android.content.Context
import android.content.res.Resources
import kotlin.math.roundToInt

/** Shared layout profile used by the visible reader and the background shelf cache worker. */
object ReaderCacheProfile {
    const val READER_PREFS = "reader_prefs"
    const val PREF_TEXT_SIZE = "text_size"
    private const val PREF_VIEWPORT_WIDTH = "cache_viewport_width_px"
    private const val PREF_VIEWPORT_HEIGHT = "cache_viewport_height_px"

    const val TOP_GUARD_DP = 0
    const val HORIZONTAL_PADDING_DP = 28
    const val CONTENT_TOP_PADDING_DP = 0
    const val CONTENT_BOTTOM_PADDING_DP = 0
    const val LINE_SPACING_MULTIPLIER = 1.75f
    const val TITLE_SIZE_DELTA_SP = 2f

    fun rememberViewport(context: Context, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_VIEWPORT_WIDTH, widthPx)
            .putInt(PREF_VIEWPORT_HEIGHT, heightPx)
            .apply()
    }

    fun currentTextSizeSp(context: Context): Float =
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .getFloat(PREF_TEXT_SIZE, 18f)
            .coerceIn(12f, 42f)

    fun createSettings(
        context: Context,
        textSizeSp: Float = currentTextSizeSp(context),
        viewportWidthPx: Int? = null,
        viewportHeightPx: Int? = null,
        contentPaddingLeftPx: Int? = null,
        contentPaddingTopPx: Int? = null,
        contentPaddingRightPx: Int? = null,
        contentPaddingBottomPx: Int? = null
    ): ReaderLayoutSettings {
        val metrics = context.resources.displayMetrics
        val prefs = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
        val width = viewportWidthPx?.takeIf { it > 0 }
            ?: prefs.getInt(PREF_VIEWPORT_WIDTH, 0).takeIf { it > 0 }
            ?: metrics.widthPixels.coerceAtLeast(1)
        val height = viewportHeightPx?.takeIf { it > 0 }
            ?: prefs.getInt(PREF_VIEWPORT_HEIGHT, 0).takeIf { it > 0 }
            ?: fallbackViewportHeight(context)
        val textPx = textSizeSp.coerceIn(12f, 42f) * metrics.scaledDensity
        val titleDeltaPx = TITLE_SIZE_DELTA_SP * metrics.scaledDensity
        return ReaderLayoutSettings(
            viewportWidthPx = width.coerceAtLeast(1),
            viewportHeightPx = height.coerceAtLeast(1),
            contentPaddingLeftPx = contentPaddingLeftPx ?: dp(context, HORIZONTAL_PADDING_DP),
            contentPaddingTopPx = contentPaddingTopPx ?: dp(context, CONTENT_TOP_PADDING_DP),
            contentPaddingRightPx = contentPaddingRightPx ?: dp(context, HORIZONTAL_PADDING_DP),
            contentPaddingBottomPx = contentPaddingBottomPx ?: dp(context, CONTENT_BOTTOM_PADDING_DP),
            textSizePx = textPx,
            typefaceKey = "default",
            lineSpacingExtraPx = 0f,
            lineSpacingMultiplier = LINE_SPACING_MULTIPLIER,
            titleScale = ((textPx + titleDeltaPx) / textPx).coerceAtLeast(1f)
        )
    }

    private fun fallbackViewportHeight(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val systemBars = systemDimension(context.resources, "status_bar_height") +
            systemDimension(context.resources, "navigation_bar_height")
        return (metrics.heightPixels - systemBars - dp(context, TOP_GUARD_DP)).coerceAtLeast(metrics.heightPixels / 2)
    }

    private fun systemDimension(resources: Resources, name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
