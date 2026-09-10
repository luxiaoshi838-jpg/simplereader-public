#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'{label} marker not found')
    return text.replace(old, new, 1)

# Version 783.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000782"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000783"')
s = s.replace('?: 2098000782', '?: 2098000783')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "782"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "783"')
gradle.write_text(s, encoding='utf-8')

# Main shelf: the RecyclerView itself reaches the physical right edge, while book/group content
# keeps the normal 16dp visual margin. This recovers the old 28dp dedicated slider column.
xml = root / 'app/src/main/res/layout/activity_main.xml'
s = xml.read_text(encoding='utf-8')
old = '''        android:clipToPadding="false"\n        android:paddingEnd="28dp"\n        android:scrollbars="none" />'''
new = '''        android:clipToPadding="false"\n        android:layout_marginEnd="-16dp"\n        android:paddingEnd="16dp"\n        android:scrollbars="none" />'''
s = replace_once(s, old, new, 'main shelf edge-overlay layout')
xml.write_text(s, encoding='utf-8')

# Group shelf: same geometry as main shelf. Grid content stays 16dp from the screen edge, while
# the decoration canvas extends through that margin so the handle can float above the right column.
group = root / 'app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt'
s = group.read_text(encoding='utf-8')
old = '''                clipToPadding = false\n                setPadding(0, dp(8), dp(28), dp(18))\n                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)'''
new = '''                clipToPadding = false\n                setPadding(0, dp(8), dp(16), dp(18))\n                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {\n                    marginEnd = -dp(16)\n                }'''
s = replace_once(s, old, new, 'group shelf edge-overlay layout')
group.write_text(s, encoding='utf-8')

# Main shelf card sizing previously retained 14dp of an older fast-scroll reservation. Remove it
# so the three columns use the full normal 16dp/16dp content width after the gutter is removed.
main = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
s = main.read_text(encoding='utf-8')
s = s.replace('val horizontalPadding = dp(16 * 2 + 14)', 'val horizontalPadding = dp(16 * 2)')
if 'val horizontalPadding = dp(16 * 2 + 14)' in s:
    raise SystemExit('main shelf legacy 14dp width reservation still present')
main.write_text(s, encoding='utf-8')

