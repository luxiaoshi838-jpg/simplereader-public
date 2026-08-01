package com.simplereader.app.ui

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** 阅读页保留当前主题底色，纹理绘制与 v579 封面完全一致。 */
class PaperPageDrawable(
    private val backgroundColor: Int
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 72
        xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    }
    private val textureMatrix = Matrix()

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return

        canvas.drawRect(area, fillPaint)

        val texture = PaperTextureData.bitmap()
        val shader = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(
            area.width().toFloat() / texture.width.toFloat(),
            area.height().toFloat() / texture.height.toFloat()
        )
        textureMatrix.reset()
        textureMatrix.setScale(scale, scale)
        textureMatrix.postTranslate(
            area.left + (area.width() - texture.width * scale) / 2f,
            area.top + (area.height() - texture.height * scale) / 2f
        )
        shader.setLocalMatrix(textureMatrix)
        texturePaint.shader = shader
        canvas.drawRect(area, texturePaint)
        texturePaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        texturePaint.alpha = (72 * alpha / 255).coerceIn(0, 255)
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
