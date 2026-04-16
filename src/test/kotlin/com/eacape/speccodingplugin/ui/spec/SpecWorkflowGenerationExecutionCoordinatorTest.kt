package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecClarificationDraft
import com.eacape.speccodingplugin.spec.SpecGenerationProgress
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpecWorkflowGenerationExecutionCoordinatorTest {

    @Test
    fun `requestClarificationDraft should show generating state and apply draft result`() {
        val harness = harness()

        harness.coordinator.requestClarificationDraft(
            SpecWorkflowClarificationDraftLaunchRequest(
                context = generationContext(),
                input = "Clarify requirements",
                options = generationOptions(),
                suggestedDetails = "Clarify requirements",
                clarificationRound = 2,
            ),
        )
        harness.backgroundRunner.runNext()

        assertEquals(
            listOf("showClarificationGenerating:wf-1", "applyClarificationDraftResult:wf-1"),
            harness.ui.events,
        )
        assertEquals(
            listOf("What should be verified?"),
            harness.ui.lastClarificationResult?.structuredQuestions,
        )
        assertEquals(2, harness.ui.lastPreparedClarification?.clarificationRound)
        assertTrue(harness.cancelCalls.isEmpty())
    }

    @Test
    fun `requestClarificationDraft should skip ui callbacks when workflow is no longer selected`() {
        val harness = harness(selectedWorkflowId = "wf-2")

        harness.coordinator.requestClarificationDraft(
            SpecWorkflowClarificationDraftLaunchRequest(
                context = generationContext(),
                input = "Clarify requirements",
            ),
        )
        harness.backgroundRunner.runNext()

        assertTrue(harness.ui.events.isEmpty())
        assertNull(harness.ui.lastClarificationResult)
    }

    @Test
    fun `runGeneration should publish interrupted update when generation is cancelled`() {
        val harness = harness(
            generateCurrentPhase = { _, _, _ ->
                flow {
                    throw CancellationException("cancelled")
                }
            },
        )

        harness.coordinator.runGeneration(
            SpecWorkflowGenerationExecutionRequest(
                workflowId = "wf-1",
                phase = SpecPhase.SPECIFY,
                input = "Generate requirements",
                options = generationOptions(),
            ),
        )

        assertThrows(CancellationException::class.java) {
            harness.backgroundRunner.runNext()
        }

        val update = harness.ui.lastGenerationUpdate
        assertEquals(
            SpecCodingBundle.message("spec.workflow.generation.interrupted"),
            update?.retryLastError,
        )
        assertTrue(update?.shouldShowGenerationFailed == true)
    }

    @Test
    fun `cancelActiveRequest should cancel tracked provider request and runner handle`() {
        val harness = harness()

        harness.coordinator.requestClarificationDraft(
            SpecWorkflowClarificationDraftLaunchRequest(
                context = generationContext(),
                input = "Clarify requirements",
                options = generationOptions(),
            ),
        )
        harness.coordinator.cancelActiveRequest("Disposed")

        assertEquals(
            listOf("provider-1:spec-wf-1-specify-1-token123"),
            harness.cancelCalls,
        )
        assertEquals(listOf("Disposed"), harness.backgroundRunner.cancelledReasons)
    }

    private fun harness(
        selectedWorkflowId: String = "wf-1",
        draftClarification: (String, String, GenerationOptions) -> Result<SpecClarificationDraft> = { _, _, _ ->
            Result.success(
                SpecClarificationDraft(
                    phase = SpecPhase.SPECIFY,
                    questions = listOf("What should be verified?"),
                    rawContent = "",
                ),
            )
        },
        generateCurrentPhase: (String, String, GenerationOptions) -> Flow<SpecGenerationProgress> = { _, _, _ ->
            emptyFlow()
        },
    ): CoordinatorHarness {
        val backgroundRunner = RecordingBackgroundRunner()
        val ui = RecordingUi(selectedWorkflowId = selectedWorkflowId)
        val cancelCalls = mutableListOf<String>()
        val coordinator = SpecWorkflowGenerationExecutionCoordinator(
            backgroundRunner = backgroundRunner,
            ui = ui,
            generationCoordinator = SpecWorkflowGenerationCoordinator(
                providerDisplayName = { providerId -> "Provider $providerId" },
                renderFailureMessage = { error, fallback -> error?.message ?: fallback },
                currentTimeMillis = { 1L },
                randomToken = { "token123" },
            ),
            draftClarification = draftClarification,
            generateCurrentPhase = generateCurrentPhase,
            cancelRequestAcrossProviders = { providerId, requestId ->
                cancelCalls += "${providerId ?: "null"}:$requestId"
            },
            logClarificationDraftFailure = { _, _ -> },
        )
        return CoordinatorHarness(
            coordinator = coordinator,
            backgroundRunner = backgroundRunner,
            ui = ui,
            cancelCalls = cancelCalls,
        )
    }

    private fun generationContext(): SpecWorkflowGenerationContext {
        return SpecWorkflowGenerationContext(
            workflowId = "wf-1",
            phase = SpecPhase.SPECIFY,
            options = generationOptions(),
        )
    }

    private fun generationOptions(): GenerationOptions {
        return GenerationOptions(
            providerId = "provider-1",
            model = "model-1",
            workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1")),
            composeActionMode = ArtifactComposeActionMode.GENERATE,
        )
    }

    private data class CoordinatorHarness(
        val coordinator: SpecWorkflowGenerationExecutionCoordinator,
        val backgroundRunner: RecordingBackgroundRunner,
        val ui: RecordingUi,
        val cancelCalls: List<String>,
    )

    private class RecordingBackgroundRunner : SpecWorkflowGenerationExecutionBackgroundRunner {
        private val queuedTasks = ArrayDeque<suspend () -> Unit>()
        val cancelledReasons = mutableListOf<String>()

        override fun launch(task: suspend () -> Unit): SpecWorkflowGenerationExecutionHandle {
            queuedTasks.addLast(task)
            return SpecWorkflowGenerationExecutionHandle { reason ->
                cancelledReasons += reason
            }
        }

        fun runNext() {
            if (queuedTasks.isEmpty()) {
                error("No queued background task")
            }
            val task = queuedTasks.removeFirst()
            runBlocking {
                task()
            }
        }
    }

    private class RecordingUi(
        private val selectedWorkflowId: String,
    ) : SpecWorkflowGenerationExecutionUi {

        val events = mutableListOf<String>()
        var lastPreparedClarification: SpecWorkflowPreparedClarificationDraftRequest? = null
        var lastClarificationResult: SpecWorkflowClarificationDraftResult? = null
        var lastGenerationUpdate: SpecWorkflowGenerationProgressUpdate? = null

        override fun invokeLater(action: () -> Unit) {
            action()
        }

        override fun isWorkflowSelected(workflowId: String): Boolean {
            return selectedWorkflowId == workflowId
        }

        override fun showClarificationGenerating(prepared: SpecWorkflowPreparedClarificationDraftRequest) {
            lastPreparedClarification = prepared
            events += "showClarificationGenerating:${prepared.context.workflowId}"
        }

        override fun applyClarificationDraftResult(
            prepared: SpecWorkflowPreparedClarificationDraftRequest,
            result: SpecWorkflowClarificationDraftResult,
        ) {
            lastPreparedClarification = prepared
            lastClarificationResult = result
            events += "applyClarificationDraftResult:${prepared.context.workflowId}"
        }

        override fun applyGenerationProgress(
            workflowId: String,
            input: String,
            update: SpecWorkflowGenerationProgressUpdate,
        ) {
            lastGenerationUpdate = update
            events += "applyGenerationProgress:$workflowId"
        }

        override fun handleClarificationInterrupted(
            workflowId: String,
            input: String,
            options: GenerationOptions,
        ) {
            events += "handleClarificationInterrupted:$workflowId"
        }
    }
}
