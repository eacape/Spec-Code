package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecArchiveReadOnlySummary
import com.eacape.speccodingplugin.spec.SpecArchiveResult
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowLifecycleCoordinatorTest {

    @Test
    fun `complete should normalize workflow id and refresh workflow state`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.complete(" wf-complete ")

        assertEquals(listOf("wf-complete"), recorder.completeCalls)
        assertEquals(1, recorder.reloadCalls)
        assertEquals(1, recorder.refreshCalls)
        assertEquals(
            listOf(SpecCodingBundle.message("toolwindow.spec.command.completed", "wf-complete")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `complete should surface rendered failure message`() {
        val recorder = RecordingEnvironment().apply {
            completeResult = Result.failure(IllegalStateException("complete failed"))
        }
        val coordinator = coordinator(recorder)

        coordinator.complete("wf-complete")

        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:complete failed"),
            ),
            recorder.statusTexts,
        )
        assertEquals(0, recorder.reloadCalls)
        assertEquals(0, recorder.refreshCalls)
    }

    @Test
    fun `togglePauseResume should resume paused workflow and refresh current selection`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.togglePauseResume("wf-paused", isPaused = true)

        assertEquals(listOf("wf-paused"), recorder.resumeCalls)
        assertEquals(emptyList<String>(), recorder.pauseCalls)
        assertEquals(1, recorder.reloadCalls)
        assertEquals(1, recorder.refreshCalls)
        assertEquals(emptyList<String>(), recorder.statusTexts)
    }

    @Test
    fun `togglePauseResume should surface failure instead of failing silently`() {
        val recorder = RecordingEnvironment().apply {
            pauseResult = Result.failure(IllegalStateException("pause failed"))
        }
        val coordinator = coordinator(recorder)

        coordinator.togglePauseResume("wf-active", isPaused = false)

        assertEquals(listOf("wf-active"), recorder.pauseCalls)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:pause failed"),
            ),
            recorder.statusTexts,
        )
        assertEquals(0, recorder.reloadCalls)
        assertEquals(0, recorder.refreshCalls)
    }

    @Test
    fun `archive should reject missing workflow selection before confirming`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.archive(workflowId = null, status = null)

        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.archive.selectFirst")),
            recorder.statusTexts,
        )
        assertEquals(emptyList<String>(), recorder.confirmArchiveCalls)
        assertEquals(emptyList<String>(), recorder.archiveCalls)
    }

    @Test
    fun `archive should reject non completed workflow`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.archive(workflowId = "wf-active", status = WorkflowStatus.IN_PROGRESS)

        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.archive.onlyCompleted")),
            recorder.statusTexts,
        )
        assertEquals(emptyList<String>(), recorder.confirmArchiveCalls)
        assertEquals(emptyList<String>(), recorder.archiveCalls)
    }

    @Test
    fun `archive should stop when confirmation is declined`() {
        val recorder = RecordingEnvironment().apply {
            confirmArchiveResult = false
        }
        val coordinator = coordinator(recorder)

        coordinator.archive(workflowId = " wf-archive ", status = WorkflowStatus.COMPLETED)

        assertEquals(listOf("wf-archive"), recorder.confirmArchiveCalls)
        assertEquals(emptyList<String>(), recorder.archiveCalls)
        assertEquals(emptyList<String>(), recorder.statusTexts)
    }

    @Test
    fun `archive should clear selected workflow and refresh list after success`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.archive(workflowId = "wf-archive", status = WorkflowStatus.COMPLETED)

        assertEquals(listOf("wf-archive"), recorder.confirmArchiveCalls)
        assertEquals(listOf("wf-archive"), recorder.archiveCalls)
        assertEquals(listOf("wf-archive"), recorder.clearedWorkflowIds)
        assertEquals(1, recorder.refreshCalls)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.archive.done", "wf-archive")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `archive should surface rendered failure message`() {
        val recorder = RecordingEnvironment().apply {
            archiveResult = Result.failure(IllegalStateException("archive failed"))
        }
        val coordinator = coordinator(recorder)

        coordinator.archive(workflowId = "wf-archive", status = WorkflowStatus.COMPLETED)

        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.archive.failed", "rendered:archive failed"),
            ),
            recorder.statusTexts,
        )
        assertEquals(emptyList<String>(), recorder.clearedWorkflowIds)
        assertEquals(0, recorder.refreshCalls)
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowLifecycleCoordinator {
        return SpecWorkflowLifecycleCoordinator(
            backgroundRunner = object : SpecWorkflowLifecycleBackgroundRunner {
                override fun <T> run(request: SpecWorkflowLifecycleBackgroundRequest<T>) {
                    runCatching { request.task() }
                        .onSuccess(request.onSuccess)
                        .onFailure(request.onFailure)
                }
            },
            completeWorkflow = { workflowId ->
                recorder.completeCalls += workflowId
                recorder.completeResult
            },
            pauseWorkflow = { workflowId ->
                recorder.pauseCalls += workflowId
                recorder.pauseResult
            },
            resumeWorkflow = { workflowId ->
                recorder.resumeCalls += workflowId
                recorder.resumeResult
            },
            archiveWorkflow = { workflowId ->
                recorder.archiveCalls += workflowId
                recorder.archiveResult
            },
            confirmArchive = { workflowId ->
                recorder.confirmArchiveCalls += workflowId
                recorder.confirmArchiveResult
            },
            reloadCurrentWorkflow = {
                recorder.reloadCalls += 1
            },
            refreshWorkflows = {
                recorder.refreshCalls += 1
            },
            clearOpenedWorkflowIfSelected = { workflowId ->
                recorder.clearedWorkflowIds += workflowId
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            renderFailureMessage = { error ->
                "rendered:${error.message}"
            },
        )
    }

    private class RecordingEnvironment {
        val completeCalls = mutableListOf<String>()
        val pauseCalls = mutableListOf<String>()
        val resumeCalls = mutableListOf<String>()
        val archiveCalls = mutableListOf<String>()
        val confirmArchiveCalls = mutableListOf<String>()
        val clearedWorkflowIds = mutableListOf<String>()
        val statusTexts = mutableListOf<String>()
        var reloadCalls: Int = 0
        var refreshCalls: Int = 0
        var confirmArchiveResult: Boolean = true
        var completeResult: Result<SpecWorkflow> = Result.success(workflow("wf-complete"))
        var pauseResult: Result<SpecWorkflow> = Result.success(workflow("wf-active", WorkflowStatus.PAUSED))
        var resumeResult: Result<SpecWorkflow> = Result.success(workflow("wf-paused"))
        var archiveResult: Result<SpecArchiveResult> = Result.success(
            SpecArchiveResult(
                workflowId = "wf-archive",
                archiveId = "archive-wf-archive",
                archivePath = Path.of("archives", "wf-archive.zip"),
                auditLogPath = Path.of("archives", "wf-archive.audit.json"),
                archivedAt = 0L,
                readOnlySummary = SpecArchiveReadOnlySummary(
                    filesMarkedReadOnly = 3,
                    failures = 0,
                ),
            ),
        )
    }

    companion object {
        private fun workflow(
            workflowId: String,
            status: WorkflowStatus = WorkflowStatus.IN_PROGRESS,
        ): SpecWorkflow {
            return SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.SPECIFY,
                documents = emptyMap(),
                status = status,
                title = workflowId,
                description = "",
                template = WorkflowTemplate.FULL_SPEC,
                stageStates = linkedMapOf(
                    StageId.REQUIREMENTS to StageState(
                        active = true,
                        status = StageProgress.IN_PROGRESS,
                    ),
                ),
                currentStage = StageId.REQUIREMENTS,
                verifyEnabled = true,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }
    }
}
