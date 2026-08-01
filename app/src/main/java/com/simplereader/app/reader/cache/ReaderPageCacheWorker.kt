package com.simplereader.app.reader.cache

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simplereader.app.data.cache.StructuredBookCache
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.parser.TxtMappedRange
import com.simplereader.app.parser.TxtParser
import com.simplereader.app.ui.ReaderLayoutSignature
import com.simplereader.app.ui.ReaderPageBoundaryCalculator
import com.simplereader.app.ui.ReaderPageIndexStore
import com.simplereader.app.ui.TxtChapterIndexStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * User-triggered long-running whole-book page cache.
 *
 * Directory recognition is separated from page layout. Every chapter is laid out
 * with the same metrics as the reader, but only page start anchors are persisted;
 * page text and page views are never retained. The ordinary serialized worker resumes
 * from persisted chapter checkpoints without starting a foreground service.
 */
class ReaderPageCacheWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result = CACHE_MUTEX.withLock {
        withContext(Dispatchers.Default) {
        val bookId = ReaderPageCacheManager.bookId(inputData)
        if (bookId <= 0L) return@withContext failure("书籍编号无效")
        val signature = ReaderPageCacheManager.signature(inputData)
        val book = withContext(Dispatchers.IO) {
            SimpleReaderDatabase.getDatabase(applicationContext).bookDao().getBook(bookId)
        } ?: return@withContext failure("书籍记录不存在")

        try {
            when (book.format.uppercase()) {
                "TXT" -> cacheTxt(book, signature)
                "EPUB", "CHM" -> cacheStructured(book, signature)
                else -> failure("暂不支持缓存 ${book.format}")
            }
        } catch (error: Throwable) {
            if (isStopped) Result.retry()
            else failure(error.message ?: error.javaClass.simpleName)
        }
        }
    }

    private suspend fun cacheTxt(
        book: Book,
        signature: ReaderLayoutSignature
    ): Result {
        val uri = Uri.parse(book.filePath)
        val document = if (uri.scheme == "content") {
            DocumentFile.fromSingleUri(applicationContext, uri)
        } else null
        val sourceFile = localFile(book)
        val totalBytes = (document?.length()?.takeIf { it > 0L }
            ?: sourceFile?.length()?.takeIf { it > 0L }
            ?: book.fileSize
            ?: 0L).coerceAtLeast(0L)
        if (totalBytes <= 0L) return failure("TXT 文件为空或无法读取")
        val chapterCacheModified = document?.lastModified()?.takeIf { it > 0L }
            ?: sourceFile?.lastModified()?.takeIf { it > 0L }
            ?: book.lastModified
            ?: 0L
        val revision = sourceRevision(totalBytes, chapterCacheModified)
        val store = ReaderPageIndexStore(applicationContext, book.id)
        store.completeChapterCount(signature, revision)?.let { cachedChapters ->
            notifyComplete(book, cachedChapters)
            return Result.success(
                workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to "原文件未变化，已跳过")
            )
        }

        val charset = book.txtCharset?.takeIf(String::isNotBlank) ?: withContext(Dispatchers.IO) {
            openSource(book)?.let { TxtParser.detectCharset(it) }
        } ?: Charsets.UTF_8.name()

        val saved = TxtChapterIndexStore.load(
            context = applicationContext,
            bookId = book.id,
            totalBytes = totalBytes,
            lastModified = chapterCacheModified,
            charsetName = charset
        )
        val chapters = if (saved.isNotEmpty()) {
            saved
        } else {
            val scanned = withContext(Dispatchers.IO) {
                openSource(book)?.let { TxtParser.scanChapters(it, charset) }.orEmpty()
            }
            val stable = if (scanned.isEmpty()) {
                listOf(TxtChapterIndexStore.Entry("正文", 0L))
            } else {
                scanned.map { TxtChapterIndexStore.Entry(it.title, it.byteOffset) }
            }
            TxtChapterIndexStore.save(
                context = applicationContext,
                bookId = book.id,
                totalBytes = totalBytes,
                lastModified = chapterCacheModified,
                charsetName = charset,
                chapters = stable
            )
            stable
        }
        val starts = chapters.map { it.offset.coerceIn(0L, totalBytes) }
            .distinct()
            .sorted()
            .ifEmpty { listOf(0L) }
        val loaded = store.load(signature, revision, starts.size)
        val localStarts = loaded?.pageStartsByChapter?.toMutableMap() ?: linkedMapOf()
        val sourceStarts = loaded?.sourceStartsByChapter?.toMutableMap() ?: linkedMapOf()
        if (loaded?.complete == true && localStarts.size == starts.size) {
            notifyComplete(book, starts.size)
            return Result.success(workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to "已缓存"))
        }

        for (chapter in starts.indices) {
            coroutineContext.ensureActive()
            if (isStopped) return Result.retry()
            if (localStarts.containsKey(chapter)) {
                reportProgress(book, chapter + 1, starts.size, chapters.getOrNull(chapter)?.title.orEmpty())
                continue
            }
            val start = starts[chapter]
            val end = starts.getOrNull(chapter + 1) ?: totalBytes
            val mapped = withContext(Dispatchers.IO) {
                readMappedRange(book, charset, start, end)
            }
            val raw = mapped.text.trimEnd()
            val styled = styleChapter(raw, signature, book)
            val boundaries = ReaderPageBoundaryCalculator.calculate(
                text = styled,
                signature = signature,
                typeface = Typeface.DEFAULT,
                lineSpacingMultiplier = signature.lineSpacingMultiplierX100 / 100f,
                sourceOffsetForCharacter = { offset ->
                    mapped.sourceOffsets[offset.coerceIn(0, mapped.sourceOffsets.lastIndex)]
                }
            )
            localStarts[chapter] = boundaries.chapterOffsets
            sourceStarts[chapter] = boundaries.sourceOffsets
            if ((chapter + 1) % CHECKPOINT_CHAPTERS == 0 || chapter == starts.lastIndex) {
                withContext(Dispatchers.IO) {
                    store.save(signature, revision, starts.size, localStarts, sourceStarts)
                }
            }
            reportProgress(book, chapter + 1, starts.size, chapters.getOrNull(chapter)?.title.orEmpty())
        }
        withContext(Dispatchers.IO) {
            store.save(signature, revision, starts.size, localStarts, sourceStarts)
        }
        notifyComplete(book, starts.size)
        return Result.success(workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to "缓存完成"))
    }

    private suspend fun cacheStructured(
        book: Book,
        signature: ReaderLayoutSignature
    ): Result {
        val uri = Uri.parse(book.filePath)
        val document = if (uri.scheme == "content") {
            DocumentFile.fromSingleUri(applicationContext, uri)
        } else null
        val sourceFile = localFile(book)
        val sourceSize = (document?.length()?.takeIf { it > 0L }
            ?: sourceFile?.length()?.takeIf { it > 0L }
            ?: book.fileSize
            ?: 0L).coerceAtLeast(0L)
        val sourceModified = document?.lastModified()?.takeIf { it > 0L }
            ?: sourceFile?.lastModified()?.takeIf { it > 0L }
            ?: book.lastModified
            ?: 0L
        val revision = sourceRevision(sourceSize, sourceModified)
        val store = ReaderPageIndexStore(applicationContext, book.id)
        store.completeChapterCount(signature, revision)?.let { cachedChapters ->
            notifyComplete(book, cachedChapters)
            return Result.success(
                workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to "原文件未变化，已跳过")
            )
        }

        val cached = withContext(Dispatchers.IO) {
            StructuredBookCache.loadAny(applicationContext, book.id)
        } ?: return failure("请先打开一次本书以建立正文缓存")
        val wholeText = withContext(Dispatchers.IO) { cached.textFile.readText(Charsets.UTF_8) }
        val loaded = store.load(signature, revision, cached.chapters.size)
        val localStarts = loaded?.pageStartsByChapter?.toMutableMap() ?: linkedMapOf()
        val sourceStarts = loaded?.sourceStartsByChapter?.toMutableMap() ?: linkedMapOf()
        if (loaded?.complete == true && localStarts.size == cached.chapters.size) {
            notifyComplete(book, cached.chapters.size)
            return Result.success(workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to "已缓存"))
        }

        cached.chapters.forEachIndexed { chapter, metadata ->
            coroutineContext.ensureActive()
            if (isStopped) return Result.retry()
            if (!localStarts.containsKey(chapter)) {
                val start = metadata.startChar.coerceIn(0, wholeText.length)
                val end = metadata.endChar.coerceIn(start, wholeText.length)
                val raw = wholeText.substring(start, end).trimEnd()
                val styled = styleChapter(raw, signature, book)
                val boundaries = ReaderPageBoundaryCalculator.calculate(
                    text = styled,
                    signature = signature,
                    typeface = Typeface.DEFAULT,
                    lineSpacingMultiplier = signature.lineSpacingMultiplierX100 / 100f
                )
                localStarts[chapter] = boundaries.chapterOffsets
                if ((chapter + 1) % CHECKPOINT_CHAPTERS == 0 || chapter == cached.chapters.lastIndex) {
                    withContext(Dispatchers.IO) {
                        store.save(
                            signature,
                            revision,
                            cached.chapters.size,
                            localStarts,
                            sourceStarts
                        )
                    }
                }
            }
            reportProgress(book, chapter + 1, cached.chapters.size, metadata.title)
        }
        withContext(Dispatchers.IO) {
            store.save(signature, revision, cached.chapters.size, localStarts, sourceStarts)
        }
        notifyComplete(book, cached.chapters.size)
        return Result.success(workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to "缓存完成"))
    }

    private suspend fun reportProgress(book: Book, done: Int, total: Int, chapter: String) {
        val progress = workDataOf(
            ReaderPageCacheManager.PROGRESS_DONE to done,
            ReaderPageCacheManager.PROGRESS_TOTAL to total,
            ReaderPageCacheManager.PROGRESS_CHAPTER to chapter
        )
        setProgress(progress)
    }

    private fun sourceRevision(totalBytes: Long, lastModified: Long): String =
        listOf(
            PAGINATION_INDEX_SCHEMA_VERSION,
            totalBytes.coerceAtLeast(0L),
            lastModified.coerceAtLeast(0L)
        ).joinToString(":")

    private fun styleChapter(
        text: String,
        signature: ReaderLayoutSignature,
        book: Book
    ): CharSequence {
        if (text.isBlank()) return text
        val styled = SpannableString(text)
        if (book.format.equals("EPUB", ignoreCase = true)) {
            EPUB_IMAGE_MARKER.findAll(text).forEach { match ->
                val href = match.groupValues.getOrNull(1).orEmpty()
                val imageFile = StructuredBookCache.imageFile(applicationContext, book.id, href)
                    ?: return@forEach
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@forEach
                val maxWidth = (
                    applicationContext.resources.displayMetrics.widthPixels -
                        (56 * applicationContext.resources.displayMetrics.density).toInt()
                ).coerceAtLeast(120)
                val scale = (maxWidth.toFloat() / bitmap.width.coerceAtLeast(1)).coerceAtMost(1f)
                val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val drawable = BitmapDrawable(applicationContext.resources, bitmap).apply {
                    setBounds(0, 0, width, height)
                }
                styled.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd).trim()
            if (TxtParser.isLikelyChapterTitle(line)) {
                styled.setSpan(
                    RelativeSizeSpan(signature.chapterTitleScaleX100.coerceAtLeast(100) / 100f),
                    lineStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                styled.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            lineStart = lineEnd + 1
        }
        return styled
    }

    private fun readMappedRange(
        book: Book,
        charset: String,
        start: Long,
        end: Long
    ): TxtMappedRange {
        val length = (end - start).coerceAtLeast(0L)
        require(length <= Int.MAX_VALUE.toLong()) { "单章过大，无法缓存" }
        val seekable = runCatching {
            val uri = Uri.parse(book.filePath)
            when (uri.scheme) {
                "content" -> applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        stream.channel.position(start)
                        shiftMapped(TxtParser.readRangeMapped(stream, charset, 0L, length), start)
                    }
                }
                "file" -> FileInputStream(File(requireNotNull(uri.path))).use { stream ->
                    stream.channel.position(start)
                    shiftMapped(TxtParser.readRangeMapped(stream, charset, 0L, length), start)
                }
                else -> localFile(book)?.let { file ->
                    FileInputStream(file).use { stream ->
                        stream.channel.position(start)
                        shiftMapped(TxtParser.readRangeMapped(stream, charset, 0L, length), start)
                    }
                }
            }
        }.getOrNull()
        if (seekable != null) return seekable
        return openSource(book)?.let { stream ->
            TxtParser.readRangeMapped(stream, charset, start, end)
        } ?: error("无法读取 TXT 源文件")
    }

    private fun shiftMapped(mapped: TxtMappedRange, absoluteStart: Long): TxtMappedRange =
        TxtMappedRange(
            text = mapped.text,
            startByte = absoluteStart,
            nextByte = absoluteStart + (mapped.nextByte - mapped.startByte),
            sourceOffsets = LongArray(mapped.sourceOffsets.size) { index ->
                absoluteStart + mapped.sourceOffsets[index]
            }
        )

    private fun openSource(book: Book): InputStream? {
        val uri = Uri.parse(book.filePath)
        return when (uri.scheme) {
            "content" -> applicationContext.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
            else -> localFile(book)?.takeIf(File::isFile)?.inputStream()
        }
    }

    private fun localFile(book: Book): File? {
        val uri = Uri.parse(book.filePath)
        return when (uri.scheme) {
            null, "" -> File(book.filePath)
            "file" -> uri.path?.let(::File)
            else -> null
        }
    }

    private suspend fun notifyComplete(book: Book, chapters: Int) {
        setProgress(
            workDataOf(
                ReaderPageCacheManager.PROGRESS_DONE to chapters,
                ReaderPageCacheManager.PROGRESS_TOTAL to chapters,
                ReaderPageCacheManager.PROGRESS_CHAPTER to "${book.title} · 缓存完成"
            )
        )
    }

    private fun failure(message: String): Result =
        Result.failure(workDataOf(ReaderPageCacheManager.OUTPUT_MESSAGE to message))

    companion object {
        private val CACHE_MUTEX = Mutex()
        private const val CHECKPOINT_CHAPTERS = 8
        private const val PAGINATION_INDEX_SCHEMA_VERSION = 4
        private val EPUB_IMAGE_MARKER = Regex("\\[\\[SR_IMAGE:([^\\]]+)]]")
    }
}
