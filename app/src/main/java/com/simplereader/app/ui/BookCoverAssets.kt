package com.simplereader.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.simplereader.app.R

/** Format-specific fallback covers supplied for the local TXT/EPUB library. */
object BookCoverAssets {
    fun defaultCoverRes(format: String): Int =
        if (format.equals("EPUB", ignoreCase = true)) {
            R.drawable.book_cover_default_epub
        } else {
            R.drawable.book_cover_default_txt
        }

    fun drawable(context: Context, format: String, radiusPx: Float): Drawable =
        DefaultBookCoverDrawable(
            bitmap = CoverBitmapCache.load(context, defaultCoverRes(format)),
            radiusPx = radiusPx
        )
}

private object CoverBitmapCache {
    private val bitmaps = mutableMapOf<Int, Bitmap>()

    fun load(context: Context, resId: Int): Bitmap = synchronized(bitmaps) {
        bitmaps[resId] ?: requireNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "无法读取默认封面资源：$resId"
        }.also { bitmaps[resId] = it }
    }
}

private class DefaultBookCoverDrawable(
    private val bitmap: Bitmap,
    private val radiusPx: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val source = Rect()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        destination.set(bounds)
        if (destination.isEmpty) return
        calculateCenterCrop(destination.width() / destination.height())
        clip.reset()
        clip.addRoundRect(destination, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, source, destination, paint)
        canvas.restoreToCount(save)
    }

    private fun calculateCenterCrop(destinationAspect: Float) {
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        if (bitmapAspect > destinationAspect) {
            val targetWidth = (bitmap.height * destinationAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - targetWidth) / 2
            source.set(left, 0, left + targetWidth, bitmap.height)
        } else {
            val targetHeight = (bitmap.width / destinationAspect.coerceAtLeast(0.01f)).toInt()
                .coerceIn(1, bitmap.height)
            val top = (bitmap.height - targetHeight) / 2
            source.set(0, top, bitmap.width, top + targetHeight)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
