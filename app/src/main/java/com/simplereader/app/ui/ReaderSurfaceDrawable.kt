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
 * Reader background with three always-on layers:
 * 1) chosen base colour, 2) soft material light/shadow, 3) irregular paper grain/fibres.
 */
class ReaderSurfaceDrawable(
    private val baseColor: Int,
    private val seed: Int = 608
) : Drawable() {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val materialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fibrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        basePaint.color = baseColor
        canvas.drawRect(rect, basePaint)

        materialPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                Color.argb(if (ReaderAppearance.isDark(baseColor)) 18 else 34, 255, 255, 255),
                Color.TRANSPARENT,
                Color.argb(if (ReaderAppearance.isDark(baseColor)) 22 else 18, 45, 35, 24)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, materialPaint)
        materialPaint.shader = RadialGradient(
            rect.centerX(),
            rect.top + rect.height() * 0.12f,
            rect.height() * 0.95f,
            intArrayOf(Color.argb(if (ReaderAppearance.isDark(baseColor)) 14 else 22, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, materialPaint)

        val random = Random(seed.toLong() + rect.width().toLong() * 31L + rect.height().toLong())
        val dark = ReaderAppearance.isDark(baseColor)
        repeat(72) { index ->
            val x = rect.left + random.nextFloat() * rect.width()
            val y = rect.top + random.nextFloat() * rect.height()
            val length = rect.width() * (0.035f + random.nextFloat() * 0.12f)
            val dx = (random.nextFloat() - 0.5f) * length
            val dy = (random.nextFloat() - 0.5f) * length * 0.42f
            val path = Path().apply {
                moveTo(x, y)
                cubicTo(
                    x + dx * 0.32f,
                    y + dy * 0.15f + random.nextFloat() * 3f - 1.5f,
                    x + dx * 0.68f,
                    y + dy * 0.85f + random.nextFloat() * 3f - 1.5f,
                    x + dx,
                    y + dy
                )
            }
            fibrePaint.strokeWidth = 0.35f + random.nextFloat() * 0.55f
            fibrePaint.color = if (index % 4 == 0) {
                if (dark) Color.argb(16, 0, 0, 0) else Color.argb(13, 78, 61, 42)
            } else {
                if (dark) Color.argb(17, 255, 255, 255) else Color.argb(17, 255, 255, 248)
            }
            canvas.drawPath(path, fibrePaint)
        }
        repeat(150) { index ->
            grainPaint.color = if (index % 6 == 0) {
                if (dark) Color.argb(10, 0, 0, 0) else Color.argb(9, 88, 70, 52)
            } else {
                if (dark) Color.argb(10, 255, 255, 255) else Color.argb(11, 255, 255, 248)
            }
            canvas.drawCircle(
                rect.left + random.nextFloat() * rect.width(),
                rect.top + random.nextFloat() * rect.height(),
                0.25f + random.nextFloat() * 0.55f,
                grainPaint
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        materialPaint.alpha = alpha
        fibrePaint.alpha = alpha
        grainPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        basePaint.colorFilter = colorFilter
        materialPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
