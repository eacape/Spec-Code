package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowPhaseNavigationCoordinatorTest {

    @Test
    fun `next should ignore blank workflow selection`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "  "
        }

        coordinator(recorder).next()

        assertEquals(0, recorder.backgroundCalls)
        assertTrue(recorder.nextCalls.isEmpty())
        assertTrue(recorder.goBackCalls.isEmpty())
        assertEquals(0, recorder.clearInputCalls)
        assertEquals(0, recorder.reloadCalls)
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `next should normalize workflow id and reload current workflow on success`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = " wf-next "
            nextResult = Result.success(workflow("wf-next", SpecPhase.DESIGN))
        }

        coordinator(recorder).next()

        assertEquals(1, recorder.backgroundCalls)
        assertEquals(listOf("wf-next"), recorder.nextCalls)
        assertTrue(recorder.goBackCalls.isEmpty())
        assertEquals(1, recorder.clearInputCalls)
        assertEquals(1, recorder.reloadCalls)
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `next should render failure and avoid clearing input when phase advance fails`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "wf-next"
            nextResult = Result.failure(IllegalStateException("advance failed"))
        }

        coordinator(recorder).next()

        assertEquals(1, recorder.backgroundCalls)
        assertEquals(listOf("wf-next"), recorder.nextCalls)
        assertEquals(0, recorder.clearInputCalls)
        assertEquals(0, recorder.reloadCalls)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:advance failed"),
            ),
            recorder.statusTexts,
        )
    }

    @Test
    fun `goBack should render failure and avoid reloading when rollback is blocked`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = " wf-back "
            goBackResult = Result.failure(IllegalStateException("rollback locked"))
        }

        coordinator(recorder).goBack()

        assertEquals(1, recorder.backgroundCalls)
        assertTrue(recorder.nextCalls.isEmpty())
        assertEquals(listOf("wf-back"), recorder.goBackCalls)
        assertEquals(0, recorder.clearInputCalls)
        assertEquals(0, recorder.reloadCalls)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:rollback locked"),
            ),
            recorder.statusTexts,
        )
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowPhaseNavigationCoordinator {
        return SpecWorkflowPhaseNavigationCoordinator(
            backgroundRunner = object : SpecWorkflowPhaseNavigationBackgroundRunner {
                override fun <T> run(request: SpecWorkflowPhaseNavigationBackgroundRequest<T>) {
                    recorder.backgroundCalls += 1
                    runCatching { request.task() }
                        .onSuccess(request.onSuccess)
                        .onFailure(request.onFailure)
                }
            },
            selectedWorkflowId = { recorder.selectedWorkflowId },
            proceedToNextPhase = { workflowId ->
                recorder.nextCalls += workflowId
                recorder.nextResult
            },
            goBackToPreviousPhase = { workflowId ->
                recorder.goBackCalls += workflowId
                recorder.goBackResult
            },
            clearInput = {
                recorder.clearInputCalls += 1
            },
            reloadCurrentWorkflow = {
                recorder.reloadCalls += 1
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            renderFailureMessage = { error ->
                "rendered:${error.message}"
            },
        )
    }

    private fun workflow(id: String, phase: SpecPhase): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = phase,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            description = "phase navigation coordinator",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private class RecordingEnvironment {
        var selectedWorkflowId: String? = null
        var nextResult: Result<SpecWorkflow> = Result.failure(IllegalStateException("missing next stub"))
        var goBackResult: Result<SpecWorkflow> = Result.failure(IllegalStateException("missing back stub"))
        var backgroundCalls: Int = 0
        var clearInputCalls: Int = 0
        var reloadCalls: Int = 0
        val nextCalls = mutableListOf<String>()
        val goBackCalls = mutableListOf<String>()
        val statusTexts = mutableListOf<String>()
    }
}
