package com.simplereader.app.data.repository

import com.simplereader.app.data.entity.BookGroup

object FolderGroupNaming {
    fun normalize(group: BookGroup): BookGroup {
        val pathSegments = group.relativePath
            ?.split('/')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (pathSegments.isEmpty()) return group

        val leafName = pathSegments.last()
        val currentName = group.displayName.ifBlank { group.name }.trim()
        val wasAutomaticallyNamed = currentName.isBlank() ||
            currentName == leafName ||
            currentName == pathSegments.joinToString("/") ||
            currentName == pathSegments.joinToString(" / ")
        if (!wasAutomaticallyNamed) return group

        return group.copy(name = leafName, displayName = leafName)
    }
}
