package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
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
import java.nio.file.Path

class SpecWorkflowRequirementsRepairClarificationCoordinatorTest {

    @Test
    fun `launchClarification should request clarification draft when requirements ai is available`() {
        val harness = harness()

        harness.coordinator.launchClarification(
            SpecWorkflowRequirementsRepairClarificationLaunchRequest(
                workflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY),
                input = "repair requirements",
                suggestedDetails = "confirmed details",
                pendingRetry = retryPayload(confirmedContext = "seed context"),
                clarificationRound = 2,
            ),
        )

        val request = harness.requestClarificationDraftCalls.single()
        assertEquals("wf-1", request.context.workflowId)
        assertEquals(SpecPhase.SPECIFY, request.context.phase)
        assertEquals("repair requirements", request.input)
        assertEquals("confirmed details", request.suggestedDetails)
        assertEquals(
            GenerationOptions(
                providerId = "provider-1",
                model = "model-1",
                confirmedContext = "seed context",
                workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-wf-1")),
                composeActionMode = workflow(id = "wf-1", phase = SpecPhase.SPECIFY)
                    .resolveComposeActionMode(SpecPhase.SPECIFY),
            ),
            request.options,
        )
        assertTrue(harness.previewAndApplyCalls.isEmpty())
        assertTrue(harness.manualFallbacks.isEmpty())
    }

    @Test
    fun `launchClarification should fall back to manual draft and persist retry state when requirements ai is unavailable`() {
        val harness = harness(
            aiUnavailableReason = { "AI offline" },
            providerId = null,
            modelId = null,
        )
        val pendingRetry = retryPayload(
            confirmedContext = "existing context",
            clarificationRound = 3,
            requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
        )

        harness.coordinator.launchClarification(
            SpecWorkflowRequirementsRepairClarificationLaunchRequest(
                workflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY),
                input = "repair requirements",
                suggestedDetails = "new details",
                pendingRetry = pendingRetry,
                clarificationRound = 4,
            ),
        )

        assertTrue(harness.requestClarificationDraftCalls.isEmpty())
        val fallback = harness.manualFallbacks.single()
        assertEquals("wf-1", fallback.workflowId)
        assertEquals("repair requirements", fallback.input)
        assertEquals("new details", fallback.suggestedDetails)
        assertEquals(4, fallback.clarificationRound)
        assertEquals(
            ClarificationRetryPayload(
                input = "repair requirements",
                confirmedContext = "new details",
                questionsMarkdown = "markdown:AI offline",
                structuredQuestions = emptyList(),
                clarificationRound = 4,
                lastError = "AI offline",
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
            harness.retryStore.current("wf-1"),
        )
        assertEquals(
            listOf(
                TimelineCall(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                    state = SpecWorkflowTimelineEntryState.DONE,
                ),
                TimelineCall(
                    text = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualFallback.timeline"),
                    state = SpecWorkflowTimelineEntryState.INFO,
                ),
            ),
            harness.timelineCalls,
        )
        assertEquals(fallback.statusMessage, harness.statusCalls.single())
    }

    @Test
    fun `continueAfterClarification should exit clarification and open requirements document when ai fill falls back to manual path`() {
        val harness = harness(aiUnavailableReason = { "AI offline" })

        harness.coordinator.continueAfterClarification(
            SpecWorkflowContinueRequirementsRepairRequest(
                workflowId = "wf-1",
                pendingRetry = retryPayload(
                    requirementsRepairSections = listOf(RequirementsSectionId.NON_FUNCTIONAL),
                ),
                input = "repair input",
                confirmedContext = "confirmed details",
            ),
        )

        assertEquals(1, harness.unlockCalls)
        assertEquals(listOf(false), harness.exitClarificationCalls)
        assertEquals(
            SpecCodingBundle.message(
                "spec.toolwindow.gate.quickFix.clarify.manualFallback.status",
                "AI offline",
            ),
            harness.statusCalls.single(),
        )
        assertEquals(listOf(Path.of("/tmp/wf-1-requirements.md")), harness.openedPaths)
        assertEquals(
            listOf(
                InfoCall(
                    title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarify.manualContinue.title"),
                    message = SpecCodingBundle.message(
                        "spec.toolwindow.gate.quickFix.clarify.manualContinue.message",
                        RequirementsSectionSupport.describeSections(
                            listOf(RequirementsSectionId.NON_FUNCTIONAL),
                        ),
                        "AI offline",
                    ),
                ),
            ),
            harness.infoCalls,
        )
    }

    @Test
    fun `continueAfterClarification should clear retry and reload requirements workflow when ai fill applies`() {
        val harness = harness()
        harness.retryStore.remember(
            retryRememberRequest(
                workflowId = "wf-1",
                confirmedContext = "confirmed details",
                requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
        )

        harness.coordinator.continueAfterClarification(
            SpecWorkflowContinueRequirementsRepairRequest(
                workflowId = "wf-1",
                pendingRetry = retryPayload(
                    requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
                ),
                input = "repair input",
                confirmedContext = "confirmed details",
            ),
        )

        val request = harness.previewAndApplyCalls.single()
        request.onApplied()

        assertNull(harness.retryStore.current("wf-1"))
        assertEquals(listOf(true), harness.reloadRequirementsWorkflowCalls)
        assertEquals(listOf(true), harness.exitClarificationCalls)
        assertEquals(1, harness.unlockCalls)
    }

    @Test
    fun `continueAfterClarification should restore retry state with rendered error when ai fill preview fails`() {
        val harness = harness()

        harness.coordinator.continueAfterClarification(
            SpecWorkflowContinueRequirementsRepairRequest(
                workflowId = "wf-1",
                pendingRetry = retryPayload(
                    clarificationRound = 5,
                    confirmed = true,
                    requirementsRepairSections = listOf(RequirementsSectionId.ACCEPTANCE_CRITERIA),
                ),
                input = "repair input",
                confirmedContext = "confirmed details",
            ),
        )

        val request = harness.previewAndApplyCalls.single()
        request.onFailure(IllegalStateException("preview failed"))

        assertEquals(1, harness.unlockCalls)
        assertEquals(
            ClarificationRetryPayload(
                input = "repair input",
                confirmedContext = "confirmed details",
                questionsMarkdown = "",
                structuredQuestions = emptyList(),
                clarificationRound = 5,
                lastError = "rendered:preview failed",
                confirmed = true,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.ACCEPTANCE_CRITERIA),
            ),
            harness.retryStore.current("wf-1"),
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:preview failed"),
            ),
            harness.statusCalls,
        )
        assertTrue(harness.reloadRequirementsWorkflowCalls.isEmpty())
    }

    private fun harness(
        aiUnavailableReason: (String?) -> String? = { null },
        providerId: String? = "provider-1",
        modelId: String? = "model-1",
    ): CoordinatorHarness {
        return CoordinatorHarness(
            aiUnavailableReason = aiUnavailableReason,
            providerId = providerId,
            modelId = modelId,
        )
    }

    private fun retryRememberRequest(
        workflowId: String,
        input: String = "repair input",
        confirmedContext: String = "repair context",
        clarificationRound: Int = 1,
        lastError: String? = null,
        confirmed: Boolean = false,
        requirementsRepairSections: List<RequirementsSectionId> = listOf(RequirementsSectionId.NON_FUNCTIONAL),
    ): SpecWorkflowClarificationRetryRememberRequest {
        return SpecWorkflowClarificationRetryRememberRequest(
            workflowId = workflowId,
            input = input,
            confirmedContext = confirmedContext,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun retryPayload(
        input: String = "repair input",
        confirmedContext: String = "repair context",
        clarificationRound: Int = 1,
        lastError: String? = null,
        confirmed: Boolean = false,
        requirementsRepairSections: List<RequirementsSectionId> = listOf(RequirementsSectionId.NON_FUNCTIONAL),
    ): ClarificationRetryPayload {
        return ClarificationRetryPayload(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = "",
            structuredQuestions = emptyList(),
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun workflow(
        id: String,
        phase: SpecPhase,
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

    private data class TimelineCall(
        val text: String,
        val state: SpecWorkflowTimelineEntryState,
    )

    private data class InfoCall(
        val title: String,
        val message: String,
    )

    private class CoordinatorHarness(
        aiUnavailableReason: (String?) -> String?,
        providerId: String?,
        modelId: String?,
    ) {
        val requestClarificationDraftCalls = mutableListOf<SpecWorkflowClarificationDraftLaunchRequest>()
        val previewAndApplyCalls = mutableListOf<SpecWorkflowRequirementsRepairPreviewAndApplyRequest>()
        val timelineCalls = mutableListOf<TimelineCall>()
        val manualFallbacks = mutableListOf<SpecWorkflowGateRequirementsClarificationLaunch.ManualFallback>()
        val statusCalls = mutableListOf<String>()
        val infoCalls = mutableListOf<InfoCall>()
        val exitClarificationCalls = mutableListOf<Boolean>()
        val reloadRequirementsWorkflowCalls = mutableListOf<Boolean>()
        val openedPaths = mutableListOf<Path>()
        var unlockCalls = 0

        val retryStore = SpecWorkflowClarificationRetryStore { _, _ -> }

        val coordinator = SpecWorkflowRequirementsRepairClarificationCoordinator(
            retryStore = retryStore,
            gateRequirementsRepairCoordinator = SpecWorkflowGateRequirementsRepairCoordinator(
                aiUnavailableReason = aiUnavailableReason,
                locateRequirementsArtifact = { workflowId ->
                    Path.of("/tmp/${workflowId}-requirements.md")
                },
                renderClarificationFailureMarkdown = { error ->
                    "markdown:${error.message}"
                },
            ),
            resolveProviderId = { providerId },
            resolveModelId = { modelId },
            resolveWorkflowSourceUsage = { workflowId ->
                WorkflowSourceUsage(selectedSourceIds = listOf("source-$workflowId"))
            },
            requestClarificationDraft = { request ->
                requestClarificationDraftCalls += request
            },
            previewAndApply = { request ->
                previewAndApplyCalls += request
            },
            appendTimelineEntry = { entry ->
                timelineCalls += TimelineCall(entry.text, entry.state)
            },
            showClarificationManualFallback = { fallback ->
                manualFallbacks += fallback
            },
            setStatusText = { text ->
                statusCalls += text
            },
            unlockClarificationChecklistInteractions = {
                unlockCalls += 1
            },
            exitClarificationMode = { clearInput ->
                exitClarificationCalls += clearInput
            },
            reloadRequirementsWorkflow = { focusRequirements ->
                reloadRequirementsWorkflowCalls += focusRequirements
            },
            openRequirementsDocument = { path ->
                openedPaths.add(path)
            },
            showInfo = { title, message ->
                infoCalls += InfoCall(title, message)
            },
            renderFailureMessage = { error ->
                "rendered:${error.message}"
            },
        )
    }
}
