package com.simplereader.app.operation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.simplereader.app.crash.CrashLogStore

/** Operation-log UI. The list page deliberately has no copy button. */
object OperationLogDialogs {

    fun showLogHub(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("日志")
            .setItems(arrayOf("闪退/崩溃日志", "操作日志")) { _, which ->
                when (which) {
                    0 -> showPendingCrashLog(activity)
                    1 -> showOperationList(activity)
                }
            }
            .show()
    }

    fun showOperationList(activity: AppCompatActivity) {
        val entries = OperationLogStore.list(activity)
        if (entries.isEmpty()) {
            Toast.makeText(activity, "暂无操作日志", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("操作日志")
            .setItems(entries.map { it.title }.toTypedArray()) { _, which ->
                entries.getOrNull(which)?.let { showOperationDetail(activity, it) }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showOperationDetail(activity: AppCompatActivity, entry: OperationLogStore.Entry) {
        val bodyView = TextView(activity).apply {
            text = entry.body
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 12))
        }
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(bodyView)
        }
        val seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            contentDescription = "日志滚动进度"
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 420)
                )
            )
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        var dragging = false
        fun maxScroll(): Int = (bodyView.height - scrollView.height).coerceAtLeast(0)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val max = maxScroll()
                val target = if (max <= 0) 0 else (max * (progress / 1000f)).toInt()
                scrollView.scrollTo(0, target)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                dragging = false
            }
        })
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (dragging) return@addOnScrollChangedListener
            val max = maxScroll()
            val progress = if (max <= 0) 0 else (scrollView.scrollY * 1000L / max).toInt()
            if (seekBar.progress != progress) seekBar.progress = progress.coerceIn(0, 1000)
        }

        AlertDialog.Builder(activity)
            .setTitle(entry.title)
            .setView(content)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("简阅操作日志", entry.body))
                Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showPendingCrashLog(activity: AppCompatActivity) {
        val body = CrashLogStore.readPending(activity)
        if (body.isNullOrBlank()) {
            Toast.makeText(activity, "暂无闪退/崩溃日志", Toast.LENGTH_SHORT).show()
            return
        }
        val text = TextView(activity).apply {
            this.text = body
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 12))
        }
        AlertDialog.Builder(activity)
            .setTitle("闪退/崩溃日志")
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("简阅闪退日志", body))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
