package com.simplereader.app.ui

import android.graphics.Color
import android.text.Layout
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.util.LruCache
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.reader.page.ReaderPage

/** V632-V745 continuous vertical reader: one ReaderPage per recycled TextView. */
class VerticalPageHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

class VerticalPageAdapter(private val activity: ReaderActivity) : RecyclerView.Adapter<VerticalPageHolder>() {
    private var pages: List<ReaderPage> = emptyList()
    private val rendered = LruCache<Int, CharSequence>(32)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerticalPageHolder {
        val text = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            // Chinese novel rows do not need balanced breaking or hyphenation. SIMPLE keeps
            // TextView measurement cheap when RecyclerView prepares the next page.
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
        return VerticalPageHolder(text)
    }

    override fun onBindViewHolder(holder: VerticalPageHolder, position: Int) {
        val view = holder.textView
        view.textSize = activity.verticalTextSizeSp()
        view.setLineSpacing(0f, activity.verticalLineSpacingMultiplier())
        view.setTextColor(activity.verticalTextColor())
        val fontHeight = view.paint.getFontMetricsInt(null)
        val finalGap = (view.lineHeight - fontHeight).coerceAtLeast(0)
        view.setPadding(
            activity.verticalPaddingLeft(),
            0,
            activity.verticalPaddingRight(),
            if (position == pages.lastIndex) 0 else finalGap
        )
        var text = rendered.get(position)
        if (text == null) {
            text = activity.verticalRenderPage(pages[position])
            rendered.put(position, text)
        }
        // V634 terminal-newline normalization: neighboring items must not manufacture a blank line.
        if (position != pages.lastIndex && text.isNotEmpty() && text.last() == '\n') {
            text = text.subSequence(0, text.length - 1)
        }
        view.setText(text, TextView.BufferType.SPANNABLE)
    }

    override fun getItemCount(): Int = pages.size

    fun setPages(value: List<ReaderPage>) {
        pages = value
        rendered.evictAll()
        notifyDataSetChanged()
    }

    fun refresh() {
        rendered.evictAll()
        notifyDataSetChanged()
    }

    /**
     * v756: search highlighting is transient. ReaderActivity clears activeSearchHit as soon as the
     * user starts dragging; then discard cached highlighted CharSequences and rebind only the
     * currently visible ReaderPage rows. Item count/order and LayoutManager position are untouched,
     * so this cannot become a source-offset restore or reading-position jump.
     */
    fun clearTransientSearchHighlight(recyclerView: RecyclerView, position: Int) {
        if (position < 0) return
        rendered.remove(position)
        val holder = recyclerView.findViewHolderForAdapterPosition(position) as? VerticalPageHolder ?: return
        val text = holder.textView.text as? Spannable ?: return
        val searchColor = Color.rgb(255, 226, 105)
        text.getSpans(0, text.length, BackgroundColorSpan::class.java)
            .filter { it.backgroundColor == searchColor }
            .forEach(text::removeSpan)
        holder.textView.invalidate()
    }
}

class VerticalScrollListener(
    private val activity: ReaderActivity,
    private val layoutManager: LinearLayoutManager
) : RecyclerView.OnScrollListener() {
    private var lastReportedIndex = RecyclerView.NO_POSITION

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (activity.verticalShouldIgnoreScroll()) return
        val index = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (index >= 0 && index != lastReportedIndex) {
            lastReportedIndex = index
            activity.verticalShowBoundaryHaze()
            activity.verticalOnPageVisible(index)
        }
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            val hitPage = activity.verticalOnUserDrag()
            if (hitPage != null) {
                (recyclerView.adapter as? VerticalPageAdapter)
                    ?.clearTransientSearchHighlight(recyclerView, hitPage)
            }
        }
    }
}

class VerticalTouchListener(private val activity: ReaderActivity) : android.view.View.OnTouchListener {
    override fun onTouch(v: android.view.View?, event: android.view.MotionEvent): Boolean = activity.verticalHandleTouch(event)
}
