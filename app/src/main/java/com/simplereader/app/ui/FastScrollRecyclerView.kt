package com.simplereader.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * RecyclerView fast-scroll thumb used by both the main shelf and group shelves.
 *
 * The thumb is intentionally NOT permanently grab-enabled. It becomes visible/grabbable only
 * after the list has actually scrolled, remains grabbable while scrolling/settling, and fades
 * shortly after scrolling stops. A drag can only start by pressing the currently visible thumb.
 */
class FastScrollRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 62, 58, 52)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 88, 83, 74)
    }
    private val thumbRect = RectF()
    private val trackRect = RectF()
    private val thumbWidth = 10f * density
    private val trackWidth = 2f * density
    private val edgeInset = 3f * density
    private val minThumbHeight = 52f * density
    private val thumbTouchPadding = max(
        8f * density,
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    )

    private var thumbActive = false
    private var draggingThumb = false
    private var dragOffset = 0f

    private val deactivateRunnable = Runnable {
        if (!draggingThumb && scrollState == SCROLL_STATE_IDLE) {
            thumbActive = false
            invalidate()
        }
    }

    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setWillNotDraw(false)
        contentDescription = "滚动时可按住右侧滑块快速滚动"
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        if (dy != 0 && canFastScroll()) {
            activateThumb()
        }
        invalidate()
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (!canFastScroll()) {
            thumbActive = false
            draggingThumb = false
            removeCallbacks(deactivateRunnable)
            invalidate()
            return
        }
        if (state == SCROLL_STATE_IDLE) {
            scheduleDeactivate()
        } else {
            activateThumb()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            thumbActive &&
            canFastScroll() &&
            isOnVisibleThumb(event.x, event.y)
        ) {
            startThumbDrag(event.y)
            return true
        }
        return if (draggingThumb) true else super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (thumbActive && canFastScroll() && isOnVisibleThumb(event.x, event.y)) {
                    startThumbDrag(event.y)
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
                scheduleDeactivate()
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

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (!thumbActive && !draggingThumb) return
        val geometry = geometry() ?: return
        updateThumbRect(geometry)

        val right = width - paddingRight - edgeInset
        trackRect.set(
            right - trackWidth,
            paddingTop.toFloat(),
            right,
            (height - paddingBottom).toFloat()
        )
        canvas.drawRoundRect(trackRect, trackWidth, trackWidth, trackPaint)
        canvas.drawRoundRect(thumbRect, thumbWidth, thumbWidth, thumbPaint)
    }

    private fun activateThumb() {
        thumbActive = true
        removeCallbacks(deactivateRunnable)
        if (scrollState == SCROLL_STATE_IDLE && !draggingThumb) {
            scheduleDeactivate()
        }
        invalidate()
    }

    private fun scheduleDeactivate() {
        removeCallbacks(deactivateRunnable)
        postDelayed(deactivateRunnable, 1200L)
    }

    private fun startThumbDrag(y: Float) {
        val geometry = geometry() ?: return
        updateThumbRect(geometry)
        draggingThumb = true
        thumbActive = true
        removeCallbacks(deactivateRunnable)
        parent?.requestDisallowInterceptTouchEvent(true)
        stopScroll()
        dragOffset = (y - thumbRect.top).coerceIn(0f, geometry.thumbHeight)
        invalidate()
    }

    private fun scrollFromFinger(y: Float) {
        val geometry = geometry() ?: return
        val travel = (geometry.viewportHeight - geometry.thumbHeight).coerceAtLeast(1f)
        val desiredTop = (y - paddingTop - dragOffset).coerceIn(0f, travel)
        val targetOffset = (desiredTop / travel * geometry.maxScrollOffset).toInt()
        val currentOffset = computeVerticalScrollOffset().coerceIn(0, geometry.maxScrollOffset)
        val delta = targetOffset - currentOffset
        if (delta != 0) scrollBy(0, delta)
        thumbActive = true
        invalidate()
    }

    private fun isOnVisibleThumb(x: Float, y: Float): Boolean {
        val geometry = geometry() ?: return false
        updateThumbRect(geometry)
        val hitRect = RectF(
            thumbRect.left - thumbTouchPadding,
            thumbRect.top - thumbTouchPadding,
            thumbRect.right + thumbTouchPadding,
            thumbRect.bottom + thumbTouchPadding
        )
        return hitRect.contains(x, y)
    }

    private fun updateThumbRect(geometry: ScrollGeometry) {
        val travel = (geometry.viewportHeight - geometry.thumbHeight).coerceAtLeast(0f)
        val currentOffset = computeVerticalScrollOffset().coerceIn(0, geometry.maxScrollOffset)
        val fraction = currentOffset.toFloat() / geometry.maxScrollOffset.toFloat()
        val top = paddingTop + fraction.coerceIn(0f, 1f) * travel
        val right = width - paddingRight - edgeInset
        thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)
    }

    private fun geometry(): ScrollGeometry? {
        if (height <= paddingTop + paddingBottom) return null
        val extent = computeVerticalScrollExtent().coerceAtLeast(1)
        val range = computeVerticalScrollRange().coerceAtLeast(extent)
        if (range <= extent) return null

        val viewportHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val maxScrollOffset = (range - extent).coerceAtLeast(1)
        val thumbHeight = max(
            minThumbHeight,
            viewportHeight * extent.toFloat() / range.toFloat()
        ).coerceAtMost(viewportHeight)
        return ScrollGeometry(viewportHeight, maxScrollOffset, thumbHeight)
    }

    private fun canFastScroll(): Boolean = geometry() != null

    private data class ScrollGeometry(
        val viewportHeight: Float,
        val maxScrollOffset: Int,
        val thumbHeight: Float
    )
}
