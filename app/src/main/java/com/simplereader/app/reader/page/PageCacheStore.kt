package com.simplereader.app.reader.page

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object PageCacheStore {
    private const val CACHE_VERSION = 3
    private const val MIN_COMPATIBLE_CACHE_VERSION = 2
    private const val ROOT = "reader_page_cache"
    private const val MANIFEST = "pages.json"
    private const val TXT_CONTENT = "content.txt"
    private const val TXT_SOURCE = "txt-source.json"
    private const val EXPORT_ENCODING = "gzip+base64+utf8"

    data class CacheIdentity(
        val bookId: Long,
        val filePath: String,
        val fileSize: Long,
        val lastModified: Long,
        val settingsHash: String,
        val textFingerprint: String
    )

    data class NormalizedTxt(
        val textFile: File,
        val chapters: List<BookChapter>,
        val charsetName: String
    )

    fun textFingerprint(text: String): String {
        val sampleSize = 8_192
        val head = text.take(sampleSize)
        val tail = if (text.length > sampleSize) text.takeLast(sampleSize) else ""
        val raw = "${text.length}|${text.hashCode()}|$head|$tail"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun loadPages(context: Context, identity: CacheIdentity, text: String): ReaderBook? {
        val file = bookDir(context, identity.bookId).resolve(MANIFEST)
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val version = root.optInt("cacheVersion")
            require(version in MIN_COMPATIBLE_CACHE_VERSION..CACHE_VERSION)
            require(root.optLong("bookId") == identity.bookId)
            require(root.optString("readerSettingsHash") == identity.settingsHash)

            val storedFingerprint = root.optString("textFingerprint")
            if (storedFingerprint.isNotBlank()) {
                require(storedFingerprint == identity.textFingerprint)
            } else {
                require(root.optString("filePath") == identity.filePath)
                require(root.optLong("fileSize") == identity.fileSize)
                require(root.optLong("lastModified") == identity.lastModified)
            }

            val chapters = root.getJSONArray("chapters").toBookChapters(text.length)
            require(chapters.isNotEmpty())
            val rawPages = root.getJSONArray("pages")
            val total = rawPages.length()
            require(total > 0)
            val pages = (0 until total).map { index ->
                val item = rawPages.getJSONObject(index)
                val chapterIndex = item.getInt("chapterIndex")
                require(chapterIndex in chapters.indices)
                ReaderPage(
                    globalPageIndex = index,
                    totalPageCount = total,
                    chapterIndex = chapterIndex,
                    pageIndexInChapter = item.getInt("pageIndexInChapter"),
                    chapterPageCount = item.getInt("chapterPageCount"),
                    startOffset = item.getInt("startOffset").coerceIn(0, text.length),
                    endOffset = item.getInt("endOffset").coerceIn(0, text.length)
                )
            }
            require(pages.all { it.startOffset <= it.endOffset })
            require(pages.zipWithNext().all { (a, b) -> a.endOffset <= b.endOffset })
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
            .put("textFingerprint", identity.textFingerprint)
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
            val storedSize = root.optLong("fileSize", -1L)
            if (storedSize > 0L && fileSize > 0L) require(storedSize == fileSize)
            require(content.isFile && content.length() > 0L)
            val normalizedText = content.readText(Charsets.UTF_8)
            val storedFingerprint = root.optString("textFingerprint")
            if (storedFingerprint.isNotBlank()) {
                require(storedFingerprint == textFingerprint(normalizedText))
            } else {
                require(root.optString("filePath") == filePath)
                if (root.optLong("lastModified") > 0L && lastModified > 0L) {
                    require(root.optLong("lastModified") == lastModified)
                }
            }
            NormalizedTxt(
                textFile = content,
                chapters = root.getJSONArray("chapters").toBookChapters(normalizedText.length),
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
                .put("textFingerprint", textFingerprint(normalizedText))
                .put("chapters", chapterJson)
                .toString()
        )
        return NormalizedTxt(content, chapters, charsetName)
    }

    /** Export compact local TXT/chapter and layout page caches for backup/sync. */
    fun exportAll(context: Context): JSONArray {
        val output = JSONArray()
        rootDirectory(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.toLongOrNull() != null }
            .sortedBy { it.name.toLongOrNull() }
            .forEach { directory ->
                val bookId = directory.name.toLongOrNull() ?: return@forEach
                val item = JSONObject().put("bookId", bookId)
                directory.resolve(MANIFEST).takeIf(File::isFile)?.let { manifest ->
                    item.put("pageManifestEncoding", EXPORT_ENCODING)
                    item.put("pageManifestData", encodeCompressed(manifest.readText(Charsets.UTF_8)))
                }
                val txtSource = directory.resolve(TXT_SOURCE)
                val txtContent = directory.resolve(TXT_CONTENT)
                if (txtSource.isFile && txtContent.isFile && txtContent.length() > 0L) {
                    item.put("txtSourceEncoding", EXPORT_ENCODING)
                    item.put("txtSourceData", encodeCompressed(txtSource.readText(Charsets.UTF_8)))
                    item.put("txtContentEncoding", EXPORT_ENCODING)
                    item.put("txtContentData", encodeCompressed(txtContent.readText(Charsets.UTF_8)))
                }
                if (item.length() > 1) output.put(item)
            }
        return output
    }

    /** Restore caches after old book IDs have been mapped to the current database IDs. */
    fun restoreAll(
        context: Context,
        entries: List<JSONObject>,
        bookIdMap: Map<Long, Long>
    ): Int {
        var restored = 0
        entries.forEach { item ->
            val oldBookId = item.optLong("bookId", -1L)
            val newBookId = bookIdMap[oldBookId] ?: return@forEach
            val directory = bookDir(context, newBookId).apply { mkdirs() }
            var restoredAny = false

            runCatching {
                if (item.optString("pageManifestEncoding") == EXPORT_ENCODING) {
                    val encoded = item.optString("pageManifestData")
                    if (encoded.isNotBlank()) {
                        val manifest = JSONObject(decodeCompressed(encoded))
                            .put("bookId", newBookId)
                        atomicWrite(directory.resolve(MANIFEST), manifest.toString())
                        restoredAny = true
                    }
                }

                if (item.optString("txtSourceEncoding") == EXPORT_ENCODING &&
                    item.optString("txtContentEncoding") == EXPORT_ENCODING
                ) {
                    val sourceData = item.optString("txtSourceData")
                    val contentData = item.optString("txtContentData")
                    if (sourceData.isNotBlank() && contentData.isNotBlank()) {
                        val sourceJson = JSONObject(decodeCompressed(sourceData))
                        val contentText = decodeCompressed(contentData)
                        val fingerprint = sourceJson.optString("textFingerprint")
                        if (fingerprint.isNotBlank()) {
                            require(fingerprint == textFingerprint(contentText))
                        }
                        atomicWrite(directory.resolve(TXT_CONTENT), contentText)
                        atomicWrite(directory.resolve(TXT_SOURCE), sourceJson.toString())
                        restoredAny = true
                    }
                }
            }.onFailure {
                // One damaged cache entry must not abort restoration of books/progress/bookmarks.
            }

            if (restoredAny) restored += 1
        }
        return restored
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

    private fun rootDirectory(context: Context): File = context.filesDir.resolve(ROOT)

    private fun bookDir(context: Context, bookId: Long): File =
        rootDirectory(context).resolve(bookId.toString())

    private fun encodeCompressed(text: String): String {
        val bytes = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(text.toByteArray(Charsets.UTF_8))
            }
            output.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeCompressed(encoded: String): String {
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

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
