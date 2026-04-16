package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowRequirementsRepairEntryCoordinatorTest {

    @Test
    fun `startClarifyThenFill should return false when workflow cannot be resolved`() {
        val harness = CoordinatorHarness(workflows = emptyMap())

        assertFalse(
            harness.coordinator.startClarifyThenFill(
                workflowId = "missing",
                missingSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
        )

        assertTrue(harness.uiCalls.isEmpty())
        assertTrue(harness.timelineEntries.isEmpty())
        assertTrue(harness.launchCalls.isEmpty())
        assertNull(harness.retryStore.current("missing"))
    }

    @Test
    fun `startClarifyThenFill should return false when no missing sections are provided`() {
        val harness = CoordinatorHarness()

        assertFalse(
            harness.coordinator.startClarifyThenFill(
                workflowId = "wf-1",
                missingSections = emptyList(),
            ),
        )

        assertTrue(harness.uiCalls.isEmpty())
        assertTrue(harness.timelineEntries.isEmpty())
        assertTrue(harness.launchCalls.isEmpty())
        assertNull(harness.retryStore.current("wf-1"))
    }

    @Test
    fun `startClarifyThenFill should focus requirements workspace and launch first clarification round`() {
        val harness = CoordinatorHarness()
        val missingSections = listOf(
            RequirementsSectionId.USER_STORIES,
            RequirementsSectionId.ACCEPTANCE_CRITERIA,
        )
        val expectedPlan = requireNotNull(
            harness.gateRequirementsRepairCoordinator.buildClarifyThenFillPlan(
                missingSections = missingSections,
                previousRetry = null,
            ),
        )

        assertTrue(
            harness.coordinator.startClarifyThenFill(
                workflowId = "wf-1",
                missingSections = missingSections,
            ),
        )

        assertEquals(
            listOf(
                "showWorkspaceContent",
                "focusStage:REQUIREMENTS",
                "clearProcessTimeline",
            ),
            harness.uiCalls,
        )
        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.round", 1),
                    state = SpecWorkflowTimelineEntryState.ACTIVE,
                ),
            ),
            harness.timelineEntries,
        )
        val retry = requireNotNull(harness.retryStore.current("wf-1"))
        assertEquals(expectedPlan.input, retry.input)
        assertEquals(expectedPlan.suggestedDetails, retry.confirmedContext)
        assertEquals(1, retry.clarificationRound)
        assertFalse(retry.confirmed)
        assertEquals(ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR, retry.followUp)
        assertEquals(missingSections, retry.requirementsRepairSections)

        val launch = harness.launchCalls.single()
        assertEquals("wf-1", launch.workflow.id)
        assertEquals(expectedPlan.input, launch.input)
        assertEquals(expectedPlan.suggestedDetails, launch.suggestedDetails)
        assertEquals(1, launch.clarificationRound)
        assertEquals(retry, launch.pendingRetry)
    }

    @Test
    fun `startClarifyThenFill should reuse existing requirements repair retry context`() {
        val harness = CoordinatorHarness()
        val missingSections = listOf(
            RequirementsSectionId.USER_STORIES,
            RequirementsSectionId.ACCEPTANCE_CRITERIA,
        )
        harness.retryStore.remember(
            SpecWorkflowClarificationRetryRememberRequest(
                workflowId = "wf-1",
                input = "repair requirements",
                confirmedContext = "existing repair context",
                questionsMarkdown = "## Questions",
                structuredQuestions = listOf("Q1"),
                clarificationRound = 2,
                lastError = "missing acceptance criteria",
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = missingSections,
            ),
        )

        assertTrue(
            harness.coordinator.startClarifyThenFill(
                workflowId = "wf-1",
                missingSections = missingSections,
            ),
        )

        assertEquals(
            listOf(
                "showWorkspaceContent",
                "focusStage:REQUIREMENTS",
            ),
            harness.uiCalls,
        )
        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.round", 3),
                    state = SpecWorkflowTimelineEntryState.ACTIVE,
                ),
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineEntries,
        )
        val retry = requireNotNull(harness.retryStore.current("wf-1"))
        assertEquals("repair requirements", retry.input)
        assertEquals("existing repair context", retry.confirmedContext)
        assertEquals("## Questions", retry.questionsMarkdown)
        assertEquals(listOf("Q1"), retry.structuredQuestions)
        assertEquals("missing acceptance criteria", retry.lastError)
        assertEquals(3, retry.clarificationRound)
        assertEquals(ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR, retry.followUp)
        assertEquals(missingSections, retry.requirementsRepairSections)

        val launch = harness.launchCalls.single()
        assertEquals("repair requirements", launch.input)
        assertEquals("existing repair context", launch.suggestedDetails)
        assertEquals(3, launch.clarificationRound)
        assertEquals(retry, launch.pendingRetry)
    }

    private class CoordinatorHarness(
        workflows: Map<String, SpecWorkflow> = mapOf("wf-1" to workflow("wf-1")),
    ) {
        val retryStore = SpecWorkflowClarificationRetryStore { _, _ -> }
        val uiCalls = mutableListOf<String>()
        val timelineEntries = mutableListOf<SpecWorkflowTimelineEntry>()
        val launchCalls = mutableListOf<SpecWorkflowRequirementsRepairClarificationLaunchRequest>()
        private val workflowById = workflows.toMutableMap()
        val gateRequirementsRepairCoordinator = SpecWorkflowGateRequirementsRepairCoordinator(
            aiUnavailableReason = { null },
            locateRequirementsArtifact = { workflowId -> Path.of("/tmp", "$workflowId-requirements.md") },
            renderClarificationFailureMarkdown = { error -> error.message.orEmpty() },
        )
        val coordinator = SpecWorkflowRequirementsRepairEntryCoordinator(
            retryStore = retryStore,
            resolveWorkflow = { workflowId -> workflowById[workflowId] },
            gateRequirementsRepairCoordinator = gateRequirementsRepairCoordinator,
            showWorkspaceContent = {
                uiCalls += "showWorkspaceContent"
            },
            focusStage = { stageId ->
                uiCalls += "focusStage:${stageId.name}"
            },
            clearProcessTimeline = {
                uiCalls += "clearProcessTimeline"
            },
            appendTimelineEntry = timelineEntries::add,
            launchClarification = launchCalls::add,
        )
    }

    private companion object {
        fun workflow(id: String): SpecWorkflow {
            val metadata = SpecMetadata(
                title = "Workflow $id",
                description = "Spec for $id",
            )
            return SpecWorkflow(
                id = id,
                currentPhase = SpecPhase.IMPLEMENT,
                documents = mapOf(
                    SpecPhase.IMPLEMENT to SpecDocument(
                        id = "$id-implement",
                        phase = SpecPhase.IMPLEMENT,
                        content = "tasks",
                        metadata = metadata,
                    ),
                ),
                status = WorkflowStatus.IN_PROGRESS,
                title = metadata.title,
                description = metadata.description,
            )
        }
    }
}
