package com.simplereader.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simplereader.app.data.model.DuplicateStatus
import com.simplereader.app.data.model.ImportCandidate
import com.simplereader.app.data.model.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderGroupingTest {
    @Test
    fun selectableDepthStartsBelowSelectedRootFolder() {
        val candidates = listOf(
            candidate("书库/作者/系列/卷一/正文/章节", "a.txt"),
            candidate("书库/作者/短篇", "b.txt")
        )

        assertEquals(5, FolderGrouping.maxDetectedDepth(candidates))
        assertEquals((1..5).toList(), FolderGrouping.levelPreviews(candidates).map { it.depth })
    }

    @Test
    fun selectableDepthIsCappedAtTenLevels() {
        val path = (1..12).joinToString("/") { "第${it}层" }

        assertEquals(10, FolderGrouping.maxDetectedDepth(listOf(candidate(path, "deep.txt"))))
    }

    @Test
    fun previewsShowGroupsAddedBelowRoot() {
        val candidates = listOf(
            candidate("书库/作者A", "a0.txt"),
            candidate("书库/作者A/系列1", "a1.txt"),
            candidate("书库/作者B/系列1", "b1.txt")
        )

        val levels = FolderGrouping.levelPreviews(candidates)

        assertEquals(listOf(2, 3), levels.map { it.groupCount })
        assertEquals(listOf(2, 1), levels.map { it.additionalGroupCount })
    }

    @Test
    fun selectedDepthKeepsInternalPathsButShowsOnlyFolderNames() {
        val candidates = listOf(
            candidate("小说/作者A/正文", "a.txt"),
            candidate("小说/作者B/正文", "b.txt")
        )

        val depthOne = FolderGrouping.applyDepth(candidates, 1)
        val depthTwo = FolderGrouping.applyDepth(candidates, 2)

        assertEquals(listOf("作者A", "作者B"), depthOne.map { it.suggestedGroupName })
        assertEquals(listOf("正文", "正文"), depthTwo.map { it.suggestedGroupName })
        assertEquals(listOf("作者A/正文", "作者B/正文"), depthTwo.map { it.suggestedGroupRelativePath })
    }

    @Test
    fun filesDirectlyInSelectedRootRemainUngrouped() {
        val grouped = FolderGrouping.applyDepth(candidate("书库", "root.txt"), 1)

        assertNull(grouped.suggestedGroupKey)
        assertNull(grouped.suggestedGroupName)
        assertNull(grouped.suggestedGroupRelativePath)
    }

    private fun candidate(path: String, fileName: String): ImportCandidate {
        return ImportCandidate(
            uri = "content://book/$fileName",
            displayName = fileName,
            format = "TXT",
            sourceTreeUri = "content://tree/books",
            relativeParentPath = path,
            suggestedGroupKey = null,
            suggestedGroupName = null,
            size = 1L,
            lastModified = 1L,
            duplicateStatus = DuplicateStatus.NEW,
            permissionStatus = PermissionStatus.PERSISTED
        )
    }
}
