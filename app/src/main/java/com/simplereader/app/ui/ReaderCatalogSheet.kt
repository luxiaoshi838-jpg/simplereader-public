package com.simplereader.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.simplereader.app.data.entity.Bookmark

/** Clean catalog/bookmark sheet. Adding bookmarks remains only in the reader top bar. */
object ReaderCatalogSheet {
    fun show(
        activity: Activity,
        catalog: List<CatalogPageRow>,
        bookmarks: List<BookmarkPageRow>,
        startWithBookmarks: Boolean = false,
        onCatalog: (CatalogPageRow) -> Unit,
        onBookmark: (BookmarkPageRow) -> Unit,
        onDeleteBookmark: (BookmarkPageRow) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val palette = ReaderAppearance.palette(activity)
        val primary = palette.textColor
        val secondary = withAlpha(primary, 0.64f)
        val accent = Color.rgb(239, 122, 40)

        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ReaderSurfaceDrawable(palette.backgroundColor, seed = 901)
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabArea = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun tab(label: String): Pair<LinearLayout, TextView> {
            val text = TextView(activity).apply {
                this.text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary)
                setPadding(dp(18), dp(12), dp(18), dp(9))
            }
            val indicator = View(activity).apply { setBackgroundColor(accent) }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
                addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
                tag = indicator
            } to text
        }
        val (catalogTab, catalogText) = tab("目录")
        val (bookmarkTab, bookmarkText) = tab("书签")
        tabArea.addView(catalogTab)
        tabArea.addView(bookmarkTab)
        val close = TextView(activity).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(secondary)
            contentDescription = "关闭"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(tabArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

        val listFrame = FrameLayout(activity)
        val list = ListView(activity).apply {
            divider = ColorDrawable(withAlpha(primary, 0.10f))
            dividerHeight = dp(1)
            isFastScrollEnabled = true
            setPadding(0, dp(4), 0, dp(12))
            clipToPadding = false
        }
        val empty = TextView(activity).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(secondary)
        }
        listFrame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listFrame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        list.emptyView = empty
        root.addView(header)
        root.addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var showBookmarks = startWithBookmarks
        fun render() {
            val catalogIndicator = catalogTab.tag as View
            val bookmarkIndicator = bookmarkTab.tag as View
            catalogIndicator.visibility = if (showBookmarks) View.INVISIBLE else View.VISIBLE
            bookmarkIndicator.visibility = if (showBookmarks) View.VISIBLE else View.INVISIBLE
            catalogText.alpha = if (showBookmarks) 0.58f else 1f
            bookmarkText.alpha = if (showBookmarks) 1f else 0.58f
            if (showBookmarks) {
                empty.text = "暂无书签\n请在阅读页右上角添加"
                list.adapter = RowAdapter(
                    activity,
                    bookmarks.map {
                        Row(
                            primary = it.bookmark.content.ifBlank { "书签" },
                            secondary = it.pageLabel,
                            maxLines = 3
                        )
                    },
                    primary,
                    secondary
                )
                list.setOnItemClickListener { _, _, position, _ ->
                    bookmarks.getOrNull(position)?.let { row ->
                        dialog.dismiss()
                        onBookmark(row)
                    }
                }
                list.setOnItemLongClickListener { _, _, position, _ ->
                    bookmarks.getOrNull(position)?.let { row ->
                        dialog.dismiss()
                        onDeleteBookmark(row)
                    }
                    true
                }
            } else {
                empty.text = "未识别到目录"
                list.adapter = RowAdapter(
                    activity,
                    catalog.map { Row(it.title, it.pageLabel, 2) },
                    primary,
                    secondary
                )
                list.setOnItemClickListener { _, _, position, _ ->
                    catalog.getOrNull(position)?.let { row ->
                        dialog.dismiss()
                        onCatalog(row)
                    }
                }
                list.setOnItemLongClickListener(null)
            }
        }
        catalogTab.setOnClickListener { showBookmarks = false; render() }
        bookmarkTab.setOnClickListener { showBookmarks = true; render() }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = true
                }
            }
            render()
        }
        dialog.show()
    }

    private data class Row(val primary: String, val secondary: String, val maxLines: Int)

    private class RowAdapter(
        private val context: Context,
        private val rows: List<Row>,
        private val primaryColor: Int,
        private val secondaryColor: Int
    ) : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            val holder: Holder
            val root = if (convertView is LinearLayout && convertView.tag is Holder) {
                holder = convertView.tag as Holder
                convertView
            } else {
                val title = TextView(context).apply {
                    textSize = 16.5f
                    setTextColor(primaryColor)
                }
                val page = TextView(context).apply {
                    textSize = 13f
                    setTextColor(secondaryColor)
                    setPadding(0, dp(5), 0, 0)
                }
                holder = Holder(title, page)
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    addView(title)
                    addView(page)
                    tag = holder
                }
            }
            val row = rows[position]
            holder.title.text = row.primary
            holder.title.maxLines = row.maxLines
            holder.page.text = row.secondary
            return root
        }

        private data class Holder(val title: TextView, val page: TextView)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
