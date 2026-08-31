package com.simplereader.app.reader

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import com.simplereader.app.ui.ReaderPageSnapshot

/**
 * V682/V745 rendering-only chapter-title cleanup. The source text, chapter offsets, search anchors
 * and persisted progress remain untouched; only rendered chapter-title lines are whitespace-normalized.
 */
object ReaderBodyTitleNormalizerV104 {
    /** v745 placement: horizontal paged snapshots only; vertical renderPage remains untouched. */
    @JvmStatic
    fun normalizeSnapshot(snapshot: ReaderPageSnapshot): CharSequence = normalize(snapshot.content)

    @JvmStatic
    fun normalize(content: CharSequence): CharSequence {
        if (content.isEmpty()) return content
        val source = if (content is Spanned) content else return content
        val out = SpannableStringBuilder(content)
        var cursor = 0
        // Work from the end so replacing an earlier line never invalidates later source coordinates.
        val replacements = ArrayList<Triple<Int, Int, String>>()
        while (cursor <= content.length) {
            val lineEnd = indexOfNewline(content, cursor)
            val end = if (lineEnd < 0) content.length else lineEnd
            if (end > cursor) {
                val spans = source.getSpans(cursor, end, RelativeSizeSpan::class.java)
                if (spans.isNotEmpty()) {
                    val raw = content.subSequence(cursor, end).toString()
                    if (CatalogTitleNormalizerV103.recognizeNormalized(raw) != null) {
                        val normalized = CatalogTitleNormalizerV103.normalize(raw)
                        if (normalized != null && normalized != raw) replacements += Triple(cursor, end, normalized)
                    }
                }
            }
            if (lineEnd < 0) break
            cursor = lineEnd + 1
        }
        for ((start, end, replacement) in replacements.asReversed()) out.replace(start, end, replacement)
        return out
    }

    private fun indexOfNewline(text: CharSequence, from: Int): Int {
        for (i in from until text.length) if (text[i] == '\n') return i
        return -1
    }
}
