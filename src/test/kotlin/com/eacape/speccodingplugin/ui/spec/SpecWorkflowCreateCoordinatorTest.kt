package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ArrayDeque

class SpecWorkflowCreateCoordinatorTest {

    @Test
    fun `create should record attempt and success when first visible artifact exists`() {
        val recorder = RecordingEnvironment()
        recorder.createResult = Result.success(workflow(id = "wf-quick", template = WorkflowTemplate.QUICK_TASK))
        recorder.firstVisibleArtifactExists = true
        val coordinator = coordinator(
            recorder = recorder,
            timestamps = listOf(1_000L, 121_000L),
        )

        val outcome = coordinator.create(createRequest(template = WorkflowTemplate.QUICK_TASK)).getOrThrow()

        assertTrue(outcome.firstVisibleArtifactMaterialized)
        assertEquals("tasks.md", outcome.expectedFirstVisibleArtifactFileName)
        assertEquals(listOf(AttemptCall(WorkflowTemplate.QUICK_TASK, 1_000L)), recorder.attemptCalls)
        assertEquals(listOf(SuccessCall("wf-quick", WorkflowTemplate.QUICK_TASK, 121_000L)), recorder.successCalls)
        assertEquals(listOf("wf-quick" to "tasks.md"), recorder.artifactExistsCalls)
    }

    @Test
    fun `create should not record first run success when the expected artifact is missing`() {
        val recorder = RecordingEnvironment()
        recorder.createResult = Result.success(workflow(id = "wf-full", template = WorkflowTemplate.FULL_SPEC))
        recorder.firstVisibleArtifactExists = false
        val coordinator = coordinator(
            recorder = recorder,
            timestamps = listOf(5_000L, 25_000L),
        )

        val outcome = coordinator.create(createRequest(template = WorkflowTemplate.FULL_SPEC)).getOrThrow()

        assertFalse(outcome.firstVisibleArtifactMaterialized)
        assertEquals("requirements.md", outcome.expectedFirstVisibleArtifactFileName)
        assertEquals(listOf(AttemptCall(WorkflowTemplate.FULL_SPEC, 5_000L)), recorder.attemptCalls)
        assertTrue(recorder.successCalls.isEmpty())
        assertEquals(listOf("wf-full" to "requirements.md"), recorder.artifactExistsCalls)
    }

    @Test
    fun `create should keep failed engine calls as attempt only`() {
        val recorder = RecordingEnvironment()
        recorder.createResult = Result.failure(IllegalStateException("create failed"))
        val coordinator = coordinator(
            recorder = recorder,
            timestamps = listOf(9_000L),
        )

        val result = coordinator.create(createRequest(template = WorkflowTemplate.DIRECT_IMPLEMENT))

        assertTrue(result.isFailure)
        assertEquals(listOf(AttemptCall(WorkflowTemplate.DIRECT_IMPLEMENT, 9_000L)), recorder.attemptCalls)
        assertTrue(recorder.successCalls.isEmpty())
        assertTrue(recorder.artifactExistsCalls.isEmpty())
    }

    private fun coordinator(
        recorder: RecordingEnvironment,
        timestamps: List<Long>,
    ): SpecWorkflowCreateCoordinator {
        val timestampQueue = ArrayDeque(timestamps)
        return SpecWorkflowCreateCoordinator(
            createWorkflow = { request ->
                recorder.requests += request
                recorder.createResult
            },
            recordCreateAttempt = { template, timestamp ->
                recorder.attemptCalls += AttemptCall(template, timestamp)
            },
            recordCreateSuccess = { workflowId, template, timestamp ->
                recorder.successCalls += SuccessCall(workflowId, template, timestamp)
            },
            firstVisibleArtifactExists = { workflowId, artifactFileName ->
                recorder.artifactExistsCalls += workflowId to artifactFileName
                recorder.firstVisibleArtifactExists
            },
            currentTimeMillis = {
                check(timestampQueue.isNotEmpty()) { "Missing timestamp for test coordinator call" }
                timestampQueue.removeFirst()
            },
        )
    }

    private fun createRequest(template: WorkflowTemplate): SpecWorkflowCreateRequest {
        return SpecWorkflowCreateRequest(
            title = "Create $template",
            description = "workflow create request",
            template = template,
            verifyEnabled = false,
            changeIntent = SpecChangeIntent.FULL,
            baselineWorkflowId = null,
        )
    }

    private fun workflow(id: String, template: WorkflowTemplate): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $id",
            description = "workflow",
            template = template,
        )
    }

    private class RecordingEnvironment {
        var createResult: Result<SpecWorkflow> = Result.failure(IllegalStateException("missing create result"))
        var firstVisibleArtifactExists: Boolean = false
        val requests = mutableListOf<SpecWorkflowCreateRequest>()
        val attemptCalls = mutableListOf<AttemptCall>()
        val successCalls = mutableListOf<SuccessCall>()
        val artifactExistsCalls = mutableListOf<Pair<String, String>>()
    }

    private data class AttemptCall(
        val template: WorkflowTemplate,
        val timestamp: Long,
    )

    private data class SuccessCall(
        val workflowId: String,
        val template: WorkflowTemplate,
        val timestamp: Long,
    )
}
