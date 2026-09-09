package com.simplereader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * Touch-observing containers used by the reader.
 *
 * They observe the complete MotionEvent stream before child dispatch without consuming it. This is
 * important when the child TextView is selectable: the selectable TextView may become the touch
 * target, but normal reader gestures (center tap / vertical scrolling state) must still be seen by
 * the reader container.
 */
class ReaderTouchObservingRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    var touchObserver: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touchObserver?.invoke(event)
        return super.dispatchTouchEvent(event)
    }
}

class ReaderTouchObservingNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    var touchObserver: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touchObserver?.invoke(event)
        return super.dispatchTouchEvent(event)
    }
}
