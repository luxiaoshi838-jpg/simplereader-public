package com.simplereader.app.reader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simplereader.app.data.cache.StructuredBookCache
import com.simplereader.app.data.entity.Book
import com.simplereader.app.parser.TxtParser
import com.simplereader.app.reader.page.BookChapter
import java.io.File

data class ReaderDocument(
    val text: String,
    val chapters: List<BookChapter>,
    val sourceSize: Long,
    val sourceModified: Long,
    val imageDirectory: File? = null,
    val charsetName: String? = null,
    val fromCacheOnly: Boolean = false
) {
    fun imageFile(href: String): File? {
        val directory = imageDirectory?.takeIf(File::isDirectory) ?: return null
        return directory.listFiles()?.firstOrNull { file ->
            file.isFile && (
                file.name.equals(StructuredBookCache.imageFileNameForLookup(href), ignoreCase = true) ||
                    file.nameWithoutExtension.equals(href.substringAfterLast('/').substringBeforeLast('.'), ignoreCase = true)
                )
        }
    }
}

object ReaderDocumentLoader {
    fun load(context: Context, book: Book): ReaderDocument {
        val format = book.format.uppercase()
        require(format == "TXT" || format == "EPUB") { "当前仅支持 TXT 与 EPUB" }
        val source = resolveDocument(context, book)
        return when (format) {
            "TXT" -> loadTxt(context, book, source ?: error("书籍文件不存在或权限已失效"))
            else -> loadEpub(context, book, source)
        }
    }

    fun resolveDocument(context: Context, book: Book): DocumentFile? {
        val directUri = runCatching { Uri.parse(book.filePath) }.getOrNull()
        directUri?.let { uri ->
            val direct = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            if (direct != null && runCatching { direct.exists() && direct.isFile }.getOrDefault(false)) {
                return direct
            }
        }
        val treeUri = book.sourceTreeUri?.takeIf(String::isNotBlank) ?: return null
        var current = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        }.getOrNull() ?: return null
        val rootName = current.name
        val segments = book.relativePath.orEmpty()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
            .let { path ->
                if (rootName != null && path.firstOrNull().equals(rootName, ignoreCase = true)) {
                    path.drop(1)
                } else path
            }
        segments.forEach { segment ->
            current = runCatching { current.findFile(segment) }.getOrNull() ?: return null
            if (!current.isDirectory) return null
        }
        val fileName = book.fileName.ifBlank {
            directUri?.lastPathSegment?.substringAfterLast('/') ?: book.title
        }
        return runCatching { current.findFile(fileName) }.getOrNull()?.takeIf { it.isFile && it.exists() }
    }

    private fun loadTxt(context: Context, book: Book, source: DocumentFile): ReaderDocument {
        val result = context.contentResolver.openInputStream(source.uri)?.use { input ->
            TxtParser.readText(input, book.txtCharset)
        } ?: error("无法读取 TXT 文件")
        val text = result.text.replace("\r\n", "\n").replace('\r', '\n')
        val chapters = detectTxtChapters(text)
        return ReaderDocument(
            text = text,
            chapters = chapters,
            sourceSize = source.length().takeIf { it >= 0L } ?: book.fileSize ?: text.length.toLong(),
            sourceModified = source.lastModified().takeIf { it > 0L } ?: book.lastModified ?: 0L,
            charsetName = result.charsetName
        )
    }

    private fun loadEpub(context: Context, book: Book, source: DocumentFile?): ReaderDocument {
        val cached = if (source == null) {
            StructuredBookCache.loadAny(context, book.id)
                ?: error("EPUB 原文件不存在，且没有可恢复缓存")
        } else {
            StructuredBookCache.openOrBuild(
                context = context,
                bookId = book.id,
                format = "EPUB",
                sourceSize = source.length().takeIf { it >= 0L } ?: book.fileSize ?: -1L,
                sourceModified = source.lastModified().takeIf { it > 0L } ?: book.lastModified ?: 0L,
                sourceProvider = {
                    context.contentResolver.openInputStream(source.uri)
                        ?: error("无法读取 EPUB 文件")
                }
            )
        }
        val text = cached.textFile.readText(Charsets.UTF_8)
        val chapters = cached.chapters.mapIndexed { index, chapter ->
            BookChapter(
                title = chapter.title.ifBlank { "章节 ${index + 1}" },
                startOffset = chapter.startChar.coerceIn(0, text.length),
                endOffset = chapter.endChar.coerceIn(0, text.length),
                sourceHref = chapter.source,
                catalogVisible = true
            )
        }
        return ReaderDocument(
            text = text,
            chapters = chapters,
            sourceSize = cached.sourceSize,
            sourceModified = cached.sourceModified,
            imageDirectory = cached.imageDirectory,
            fromCacheOnly = source == null
        )
    }

    private fun detectTxtChapters(text: String): List<BookChapter> {
        if (text.isBlank()) return listOf(BookChapter("正文", 0, text.length))
        val hits = mutableListOf<Pair<String, Int>>()
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val raw = text.substring(start, end).trim()
            TxtParser.extractChapterTitle(raw)?.let { title ->
                if (hits.lastOrNull()?.second?.let { start - it >= 20 } != false) {
                    hits += title to start
                }
            }
            start = end + 1
        }
        if (hits.isEmpty()) return listOf(BookChapter("正文", 0, text.length))
        val output = mutableListOf<BookChapter>()
        if (hits.first().second > 0) {
            output += BookChapter("正文", 0, hits.first().second, catalogVisible = false)
        }
        hits.forEachIndexed { index, hit ->
            val end = hits.getOrNull(index + 1)?.second ?: text.length
            output += BookChapter(hit.first, hit.second, end)
        }
        return output
    }
}
