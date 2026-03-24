package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowWorkspaceLayoutTest {

    @Test
    fun `requirements stage should show continue checks but not verification yet`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = StageId.REQUIREMENTS,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `design stage should show continue checks but not verification yet`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = StageId.DESIGN,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `tasks stage should show continue checks but not verification yet`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = StageId.TASKS,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `implement stage should show checks and verification together`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = StageId.IMPLEMENT,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `completed workflow should keep verification visible without continue checks`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.VERIFY,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.visibleSections(
                currentStage = StageId.ARCHIVE,
                status = WorkflowStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun `requirements stage should focus overview checks and documents`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.REQUIREMENTS,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `design stage should focus overview checks and documents`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.DESIGN,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `tasks stage should focus tasks and documents`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.TASKS,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.TASKS,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `verify stage should focus gate and verify modules`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.VERIFY,
                status = WorkflowStatus.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `completed workflow should keep only overview and verify expanded`() {
        assertEquals(
            linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.VERIFY,
            ),
            SpecWorkflowWorkspaceLayout.defaultExpandedSections(
                currentStage = StageId.ARCHIVE,
                status = WorkflowStatus.COMPLETED,
            ),
        )
    }
}
