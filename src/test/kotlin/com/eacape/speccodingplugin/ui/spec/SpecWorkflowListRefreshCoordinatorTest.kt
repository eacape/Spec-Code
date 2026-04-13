package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.WorkflowMeta
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowListRefreshCoordinatorTest {

    private val coordinator = SpecWorkflowListRefreshCoordinator(
        listWorkflowMetadata = { error("unused in apply tests") },
        stageLabel = { stageId -> "stage:${stageId.name.lowercase()}" },
        selectionCoordinator = SpecWorkflowSelectionCoordinator(),
    )

    @Test
    fun `load should map workflow metadata into workflow list items`() {
        val loadCoordinator = SpecWorkflowListRefreshCoordinator(
            listWorkflowMetadata = {
                listOf(
                    workflowMeta(
                        workflowId = "wf-load",
                        title = " ",
                        description = null,
                        currentStage = StageId.DESIGN,
                        updatedAt = 42L,
                        baselineWorkflowId = "wf-baseline",
                        changeIntent = SpecChangeIntent.INCREMENTAL,
                    ),
                )
            },
            stageLabel = { stageId -> "stage:${stageId.name.lowercase()}" },
            selectionCoordinator = SpecWorkflowSelectionCoordinator(),
        )

        val loadedState = loadCoordinator.load()

        assertEquals(
            listOf(
                SpecWorkflowListPanel.WorkflowListItem(
                    workflowId = "wf-load",
                    title = "wf-load",
                    description = "",
                    currentPhase = SpecPhase.DESIGN,
                    currentStageLabel = "stage:design",
                    status = WorkflowStatus.IN_PROGRESS,
                    updatedAt = 42L,
                    changeIntent = SpecChangeIntent.INCREMENTAL,
                    baselineWorkflowId = "wf-baseline",
                ),
            ),
            loadedState.items,
        )
    }

    @Test
    fun `apply should update list state and load resolved workflow target`() {
        val callbacks = RecordingCallbacks()

        coordinator.apply(
            request = SpecWorkflowListRefreshApplyRequest(
                loadedState = SpecWorkflowListRefreshLoadedState(
                    listOf(item("wf-a"), item("wf-b")),
                ),
                selectWorkflowId = " wf-b ",
                selectedWorkflowId = "wf-a",
                highlightedWorkflowId = "wf-a",
            ),
            callbacks = callbacks,
        )

        assertEquals(
            listOf("cancelPopup", "updateItems:2", "status:null", "switchEnabled:true", "highlight:wf-b", "load:wf-b"),
            callbacks.events,
        )
        assertEquals(linkedSetOf("wf-a", "wf-b"), callbacks.validWorkflowIds.single())
        assertNull(callbacks.clearOpenedWorkflowResetHighlight)
    }

    @Test
    fun `apply should preserve list mode without reopening a workflow`() {
        val callbacks = RecordingCallbacks()

        coordinator.apply(
            request = SpecWorkflowListRefreshApplyRequest(
                loadedState = SpecWorkflowListRefreshLoadedState(
                    listOf(item("wf-a"), item("wf-b")),
                ),
                selectedWorkflowId = "wf-missing",
                highlightedWorkflowId = "wf-b",
                preserveListMode = true,
            ),
            callbacks = callbacks,
        )

        assertEquals(
            listOf("cancelPopup", "updateItems:2", "status:null", "switchEnabled:true", "highlight:wf-b", "clear:false"),
            callbacks.events,
        )
        assertEquals(false, callbacks.clearOpenedWorkflowResetHighlight)
    }

    @Test
    fun `apply should clear highlight and opened workflow when no workflows remain`() {
        val callbacks = RecordingCallbacks()

        coordinator.apply(
            request = SpecWorkflowListRefreshApplyRequest(
                loadedState = SpecWorkflowListRefreshLoadedState(emptyList()),
                selectedWorkflowId = "wf-stale",
                highlightedWorkflowId = "wf-stale",
            ),
            callbacks = callbacks,
        )

        assertEquals(
            listOf("cancelPopup", "updateItems:0", "status:null", "switchEnabled:false", "highlight:null", "clear:true"),
            callbacks.events,
        )
        assertTrue(callbacks.validWorkflowIds.single().isEmpty())
        assertEquals(true, callbacks.clearOpenedWorkflowResetHighlight)
        assertTrue(callbacks.loadedWorkflowIds.isEmpty())
    }

    private fun workflowMeta(
        workflowId: String,
        title: String? = "Workflow $workflowId",
        description: String? = "refresh test",
        currentStage: StageId = StageId.DESIGN,
        updatedAt: Long = 1L,
        baselineWorkflowId: String? = null,
        changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
    ): WorkflowMeta {
        return WorkflowMeta(
            workflowId = workflowId,
            title = title,
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = mapOf(
                currentStage to StageState(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                ),
            ),
            currentStage = currentStage,
            currentPhase = SpecPhase.DESIGN,
            verifyEnabled = true,
            configPinHash = null,
            baselineWorkflowId = baselineWorkflowId,
            artifactDraftStates = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            createdAt = 0L,
            updatedAt = updatedAt,
            description = description,
            changeIntent = changeIntent,
        )
    }

    private fun item(workflowId: String): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = workflowId,
            title = "Workflow $workflowId",
            description = "refresh test",
            currentPhase = SpecPhase.DESIGN,
            currentStageLabel = "stage:design",
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
        )
    }

    private class RecordingCallbacks : SpecWorkflowListRefreshCallbacks {
        val events = mutableListOf<String>()
        val validWorkflowIds = mutableListOf<Set<String>>()
        val loadedWorkflowIds = mutableListOf<String>()
        var clearOpenedWorkflowResetHighlight: Boolean? = null

        override fun cancelWorkflowSwitcherPopup() {
            events += "cancelPopup"
        }

        override fun updateWorkflowItems(items: List<SpecWorkflowListPanel.WorkflowListItem>) {
            events += "updateItems:${items.size}"
        }

        override fun setStatusText(text: String?) {
            events += "status:${text ?: "null"}"
        }

        override fun setSwitchWorkflowEnabled(enabled: Boolean) {
            events += "switchEnabled:$enabled"
        }

        override fun dropPendingOpenRequestIfInvalid(validWorkflowIds: Set<String>) {
            this.validWorkflowIds += validWorkflowIds
        }

        override fun highlightWorkflow(workflowId: String?) {
            events += "highlight:${workflowId ?: "null"}"
        }

        override fun loadWorkflow(workflowId: String) {
            loadedWorkflowIds += workflowId
            events += "load:$workflowId"
        }

        override fun clearOpenedWorkflowUi(resetHighlight: Boolean) {
            clearOpenedWorkflowResetHighlight = resetHighlight
            events += "clear:$resetHighlight"
        }
    }
}
