package com.simplereader.app.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.style.ReplacementSpan
import com.simplereader.app.data.cache.StructuredBookCache
import java.util.LinkedHashMap
import kotlin.math.roundToInt

class ReaderImageRepository(
    private val context: Context,
    private val bookId: Long
) {
    private val cache = object : LinkedHashMap<String, Bitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 12
    }

    fun span(href: String, maxWidth: Int, maxHeight: Int): ReplacementSpan {
        val bitmap = synchronized(cache) {
            cache[href] ?: loadBitmap(href, maxWidth, maxHeight)?.also { cache[href] = it }
        }
        return ScaledImageSpan(bitmap, maxWidth, maxHeight)
    }

    private fun loadBitmap(href: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val file = StructuredBookCache.imageFile(context, bookId, href) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) {
            sample *= 2
        }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}

private class ScaledImageSpan(
    private val bitmap: Bitmap?,
    maxWidth: Int,
    maxHeight: Int
) : ReplacementSpan() {
    private val width: Int
    private val height: Int
    private val destination: Rect

    init {
        if (bitmap == null) {
            width = maxWidth.coerceAtLeast(1)
            height = (maxHeight * 0.22f).roundToInt().coerceAtLeast(80)
        } else {
            val scale = minOf(
                maxWidth.toFloat() / bitmap.width.coerceAtLeast(1),
                (maxHeight * 0.82f) / bitmap.height.coerceAtLeast(1),
                1f
            )
            width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        }
        destination = Rect(0, 0, width, height)
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let {
            it.ascent = -height
            it.top = -height
            it.descent = 0
            it.bottom = 0
        }
        return width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val save = canvas.save()
        canvas.translate(x, (bottom - height).toFloat())
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, destination, paint)
        } else {
            val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(24, 90, 90, 90)
                style = Paint.Style.FILL
            }
            canvas.drawRect(destination, placeholder)
            placeholder.color = Color.argb(150, 90, 90, 90)
            placeholder.textAlign = Paint.Align.CENTER
            placeholder.textSize = 28f
            canvas.drawText("图片加载失败", width / 2f, height / 2f, placeholder)
        }
        canvas.restoreToCount(save)
    }
}
