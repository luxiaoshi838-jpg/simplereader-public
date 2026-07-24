package com.simplereader.app.data.cache

import android.content.Context
import android.util.Base64
import com.simplereader.app.parser.ChmChapter
import com.simplereader.app.parser.ChmParser
import com.simplereader.app.parser.EpubParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CachedChapter(
    val source: String,
    val title: String,
    val depth: Int,
    val startChar: Int,
    val endChar: Int
)

data class CachedCatalogEntry(
    val title: String,
    val depth: Int,
    val targetChapterIndex: Int,
    val isSection: Boolean
)

data class CachedBook(
    val bookId: Long,
    val format: String,
    val textFile: File,
    val coverFile: File?,
    val chapters: List<CachedChapter>,
    val catalog: List<CachedCatalogEntry>,
    val sourceSize: Long,
    val sourceModified: Long
)

/**
 * Persistent normalized cache for EPUB and CHM only.
 *
 * TXT is deliberately excluded because copying a large TXT would duplicate the
 * entire book. TXT remains source-streamed; only its small chapter index is
 * cached. EPUB and CHM are normalized once into readable UTF-8 `content.txt`
 * plus a chapter/catalog manifest, then included in backup and sync data.
 */
object StructuredBookCache {
    private const val CACHE_VERSION = 2
    private const val ROOT_NAME = "structured_books"
    private const val MANIFEST_NAME = "manifest.json"
    private const val CONTENT_NAME = "content.txt"
    private const val COVER_NAME = "cover.bin"
    private const val CONTENT_ENCODING = "gzip+base64+utf8"
    private val SUPPORTED_FORMATS = setOf("EPUB", "CHM")

    @Synchronized
    fun openOrBuild(
        context: Context,
        bookId: Long,
        format: String,
        sourceSize: Long,
        sourceModified: Long,
        sourceProvider: () -> InputStream
    ): CachedBook {
        val normalizedFormat = format.uppercase()
        require(normalizedFormat in SUPPORTED_FORMATS) {
            "持久正文缓存仅支持 EPUB/CHM，TXT 必须直接流式读取"
        }
        val target = cacheDirectory(context, bookId)
        loadDirectory(target)?.takeIf { cached ->
            cached.format == normalizedFormat &&
                cached.sourceSize == sourceSize &&
                cached.sourceModified == sourceModified &&
                cached.textFile.isFile &&
                cached.textFile.length() > 0L
        }?.let { return it }

        val staging = rootDirectory(context).resolve(".$bookId.${System.nanoTime()}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            when (normalizedFormat) {
                "EPUB" -> buildEpub(
                    staging = staging,
                    bookId = bookId,
                    sourceSize = sourceSize,
                    sourceModified = sourceModified,
                    sourceProvider = sourceProvider
                )
                "CHM" -> buildChm(
                    context = context,
                    staging = staging,
                    bookId = bookId,
                    sourceSize = sourceSize,
                    sourceModified = sourceModified,
                    sourceProvider = sourceProvider
                )
            }
            require(staging.resolve(CONTENT_NAME).length() > 0L) { "缓存正文为空" }
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            return loadDirectory(target) ?: error("缓存写入后无法重新读取")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun loadAny(context: Context, bookId: Long): CachedBook? =
        loadDirectory(cacheDirectory(context, bookId))

    fun coverFile(context: Context, bookId: Long): File? =
        loadAny(context, bookId)?.coverFile?.takeIf(File::isFile)

    fun clearBook(context: Context, bookId: Long) {
        cacheDirectory(context, bookId).deleteRecursively()
    }

    fun exportAll(context: Context): JSONArray {
        val output = JSONArray()
        rootDirectory(context).listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.toLongOrNull() != null }
            .sortedBy { it.name.toLongOrNull() }
            .forEach { directory ->
                val cached = loadDirectory(directory) ?: return@forEach
                if (cached.format !in SUPPORTED_FORMATS) return@forEach
                val manifestFile = directory.resolve(MANIFEST_NAME)
                val item = JSONObject()
                    .put("bookId", cached.bookId)
                    .put("contentEncoding", CONTENT_ENCODING)
                    .put("manifest", JSONObject(manifestFile.readText(Charsets.UTF_8)))
                    .put(
                        "contentData",
                        Base64.encodeToString(gzip(cached.textFile), Base64.NO_WRAP)
                    )
                cached.coverFile?.takeIf(File::isFile)?.let { cover ->
                    item.put("coverData", Base64.encodeToString(cover.readBytes(), Base64.NO_WRAP))
                }
                output.put(item)
            }
        return output
    }

