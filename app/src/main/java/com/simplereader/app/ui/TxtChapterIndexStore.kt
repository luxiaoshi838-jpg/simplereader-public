package com.simplereader.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent TXT table of contents. Unlike cacheDir, this survives cache cleanup. */
object TxtChapterIndexStore {
    private const val VERSION = 2
    private const val MAX_CHAPTERS = 5000

    data class Entry(val title: String, val offset: Long)

    fun file(context: Context, bookId: Long): File =
        File(context.filesDir, "txt_chapters").apply { mkdirs() }.resolve("$bookId.json")

    @Synchronized
    fun load(
        context: Context,
        bookId: Long,
        totalBytes: Long,
        lastModified: Long,
        charsetName: String
    ): List<Entry> = runCatching {
        val target = file(context, bookId)
        if (!target.isFile) return emptyList()
        val root = JSONObject(target.readText(Charsets.UTF_8))
        if (root.optInt("version") != VERSION) return emptyList()
        if (root.optLong("totalBytes") != totalBytes) return emptyList()
        if (root.optLong("lastModified") != lastModified) return emptyList()
        if (root.optString("charset") != charsetName) return emptyList()
        val chapters = root.optJSONArray("chapters") ?: return emptyList()
        buildList {
            for (index in 0 until chapters.length()) {
                val item = chapters.optJSONObject(index) ?: continue
                val title = item.optString("title")
                val offset = item.optLong("offset", -1L)
                if (title.isNotBlank() && offset >= 0L) add(Entry(title, offset))
            }
        }
    }.getOrElse { emptyList() }

    @Synchronized
    fun save(
        context: Context,
        bookId: Long,
        totalBytes: Long,
        lastModified: Long,
        charsetName: String,
        chapters: List<Entry>
    ) {
        if (bookId <= 0L || chapters.isEmpty()) return
        runCatching {
            val items = JSONArray()
            chapters.take(MAX_CHAPTERS).forEach { chapter ->
                items.put(
                    JSONObject()
                        .put("title", chapter.title)
                        .put("offset", chapter.offset)
                )
            }
            val root = JSONObject()
                .put("version", VERSION)
                .put("bookId", bookId)
                .put("totalBytes", totalBytes)
                .put("lastModified", lastModified)
                .put("charset", charsetName)
                .put("chapters", items)
            val target = file(context, bookId)
            val temporary = target.resolveSibling("${target.name}.tmp")
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(root.toString(), Charsets.UTF_8)
                temporary.delete()
            }
        }
    }
}
