package com.simplereader.app.ui

import android.app.Activity
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast

/**
 * V768 selected-text toolbar.
 *
 * Final requested action set: 复制、翻译、搜索。No note/underline/excerpt/share/TTS actions are
 * exposed or retained here. The callback is attached only while the reader's “选中文本” switch is
 * enabled.
 */
class ReaderSelectionActions(private val activity: Activity) {

    @Suppress("UNUSED_PARAMETER")
    fun attach(
        view: TextView,
        bookId: Long,
        sourceOffset: Int,
        sourceTextProvider: () -> String?
    ) = attach(view, bookId, { sourceOffset }, sourceTextProvider)

    @Suppress("UNUSED_PARAMETER")
    fun attach(
        view: TextView,
        bookId: Long,
        sourceOffsetProvider: () -> Int,
        sourceTextProvider: () -> String?
    ) {
        view.setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // Keep the selected-text toolbar deliberately limited to the three requested actions.
                menu.clear()
                addAction(menu, ACTION_COPY, "复制", 0, MenuItem.SHOW_AS_ACTION_ALWAYS)
                addAction(menu, ACTION_TRANSLATE, "翻译", 10)
                addAction(menu, ACTION_SEARCH, "搜索", 20)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val text = selectedText(view) ?: return false
                val handled = when (item.itemId) {
                    ACTION_COPY -> {
                        copy(text)
                        true
                    }
                    ACTION_TRANSLATE -> {
                        translate(text)
                        true
                    }
                    ACTION_SEARCH -> {
                        search(text)
                        true
                    }
                    else -> false
                }
                if (handled) mode.finish()
                return handled
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        })
    }

    fun clearCallback(view: TextView) {
        view.setCustomSelectionActionModeCallback(null)
    }

    @Suppress("UNUSED_PARAMETER")
    fun decorate(bookId: Long, sourceOffset: Int, text: CharSequence): CharSequence = text

    fun release() = Unit

    private fun addAction(
        menu: Menu,
        id: Int,
        title: String,
        order: Int,
        showAsAction: Int = MenuItem.SHOW_AS_ACTION_IF_ROOM
    ) {
        menu.add(Menu.NONE, id, order, title).setShowAsAction(showAsAction)
    }

    private fun selectedText(view: TextView): String? {
        val rawStart = view.selectionStart
        val rawEnd = view.selectionEnd
        if (rawStart < 0 || rawEnd < 0 || rawStart == rawEnd) return null
        val start = minOf(rawStart, rawEnd).coerceIn(0, view.text.length)
        val end = maxOf(rawStart, rawEnd).coerceIn(0, view.text.length)
        if (end <= start) return null
        return view.text.subSequence(start, end).toString().takeIf { it.isNotBlank() }
    }

    private fun copy(text: String) {
        val clipboard = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("简阅选中文本", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun translate(text: String) {
        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        if (activity.packageManager.queryIntentActivities(processIntent, 0).isNotEmpty()) {
            runCatching { activity.startActivity(Intent.createChooser(processIntent, "翻译")) }
                .onSuccess { return }
        }
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://translate.google.com/?sl=auto&text=${Uri.encode(text)}&op=translate")
        )
        runCatching { activity.startActivity(web) }
            .onFailure { Toast.makeText(activity, "未找到可用的翻译应用", Toast.LENGTH_SHORT).show() }
    }

    private fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
        }
        if (activity.packageManager.resolveActivity(webSearch, 0) != null) {
            runCatching { activity.startActivity(webSearch) }
                .onSuccess { return }
        }
        val browserSearch = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        )
        runCatching { activity.startActivity(browserSearch) }
            .onFailure { Toast.makeText(activity, "未找到可用的搜索应用", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val ACTION_COPY = 0x535200
        private const val ACTION_TRANSLATE = 0x535201
        private const val ACTION_SEARCH = 0x535202
    }
}
