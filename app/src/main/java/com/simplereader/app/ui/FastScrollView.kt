package com.simplereader.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.max

/** ScrollView with a large, always-visible and directly draggable thumb. */
class FastScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 88, 83, 74)
    }
    private val activeThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 62, 58, 52)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 88, 83, 74)
    }
    private val thumbRect = RectF()
    private val trackRect = RectF()
    private val thumbWidth = 10f * density
    private val trackWidth = 4f * density
    private val touchWidth = max(44f * density, ViewConfiguration.get(context).scaledTouchSlop * 3f)
    private val minThumbHeight = 52f * density
    private var draggingThumb = false
    private var dragOffset = 0f

    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setWillNotDraw(false)
        contentDescription = "右侧可抓取拖动快速滚动条"
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && canFastScroll() && inFastScrollZone(event.x)) {
            startDragging(event.y)
            return true
        }
        return if (draggingThumb) true else super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (canFastScroll() && inFastScrollZone(event.x)) {
                    startDragging(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (draggingThumb) {
                scrollFromFinger(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingThumb) {
                scrollFromFinger(event.y)
                draggingThumb = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidate()
    }

    /**
     * Draw the custom scrollbar in viewport coordinates. ScrollView translates
     * its drawing canvas by -scrollY for content, so adding scrollX/scrollY here
     * keeps the track fixed to the screen while the thumb follows scroll progress.
     * The framework scrollbar renderer is intentionally not called because this
     * view disables framework scrollbars and owns the complete scrollbar UI.
     */
    override fun onDrawForeground(canvas: Canvas) {
        foreground?.let { drawable ->
            drawable.setBounds(scrollX, scrollY, scrollX + width, scrollY + height)
            drawable.draw(canvas)
        }

        val geometry = geometry() ?: return
        updateThumbRect(geometry)

        val saveCount = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        val right = width - paddingRight - 3f * density
        trackRect.set(
            right - trackWidth,
            paddingTop.toFloat(),
            right,
            (height - paddingBottom).toFloat()
        )
        canvas.drawRoundRect(trackRect, trackWidth, trackWidth, trackPaint)
        canvas.drawRoundRect(
            thumbRect,
            thumbWidth,
            thumbWidth,
            if (draggingThumb) activeThumbPaint else thumbPaint
        )
        canvas.restoreToCount(saveCount)
    }

    private fun startDragging(y: Float) {
        val geometry = geometry() ?: return
        updateThumbRect(geometry)
        draggingThumb = true
        parent?.requestDisallowInterceptTouchEvent(true)
        dragOffset = if (y >= thumbRect.top && y <= thumbRect.bottom) {
            y - thumbRect.top
        } else {
            geometry.thumbHeight / 2f
        }
        scrollFromFinger(y)
        invalidate()
    }

    private fun scrollFromFinger(y: Float) {
        val geometry = geometry() ?: return
        val travel = (geometry.viewportHeight - geometry.thumbHeight).coerceAtLeast(1f)
        val desiredTop = (y - paddingTop - dragOffset).coerceIn(0f, travel)
        val target = (desiredTop / travel * geometry.maxScroll).toInt()
        scrollTo(scrollX, target.coerceIn(0, geometry.maxScroll))
    }

    private fun updateThumbRect(geometry: ScrollGeometry) {
        val travel = (geometry.viewportHeight - geometry.thumbHeight).coerceAtLeast(0f)
        val scrollFraction = (scrollY.toFloat() / geometry.maxScroll.toFloat()).coerceIn(0f, 1f)
        val top = paddingTop + scrollFraction * travel
        val right = width - paddingRight - 3f * density
        thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)
    }

    private fun geometry(): ScrollGeometry? {
        val child = getChildAt(0) ?: return null
        val viewportHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val contentHeight = max(child.height, child.measuredHeight).toFloat()
        if (contentHeight <= viewportHeight) return null
        val maxScroll = (contentHeight - viewportHeight).toInt().coerceAtLeast(1)
        val thumbHeight = max(minThumbHeight, viewportHeight * viewportHeight / contentHeight)
            .coerceAtMost(viewportHeight)
        return ScrollGeometry(viewportHeight, maxScroll, thumbHeight)
    }

    private fun canFastScroll(): Boolean = geometry() != null

    private fun inFastScrollZone(x: Float): Boolean = x >= width - paddingRight - touchWidth

    private data class ScrollGeometry(
        val viewportHeight: Float,
        val maxScroll: Int,
        val thumbHeight: Float
    )
}
