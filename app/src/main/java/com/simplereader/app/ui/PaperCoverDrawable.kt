package com.simplereader.app.ui

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 以 v575 为基准：保留 v575 原蓝色，只把模拟颗粒换成 v578 已确认的第二版纸纹理。
 * 不绘制 TXT、EPUB、CHM、PDF 或扩展名角标。
 */
class PaperCoverDrawable(
    private val topColor: Int = Color.rgb(74, 124, 166),
    private val bottomColor: Int = Color.rgb(45, 89, 132),
    private val radiusPx: Float = 10f,
    private val seed: Int = 0
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 72
        xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val rect = RectF()
    private val clipPath = Path()
    private val textureMatrix = Matrix()

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
        val save = canvas.saveLayer(rect, null)
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, fillPaint)
        drawTexture(canvas)
        drawEdge(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawTexture(canvas: Canvas) {
        val texture = PaperTextureData.bitmap()
        val shader = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(
            rect.width() / texture.width.toFloat(),
            rect.height() / texture.height.toFloat()
        )
        val xPhase = ((seed and 0x1F) / 31f - 0.5f) * texture.width * scale * 0.08f
        val yPhase = (((seed ushr 5) and 0x1F) / 31f - 0.5f) * texture.height * scale * 0.08f
        textureMatrix.reset()
        textureMatrix.setScale(scale, scale)
        textureMatrix.postTranslate(
            rect.left + (rect.width() - texture.width * scale) / 2f + xPhase,
            rect.top + (rect.height() - texture.height * scale) / 2f + yPhase
        )
        shader.setLocalMatrix(textureMatrix)
        texturePaint.shader = shader
        canvas.drawRect(rect, texturePaint)
        texturePaint.shader = null
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
        canvas.drawLine(rect.left + 7f, rect.top + 8f, rect.left + 7f, rect.bottom - 8f, edgePaint)
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
        texturePaint.alpha = (72 * alpha / 255).coerceIn(0, 255)
        edgePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        texturePaint.colorFilter = colorFilter
        edgePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
