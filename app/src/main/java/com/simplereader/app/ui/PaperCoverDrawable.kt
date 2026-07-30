package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

class PaperCoverDrawable(
    private val topColor: Int = Color.rgb(74, 124, 166),
    private val bottomColor: Int = Color.rgb(45, 89, 132),
    private val radiusPx: Float = 10f,
    private val seed: Int = 0
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.argb(62, 255, 255, 255)
    }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 255, 255, 255)
        strokeWidth = 1f
    }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(92, 232, 238, 244)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
    }
    private val rect = RectF()
    private val clipPath = Path()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        fillPaint.shader = RadialGradient(
            rect.centerX(),
            rect.top + rect.height() * 0.18f,
            rect.height() * 0.95f,
            intArrayOf(Color.rgb(95, 145, 184), topColor, bottomColor),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        clipPath.reset()
        clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, fillPaint)

        val specks = 58
        for (index in 0 until specks) {
            val xUnit = ((seed * 31 + index * 73) and 0x7fffffff) % 1000 / 1000f
            val yUnit = ((seed * 17 + index * 97) and 0x7fffffff) % 1000 / 1000f
            val alpha = 12 + ((seed + index * 13) and 15)
            grainPaint.color = if (index % 3 == 0) {
                Color.argb(alpha, 28, 48, 66)
            } else {
                Color.argb(alpha, 245, 250, 252)
            }
            val size = 0.7f + (((seed + index * 11) and 3) * 0.22f)
            canvas.drawCircle(rect.left + rect.width() * xUnit, rect.top + rect.height() * yUnit, size, grainPaint)
        }

        for (index in 0 until 10) {
            val y = rect.top + rect.height() * (index + 1) / 12f
            canvas.drawLine(rect.left + 8f, y, rect.right - 8f, y + ((seed + index) % 3 - 1), linePaint)
        }

        edgePaint.color = Color.argb(38, 20, 36, 54)
        canvas.drawRoundRect(rect.insetCopy(1.2f), radiusPx, radiusPx, edgePaint)
        edgePaint.color = Color.argb(42, 255, 255, 255)
        canvas.drawLine(rect.left + 7f, rect.top + 8f, rect.left + 7f, rect.bottom - 8f, edgePaint)

        txtPaint.textSize = rect.height() * 0.16f
        canvas.drawText("TXT", rect.centerX(), rect.bottom - rect.height() * 0.18f, txtPaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        edgePaint.alpha = alpha
        grainPaint.alpha = alpha
        linePaint.alpha = alpha
        txtPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private fun RectF.insetCopy(value: Float): RectF =
        RectF(left + value, top + value, right - value, bottom - value)
}
