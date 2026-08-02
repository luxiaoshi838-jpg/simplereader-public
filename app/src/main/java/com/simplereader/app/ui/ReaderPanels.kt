package com.simplereader.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.simplereader.app.data.entity.Bookmark

data class CatalogPageRow(
    val title: String,
    val chapterIndex: Int,
    val globalPageIndex: Int,
    val pageLabel: String
)

data class BookmarkPageRow(
    val bookmark: Bookmark,
    val pageLabel: String
)

data class SearchPageHit(
    val keyword: String,
    val chapterIndex: Int,
    val globalPageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val previewText: String,
    val pageLabel: String
)

object ReaderPanels {
    fun showCatalogAndBookmarks(
        activity: Activity,
        catalog: List<CatalogPageRow>,
        bookmarks: List<BookmarkPageRow>,
        startWithBookmarks: Boolean = false,
        onCatalog: (CatalogPageRow) -> Unit,
        onBookmark: (BookmarkPageRow) -> Unit,
        onAddBookmark: () -> Unit,
        onDeleteBookmark: (BookmarkPageRow) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val catalogTab = Button(activity).apply { text = "目录"; isAllCaps = false }
        val bookmarkTab = Button(activity).apply { text = "书签"; isAllCaps = false }
        val addButton = Button(activity).apply { text = "添加书签"; isAllCaps = false }
        tabs.addView(catalogTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(bookmarkTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(addButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        val list = ListView(activity).apply {
            dividerHeight = dp(1)
            isFastScrollEnabled = true
        }
        root.addView(tabs)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var showBookmarks = startWithBookmarks
        lateinit var dialog: AlertDialog
        fun refresh() {
            if (showBookmarks) {
                list.adapter = TwoLineAdapter(
                    activity,
                    bookmarks.map { row ->
                        TwoLineRow(
                            primary = row.bookmark.content.ifBlank { "书签" },
                            secondary = row.pageLabel,
                            maxPrimaryLines = 3
                        )
                    }
                )
                list.setOnItemClickListener { _, _, position, _ ->
                    bookmarks.getOrNull(position)?.let {
                        dialog.dismiss()
                        onBookmark(it)
                    }
                }
                list.setOnItemLongClickListener { _, _, position, _ ->
                    bookmarks.getOrNull(position)?.let(onDeleteBookmark)
                    true
                }
            } else {
                list.adapter = TwoLineAdapter(
                    activity,
                    catalog.map { row -> TwoLineRow(row.title, row.pageLabel, 2) }
                )
                list.setOnItemClickListener { _, _, position, _ ->
                    catalog.getOrNull(position)?.let {
                        dialog.dismiss()
                        onCatalog(it)
                    }
                }
                list.setOnItemLongClickListener(null)
            }
            catalogTab.isEnabled = showBookmarks
            bookmarkTab.isEnabled = !showBookmarks
        }
        catalogTab.setOnClickListener { showBookmarks = false; refresh() }
        bookmarkTab.setOnClickListener { showBookmarks = true; refresh() }
        addButton.setOnClickListener {
            dialog.dismiss()
            onAddBookmark()
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("目录与书签")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener { refresh() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    fun showSearch(
        activity: Activity,
        initialKeyword: String,
        initialHits: List<SearchPageHit>,
        onSearch: (String, (List<SearchPageHit>) -> Unit) -> Unit,
        onHit: (SearchPageHit) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(activity).apply {
            hint = "输入当前书内关键词"
            setSingleLine(true)
            setText(initialKeyword)
            setSelection(text.length)
        }
        val button = Button(activity).apply { text = "搜索"; isAllCaps = false }
        val status = TextView(activity).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(Color.rgb(100, 92, 80))
        }
        val list = ListView(activity).apply { dividerHeight = dp(1) }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button, LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var hits = initialHits
        lateinit var dialog: AlertDialog

        fun render() {
            status.text = if (hits.isEmpty()) "没有结果" else "共 ${hits.size} 条结果"
            list.adapter = TwoLineAdapter(
                activity,
                hits.map { hit -> TwoLineRow(hit.previewText, hit.pageLabel, 3) }
            )
            list.setOnItemClickListener { _, _, position, _ ->
                hits.getOrNull(position)?.let {
                    dialog.dismiss()
                    onHit(it)
                }
            }
        }
        fun search() {
            val keyword = input.text.toString().trim()
            if (keyword.isBlank()) {
                hits = emptyList()
                render()
                return
            }
            button.isEnabled = false
            status.text = "搜索中…"
            onSearch(keyword) { result ->
                hits = result
                button.isEnabled = true
                render()
            }
        }
        button.setOnClickListener { search() }
        input.setOnEditorActionListener { _, _, _ -> search(); true }
        dialog = AlertDialog.Builder(activity)
            .setTitle("书内搜索")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener { render() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    private data class TwoLineRow(
        val primary: String,
        val secondary: String,
        val maxPrimaryLines: Int
    )

    private class TwoLineAdapter(
        private val activity: Activity,
        private val rows: List<TwoLineRow>
    ) : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): TwoLineRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val holder: Holder
            val root = if (convertView is LinearLayout && convertView.tag is Holder) {
                holder = convertView.tag as Holder
                convertView
            } else {
                val primary = TextView(activity).apply {
                    textSize = 16f
                    setTextColor(Color.rgb(38, 35, 31))
                    setTypeface(typeface, Typeface.NORMAL)
                }
                val secondary = TextView(activity).apply {
                    textSize = 13f
                    setTextColor(Color.rgb(116, 102, 82))
                }
                holder = Holder(primary, secondary)
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(primary)
                    addView(secondary)
                    tag = holder
                }
            }
            val row = rows[position]
            holder.primary.text = row.primary
            holder.primary.maxLines = row.maxPrimaryLines
            holder.secondary.text = row.secondary
            return root
        }

        private data class Holder(val primary: TextView, val secondary: TextView)
    }
}
