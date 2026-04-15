package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowDocumentSaveCoordinatorTest {

    @Test
    fun `save should fail through callback when workflow selection is missing`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "  "
        }
        var callbackResult: Result<SpecWorkflow>? = null

        coordinator(recorder).save(
            phase = SpecPhase.IMPLEMENT,
            content = "updated document",
            onDone = { result -> callbackResult = result },
        )

        assertEquals(0, recorder.backgroundCalls)
        assertTrue(recorder.saveCalls.isEmpty())
        assertTrue(recorder.appliedWorkflows.isEmpty())
        assertTrue(recorder.statusTexts.isEmpty())
        assertEquals(
            SpecCodingBundle.message("spec.detail.noWorkflow"),
            callbackResult?.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `save should normalize workflow id apply saved workflow state and clear status on success`() {
        val updatedWorkflow = workflow(id = "wf-save", status = WorkflowStatus.COMPLETED)
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = " wf-save "
            saveResult = Result.success(updatedWorkflow)
        }
        var callbackResult: Result<SpecWorkflow>? = null

        coordinator(recorder).save(
            phase = SpecPhase.IMPLEMENT,
            content = "updated document",
            onDone = { result -> callbackResult = result },
        )

        assertEquals(1, recorder.backgroundCalls)
        assertEquals(listOf("wf-save:IMPLEMENT:updated document"), recorder.saveCalls)
        assertEquals(listOf(updatedWorkflow), recorder.appliedWorkflows)
        assertEquals(listOf(null), recorder.statusTexts)
        assertSame(updatedWorkflow, callbackResult?.getOrNull())
    }

    @Test
    fun `save should render failure and avoid applying workflow state when save fails`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "wf-save"
            saveResult = Result.failure(IllegalStateException("save failed"))
        }
        var callbackResult: Result<SpecWorkflow>? = null

        coordinator(recorder).save(
            phase = SpecPhase.DESIGN,
            content = "broken",
            onDone = { result -> callbackResult = result },
        )

        assertEquals(1, recorder.backgroundCalls)
        assertEquals(listOf("wf-save:DESIGN:broken"), recorder.saveCalls)
        assertTrue(recorder.appliedWorkflows.isEmpty())
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:save failed"),
            ),
            recorder.statusTexts,
        )
        assertEquals("save failed", callbackResult?.exceptionOrNull()?.message)
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowDocumentSaveCoordinator {
        return SpecWorkflowDocumentSaveCoordinator(
            backgroundRunner = object : SpecWorkflowDocumentSaveBackgroundRunner {
                override fun <T> run(request: SpecWorkflowDocumentSaveBackgroundRequest<T>) {
                    recorder.backgroundCalls += 1
                    runCatching { request.task() }
                        .onSuccess(request.onSuccess)
                        .onFailure(request.onFailure)
                }
            },
            selectedWorkflowId = { recorder.selectedWorkflowId },
            saveDocumentContent = { workflowId, phase, content ->
                recorder.saveCalls += "$workflowId:${phase.name}:$content"
                recorder.saveResult
            },
            applySavedWorkflowState = { workflow ->
                recorder.appliedWorkflows += workflow
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            renderFailureMessage = { error ->
                "rendered:${error.message}"
            },
        )
    }

    private fun workflow(
        id: String,
        status: WorkflowStatus = WorkflowStatus.IN_PROGRESS,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = status,
            title = id,
            description = "document save coordinator",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private class RecordingEnvironment {
        var selectedWorkflowId: String? = null
        var saveResult: Result<SpecWorkflow> = Result.failure(IllegalStateException("missing stub"))
        var backgroundCalls: Int = 0
        val saveCalls = mutableListOf<String>()
        val appliedWorkflows = mutableListOf<SpecWorkflow>()
        val statusTexts = mutableListOf<String?>()
    }
}
