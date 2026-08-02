package com.simplereader.app.reader.page

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PageCacheStore {
    private const val CACHE_VERSION = 2
    private const val ROOT = "reader_page_cache"
    private const val MANIFEST = "pages.json"
    private const val TXT_CONTENT = "content.txt"
    private const val TXT_SOURCE = "txt-source.json"

    data class CacheIdentity(
        val bookId: Long,
        val filePath: String,
        val fileSize: Long,
        val lastModified: Long,
        val settingsHash: String
    )

    data class NormalizedTxt(
        val textFile: File,
        val chapters: List<BookChapter>,
        val charsetName: String
    )

    fun loadPages(context: Context, identity: CacheIdentity, text: String): ReaderBook? {
        val file = bookDir(context, identity.bookId).resolve(MANIFEST)
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("cacheVersion") == CACHE_VERSION)
            require(root.optLong("bookId") == identity.bookId)
            require(root.optString("filePath") == identity.filePath)
            require(root.optLong("fileSize") == identity.fileSize)
            require(root.optLong("lastModified") == identity.lastModified)
            require(root.optString("readerSettingsHash") == identity.settingsHash)
            val chapters = root.getJSONArray("chapters").toBookChapters(text.length)
            val rawPages = root.getJSONArray("pages")
            val total = rawPages.length()
            require(total > 0)
            val pages = (0 until total).map { index ->
                val item = rawPages.getJSONObject(index)
                ReaderPage(
                    globalPageIndex = index,
                    totalPageCount = total,
                    chapterIndex = item.getInt("chapterIndex"),
                    pageIndexInChapter = item.getInt("pageIndexInChapter"),
                    chapterPageCount = item.getInt("chapterPageCount"),
                    startOffset = item.getInt("startOffset").coerceIn(0, text.length),
                    endOffset = item.getInt("endOffset").coerceIn(0, text.length)
                )
            }
            require(pages.zipWithNext().all { (a, b) -> a.startOffset <= a.endOffset && a.endOffset <= b.endOffset })
            ReaderBook(text, chapters, pages, identity.settingsHash)
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun savePages(context: Context, identity: CacheIdentity, book: ReaderBook) {
        val directory = bookDir(context, identity.bookId).apply { mkdirs() }
        val chapters = JSONArray().apply {
            book.chapters.forEach { chapter ->
                put(JSONObject()
                    .put("title", chapter.title)
                    .put("startOffset", chapter.startOffset)
                    .put("endOffset", chapter.endOffset)
                    .put("sourceHref", chapter.sourceHref)
                    .put("catalogVisible", chapter.catalogVisible))
            }
        }
        val pages = JSONArray().apply {
            book.pages.forEach { page ->
                put(JSONObject()
                    .put("chapterIndex", page.chapterIndex)
                    .put("pageIndexInChapter", page.pageIndexInChapter)
                    .put("chapterPageCount", page.chapterPageCount)
                    .put("startOffset", page.startOffset)
                    .put("endOffset", page.endOffset))
            }
        }
        val root = JSONObject()
            .put("cacheVersion", CACHE_VERSION)
            .put("bookId", identity.bookId)
            .put("filePath", identity.filePath)
            .put("fileSize", identity.fileSize)
            .put("lastModified", identity.lastModified)
            .put("readerSettingsHash", identity.settingsHash)
            .put("chapters", chapters)
            .put("pages", pages)
        atomicWrite(directory.resolve(MANIFEST), root.toString())
    }

    fun loadNormalizedTxt(
        context: Context,
        bookId: Long,
        filePath: String,
        fileSize: Long,
        lastModified: Long
    ): NormalizedTxt? {
        val directory = bookDir(context, bookId)
        val content = directory.resolve(TXT_CONTENT)
        val source = directory.resolve(TXT_SOURCE)
        return runCatching {
            val root = JSONObject(source.readText(Charsets.UTF_8))
            require(root.optString("filePath") == filePath)
            require(root.optLong("fileSize") == fileSize)
            require(root.optLong("lastModified") == lastModified)
            require(content.isFile && content.length() > 0L)
            val textLength = content.readText(Charsets.UTF_8).length
            NormalizedTxt(
                textFile = content,
                chapters = root.getJSONArray("chapters").toBookChapters(textLength),
                charsetName = root.optString("charsetName", Charsets.UTF_8.name())
            )
        }.getOrNull()
    }

    fun saveNormalizedTxt(
        context: Context,
        bookId: Long,
        filePath: String,
        fileSize: Long,
        lastModified: Long,
        normalizedText: String,
        chapters: List<BookChapter>,
        charsetName: String
    ): NormalizedTxt {
        val directory = bookDir(context, bookId).apply { mkdirs() }
        val content = directory.resolve(TXT_CONTENT)
        atomicWrite(content, normalizedText)
        val chapterJson = JSONArray().apply {
            chapters.forEach { chapter ->
                put(JSONObject()
                    .put("title", chapter.title)
                    .put("startOffset", chapter.startOffset)
                    .put("endOffset", chapter.endOffset)
                    .put("sourceHref", chapter.sourceHref)
                    .put("catalogVisible", chapter.catalogVisible))
            }
        }
        atomicWrite(
            directory.resolve(TXT_SOURCE),
            JSONObject()
                .put("filePath", filePath)
                .put("fileSize", fileSize)
                .put("lastModified", lastModified)
                .put("charsetName", charsetName)
                .put("chapters", chapterJson)
                .toString()
        )
        return NormalizedTxt(content, chapters, charsetName)
    }

    fun clearBook(context: Context, bookId: Long) {
        bookDir(context, bookId).deleteRecursively()
    }

    private fun JSONArray.toBookChapters(textLength: Int): List<BookChapter> =
        (0 until length()).map { index ->
            val item = getJSONObject(index)
            BookChapter(
                title = item.optString("title", "章节 ${index + 1}"),
                startOffset = item.optInt("startOffset").coerceIn(0, textLength),
                endOffset = item.optInt("endOffset", textLength).coerceIn(0, textLength),
                sourceHref = item.optString("sourceHref").takeIf { it.isNotBlank() && it != "null" },
                catalogVisible = item.optBoolean("catalogVisible", true)
            )
        }

    private fun bookDir(context: Context, bookId: Long): File =
        context.filesDir.resolve(ROOT).resolve(bookId.toString())

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = target.parentFile.resolve(".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }
}
