package com.simplereader.app.data.repository

import com.simplereader.app.data.dao.BookDao
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.model.ShelfBookItem
import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {
    fun getUngroupedBooks(): Flow<List<Book>> = bookDao.getUngroupedBooks()
    fun getBooksByGroup(groupId: Long): Flow<List<Book>> = bookDao.getBooksByGroup(groupId)
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks()
    fun getShelfBooks(): Flow<List<ShelfBookItem>> = bookDao.getShelfBooks()
    fun getShelfBooksByGroup(groupId: Long): Flow<List<ShelfBookItem>> = bookDao.getShelfBooksByGroup(groupId)

    suspend fun getBook(id: Long): Book? = bookDao.getBook(id)
    suspend fun getByFilePath(filePath: String): Book? = bookDao.getByFilePath(filePath)
    suspend fun getBySourceLocation(
        sourceTreeUri: String,
        relativePath: String?,
        fileName: String
    ): Book? = bookDao.getBySourceLocation(sourceTreeUri, relativePath, fileName)

    suspend fun getByFileNameAndSize(fileName: String, fileSize: Long): Book? =
        bookDao.getByFileNameAndSize(fileName, fileSize)

    suspend fun findDuplicate(
        filePath: String,
        sourceTreeUri: String?,
        relativePath: String?,
        fileName: String,
        fileSize: Long?
    ): Book? {
        getByFilePath(filePath)?.let { return it }
        if (!sourceTreeUri.isNullOrBlank()) {
            getBySourceLocation(sourceTreeUri, relativePath, fileName)?.let { return it }
        }
        if (fileSize != null && fileSize >= 0L && fileName.isNotBlank()) {
            getByFileNameAndSize(fileName, fileSize)?.let { return it }
        }
        return null
    }

    suspend fun insert(book: Book): Long = bookDao.insert(book)
    suspend fun insertOrGetExistingId(book: Book): Long {
        val insertedId = bookDao.insert(book)
        return if (insertedId != -1L) {
            insertedId
        } else {
            bookDao.getByFilePath(book.filePath)?.id
                ?: error("Book insert conflicted but existing row was not found: ${book.filePath}")
        }
    }
    suspend fun update(book: Book) = bookDao.update(book)
    suspend fun delete(book: Book) = bookDao.delete(book)
    suspend fun updateLastReadTime(bookId: Long, lastReadTime: Long) = bookDao.updateLastReadTime(bookId, lastReadTime)
    suspend fun updateFileStatus(bookId: Long, fileStatus: String) = bookDao.updateFileStatus(bookId, fileStatus)
    suspend fun updateTxtCharset(bookId: Long, txtCharset: String) = bookDao.updateTxtCharset(bookId, txtCharset)
}
