package com.simplereader.app.ui

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * v582 阅读背景库。
 *
 * 多看只作为“快捷背景 + 更多分类”的交互参考；所有纹理均由简阅自行绘制，
 * 不包含或复制第三方专有图片资源。
 */
object ReaderBackgrounds {
    const val DEFAULT_ID = "texture_paper"
    const val NIGHT_ID = "night_soft"

    enum class Category(val title: String) {
        SOLID("纯色"),
        TEXTURE("纹理"),
        MATERIAL("质感")
    }

    enum class Effect {
        SOLID,
        PAPER,
        LINEN,
        FIBER,
        SOFT_GRAIN,
        PARCHMENT,
        CLOUD,
        WARM_VIGNETTE,
        NIGHT_SOFT
    }

    data class Preset(
        val id: String,
        val title: String,
        val category: Category,
        val backgroundColor: Int,
        val textColor: Int,
        val effect: Effect
    )

    val presets: List<Preset> = listOf(
        Preset("solid_ivory", "象牙白", Category.SOLID, Color.rgb(248, 244, 232), Color.rgb(52, 48, 40), Effect.SOLID),
        Preset("solid_eye", "护眼绿", Category.SOLID, Color.rgb(218, 238, 205), Color.rgb(48, 60, 42), Effect.SOLID),
        Preset("solid_white", "净白", Category.SOLID, Color.WHITE, Color.rgb(35, 35, 35), Effect.SOLID),
        Preset("solid_mist", "雾灰", Category.SOLID, Color.rgb(232, 234, 231), Color.rgb(49, 51, 49), Effect.SOLID),
        Preset("solid_peach", "浅桃", Category.SOLID, Color.rgb(246, 229, 221), Color.rgb(66, 48, 43), Effect.SOLID),
        Preset("solid_blue", "浅蓝", Category.SOLID, Color.rgb(224, 237, 243), Color.rgb(40, 54, 61), Effect.SOLID),

        Preset("texture_paper", "经典纸张", Category.TEXTURE, Color.rgb(245, 233, 200), Color.rgb(59, 52, 40), Effect.PAPER),
        Preset("texture_linen", "细麻纹", Category.TEXTURE, Color.rgb(239, 232, 212), Color.rgb(58, 52, 43), Effect.LINEN),
        Preset("texture_fiber", "宣纸纤维", Category.TEXTURE, Color.rgb(244, 239, 222), Color.rgb(57, 52, 43), Effect.FIBER),
        Preset("texture_green", "青禾纸", Category.TEXTURE, Color.rgb(224, 235, 214), Color.rgb(47, 59, 43), Effect.SOFT_GRAIN),
        Preset("texture_grey", "柔灰纸", Category.TEXTURE, Color.rgb(228, 228, 222), Color.rgb(50, 50, 47), Effect.SOFT_GRAIN),

        Preset("material_parchment", "羊皮纸", Category.MATERIAL, Color.rgb(229, 205, 158), Color.rgb(67, 51, 30), Effect.PARCHMENT),
        Preset("material_cloud", "云雾", Category.MATERIAL, Color.rgb(229, 237, 235), Color.rgb(43, 54, 53), Effect.CLOUD),
        Preset("material_warm", "暖绒", Category.MATERIAL, Color.rgb(238, 220, 199), Color.rgb(64, 48, 38), Effect.WARM_VIGNETTE),
        Preset("material_jade", "浅玉", Category.MATERIAL, Color.rgb(215, 232, 220), Color.rgb(42, 58, 49), Effect.CLOUD)
    )

    val quickIds: List<String> = listOf(DEFAULT_ID, "solid_eye", "solid_white")

    val nightPreset = Preset(
        NIGHT_ID,
        "夜间",
        Category.SOLID,
        Color.rgb(32, 33, 36),
        Color.rgb(232, 234, 237),
        Effect.NIGHT_SOFT
    )

    fun preset(id: String?): Preset = presets.firstOrNull { it.id == id } ?: presets.first { it.id == DEFAULT_ID }

