package com.simplereader.app.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decoder for the existing SimpleReaderBackup schemaVersion 1 format.
 *
 * The decoder validates the complete document before any database write starts.
 * Optional legacy columns remain optional; the four table names and their meaning
 * are intentionally unchanged.
 */
object SimpleReaderBackupDecoder {
    const val FORMAT = "SimpleReaderBackup"
    const val CURRENT_SCHEMA_VERSION = 1

    data class DecodedBackup(
        val schemaVersion: Int,
        val manifest: JSONObject,
        val relationships: JSONObject?,
        val groups: List<JSONObject>,
        val books: List<JSONObject>,
        val bookmarks: List<JSONObject>,
        val progress: List<JSONObject>,
        val structuredCache: List<JSONObject>
    )

    fun decode(text: String): DecodedBackup {
        val normalized = text.removePrefix("\uFEFF").trim()
        require(normalized.isNotEmpty()) { "备份文件为空" }

        val root = runCatching { JSONObject(normalized) }
            .getOrElse { error("备份文件不是有效的 JSON：${it.message ?: "格式错误"}") }
        val manifest = root.optJSONObject("manifest")
            ?: error("不是有效的简阅备份文件：缺少 manifest")
        require(manifest.optString("format") == FORMAT) {
            "备份格式不受支持"
        }

        val schemaVersion = manifest.optInt("schemaVersion", -1)
        require(schemaVersion in 1..CURRENT_SCHEMA_VERSION) {
            "备份版本 $schemaVersion 暂不受支持"
        }

        val tables = root.optJSONObject("tables")
            ?: error("备份文件缺少 tables")

        return DecodedBackup(
            schemaVersion = schemaVersion,
            manifest = manifest,
            relationships = root.optJSONObject("relationships"),
            groups = tables.objectRows("book_groups"),
            books = tables.objectRows("books"),
            bookmarks = tables.objectRows("bookmarks"),
            progress = tables.objectRows("read_progress"),
            structuredCache = root.objectRows("structuredCache")
        )
    }

    private fun JSONObject.objectRows(tableName: String): List<JSONObject> {
        val array = optJSONArray(tableName) ?: JSONArray()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index)
                    ?: error("备份数据表 $tableName 的第 ${index + 1} 行无效")
                add(row)
            }
        }
    }
}