# Shared main/group fast scroller. V783 deliberately supersedes the v777 dedicated gutter and the
# v778 center-track appearance, but preserves v773 system-scrollbar safety and real-scroll activation.
scroller = root / 'app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt'
scroller.write_text(r'''package com.simplereader.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * V783_OVERLAY_FAST_SCROLL
 *
 * Attach-only fast scroller shared by the main shelf and group shelves.
 *
 * Layout contract:
 * - the track is a 1dp line at the RecyclerView's physical right edge and consumes no grid width;
 * - at rest, only a tiny position marker is shown on that edge;
 * - after real vertical movement, a 28dp x 52dp (minimum viewport permitting) floating pill appears;
 * - the pill is drawn in onDrawOver(), above books/groups, and intentionally overlaps the content;
 * - only the expanded visible pill can start a fast-scroll drag;
 * - no platform scrollbar API is touched (Android 35 startup-safety requirement from v773).
 */
class ShelfFastScroller private constructor(
    private val recyclerView: RecyclerView
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {

    private val density = recyclerView.resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val collapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val thumbRect = RectF()
    private val trackRect = RectF()
    private val shadowRect = RectF()
    private val collapsedRect = RectF()

    // Matches the user-provided reference: ~28dp wide x 52dp high, ratio ~1:1.86.
    private val thumbWidth = 28f * density
    private val fixedThumbHeight = 52f * density
    private val edgeInset = 6f * density
    private val trackWidth = 1f * density
    private val collapsedThumbWidth = 4f * density
    private val collapsedThumbHeight = 24f * density
    private val gripWidth = 13f * density
    private val gripGap = 6f * density
    private val thumbHorizontalTouchPadding = 4f * density
    private val thumbVerticalTouchPadding = max(
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
                // Preserve v773 semantics: touching the list alone does not expose/grab the handle.
                RecyclerView.SCROLL_STATE_DRAGGING -> Unit
            }
        }
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val geometry = geometry() ?: return
        updateThumbRect(geometry)
        updateVisualColors()

        // The track is literally the right-most 1dp line of the RecyclerView. The RecyclerView is
        // extended to the physical screen edge by the shelf/group layouts, so this reserves no column.
        trackRect.set(
            parent.width.toFloat() - trackWidth,
            parent.paddingTop.toFloat(),
            parent.width.toFloat(),
            (parent.height - parent.paddingBottom).toFloat()
        )
        canvas.drawRoundRect(trackRect, trackWidth, trackWidth, trackPaint)

        if (thumbActive || draggingThumb) {
            drawExpandedThumb(canvas)
        } else {
            drawCollapsedThumb(canvas)
        }
    }

    private fun drawExpandedThumb(canvas: Canvas) {
        val radius = thumbWidth / 2f
        shadowRect.set(
            thumbRect.left - 2f * density,
            thumbRect.top + 2f * density,
            thumbRect.right + 2f * density,
            thumbRect.bottom + 4f * density
        )
        canvas.drawRoundRect(shadowRect, radius + 2f * density, radius + 2f * density, shadowPaint)
        canvas.drawRoundRect(thumbRect, radius, radius, activeFillPaint)
        canvas.drawRoundRect(thumbRect, radius, radius, activeBorderPaint)

        val centerX = thumbRect.centerX()
        val centerY = thumbRect.centerY()
        val left = centerX - gripWidth / 2f
        val right = centerX + gripWidth / 2f
        canvas.drawLine(left, centerY - gripGap, right, centerY - gripGap, gripPaint)
        canvas.drawLine(left, centerY, right, centerY, gripPaint)
        canvas.drawLine(left, centerY + gripGap, right, centerY + gripGap, gripPaint)
    }

    private fun drawCollapsedThumb(canvas: Canvas) {
        val centerY = thumbRect.centerY()
        collapsedRect.set(
            recyclerView.width.toFloat() - collapsedThumbWidth,
            centerY - collapsedThumbHeight / 2f,
            recyclerView.width.toFloat(),
            centerY + collapsedThumbHeight / 2f
        )
        canvas.drawRoundRect(
            collapsedRect,
            collapsedThumbWidth / 2f,
            collapsedThumbWidth / 2f,
            collapsedPaint
        )
    }

    private fun updateVisualColors() {
        val background = ReaderAppearance.palette(recyclerView.context).backgroundColor
        val luminance = (
            Color.red(background) * 299 +
                Color.green(background) * 587 +
                Color.blue(background) * 114
            ) / 1000
        val dark = luminance < 128
        trackPaint.color = if (dark) Color.argb(72, 235, 232, 224) else Color.argb(52, 82, 78, 71)
        collapsedPaint.color = if (dark) Color.argb(180, 215, 212, 204) else Color.argb(155, 125, 121, 113)
        shadowPaint.color = Color.argb(if (dark) 90 else 48, 0, 0, 0)
        activeFillPaint.color = if (dark) Color.rgb(58, 56, 52) else Color.rgb(252, 251, 248)
        activeBorderPaint.color = if (dark) Color.argb(125, 224, 221, 214) else Color.argb(90, 145, 141, 133)
        gripPaint.color = if (dark) Color.rgb(206, 203, 196) else Color.rgb(157, 154, 147)
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
        // V783 intentionally allows the active hit target to overlap the right-most book/group.
        val hitRect = RectF(
            (thumbRect.left - thumbHorizontalTouchPadding).coerceAtLeast(0f),
            thumbRect.top - thumbVerticalTouchPadding,
            (thumbRect.right + thumbHorizontalTouchPadding).coerceAtMost(recyclerView.width.toFloat()),
            thumbRect.bottom + thumbVerticalTouchPadding
        )
        return hitRect.contains(x, y)
    }

    private fun updateThumbRect(geometry: ScrollGeometry) {
        val travel = (geometry.viewportHeight - geometry.thumbHeight).coerceAtLeast(0f)
        val currentOffset = recyclerView.computeVerticalScrollOffset()
            .coerceIn(0, geometry.maxScrollOffset)
        val fraction = currentOffset.toFloat() / geometry.maxScrollOffset.toFloat()
        val top = recyclerView.paddingTop + fraction.coerceIn(0f, 1f) * travel
        val right = recyclerView.width - edgeInset
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
        val thumbHeight = fixedThumbHeight.coerceAtMost(viewportHeight)
        return ScrollGeometry(viewportHeight, maxScrollOffset, thumbHeight)
    }

    private data class ScrollGeometry(
        val viewportHeight: Float,
        val maxScrollOffset: Int,
        val thumbHeight: Float
    )

    companion object {
        fun attach(recyclerView: RecyclerView): ShelfFastScroller {
            // Do not touch View's system scrollbar fading/cache here. On Android 35,
            // isScrollbarFadingEnabled=false while scrollbars are disabled can allocate a scroll
            // cache without a ScrollBarDrawable, and the first draw then crashes in
            // View.onDrawScrollBars(). The shelf uses only this ItemDecoration overlay.
            val scroller = ShelfFastScroller(recyclerView)
            recyclerView.addItemDecoration(scroller)
            recyclerView.addOnItemTouchListener(scroller)
            recyclerView.addOnScrollListener(scroller.scrollListener)
            return scroller
        }
    }
}
''', encoding='utf-8')

required = {
    gradle: ['2098000783', '"783"'],
    xml: ['android:layout_marginEnd="-16dp"', 'android:paddingEnd="16dp"'],
    group: ['setPadding(0, dp(8), dp(16), dp(18))', 'marginEnd = -dp(16)'],
    main: ['val horizontalPadding = dp(16 * 2)'],
    scroller: [
        'V783_OVERLAY_FAST_SCROLL',
        'private val thumbWidth = 28f * density',
        'private val fixedThumbHeight = 52f * density',
        'parent.width.toFloat() - trackWidth',
        'drawExpandedThumb(canvas)',
        'drawCollapsedThumb(canvas)',
        'canvas.drawLine(left, centerY - gripGap',
        'canvas.drawLine(left, centerY, right, centerY, gripPaint)',
        'canvas.drawLine(left, centerY + gripGap',
    ],
}
for path, markers in required.items():
    text = path.read_text(encoding='utf-8')
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise SystemExit(f'v783 patch incomplete for {path}: {missing!r}')

print('v783 applied: right-edge 1dp track + reference-proportion 28x52dp overlay pill; shelf/group grids reclaim the old gutter')
