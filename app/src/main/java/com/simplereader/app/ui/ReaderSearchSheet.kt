package com.simplereader.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object ReaderSearchSheet {
    fun show(
        activity: Activity,
        initialKeyword: String,
        initialHits: List<SearchPageHit>,
        onSearch: (String, (List<SearchPageHit>) -> Unit) -> Unit,
        onHit: (SearchPageHit) -> Unit,
        backgroundDrawable: Drawable? = null,
        textColor: Int? = null
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val palette = ReaderAppearance.palette(activity)
        val primary = textColor ?: palette.textColor
        val secondary = withAlpha(primary, 0.64f)
        val accent = Color.rgb(239, 122, 40)
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable ?: ReaderBackgrounds.drawable(activity, ReaderBackgrounds.Selection())
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "书内搜索"
            textSize = 19f
            setTextColor(primary)
        }
        val close = TextView(activity).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        titleRow.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(activity).apply {
            hint = "输入当前书内关键词"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(initialKeyword)
            setSelection(text.length)
            setTextColor(primary)
            setHintTextColor(secondary)
        }
        val search = TextView(activity).apply {
            text = "搜索"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(search, LinearLayout.LayoutParams(dp(76), dp(40)))
        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(secondary)
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }
        val list = ListView(activity).apply {
            divider = ColorDrawable(withAlpha(primary, 0.10f))
            dividerHeight = dp(1)
        }
        root.addView(titleRow)
        root.addView(searchRow)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var hits = initialHits
        fun render() {
            status.text = when {
                input.text.isNullOrBlank() -> ""
                hits.isEmpty() -> "没有结果"
                else -> "共 ${hits.size} 条结果"
            }
            list.adapter = HitAdapter(activity, hits, primary, secondary)
            list.setOnItemClickListener { _, _, position, _ ->
                hits.getOrNull(position)?.let {
                    dialog.dismiss()
                    onHit(it)
                }
            }
        }
        fun execute() {
            val keyword = input.text.toString().trim()
            if (keyword.isBlank()) {
                hits = emptyList()
                render()
                return
            }
            search.isEnabled = false
            status.text = "搜索中…"
            onSearch(keyword) { result ->
                hits = result
                search.isEnabled = true
                render()
            }
        }
        search.setOnClickListener { execute() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                execute()
                true
            } else false
        }
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
            render()
        }
        dialog.show()
    }

    private class HitAdapter(
        private val context: Context,
        private val hits: List<SearchPageHit>,
        private val primaryColor: Int,
        private val secondaryColor: Int
    ) : BaseAdapter() {
        override fun getCount(): Int = hits.size
        override fun getItem(position: Int): SearchPageHit = hits[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            val holder: Holder
            val root = if (convertView is LinearLayout && convertView.tag is Holder) {
                holder = convertView.tag as Holder
                convertView
            } else {
                val preview = TextView(context).apply {
                    textSize = 16f
                    maxLines = 3
                    setTextColor(primaryColor)
                }
                val page = TextView(context).apply {
                    textSize = 13f
                    setTextColor(secondaryColor)
                    setPadding(0, dp(5), 0, 0)
                }
                holder = Holder(preview, page)
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    addView(preview)
                    addView(page)
                    tag = holder
                }
            }
            val hit = hits[position]
            holder.preview.text = hit.previewText
            holder.page.text = hit.pageLabel
            return root
        }
        private data class Holder(val preview: TextView, val page: TextView)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
