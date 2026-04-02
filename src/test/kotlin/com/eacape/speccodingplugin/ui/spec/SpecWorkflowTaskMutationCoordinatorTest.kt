package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.TaskStatus
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.VerificationConclusion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowTaskMutationCoordinatorTest {

    @Test
    fun `transitionStatus should update service and publish refresh`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val auditContext = linkedMapOf("source" to "panel")

        coordinator.transitionStatus(
            workflowId = "wf-1",
            taskId = "T-1",
            to = TaskStatus.IN_PROGRESS,
            auditContext = auditContext,
        )

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.status.progress"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(
            TransitionStatusCall(
                workflowId = "wf-1",
                taskId = "T-1",
                to = TaskStatus.IN_PROGRESS,
                auditContext = auditContext,
            ),
            recorder.transitionStatusCall,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.status.updated", "T-1", TaskStatus.IN_PROGRESS.name)),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-1", "T-1", "spec_task_status_transition")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
    }

    @Test
    fun `updateDependsOn should persist dependency list without publishing chat refresh`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val dependsOn = listOf("T-1", "T-2")

        coordinator.updateDependsOn(
            workflowId = "wf-2",
            taskId = "T-3",
            dependsOn = dependsOn,
        )

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.progress"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(
            DependsOnCall(
                workflowId = "wf-2",
                taskId = "T-3",
                dependsOn = dependsOn,
            ),
            recorder.dependsOnCall,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.updated", "T-3")),
            recorder.statusTexts,
        )
        assertEquals(emptyList<RefreshEvent>(), recorder.refreshEvents)
        assertEquals(1, recorder.reloadCalls)
    }

    @Test
    fun `completeTask should forward related files and verification result`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val verificationResult = verificationResult(runId = "verify-1")
        val auditContext = linkedMapOf("source" to "panel", "action" to "complete")

        coordinator.completeTask(
            workflowId = "wf-3",
            taskId = "T-4",
            files = listOf("src/Main.kt", "README.md"),
            verificationResult = verificationResult,
            auditContext = auditContext,
        )

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(
            CompleteTaskCall(
                workflowId = "wf-3",
                taskId = "T-4",
                relatedFiles = listOf("src/Main.kt", "README.md"),
                verificationResult = verificationResult,
                auditContext = auditContext,
            ),
            recorder.completeTaskCall,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.complete.updated", "T-4")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-3", "T-4", "spec_task_completed")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
    }

    @Test
    fun `updateVerificationResult should store new verification result when present`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val verificationResult = verificationResult(runId = "verify-2")
        val auditContext = linkedMapOf("source" to "panel")

        coordinator.updateVerificationResult(
            workflowId = "wf-4",
            taskId = "T-5",
            verificationResult = verificationResult,
            existingVerificationResult = null,
            auditContext = auditContext,
        )

        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.tasks.verification.progress"),
            recorder.lastBackgroundTitle,
        )
        assertEquals(
            UpdateVerificationCall(
                workflowId = "wf-4",
                taskId = "T-5",
                verificationResult = verificationResult,
                auditContext = auditContext,
            ),
            recorder.updateVerificationCall,
        )
        assertNull(recorder.clearVerificationCall)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.verification.updated", "T-5")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-4", "T-5", "spec_task_verification_updated")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
    }

    @Test
    fun `updateVerificationResult should clear previous verification result when selection is removed`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)
        val existingResult = verificationResult(runId = "verify-3")
        val auditContext = linkedMapOf("source" to "panel", "action" to "clear")

        coordinator.updateVerificationResult(
            workflowId = "wf-5",
            taskId = "T-6",
            verificationResult = null,
            existingVerificationResult = existingResult,
            auditContext = auditContext,
        )

        assertNull(recorder.updateVerificationCall)
        assertEquals(
            ClearVerificationCall(
                workflowId = "wf-5",
                taskId = "T-6",
                auditContext = auditContext,
            ),
            recorder.clearVerificationCall,
        )
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.tasks.verification.cleared", "T-6")),
            recorder.statusTexts,
        )
        assertEquals(
            listOf(RefreshEvent("wf-5", "T-6", "spec_task_verification_updated")),
            recorder.refreshEvents,
        )
        assertEquals(1, recorder.reloadCalls)
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowTaskMutationCoordinator {
        return SpecWorkflowTaskMutationCoordinator(
            runBackground = { request ->
                recorder.lastBackgroundTitle = request.title
                request.task()
                request.onSuccess()
            },
            applyStatusTransition = { workflowId, taskId, to, auditContext ->
                recorder.transitionStatusCall = TransitionStatusCall(workflowId, taskId, to, auditContext)
            },
            persistDependsOn = { workflowId, taskId, dependsOn ->
                recorder.dependsOnCall = DependsOnCall(workflowId, taskId, dependsOn)
            },
            applyTaskCompletion = { workflowId, taskId, relatedFiles, verificationResult, auditContext ->
                recorder.completeTaskCall = CompleteTaskCall(
                    workflowId,
                    taskId,
                    relatedFiles,
                    verificationResult,
                    auditContext,
                )
            },
            storeVerificationResult = { workflowId, taskId, verificationResult, auditContext ->
                recorder.updateVerificationCall = UpdateVerificationCall(
                    workflowId,
                    taskId,
                    verificationResult,
                    auditContext,
                )
            },
            removeVerificationResult = { workflowId, taskId, auditContext ->
                recorder.clearVerificationCall = ClearVerificationCall(workflowId, taskId, auditContext)
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            publishWorkflowChatRefresh = { workflowId, taskId, reason ->
                recorder.refreshEvents += RefreshEvent(workflowId, taskId, reason)
            },
            reloadCurrentWorkflow = {
                recorder.reloadCalls += 1
            },
        )
    }

    private fun verificationResult(runId: String): TaskVerificationResult {
        return TaskVerificationResult(
            conclusion = VerificationConclusion.PASS,
            runId = runId,
            summary = "verified",
            at = "2026-04-02T00:00:00Z",
        )
    }

    private class RecordingEnvironment {
        var lastBackgroundTitle: String? = null
        var transitionStatusCall: TransitionStatusCall? = null
        var dependsOnCall: DependsOnCall? = null
        var completeTaskCall: CompleteTaskCall? = null
        var updateVerificationCall: UpdateVerificationCall? = null
        var clearVerificationCall: ClearVerificationCall? = null
        val statusTexts = mutableListOf<String>()
        val refreshEvents = mutableListOf<RefreshEvent>()
        var reloadCalls: Int = 0
    }

    private data class TransitionStatusCall(
        val workflowId: String,
        val taskId: String,
        val to: TaskStatus,
        val auditContext: Map<String, String>,
    )

    private data class DependsOnCall(
        val workflowId: String,
        val taskId: String,
        val dependsOn: List<String>,
    )

    private data class CompleteTaskCall(
        val workflowId: String,
        val taskId: String,
        val relatedFiles: List<String>,
        val verificationResult: TaskVerificationResult?,
        val auditContext: Map<String, String>,
    )

    private data class UpdateVerificationCall(
        val workflowId: String,
        val taskId: String,
        val verificationResult: TaskVerificationResult,
        val auditContext: Map<String, String>,
    )

    private data class ClearVerificationCall(
        val workflowId: String,
        val taskId: String,
        val auditContext: Map<String, String>,
    )

    private data class RefreshEvent(
        val workflowId: String,
        val taskId: String,
        val reason: String,
    )
}
