package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.ConfirmedClarificationPayload
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecClarificationDraft
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecGenerationProgress
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowGenerationCoordinatorTest {

    @Test
    fun `resolveGenerationContext should normalize selection and keep workflow compose mode`() {
        val workflow = workflow(id = "wf-1", phase = SpecPhase.DESIGN)
        val coordinator = coordinator()

        val resolution = coordinator.resolveGenerationContext(
            selectedWorkflowId = " wf-1 ",
            currentWorkflow = workflow,
            providerId = " provider-1 ",
            modelId = " model-1 ",
            workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
        )

        val success = resolution as SpecWorkflowGenerationContextResolution.Success
        assertEquals("wf-1", success.context.workflowId)
        assertEquals(SpecPhase.DESIGN, success.context.phase)
        assertEquals(
            GenerationOptions(
                providerId = "provider-1",
                model = "model-1",
                workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
                composeActionMode = workflow.resolveComposeActionMode(workflow.currentPhase),
            ),
            success.context.options,
        )
    }

    @Test
    fun `resolveGenerationContext should require provider before model`() {
        val coordinator = coordinator()

        val resolution = coordinator.resolveGenerationContext(
            selectedWorkflowId = "wf-2",
            currentWorkflow = workflow(id = "wf-2", phase = SpecPhase.SPECIFY),
            providerId = " ",
            modelId = "model-1",
            workflowSourceUsage = WorkflowSourceUsage(),
        )

        val failure = resolution as SpecWorkflowGenerationContextResolution.Failure
        assertEquals(
            SpecCodingBundle.message("spec.workflow.generation.providerRequired"),
            failure.statusMessage,
        )
    }

    @Test
    fun `buildLaunchPlan should resume generation when confirmed retry can be reused`() {
        val coordinator = coordinator()
        val retry = ClarificationRetryPayload(
            input = "seed input",
            confirmedContext = "confirmed details",
            questionsMarkdown = "## Questions",
            structuredQuestions = listOf("Q1"),
            clarificationRound = 2,
            lastError = "last error",
            confirmed = true,
            followUp = ClarificationFollowUp.GENERATION,
            requirementsRepairSections = emptyList(),
        )

        val plan = coordinator.buildLaunchPlan(
            input = "",
            pendingRetry = retry,
            context = generationContext(),
        )

        val resume = plan as SpecWorkflowGenerationLaunchPlan.ResumeGeneration
        assertEquals("wf-1", resume.workflowId)
        assertEquals("seed input", resume.input)
        assertTrue(resume.shouldShowRetryContextReuse)
        assertEquals(
            ConfirmedClarificationPayload(
                confirmedContext = "confirmed details",
                questionsMarkdown = "## Questions",
                structuredQuestions = listOf("Q1"),
                clarificationRound = 2,
            ),
            resume.options.clarificationWriteback,
        )
        assertEquals("confirmed details", resume.options.confirmedContext)
    }

    @Test
    fun `buildLaunchPlan should request clarification and reuse previous context as seed`() {
        val coordinator = coordinator()
        val retry = ClarificationRetryPayload(
            input = "existing input",
            confirmedContext = "existing context",
            questionsMarkdown = "## Questions",
            structuredQuestions = listOf("Q1", "Q2"),
            clarificationRound = 3,
            lastError = "validation failed",
            confirmed = false,
            followUp = ClarificationFollowUp.GENERATION,
            requirementsRepairSections = emptyList(),
        )

        val plan = coordinator.buildLaunchPlan(
            input = "",
            pendingRetry = retry,
            context = generationContext(),
        )

        val request = plan as SpecWorkflowGenerationLaunchPlan.RequestClarification
        assertEquals("existing input", request.input)
        assertEquals("existing context", request.suggestedDetails)
        assertEquals("existing context", request.options.confirmedContext)
        assertEquals("## Questions", request.seedQuestionsMarkdown)
        assertEquals(listOf("Q1", "Q2"), request.seedStructuredQuestions)
        assertEquals(4, request.clarificationRound)
        assertFalse(request.shouldClearProcessTimeline)
        assertTrue(request.shouldShowRetryContextReuse)
        assertEquals("validation failed", request.retryLastError)
    }

    @Test
    fun `prepareClarificationDraft should build request metadata and reused question hint`() {
        val coordinator = coordinator(currentTimeMillis = { 12L }, randomToken = { "abcd1234" })

        val prepared = coordinator.prepareClarificationDraft(
            context = generationContext(phase = SpecPhase.DESIGN),
            input = "Summarize design choices",
            options = generationOptions(composeActionMode = ArtifactComposeActionMode.REVISE),
            suggestedDetails = "",
            seedQuestionsMarkdown = "## Previous",
            seedStructuredQuestions = listOf("Q1"),
            clarificationRound = 3,
        )

        assertEquals("Summarize design choices", prepared.safeSuggestedDetails)
        assertEquals("spec-wf-1-design-12-abcd1234", prepared.requestOptions.requestId)
        assertEquals(ArtifactComposeActionMode.REVISE, prepared.composeMode)
        assertEquals(
            ArtifactComposeActionUiText.clarificationGenerating(ArtifactComposeActionMode.REVISE),
            prepared.loadingStatusText,
        )
        assertEquals(3, prepared.clarificationRound)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.process.clarify.lastRoundReused"),
                SpecCodingBundle.message("spec.workflow.process.clarify.prepare"),
                SpecCodingBundle.message("spec.workflow.process.clarify.request", 3),
            ),
            prepared.initialTimelineEntries.map(SpecWorkflowTimelineEntry::text),
        )
    }

    @Test
    fun `buildClarificationDraftResult should preserve draft questions on success`() {
        val coordinator = coordinator()
        val prepared = coordinator.prepareClarificationDraft(
            context = generationContext(),
            input = "Prompt",
            seedStructuredQuestions = listOf("Seed"),
        )

        val result = coordinator.buildClarificationDraftResult(
            prepared = prepared,
            draft = SpecClarificationDraft(
                phase = SpecPhase.SPECIFY,
                questions = listOf("What is the rollback plan?"),
                rawContent = "",
            ),
            error = null,
        )

        assertEquals(listOf("What is the rollback plan?"), result.structuredQuestions)
        assertTrue(result.questionsMarkdown.contains("What is the rollback plan?"))
        assertNull(result.errorText)
        assertNull(result.statusText)
        assertEquals(SpecWorkflowTimelineEntryState.DONE, result.timelineEntry.state)
    }

    @Test
    fun `buildClarificationDraftResult should fall back to markdown error block when drafting fails`() {
        val coordinator = coordinator()
        val prepared = coordinator.prepareClarificationDraft(
            context = generationContext(),
            input = "Prompt",
            seedStructuredQuestions = listOf("Seed question"),
        )

        val result = coordinator.buildClarificationDraftResult(
            prepared = prepared,
            draft = null,
            error = IllegalStateException("draft failed"),
        )

        assertEquals(listOf("Seed question"), result.structuredQuestions)
        assertTrue(result.questionsMarkdown.contains("draft failed"))
        assertEquals("draft failed", result.errorText)
        assertEquals(
            SpecCodingBundle.message("spec.workflow.error", "draft failed"),
            result.statusText,
        )
        assertEquals(
            SpecWorkflowRuntimeTroubleshootingTrigger.CLARIFICATION_DRAFT_FAILURE,
            result.troubleshootingTrigger,
        )
        assertEquals(SpecWorkflowTimelineEntryState.FAILED, result.timelineEntry.state)
    }

    @Test
    fun `advanceGenerationProgress should emit model call once then normalize once`() {
        val coordinator = coordinator()
        val prepared = coordinator.prepareGeneration(
            workflowId = "wf-1",
            phase = SpecPhase.SPECIFY,
            input = "Prompt",
            options = generationOptions(),
        )

        val firstUpdate = coordinator.advanceGenerationProgress(
            prepared = prepared,
            tracker = SpecWorkflowGenerationProgressTracker(),
            progress = SpecGenerationProgress.Generating(SpecPhase.SPECIFY, 0.2),
        )
        val secondUpdate = coordinator.advanceGenerationProgress(
            prepared = prepared,
            tracker = firstUpdate.tracker,
            progress = SpecGenerationProgress.Generating(SpecPhase.SPECIFY, 0.7),
        )

        assertEquals(
            listOf(ArtifactComposeActionUiText.processCall(ArtifactComposeActionMode.GENERATE, 20)),
            firstUpdate.timelineEntries.map(SpecWorkflowTimelineEntry::text),
        )
        assertTrue(firstUpdate.tracker.modelCallRecorded)
        assertFalse(firstUpdate.tracker.normalizeRecorded)
        assertEquals(
            listOf(ArtifactComposeActionUiText.processNormalize(ArtifactComposeActionMode.GENERATE)),
            secondUpdate.timelineEntries.map(SpecWorkflowTimelineEntry::text),
        )
        assertTrue(secondUpdate.tracker.modelCallRecorded)
        assertTrue(secondUpdate.tracker.normalizeRecorded)
    }

    @Test
    fun `advanceGenerationProgress should surface validation failure generation failure and interrupted retry data`() {
        val coordinator = coordinator()
        val prepared = coordinator.prepareGeneration(
            workflowId = "wf-1",
            phase = SpecPhase.SPECIFY,
            input = "Prompt",
            options = generationOptions(confirmedContext = "confirmed context"),
        )
        val validation = ValidationResult(
            valid = false,
            errors = listOf("Missing acceptance criteria"),
        )

        val validationUpdate = coordinator.advanceGenerationProgress(
            prepared = prepared,
            tracker = SpecWorkflowGenerationProgressTracker(),
            progress = SpecGenerationProgress.ValidationFailed(document(), validation),
        )
        val failedUpdate = coordinator.advanceGenerationProgress(
            prepared = prepared,
            tracker = SpecWorkflowGenerationProgressTracker(),
            progress = SpecGenerationProgress.Failed("provider unavailable", details = null),
        )
        val interruptedUpdate = coordinator.buildInterruptedProgressUpdate(
            prepared = prepared,
            interruptedMessage = "Interrupted by user",
        )

        assertEquals("confirmed context", validationUpdate.retryConfirmedContext)
        assertEquals("Missing acceptance criteria", validationUpdate.retryLastError)
        assertEquals(
            SpecCodingBundle.message("spec.workflow.validation.failed", "Missing acceptance criteria"),
            validationUpdate.statusText,
        )
        assertTrue(validationUpdate.shouldReloadWorkflow)
        assertEquals(validation, validationUpdate.validationFailure)
        assertEquals(
            SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_FAILURE,
            failedUpdate.troubleshootingTrigger,
        )
        assertEquals("provider unavailable", failedUpdate.retryLastError)
        assertEquals(
            SpecCodingBundle.message("spec.workflow.error", "provider unavailable"),
            failedUpdate.statusText,
        )
        assertTrue(failedUpdate.shouldShowGenerationFailed)
        assertEquals("confirmed context", interruptedUpdate.retryConfirmedContext)
        assertEquals("Interrupted by user", interruptedUpdate.retryLastError)
        assertTrue(interruptedUpdate.shouldShowGenerationFailed)
        assertEquals(
            SpecCodingBundle.message("spec.workflow.error", "Interrupted by user"),
            interruptedUpdate.statusText,
        )
        assertNull(interruptedUpdate.troubleshootingTrigger)
    }

    private fun coordinator(
        currentTimeMillis: () -> Long = { 100L },
        randomToken: () -> String = { "token123" },
    ): SpecWorkflowGenerationCoordinator {
        return SpecWorkflowGenerationCoordinator(
            providerDisplayName = { providerId -> "Provider $providerId" },
            renderFailureMessage = { error, fallback -> error?.message ?: fallback },
            currentTimeMillis = currentTimeMillis,
            randomToken = randomToken,
        )
    }

    private fun generationContext(
        workflowId: String = "wf-1",
        phase: SpecPhase = SpecPhase.SPECIFY,
    ): SpecWorkflowGenerationContext {
        return SpecWorkflowGenerationContext(
            workflowId = workflowId,
            phase = phase,
            options = generationOptions(
                composeActionMode = workflow(id = workflowId, phase = phase).resolveComposeActionMode(phase),
            ),
        )
    }

    private fun generationOptions(
        composeActionMode: ArtifactComposeActionMode = ArtifactComposeActionMode.GENERATE,
        confirmedContext: String? = null,
    ): GenerationOptions {
        return GenerationOptions(
            providerId = "provider-1",
            model = "model-1",
            confirmedContext = confirmedContext,
            workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
            composeActionMode = composeActionMode,
        )
    }

    private fun workflow(
        id: String,
        phase: SpecPhase,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = phase,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $id",
        )
    }

    private fun document(): SpecDocument {
        return SpecDocument(
            id = "doc-1",
            phase = SpecPhase.SPECIFY,
            content = "# Requirements",
            metadata = SpecMetadata(
                title = "Requirements",
                description = "Generated for tests",
            ),
        )
    }
}
