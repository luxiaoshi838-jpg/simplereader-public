package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** One selectable hit in the reader's incrementally loaded search result list. */
data class ReaderSearchHit(
    val stableKey: String,
    val position: Long,
    val positionLabel: String,
    val preview: String
)

data class ReaderSearchPage(
    val hits: List<ReaderSearchHit>,
    val nextPosition: Long,
    val endReached: Boolean
)

/** AndroidX ListAdapter keeps updates lightweight through DiffUtil and view recycling. */
class ReaderSearchResultAdapter(
    private val onClick: (ReaderSearchHit) -> Unit
) : ListAdapter<ReaderSearchHit, ReaderSearchResultAdapter.Holder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }
        val positionView = TextView(parent.context).apply {
            textSize = 13f
            setTextColor(Color.rgb(116, 102, 82))
            setTypeface(typeface, Typeface.BOLD)
        }
        val previewView = TextView(parent.context).apply {
            textSize = 16f
            setTextColor(Color.rgb(38, 35, 31))
            maxLines = 3
            setPadding(0, dp(4), 0, dp(6))
        }
        val divider = TextView(parent.context).apply {
            setBackgroundColor(Color.rgb(225, 220, 207))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }
        root.addView(positionView)
        root.addView(previewView)
        root.addView(divider)
        return Holder(root, positionView, previewView, onClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        root: LinearLayout,
        private val positionView: TextView,
        private val previewView: TextView,
        private val onClick: (ReaderSearchHit) -> Unit
    ) : RecyclerView.ViewHolder(root) {
        private var item: ReaderSearchHit? = null

        init {
            root.setOnClickListener { item?.let(onClick) }
        }

        fun bind(value: ReaderSearchHit) {
            item = value
            positionView.text = value.positionLabel
            previewView.text = value.preview
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ReaderSearchHit>() {
            override fun areItemsTheSame(oldItem: ReaderSearchHit, newItem: ReaderSearchHit): Boolean =
                oldItem.stableKey == newItem.stableKey

            override fun areContentsTheSame(oldItem: ReaderSearchHit, newItem: ReaderSearchHit): Boolean =
                oldItem == newItem
        }
    }
}
