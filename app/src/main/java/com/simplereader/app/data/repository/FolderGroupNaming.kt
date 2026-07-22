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
        val compactPath = pathSegments.joinToString("/")
        val pathAwareName = pathSegments.joinToString(" / ")
        val currentName = group.displayName.ifBlank { group.name }.trim()
        val wasAutomaticallyNamed = currentName.isBlank() ||
            currentName == leafName ||
            currentName == compactPath ||
            currentName == pathAwareName
        if (!wasAutomaticallyNamed) return group

        return group.copy(name = pathAwareName, displayName = pathAwareName)
    }
}
