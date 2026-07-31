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

/** v585 分层背景选择页：颜色、纹理、质感可自由搭配。 */
object ReaderBackgroundPicker {
    fun show(
        activity: AppCompatActivity,
        selected: ReaderBackgrounds.Selection,
        onSelectionChanged: (ReaderBackgrounds.Selection) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        var currentSelection = ReaderBackgrounds.validated(selected)
        var activeCategory = ReaderBackgrounds.Category.COLOR
        var dialog: AlertDialog? = null

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        val explanation = TextView(activity).apply {
            text = "颜色决定底色；纹理与质感分别叠加，也可各自选择“纯净”关闭。"
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

        fun isSelected(category: ReaderBackgrounds.Category, id: String): Boolean = when (category) {
            ReaderBackgrounds.Category.COLOR -> currentSelection.colorId == id
            ReaderBackgrounds.Category.TEXTURE -> currentSelection.textureId == id
            ReaderBackgrounds.Category.MATERIAL -> currentSelection.materialId == id
        }

        fun previewSelection(category: ReaderBackgrounds.Category, id: String): ReaderBackgrounds.Selection =
            when (category) {
                ReaderBackgrounds.Category.COLOR -> currentSelection.copy(colorId = id)
                ReaderBackgrounds.Category.TEXTURE -> currentSelection.copy(textureId = id)
                ReaderBackgrounds.Category.MATERIAL -> currentSelection.copy(materialId = id)
            }

        fun optionPairs(): List<Pair<String, String>> = when (activeCategory) {
            ReaderBackgrounds.Category.COLOR -> ReaderBackgrounds.colorOptions.map { it.id to it.title }
            ReaderBackgrounds.Category.TEXTURE -> ReaderBackgrounds.textureOptions.map { it.id to it.title }
            ReaderBackgrounds.Category.MATERIAL -> ReaderBackgrounds.materialOptions.map { it.id to it.title }
        }

        fun selectOption(category: ReaderBackgrounds.Category, id: String) {
            currentSelection = ReaderBackgrounds.validated(previewSelection(category, id))
            onSelectionChanged(currentSelection)
            renderSummary()
            renderTabs()
            renderGrid()
        }

        fun renderGridInternal() {
            grid.removeAllViews()
            optionPairs().chunked(3).forEach { chunk ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                chunk.forEach { (id, title) ->
                    val selectedOption = isSelected(activeCategory, id)
                    val previewValue = previewSelection(activeCategory, id)
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
                        setTextColor(ReaderBackgrounds.color(previewValue.colorId).textColor)
                        background = ReaderBackgrounds.previewDrawable(
                            context = activity,
                            selection = previewValue,
                            selected = selectedOption
                        )
                    }
                    val label = TextView(activity).apply {
                        text = title
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(Color.rgb(62, 58, 51))
                        setPadding(0, dp(5), 0, 0)
                    }
                    card.addView(
                        preview,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(76)
                        )
                    )
                    card.addView(label)
                    card.setOnClickListener { selectOption(activeCategory, id) }
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

        fun renderGrid() = renderGridInternal()

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
        dialog = AlertDialog.Builder(activity)
            .setTitle("阅读背景 · 自由搭配")
            .setView(root)
            .setPositiveButton("完成", null)
            .create()
        dialog?.show()
    }
}
