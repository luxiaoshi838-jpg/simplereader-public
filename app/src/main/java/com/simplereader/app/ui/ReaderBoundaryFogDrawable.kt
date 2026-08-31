package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Reconstructed from the shipped v745 ReaderBoundaryFogDrawable bytecode. */
class ReaderBoundaryFogDrawable(
    private val backgroundColor: Int,
    private val topEdge: Boolean
) : Drawable() {
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var globalAlpha: Int = 255

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        drawBackgroundHaze(canvas, area)
        drawSoftCore(canvas, area)
    }

    private fun scaledAlpha(alpha: Int): Int = globalAlpha * alpha / 255

    private fun drawBackgroundHaze(canvas: Canvas, area: Rect) {
        val r = Color.red(backgroundColor)
        val g = Color.green(backgroundColor)
        val b = Color.blue(backgroundColor)
        val colors = intArrayOf(
            Color.argb(scaledAlpha(138), r, g, b),
            Color.argb(scaledAlpha(72), r, g, b),
            Color.argb(scaledAlpha(24), r, g, b),
            Color.argb(0, r, g, b)
        )
        val positions = floatArrayOf(0f, 0.20f, 0.55f, 1f)
        // The opaque edge is the actual reader content boundary; haze fades inward.
        hazePaint.shader = if (topEdge) {
            LinearGradient(0f, area.top.toFloat(), 0f, area.bottom.toFloat(), colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, area.bottom.toFloat(), 0f, area.top.toFloat(), colors, positions, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(area, hazePaint)
        hazePaint.shader = null
    }

    private fun drawSoftCore(canvas: Canvas, area: Rect) {
        val r = Color.red(backgroundColor)
        val g = Color.green(backgroundColor)
        val b = Color.blue(backgroundColor)
        val average = (r + g + b) / 3
        fun coreChannel(c: Int): Int = if (average <= 130) {
            (c * 0.72f).toInt().coerceIn(0, 255)
        } else {
            (c + (255 - c) * 0.34f).toInt().coerceIn(0, 255)
        }
        val cr = coreChannel(r)
        val cg = coreChannel(g)
        val cb = coreChannel(b)
        val alpha52 = Color.argb(scaledAlpha(52), cr, cg, cb)
        val alpha22 = Color.argb(scaledAlpha(22), cr, cg, cb)
        val transparent = Color.argb(0, cr, cg, cb)
        val start = if (topEdge) area.top.toFloat() else area.bottom.toFloat()
        val direction = if (topEdge) 1f else -1f
        val length = area.height().coerceAtLeast(1).toFloat() * 0.58f
        corePaint.shader = LinearGradient(
            0f,
            start,
            0f,
            start + direction * length,
            intArrayOf(alpha52, alpha22, alpha22, transparent),
            floatArrayOf(0f, 0.16f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, corePaint)
        corePaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        hazePaint.colorFilter = colorFilter
        corePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
