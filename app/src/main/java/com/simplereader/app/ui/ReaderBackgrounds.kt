package com.simplereader.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.simplereader.app.R
import kotlin.math.max

/**
 * v585 分层阅读背景。
 *
 * 颜色、纹理、质感分别保存和组合：
 * - 颜色只决定底色与正文颜色；
 * - 纹理是可关闭的真实位图表面纹理；
 * - 质感是可关闭的独立明暗/表面层。
 *
 * 位图素材来自 CC0 资源，来源记录在 THIRD_PARTY_TEXTURES.md。
 */
object ReaderBackgrounds {
    const val DEFAULT_COLOR_ID = "solid_ivory"
    const val DEFAULT_TEXTURE_ID = "texture_paper_grain"
    const val DEFAULT_MATERIAL_ID = "material_none"
    const val NONE_TEXTURE_ID = "texture_none"
    const val NONE_MATERIAL_ID = "material_none"

    enum class Category(val title: String) {
        COLOR("纯色"),
        TEXTURE("纹理"),
        MATERIAL("质感")
    }

    data class ColorOption(
        val id: String,
        val title: String,
        val backgroundColor: Int,
        val textColor: Int
    )

    enum class TextureEffect {
        NONE,
        PAPER_GRAIN,
        PAPER_FIBER
    }

    data class TextureOption(
        val id: String,
        val title: String,
        val effect: TextureEffect,
        val drawableRes: Int? = null,
        val alpha: Int = 0
    )

    enum class MaterialEffect {
        NONE,
        MATTE,
        FROSTED,
        PATINA
    }

    data class MaterialOption(
        val id: String,
        val title: String,
        val effect: MaterialEffect,
        val drawableRes: Int? = null,
        val alpha: Int = 0
    )

    data class Selection(
        val colorId: String = DEFAULT_COLOR_ID,
        val textureId: String = DEFAULT_TEXTURE_ID,
        val materialId: String = DEFAULT_MATERIAL_ID
    )

    val colorOptions: List<ColorOption> = listOf(
        ColorOption("solid_ivory", "象牙白", Color.rgb(248, 244, 232), Color.rgb(52, 48, 40)),
        ColorOption("solid_eye", "护眼绿", Color.rgb(218, 238, 205), Color.rgb(48, 60, 42)),
        ColorOption("solid_white", "净白", Color.WHITE, Color.rgb(35, 35, 35)),
        ColorOption("solid_mist", "雾灰", Color.rgb(232, 234, 231), Color.rgb(49, 51, 49)),
        ColorOption("solid_peach", "浅桃", Color.rgb(246, 229, 221), Color.rgb(66, 48, 43)),
        ColorOption("solid_blue", "浅蓝", Color.rgb(224, 237, 243), Color.rgb(40, 54, 61))
    )

    val textureOptions: List<TextureOption> = listOf(
        TextureOption(NONE_TEXTURE_ID, "纯净", TextureEffect.NONE),
        TextureOption(
            id = "texture_paper_grain",
            title = "纸张颗粒",
            effect = TextureEffect.PAPER_GRAIN,
            drawableRes = R.drawable.reader_texture_paper_grain,
            alpha = 112
        ),
        TextureOption(
            id = "texture_paper_fiber",
            title = "宣纸纤维",
            effect = TextureEffect.PAPER_FIBER,
            drawableRes = R.drawable.reader_texture_paper_fiber,
            alpha = 124
        )
    )

    val materialOptions: List<MaterialOption> = listOf(
        MaterialOption(NONE_MATERIAL_ID, "纯净", MaterialEffect.NONE),
        MaterialOption("material_matte", "柔和哑光", MaterialEffect.MATTE),
        MaterialOption(
            id = "material_frosted",
            title = "雾面",
            effect = MaterialEffect.FROSTED,
            drawableRes = R.drawable.reader_material_frosted,
            alpha = 76
        ),
        MaterialOption(
            id = "material_patina",
            title = "旧纸质感",
            effect = MaterialEffect.PATINA,
            drawableRes = R.drawable.reader_material_patina,
            alpha = 84
        )
    )

    val quickColorIds: List<String> = listOf(DEFAULT_COLOR_ID, "solid_eye", "solid_white")

    private val nightColor = ColorOption(
        id = "night_soft",
        title = "夜间",
        backgroundColor = Color.rgb(32, 33, 36),
        textColor = Color.rgb(232, 234, 237)
    )

    fun color(id: String?): ColorOption =
        colorOptions.firstOrNull { it.id == id } ?: colorOptions.first { it.id == DEFAULT_COLOR_ID }

