package com.simplereader.app.data.backup

import com.simplereader.app.data.backup.LocalLibraryScanner.LocalBookFile

/** Stable, one-to-one matching between schema-v1 book rows and a freshly scanned library. */
object BackupFileMatcher {
    data class BackupBookIdentity(
        val oldId: Long,
        val title: String,
        val fileName: String,
        val format: String,
        val fileSize: Long?,
        val relativePath: String?
    )

    fun selectUnique(
        backup: BackupBookIdentity,
        candidates: Collection<LocalBookFile>
    ): LocalBookFile? {
        if (candidates.isEmpty()) return null
        val sameFormat = candidates.filter { it.format.equals(backup.format, ignoreCase = true) }
        if (sameFormat.isEmpty()) return null

        val sameName = sameFormat.filter {
            it.displayName.equals(backup.fileName, ignoreCase = true)
        }

        uniqueOrNull(sameName.filter { pathMatches(backup, it) })?.let { return it }

        if (backup.fileSize != null) {
            uniqueOrNull(sameName.filter { it.size == backup.fileSize })?.let { return it }
        }

        uniqueOrNull(sameName)?.let { return it }

        val sameTitle = sameFormat.filter {
            it.title.equals(backup.title, ignoreCase = true)
        }
        if (backup.fileSize != null) {
            uniqueOrNull(sameTitle.filter { it.size == backup.fileSize })?.let { return it }
        }
        return uniqueOrNull(sameTitle)
    }

    private fun pathMatches(backup: BackupBookIdentity, candidate: LocalBookFile): Boolean {
        val oldParent = normalizePath(backup.relativePath) ?: return false
        val newParent = normalizePath(candidate.relativeParentPath) ?: ""
        val oldFull = joinPath(oldParent, backup.fileName.lowercase())
        val newFull = joinPath(newParent, candidate.displayName.lowercase())
        return oldFull == newFull || oldFull.endsWith("/$newFull") || newFull.endsWith("/$oldFull")
    }

    private fun joinPath(parent: String, child: String): String =
        listOf(parent.trim('/'), child.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")

    private fun normalizePath(value: String?): String? = value
        ?.replace('\\', '/')
        ?.trim('/')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    private fun <T> uniqueOrNull(values: List<T>): T? =
        if (values.size == 1) values.single() else null
}
