package com.simplereader.app.data.dao

import androidx.room.*
import com.simplereader.app.data.entity.Book
import com.simplereader.app.data.model.ShelfBookItem
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): Book?

    @Query("SELECT * FROM books WHERE groupId IS NULL ORDER BY lastReadTime DESC")
    fun getUngroupedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE groupId = :groupId ORDER BY lastReadTime DESC")
    fun getBooksByGroup(groupId: Long): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY lastReadTime DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query(
        """
        SELECT
            books.id AS id,
            books.title AS title,
            books.author AS author,
            books.filePath AS filePath,
            books.format AS format,
            books.groupId AS groupId,
            books.lastReadTime AS lastReadTime,
            books.addTime AS addTime,
            books.fileName AS fileName,
            books.fileStatus AS fileStatus,
            read_progress.position AS progressPosition,
            read_progress.locatorType AS locatorType,
            read_progress.txtCharOffset AS txtCharOffset,
            read_progress.txtTotalLength AS txtTotalLength,
            read_progress.epubProgressFraction AS epubProgressFraction
        FROM books
        LEFT JOIN read_progress ON books.id = read_progress.bookId
        ORDER BY books.lastReadTime DESC, books.addTime DESC
        """
    )
    fun getShelfBooks(): Flow<List<ShelfBookItem>>

    @Query(
        """
        SELECT
            books.id AS id,
            books.title AS title,
            books.author AS author,
            books.filePath AS filePath,
            books.format AS format,
            books.groupId AS groupId,
            books.lastReadTime AS lastReadTime,
            books.addTime AS addTime,
            books.fileName AS fileName,
            books.fileStatus AS fileStatus,
            read_progress.position AS progressPosition,
            read_progress.locatorType AS locatorType,
            read_progress.txtCharOffset AS txtCharOffset,
            read_progress.txtTotalLength AS txtTotalLength,
            read_progress.epubProgressFraction AS epubProgressFraction
        FROM books
        LEFT JOIN read_progress ON books.id = read_progress.bookId
        WHERE books.groupId = :groupId
        ORDER BY books.lastReadTime DESC, books.addTime DESC
        """
    )
    fun getShelfBooksByGroup(groupId: Long): Flow<List<ShelfBookItem>>

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE books SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long): Int

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): Book?

    @Query(
        """
        SELECT * FROM books
        WHERE sourceTreeUri = :sourceTreeUri
          AND COALESCE(relativePath, '') = COALESCE(:relativePath, '')
          AND LOWER(fileName) = LOWER(:fileName)
        LIMIT 1
        """
    )
    suspend fun getBySourceLocation(
        sourceTreeUri: String,
        relativePath: String?,
        fileName: String
    ): Book?

    @Query(
        """
        SELECT * FROM books
        WHERE LOWER(fileName) = LOWER(:fileName)
          AND fileSize = :fileSize
        LIMIT 1
        """
    )
    suspend fun getByFileNameAndSize(fileName: String, fileSize: Long): Book?

    @Query("UPDATE books SET lastReadTime = :lastReadTime WHERE id = :bookId")
    suspend fun updateLastReadTime(bookId: Long, lastReadTime: Long)

    @Query("UPDATE books SET fileStatus = :fileStatus WHERE id = :bookId")
    suspend fun updateFileStatus(bookId: Long, fileStatus: String)

    @Query("UPDATE books SET txtCharset = :txtCharset WHERE id = :bookId")
    suspend fun updateTxtCharset(bookId: Long, txtCharset: String)
}
