package com.simplereader.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.simplereader.app.R

/**
 * Confirmed v625 background model.
 * Exactly one complete background bitmap is selected at a time.
 * No layer stacking, tiling, generated texture, thumbnail enlargement, or fallback substitution.
 */
object ReaderBackgrounds {
    const val DEFAULT_COLOR_ID = "scene_yellow"
    const val DEFAULT_TEXTURE_ID = "texture_yellow"
    const val DEFAULT_MATERIAL_ID = "vine_yellow"

    enum class Category(val title: String) {
        COLOR("纯色"),
        TEXTURE("纹理"),
        MATERIAL("质感")
    }

    data class BackgroundOption(
        val id: String,
        val title: String,
        val category: Category,
        val drawableRes: Int,
        val representativeColor: Int,
        val textColor: Int
    )

    data class Selection(
        val category: Category = Category.COLOR,
        val optionId: String = DEFAULT_COLOR_ID
    )

    private val colorOptions = listOf(
        BackgroundOption("scene_yellow", "米黄", Category.COLOR, R.drawable.reader_scene_duokan_yellow, Color.rgb(229, 221, 190), Color.rgb(57, 51, 39)),
        BackgroundOption("scene_green", "护眼绿", Category.COLOR, R.drawable.reader_scene_duokan_green, Color.rgb(202, 224, 205), Color.rgb(42, 57, 43)),
        BackgroundOption("scene_white", "柔白", Category.COLOR, R.drawable.reader_scene_duokan_white, Color.rgb(240, 240, 240), Color.rgb(35, 35, 35)),
        BackgroundOption("scene_blue", "浅蓝", Category.COLOR, R.drawable.reader_scene_duokan_blue, Color.rgb(204, 219, 240), Color.rgb(38, 52, 67)),
        BackgroundOption("scene_black", "夜间黑", Category.COLOR, R.drawable.reader_scene_duokan_black, Color.rgb(9, 21, 31), Color.rgb(224, 230, 236))
    )

    private val textureOptions = listOf(
        BackgroundOption("texture_yellow", "米黄纹理", Category.TEXTURE, R.drawable.reader_texture_theme_yellow, Color.rgb(228, 206, 173), Color.rgb(57, 48, 37)),
        BackgroundOption("texture_green", "绿色纹理", Category.TEXTURE, R.drawable.reader_texture_theme_green, Color.rgb(202, 213, 205), Color.rgb(42, 54, 45)),
        BackgroundOption("texture_white", "白色纹理", Category.TEXTURE, R.drawable.reader_texture_theme_white, Color.rgb(244, 242, 250), Color.rgb(35, 35, 38)),
        BackgroundOption("texture_blue", "蓝色纹理", Category.TEXTURE, R.drawable.reader_texture_theme_blue, Color.rgb(199, 211, 227), Color.rgb(38, 49, 64))
    )

    private val materialOptions = listOf(
        BackgroundOption("vine_yellow", "米黄质感", Category.MATERIAL, R.drawable.reader_material_vine_yellow, Color.rgb(250, 226, 192), Color.rgb(65, 49, 34)),
        BackgroundOption("vine_green", "绿色质感", Category.MATERIAL, R.drawable.reader_material_vine_green, Color.rgb(200, 223, 202), Color.rgb(42, 57, 43)),
        BackgroundOption("vine_white", "白色质感", Category.MATERIAL, R.drawable.reader_material_vine_white, Color.rgb(231, 231, 231), Color.rgb(38, 38, 38)),
        BackgroundOption("vine_blue", "蓝色质感", Category.MATERIAL, R.drawable.reader_material_vine_blue, Color.rgb(184, 199, 220), Color.rgb(35, 48, 63)),
        BackgroundOption("vine_black", "黑色质感", Category.MATERIAL, R.drawable.reader_material_vine_black, Color.rgb(32, 32, 34), Color.rgb(232, 232, 234))
    )

    val quickColorIds: List<String> = listOf("scene_yellow", "scene_green", "scene_white")

    private val optionsByCategory = mapOf(
        Category.COLOR to colorOptions,
        Category.TEXTURE to textureOptions,
        Category.MATERIAL to materialOptions
    )

    fun options(category: Category): List<BackgroundOption> = optionsByCategory[category].orEmpty()

    fun selection(category: Category, optionId: String): Selection = validated(Selection(category, optionId))

