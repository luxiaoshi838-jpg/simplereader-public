package com.simplereader.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Continuous vertical reader backed by the same fixed logical pages used by every
 * horizontal turn mode.
 *
 * Each adapter cell is exactly one *content-page* high. The system/reading top and
 * bottom insets belong to the viewport, not to every cell, so ordinary pages join
 * continuously. A chapter's short final page still occupies its complete logical
 * cell; therefore the next chapter can only begin at the next page boundary.
 */
class VerticalPageFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    data class Style(
        val textSizePx: Float,
        val textColor: Int,
        val horizontalPaddingPx: Int,
        val topViewportPaddingPx: Int,
        val bottomViewportPaddingPx: Int,
        val lineSpacingMultiplier: Float,
        val typeface: Typeface? = Typeface.DEFAULT,
        val edgeFadeColor: Int,
        val backgroundFactory: () -> Drawable
    )

    var onCurrentPageChanged: ((page: ReaderPageSnapshot, offsetPx: Int) -> Unit)? = null
    var onNeedPreviousPages: ((firstPage: ReaderPageSnapshot) -> Unit)? = null
    var onNeedNextPages: ((lastPage: ReaderPageSnapshot) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val pageAdapter = PageAdapter()
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = this@VerticalPageFlowView.layoutManager
        adapter = pageAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        clipToPadding = false
        setHasFixedSize(true)
    }
    private val topFade = View(context).apply { isClickable = false }
    private val bottomFade = View(context).apply { isClickable = false }
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y in height * 0.18f..height * 0.82f) {
                when {
                    e.x < width * 0.33f -> scrollByPage(-1)
                    e.x > width * 0.67f -> scrollByPage(1)
                    else -> onCenterTap?.invoke()
                }
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke()
        }
    })

    private var style: Style? = null
    private var pageHeightPx: Int = 1
    private var currentAnchorKey: String? = null
    private var previousRequestKey: String? = null
    private var nextRequestKey: String? = null
    private var lastReportAt: Long = 0L

    init {
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(topFade, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply { gravity = Gravity.TOP })
        addView(bottomFade, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)).apply { gravity = Gravity.BOTTOM })

        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                reportCurrentPage(force = false)
                requestEdgesIfNeeded()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    reportCurrentPage(force = true)
                    requestEdgesIfNeeded()
                }
            }
        })
    }

    fun configure(style: Style) {
        this.style = style
        background = style.backgroundFactory()
        recyclerView.setPadding(0, style.topViewportPaddingPx, 0, style.bottomViewportPaddingPx)
        topFade.background = edgeGradient(style.edgeFadeColor, top = true)
        bottomFade.background = edgeGradient(style.edgeFadeColor, top = false)
        pageAdapter.style = style
        updatePageHeight()
    }

    fun cancelNavigation() {
        recyclerView.stopScroll()
    }

    fun clear() {
        recyclerView.stopScroll()
        pageAdapter.replace(emptyList())
        currentAnchorKey = null
        previousRequestKey = null
        nextRequestKey = null
    }

    fun bind(
        pages: List<ReaderPageSnapshot>,
        current: ReaderPageSnapshot,
        offsetPx: Int = 0
    ) {
        recyclerView.stopScroll()
        pageAdapter.replace(deduplicate(pages))
        previousRequestKey = null
        nextRequestKey = null
        val targetIndex = pageAdapter.indexOf(current).coerceAtLeast(0)
        recyclerView.post {
            layoutManager.scrollToPositionWithOffset(targetIndex, -offsetPx.coerceIn(0, pageHeightPx - 1))
            currentAnchorKey = null
            reportCurrentPage(force = true)
            requestEdgesIfNeeded()
        }
    }

    fun prepend(pages: List<ReaderPageSnapshot>) {
        val incoming = deduplicate(pages).filterNot(pageAdapter::contains)
        if (incoming.isEmpty()) return
        val first = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val firstView = layoutManager.findViewByPosition(first)
        val anchorPage = pageAdapter.getOrNull(first)
        val anchorTop = firstView?.top ?: recyclerView.paddingTop
        pageAdapter.prepend(incoming)
        if (anchorPage != null) {
            retainThreeChapterWindow(anchorPage, anchorTop)
        } else {
            layoutManager.scrollToPositionWithOffset(first + incoming.size, anchorTop)
        }
        previousRequestKey = null
    }

    fun append(pages: List<ReaderPageSnapshot>) {
        val incoming = deduplicate(pages).filterNot(pageAdapter::contains)
        if (incoming.isEmpty()) return
        val first = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val firstView = layoutManager.findViewByPosition(first)
        val anchorPage = pageAdapter.getOrNull(first)
        val anchorTop = firstView?.top ?: recyclerView.paddingTop
        pageAdapter.append(incoming)
        if (anchorPage != null) retainThreeChapterWindow(anchorPage, anchorTop)
        nextRequestKey = null
    }

    /**
     * Duokan-style bounded page window. RecyclerView recycles visible cells, but the
     * adapter would otherwise retain every chapter visited in a long novel. Keep only
     * previous/current/next chapter snapshots and restore the exact visible page/top.
     */
    private fun retainThreeChapterWindow(anchorPage: ReaderPageSnapshot, anchorTop: Int) {
        val currentChapter = anchorPage.startAnchor.chapterIndex
        val minChapter = currentChapter - 1
        val maxChapter = currentChapter + 1
        val removeFromStart = pageAdapter.countFromStartWhile { page ->
            page.startAnchor.chapterIndex < minChapter
        }
        val removeFromEnd = pageAdapter.countFromEndWhile { page ->
            page.startAnchor.chapterIndex > maxChapter
        }
        if (removeFromEnd > 0) pageAdapter.removeEnd(removeFromEnd)
        if (removeFromStart > 0) pageAdapter.removeStart(removeFromStart)
        val restored = pageAdapter.indexOf(anchorPage)
        if (restored >= 0) {
            layoutManager.scrollToPositionWithOffset(restored, anchorTop)
        }
        previousRequestKey = null
        nextRequestKey = null
    }

    fun currentPage(): ReaderPageSnapshot? = currentVisiblePosition()?.first?.let(pageAdapter::getOrNull)

    fun currentOffsetPx(): Int = currentVisiblePosition()?.second ?: 0

    fun scrollByPage(direction: Int) {
        if (direction == 0) return
        val distance = (height * 0.78f).toInt().coerceAtLeast(1)
        recyclerView.smoothScrollBy(0, direction * distance)
    }

    fun pageCount(): Int = pageAdapter.itemCount

    private fun updatePageHeight() {
        val currentStyle = style ?: return
        val viewport = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        pageHeightPx = (viewport - currentStyle.topViewportPaddingPx - currentStyle.bottomViewportPaddingPx)
            .coerceAtLeast(dp(120))
        pageAdapter.pageHeightPx = pageHeightPx
        pageAdapter.notifyDataSetChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) updatePageHeight()
    }

    private fun currentVisiblePosition(): Pair<Int, Int>? {
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val view = layoutManager.findViewByPosition(first) ?: return first to 0
        val offset = (recyclerView.paddingTop - view.top).coerceIn(0, pageHeightPx - 1)
        return first to offset
    }

    private fun reportCurrentPage(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastReportAt < 48L) return
        val (position, offset) = currentVisiblePosition() ?: return
        val page = pageAdapter.getOrNull(position) ?: return
        val key = pageKey(page)
        if (force || key != currentAnchorKey) {
            currentAnchorKey = key
            lastReportAt = now
            onCurrentPageChanged?.invoke(page, offset)
        }
    }

    private fun requestEdgesIfNeeded() {
        if (pageAdapter.itemCount == 0) return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && first <= 1) {
            val page = pageAdapter.getOrNull(0) ?: return
            val key = pageKey(page)
            if (previousRequestKey != key) {
                previousRequestKey = key
                onNeedPreviousPages?.invoke(page)
            }
        }
        if (last != RecyclerView.NO_POSITION && last >= pageAdapter.itemCount - 2) {
            val page = pageAdapter.getOrNull(pageAdapter.itemCount - 1) ?: return
            val key = pageKey(page)
            if (nextRequestKey != key) {
                nextRequestKey = key
                onNeedNextPages?.invoke(page)
            }
        }
    }

    private fun edgeGradient(color: Int, top: Boolean): Drawable {
        val opaque = Color.argb(238, Color.red(color), Color.green(color), Color.blue(color))
        val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        return GradientDrawable(
            if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(opaque, transparent)
        )
    }

    private fun deduplicate(pages: List<ReaderPageSnapshot>): List<ReaderPageSnapshot> =
        pages.distinctBy(::pageKey)

    private fun pageKey(page: ReaderPageSnapshot): String = with(page.startAnchor) {
        "$chapterIndex:$chapterOffset:$sourceOffset"
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        private val pages = mutableListOf<ReaderPageSnapshot>()
        var style: Style? = null
        var pageHeightPx: Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val text = TextView(parent.context).apply {
                gravity = Gravity.TOP or Gravity.START
                setTextIsSelectable(false)
                isLongClickable = false
                isClickable = false
                includeFontPadding = true
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                setBackgroundColor(Color.TRANSPARENT)
            }
            return PageHolder(text)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val currentStyle = style ?: return
            holder.textView.apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    pageHeightPx.coerceAtLeast(1)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_PX, currentStyle.textSizePx)
                setTextColor(currentStyle.textColor)
                typeface = currentStyle.typeface
                setLineSpacing(0f, currentStyle.lineSpacingMultiplier)
                setPadding(
                    currentStyle.horizontalPaddingPx,
                    0,
                    currentStyle.horizontalPaddingPx,
                    0
                )
                text = pages[position].content
            }
        }

        override fun getItemCount(): Int = pages.size

        fun replace(value: List<ReaderPageSnapshot>) {
            pages.clear()
            pages.addAll(value)
            notifyDataSetChanged()
        }

        fun prepend(value: List<ReaderPageSnapshot>) {
            pages.addAll(0, value)
            notifyItemRangeInserted(0, value.size)
        }

        fun append(value: List<ReaderPageSnapshot>) {
            val start = pages.size
            pages.addAll(value)
            notifyItemRangeInserted(start, value.size)
        }

        fun removeStart(count: Int) {
            val safeCount = count.coerceIn(0, pages.size)
            if (safeCount == 0) return
            pages.subList(0, safeCount).clear()
            notifyItemRangeRemoved(0, safeCount)
        }

        fun removeEnd(count: Int) {
            val safeCount = count.coerceIn(0, pages.size)
            if (safeCount == 0) return
            val start = pages.size - safeCount
            pages.subList(start, pages.size).clear()
            notifyItemRangeRemoved(start, safeCount)
        }

        fun countFromStartWhile(predicate: (ReaderPageSnapshot) -> Boolean): Int {
            var count = 0
            while (count < pages.size && predicate(pages[count])) count += 1
            return count
        }

        fun countFromEndWhile(predicate: (ReaderPageSnapshot) -> Boolean): Int {
            var count = 0
            var index = pages.lastIndex
            while (index >= 0 && predicate(pages[index])) {
                count += 1
                index -= 1
            }
            return count
        }

        fun indexOf(page: ReaderPageSnapshot): Int = pages.indexOfFirst { pageKey(it) == pageKey(page) }
        fun contains(page: ReaderPageSnapshot): Boolean = indexOf(page) >= 0
        fun getOrNull(index: Int): ReaderPageSnapshot? = pages.getOrNull(index)
    }

    private class PageHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
