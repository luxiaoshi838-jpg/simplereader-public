package com.simplereader.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SimpleReaderBackupDecoderTest {
    @Test
    fun `recognizes the existing schema version 1 document without optional fields`() {
        val backup = SimpleReaderBackupDecoder.decode(schemaV1Json())

        assertEquals(1, backup.schemaVersion)
        assertEquals(1, backup.groups.size)
        assertEquals(1, backup.books.size)
        assertEquals(1, backup.bookmarks.size)
        assertEquals(1, backup.progress.size)
        assertNull(backup.books.single().opt("author"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a foreign backup before any restore can start`() {
        SimpleReaderBackupDecoder.decode(
            """{"manifest":{"format":"OtherApp","schemaVersion":1},"tables":{}}"""
        )
    }

    companion object {
        fun schemaV1Json(): String = """
            {
              "manifest": {
                "format": "SimpleReaderBackup",
                "schemaVersion": 1,
                "minimumCompatibleSchemaVersion": 1,
                "encoding": "UTF-8"
              },
              "relationships": {
                "books.groupId": "book_groups.id",
                "bookmarks.bookId": "books.id",
                "read_progress.bookId": "books.id"
              },
              "tables": {
                "book_groups": [
                  {"id":0,"name":"文学","displayName":"文学","sourceKey":"legacy:0","sortOrder":0}
                ],
                "books": [
                  {
                    "id":10,
                    "title":"第一本",
                    "filePath":"content://old/第一本.txt",
                    "format":"TXT",
                    "groupId":0,
                    "fileName":"第一本.txt",
                    "fileSize":100,
                    "relativePath":"旧根目录/文学",
                    "fileStatus":"AVAILABLE"
                  }
                ],
                "bookmarks": [
                  {"id":20,"bookId":10,"position":"12","content":"书签","createTime":1000}
                ],
                "read_progress": [
                  {"bookId":10,"position":"25","locatorType":"TXT","txtCharOffset":25,"updateTime":2000}
                ]
              }
            }
        """.trimIndent()
    }
}
