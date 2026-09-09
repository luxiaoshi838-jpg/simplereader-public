package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * Attach-only fast scroller shared by the main shelf and group shelves.
 *
 * It deliberately does not replace/inflate RecyclerView itself. The thumb becomes visible and
 * grabbable only after real vertical scrolling has occurred, stays active while a drag is owned,
 * and deactivates shortly after scrolling becomes idle. A drag can start only on the visible thumb.
 */
class ShelfFastScroller private constructor(
    private val recyclerView: RecyclerView
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {

    private val density = recyclerView.resources.displayMetrics.density
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
        ViewConfiguration.get(recyclerView.context).scaledTouchSlop.toFloat()
    )

    private var thumbActive = false
    private var draggingThumb = false
    private var dragOffset = 0f

    private val deactivateRunnable = Runnable {
        if (!draggingThumb && recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            thumbActive = false
            recyclerView.invalidateItemDecorations()
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0 && geometry() != null) activateThumb()
        }

        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            if (geometry() == null) {
                thumbActive = false
                draggingThumb = false
                rv.removeCallbacks(deactivateRunnable)
                rv.invalidateItemDecorations()
                return
            }
            when (newState) {
                RecyclerView.SCROLL_STATE_IDLE -> scheduleDeactivate()
                RecyclerView.SCROLL_STATE_SETTLING -> if (thumbActive) activateThumb()
                // DRAGGING alone is not enough: onScrolled must observe actual movement first.
                RecyclerView.SCROLL_STATE_DRAGGING -> Unit
            }
        }
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!thumbActive && !draggingThumb) return
        val geometry = geometry() ?: return
        updateThumbRect(geometry)

        val right = parent.width - parent.paddingRight - edgeInset
        trackRect.set(
            right - trackWidth,
            parent.paddingTop.toFloat(),
            right,
            (parent.height - parent.paddingBottom).toFloat()
        )
        canvas.drawRoundRect(trackRect, trackWidth, trackWidth, trackPaint)
        canvas.drawRoundRect(thumbRect, thumbWidth, thumbWidth, thumbPaint)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            thumbActive &&
            geometry() != null &&
            isOnVisibleThumb(event.x, event.y)
        ) {
            startThumbDrag(event.y)
            return true
        }
        return draggingThumb
    }

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (draggingThumb) scrollFromFinger(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingThumb) {
                scrollFromFinger(event.y)
                draggingThumb = false
                rv.parent?.requestDisallowInterceptTouchEvent(false)
                scheduleDeactivate()
                rv.invalidateItemDecorations()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    private fun activateThumb() {
        thumbActive = true
        recyclerView.removeCallbacks(deactivateRunnable)
        if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE && !draggingThumb) {
            scheduleDeactivate()
        }
        recyclerView.invalidateItemDecorations()
    }

    private fun scheduleDeactivate() {
        recyclerView.removeCallbacks(deactivateRunnable)
        recyclerView.postDelayed(deactivateRunnable, 1200L)
    }

    private fun startThumbDrag(y: Float) {
        val geometry = geometry() ?: return
        updateThumbRect(geometry)
        draggingThumb = true
        thumbActive = true
        recyclerView.removeCallbacks(deactivateRunnable)
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        recyclerView.stopScroll()
        dragOffset = (y - thumbRect.top).coerceIn(0f, geometry.thumbHeight)
        recyclerView.invalidateItemDecorations()
    }

    private fun scrollFromFinger(y: Float) {
        val geometry = geometry() ?: return
        val travel = (geometry.viewportHeight - geometry.thumbHeight).coerceAtLeast(1f)
        val desiredTop = (y - recyclerView.paddingTop - dragOffset).coerceIn(0f, travel)
        val targetOffset = (desiredTop / travel * geometry.maxScrollOffset).toInt()
        val currentOffset = recyclerView.computeVerticalScrollOffset()
            .coerceIn(0, geometry.maxScrollOffset)
        val delta = targetOffset - currentOffset
        if (delta != 0) recyclerView.scrollBy(0, delta)
        thumbActive = true
        recyclerView.invalidateItemDecorations()
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
        val currentOffset = recyclerView.computeVerticalScrollOffset()
            .coerceIn(0, geometry.maxScrollOffset)
        val fraction = currentOffset.toFloat() / geometry.maxScrollOffset.toFloat()
        val top = recyclerView.paddingTop + fraction.coerceIn(0f, 1f) * travel
        val right = recyclerView.width - recyclerView.paddingRight - edgeInset
        thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)
    }

    private fun geometry(): ScrollGeometry? {
        if (recyclerView.height <= recyclerView.paddingTop + recyclerView.paddingBottom) return null
        val extent = recyclerView.computeVerticalScrollExtent().coerceAtLeast(1)
        val range = recyclerView.computeVerticalScrollRange().coerceAtLeast(extent)
        if (range <= extent) return null

        val viewportHeight = (
            recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom
        ).toFloat().coerceAtLeast(1f)
        val maxScrollOffset = (range - extent).coerceAtLeast(1)
        val thumbHeight = max(
            minThumbHeight,
            viewportHeight * extent.toFloat() / range.toFloat()
        ).coerceAtMost(viewportHeight)
        return ScrollGeometry(viewportHeight, maxScrollOffset, thumbHeight)
    }

    private data class ScrollGeometry(
        val viewportHeight: Float,
        val maxScrollOffset: Int,
        val thumbHeight: Float
    )

    companion object {
        fun attach(recyclerView: RecyclerView): ShelfFastScroller {
            recyclerView.isVerticalScrollBarEnabled = false
            recyclerView.isHorizontalScrollBarEnabled = false
            recyclerView.isScrollbarFadingEnabled = false
            val scroller = ShelfFastScroller(recyclerView)
            recyclerView.addItemDecoration(scroller)
            recyclerView.addOnItemTouchListener(scroller)
            recyclerView.addOnScrollListener(scroller.scrollListener)
            return scroller
        }
    }
}
