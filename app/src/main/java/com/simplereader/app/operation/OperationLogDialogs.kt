package com.simplereader.app.operation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.simplereader.app.crash.CrashLogStore

/**
 * Compatibility entry for the existing `数据导出 → 日志` call site.
 *
 * Operation-log UI was removed by product decision. The only user-facing log destination is the
 * retained crash-log directory. Keeping this small compatibility object avoids a risky rewrite of
 * MainActivity while making the source behavior identical to the accepted v742 route.
 */
object OperationLogDialogs {

    fun showLogHub(activity: AppCompatActivity) = showCrashLogList(activity)

    private fun showCrashLogList(activity: AppCompatActivity) {
        val entries = CrashLogStore.list(activity)
        if (entries.isEmpty()) {
            Toast.makeText(activity, "暂无闪退/崩溃日志", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("闪退/崩溃日志")
            .setItems(entries.map { it.displayName }.toTypedArray()) { _, which ->
                entries.getOrNull(which)?.let { showCrashLogEntry(activity, it) }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showCrashLogEntry(activity: AppCompatActivity, entry: CrashLogStore.Entry) {
        val body = CrashLogStore.read(activity, entry)
        if (body.isNullOrBlank()) {
            Toast.makeText(activity, "日志文件已不存在", Toast.LENGTH_SHORT).show()
            showCrashLogList(activity)
            return
        }
        val text = TextView(activity).apply {
            this.text = body
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 12))
        }
        AlertDialog.Builder(activity)
            .setTitle(entry.displayName)
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("简阅闪退日志", body))
                Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
