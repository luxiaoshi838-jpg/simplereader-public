package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

class PaperCoverDrawable(
    private val topColor: Int = Color.rgb(92, 132, 164),
    private val bottomColor: Int = Color.rgb(54, 92, 128),
    private val radiusPx: Float = 10f,
    private val seed: Int = 0
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 245)
        strokeWidth = 1.1f
    }
    private val shadowFiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(24, 37, 38, 35)
        strokeWidth = 0.9f
    }
    private val rect = RectF()
    private val clipPath = Path()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        fillPaint.shader = LinearGradient(
            0f,
            rect.top,
            0f,
            rect.bottom,
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP
        )
        clipPath.reset()
        clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, fillPaint)
        val lineCount = max(9, (rect.height() / 11f).toInt())
        for (index in 0 until lineCount) {
            val y = rect.top + 5f + index * rect.height() / lineCount
            val wobble = ((seed + index * 37) % 9 - 4).toFloat()
            canvas.drawLine(rect.left + 5f, y + wobble * 0.18f, rect.right - 5f, y - wobble * 0.12f, fiberPaint)
        }
        val verticalCount = 5
        for (index in 0 until verticalCount) {
            val x = rect.left + (index + 1) * rect.width() / (verticalCount + 1) + ((seed + index * 19) % 7 - 3)
            canvas.drawLine(x, rect.top + 4f, x + ((seed + index * 11) % 5 - 2), rect.bottom - 4f, shadowFiberPaint)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        fiberPaint.alpha = (alpha * 38 / 255).coerceIn(0, 255)
        shadowFiberPaint.alpha = (alpha * 24 / 255).coerceIn(0, 255)
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
