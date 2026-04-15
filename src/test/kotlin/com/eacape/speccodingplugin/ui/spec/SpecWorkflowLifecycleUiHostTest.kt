package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowLifecycleUiHostTest {

    @Test
    fun `requestComplete should delegate selected workflow id`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "wf-complete"
        }

        host(recorder).requestComplete()

        assertEquals(listOf("wf-complete"), recorder.completeCalls)
        assertEquals(emptyList<PauseResumeCall>(), recorder.pauseResumeCalls)
        assertEquals(emptyList<ArchiveCall>(), recorder.archiveCalls)
    }

    @Test
    fun `requestPauseResume should delegate selected workflow id with active status`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "wf-active"
            currentWorkflow = workflow(id = "wf-active", status = WorkflowStatus.IN_PROGRESS)
        }

        host(recorder).requestPauseResume()

        assertEquals(
            listOf(PauseResumeCall(workflowId = "wf-active", isPaused = false)),
            recorder.pauseResumeCalls,
        )
        assertEquals(emptyList<String>(), recorder.completeCalls)
        assertEquals(emptyList<ArchiveCall>(), recorder.archiveCalls)
    }

    @Test
    fun `requestPauseResume should delegate paused status from current workflow`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "wf-paused"
            currentWorkflow = workflow(id = "wf-paused", status = WorkflowStatus.PAUSED)
        }

        host(recorder).requestPauseResume()

        assertEquals(
            listOf(PauseResumeCall(workflowId = "wf-paused", isPaused = true)),
            recorder.pauseResumeCalls,
        )
    }

    @Test
    fun `requestArchive should delegate current workflow id and status`() {
        val recorder = RecordingEnvironment().apply {
            selectedWorkflowId = "wf-selected"
            currentWorkflow = workflow(id = "wf-archive", status = WorkflowStatus.COMPLETED)
        }

        host(recorder).requestArchive()

        assertEquals(
            listOf(ArchiveCall(workflowId = "wf-archive", status = WorkflowStatus.COMPLETED)),
            recorder.archiveCalls,
        )
        assertEquals(emptyList<String>(), recorder.completeCalls)
        assertEquals(emptyList<PauseResumeCall>(), recorder.pauseResumeCalls)
    }

    private fun host(recorder: RecordingEnvironment): SpecWorkflowLifecycleUiHost {
        return SpecWorkflowLifecycleUiHost(
            selectedWorkflowId = { recorder.selectedWorkflowId },
            currentWorkflow = { recorder.currentWorkflow },
            completeWorkflow = { workflowId ->
                recorder.completeCalls += workflowId
            },
            togglePauseResume = { workflowId, isPaused ->
                recorder.pauseResumeCalls += PauseResumeCall(workflowId, isPaused)
            },
            archiveWorkflow = { workflowId, status ->
                recorder.archiveCalls += ArchiveCall(workflowId, status)
            },
        )
    }

    private fun workflow(
        id: String,
        status: WorkflowStatus,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = status,
            title = id,
            description = "workflow",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.IMPLEMENT,
        )
    }

    private class RecordingEnvironment {
        var selectedWorkflowId: String? = null
        var currentWorkflow: SpecWorkflow? = null
        val completeCalls = mutableListOf<String?>()
        val pauseResumeCalls = mutableListOf<PauseResumeCall>()
        val archiveCalls = mutableListOf<ArchiveCall>()
    }

    private data class PauseResumeCall(
        val workflowId: String?,
        val isPaused: Boolean,
    )

    private data class ArchiveCall(
        val workflowId: String?,
        val status: WorkflowStatus?,
    )
}
