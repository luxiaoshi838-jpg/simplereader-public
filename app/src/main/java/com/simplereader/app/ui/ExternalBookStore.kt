package com.simplereader.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.simplereader.app.data.cache.StructuredBookCache
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.reader.page.PageCacheStore
import java.io.File

object ExternalBookStore {
    const val STATUS_PENDING = "EXTERNAL_PENDING"
    const val EXTRA_EXTERNAL_PENDING = "externalPendingShelfDecision"
    private const val STALE_PENDING_MS = 24L * 60L * 60L * 1000L

    data class Prepared(val bookId: Long, val pendingDecision: Boolean)

    suspend fun prepare(context: Context, database: SimpleReaderDatabase, uri: Uri): Prepared {
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "外部书籍"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf(String::isNotBlank)?.let { displayName = it }
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
        }
        val mime = resolver.getType(uri).orEmpty()
        val format = when {
            displayName.endsWith(".txt", true) -> "TXT"
            displayName.endsWith(".epub", true) -> "EPUB"
            mime.equals("text/plain", true) -> "TXT"
            mime.equals("application/epub+zip", true) -> "EPUB"
            else -> error("简阅当前只能从其他应用打开 TXT 或 EPUB 文件")
        }
        if (!displayName.contains('.')) displayName += ".${format.lowercase()}"

        val dao = database.bookDao()
        if (size != null) {
            dao.getByFileNameAndSize(displayName, size!!)?.let { existing ->
                if (existing.fileStatus != STATUS_PENDING) return Prepared(existing.id, false)
            }
        }

        cleanupStale(context, database)
        val directory = managedDirectory(context).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120)
        val target = directory.resolve("${System.currentTimeMillis()}_${System.nanoTime()}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("无法读取外部文件")
        if (!target.isFile || target.length() <= 0L) {
            target.delete()
            error("外部文件为空或复制失败")
        }

        val book = Book(
            title = displayName.removeSuffixIgnoreCase(".txt").removeSuffixIgnoreCase(".epub"),
            filePath = target.absolutePath,
            format = format,
            groupId = null,
            fileName = displayName,
            fileSize = target.length(),
            lastModified = target.lastModified().takeIf { it > 0L },
            fileStatus = STATUS_PENDING
        )
        val id = dao.insert(book)
        if (id <= 0L) {
            target.delete()
            error("临时阅读记录创建失败")
        }
        return Prepared(id, true)
    }

    suspend fun confirm(database: SimpleReaderDatabase, bookId: Long) {
        database.bookDao().updateFileStatus(bookId, "AVAILABLE")
    }

    suspend fun discard(context: Context, database: SimpleReaderDatabase, bookId: Long) {
        val book = database.bookDao().getBook(bookId)
        database.withTransaction {
            database.bookmarkDao().deleteByBookId(bookId)
            database.readProgressDao().deleteByBookId(bookId)
            database.bookDao().deleteById(bookId)
        }
        StructuredBookCache.clearBook(context, bookId)
        PageCacheStore.clearBook(context, bookId)
        book?.takeIf { it.fileStatus == STATUS_PENDING }?.let { deleteManagedFile(context, it.filePath) }
    }

    suspend fun cleanupStale(context: Context, database: SimpleReaderDatabase) {
        val cutoff = System.currentTimeMillis() - STALE_PENDING_MS
        database.bookDao().getByFileStatus(STATUS_PENDING)
            .filter { it.addTime in 1 until cutoff }
            .forEach { discard(context, database, it.id) }
    }

    private fun managedDirectory(context: Context): File = File(context.filesDir, "external_books")

    private fun deleteManagedFile(context: Context, path: String) {
        runCatching {
            val root = managedDirectory(context).canonicalFile
            val file = File(path).canonicalFile
            if (file.path.startsWith(root.path + File.separator)) file.delete()
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
