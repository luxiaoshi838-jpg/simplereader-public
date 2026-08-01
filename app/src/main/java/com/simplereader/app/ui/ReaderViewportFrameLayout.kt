package com.simplereader.app.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Records the exact laid-out reader container used by every page renderer. */
class ReaderViewportFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ReaderViewportMetrics.record(w, h)
    }
}