    fun texture(id: String?): TextureOption =
        textureOptions.firstOrNull { it.id == id } ?: textureOptions.first { it.id == DEFAULT_TEXTURE_ID }

    fun material(id: String?): MaterialOption =
        materialOptions.firstOrNull { it.id == id } ?: materialOptions.first { it.id == DEFAULT_MATERIAL_ID }

    fun validated(selection: Selection): Selection = Selection(
        colorId = color(selection.colorId).id,
        textureId = texture(selection.textureId).id,
        materialId = material(selection.materialId).id
    )

    fun closestColorId(backgroundColor: Int): String {
        fun distance(candidate: Int): Long {
            val dr = Color.red(candidate) - Color.red(backgroundColor)
            val dg = Color.green(candidate) - Color.green(backgroundColor)
            val db = Color.blue(candidate) - Color.blue(backgroundColor)
            return (dr * dr + dg * dg + db * db).toLong()
        }
        return colorOptions.minByOrNull { distance(it.backgroundColor) }?.id ?: DEFAULT_COLOR_ID
    }

    /** 将 v582-v584 的互斥预设迁移成 v585 的三层组合。 */
    fun selectionFromLegacy(styleId: String?, paletteBackground: Int): Selection {
        return validated(
            when (styleId) {
                "solid_ivory" -> Selection("solid_ivory", NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "solid_eye" -> Selection("solid_eye", NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "solid_white" -> Selection("solid_white", NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "solid_mist" -> Selection("solid_mist", NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "solid_peach" -> Selection("solid_peach", NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "solid_blue" -> Selection("solid_blue", NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "texture_paper" -> Selection("solid_ivory", "texture_paper_grain", NONE_MATERIAL_ID)
                "texture_linen" -> Selection("solid_ivory", "texture_paper_grain", NONE_MATERIAL_ID)
                "texture_fiber" -> Selection("solid_ivory", "texture_paper_fiber", NONE_MATERIAL_ID)
                "texture_green" -> Selection("solid_eye", "texture_paper_grain", NONE_MATERIAL_ID)
                "texture_grey" -> Selection("solid_mist", "texture_paper_grain", NONE_MATERIAL_ID)
                "material_parchment" -> Selection("solid_ivory", NONE_TEXTURE_ID, "material_patina")
                "material_cloud" -> Selection("solid_blue", NONE_TEXTURE_ID, "material_frosted")
                "material_warm" -> Selection("solid_peach", NONE_TEXTURE_ID, "material_matte")
                "material_jade" -> Selection("solid_eye", NONE_TEXTURE_ID, "material_frosted")
                else -> Selection(
                    colorId = closestColorId(paletteBackground),
                    textureId = DEFAULT_TEXTURE_ID,
                    materialId = DEFAULT_MATERIAL_ID
                )
            }
        )
    }

    fun summary(selection: Selection): String {
        val safe = validated(selection)
        return "${color(safe.colorId).title} + ${texture(safe.textureId).title} + ${material(safe.materialId).title}"
    }

    fun drawable(context: Context, selection: Selection): Drawable {
        val safe = validated(selection)
        return LayeredReaderBackgroundDrawable(
            context = context,
            color = color(safe.colorId),
            texture = texture(safe.textureId),
            material = material(safe.materialId)
        )
    }

    fun nightDrawable(context: Context): Drawable = LayeredReaderBackgroundDrawable(
        context = context,
        color = nightColor,
        texture = texture(NONE_TEXTURE_ID),
        material = material(NONE_MATERIAL_ID)
    )

    fun previewDrawable(context: Context, selection: Selection, selected: Boolean): Drawable =
        ReaderBackgroundPreviewDrawable(context, validated(selection), selected)
}

private object ReaderBackgroundBitmapCache {
    private val cache = mutableMapOf<Int, Bitmap>()

    fun bitmap(context: Context, resId: Int): Bitmap? = synchronized(cache) {
        cache[resId] ?: BitmapFactory.decodeResource(context.resources, resId)?.also { cache[resId] = it }
    }
}

private class LayeredReaderBackgroundDrawable(
    private val context: Context,
    private val color: ReaderBackgrounds.ColorOption,
    private val texture: ReaderBackgrounds.TextureOption,
    private val material: ReaderBackgrounds.MaterialOption
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LayeredReaderBackgroundDrawable.color.backgroundColor }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        canvas.drawRect(area, fillPaint)
        drawTexture(canvas, area)
        drawMaterial(canvas, area)
    }

    private fun drawTexture(canvas: Canvas, area: Rect) {
        when (texture.effect) {
            ReaderBackgrounds.TextureEffect.NONE -> Unit
            ReaderBackgrounds.TextureEffect.PAPER_GRAIN,
            ReaderBackgrounds.TextureEffect.PAPER_FIBER -> {
                val resId = texture.drawableRes ?: return
                drawBitmapLayer(
                    canvas = canvas,
                    area = area,
                    resId = resId,
                    targetTilePx = 260f * density,
                    alpha = texture.alpha,
                    mode = PorterDuff.Mode.OVERLAY
                )
            }
        }
    }

    private fun drawMaterial(canvas: Canvas, area: Rect) {
        when (material.effect) {
            ReaderBackgrounds.MaterialEffect.NONE -> Unit
            ReaderBackgrounds.MaterialEffect.MATTE -> drawMatte(canvas, area)
            ReaderBackgrounds.MaterialEffect.FROSTED -> {
                val resId = material.drawableRes ?: return
                drawBitmapLayer(
                    canvas = canvas,
                    area = area,
                    resId = resId,
                    targetTilePx = 520f * density,
                    alpha = material.alpha,
                    mode = PorterDuff.Mode.SCREEN
                )
                drawSoftHighlight(canvas, area, 24)
            }
            ReaderBackgrounds.MaterialEffect.PATINA -> {
                val resId = material.drawableRes ?: return
                drawBitmapLayer(
                    canvas = canvas,
                    area = area,
                    resId = resId,
                    targetTilePx = 620f * density,
                    alpha = material.alpha,
                    mode = PorterDuff.Mode.MULTIPLY
                )
                drawEdgePatina(canvas, area)
            }
        }
    }

    private fun drawBitmapLayer(
        canvas: Canvas,
        area: Rect,
        resId: Int,
        targetTilePx: Float,
        alpha: Int,
        mode: PorterDuff.Mode
    ) {
        val bitmap = ReaderBackgroundBitmapCache.bitmap(context, resId) ?: return
        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val scale = targetTilePx / bitmap.width.coerceAtLeast(1).toFloat()
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(area.left.toFloat(), area.top.toFloat())
        shader.setLocalMatrix(matrix)
        bitmapPaint.shader = shader
        bitmapPaint.alpha = (alpha * globalAlpha / 255).coerceIn(0, 255)
        bitmapPaint.xfermode = PorterDuffXfermode(mode)
        canvas.drawRect(area, bitmapPaint)
        bitmapPaint.shader = null
        bitmapPaint.xfermode = null
    }

    private fun drawMatte(canvas: Canvas, area: Rect) {
        effectPaint.shader = LinearGradient(
            area.left.toFloat(),
            area.top.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
            intArrayOf(
                Color.argb(34 * globalAlpha / 255, 255, 255, 255),
                Color.argb(4 * globalAlpha / 255, 255, 255, 255),
                Color.argb(28 * globalAlpha / 255, 68, 59, 48)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, effectPaint)
        effectPaint.shader = null
        drawSoftHighlight(canvas, area, 18)
    }

    private fun drawSoftHighlight(canvas: Canvas, area: Rect, strength: Int) {
        effectPaint.shader = RadialGradient(
            area.exactCenterX(),
            area.top + area.height() * 0.30f,
            max(area.width(), area.height()).toFloat() * 0.82f,
            intArrayOf(
                Color.argb(strength * globalAlpha / 255, 255, 255, 255),
                Color.TRANSPARENT,
                Color.argb((strength / 2) * globalAlpha / 255, 52, 48, 42)
            ),
            floatArrayOf(0f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, effectPaint)
        effectPaint.shader = null
    }

    private fun drawEdgePatina(canvas: Canvas, area: Rect) {
        effectPaint.shader = RadialGradient(
            area.exactCenterX(),
            area.exactCenterY(),
            max(area.width(), area.height()).toFloat() * 0.72f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(18 * globalAlpha / 255, 101, 72, 39),
                Color.argb(46 * globalAlpha / 255, 72, 47, 22)
            ),
            floatArrayOf(0.48f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, effectPaint)
        effectPaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        fillPaint.alpha = globalAlpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
        effectPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class ReaderBackgroundPreviewDrawable(
    context: Context,
    selection: ReaderBackgrounds.Selection,
    private val selected: Boolean
) : Drawable() {
    private val background = ReaderBackgrounds.drawable(context, selection)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 5f else 2f
        color = if (selected) Color.rgb(239, 122, 40) else Color.argb(80, 92, 84, 72)
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
            9f,
            9f,
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
