package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.worktree.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreeDetailPanelTest {

    @Test
    fun `updateWorktree should display detail fields and error text`() {
        val panel = WorktreeDetailPanel()
        val item = WorktreeListItem(
            id = "wt-1",
            specTaskId = "SPEC-42",
            specTitle = "Auth Flow",
            branchName = "spec/spec-42-auth-flow",
            worktreePath = "D:/worktrees/wt-1",
            baseBranch = "main",
            status = WorktreeStatus.ERROR,
            isActive = true,
            updatedAt = 12345L,
            lastError = "merge conflict",
        )

        panel.updateWorktree(item)

        assertTrue(panel.displayedSpecTaskIdForTest().contains("SPEC-42"))
        assertTrue(panel.displayedStatusForTest().contains("error"))
        assertTrue(panel.displayedStatusForTest().contains("active"))
        assertEquals("merge conflict", panel.displayedLastErrorForTest())
        assertTrue(!panel.isShowingEmptyForTest())
    }

    @Test
    fun `showEmpty should reset to empty state`() {
        val panel = WorktreeDetailPanel()
        val item = WorktreeListItem(
            id = "wt-2",
            specTaskId = "SPEC-2",
            specTitle = "Spec 2",
            branchName = "spec/spec-2-demo",
            worktreePath = "D:/worktrees/wt-2",
            baseBranch = "develop",
            status = WorktreeStatus.ACTIVE,
            isActive = false,
            updatedAt = 1L,
            lastError = null,
        )

        panel.updateWorktree(item)
        assertTrue(!panel.isShowingEmptyForTest())

        panel.showEmpty()
        assertTrue(panel.isShowingEmptyForTest())
    }
}
