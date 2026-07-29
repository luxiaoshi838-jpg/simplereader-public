package com.simplereader.app.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simplereader.app.data.entity.Book
import java.io.File

object BookFileActions {
    fun titleFromFileName(fileName: String): String {
        return fileName
            .removeSuffixIgnoreCase(".txt")
            .removeSuffixIgnoreCase(".epub")
            .removeSuffixIgnoreCase(".chm")
    }

    fun fileNameForTitle(title: String, oldFileName: String): String {
        val trimmed = title.trim()
        val oldExtension = oldFileName.substringAfterLast('.', missingDelimiterValue = "")
        val hasExtension = trimmed.substringAfterLast('.', missingDelimiterValue = "").let { extension ->
            extension.equals("txt", true) || extension.equals("epub", true) || extension.equals("chm", true)
        }
        return if (hasExtension || oldExtension.isBlank()) trimmed else "$trimmed.$oldExtension"
    }

    suspend fun renameBookFile(context: Context, book: Book, newTitle: String): Book {
        val cleanedTitle = newTitle.trim()
        require(cleanedTitle.isNotBlank()) { "书名不能为空" }

        val oldFileName = book.fileName.ifBlank {
            runCatching { Uri.parse(book.filePath).lastPathSegment }.getOrNull()
        }.orEmpty().ifBlank { "${book.title}.${book.format.lowercase()}" }
        val newFileName = fileNameForTitle(cleanedTitle, oldFileName)
        val updatedTitle = titleFromFileName(newFileName)

        val uri = runCatching { Uri.parse(book.filePath) }.getOrNull()
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            val document = DocumentFile.fromSingleUri(context, uri ?: error("无法访问原书籍文件"))
                ?: error("无法访问原书籍文件")
            check(document.renameTo(newFileName)) { "本地文件重命名失败" }
            return book.copy(
                title = updatedTitle,
                fileName = newFileName,
                filePath = document.uri.toString(),
                lastModified = document.lastModified().takeIf { it > 0L } ?: book.lastModified,
                fileSize = document.length().takeIf { it >= 0L } ?: book.fileSize,
                fileStatus = "AVAILABLE"
            )
        }

        val file = when {
            uri?.scheme.equals("file", ignoreCase = true) -> File(uri!!.path.orEmpty())
            else -> File(book.filePath)
        }
        check(file.exists()) { "无法访问原书籍文件" }
        val target = File(file.parentFile ?: error("无法识别原文件夹"), newFileName)
        check(!target.exists() || target.absolutePath == file.absolutePath) { "同名文件已存在" }
        check(file.renameTo(target)) { "本地文件重命名失败" }
        return book.copy(
            title = updatedTitle,
            fileName = newFileName,
            filePath = target.absolutePath,
            lastModified = target.lastModified().takeIf { it > 0L } ?: book.lastModified,
            fileSize = target.length().takeIf { it >= 0L } ?: book.fileSize,
            fileStatus = "AVAILABLE"
        )
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String {
        return if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
    }
}