    fun closestId(backgroundColor: Int): String {
        fun distance(candidate: Int): Long {
            val dr = Color.red(candidate) - Color.red(backgroundColor)
            val dg = Color.green(candidate) - Color.green(backgroundColor)
            val db = Color.blue(candidate) - Color.blue(backgroundColor)
            return (dr * dr + dg * dg + db * db).toLong()
        }
        return presets.minByOrNull { distance(it.backgroundColor) }?.id ?: DEFAULT_ID
    }

    fun presets(category: Category): List<Preset> = presets.filter { it.category == category }

    fun drawable(preset: Preset): Drawable = ReaderBackgroundDrawable(preset)

    fun previewDrawable(id: String, selected: Boolean): Drawable =
        ReaderBackgroundPreviewDrawable(preset(id), selected)
}

private class ReaderBackgroundDrawable(
    private val preset: ReaderBackgrounds.Preset
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.backgroundColor }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 72
        xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    }
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        canvas.drawRect(area, fillPaint)
        when (preset.effect) {
            ReaderBackgrounds.Effect.SOLID -> Unit
            ReaderBackgrounds.Effect.PAPER -> drawPaper(canvas, area)
            ReaderBackgrounds.Effect.LINEN -> drawLinen(canvas, area)
            ReaderBackgrounds.Effect.FIBER -> drawFiber(canvas, area)
            ReaderBackgrounds.Effect.SOFT_GRAIN -> drawSoftGrain(canvas, area)
            ReaderBackgrounds.Effect.PARCHMENT -> drawParchment(canvas, area)
            ReaderBackgrounds.Effect.CLOUD -> drawCloud(canvas, area)
            ReaderBackgrounds.Effect.WARM_VIGNETTE -> drawWarmVignette(canvas, area)
            ReaderBackgrounds.Effect.NIGHT_SOFT -> drawNight(canvas, area)
        }
    }

    private fun drawPaper(canvas: Canvas, area: Rect) {
        val texture = PaperTextureData.bitmap()
        val shader = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(
            area.width().toFloat() / texture.width.toFloat(),
            area.height().toFloat() / texture.height.toFloat()
        )
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            area.left + (area.width() - texture.width * scale) / 2f,
            area.top + (area.height() - texture.height * scale) / 2f
        )
        shader.setLocalMatrix(matrix)
        texturePaint.shader = shader
        texturePaint.alpha = 72
        canvas.drawRect(area, texturePaint)
        texturePaint.shader = null
    }

    private fun drawLinen(canvas: Canvas, area: Rect) {
        detailPaint.strokeWidth = 1f
        var y = area.top.toFloat()
        var index = 0
        while (y <= area.bottom) {
            detailPaint.color = if (index % 2 == 0) {
                Color.argb(18, 255, 255, 255)
            } else {
                Color.argb(12, 66, 55, 42)
            }
            canvas.drawLine(area.left.toFloat(), y, area.right.toFloat(), y, detailPaint)
            y += 5f
            index++
        }
        var x = area.left.toFloat()
        index = 0
        while (x <= area.right) {
            detailPaint.color = if (index % 3 == 0) {
                Color.argb(12, 255, 255, 255)
            } else {
                Color.argb(8, 70, 59, 48)
            }
            canvas.drawLine(x, area.top.toFloat(), x, area.bottom.toFloat(), detailPaint)
            x += 7f
            index++
        }
    }

    private fun drawFiber(canvas: Canvas, area: Rect) {
        var state = 0x13579BDF
        repeat(120) { index ->
            state = state * 1103515245 + 12345
            val x = area.left + ((state ushr 8) and 0x7FFF) % area.width().coerceAtLeast(1)
            state = state * 1103515245 + 12345
            val y = area.top + ((state ushr 8) and 0x7FFF) % area.height().coerceAtLeast(1)
            val length = 8f + (index % 17)
            detailPaint.strokeWidth = if (index % 5 == 0) 1.2f else 0.7f
            detailPaint.color = if (index % 4 == 0) {
                Color.argb(24, 118, 101, 72)
            } else {
                Color.argb(22, 255, 255, 255)
            }
            canvas.drawLine(x.toFloat(), y.toFloat(), x + length, y.toFloat() + (index % 3 - 1).toFloat(), detailPaint)
        }
    }

    private fun drawSoftGrain(canvas: Canvas, area: Rect) {
        var state = 0x2468ACE
        repeat(180) { index ->
            state = state * 1664525 + 1013904223
            val x = area.left + ((state ushr 9) and 0x7FFF) % area.width().coerceAtLeast(1)
            state = state * 1664525 + 1013904223
            val y = area.top + ((state ushr 9) and 0x7FFF) % area.height().coerceAtLeast(1)
            detailPaint.color = if (index % 3 == 0) {
                Color.argb(14, 65, 73, 60)
            } else {
                Color.argb(18, 255, 255, 255)
            }
            canvas.drawCircle(x.toFloat(), y.toFloat(), if (index % 7 == 0) 1.3f else 0.8f, detailPaint)
        }
    }

    private fun drawParchment(canvas: Canvas, area: Rect) {
        detailPaint.shader = RadialGradient(
            area.exactCenterX(),
            area.exactCenterY(),
            max(area.width(), area.height()).toFloat() * 0.72f,
            intArrayOf(Color.argb(0, 255, 250, 223), Color.argb(28, 102, 68, 31), Color.argb(64, 80, 48, 18)),
            floatArrayOf(0.36f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, detailPaint)
        detailPaint.shader = null
        drawFiber(canvas, area)
    }

    private fun drawCloud(canvas: Canvas, area: Rect) {
        detailPaint.shader = LinearGradient(
            area.left.toFloat(),
            area.top.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
            intArrayOf(Color.argb(42, 255, 255, 255), Color.argb(8, 255, 255, 255), Color.argb(24, 83, 109, 103)),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, detailPaint)
        detailPaint.shader = null
        repeat(8) { index ->
            val cx = area.left + area.width() * ((index * 37 % 100) / 100f)
            val cy = area.top + area.height() * ((index * 61 % 100) / 100f)
            detailPaint.color = Color.argb(10 + index % 3 * 4, 255, 255, 255)
            canvas.drawCircle(cx, cy, area.width() * (0.16f + (index % 3) * 0.03f), detailPaint)
        }
    }

    private fun drawWarmVignette(canvas: Canvas, area: Rect) {
        detailPaint.shader = RadialGradient(
            area.exactCenterX(),
            area.top + area.height() * 0.28f,
            max(area.width(), area.height()).toFloat(),
            intArrayOf(Color.argb(38, 255, 248, 232), Color.argb(0, 255, 255, 255), Color.argb(36, 115, 75, 49)),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, detailPaint)
        detailPaint.shader = null
        drawSoftGrain(canvas, area)
    }

    private fun drawNight(canvas: Canvas, area: Rect) {
        detailPaint.shader = LinearGradient(
            area.left.toFloat(),
            area.top.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
            intArrayOf(Color.argb(16, 83, 88, 96), Color.argb(0, 0, 0, 0), Color.argb(18, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, detailPaint)
        detailPaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        detailPaint.alpha = alpha
        texturePaint.alpha = (72 * alpha / 255).coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        detailPaint.colorFilter = colorFilter
        texturePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class ReaderBackgroundPreviewDrawable(
    private val preset: ReaderBackgrounds.Preset,
    private val selected: Boolean
) : Drawable() {
    private val background = ReaderBackgroundDrawable(preset)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 5f else 2f
        color = if (selected) Color.rgb(239, 122, 40) else Color.argb(70, 255, 255, 255)
    }

    override fun draw(canvas: Canvas) {
        background.bounds = bounds
        background.draw(canvas)
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
            8f,
            8f,
            borderPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        background.alpha = alpha
        borderPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        background.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
