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

/** v625 picker: one complete background is selected; categories are alternatives, never stacked. */
object ReaderBackgroundPicker {
    fun show(
        activity: AppCompatActivity,
        selected: ReaderBackgrounds.Selection,
        onSelectionChanged: (ReaderBackgrounds.Selection) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        var currentSelection = ReaderBackgrounds.validated(selected)
        var activeCategory = currentSelection.category

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        val explanation = TextView(activity).apply {
            text = "每次选择一种完整背景。纯色、纹理、质感互为独立方案，不叠加、不平铺。"
            textSize = 13f
            setTextColor(Color.rgb(94, 86, 73))
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val summary = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.rgb(67, 61, 52))
            setPadding(dp(6), dp(10), dp(6), dp(2))
        }
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        scrollView.addView(grid)
        root.addView(explanation)
        root.addView(tabRow)
        root.addView(summary)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.52f).toInt()
            )
        )

        val tabViews = linkedMapOf<ReaderBackgrounds.Category, TextView>()
        fun tabBackground(selectedTab: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(if (selectedTab) Color.rgb(239, 122, 40) else Color.rgb(233, 230, 220))
        }
        fun renderSummary() {
            summary.text = "当前：${ReaderBackgrounds.summary(currentSelection)}"
        }
        fun renderTabs() {
            tabViews.forEach { (category, view) ->
                val active = category == activeCategory
                view.background = tabBackground(active)
                view.setTextColor(if (active) Color.WHITE else Color.rgb(66, 62, 54))
            }
        }
        fun isSelected(category: ReaderBackgrounds.Category, id: String): Boolean =
            currentSelection.category == category && currentSelection.optionId == id

        var renderGrid: () -> Unit = {}
        fun selectOption(category: ReaderBackgrounds.Category, id: String) {
            currentSelection = ReaderBackgrounds.selection(category, id)
            onSelectionChanged(currentSelection)
            renderSummary()
            renderTabs()
            renderGrid()
        }

        renderGrid = {
            grid.removeAllViews()
            ReaderBackgrounds.options(activeCategory).chunked(3).forEach { chunk ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                chunk.forEach { option ->
                    val selectedOption = isSelected(activeCategory, option.id)
                    val previewSelection = ReaderBackgrounds.selection(activeCategory, option.id)
                    val card = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(5), dp(4), dp(9))
                        isClickable = true
                        isFocusable = true
                    }
                    val preview = TextView(activity).apply {
                        text = if (selectedOption) "✓" else "Aa"
                        gravity = Gravity.CENTER
                        textSize = if (selectedOption) 22f else 19f
                        setTextColor(option.textColor)
                        background = ReaderBackgrounds.previewDrawable(activity, previewSelection, selectedOption)
                    }
                    val label = TextView(activity).apply {
                        text = option.title
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(Color.rgb(62, 58, 51))
                        setPadding(0, dp(5), 0, 0)
                    }
                    card.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))
                    card.addView(label)
                    card.setOnClickListener { selectOption(activeCategory, option.id) }
                    row.addView(
                        card,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(3)
                            marginEnd = dp(3)
                        }
                    )
                }
                repeat(3 - chunk.size) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
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

        renderSummary()
        renderTabs()
        renderGrid()
        AlertDialog.Builder(activity)
            .setTitle("阅读背景")
            .setView(root)
            .setPositiveButton("完成", null)
            .show()
    }
}
