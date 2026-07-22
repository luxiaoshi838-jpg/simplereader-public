package com.simplereader.app.data.repository

import com.simplereader.app.data.dao.BookGroupDao
import com.simplereader.app.data.entity.BookGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookGroupRepository(private val bookGroupDao: BookGroupDao) {
    fun getAllGroups(): Flow<List<BookGroup>> = bookGroupDao.getAllGroups().map { groups ->
        groups.map(FolderGroupNaming::normalize)
    }

    suspend fun getGroup(id: Long): BookGroup? =
        bookGroupDao.getGroup(id)?.let(FolderGroupNaming::normalize)

    suspend fun getGroupByName(name: String): BookGroup? =
        bookGroupDao.getGroupByName(name)?.let(FolderGroupNaming::normalize)

    suspend fun getGroupBySourceKey(sourceKey: String): BookGroup? =
        bookGroupDao.getGroupBySourceKey(sourceKey)?.let(FolderGroupNaming::normalize)

    suspend fun insert(group: BookGroup): Long = bookGroupDao.insert(FolderGroupNaming.normalize(group))

    suspend fun insertOrGetExistingId(group: BookGroup): Long {
        val normalizedGroup = FolderGroupNaming.normalize(group)
        val insertedId = bookGroupDao.insert(normalizedGroup)
        return if (insertedId != -1L) {
            insertedId
        } else {
            val existingGroup = bookGroupDao.getGroupBySourceKey(normalizedGroup.sourceKey)
                ?: error("BookGroup insert conflicted but existing row was not found: ${normalizedGroup.sourceKey}")
            val normalizedExistingGroup = FolderGroupNaming.normalize(existingGroup)
            if (normalizedExistingGroup != existingGroup) {
                bookGroupDao.update(normalizedExistingGroup)
            }
            existingGroup.id
        }
    }

    suspend fun rename(id: Long, newName: String) {
        val trimmedName = newName.trim()
        require(trimmedName.isNotEmpty()) { "分组名称不能为空" }
        val changedRows = bookGroupDao.renameById(id, trimmedName)
        check(changedRows == 1) { "未找到需要重命名的分组" }
        val savedGroup = bookGroupDao.getGroup(id)
            ?: error("重命名后未能读取分组")
        check(savedGroup.name == trimmedName && savedGroup.displayName == trimmedName) {
            "分组名称未正确保存"
        }
    }

    suspend fun update(group: BookGroup) = bookGroupDao.update(group)
    suspend fun delete(group: BookGroup) = bookGroupDao.delete(group)
}
