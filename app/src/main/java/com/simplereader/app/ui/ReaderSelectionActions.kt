package com.simplereader.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Selection toolbar restored in V768.
 *
 * The toolbar is attached only when the reader's “选中文本” switch is enabled.  It keeps the
 * platform Copy/Select-all entries and adds the reader actions requested by the user:
 * 翻译、朗读选中、笔记、划线、摘录、分享。
 *
 * Notes/excerpts/underlines are stored per book in app-private preferences so they survive reader
 * recreation without changing the Room schema.  Only underline has a visual span in the reader;
 * notes and excerpts are data records and the same note is reopened when the same range is selected.
 */
class ReaderSelectionActions(private val activity: Activity) {
    private data class Record(
        val kind: String,
        val startOffset: Int,
        val endOffset: Int,
        val text: String,
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    private data class Selected(
        val localStart: Int,
        val localEnd: Int,
        val sourceStart: Int,
        val sourceEnd: Int,
        val text: String
    )

    private val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
    private val cache = mutableMapOf<Long, MutableList<Record>>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    fun attach(
        view: TextView,
        bookId: Long,
        sourceOffset: Int,
        sourceTextProvider: () -> String?
    ) {
        view.setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                addAction(menu, ACTION_TRANSLATE, "翻译", 10)
                addAction(menu, ACTION_SPEAK, "朗读选中", 20)
                addAction(menu, ACTION_NOTE, "笔记", 30)
                addAction(menu, ACTION_UNDERLINE, "划线", 40)
                addAction(menu, ACTION_EXCERPT, "摘录", 50)
                addAction(menu, ACTION_SHARE, "分享", 60)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val selected = selected(view, sourceOffset, sourceTextProvider()) ?: return false
                val handled = when (item.itemId) {
                    ACTION_TRANSLATE -> { translate(selected.text); true }
                    ACTION_SPEAK -> { speak(selected.text); true }
                    ACTION_NOTE -> { showNoteDialog(bookId, selected); true }
                    ACTION_UNDERLINE -> { saveUnderline(bookId, view, selected); true }
                    ACTION_EXCERPT -> { saveExcerpt(bookId, selected); true }
                    ACTION_SHARE -> { share(selected.text); true }
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

    fun decorate(bookId: Long, sourceOffset: Int, text: CharSequence): CharSequence {
        if (text.isEmpty()) return text
        val underlines = records(bookId).filter { it.kind == KIND_UNDERLINE }
        if (underlines.isEmpty()) return text
        val pageStart = sourceOffset.coerceAtLeast(0)
        val pageEnd = pageStart + text.length
        val overlapping = underlines.filter { it.endOffset > pageStart && it.startOffset < pageEnd }
        if (overlapping.isEmpty()) return text
        val out = SpannableString(text)
        overlapping.forEach { record ->
            val localStart = (record.startOffset - pageStart).coerceIn(0, out.length)
            val localEnd = (record.endOffset - pageStart).coerceIn(0, out.length)
            if (localEnd > localStart) {
                out.setSpan(UnderlineSpan(), localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    fun release() {
        pendingSpeech = null
        ttsReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        cache.clear()
    }

    private fun addAction(menu: Menu, id: Int, title: String, order: Int) {
        menu.add(Menu.NONE, id, order, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    private fun selected(view: TextView, baseOffset: Int, sourceText: String?): Selected? {
        val rawStart = view.selectionStart
        val rawEnd = view.selectionEnd
        if (rawStart < 0 || rawEnd < 0 || rawStart == rawEnd) return null
        val localStart = minOf(rawStart, rawEnd).coerceIn(0, view.text.length)
        val localEnd = maxOf(rawStart, rawEnd).coerceIn(0, view.text.length)
        if (localEnd <= localStart) return null
        val selectedText = view.text.subSequence(localStart, localEnd).toString()
        if (selectedText.isBlank()) return null
        val guessedStart = (baseOffset + localStart).coerceAtLeast(0)
        val resolvedStart = resolveSourceStart(sourceText, guessedStart, selectedText)
        return Selected(
            localStart = localStart,
            localEnd = localEnd,
            sourceStart = resolvedStart,
            sourceEnd = resolvedStart + selectedText.length,
            text = selectedText
        )
    }

    /**
     * Horizontal title rendering can normalize whitespace.  Resolve the selected string back to the
     * nearest source occurrence so persisted underline offsets remain tied to the source document.
     */
    private fun resolveSourceStart(source: String?, guess: Int, selected: String): Int {
        if (source.isNullOrEmpty() || selected.isEmpty()) return guess
        val safeGuess = guess.coerceIn(0, source.length)
        val from = (safeGuess - SOURCE_MATCH_RADIUS).coerceAtLeast(0)
        val to = (safeGuess + selected.length + SOURCE_MATCH_RADIUS).coerceAtMost(source.length)
        var cursor = source.indexOf(selected, from)
        var best = -1
        var bestDistance = Int.MAX_VALUE
        while (cursor >= 0 && cursor <= to - selected.length) {
            val distance = abs(cursor - safeGuess)
            if (distance < bestDistance) {
                best = cursor
                bestDistance = distance
            }
            cursor = source.indexOf(selected, cursor + 1)
        }
        return if (best >= 0) best else safeGuess
    }

    private fun translate(text: String) {
        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val handlers = activity.packageManager.queryIntentActivities(processIntent, 0)
        if (handlers.isNotEmpty()) {
            activity.startActivity(Intent.createChooser(processIntent, "翻译"))
            return
        }
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://translate.google.com/?sl=auto&text=${Uri.encode(text)}&op=translate")
        )
        runCatching { activity.startActivity(web) }
            .onFailure { Toast.makeText(activity, "未找到可用的翻译应用", Toast.LENGTH_SHORT).show() }
    }

    private fun speak(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (tts == null) {
            pendingSpeech = content
            tts = TextToSpeech(activity.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    Toast.makeText(activity, "系统朗读服务不可用", Toast.LENGTH_SHORT).show()
                    pendingSpeech = null
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                val localeResult = engine.setLanguage(Locale.getDefault())
                if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.CHINESE)
                }
                pendingSpeech?.let(::speakNow)
                pendingSpeech = null
            }
            return
        }
        if (!ttsReady) {
            pendingSpeech = content
            return
        }
        speakNow(content)
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        val max = (TextToSpeech.getMaxSpeechInputLength() - 64).coerceAtLeast(512)
        val chunks = text.chunked(max)
        chunks.forEachIndexed { index, chunk ->
            engine.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "simplereader-selection-$index-${System.nanoTime()}"
            )
        }
    }

    private fun showNoteDialog(bookId: Long, selected: Selected) {
        val existing = records(bookId).lastOrNull {
            it.kind == KIND_NOTE && it.startOffset == selected.sourceStart && it.endOffset == selected.sourceEnd
        }
        val input = EditText(activity).apply {
            minLines = 3
            maxLines = 8
            hint = "输入笔记"
            setText(existing?.note.orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle("笔记")
            .setMessage(selected.text.take(MAX_DIALOG_PREVIEW))
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val note = input.text?.toString().orEmpty().trim()
                val list = records(bookId)
                if (existing != null) list.remove(existing)
                list += Record(KIND_NOTE, selected.sourceStart, selected.sourceEnd, selected.text, note)
                persist(bookId)
                Toast.makeText(activity, "笔记已保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveUnderline(bookId: Long, view: TextView, selected: Selected) {
        val list = records(bookId)
        val exists = list.any {
            it.kind == KIND_UNDERLINE &&
                it.startOffset == selected.sourceStart &&
                it.endOffset == selected.sourceEnd
        }
        if (!exists) {
            list += Record(KIND_UNDERLINE, selected.sourceStart, selected.sourceEnd, selected.text)
            persist(bookId)
        }
        val spannable = when (val value = view.text) {
            is Spannable -> value
            else -> SpannableString(value).also { view.setText(it, TextView.BufferType.SPANNABLE) }
        }
        if (selected.localEnd <= spannable.length) {
            spannable.setSpan(
                UnderlineSpan(),
                selected.localStart,
                selected.localEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            view.invalidate()
        }
        Toast.makeText(activity, "已划线", Toast.LENGTH_SHORT).show()
    }

    private fun saveExcerpt(bookId: Long, selected: Selected) {
        val list = records(bookId)
        val exists = list.any {
            it.kind == KIND_EXCERPT &&
                it.startOffset == selected.sourceStart &&
                it.endOffset == selected.sourceEnd &&
                it.text == selected.text
        }
        if (!exists) {
            list += Record(KIND_EXCERPT, selected.sourceStart, selected.sourceEnd, selected.text)
            persist(bookId)
        }
        Toast.makeText(activity, "摘录已保存", Toast.LENGTH_SHORT).show()
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "分享选中文本")) }
            .onFailure { Toast.makeText(activity, "未找到可用的分享应用", Toast.LENGTH_SHORT).show() }
    }

    private fun records(bookId: Long): MutableList<Record> = cache.getOrPut(bookId) { load(bookId) }

    private fun load(bookId: Long): MutableList<Record> {
        val raw = prefs.getString(key(bookId), null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val obj = array.getJSONObject(index)
                Record(
                    kind = obj.getString("kind"),
                    startOffset = obj.getInt("start"),
                    endOffset = obj.getInt("end"),
                    text = obj.optString("text", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0L)
                )
            }
        }.getOrElse { mutableListOf() }
    }

    private fun persist(bookId: Long) {
        val array = JSONArray()
        records(bookId).forEach { record ->
            array.put(JSONObject().apply {
                put("kind", record.kind)
                put("start", record.startOffset)
                put("end", record.endOffset)
                put("text", record.text)
                put("note", record.note)
                put("createdAt", record.createdAt)
            })
        }
        prefs.edit().putString(key(bookId), array.toString()).apply()
    }

    private fun key(bookId: Long): String = "book_$bookId"

    companion object {
        private const val PREFS = "reader_selection_annotations_v1"
        private const val KIND_NOTE = "note"
        private const val KIND_UNDERLINE = "underline"
        private const val KIND_EXCERPT = "excerpt"
        private const val MAX_DIALOG_PREVIEW = 220
        private const val SOURCE_MATCH_RADIUS = 256

        private const val ACTION_TRANSLATE = 0x535201
        private const val ACTION_SPEAK = 0x535202
        private const val ACTION_NOTE = 0x535203
        private const val ACTION_UNDERLINE = 0x535204
        private const val ACTION_EXCERPT = 0x535205
        private const val ACTION_SHARE = 0x535206
    }
}
