package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.worktree.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreeListPanelTest {

    @Test
    fun `updateWorktrees should replace list items and selection can be set`() {
        val panel = WorktreeListPanel(
            onWorktreeSelected = {},
            onCreateWorktree = {},
            onSwitchWorktree = {},
            onMergeWorktree = {},
            onCleanupWorktree = {},
        )

        panel.updateWorktrees(
            listOf(
                item(id = "wt-1", status = WorktreeStatus.ACTIVE, isActive = true),
                item(id = "wt-2", status = WorktreeStatus.MERGED, isActive = false),
            )
        )

        assertEquals(2, panel.itemsForTest().size)
        assertEquals("wt-1", panel.itemsForTest()[0].id)
        assertEquals("wt-2", panel.itemsForTest()[1].id)

        panel.setSelectedWorktree("wt-1")
        assertEquals("wt-1", panel.selectedWorktreeIdForTest())

        val activeStates = panel.buttonStatesForTest()
        assertTrue(activeStates["createEnabled"] == true)
        assertTrue(activeStates["switchEnabled"] == true)
        assertTrue(activeStates["mergeEnabled"] == true)
        assertTrue(activeStates["cleanupEnabled"] == true)

        panel.setSelectedWorktree("wt-2")
        val mergedStates = panel.buttonStatesForTest()
        assertFalse(mergedStates["switchEnabled"] == true)
        assertFalse(mergedStates["mergeEnabled"] == true)
        assertTrue(mergedStates["cleanupEnabled"] == true)

        panel.setSelectedWorktree(null)
        val noSelectionStates = panel.buttonStatesForTest()
        assertFalse(noSelectionStates["switchEnabled"] == true)
        assertFalse(noSelectionStates["mergeEnabled"] == true)
        assertFalse(noSelectionStates["cleanupEnabled"] == true)
    }

    @Test
    fun `toolbar actions should trigger callbacks with selected worktree`() {
        var createCalls = 0
        val switched = mutableListOf<String>()
        val merged = mutableListOf<String>()
        val cleaned = mutableListOf<String>()

        val panel = WorktreeListPanel(
            onWorktreeSelected = {},
            onCreateWorktree = { createCalls += 1 },
            onSwitchWorktree = { switched += it },
            onMergeWorktree = { merged += it },
            onCleanupWorktree = { cleaned += it },
        )

        panel.updateWorktrees(listOf(item(id = "wt-action", status = WorktreeStatus.ACTIVE, isActive = true)))

        panel.clickCreateForTest()
        assertEquals(1, createCalls)

        panel.setSelectedWorktree("wt-action")
        panel.clickSwitchForTest()
        panel.clickMergeForTest()
        panel.clickCleanupForTest()

        assertEquals(listOf("wt-action"), switched)
        assertEquals(listOf("wt-action"), merged)
        assertEquals(listOf("wt-action"), cleaned)
    }

    private fun item(id: String, status: WorktreeStatus, isActive: Boolean): WorktreeListItem {
        return WorktreeListItem(
            id = id,
            specTaskId = "SPEC-1",
            specTitle = "Spec Title",
            branchName = "spec/spec-1-demo",
            worktreePath = "D:/tmp/$id",
            baseBranch = "main",
            status = status,
            isActive = isActive,
            updatedAt = 1L,
            lastError = null,
        )
    }
}

