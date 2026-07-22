package com.simplereader.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simplereader.app.data.dao.*
import com.simplereader.app.data.entity.*

@Database(
    entities = [
        Book::class,
        BookGroup::class,
        Bookmark::class,
        ReadProgress::class
    ],
    version = 2,
    exportSchema = true
)
abstract class SimpleReaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readProgressDao(): ReadProgressDao

    companion object {
        @Volatile
        private var INSTANCE: SimpleReaderDatabase? = null

        fun getDatabase(context: Context): SimpleReaderDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SimpleReaderDatabase::class.java,
                    "simple_reader_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TEMP TABLE book_id_map AS
                    SELECT
                        b.id AS oldId,
                        kept.keepId AS keepId
                    FROM books b
                    INNER JOIN (
                        SELECT filePath, MIN(id) AS keepId
                        FROM books
                        GROUP BY filePath
                    ) kept ON kept.filePath = b.filePath
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE books_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        format TEXT NOT NULL,
                        groupId INTEGER,
                        lastReadTime INTEGER NOT NULL,
                        addTime INTEGER NOT NULL,
                        cover TEXT,
                        fileName TEXT NOT NULL DEFAULT '',
                        fileSize INTEGER,
                        lastModified INTEGER,
                        sourceTreeUri TEXT,
                        relativePath TEXT,
                        fileStatus TEXT NOT NULL DEFAULT 'AVAILABLE',
                        txtCharset TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO books_new (
                        id, title, author, filePath, format, groupId,
                        lastReadTime, addTime, cover, fileName, fileStatus
                    )
                    SELECT
                        b.id, b.title, b.author, b.filePath, b.format, b.groupId,
                        b.lastReadTime, b.addTime, b.cover, b.title, 'AVAILABLE'
                    FROM books b
                    INNER JOIN (
                        SELECT filePath, MIN(id) AS keepId
                        FROM books
                        GROUP BY filePath
                    ) kept ON kept.keepId = b.id
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE books")
                db.execSQL("ALTER TABLE books_new RENAME TO books")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_filePath ON books(filePath)")

                db.execSQL(
                    """
                    CREATE TABLE book_groups_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createTime INTEGER NOT NULL,
                        displayName TEXT NOT NULL DEFAULT '',
                        sourceTreeUri TEXT,
                        relativePath TEXT,
                        sourceKey TEXT NOT NULL DEFAULT '',
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        isExpanded INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO book_groups_new (
                        id, name, createTime, displayName, sourceKey, sortOrder, isExpanded
                    )
                    SELECT
                        id, name, createTime, name, 'legacy:' || id, 0, 1
                    FROM book_groups
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE book_groups")
                db.execSQL("ALTER TABLE book_groups_new RENAME TO book_groups")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_groups_sourceKey ON book_groups(sourceKey)")

                db.execSQL(
                    """
                    CREATE TABLE read_progress_new (
                        bookId INTEGER PRIMARY KEY NOT NULL,
                        position TEXT NOT NULL,
                        locatorType TEXT NOT NULL DEFAULT 'TXT',
                        txtCharOffset INTEGER,
                        txtTotalLength INTEGER,
                        epubSpineIndex INTEGER,
                        epubChapterHref TEXT,
                        epubChapterOffset INTEGER,
                        epubProgressFraction REAL,
                        updateTime INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO read_progress_new (
                        bookId,
                        position,
                        locatorType,
                        txtCharOffset,
                        txtTotalLength,
                        epubSpineIndex,
                        epubChapterHref,
                        epubChapterOffset,
                        epubProgressFraction,
                        updateTime
                    )
                    SELECT
                        map.keepId,
                        rp.position,
                        CASE
                            WHEN UPPER(COALESCE(b.format, 'TXT')) = 'EPUB' THEN 'EPUB_COMBINED_LEGACY'
                            ELSE 'TXT'
                        END,
                        CASE
                            WHEN UPPER(COALESCE(b.format, 'TXT')) = 'TXT' AND rp.position GLOB '[0-9]*'
                            THEN CAST(rp.position AS INTEGER)
                            ELSE NULL
                        END,
                        NULL,
                        CASE
                            WHEN UPPER(COALESCE(b.format, 'TXT')) = 'EPUB' THEN 0
                            ELSE NULL
                        END,
                        CASE
                            WHEN UPPER(COALESCE(b.format, 'TXT')) = 'EPUB' THEN 'EPUB_COMBINED_LEGACY'
                            ELSE NULL
                        END,
                        CASE
                            WHEN UPPER(COALESCE(b.format, 'TXT')) = 'EPUB' AND rp.position GLOB '[0-9]*'
                            THEN CAST(rp.position AS INTEGER)
                            ELSE NULL
                        END,
                        NULL,
                        rp.updateTime
                    FROM read_progress rp
                    INNER JOIN book_id_map map ON map.oldId = rp.bookId
                    INNER JOIN books b ON b.id = map.keepId
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM read_progress newer
                        INNER JOIN book_id_map newer_map ON newer_map.oldId = newer.bookId
                        WHERE newer_map.keepId = map.keepId
                          AND (
                              newer.updateTime > rp.updateTime
                              OR (
                                  newer.updateTime = rp.updateTime
                                  AND newer.bookId > rp.bookId
                              )
                          )
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE read_progress")
                db.execSQL("ALTER TABLE read_progress_new RENAME TO read_progress")

                db.execSQL(
                    """
                    CREATE TABLE bookmarks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        position TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createTime INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO bookmarks_new (id, bookId, position, content, createTime)
                    SELECT bm.id, map.keepId, bm.position, bm.content, bm.createTime
                    FROM bookmarks bm
                    INNER JOIN book_id_map map ON map.oldId = bm.bookId
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE bookmarks")
                db.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")
                db.execSQL("DROP TABLE book_id_map")
            }
        }
    }
}
