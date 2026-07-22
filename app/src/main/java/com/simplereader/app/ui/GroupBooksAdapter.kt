package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.data.model.ShelfBookItem

/** Lightweight virtualized grid adapter for a category screen. */
class GroupBooksAdapter(
    private val onClick: (ShelfBookItem) -> Unit,
    private val onLongClick: (ShelfBookItem) -> Unit
) : ListAdapter<ShelfBookItem, GroupBooksAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(10))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(3), dp(3), dp(3), dp(6))
            }
            isClickable = true
            isFocusable = true
        }
        val cover = TextView(parent.context).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(74, 126, 172), Color.rgb(47, 94, 136))
            ).apply { cornerRadius = dp(4).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(104)
            )
        }
        val title = TextView(parent.context).apply {
            textSize = 15f
            setTextColor(Color.rgb(30, 30, 30))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(5), dp(2), 0)
        }
        val progress = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(Color.rgb(130, 126, 118))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        root.addView(cover)
        root.addView(title)
        root.addView(progress)
        return Holder(root, cover, title, progress, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class Holder(
        root: LinearLayout,
        private val cover: TextView,
        private val title: TextView,
        private val progress: TextView,
        private val onClick: (ShelfBookItem) -> Unit,
        private val onLongClick: (ShelfBookItem) -> Unit
    ) : RecyclerView.ViewHolder(root) {
        private var boundItem: ShelfBookItem? = null

        init {
            root.setOnClickListener {
                boundItem?.let(onClick)
            }
            root.setOnLongClickListener {
                val item = boundItem ?: return@setOnLongClickListener false
                onLongClick(item)
                true
            }
        }

        fun bind(value: ShelfBookItem) {
            boundItem = value
            cover.text = value.title.take(22)
            title.text = value.title
            val status = if (value.fileStatus == "AVAILABLE") "" else " · ${value.fileStatus}"
            progress.text = "已读 ${value.progressPercent()}%$status"
            itemView.contentDescription = "${value.title}，已读 ${value.progressPercent()}%"
        }

        fun clear() {
            boundItem = null
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ShelfBookItem>() {
            override fun areItemsTheSame(oldItem: ShelfBookItem, newItem: ShelfBookItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ShelfBookItem, newItem: ShelfBookItem): Boolean =
                oldItem == newItem
        }
    }
}
