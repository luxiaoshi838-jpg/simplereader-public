package com.simplereader.app.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.reader.page.ReaderPage

class ReaderPageAdapter(
    private var pages: List<ReaderPage>,
    private val pageWidth: Int,
    private val pageHeight: Int,
    private val paddingLeft: Int,
    private val paddingTop: Int,
    private val paddingRight: Int,
    private val paddingBottom: Int,
    private val textSizeSp: Float,
    private val lineSpacingExtra: Float,
    private val lineSpacingMultiplier: Float,
    private val backgroundColor: Int,
    private val textColor: Int,
    private val renderer: (ReaderPage) -> CharSequence
) : RecyclerView.Adapter<ReaderPageAdapter.PageHolder>() {

    private var highlightStart = -1
    private var highlightEnd = -1

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = pages.size
    override fun getItemId(position: Int): Long = pages[position].globalPageIndex.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = TextView(parent.context).apply {
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(false)
            includeFontPadding = false
            setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            textSize = textSizeSp
            setTextColor(textColor)
            setBackgroundColor(backgroundColor)
            layoutParams = RecyclerView.LayoutParams(pageWidth, pageHeight)
        }
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val page = pages[position]
        val rendered = runCatching { renderer(page) }.getOrElse { "页面加载失败" }
        holder.textView.text = applyHighlight(page, rendered)
    }

    fun replacePages(newPages: List<ReaderPage>) {
        pages = newPages
        notifyDataSetChanged()
    }

    fun setHighlight(startOffset: Int, endOffset: Int) {
        highlightStart = startOffset
        highlightEnd = endOffset
        notifyDataSetChanged()
    }

    fun clearHighlight() {
        if (highlightStart < 0 && highlightEnd < 0) return
        highlightStart = -1
        highlightEnd = -1
        notifyDataSetChanged()
    }

    private fun applyHighlight(page: ReaderPage, rendered: CharSequence): CharSequence {
        if (highlightStart < page.startOffset || highlightStart >= page.endOffset) return rendered
        val localStart = (highlightStart - page.startOffset).coerceIn(0, rendered.length)
        val localEnd = (highlightEnd - page.startOffset).coerceIn(localStart, rendered.length)
        if (localEnd <= localStart) return rendered
        val result = SpannableString(rendered)
        result.setSpan(
            BackgroundColorSpan(Color.rgb(255, 226, 105)),
            localStart,
            localEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return result
    }

    class PageHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
