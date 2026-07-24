package com.simplereader.app.ui

import kotlin.math.abs

internal object ReaderSearchSupport {
    fun nearestMatch(
        text: String,
        query: String,
        anchor: Int,
        ignoreCase: Boolean = true
    ): Int {
        if (text.isEmpty() || query.isEmpty()) return -1
        val safeAnchor = anchor.coerceIn(0, text.length)
        var cursor = 0
        var nearest = -1
        var nearestDistance = Int.MAX_VALUE
        while (cursor < text.length) {
            val index = text.indexOf(query, startIndex = cursor, ignoreCase = ignoreCase)
            if (index < 0) break
            val distance = abs(index - safeAnchor)
            if (distance < nearestDistance) {
                nearest = index
                nearestDistance = distance
                if (distance == 0) break
            }
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(text.length)
        }
        return nearest
    }
}
