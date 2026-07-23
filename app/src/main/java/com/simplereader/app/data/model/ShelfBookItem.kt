package com.simplereader.app.data.model

data class ShelfBookItem(
    val id: Long,
    val title: String,
    val author: String,
    val filePath: String,
    val format: String,
    val groupId: Long?,
    val lastReadTime: Long,
    val addTime: Long,
    val fileName: String,
    val fileStatus: String,
    val progressPosition: String?,
    val locatorType: String?,
    val txtCharOffset: Int?,
    val txtTotalLength: Int?,
    val epubProgressFraction: Float?
) {
    fun progressPercent(): Int {
        val fraction = when (format.uppercase()) {
            "TXT" -> {
                val offset = txtCharOffset ?: progressPosition?.toIntOrNull() ?: 0
                val total = txtTotalLength ?: 0
                if (total > 0) offset.toFloat() / total else 0f
            }
            "EPUB", "CHM" -> epubProgressFraction ?: 0f
            else -> 0f
        }
        return (fraction.coerceIn(0f, 1f) * 100).toInt()
    }
}
