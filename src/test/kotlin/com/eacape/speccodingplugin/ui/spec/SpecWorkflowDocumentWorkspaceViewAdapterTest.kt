package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowDocumentWorkspaceViewAdapterTest {

    @Test
    fun `updatePresentation should fall back to document view when structured tasks view is unavailable`() {
        val ui = Recorder()
        val adapter = SpecWorkflowDocumentWorkspaceViewAdapter(
            ui = ui,
            selectedView = { DocumentWorkspaceView.STRUCTURED_TASKS },
            selectedStructuredTaskId = { "task-1" },
            supportsStructuredTasksDocumentWorkspaceView = { false },
        )

        val presentation = adapter.resolvePresentation(workbenchState(StageId.VERIFY))
        adapter.updatePresentation(workbenchState(StageId.VERIFY))

        assertFalse(presentation.supportsStructuredTasksView)
        assertEquals(DocumentWorkspaceView.DOCUMENT, presentation.effectiveView)
        assertEquals(listOf(false), ui.tabsVisible)
        assertEquals(listOf(DocumentWorkspaceView.DOCUMENT), ui.shownCards)
        assertTrue(ui.syncedTaskIds.isEmpty())
        assertEquals(1, ui.refreshButtonsCalls)
        assertEquals(1, ui.refreshContainersCalls)
    }

    @Test
    fun `updatePresentation should keep structured tasks view and sync selected task when supported`() {
        val ui = Recorder()
        val adapter = SpecWorkflowDocumentWorkspaceViewAdapter(
            ui = ui,
            selectedView = { DocumentWorkspaceView.STRUCTURED_TASKS },
            selectedStructuredTaskId = { "task-42" },
            supportsStructuredTasksDocumentWorkspaceView = { it?.focusedStage == StageId.IMPLEMENT },
        )

        val presentation = adapter.resolvePresentation(workbenchState(StageId.IMPLEMENT))
        adapter.updatePresentation(workbenchState(StageId.IMPLEMENT))

        assertTrue(presentation.supportsStructuredTasksView)
        assertEquals(DocumentWorkspaceView.STRUCTURED_TASKS, presentation.effectiveView)
        assertTrue(presentation.shouldSyncStructuredTaskSelection)
        assertEquals(listOf(true), ui.tabsVisible)
        assertEquals(listOf(DocumentWorkspaceView.STRUCTURED_TASKS), ui.shownCards)
        assertEquals(listOf("task-42"), ui.syncedTaskIds)
    }

    private fun workbenchState(focusedStage: StageId): SpecWorkflowStageWorkbenchState {
        return SpecWorkflowStageWorkbenchState(
            currentStage = focusedStage,
            focusedStage = focusedStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = 1,
                totalSteps = 1,
                stageStatus = StageProgress.IN_PROGRESS,
                completedCheckCount = 0,
                totalCheckCount = 0,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = focusedStage,
                title = "Artifact",
                fileName = StageId.TASKS.artifactFileName,
                documentPhase = SpecPhase.IMPLEMENT,
                mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                fallbackEditable = false,
            ),
            visibleSections = emptySet(),
        )
    }

    private class Recorder : SpecWorkflowDocumentWorkspaceViewUi {
        val tabsVisible = mutableListOf<Boolean>()
        val shownCards = mutableListOf<DocumentWorkspaceView>()
        val syncedTaskIds = mutableListOf<String?>()
        var refreshButtonsCalls = 0
        var refreshContainersCalls = 0

        override fun setDocumentWorkspaceTabsVisible(visible: Boolean) {
            tabsVisible += visible
        }

        override fun showDocumentWorkspaceCard(view: DocumentWorkspaceView) {
            shownCards += view
        }

        override fun refreshDocumentWorkspaceViewButtons() {
            refreshButtonsCalls += 1
        }

        override fun syncStructuredTaskSelection(taskId: String?) {
            syncedTaskIds += taskId
        }

        override fun refreshDocumentWorkspaceContainers() {
            refreshContainersCalls += 1
        }
    }
}
