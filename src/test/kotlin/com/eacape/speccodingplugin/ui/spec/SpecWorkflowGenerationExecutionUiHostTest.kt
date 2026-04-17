package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowGenerationExecutionUiHostTest {

    @Test
    fun `showClarificationGenerating should render loading ui timeline and status`() {
        val harness = Harness()
        val prepared = preparedClarificationRequest()

        harness.host.showClarificationGenerating(prepared)

        assertEquals(
            listOf(
                ClarificationGeneratingCall(
                    phase = SpecPhase.SPECIFY,
                    input = "Clarify requirements",
                    suggestedDetails = "Reuse prior notes",
                ),
            ),
            harness.clarificationGeneratingCalls,
        )
        assertEquals(prepared.initialTimelineEntries, harness.timelineEntries)
        assertEquals(listOf(prepared.loadingStatusText), harness.plainStatuses)
    }

    @Test
    fun `applyClarificationDraftResult should remember retry and surface troubleshooting status`() {
        val harness = Harness()
        val prepared = preparedClarificationRequest(clarificationRound = 3)
        val result = SpecWorkflowClarificationDraftResult(
            phase = SpecPhase.SPECIFY,
            questionsMarkdown = "## Questions",
            structuredQuestions = listOf("What should be verified?"),
            errorText = "provider timeout",
            statusText = "Clarification failed",
            timelineEntry = timelineEntry("Clarification failed", SpecWorkflowTimelineEntryState.FAILED),
            troubleshootingTrigger = SpecWorkflowRuntimeTroubleshootingTrigger.CLARIFICATION_DRAFT_FAILURE,
        )

        harness.host.applyClarificationDraftResult(prepared, result)

        assertEquals(
            listOf(
                ClarificationDraftCall(
                    phase = SpecPhase.SPECIFY,
                    input = "Clarify requirements",
                    questionsMarkdown = "## Questions",
                    suggestedDetails = "Reuse prior notes",
                    structuredQuestions = listOf("What should be verified?"),
                ),
            ),
            harness.clarificationDraftCalls,
        )
        assertEquals(
            listOf(
                SpecWorkflowClarificationRetryRememberRequest(
                    workflowId = "wf-1",
                    input = "Clarify requirements",
                    confirmedContext = "Reuse prior notes",
                    questionsMarkdown = "## Questions",
                    structuredQuestions = listOf("What should be verified?"),
                    clarificationRound = 3,
                    lastError = "provider timeout",
                ),
            ),
            harness.rememberRequests,
        )
        assertEquals(listOf(result.timelineEntry), harness.timelineEntries)
        assertEquals(
            listOf(
                TroubleshootingStatusCall(
                    workflowId = "wf-1",
                    text = "Clarification failed",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.CLARIFICATION_DRAFT_FAILURE,
                ),
            ),
            harness.troubleshootingStatuses,
        )
        assertTrue(harness.plainStatuses.isEmpty())
    }

    @Test
    fun `applyGenerationProgress should ignore updates when workflow is not selected`() {
        val harness = Harness(selectedWorkflowId = "wf-2")

        harness.host.applyGenerationProgress(
            workflowId = "wf-1",
            input = "Generate requirements",
            update = SpecWorkflowGenerationProgressUpdate(
                tracker = SpecWorkflowGenerationProgressTracker(),
                timelineEntries = listOf(timelineEntry("Call model", SpecWorkflowTimelineEntryState.ACTIVE)),
                progressFraction = 0.4,
                statusText = "Still running",
            ),
        )

        assertTrue(harness.timelineEntries.isEmpty())
        assertTrue(harness.progressFractions.isEmpty())
        assertTrue(harness.plainStatuses.isEmpty())
        assertTrue(harness.troubleshootingStatuses.isEmpty())
    }

    @Test
    fun `applyGenerationProgress should update retry state and reload validation failure`() {
        val harness = Harness(
            clarificationRounds = mapOf("wf-1" to 4),
        )
        val validation = ValidationResult(
            valid = false,
            errors = listOf("Missing API Design"),
        )

        harness.host.applyGenerationProgress(
            workflowId = "wf-1",
            input = "Generate requirements",
            update = SpecWorkflowGenerationProgressUpdate(
                tracker = SpecWorkflowGenerationProgressTracker(),
                timelineEntries = listOf(timelineEntry("Validate", SpecWorkflowTimelineEntryState.ACTIVE)),
                progressFraction = 0.5,
                shouldClearRetry = true,
                retryConfirmedContext = "Keep current assumptions",
                retryLastError = "Missing API Design",
                statusText = "Validation failed",
                shouldReloadWorkflow = true,
                validationFailure = validation,
            ),
        )

        assertEquals(listOf(timelineEntry("Validate", SpecWorkflowTimelineEntryState.ACTIVE)), harness.timelineEntries)
        assertEquals(listOf(0.5), harness.progressFractions)
        assertEquals(listOf("wf-1"), harness.clearedWorkflowIds)
        assertEquals(
            listOf(
                SpecWorkflowClarificationRetryRememberRequest(
                    workflowId = "wf-1",
                    input = "Generate requirements",
                    confirmedContext = "Keep current assumptions",
                    clarificationRound = 4,
                    lastError = "Missing API Design",
                ),
            ),
            harness.rememberRequests,
        )
        assertEquals(listOf("Validation failed"), harness.plainStatuses)
        assertEquals(1, harness.reloadCallbacks.size)
        assertEquals(0, harness.generationFailedCount)
        assertTrue(harness.exitClarificationModeCalls.isEmpty())

        harness.reloadCallbacks.single()?.invoke(workflow(currentPhase = SpecPhase.DESIGN))

        assertEquals(
            listOf(
                ValidationFailureCall(
                    phase = SpecPhase.DESIGN,
                    validation = validation,
                ),
            ),
            harness.validationFailureCalls,
        )
    }

    @Test
    fun `applyGenerationProgress should surface failure state troubleshooting and reload`() {
        val harness = Harness()

        harness.host.applyGenerationProgress(
            workflowId = "wf-1",
            input = "Generate requirements",
            update = SpecWorkflowGenerationProgressUpdate(
                tracker = SpecWorkflowGenerationProgressTracker(),
                timelineEntries = listOf(timelineEntry("Generation failed", SpecWorkflowTimelineEntryState.FAILED)),
                shouldShowGenerationFailed = true,
                shouldExitClarificationMode = true,
                clearInputOnExit = true,
                statusText = "Generation failed",
                shouldReloadWorkflow = true,
                troubleshootingTrigger = SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_FAILURE,
            ),
        )

        assertEquals(listOf(timelineEntry("Generation failed", SpecWorkflowTimelineEntryState.FAILED)), harness.timelineEntries)
        assertEquals(1, harness.generationFailedCount)
        assertEquals(listOf(true), harness.exitClarificationModeCalls)
        assertEquals(
            listOf(
                TroubleshootingStatusCall(
                    workflowId = "wf-1",
                    text = "Generation failed",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_FAILURE,
                ),
            ),
            harness.troubleshootingStatuses,
        )
        assertEquals(1, harness.reloadCallbacks.size)
        assertNull(harness.reloadCallbacks.single())
    }

    @Test
    fun `handleClarificationInterrupted should remember retry and set error status`() {
        val harness = Harness(
            clarificationRounds = mapOf("wf-1" to 2),
        )
        val options = GenerationOptions(
            providerId = "provider-1",
            model = "model-1",
            confirmedContext = "Need deployment constraints",
            workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
            composeActionMode = ArtifactComposeActionMode.GENERATE,
        )

        harness.host.handleClarificationInterrupted(
            workflowId = "wf-1",
            input = "Clarify requirements",
            options = options,
        )

        val interruptedMessage = SpecCodingBundle.message("spec.workflow.generation.interrupted")
        assertEquals(
            listOf(
                timelineEntry(
                    text = SpecCodingBundle.message("spec.workflow.process.clarify.failed", interruptedMessage),
                    state = SpecWorkflowTimelineEntryState.FAILED,
                ),
            ),
            harness.timelineEntries,
        )
        assertEquals(
            listOf(
                SpecWorkflowClarificationRetryRememberRequest(
                    workflowId = "wf-1",
                    input = "Clarify requirements",
                    confirmedContext = "Need deployment constraints",
                    clarificationRound = 2,
                    lastError = interruptedMessage,
                ),
            ),
            harness.rememberRequests,
        )
        assertEquals(1, harness.generationFailedCount)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", interruptedMessage),
            ),
            harness.plainStatuses,
        )
    }

    private class Harness(
        private val selectedWorkflowId: String = "wf-1",
        clarificationRounds: Map<String, Int> = emptyMap(),
    ) {
        val clarificationGeneratingCalls = mutableListOf<ClarificationGeneratingCall>()
        val clarificationDraftCalls = mutableListOf<ClarificationDraftCall>()
        val timelineEntries = mutableListOf<SpecWorkflowTimelineEntry>()
        val progressFractions = mutableListOf<Double>()
        val validationFailureCalls = mutableListOf<ValidationFailureCall>()
        var generationFailedCount = 0
        val exitClarificationModeCalls = mutableListOf<Boolean>()
        val rememberRequests = mutableListOf<SpecWorkflowClarificationRetryRememberRequest>()
        val clearedWorkflowIds = mutableListOf<String>()
        val plainStatuses = mutableListOf<String?>()
        val troubleshootingStatuses = mutableListOf<TroubleshootingStatusCall>()
        val reloadCallbacks = mutableListOf<((SpecWorkflow) -> Unit)?>()
        private val clarificationRounds = clarificationRounds.toMutableMap()

        val host = SpecWorkflowGenerationExecutionUiHost(
            invokeLaterOnUi = { action -> action() },
            isWorkflowSelectedNow = { workflowId -> selectedWorkflowId == workflowId },
            showClarificationGeneratingUi = { phase, input, suggestedDetails ->
                clarificationGeneratingCalls += ClarificationGeneratingCall(
                    phase = phase,
                    input = input,
                    suggestedDetails = suggestedDetails,
                )
            },
            showClarificationDraftUi = { phase, input, questionsMarkdown, suggestedDetails, structuredQuestions ->
                clarificationDraftCalls += ClarificationDraftCall(
                    phase = phase,
                    input = input,
                    questionsMarkdown = questionsMarkdown,
                    suggestedDetails = suggestedDetails,
                    structuredQuestions = structuredQuestions,
                )
            },
            appendTimelineEntriesUi = { entries ->
                timelineEntries += entries
            },
            showGeneratingUi = { fraction ->
                progressFractions += fraction
            },
            showValidationFailureUi = { phase, validation ->
                validationFailureCalls += ValidationFailureCall(
                    phase = phase,
                    validation = validation,
                )
            },
            showGenerationFailedUi = {
                generationFailedCount += 1
            },
            exitClarificationModeUi = { clearInput ->
                exitClarificationModeCalls += clearInput
            },
            rememberClarificationRetry = { request ->
                rememberRequests += request
            },
            clearClarificationRetry = { workflowId ->
                clearedWorkflowIds += workflowId
            },
            resolveClarificationRound = clarificationRounds::get,
            setStatusText = { text ->
                plainStatuses += text
            },
            setRuntimeTroubleshootingStatus = { workflowId, text, trigger ->
                troubleshootingStatuses += TroubleshootingStatusCall(
                    workflowId = workflowId,
                    text = text,
                    trigger = trigger,
                )
            },
            reloadCurrentWorkflow = { onUpdated ->
                reloadCallbacks += onUpdated
            },
        )
    }

    private data class ClarificationGeneratingCall(
        val phase: SpecPhase,
        val input: String,
        val suggestedDetails: String,
    )

    private data class ClarificationDraftCall(
        val phase: SpecPhase,
        val input: String,
        val questionsMarkdown: String,
        val suggestedDetails: String,
        val structuredQuestions: List<String>,
    )

    private data class ValidationFailureCall(
        val phase: SpecPhase,
        val validation: ValidationResult,
    )

    private data class TroubleshootingStatusCall(
        val workflowId: String?,
        val text: String?,
        val trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    )

    private companion object {
        fun preparedClarificationRequest(
            clarificationRound: Int = 1,
        ): SpecWorkflowPreparedClarificationDraftRequest {
            return SpecWorkflowPreparedClarificationDraftRequest(
                context = SpecWorkflowGenerationContext(
                    workflowId = "wf-1",
                    phase = SpecPhase.SPECIFY,
                    options = GenerationOptions(
                        providerId = "provider-1",
                        model = "model-1",
                        workflowSourceUsage = WorkflowSourceUsage(),
                        composeActionMode = ArtifactComposeActionMode.GENERATE,
                    ),
                ),
                input = "Clarify requirements",
                safeSuggestedDetails = "Reuse prior notes",
                requestOptions = GenerationOptions(
                    providerId = "provider-1",
                    model = "model-1",
                    workflowSourceUsage = WorkflowSourceUsage(),
                    composeActionMode = ArtifactComposeActionMode.GENERATE,
                    requestId = "request-1",
                ),
                composeMode = ArtifactComposeActionMode.GENERATE,
                activeRequest = SpecWorkflowActiveGenerationRequest(
                    workflowId = "wf-1",
                    providerId = "provider-1",
                    requestId = "request-1",
                ),
                seedStructuredQuestions = emptyList(),
                clarificationRound = clarificationRound,
                loadingStatusText = "Generating clarification",
                initialTimelineEntries = listOf(
                    timelineEntry("Prepare clarification", SpecWorkflowTimelineEntryState.DONE),
                    timelineEntry("Ask clarification", SpecWorkflowTimelineEntryState.ACTIVE),
                ),
            )
        }

        fun timelineEntry(
            text: String,
            state: SpecWorkflowTimelineEntryState,
        ): SpecWorkflowTimelineEntry {
            return SpecWorkflowTimelineEntry(text = text, state = state)
        }

        fun workflow(currentPhase: SpecPhase): SpecWorkflow {
            return SpecWorkflow(
                id = "wf-1",
                currentPhase = currentPhase,
                documents = mapOf(
                    currentPhase to SpecDocument(
                        id = "${currentPhase.name.lowercase()}-1",
                        phase = currentPhase,
                        content = "",
                        metadata = SpecMetadata(
                            title = currentPhase.displayName,
                            description = "",
                        ),
                    ),
                ),
                status = WorkflowStatus.IN_PROGRESS,
            )
        }
    }
}
