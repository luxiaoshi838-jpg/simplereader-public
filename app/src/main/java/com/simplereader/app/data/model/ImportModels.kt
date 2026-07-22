package com.simplereader.app.data.model

enum class DuplicateStatus {
    NEW,
    SAME_URI,
    POSSIBLE_DUPLICATE,
    INVALID
}

enum class PermissionStatus {
    PERSISTED,
    SESSION_ONLY,
    MISSING,
    PERMISSION_REVOKED
}

enum class DuplicateStrategy {
    SKIP,
    UPDATE_EXISTING,
    KEEP_BOTH
}

enum class TargetGroupMode {
    SUGGESTED,
    UNGROUPED,
    EXISTING,
    NEW
}

data class ImportPlanOptions(
    val targetGroupMode: TargetGroupMode = TargetGroupMode.SUGGESTED,
    val targetGroupId: Long? = null,
    val targetGroupName: String? = null,
    val duplicateStrategy: DuplicateStrategy = DuplicateStrategy.SKIP
)

data class ImportCandidate(
    val uri: String,
    val displayName: String,
    val format: String,
    val sourceTreeUri: String?,
    val relativeParentPath: String?,
    val suggestedGroupKey: String?,
    val suggestedGroupName: String?,
    val suggestedGroupRelativePath: String? = relativeParentPath,
    val size: Long?,
    val lastModified: Long?,
    val duplicateStatus: DuplicateStatus,
    val permissionStatus: PermissionStatus,
    val selected: Boolean = true
)

sealed interface ImportItemResult {
    data class Inserted(val bookId: Long) : ImportItemResult
    data class Updated(val bookId: Long) : ImportItemResult
    data class Skipped(val reason: String) : ImportItemResult
    data class Failed(val reason: String) : ImportItemResult
}

data class ImportSummary(
    val found: Int,
    val imported: Int,
    val updated: Int,
    val skippedDuplicate: Int,
    val notSelected: Int,
    val invalid: Int,
    val failed: Int = 0
)
