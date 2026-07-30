package com.simplereader.app.ui

import android.graphics.Bitmap
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
 * 多看风纸质封面。
 *
 * v578 固定规则：
 * 1. 蓝色仍使用 v575-v577 的顶部、底部颜色，不从纹理图片取色；
 * 2. 纹理改用用户生成的第二版纸纤维图片，并降低叠加强度；
 * 3. 书架、分组预览和分组内书籍统一使用相同纹理；
 * 4. 不绘制 TXT / EPUB / CHM / PDF、扩展名或任何格式角标。
 */
class PaperCoverDrawable(
    private val textureBitmap: Bitmap?,
    private val topColor: Int = Color.rgb(74, 126, 172),
    private val bottomColor: Int = Color.rgb(47, 94, 136),
    private val radiusPx: Float = 10f,
    private val seed: Int = 0
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = TEXTURE_ALPHA
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
        drawImageTexture(canvas)
        drawEdge(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawImageTexture(canvas: Canvas) {
        val texture = textureBitmap?.takeUnless { it.isRecycled } ?: return
        val shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val scale = maxOf(
            rect.width() / texture.width.toFloat(),
            rect.height() / texture.height.toFloat()
        )
        val xPhase = ((seed and 0x3F) / 64f) * texture.width * scale
        val yPhase = (((seed ushr 6) and 0x3F) / 64f) * texture.height * scale
        textureMatrix.reset()
        textureMatrix.setScale(scale, scale)
        textureMatrix.postTranslate(rect.left - xPhase, rect.top - yPhase)
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
        texturePaint.alpha = (TEXTURE_ALPHA * alpha / 255).coerceIn(0, 255)
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

    private companion object {
        const val TEXTURE_ALPHA = 72
    }
}
