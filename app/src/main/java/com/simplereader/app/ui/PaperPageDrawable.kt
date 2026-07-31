package com.simplereader.app.ui

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** 保留 v575 当前阅读主题底色，仅叠加同一张第二版纸纹理。 */
class PaperPageDrawable(
    private val backgroundColor: Int
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 28
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        canvas.drawRect(area, fillPaint)
        val texture = PaperTextureData.bitmap()
        texturePaint.shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        canvas.drawRect(area, texturePaint)
        texturePaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        texturePaint.alpha = (28 * alpha / 255).coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        texturePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
