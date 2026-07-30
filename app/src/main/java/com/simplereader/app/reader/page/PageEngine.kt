package com.simplereader.app.reader.page

import com.simplereader.app.parser.TxtWindowResult

data class ReaderPageLocator(
    val pageIndex: Long,
    val byteOffset: Long,
    val chapterIndex: Int? = null,
    val chapterOffset: Int? = null
)

data class ReaderPageBlock(
    val index: Long,
    val startByte: Long,
    val endByte: Long,
    val text: String
) {
    val isReadable: Boolean get() = text.isNotEmpty() && endByte > startByte
}

data class ReaderPageWindow(
    val blocks: List<ReaderPageBlock>,
    val centerIndex: Long,
    val targetByte: Long
) {
    val text: String = blocks.joinToString(separator = "") { it.text }
    val startByte: Long = blocks.firstOrNull()?.startByte ?: targetByte
    val endByte: Long = blocks.lastOrNull()?.endByte ?: targetByte

    fun byteForScroll(scrollY: Int, maxScroll: Int): Long {
        if (maxScroll <= 0 || blocks.isEmpty()) return targetByte
        val fraction = (scrollY.toDouble() / maxScroll.toDouble()).coerceIn(0.0, 1.0)
        return (startByte + ((endByte - startByte).coerceAtLeast(1L) * fraction))
            .toLong()
            .coerceIn(startByte, endByte)
    }

    fun fractionForByte(byteOffset: Long): Float {
        val span = (endByte - startByte).coerceAtLeast(1L)
        return ((byteOffset.coerceIn(startByte, endByte) - startByte).toDouble() / span.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }
}

object TxtPageEngine {
    fun pageIndexForByte(byteOffset: Long, pageBytes: Int): Long {
        val unit = pageBytes.coerceAtLeast(1).toLong()
        return byteOffset.coerceAtLeast(0L) / unit
    }

    fun blockFromWindow(index: Long, window: TxtWindowResult): ReaderPageBlock =
        ReaderPageBlock(
            index = index,
            startByte = window.startByte,
            endByte = window.nextByte,
            text = window.text
        )

    fun windowFromBlocks(
        targetByte: Long,
        pageBytes: Int,
        blocks: List<ReaderPageBlock>
    ): ReaderPageWindow {
        val readableBlocks = blocks
            .filter { it.isReadable }
            .distinctBy { it.startByte to it.endByte }
            .sortedBy { it.startByte }
        return ReaderPageWindow(
            blocks = readableBlocks,
            centerIndex = pageIndexForByte(targetByte, pageBytes),
            targetByte = targetByte
        )
    }
}
