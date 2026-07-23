package com.simplereader.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simplereader.app.data.backup.BackupFileMatcher.BackupBookIdentity
import com.simplereader.app.data.backup.LocalLibraryScanner.LocalBookFile
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.model.PermissionStatus
import org.json.JSONObject

/**
 * Restores a fully decoded schema-v1 backup. If a freshly scanned library is
 * supplied, books are relinked to those files; otherwise the original backup
 * locations are preserved so groups, bookmarks, and progress remain usable.
 */
class SimpleReaderBackupRestorer(
    context: Context,
    private val database: SimpleReaderDatabase
) {
    private val contentResolver = context.contentResolver

    data class RestoreSummary(
        val scannedFiles: Int,
        val insertedGroups: Int,
        val insertedBooks: Int,
        val mergedBooks: Int,
        val linkedBooks: Int,
        val missingBooks: Int,
        val restoredBookmarks: Int,
        val restoredProgress: Int
    ) {
        fun message(): String = buildString {
            append("恢复完成：书籍 ${insertedBooks + mergedBooks} 本、分组 $insertedGroups 个、")
            append("书签 $restoredBookmarks 条、阅读进度 $restoredProgress 条。")
            append("\n扫描原书文件 $scannedFiles 个，成功关联 $linkedBooks 本。")
            if (missingBooks > 0) {
                append("\n有 $missingBooks 本暂时不可直接读取；原始位置、分组、书签和进度仍已保留。")
            } else {
                append("\n所有恢复书籍均已关联，可直接阅读。")
            }
        }
    }

    suspend fun restore(
        backup: SimpleReaderBackupDecoder.DecodedBackup,
        scannedFiles: List<LocalBookFile>
    ): RestoreSummary {
        var insertedGroups = 0
        var insertedBooks = 0
        var mergedBooks = 0
        var linkedBooks = 0
        var missingBooks = 0
        var restoredBookmarks = 0
        var restoredProgress = 0

        database.withTransaction {
            val db = database.openHelper.writableDatabase
            val groupIdMap = mutableMapOf<Long, Long>()
            val bookIdMap = mutableMapOf<Long, Long>()
            val unusedFiles = scannedFiles.associateBy { it.uri }.toMutableMap()

            backup.groups.sortedBy { it.optInt("sortOrder", 0) }.forEach { row ->
                val oldId = row.optLong("id", -1L)
                val name = row.stringOrNull("name") ?: "未命名分组"
                val displayName = row.stringOrNull("displayName") ?: name

                var resolvedId = queryLong(
                    db,
                    "SELECT id FROM book_groups WHERE LOWER(displayName) = LOWER(?) OR LOWER(name) = LOWER(?) LIMIT 1",
                    arrayOf(displayName, name)
                )

                val originalSourceKey = row.stringOrNull("sourceKey")
                if (resolvedId == null && !originalSourceKey.isNullOrBlank()) {
                    resolvedId = queryLong(
                        db,
                        "SELECT id FROM book_groups WHERE sourceKey = ? LIMIT 1",
                        arrayOf(originalSourceKey)
                    )
                }

                if (resolvedId == null) {
                    val sourceKey = uniqueGroupSourceKey(db, originalSourceKey, oldId, displayName)
                    db.execSQL(
                        """
                        INSERT INTO book_groups
                        (name, createTime, displayName, sourceTreeUri, relativePath, sourceKey, sortOrder, isExpanded)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            name,
                            row.optLong("createTime", System.currentTimeMillis()),
                            displayName,
                            row.stringOrNull("sourceTreeUri"),
                            row.stringOrNull("relativePath"),
                            sourceKey,
                            row.optInt("sortOrder", 0),
                            if (row.optBoolean("isExpanded", true)) 1 else 0
                        )
                    )
                    resolvedId = queryLong(
                        db,
                        "SELECT id FROM book_groups WHERE sourceKey = ? LIMIT 1",
                        arrayOf(sourceKey)
                    )
                    if (resolvedId != null) insertedGroups++
                }

                if (oldId >= 0L && resolvedId != null) {
                    groupIdMap[oldId] = resolvedId
                }
            }

            backup.books.forEach { row ->
                val oldBookId = row.optLong("id", -1L)
                val originalFilePath = row.stringOrNull("filePath")
                val title = row.stringOrNull("title") ?: "未命名书籍"
                val format = (row.stringOrNull("format") ?: formatFromPath(originalFilePath) ?: "TXT")
                    .uppercase()
                val fileName = row.stringOrNull("fileName")
                    ?: fileNameFromPath(originalFilePath)
                    ?: title
                val fileSize = row.longOrNull("fileSize")
                val oldRelativePath = row.stringOrNull("relativePath")
                val identity = BackupBookIdentity(
                    oldId = oldBookId,
                    title = title,
                    fileName = fileName,
                    format = format,
                    fileSize = fileSize,
                    relativePath = oldRelativePath
                )

                val scanned = BackupFileMatcher.selectUnique(identity, unusedFiles.values)
                if (scanned != null) unusedFiles.remove(scanned.uri)

                val readableOriginal = scanned == null && canReadUri(originalFilePath)
                val location = when {
                    scanned != null -> BookLocation(
                        filePath = scanned.uri,
                        fileName = scanned.displayName,
                        format = scanned.format,
                        fileSize = scanned.size,
                        lastModified = scanned.lastModified,
                        sourceTreeUri = scanned.sourceTreeUri,
                        relativePath = scanned.relativeParentPath,
                        fileStatus = statusFor(scanned.permissionStatus)
                    )
                    readableOriginal -> BookLocation(
                        filePath = requireNotNull(originalFilePath),
                        fileName = fileName,
                        format = format,
                        fileSize = fileSize,
                        lastModified = row.longOrNull("lastModified"),
                        sourceTreeUri = row.stringOrNull("sourceTreeUri"),
                        relativePath = oldRelativePath,
                        fileStatus = "AVAILABLE"
                    )
                    !originalFilePath.isNullOrBlank() -> BookLocation(
                        filePath = originalFilePath,
                        fileName = fileName,
                        format = format,
                        fileSize = fileSize,
                        lastModified = row.longOrNull("lastModified"),
                        sourceTreeUri = row.stringOrNull("sourceTreeUri"),
                        relativePath = oldRelativePath,
                        fileStatus = "PERMISSION_REVOKED"
                    )
                    else -> BookLocation(
                        filePath = "backup://missing/$oldBookId/${Uri.encode(fileName)}",
                        fileName = fileName,
                        format = format,
                        fileSize = fileSize,
                        lastModified = row.longOrNull("lastModified"),
                        sourceTreeUri = null,
                        relativePath = oldRelativePath,
                        fileStatus = "MISSING"
                    )
                }

                val resolvedGroupId = row.longOrNull("groupId")?.let(groupIdMap::get)
                var resolvedBookId = findExistingBookId(
                    db = db,
                    location = location,
                    title = title
                )

                if (resolvedBookId == null) {
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO books
                        (title, author, filePath, format, groupId, lastReadTime, addTime, cover,
                         fileName, fileSize, lastModified, sourceTreeUri, relativePath, fileStatus, txtCharset)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            title,
                            row.stringOrNull("author") ?: "",
                            location.filePath,
                            location.format,
                            resolvedGroupId,
                            row.optLong("lastReadTime", 0L),
                            row.optLong("addTime", System.currentTimeMillis()),
                            row.stringOrNull("cover"),
                            location.fileName,
                            location.fileSize,
                            location.lastModified,
                            location.sourceTreeUri,
                            location.relativePath,
                            location.fileStatus,
                            row.stringOrNull("txtCharset")
                        )
                    )
                    resolvedBookId = findExistingBookId(db, location, title)
                    if (resolvedBookId != null) insertedBooks++
                } else {
                    db.execSQL(
                        """
                        UPDATE books SET
                            title = ?,
                            author = CASE WHEN ? = '' THEN author ELSE ? END,
                            filePath = ?,
                            format = ?,
                            groupId = CASE WHEN ? IS NULL THEN groupId ELSE ? END,
                            lastReadTime = MAX(lastReadTime, ?),
                            cover = COALESCE(?, cover),
                            fileName = ?,
                            fileSize = ?,
                            lastModified = ?,
                            sourceTreeUri = ?,
                            relativePath = ?,
                            fileStatus = ?,
                            txtCharset = COALESCE(txtCharset, ?)
                        WHERE id = ?
                        """.trimIndent(),
                        arrayOf(
                            title,
                            row.stringOrNull("author") ?: "",
                            row.stringOrNull("author") ?: "",
                            location.filePath,
                            location.format,
                            resolvedGroupId,
                            resolvedGroupId,
                            row.optLong("lastReadTime", 0L),
                            row.stringOrNull("cover"),
                            location.fileName,
                            location.fileSize,
                            location.lastModified,
                            location.sourceTreeUri,
                            location.relativePath,
                            location.fileStatus,
                            row.stringOrNull("txtCharset"),
                            resolvedBookId
                        )
                    )
                    mergedBooks++
                }

                if (scanned != null || readableOriginal) linkedBooks++ else missingBooks++
                if (oldBookId >= 0L && resolvedBookId != null) {
                    bookIdMap[oldBookId] = resolvedBookId
                }
            }

            backup.progress.forEach { row ->
                val mappedBookId = bookIdMap[row.optLong("bookId", -1L)] ?: return@forEach
                val backupUpdateTime = row.optLong("updateTime", 0L)
                val currentUpdateTime = queryLong(
                    db,
                    "SELECT updateTime FROM read_progress WHERE bookId = ? LIMIT 1",
                    arrayOf(mappedBookId)
                )
                if (currentUpdateTime == null || backupUpdateTime >= currentUpdateTime) {
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO read_progress
                        (bookId, position, locatorType, txtCharOffset, txtTotalLength, epubSpineIndex,
                         epubChapterHref, epubChapterOffset, epubProgressFraction, updateTime)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            mappedBookId,
                            row.stringOrNull("position") ?: "0",
                            row.stringOrNull("locatorType") ?: "TXT",
                            row.longOrNull("txtCharOffset"),
                            row.longOrNull("txtTotalLength"),
                            row.longOrNull("epubSpineIndex"),
                            row.stringOrNull("epubChapterHref"),
                            row.longOrNull("epubChapterOffset"),
                            row.doubleOrNull("epubProgressFraction"),
                            backupUpdateTime
                        )
                    )
                    restoredProgress++
                }
            }

            backup.bookmarks.forEach { row ->
                val mappedBookId = bookIdMap[row.optLong("bookId", -1L)] ?: return@forEach
                val position = row.stringOrNull("position") ?: "0"
                val content = row.stringOrNull("content") ?: ""
                val existingBookmark = queryLong(
                    db,
                    "SELECT id FROM bookmarks WHERE bookId = ? AND position = ? AND content = ? LIMIT 1",
                    arrayOf(mappedBookId, position, content)
                )
                if (existingBookmark == null) {
                    db.execSQL(
                        "INSERT INTO bookmarks (bookId, position, content, createTime) VALUES (?, ?, ?, ?)",
                        arrayOf(
                            mappedBookId,
                            position,
                            content,
                            row.optLong("createTime", System.currentTimeMillis())
                        )
                    )
                    restoredBookmarks++
                }
            }
        }

        return RestoreSummary(
            scannedFiles = scannedFiles.size,
            insertedGroups = insertedGroups,
            insertedBooks = insertedBooks,
            mergedBooks = mergedBooks,
            linkedBooks = linkedBooks,
            missingBooks = missingBooks,
            restoredBookmarks = restoredBookmarks,
            restoredProgress = restoredProgress
        )
    }

    private data class BookLocation(
        val filePath: String,
        val fileName: String,
        val format: String,
        val fileSize: Long?,
        val lastModified: Long?,
        val sourceTreeUri: String?,
        val relativePath: String?,
        val fileStatus: String
    )

    private fun findExistingBookId(
        db: SupportSQLiteDatabase,
        location: BookLocation,
        title: String
    ): Long? {
        queryLong(db, "SELECT id FROM books WHERE filePath = ? LIMIT 1", arrayOf(location.filePath))
            ?.let { return it }

        if (!location.sourceTreeUri.isNullOrBlank()) {
            queryLong(
                db,
                """
                SELECT id FROM books
                WHERE sourceTreeUri = ?
                  AND COALESCE(relativePath, '') = COALESCE(?, '')
                  AND LOWER(fileName) = LOWER(?)
                LIMIT 1
                """.trimIndent(),
                arrayOf(location.sourceTreeUri, location.relativePath, location.fileName)
            )?.let { return it }
        }

        if (location.fileSize != null) {
            queryLong(
                db,
                "SELECT id FROM books WHERE LOWER(fileName) = LOWER(?) AND fileSize = ? LIMIT 1",
                arrayOf(location.fileName, location.fileSize)
            )?.let { return it }
        }

        return queryLong(
            db,
            """
            SELECT id FROM books
            WHERE LOWER(title) = LOWER(?)
              AND UPPER(format) = UPPER(?)
              AND COALESCE(relativePath, '') = COALESCE(?, '')
            LIMIT 1
            """.trimIndent(),
            arrayOf(title, location.format, location.relativePath)
        )
    }

    private fun uniqueGroupSourceKey(
        db: SupportSQLiteDatabase,
        original: String?,
        oldId: Long,
        displayName: String
    ): String {
        val base = original?.takeIf { it.isNotBlank() }
            ?: "backup:$oldId:${displayName.lowercase()}"
        if (queryLong(db, "SELECT id FROM book_groups WHERE sourceKey = ? LIMIT 1", arrayOf(base)) == null) {
            return base
        }
        var suffix = 1
        while (true) {
            val candidate = "$base:$suffix"
            if (queryLong(db, "SELECT id FROM book_groups WHERE sourceKey = ? LIMIT 1", arrayOf(candidate)) == null) {
                return candidate
            }
            suffix++
        }
    }

    private fun canReadUri(value: String?): Boolean {
        val uri = value?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return false
        if (uri.scheme != "content" && uri.scheme != "file") return false
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun statusFor(permissionStatus: PermissionStatus): String = when (permissionStatus) {
        PermissionStatus.PERSISTED -> "AVAILABLE"
        PermissionStatus.SESSION_ONLY -> "SESSION_ONLY"
        PermissionStatus.MISSING -> "MISSING"
        PermissionStatus.PERMISSION_REVOKED -> "PERMISSION_REVOKED"
    }

    private fun fileNameFromPath(path: String?): String? = path
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
        ?.let(Uri::decode)
        ?.takeIf { it.isNotBlank() }

    private fun formatFromPath(path: String?): String? = when {
        path?.endsWith(".txt", ignoreCase = true) == true -> "TXT"
        path?.endsWith(".epub", ignoreCase = true) == true -> "EPUB"
        path?.endsWith(".chm", ignoreCase = true) == true -> "CHM"
        else -> null
    }

    private fun queryLong(
        db: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?>
    ): Long? {
        db.query(sql, args).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.longOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getLong(key) }.getOrNull()
    }

    private fun JSONObject.doubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getDouble(key) }.getOrNull()
    }
}
