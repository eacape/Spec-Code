package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.ClarificationRetryState
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowClarificationActionCoordinatorTest {

    @Test
    fun `confirm should persist retry confirmation and continue generation`() {
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY),
            generationContext = generationContext(
                workflowId = "wf-1",
                composeActionMode = ArtifactComposeActionMode.GENERATE,
            ),
        )
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "seed input",
                confirmedContext = "seed context",
                questionsMarkdown = "## Questions",
                structuredQuestions = listOf("Q1"),
                clarificationRound = 2,
                confirmed = false,
            ),
        )

        harness.coordinator.confirm(
            input = "seed input",
            confirmedContext = "confirmed details",
        )

        val retry = requireNotNull(harness.retryStore.current("wf-1"))
        val run = harness.runGenerationCalls.single()
        assertEquals("seed input", run.input)
        assertEquals("confirmed details", run.options.confirmedContext)
        assertEquals(retry.toWritebackPayload(confirmedContext = "confirmed details"), run.options.clarificationWriteback)
        assertTrue(retry.confirmed)
        assertEquals("confirmed details", retry.confirmedContext)
        assertEquals(
            listOf(
                TimelineCall(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.confirmed"),
                    state = SpecWorkflowTimelineEntryState.DONE,
                ),
            ),
            harness.timelineCalls,
        )
        assertEquals(0, harness.unlockCalls)
        assertTrue(harness.continueRepairCalls.isEmpty())
    }

    @Test
    fun `confirm should continue requirements repair when retry follow up targets gate repair`() {
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY),
            generationContext = null,
        )
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "repair input",
                confirmedContext = "repair context",
                clarificationRound = 3,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
        )

        harness.coordinator.confirm(
            input = "repair input",
            confirmedContext = "confirmed repair context",
        )

        val call = harness.continueRepairCalls.single()
        assertEquals("wf-1", call.workflowId)
        assertEquals("confirmed repair context", call.confirmedContext)
        assertEquals(
            listOf(RequirementsSectionId.USER_STORIES),
            call.pendingRetry.requirementsRepairSections,
        )
        assertEquals(0, harness.unlockCalls)
        assertTrue(harness.runGenerationCalls.isEmpty())
    }

    @Test
    fun `confirm should unlock checklist when generation context is unavailable`() {
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY),
            generationContext = null,
        )

        harness.coordinator.confirm(
            input = "seed input",
            confirmedContext = "confirmed details",
        )

        assertEquals(1, harness.unlockCalls)
        assertTrue(harness.runGenerationCalls.isEmpty())
        assertEquals("confirmed details", harness.retryStore.current("wf-1")?.confirmedContext)
    }

    @Test
    fun `regenerate should route requirements repair retries back into repair clarification`() {
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY),
            generationContext = null,
        )
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "repair input",
                confirmedContext = "existing draft",
                clarificationRound = 2,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.ACCEPTANCE_CRITERIA),
            ),
        )

        harness.coordinator.regenerate(
            input = "repair input",
            currentDraft = "updated draft",
        )

        val launch = harness.requirementsRepairLaunchCalls.single()
        assertEquals("updated draft", launch.suggestedDetails)
        assertEquals(3, launch.clarificationRound)
        assertEquals(
            listOf(
                TimelineCall(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.regenerate", 3),
                    state = SpecWorkflowTimelineEntryState.ACTIVE,
                ),
            ),
            harness.timelineCalls,
        )
        assertTrue(harness.requestClarificationDraftCalls.isEmpty())
    }

    @Test
    fun `skip should clear generation retry and continue generation with skipped status`() {
        val workflow = workflow(id = "wf-1", phase = SpecPhase.DESIGN)
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow,
            generationContext = generationContext(
                workflowId = "wf-1",
                composeActionMode = ArtifactComposeActionMode.REVISE,
            ),
        )
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "seed input",
                confirmedContext = "seed context",
                clarificationRound = 2,
            ),
        )

        harness.coordinator.skip("seed input")

        assertNull(harness.retryStore.current("wf-1"))
        assertEquals(
            ArtifactComposeActionUiText.clarificationSkippedProceed(ArtifactComposeActionMode.REVISE),
            harness.statusCalls.single(),
        )
        assertEquals(
            listOf(
                TimelineCall(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.skipped"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineCalls,
        )
        assertEquals("wf-1", harness.runGenerationCalls.single().workflowId)
    }

    @Test
    fun `cancel should stop active clarification and clear retry state`() {
        val workflow = workflow(id = "wf-1", phase = SpecPhase.IMPLEMENT)
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow,
            generationContext = null,
        )
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "seed input",
                confirmedContext = "seed context",
            ),
        )

        harness.coordinator.cancel()

        assertNull(harness.retryStore.current("wf-1"))
        assertEquals(
            listOf("Clarification cancelled by user"),
            harness.cancelReasons,
        )
        assertEquals(
            listOf(
                TimelineCall(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.cancelled"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineCalls,
        )
        assertEquals(
            listOf(
                ArtifactComposeActionUiText.clarificationCancelled(
                    workflow.resolveComposeActionMode(workflow.currentPhase),
                ),
            ),
            harness.statusCalls,
        )
    }

    @Test
    fun `autosave should ignore mismatched active workflow and keep retry in memory only`() {
        val harness = harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow(id = "wf-2", phase = SpecPhase.SPECIFY),
            generationContext = null,
        )

        harness.coordinator.autosave(
            input = "draft input",
            confirmedContext = "draft context",
            questionsMarkdown = "## Questions",
            structuredQuestions = listOf("Q1", "Q1"),
        )

        assertNull(harness.retryStore.current("wf-1"))
        assertTrue(harness.persistCalls.isEmpty())

        harness.currentWorkflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY)
        harness.coordinator.autosave(
            input = "draft input",
            confirmedContext = "draft context",
            questionsMarkdown = "## Questions",
            structuredQuestions = listOf("Q1", "Q1"),
        )

        assertEquals(
            ClarificationRetryPayload(
                input = "draft input",
                confirmedContext = "draft context",
                questionsMarkdown = "## Questions",
                structuredQuestions = listOf("Q1"),
                clarificationRound = 1,
                lastError = null,
                confirmed = false,
                followUp = ClarificationFollowUp.GENERATION,
                requirementsRepairSections = emptyList(),
            ),
            harness.retryStore.current("wf-1"),
        )
        assertTrue(harness.persistCalls.isEmpty())
    }

    private fun harness(
        selectedWorkflowId: String?,
        currentWorkflow: SpecWorkflow?,
        generationContext: SpecWorkflowGenerationContext?,
    ): CoordinatorHarness {
        return CoordinatorHarness(
            selectedWorkflowId = selectedWorkflowId,
            currentWorkflow = currentWorkflow,
            generationContext = generationContext,
        )
    }

    private fun retryRequest(
        workflowId: String,
        input: String,
        confirmedContext: String,
        questionsMarkdown: String? = null,
        structuredQuestions: List<String>? = null,
        clarificationRound: Int? = null,
        confirmed: Boolean? = null,
        followUp: ClarificationFollowUp? = null,
        requirementsRepairSections: List<RequirementsSectionId>? = null,
    ): SpecWorkflowClarificationRetryRememberRequest {
        return SpecWorkflowClarificationRetryRememberRequest(
            workflowId = workflowId,
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            confirmed = confirmed,
            followUp = followUp,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun generationContext(
        workflowId: String,
        composeActionMode: ArtifactComposeActionMode,
    ): SpecWorkflowGenerationContext {
        return SpecWorkflowGenerationContext(
            workflowId = workflowId,
            phase = SpecPhase.SPECIFY,
            options = GenerationOptions(
                providerId = "provider-1",
                model = "model-1",
                workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
                composeActionMode = composeActionMode,
            ),
        )
    }

    private fun workflow(
        id: String,
        phase: SpecPhase,
        clarificationRetryState: ClarificationRetryState? = null,
    ): SpecWorkflow {
        val metadata = SpecMetadata(
            title = "Workflow $id",
            description = "Spec for $id",
        )
        return SpecWorkflow(
            id = id,
            currentPhase = phase,
            documents = mapOf(
                phase to SpecDocument(
                    id = "$id-$phase",
                    phase = phase,
                    content = "content",
                    metadata = metadata,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = metadata.title,
            description = metadata.description,
            clarificationRetryState = clarificationRetryState,
        )
    }

    private data class TimelineCall(
        val text: String,
        val state: SpecWorkflowTimelineEntryState,
    )

    private data class PersistCall(
        val workflowId: String,
        val state: ClarificationRetryState?,
    )

    private class CoordinatorHarness(
        selectedWorkflowId: String?,
        currentWorkflow: SpecWorkflow?,
        generationContext: SpecWorkflowGenerationContext?,
    ) {
        var selectedWorkflowId: String? = selectedWorkflowId
        var currentWorkflow: SpecWorkflow? = currentWorkflow
        var generationContext: SpecWorkflowGenerationContext? = generationContext
        val timelineCalls = mutableListOf<TimelineCall>()
        val statusCalls = mutableListOf<String>()
        val cancelReasons = mutableListOf<String>()
        val requestClarificationDraftCalls = mutableListOf<SpecWorkflowClarificationDraftLaunchRequest>()
        val runGenerationCalls = mutableListOf<SpecWorkflowClarificationRunGenerationRequest>()
        val requirementsRepairLaunchCalls =
            mutableListOf<SpecWorkflowRequirementsRepairClarificationLaunchRequest>()
        val continueRepairCalls = mutableListOf<SpecWorkflowContinueRequirementsRepairRequest>()
        val persistCalls = mutableListOf<PersistCall>()
        var unlockCalls = 0

        val retryStore = SpecWorkflowClarificationRetryStore { workflowId, state ->
            persistCalls += PersistCall(workflowId, state)
        }

        val coordinator = SpecWorkflowClarificationActionCoordinator(
            retryStore = retryStore,
            resolveSelectedWorkflow = {
                val workflowId = this.selectedWorkflowId?.trim().orEmpty()
                this.currentWorkflow?.takeIf { it.id == workflowId }
            },
            resolveGenerationContext = { this.generationContext },
            selectedWorkflowId = { this.selectedWorkflowId },
            currentWorkflow = { this.currentWorkflow },
            appendTimelineEntry = { entry ->
                timelineCalls += TimelineCall(entry.text, entry.state)
            },
            setStatusText = { text ->
                statusCalls += text
            },
            unlockClarificationChecklistInteractions = {
                unlockCalls += 1
            },
            cancelActiveGenerationRequest = { reason ->
                cancelReasons += reason
            },
            requestClarificationDraft = { request ->
                requestClarificationDraftCalls += request
            },
            runGeneration = { request ->
                runGenerationCalls += request
            },
            launchRequirementsRepairClarification = { request ->
                requirementsRepairLaunchCalls += request
            },
            continueRequirementsRepairAfterClarification = { request ->
                continueRepairCalls += request
            },
        )
    }
}