    fun restoreAll(
        context: Context,
        entries: List<JSONObject>,
        bookIdMap: Map<Long, Long>
    ): Int {
        var restored = 0
        entries.forEach { item ->
            val oldBookId = item.optLong("bookId", -1L)
            val newBookId = bookIdMap[oldBookId] ?: return@forEach
            if (item.optString("contentEncoding") != CONTENT_ENCODING) return@forEach
            val manifest = item.optJSONObject("manifest") ?: return@forEach
            if (manifest.optString("format").uppercase() !in SUPPORTED_FORMATS) return@forEach
            val contentData = item.optString("contentData")
            if (contentData.isBlank()) return@forEach

            val directory = cacheDirectory(context, newBookId)
            val staging = rootDirectory(context).resolve(
                ".restore.$newBookId.${System.nanoTime()}.tmp"
            )
            runCatching {
                staging.deleteRecursively()
                staging.mkdirs()
                val restoredManifest = JSONObject(manifest.toString()).put("bookId", newBookId)
                staging.resolve(MANIFEST_NAME)
                    .writeText(restoredManifest.toString(2), Charsets.UTF_8)
                ungzipToFile(
                    Base64.decode(contentData, Base64.DEFAULT),
                    staging.resolve(CONTENT_NAME)
                )
                item.optString("coverData").takeIf(String::isNotBlank)?.let { coverData ->
                    staging.resolve(COVER_NAME)
                        .writeBytes(Base64.decode(coverData, Base64.DEFAULT))
                }
                require(loadDirectory(staging) != null) { "恢复后的缓存无效" }
                directory.deleteRecursively()
                if (!staging.renameTo(directory)) {
                    staging.copyRecursively(directory, overwrite = true)
                    staging.deleteRecursively()
                }
                restored += 1
            }.onFailure {
                staging.deleteRecursively()
            }
        }
        return restored
    }

    private fun buildEpub(
        staging: File,
        bookId: Long,
        sourceSize: Long,
        sourceModified: Long,
        sourceProvider: () -> InputStream
    ) {
        val parsed = sourceProvider().use(EpubParser::readChapters)
        require(parsed.isNotEmpty()) { "EPUB 中没有可读取的章节" }
        val contentFile = staging.resolve(CONTENT_NAME)
        val chapters = mutableListOf<CachedChapter>()
        contentFile.bufferedWriter(Charsets.UTF_8).use { rawWriter ->
            val writer = CountingWriter(rawWriter)
            parsed.forEach { chapter ->
                val start = writer.count
                writeChapter(writer, chapter.text, chapter.content)
                chapters += CachedChapter(
                    source = chapter.name,
                    title = chapter.text.ifBlank { chapter.name.substringAfterLast('/') },
                    depth = 0,
                    startChar = start,
                    endChar = writer.count
                )
            }
            writer.flush()
        }
        sourceProvider().use(EpubParser::readCoverImage)?.let { bytes ->
            if (bytes.isNotEmpty()) staging.resolve(COVER_NAME).writeBytes(bytes)
        }
        val catalog = chapters.mapIndexed { index, chapter ->
            CachedCatalogEntry(chapter.title, 0, index, false)
        }
        writeManifest(
            staging = staging,
            bookId = bookId,
            format = "EPUB",
            sourceSize = sourceSize,
            sourceModified = sourceModified,
            chapters = chapters,
            catalog = catalog,
            hasCover = staging.resolve(COVER_NAME).isFile
        )
    }

    private fun buildChm(
        context: Context,
        staging: File,
        bookId: Long,
        sourceSize: Long,
        sourceModified: Long,
        sourceProvider: () -> InputStream
    ) {
        val temporarySource = context.cacheDir.resolve(
            "simplereader_chm_${bookId}_${System.nanoTime()}.chm"
        )
        try {
            sourceProvider().use { source ->
                temporarySource.outputStream().buffered().use { output -> source.copyTo(output) }
            }
            val contentFile = staging.resolve(CONTENT_NAME)
            val chapters = mutableListOf<CachedChapter>()
            val pathToChapter = linkedMapOf<String, Int>()
            var allEntries = emptyList<ChmChapter>()
            contentFile.bufferedWriter(Charsets.UTF_8).use { rawWriter ->
                val writer = CountingWriter(rawWriter)
                allEntries = ChmParser.exportReadableChapters(temporarySource) { chapter, text ->
                    if (text.isBlank()) return@exportReadableChapters
                    val start = writer.count
                    writeChapter(writer, chapter.title, text)
                    val index = chapters.size
                    chapters += CachedChapter(
                        source = chapter.path,
                        title = chapter.title,
                        depth = chapter.depth,
                        startChar = start,
                        endChar = writer.count
                    )
                    pathToChapter[chapter.path.lowercase()] = index
                }
                writer.flush()
            }
            require(chapters.isNotEmpty()) { "CHM 中没有可读取的正文" }
            val catalog = allEntries.mapNotNull { entry ->
                val targetIndex = pathToChapter[entry.targetPath.lowercase()]
                    ?: pathToChapter[entry.path.lowercase()]
                    ?: return@mapNotNull null
                CachedCatalogEntry(
                    title = entry.title,
                    depth = entry.depth,
                    targetChapterIndex = targetIndex,
                    isSection = entry.isSection
                )
            }.ifEmpty {
                chapters.mapIndexed { index, chapter ->
                    CachedCatalogEntry(chapter.title, chapter.depth, index, false)
                }
            }
            writeManifest(
                staging = staging,
                bookId = bookId,
                format = "CHM",
                sourceSize = sourceSize,
                sourceModified = sourceModified,
                chapters = chapters,
                catalog = catalog,
                hasCover = false
            )
        } finally {
            temporarySource.delete()
        }
    }

