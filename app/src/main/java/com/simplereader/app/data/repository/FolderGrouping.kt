package com.simplereader.app.data.repository

import com.simplereader.app.data.model.ImportCandidate

/**
 * Builds import groups below the folder selected by the user.
 *
 * Depth 1 is the folder selected by the user. Files placed directly in that
 * folder use the selected folder name as their group. Nested folders can be
 * selected by increasing depth.
 */
object FolderGrouping {
    const val MAX_SUPPORTED_DEPTH = 10

    data class LevelPreview(
        val depth: Int,
        val groupCount: Int,
        val additionalGroupCount: Int
    )

    data class GroupPreview(
        val key: String,
        val name: String,
        val bookCount: Int
    )

    fun maxDetectedDepth(candidates: List<ImportCandidate>): Int {
        return candidates
            .mapNotNull { candidate -> groupingSegments(candidate.relativeParentPath).size.takeIf { it > 0 } }
            .maxOrNull()
            ?.coerceAtMost(MAX_SUPPORTED_DEPTH)
            ?: 1
    }

    fun levelPreviews(candidates: List<ImportCandidate>): List<LevelPreview> {
        var previousCount = 0
        return (1..maxDetectedDepth(candidates)).map { depth ->
            val count = groupPreviews(candidates, depth).size
            LevelPreview(
                depth = depth,
                groupCount = count,
                additionalGroupCount = (count - previousCount).coerceAtLeast(0)
            ).also {
                previousCount = count
            }
        }
    }

    fun applyDepth(candidates: List<ImportCandidate>, depth: Int): List<ImportCandidate> {
        return candidates.map { candidate -> applyDepth(candidate, depth) }
    }

    fun applyDepth(candidate: ImportCandidate, depth: Int): ImportCandidate {
        val sourceTreeUri = candidate.sourceTreeUri ?: return candidate
        val segments = groupingSegments(candidate.relativeParentPath)
        if (segments.isEmpty()) {
            return candidate.copy(
                suggestedGroupKey = null,
                suggestedGroupName = null,
                suggestedGroupRelativePath = null
            )
        }

        val effectiveDepth = depth.coerceIn(1, minOf(segments.size, MAX_SUPPORTED_DEPTH))
        val groupSegments = segments.take(effectiveDepth)
        val groupPath = groupSegments.joinToString("/")
        val groupName = groupSegments.last()

        return candidate.copy(
            suggestedGroupKey = "$sourceTreeUri|$groupPath",
            suggestedGroupName = groupName,
            suggestedGroupRelativePath = groupPath
        )
    }

    fun groupPreviews(candidates: List<ImportCandidate>, depth: Int): List<GroupPreview> {
        return applyDepth(candidates, depth)
            .filter { it.suggestedGroupKey != null }
            .groupBy { it.suggestedGroupKey.orEmpty() }
            .map { (key, groupedCandidates) ->
                GroupPreview(
                    key = key,
                    name = groupedCandidates.first().suggestedGroupName ?: "未命名分组",
                    bookCount = groupedCandidates.size
                )
            }
            .sortedWith(compareBy<GroupPreview> { it.name.lowercase() }.thenBy { it.key.lowercase() })
    }

    private fun groupingSegments(path: String?): List<String> {
        val allSegments = path
            ?.replace('\\', '/')
            ?.split('/')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return allSegments
    }
}
