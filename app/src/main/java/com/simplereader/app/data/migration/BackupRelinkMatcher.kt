package com.simplereader.app.data.migration

import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.model.ImportCandidate

/** Matches restored bookshelf rows to files selected from the user's original library folder. */
object BackupRelinkMatcher {
    data class Match(
        val book: Book,
        val candidate: ImportCandidate
    )

    fun match(
        restoredBooks: List<Book>,
        candidates: List<ImportCandidate>
    ): List<Match> {
        val unusedBooks = restoredBooks.associateBy { it.id }.toMutableMap()
        val matches = mutableListOf<Match>()

        candidates.forEach { candidate ->
            val selected = selectUniqueMatch(unusedBooks.values.toList(), candidate) ?: return@forEach
            matches += Match(selected, candidate)
            unusedBooks.remove(selected.id)
        }
        return matches
    }

    private fun selectUniqueMatch(
        books: List<Book>,
        candidate: ImportCandidate
    ): Book? {
        val sameNameAndFormat = books.filter { book ->
            book.fileName.equals(candidate.displayName, ignoreCase = true) &&
                book.format.equals(candidate.format, ignoreCase = true)
        }
        if (sameNameAndFormat.isEmpty()) return null

        uniqueOrNull(sameNameAndFormat.filter { book ->
            pathsEquivalent(book.relativePath, candidate.relativeParentPath)
        })?.let { return it }

        uniqueOrNull(sameNameAndFormat.filter { book ->
            candidate.size != null && book.fileSize == candidate.size
        })?.let { return it }

        val candidateTitle = candidate.displayName
            .removeSuffixIgnoreCase(".txt")
            .removeSuffixIgnoreCase(".epub")
            .removeSuffixIgnoreCase(".chm")
        return uniqueOrNull(sameNameAndFormat.filter { book ->
            book.title.equals(candidateTitle, ignoreCase = true)
        })
    }

    private fun pathsEquivalent(oldPath: String?, newPath: String?): Boolean {
        val oldValue = normalizePath(oldPath) ?: return false
        val newValue = normalizePath(newPath) ?: return false
        return oldValue == newValue ||
            oldValue.endsWith("/$newValue") ||
            newValue.endsWith("/$oldValue")
    }

    private fun normalizePath(value: String?): String? = value
        ?.replace('\\', '/')
        ?.trim('/')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    private fun <T> uniqueOrNull(values: List<T>): T? =
        if (values.size == 1) values.single() else null

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