    /**
     * Exact shipped-v745 compatibility path for preferences written by the older
     * background picker.  Material wins over texture, texture wins over colour;
     * when no legacy id is known, the old palette colour is matched to the nearest
     * v745 scene colour.
     */
    fun selectionFromLegacy(
        legacyColorId: String?,
        legacyTextureId: String?,
        legacyMaterialId: String?,
        legacyBackgroundColor: Int
    ): Selection {
        val material = mapOf(
            "material_duokan_dark" to "vine_black",
            "material_duokan_green" to "vine_green",
            "material_duokan_grey" to "vine_white",
            "material_duokan_paper" to "vine_yellow",
            "material_duokan_warm" to "vine_yellow",
            "material_duokan_white" to "vine_white",
            "material_parchment" to "vine_yellow",
            "material_cloud" to "vine_white",
            "material_warm" to "vine_yellow",
            "material_jade" to "vine_green"
        )[legacyMaterialId]
        if (material != null) return selection(Category.MATERIAL, material)

        val texture = mapOf(
            "texture_duokan_blue" to "texture_blue",
            "texture_duokan_green" to "texture_green",
            "texture_duokan_white" to "texture_white",
            "texture_duokan_yellow" to "texture_yellow",
            "texture_paper_grain" to "texture_yellow",
            "texture_paper_fiber" to "texture_white",
            "texture_paper" to "texture_yellow",
            "texture_linen" to "texture_yellow",
            "texture_fiber" to "texture_white",
            "texture_blue" to "texture_blue",
            "texture_green" to "texture_green",
            "texture_grey" to "texture_white"
        )[legacyTextureId]
        if (texture != null) return selection(Category.TEXTURE, texture)

        val color = mapOf(
            "solid_ivory" to "scene_yellow",
            "solid_eye" to "scene_green",
            "solid_white" to "scene_white",
            "solid_blue" to "scene_blue",
            "solid_peach" to "scene_yellow",
            "solid_mist" to "scene_yellow",
            "solid_night" to "scene_black"
        )[legacyColorId]
        if (color != null) return selection(Category.COLOR, color)

        return selection(Category.COLOR, closestSceneId(legacyBackgroundColor))
    }

    private fun closestSceneId(color: Int): String {
        fun distance(a: Int, b: Int): Long {
            val dr = Color.red(b) - Color.red(a)
            val dg = Color.green(b) - Color.green(a)
            val db = Color.blue(b) - Color.blue(a)
            return (dr * dr + dg * dg + db * db).toLong()
        }
        return colorOptions.minByOrNull { distance(color, it.representativeColor) }?.id ?: DEFAULT_COLOR_ID
    }

    fun validated(selection: Selection): Selection {
        val choices = options(selection.category)
        if (choices.any { it.id == selection.optionId }) return selection
        val defaultId = when (selection.category) {
            Category.COLOR -> DEFAULT_COLOR_ID
            Category.TEXTURE -> DEFAULT_TEXTURE_ID
            Category.MATERIAL -> DEFAULT_MATERIAL_ID
        }
        return Selection(selection.category, defaultId)
    }

    fun option(selection: Selection): BackgroundOption {
        val safe = validated(selection)
        return options(safe.category).first { it.id == safe.optionId }
    }

    fun representativeColor(selection: Selection): Int = option(selection).representativeColor
    fun textColor(selection: Selection): Int = option(selection).textColor
    fun summary(selection: Selection): String = option(selection).title
    fun nightSelection(): Selection = Selection(Category.COLOR, "scene_black")

    fun drawable(context: Context, selection: Selection): Drawable =
        FullPageReaderBackgroundDrawable(context, option(selection))

    fun nightDrawable(context: Context): Drawable = drawable(context, nightSelection())

    fun previewDrawable(context: Context, selection: Selection, selected: Boolean): Drawable =
        ReaderBackgroundPreviewDrawable(context, validated(selection), selected)
}

private object ReaderBackgroundBitmapCache {
    private val cache = mutableMapOf<Int, Bitmap>()

    fun bitmap(context: Context, resId: Int): Bitmap? = synchronized(cache) {
        cache[resId] ?: BitmapFactory.decodeResource(context.resources, resId)?.also { cache[resId] = it }
    }
}

private class FullPageReaderBackgroundDrawable(
    private val context: Context,
    private val option: ReaderBackgrounds.BackgroundOption
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = option.representativeColor }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        fillPaint.alpha = globalAlpha
        canvas.drawRect(area, fillPaint)
        val bitmap = ReaderBackgroundBitmapCache.bitmap(context, option.drawableRes)
            ?: error("Confirmed v625 background asset is missing: ${option.id}")
        destination.set(area)
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
        bitmapPaint.alpha = globalAlpha
        canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
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
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRect(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half, borderPaint)
    }

    override fun setAlpha(alpha: Int) {
        background.alpha = alpha
        borderPaint.alpha = alpha.coerceIn(0, 255)
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
