package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 多看风纸质封面。
 *
 * 规则：
 * 1. 保留书架原有蓝色渐变；
 * 2. 纹理参考用户提供的深蓝纸纤维图片，采用确定性细颗粒与短纤维叠加；
 * 3. 封面内部不绘制 TXT / EPUB / CHM / PDF 或任何文件格式角标。
 */
class PaperCoverDrawable(
    private val topColor: Int = Color.rgb(74, 126, 166),
    private val bottomColor: Int = Color.rgb(45, 89, 132),
    private val radiusPx: Float = 10f,
    private val seed: Int = 0
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fibrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
        strokeCap = Paint.Cap.ROUND
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val rect = RectF()
    private val clipPath = Path()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return

        fillPaint.shader = RadialGradient(
            rect.centerX(),
            rect.top + rect.height() * 0.18f,
            rect.height() * 0.95f,
            intArrayOf(
                blend(topColor, Color.WHITE, 0.08f),
                topColor,
                bottomColor
            ),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )

        clipPath.reset()
        clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, fillPaint)

        drawPaperGrain(canvas)
        drawPaperFibres(canvas)
        drawEdge(canvas)

        canvas.restoreToCount(save)
    }

    private fun drawPaperGrain(canvas: Canvas) {
        val random = Random(seed xor 0x5A17C9)
        repeat(190) { index ->
            val x = rect.left + random.nextFloat() * rect.width()
            val y = rect.top + random.nextFloat() * rect.height()
            val radius = 0.28f + random.nextFloat() * 0.72f
            val dark = index % 4 == 0
            grainPaint.color = if (dark) {
                Color.argb(8 + random.nextInt(8), 24, 46, 68)
            } else {
                Color.argb(5 + random.nextInt(7), 244, 249, 252)
            }
            canvas.drawCircle(x, y, radius, grainPaint)
        }
    }

    private fun drawPaperFibres(canvas: Canvas) {
        val random = Random(seed xor 0x31F24B)

        repeat(78) { index ->
            val x = rect.left + random.nextFloat() * rect.width()
            val y = rect.top + random.nextFloat() * rect.height()
            val length = rect.width() * (0.035f + random.nextFloat() * 0.085f)
            val angle = when (index % 3) {
                0 -> -0.30f + random.nextFloat() * 0.20f
                1 -> -0.05f + random.nextFloat() * 0.10f
                else -> 0.10f + random.nextFloat() * 0.22f
            }
            fibrePaint.color = if (index % 5 == 0) {
                Color.argb(9, 20, 43, 66)
            } else {
                Color.argb(7, 239, 246, 251)
            }
            fibrePaint.strokeWidth = 0.45f + random.nextFloat() * 0.65f
            canvas.drawLine(
                x,
                y,
                x + cos(angle) * length,
                y + sin(angle) * length,
                fibrePaint
            )
        }

        repeat(24) { row ->
            val y = rect.top + rect.height() * (row + 1f) / 26f
            val jitter = ((seed + row * 17) and 3) - 1.5f
            fibrePaint.color = Color.argb(5, 245, 249, 252)
            fibrePaint.strokeWidth = 0.55f
            canvas.drawLine(
                rect.left + 8f,
                y,
                rect.right - 8f,
                y + jitter,
                fibrePaint
            )
        }
    }

    private fun drawEdge(canvas: Canvas) {
        edgePaint.color = Color.argb(34, 20, 36, 54)
        canvas.drawRoundRect(
            RectF(rect.left + 1.2f, rect.top + 1.2f, rect.right - 1.2f, rect.bottom - 1.2f),
            radiusPx,
            radiusPx,
            edgePaint
        )

        edgePaint.color = Color.argb(34, 255, 255, 255)
        canvas.drawLine(
            rect.left + 7f,
            rect.top + 8f,
            rect.left + 7f,
            rect.bottom - 8f,
            edgePaint
        )
    }

    private fun blend(base: Int, overlay: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) * (1f - amount) + Color.red(overlay) * amount).toInt(),
            (Color.green(base) * (1f - amount) + Color.green(overlay) * amount).toInt(),
            (Color.blue(base) * (1f - amount) + Color.blue(overlay) * amount).toInt()
        )
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        grainPaint.alpha = alpha
        fibrePaint.alpha = alpha
        edgePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        grainPaint.colorFilter = colorFilter
        fibrePaint.colorFilter = colorFilter
        edgePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
