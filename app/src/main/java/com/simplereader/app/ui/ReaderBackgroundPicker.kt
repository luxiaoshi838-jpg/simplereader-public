package com.simplereader.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 多看式“快捷背景 + 更多分类”完整选择页。 */
object ReaderBackgroundPicker {
    fun show(
        activity: AppCompatActivity,
        selectedId: String,
        onSelected: (ReaderBackgrounds.Preset) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        scrollView.addView(grid)
        root.addView(tabRow)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        )

        var dialog: AlertDialog? = null
        var activeCategory = ReaderBackgrounds.preset(selectedId).category
        val tabViews = linkedMapOf<ReaderBackgrounds.Category, TextView>()

        fun tabBackground(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(if (selected) Color.rgb(239, 122, 40) else Color.rgb(233, 230, 220))
        }

        fun renderTabs() {
            tabViews.forEach { (category, view) ->
                val active = category == activeCategory
                view.background = tabBackground(active)
                view.setTextColor(if (active) Color.WHITE else Color.rgb(66, 62, 54))
            }
        }

        fun renderGrid() {
            grid.removeAllViews()
            val presets = ReaderBackgrounds.presets(activeCategory)
            presets.chunked(3).forEach { chunk ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                chunk.forEach { preset ->
                    val card = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(5), dp(4), dp(8))
                        isClickable = true
                        isFocusable = true
                    }
                    val preview = TextView(activity).apply {
                        text = if (preset.id == selectedId) "✓" else "Aa"
                        gravity = Gravity.CENTER
                        textSize = if (preset.id == selectedId) 22f else 19f
                        setTextColor(preset.textColor)
                        background = ReaderBackgrounds.previewDrawable(
                            preset.id,
                            preset.id == selectedId
                        )
                    }
                    val label = TextView(activity).apply {
                        text = preset.title
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(Color.rgb(62, 58, 51))
                        setPadding(0, dp(5), 0, 0)
                    }
                    card.addView(
                        preview,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(72)
                        )
                    )
                    card.addView(label)
                    card.setOnClickListener {
                        onSelected(preset)
                        dialog?.dismiss()
                    }
                    row.addView(
                        card,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(3)
                            marginEnd = dp(3)
                        }
                    )
                }
                repeat(3 - chunk.size) {
                    row.addView(
                        View(activity),
                        LinearLayout.LayoutParams(0, 1, 1f)
                    )
                }
                grid.addView(row)
            }
        }

        ReaderBackgrounds.Category.values().forEach { category ->
            val tab = TextView(activity).apply {
                text = category.title
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    activeCategory = category
                    renderTabs()
                    renderGrid()
                }
            }
            tabViews[category] = tab
            tabRow.addView(
                tab,
                LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            )
        }

        renderTabs()
        renderGrid()
        dialog = AlertDialog.Builder(activity)
            .setTitle("阅读背景")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.show()
    }
}
