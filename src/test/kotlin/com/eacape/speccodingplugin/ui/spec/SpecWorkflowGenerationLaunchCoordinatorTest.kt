package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowGenerationLaunchCoordinatorTest {

    @Test
    fun `generate should resume generation when confirmed clarification can be reused`() {
        val harness = CoordinatorHarness()
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "seed input",
                confirmedContext = "confirmed context",
                questionsMarkdown = "## Questions",
                structuredQuestions = listOf("Q1"),
                clarificationRound = 2,
                confirmed = true,
            ),
        )

        harness.coordinator.generate("")

        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineEntries,
        )
        val run = harness.runGenerationCalls.single()
        assertEquals("wf-1", run.workflowId)
        assertEquals("seed input", run.input)
        assertEquals("confirmed context", run.options.confirmedContext)
        assertEquals(
            harness.retryStore.current("wf-1")?.toWritebackPayload(),
            run.options.clarificationWriteback,
        )
        assertTrue(harness.requestClarificationDraftCalls.isEmpty())
    }

    @Test
    fun `generate should request clarification and store generation retry when more context is needed`() {
        val harness = CoordinatorHarness()

        harness.coordinator.generate("Clarify rollback and verification expectations")

        assertEquals(1, harness.clearTimelineCalls)
        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.round", 1),
                    state = SpecWorkflowTimelineEntryState.ACTIVE,
                ),
            ),
            harness.timelineEntries,
        )
        assertEquals(
            ClarificationRetryPayload(
                input = "Clarify rollback and verification expectations",
                confirmedContext = "Clarify rollback and verification expectations",
                questionsMarkdown = "",
                structuredQuestions = emptyList(),
                clarificationRound = 1,
                lastError = null,
                confirmed = false,
                followUp = ClarificationFollowUp.GENERATION,
                requirementsRepairSections = emptyList(),
            ),
            harness.retryStore.current("wf-1"),
        )
        val draft = harness.requestClarificationDraftCalls.single()
        assertEquals("wf-1", draft.context.workflowId)
        assertEquals("Clarify rollback and verification expectations", draft.input)
        assertEquals("Clarify rollback and verification expectations", draft.suggestedDetails)
        assertTrue(harness.runGenerationCalls.isEmpty())
    }

    @Test
    fun `generate should continue requirements repair when confirmed retry context can be reused`() {
        val harness = CoordinatorHarness()
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "repair requirements",
                confirmedContext = "repair context",
                clarificationRound = 2,
                confirmed = true,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
        )

        harness.coordinator.generate("")

        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineEntries,
        )
        val continuation = harness.continueRepairCalls.single()
        assertEquals("wf-1", continuation.workflowId)
        assertEquals("repair requirements", continuation.input)
        assertEquals("repair context", continuation.confirmedContext)
        assertTrue(harness.requirementsRepairLaunchCalls.isEmpty())
    }

    @Test
    fun `generate should relaunch requirements repair clarification when another round is needed`() {
        val harness = CoordinatorHarness()
        harness.retryStore.remember(
            retryRequest(
                workflowId = "wf-1",
                input = "repair requirements",
                confirmedContext = "existing repair context",
                questionsMarkdown = "",
                clarificationRound = 3,
                lastError = "missing acceptance criteria",
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.ACCEPTANCE_CRITERIA),
            ),
        )

        harness.coordinator.generate("")

        assertEquals(1, harness.clearTimelineCalls)
        assertEquals(
            listOf(
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.round", 4),
                    state = SpecWorkflowTimelineEntryState.ACTIVE,
                ),
                SpecWorkflowTimelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.retryContextReuse"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineEntries,
        )
        assertEquals(
            ClarificationRetryPayload(
                input = "repair requirements",
                confirmedContext = "existing repair context",
                questionsMarkdown = "",
                structuredQuestions = emptyList(),
                clarificationRound = 4,
                lastError = "missing acceptance criteria",
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.ACCEPTANCE_CRITERIA),
            ),
            harness.retryStore.current("wf-1"),
        )
        val launch = harness.requirementsRepairLaunchCalls.single()
        assertEquals("repair requirements", launch.input)
        assertEquals("existing repair context", launch.suggestedDetails)
        assertEquals(4, launch.clarificationRound)
        assertEquals(4, launch.pendingRetry?.clarificationRound)
        assertTrue(harness.continueRepairCalls.isEmpty())
    }

    private fun retryRequest(
        workflowId: String,
        input: String,
        confirmedContext: String,
        questionsMarkdown: String? = null,
        structuredQuestions: List<String>? = null,
        clarificationRound: Int? = null,
        lastError: String? = null,
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
            lastError = lastError,
            confirmed = confirmed,
            followUp = followUp,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun generationContext(): SpecWorkflowGenerationContext {
        return SpecWorkflowGenerationContext(
            workflowId = "wf-1",
            phase = SpecPhase.SPECIFY,
            options = GenerationOptions(
                providerId = "provider-1",
                model = "model-1",
                workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
                composeActionMode = ArtifactComposeActionMode.GENERATE,
            ),
        )
    }

    private fun workflow(
        id: String = "wf-1",
        phase: SpecPhase = SpecPhase.SPECIFY,
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
        )
    }

    private inner class CoordinatorHarness {
        val retryStore = SpecWorkflowClarificationRetryStore { _, _ -> }
        var currentWorkflow: SpecWorkflow? = workflow()
        var generationContext: SpecWorkflowGenerationContext? = generationContext()
        var clearTimelineCalls = 0
        val timelineEntries = mutableListOf<SpecWorkflowTimelineEntry>()
        val runGenerationCalls = mutableListOf<SpecWorkflowGenerationExecutionRequest>()
        val requestClarificationDraftCalls = mutableListOf<SpecWorkflowClarificationDraftLaunchRequest>()
        val requirementsRepairLaunchCalls =
            mutableListOf<SpecWorkflowRequirementsRepairClarificationLaunchRequest>()
        val continueRepairCalls = mutableListOf<SpecWorkflowContinueRequirementsRepairRequest>()

        val coordinator = SpecWorkflowGenerationLaunchCoordinator(
            retryStore = retryStore,
            resolveSelectedWorkflow = { currentWorkflow },
            resolveGenerationContext = { generationContext },
            clearProcessTimeline = {
                clearTimelineCalls += 1
            },
            appendTimelineEntry = { entry ->
                timelineEntries += entry
            },
            generationCoordinator = SpecWorkflowGenerationCoordinator(
                providerDisplayName = { providerId -> "Provider $providerId" },
                renderFailureMessage = { error, fallback -> error?.message ?: fallback },
            ),
            gateRequirementsRepairCoordinator = SpecWorkflowGateRequirementsRepairCoordinator(
                aiUnavailableReason = { null },
                locateRequirementsArtifact = { error("unused") },
                renderClarificationFailureMarkdown = { error -> error.message.orEmpty() },
            ),
            runGeneration = { request ->
                runGenerationCalls += request
            },
            requestClarificationDraft = { request ->
                requestClarificationDraftCalls += request
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
