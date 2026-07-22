package com.simplereader.app.data.repository

import com.simplereader.app.data.entity.BookGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderGroupNamingTest {
    @Test
    fun nestedAutomaticGroupUsesFullRelativePath() {
        val group = BookGroup(
            name = "系列1",
            displayName = "系列1",
            sourceTreeUri = "content://books",
            relativePath = "小说/作者A/系列1",
            sourceKey = "content://books|小说/作者A/系列1"
        )

        val normalized = FolderGroupNaming.normalize(group)

        assertEquals("小说 / 作者A / 系列1", normalized.name)
        assertEquals("小说 / 作者A / 系列1", normalized.displayName)
        assertEquals(group.sourceKey, normalized.sourceKey)
    }

    @Test
    fun identicalLeafFoldersRemainVisiblyDistinct() {
        val first = FolderGroupNaming.normalize(
            BookGroup(
                name = "正文",
                displayName = "正文",
                relativePath = "小说/作者A/正文",
                sourceKey = "tree|小说/作者A/正文"
            )
        )
        val second = FolderGroupNaming.normalize(
            BookGroup(
                name = "正文",
                displayName = "正文",
                relativePath = "小说/作者B/正文",
                sourceKey = "tree|小说/作者B/正文"
            )
        )

        assertEquals("小说 / 作者A / 正文", first.displayName)
        assertEquals("小说 / 作者B / 正文", second.displayName)
    }

    @Test
    fun manualRenameIsPreserved() {
        val group = BookGroup(
            name = "我的收藏",
            displayName = "我的收藏",
            relativePath = "小说/作者A/系列1",
            sourceKey = "tree|小说/作者A/系列1"
        )

        assertEquals(group, FolderGroupNaming.normalize(group))
    }

    @Test
    fun singleFolderNameRemainsSimple() {
        val group = BookGroup(
            name = "小说",
            displayName = "小说",
            relativePath = "小说",
            sourceKey = "tree|小说"
        )

        assertEquals("小说", FolderGroupNaming.normalize(group).displayName)
    }
}
