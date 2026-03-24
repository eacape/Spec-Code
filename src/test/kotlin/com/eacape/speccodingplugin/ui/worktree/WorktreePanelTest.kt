package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeMergeResult
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreePanelTest {

    @Test
    fun `refreshWorktrees should update list selection and detail`() {
        val panel = WorktreePanel(
            project = fakeProject(),
            listWorkflows = { listOf("SPEC-1") },
            loadWorkflow = { Result.success(workflow(id = "SPEC-1", title = "Workflow 1")) },
            getActiveWorktree = { binding(id = "wt-1", specTaskId = "SPEC-1", status = WorktreeStatus.ACTIVE) },
            listBindings = {
                listOf(
                    binding(id = "wt-1", specTaskId = "SPEC-1", status = WorktreeStatus.ACTIVE),
                    binding(id = "wt-2", specTaskId = "SPEC-2", status = WorktreeStatus.MERGED),
                )
            },
            switchWorktreeAction = { Result.success(binding(id = it, specTaskId = "SPEC-1", status = WorktreeStatus.ACTIVE)) },
            mergeWorktreeAction = { _, _ -> Result.success(mergeResult("wt-1", false)) },
            cleanupWorktreeAction = { _, _ -> Result.success(Unit) },
            runSynchronously = true,
        )

        panel.refreshWorktrees()

        assertEquals(2, panel.itemsForTest().size)
        assertEquals("wt-1", panel.selectedWorktreeIdForTest())
        assertTrue(panel.detailSpecTaskIdTextForTest().contains("SPEC-1"))
        assertTrue(panel.detailStatusTextForTest().contains("active"))

        panel.dispose()
    }

    @Test
    fun `switch merge cleanup actions should call injected operations`() {
        val switched = mutableListOf<String>()
        val merged = mutableListOf<Pair<String, String>>()
        val cleaned = mutableListOf<Pair<String, Boolean>>()

        val panel = WorktreePanel(
            project = fakeProject(),
            listWorkflows = { emptyList() },
            loadWorkflow = { Result.failure(IllegalStateException("unused")) },
            getActiveWorktree = { binding(id = "wt-1", specTaskId = "SPEC-1", status = WorktreeStatus.ACTIVE) },
            listBindings = { listOf(binding(id = "wt-1", specTaskId = "SPEC-1", status = WorktreeStatus.ACTIVE, baseBranch = "develop")) },
            switchWorktreeAction = {
                switched += it
                Result.success(binding(id = it, specTaskId = "SPEC-1", status = WorktreeStatus.ACTIVE))
            },
            mergeWorktreeAction = { id, target ->
                merged += id to target
                Result.success(mergeResult(id, false))
            },
            cleanupWorktreeAction = { id, force ->
                cleaned += id to force
                Result.success(Unit)
            },
            runSynchronously = true,
        )

        panel.refreshWorktrees()
        panel.setSelectedWorktreeForTest("wt-1")
        panel.clickSwitchForTest()
        panel.clickMergeForTest()
        panel.clickCleanupForTest()

        assertEquals(listOf("wt-1"), switched)
        assertEquals(listOf("wt-1" to "develop"), merged)
        assertEquals(listOf("wt-1" to true), cleaned)

        panel.dispose()
    }

    @Test
    fun `failed actions should not break panel state`() {
        val panel = WorktreePanel(
            project = fakeProject(),
            listWorkflows = { emptyList() },
            loadWorkflow = { Result.failure(IllegalStateException("unused")) },
            getActiveWorktree = { binding(id = "wt-err", specTaskId = "SPEC-ERR", status = WorktreeStatus.ACTIVE) },
            listBindings = { listOf(binding(id = "wt-err", specTaskId = "SPEC-ERR", status = WorktreeStatus.ACTIVE)) },
            switchWorktreeAction = { Result.failure(IllegalStateException("switch failed")) },
            mergeWorktreeAction = { _, _ -> Result.failure(IllegalStateException("merge failed")) },
            cleanupWorktreeAction = { _, _ -> Result.failure(IllegalStateException("cleanup failed")) },
            runSynchronously = true,
        )

        panel.refreshWorktrees()
        panel.setSelectedWorktreeForTest("wt-err")
        panel.clickSwitchForTest()
        panel.clickMergeForTest()
        assertTrue(panel.detailLastErrorTextForTest().contains("merge failed"))
        panel.clickCleanupForTest()

        assertEquals("wt-err", panel.selectedWorktreeIdForTest())
        assertTrue(panel.listPanelButtonStatesForTest()["switchEnabled"] == true)
        assertTrue(panel.detailStatusTextForTest().contains("active"))

        panel.dispose()
    }

    private fun fakeProject(): Project {
        return mockk(relaxed = true) {
            every { isDisposed } returns false
            every { basePath } returns "D:/repo"
        }
    }

    private fun binding(
        id: String,
        specTaskId: String,
        status: WorktreeStatus,
        baseBranch: String = "main",
    ): WorktreeBinding {
        return WorktreeBinding(
            id = id,
            specTaskId = specTaskId,
            branchName = "spec/${specTaskId.lowercase()}-$id",
            worktreePath = "D:/worktrees/$id",
            baseBranch = baseBranch,
            status = status,
            createdAt = 1L,
            updatedAt = 2L,
            lastError = null,
        )
    }

    private fun mergeResult(worktreeId: String, hasConflicts: Boolean): WorktreeMergeResult {
        return WorktreeMergeResult(
            worktreeId = worktreeId,
            sourceBranch = "spec/source",
            targetBranch = "main",
            hasConflicts = hasConflicts,
            statusDescription = if (hasConflicts) "CONFLICT" else "MERGED",
        )
    }

    private fun workflow(id: String, title: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to SpecDocument(
                    id = "doc-$id",
                    phase = SpecPhase.SPECIFY,
                    content = "content",
                    metadata = SpecMetadata(
                        title = title,
                        description = "desc",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                    validationResult = ValidationResult(valid = true),
                )
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = title,
            description = "desc",
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
}
