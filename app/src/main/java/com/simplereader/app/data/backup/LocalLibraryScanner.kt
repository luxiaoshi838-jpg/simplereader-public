package com.simplereader.app.data.backup

import androidx.documentfile.provider.DocumentFile
import com.simplereader.app.data.model.PermissionStatus
import java.util.ArrayDeque

/** Scans the user-selected local-library root once and returns current readable URIs. */
class LocalLibraryScanner(
    private val maximumFiles: Int = 10_000
) {
    data class LocalBookFile(
        val uri: String,
        val displayName: String,
        val format: String,
        val sourceTreeUri: String,
        val relativeParentPath: String,
        val size: Long?,
        val lastModified: Long?,
        val permissionStatus: PermissionStatus
    ) {
        val title: String
            get() = displayName.substringBeforeLast('.', displayName)
    }

    fun scan(
        root: DocumentFile,
        sourceTreeUri: String,
        permissionStatus: PermissionStatus
    ): List<LocalBookFile> {
        require(root.isDirectory) { "所选位置不是文件夹" }

        val result = ArrayList<LocalBookFile>()
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to "")

        while (queue.isNotEmpty() && result.size < maximumFiles) {
            val (folder, relativeParent) = queue.removeFirst()
            val children = folder.listFiles()
                .sortedBy { it.name.orEmpty().lowercase() }

            for (child in children) {
                if (result.size >= maximumFiles) break
                val name = child.name.orEmpty()
                if (name.isBlank() || name.startsWith('.')) continue

                if (child.isDirectory) {
                    queue.add(child to joinPath(relativeParent, name))
                    continue
                }

                val format = formatOf(name) ?: continue
                result += LocalBookFile(
                    uri = child.uri.toString(),
                    displayName = name,
                    format = format,
                    sourceTreeUri = sourceTreeUri,
                    relativeParentPath = relativeParent,
                    size = child.length().takeIf { it >= 0L },
                    lastModified = child.lastModified().takeIf { it > 0L },
                    permissionStatus = permissionStatus
                )
            }
        }

        return result
            .distinctBy { normalizePath(joinPath(it.relativeParentPath, it.displayName)) }
            .sortedWith(
                compareBy<LocalBookFile> { normalizePath(it.relativeParentPath) }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    private fun formatOf(name: String): String? = when {
        name.endsWith(".txt", ignoreCase = true) -> "TXT"
        name.endsWith(".epub", ignoreCase = true) -> "EPUB"
        name.endsWith(".chm", ignoreCase = true) -> "CHM"
        else -> null
    }

    private fun joinPath(parent: String, child: String): String =
        listOf(parent.trim('/'), child.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")

    private fun normalizePath(value: String): String = value
        .replace('\\', '/')
        .trim('/')
        .lowercase()

}