    private fun writeChapter(output: CountingWriter, titleValue: String, textValue: String) {
        val title = titleValue.trim()
        val text = textValue.trim()
        val rendered = when {
            text.isBlank() -> title
            title.isBlank() -> text
            text.startsWith(title) -> text
            else -> "$title\n\n$text"
        }
        output.write(rendered)
        output.write("\n\n")
    }

    private fun writeManifest(
        staging: File,
        bookId: Long,
        format: String,
        sourceSize: Long,
        sourceModified: Long,
        chapters: List<CachedChapter>,
        catalog: List<CachedCatalogEntry>,
        hasCover: Boolean
    ) {
        val chapterJson = JSONArray()
        chapters.forEach { chapter ->
            chapterJson.put(
                JSONObject()
                    .put("source", chapter.source)
                    .put("title", chapter.title)
                    .put("depth", chapter.depth)
                    .put("startChar", chapter.startChar)
                    .put("endChar", chapter.endChar)
            )
        }
        val catalogJson = JSONArray()
        catalog.forEach { entry ->
            catalogJson.put(
                JSONObject()
                    .put("title", entry.title)
                    .put("depth", entry.depth)
                    .put("targetChapterIndex", entry.targetChapterIndex)
                    .put("isSection", entry.isSection)
            )
        }
        val manifest = JSONObject()
            .put("cacheVersion", CACHE_VERSION)
            .put("bookId", bookId)
            .put("format", format)
            .put("sourceSize", sourceSize)
            .put("sourceModified", sourceModified)
            .put("contentFile", CONTENT_NAME)
            .put("coverFile", if (hasCover) COVER_NAME else JSONObject.NULL)
            .put("chapters", chapterJson)
            .put("catalog", catalogJson)
        staging.resolve(MANIFEST_NAME).writeText(manifest.toString(2), Charsets.UTF_8)
    }

    private fun loadDirectory(directory: File): CachedBook? {
        return runCatching {
            val manifestFile = directory.resolve(MANIFEST_NAME)
            val contentFile = directory.resolve(CONTENT_NAME)
            if (!manifestFile.isFile || !contentFile.isFile || contentFile.length() <= 0L) return null
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (json.optInt("cacheVersion", -1) != CACHE_VERSION) return null
            val format = json.optString("format").uppercase()
            if (format !in SUPPORTED_FORMATS) return null
            val chapters = json.optJSONArray("chapters").toObjects().map { item ->
                CachedChapter(
                    source = item.optString("source"),
                    title = item.optString("title"),
                    depth = item.optInt("depth", 0),
                    startChar = item.optInt("startChar", 0),
                    endChar = item.optInt("endChar", 0)
                )
            }
            val catalog = json.optJSONArray("catalog").toObjects().map { item ->
                CachedCatalogEntry(
                    title = item.optString("title"),
                    depth = item.optInt("depth", 0),
                    targetChapterIndex = item.optInt("targetChapterIndex", 0),
                    isSection = item.optBoolean("isSection", false)
                )
            }
            CachedBook(
                bookId = json.optLong("bookId"),
                format = format,
                textFile = contentFile,
                coverFile = if (json.isNull("coverFile")) {
                    null
                } else {
                    json.optString("coverFile")
                        .takeIf(String::isNotBlank)
                        ?.let(directory::resolve)
                        ?.takeIf(File::isFile)
                },
                chapters = chapters,
                catalog = catalog,
                sourceSize = json.optLong("sourceSize", -1L),
                sourceModified = json.optLong("sourceModified", 0L)
            )
        }.getOrNull()
    }

    private fun JSONArray?.toObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun rootDirectory(context: Context): File =
        context.filesDir.resolve(ROOT_NAME).apply { mkdirs() }

    private fun cacheDirectory(context: Context, bookId: Long): File =
        rootDirectory(context).resolve(bookId.toString())

    private fun gzip(file: File): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            file.inputStream().buffered().use { input -> input.copyTo(gzip) }
        }
        return bytes.toByteArray()
    }

    private fun ungzipToFile(compressed: ByteArray, output: File) {
        output.parentFile?.mkdirs()
        GZIPInputStream(compressed.inputStream()).use { input ->
            output.outputStream().buffered().use { target -> input.copyTo(target) }
        }
    }

    private class CountingWriter(private val delegate: Writer) : Writer() {
        var count: Int = 0
            private set

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
