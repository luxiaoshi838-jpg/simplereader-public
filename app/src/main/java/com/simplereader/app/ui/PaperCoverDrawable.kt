package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.Random

/**
 * Clean shelf cover: colour + soft material light + irregular paper fibres.
 * No format text, no horizontal rules, and no cross-hatch/grid texture.
 */
class PaperCoverDrawable(
    private val topColor: Int = Color.rgb(78, 124, 160),
    private val bottomColor: Int = Color.rgb(45, 82, 116),
    private val radiusPx: Float = 10f,
    private val seed: Int = 0
) : Drawable() {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val materialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fibrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
    }
    private val rect = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        clip.reset()
        clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)

        basePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(lighten(topColor, 0.12f), topColor, bottomColor),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, basePaint)

        materialPaint.shader = RadialGradient(
            rect.left + rect.width() * 0.32f,
            rect.top + rect.height() * 0.16f,
            rect.height() * 0.86f,
            intArrayOf(Color.argb(64, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, materialPaint)

        val random = Random(seed.toLong() * 1103515245L + 12345L)
        repeat(42) { index ->
            val startX = rect.left + random.nextFloat() * rect.width()
            val startY = rect.top + random.nextFloat() * rect.height()
            val length = rect.width() * (0.08f + random.nextFloat() * 0.28f)
            val angle = (-0.75f + random.nextFloat() * 1.5f)
            val dx = kotlin.math.cos(angle.toDouble()).toFloat() * length
            val dy = kotlin.math.sin(angle.toDouble()).toFloat() * length * 0.45f
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(
                    startX + dx * 0.28f,
                    startY + dy * 0.18f + random.nextFloat() * 4f - 2f,
                    startX + dx * 0.72f,
                    startY + dy * 0.82f + random.nextFloat() * 4f - 2f,
                    startX + dx,
                    startY + dy
                )
            }
            fibrePaint.strokeWidth = 0.45f + random.nextFloat() * 0.65f
            fibrePaint.color = if (index % 4 == 0) {
                Color.argb(18 + random.nextInt(14), 18, 35, 48)
            } else {
                Color.argb(18 + random.nextInt(18), 244, 248, 242)
            }
            canvas.drawPath(path, fibrePaint)
        }

        repeat(86) { index ->
            val x = rect.left + random.nextFloat() * rect.width()
            val y = rect.top + random.nextFloat() * rect.height()
            val radius = 0.35f + random.nextFloat() * 0.8f
            grainPaint.color = if (index % 5 == 0) {
                Color.argb(12 + random.nextInt(16), 20, 34, 46)
            } else {
                Color.argb(10 + random.nextInt(18), 252, 250, 240)
            }
            canvas.drawCircle(x, y, radius, grainPaint)
        }

        edgePaint.color = Color.argb(52, 20, 32, 42)
        canvas.drawRoundRect(inset(rect, 0.9f), radiusPx, radiusPx, edgePaint)
        edgePaint.color = Color.argb(34, 255, 255, 255)
        canvas.drawRoundRect(inset(rect, 2.1f), radiusPx * 0.82f, radiusPx * 0.82f, edgePaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        materialPaint.alpha = alpha
        fibrePaint.alpha = alpha
        grainPaint.alpha = alpha
        edgePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        basePaint.colorFilter = colorFilter
        materialPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private fun inset(source: RectF, amount: Float): RectF =
        RectF(source.left + amount, source.top + amount, source.right - amount, source.bottom - amount)

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
    )
}
