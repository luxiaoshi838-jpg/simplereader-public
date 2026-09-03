package com.simplereader.app.ui

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
    fun clearTransientSearchHighlight(recyclerView: RecyclerView) {
        rendered.evictAll()
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) {
            notifyItemRangeChanged(first, last - first + 1)
        }
    }
}

class VerticalScrollListener(
    private val activity: ReaderActivity,
    private val layoutManager: LinearLayoutManager
) : RecyclerView.OnScrollListener() {
    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (activity.verticalShouldIgnoreScroll()) return
        val index = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (index >= 0) {
            activity.verticalShowBoundaryHaze()
            activity.verticalOnPageVisible(index)
        }
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            activity.verticalOnUserDrag()
            // Post the adapter rebind outside RecyclerView's scroll callback to avoid mutating
            // adapter state while RecyclerView may still be computing a layout.
            recyclerView.post {
                (recyclerView.adapter as? VerticalPageAdapter)?.clearTransientSearchHighlight(recyclerView)
            }
        }
    }
}

class VerticalTouchListener(private val activity: ReaderActivity) : android.view.View.OnTouchListener {
    override fun onTouch(v: android.view.View?, event: android.view.MotionEvent): Boolean = activity.verticalHandleTouch(event)
}
