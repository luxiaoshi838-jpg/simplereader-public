package com.simplereader.app.ui

import com.simplereader.app.parser.TxtWindowResult
import java.util.ArrayDeque

/**
 * A bounded set of adjacent TXT source windows presented as one continuous text.
 *
 * The source file remains the single source of truth; this class only retains a
 * small rolling in-memory neighborhood. Windows are required to be byte-adjacent,
 * so adding them never duplicates or drops text at a hidden boundary.
 */
class TxtContinuousBuffer(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val minimumChunksToKeep: Int = 3
) {
    data class Chunk(val startByte: Long, val endByte: Long, val text: String) {
        init {
            require(startByte >= 0L)
            require(endByte >= startByte)
        }
    }

    data class Mutation(
        val accepted: Boolean,
        val prepended: Boolean = false,
        val appended: Boolean = false,
        val removedPrefixChars: Int = 0,
        val removedSuffixChars: Int = 0
    )

    private val chunks = ArrayDeque<Chunk>()
    private var charCount = 0

    val isEmpty: Boolean get() = chunks.isEmpty()
    val startByte: Long get() = chunks.peekFirst()?.startByte ?: 0L
    val endByte: Long get() = chunks.peekLast()?.endByte ?: startByte
    val content: String get() = buildString(charCount) { chunks.forEach { append(it.text) } }
    val chunkCount: Int get() = chunks.size

    fun reset(window: TxtWindowResult) {
        chunks.clear()
        charCount = 0
        addInitial(window)
    }

    fun append(window: TxtWindowResult): Mutation {
        if (window.text.isEmpty() || window.nextByte <= window.startByte) return Mutation(false)
        if (chunks.isEmpty()) {
            addInitial(window)
            return Mutation(true, appended = true)
        }
        val expected = endByte
        if (window.startByte != expected || window.nextByte <= expected) return Mutation(false)
        val chunk = window.toChunk()
        chunks.addLast(chunk)
        charCount += chunk.text.length
        var removed = 0
        while (charCount > maxChars && chunks.size > minimumChunksToKeep) {
            val first = chunks.removeFirst()
            charCount -= first.text.length
            removed += first.text.length
        }
        return Mutation(true, appended = true, removedPrefixChars = removed)
    }

    fun prepend(window: TxtWindowResult): Mutation {
        if (window.text.isEmpty() || window.nextByte <= window.startByte) return Mutation(false)
        if (chunks.isEmpty()) {
            addInitial(window)
            return Mutation(true, prepended = true)
        }
        val expected = startByte
        if (window.nextByte != expected || window.startByte >= expected) return Mutation(false)
        val chunk = window.toChunk()
        chunks.addFirst(chunk)
        charCount += chunk.text.length
        var removed = 0
        while (charCount > maxChars && chunks.size > minimumChunksToKeep) {
            val last = chunks.removeLast()
            charCount -= last.text.length
            removed += last.text.length
        }
        return Mutation(true, prepended = true, removedSuffixChars = removed)
    }

    fun byteForFraction(fraction: Float): Long {
        val span = (endByte - startByte).coerceAtLeast(1L)
        return startByte + (span * fraction.coerceIn(0f, 1f)).toLong()
    }

    fun fractionForByte(byteOffset: Long): Float {
        val span = (endByte - startByte).coerceAtLeast(1L)
        return ((byteOffset.coerceIn(startByte, endByte) - startByte).toDouble() / span.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun addInitial(window: TxtWindowResult) {
        if (window.text.isEmpty() || window.nextByte <= window.startByte) return
        val chunk = window.toChunk()
        chunks.add(chunk)
        charCount = chunk.text.length
    }

    private fun TxtWindowResult.toChunk(): Chunk = Chunk(startByte, nextByte, text)

    companion object {
        const val DEFAULT_MAX_CHARS = 1_200_000
    }
}
