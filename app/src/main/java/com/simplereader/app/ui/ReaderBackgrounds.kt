package com.simplereader.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.simplereader.app.R

/**
 * 阅读背景保持“纯色 + 纹理 + 质感”三层独立组合。
 *
 * 纹理层直接使用用户上传包中的 1080×2340 完整基础纹理图，按阅读区域等比裁切，
 * 不再把 97×97 选择器缩略图放大平铺，也不再程序生成圆点、纸纤维或纯色替代纹理。
 */
object ReaderBackgrounds {
    const val DEFAULT_COLOR_ID = "solid_ivory"
    const val DEFAULT_TEXTURE_ID = "texture_duokan_green"
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
        FULL_BITMAP
    }

    data class TextureOption(
        val id: String,
        val title: String,
        val effect: TextureEffect,
        val drawableRes: Int? = null,
        val alpha: Int = 0,
        val tileDp: Float = 96f,
        val blendMode: PorterDuff.Mode = PorterDuff.Mode.SRC_OVER
    )

    enum class MaterialEffect {
        NONE,
        TILED_BITMAP
    }

    data class MaterialOption(
        val id: String,
        val title: String,
        val effect: MaterialEffect,
        val drawableRes: Int? = null,
        val alpha: Int = 0,
        val tileDp: Float = 220f,
        val blendMode: PorterDuff.Mode = PorterDuff.Mode.OVERLAY
    )

    data class Selection(
        val colorId: String = DEFAULT_COLOR_ID,
        val textureId: String = DEFAULT_TEXTURE_ID,
        val materialId: String = DEFAULT_MATERIAL_ID
    )

    val colorOptions: List<ColorOption> = listOf(
        ColorOption("solid_ivory", "米黄", Color.rgb(231, 222, 191), Color.rgb(57, 51, 39)),
        ColorOption("solid_eye", "护眼绿", Color.rgb(203, 224, 205), Color.rgb(42, 57, 43)),
        ColorOption("solid_white", "柔白", Color.rgb(240, 240, 240), Color.rgb(35, 35, 35)),
        ColorOption("solid_blue", "浅蓝", Color.rgb(205, 220, 240), Color.rgb(38, 52, 67)),
        ColorOption("solid_peach", "暖棕", Color.rgb(225, 196, 162), Color.rgb(66, 47, 34)),
        ColorOption("solid_mist", "灰褐", Color.rgb(210, 188, 166), Color.rgb(61, 48, 39)),
        ColorOption("solid_night", "夜间黑", Color.rgb(6, 17, 27), Color.rgb(224, 230, 236))
    )

    val textureOptions: List<TextureOption> = listOf(
        TextureOption(NONE_TEXTURE_ID, "纯净", TextureEffect.NONE),
        TextureOption(
            id = "texture_duokan_green",
            title = "基础纸张纹理",
            effect = TextureEffect.FULL_BITMAP,
            drawableRes = R.drawable.reader_texture_duokan_green,
            alpha = 255
        )
    )

    val materialOptions: List<MaterialOption> = listOf(
        MaterialOption(NONE_MATERIAL_ID, "纯净", MaterialEffect.NONE),
        MaterialOption(
            id = "material_duokan_paper",
            title = "纸张质感",
            effect = MaterialEffect.TILED_BITMAP,
            drawableRes = R.drawable.reader_material_duokan_paper,
            alpha = 76
        ),
        MaterialOption(
            id = "material_duokan_grey",
            title = "灰纸质感",
            effect = MaterialEffect.TILED_BITMAP,
            drawableRes = R.drawable.reader_material_duokan_grey,
            alpha = 84
        ),
        MaterialOption(
            id = "material_duokan_green",
            title = "绿纹质感",
            effect = MaterialEffect.TILED_BITMAP,
            drawableRes = R.drawable.reader_material_duokan_green,
            alpha = 78
        ),
        MaterialOption(
            id = "material_duokan_white",
            title = "浅纸质感",
            effect = MaterialEffect.TILED_BITMAP,
            drawableRes = R.drawable.reader_material_duokan_white,
            alpha = 82
        ),
        MaterialOption(
            id = "material_duokan_warm",
            title = "暖纸质感",
            effect = MaterialEffect.TILED_BITMAP,
            drawableRes = R.drawable.reader_material_duokan_warm,
            alpha = 88
        ),
        MaterialOption(
            id = "material_duokan_dark",
            title = "暗纹质感",
            effect = MaterialEffect.TILED_BITMAP,
            drawableRes = R.drawable.reader_material_duokan_dark,
            alpha = 92
        )
    )

    val quickColorIds: List<String> = listOf(DEFAULT_COLOR_ID, "solid_eye", "solid_white")

    private val textureAliases = mapOf(
        "texture_paper_grain" to "texture_duokan_green",
        "texture_paper_fiber" to "texture_duokan_green",
        "texture_paper" to "texture_duokan_green",
        "texture_linen" to "texture_duokan_green",
        "texture_fiber" to "texture_duokan_green",
        "texture_blue" to "texture_duokan_green",
        "texture_green" to "texture_duokan_green",
        "texture_grey" to "texture_duokan_green",
        "texture_duokan_blue" to "texture_duokan_green",
        "texture_duokan_white" to "texture_duokan_green",
        "texture_duokan_yellow" to "texture_duokan_green"
    )

    private val materialAliases = mapOf(
        "material_matte" to "material_duokan_paper",
        "material_frosted" to "material_duokan_grey",
        "material_parchment" to "material_duokan_paper",
        "material_cloud" to "material_duokan_white",
        "material_warm" to "material_duokan_warm",
        "material_jade" to "material_duokan_green"
    )

    fun color(id: String?): ColorOption =
        colorOptions.firstOrNull { it.id == id } ?: colorOptions.first { it.id == DEFAULT_COLOR_ID }

    fun texture(id: String?): TextureOption {
        val safeId = textureAliases[id] ?: id
        return textureOptions.firstOrNull { it.id == safeId }
            ?: textureOptions.first { it.id == DEFAULT_TEXTURE_ID }
    }

    fun material(id: String?): MaterialOption {
        val safeId = materialAliases[id] ?: id
        return materialOptions.firstOrNull { it.id == safeId }
            ?: materialOptions.first { it.id == DEFAULT_MATERIAL_ID }
    }

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

    fun selectionFromLegacy(styleId: String?, paletteBackground: Int): Selection {
        return validated(
            when (styleId) {
                "solid_ivory", "solid_eye", "solid_white", "solid_mist", "solid_peach", "solid_blue" ->
                    Selection(styleId, NONE_TEXTURE_ID, NONE_MATERIAL_ID)
                "texture_paper", "texture_linen" ->
                    Selection("solid_ivory", "texture_duokan_green", NONE_MATERIAL_ID)
                "texture_fiber" ->
                    Selection("solid_white", "texture_duokan_green", NONE_MATERIAL_ID)
                "texture_green" ->
                    Selection("solid_eye", "texture_duokan_green", NONE_MATERIAL_ID)
                "texture_grey" ->
                    Selection("solid_mist", "texture_duokan_green", NONE_MATERIAL_ID)
                "material_parchment" ->
                    Selection("solid_ivory", NONE_TEXTURE_ID, "material_duokan_paper")
                "material_cloud" ->
                    Selection("solid_blue", NONE_TEXTURE_ID, "material_duokan_white")
                "material_warm" ->
                    Selection("solid_peach", NONE_TEXTURE_ID, "material_duokan_warm")
                "material_jade" ->
                    Selection("solid_eye", NONE_TEXTURE_ID, "material_duokan_green")
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
        color = color("solid_night"),
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
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@LayeredReaderBackgroundDrawable.color.backgroundColor
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        canvas.drawRect(area, fillPaint)
        if (texture.effect == ReaderBackgrounds.TextureEffect.FULL_BITMAP) {
            drawFullBitmap(
                canvas = canvas,
                area = area,
                resId = texture.drawableRes ?: return,
                alpha = texture.alpha,
                mode = texture.blendMode
            )
        }
        if (material.effect == ReaderBackgrounds.MaterialEffect.TILED_BITMAP) {
            drawTiled(
                canvas = canvas,
                area = area,
                resId = material.drawableRes ?: return,
                tileDp = material.tileDp,
                alpha = material.alpha,
                mode = material.blendMode
            )
        }
    }

    private fun drawFullBitmap(
        canvas: Canvas,
        area: Rect,
        resId: Int,
        alpha: Int,
        mode: PorterDuff.Mode
    ) {
        val bitmap = ReaderBackgroundBitmapCache.bitmap(context, resId) ?: return
        val source = Rect(0, 0, bitmap.width, bitmap.height)
        val destination = RectF(area)
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val areaAspect = area.width().toFloat() / area.height().coerceAtLeast(1).toFloat()
        if (bitmapAspect > areaAspect) {
            val targetWidth = (bitmap.height * areaAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - targetWidth) / 2
            source.set(left, 0, left + targetWidth, bitmap.height)
        } else {
            val targetHeight = (bitmap.width / areaAspect.coerceAtLeast(0.01f)).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - targetHeight) / 2
            source.set(0, top, bitmap.width, top + targetHeight)
        }
        bitmapPaint.shader = null
        bitmapPaint.alpha = (alpha * globalAlpha / 255).coerceIn(0, 255)
        bitmapPaint.xfermode = PorterDuffXfermode(mode)
        canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
        bitmapPaint.xfermode = null
    }

    private fun drawTiled(
        canvas: Canvas,
        area: Rect,
        resId: Int,
        tileDp: Float,
        alpha: Int,
        mode: PorterDuff.Mode
    ) {
        val bitmap = ReaderBackgroundBitmapCache.bitmap(context, resId) ?: return
        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val targetTilePx = tileDp * density
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

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        fillPaint.alpha = globalAlpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
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
