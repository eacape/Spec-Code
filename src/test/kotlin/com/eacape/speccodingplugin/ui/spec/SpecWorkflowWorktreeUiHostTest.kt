package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowWorktreeUiHostTest {

    @Test
    fun `requestCreate should require selected workflow before showing dialog`() {
        val recorder = RecordingEnvironment()

        host(recorder).requestCreate()

        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.worktree.selectFirst")),
            recorder.statusTexts,
        )
        assertTrue(recorder.createDialogRequests.isEmpty())
        assertTrue(recorder.createRequests.isEmpty())
    }

    @Test
    fun `requestCreate should show dialog with workflow fallback title and delegate create request`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-1", title = "")
            suggestedBaseBranch = "release"
            createDialogResult = SpecWorkflowWorktreeCreateRequest(
                specTaskId = "wf-1",
                shortName = "feature",
                baseBranch = "develop",
            )
        }

        host(recorder).requestCreate()

        assertEquals(
            listOf(
                SpecWorkflowWorktreeCreateDialogRequest(
                    workflowId = "wf-1",
                    workflowTitle = "wf-1",
                    suggestedBaseBranch = "release",
                ),
            ),
            recorder.createDialogRequests,
        )
        assertEquals(listOf(recorder.createDialogResult!!), recorder.createRequests)
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `requestCreate should ignore cancelled dialog`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-2")
        }

        host(recorder).requestCreate()

        assertEquals(1, recorder.createDialogRequests.size)
        assertTrue(recorder.createRequests.isEmpty())
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `requestCreate should fall back to main when suggested base branch resolution fails`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-branch")
            suggestedBaseBranchFailure = IllegalStateException("load failed")
        }

        host(recorder).requestCreate()

        assertEquals(
            listOf(
                SpecWorkflowWorktreeCreateDialogRequest(
                    workflowId = "wf-branch",
                    workflowTitle = "Workflow wf-branch",
                    suggestedBaseBranch = "main",
                ),
            ),
            recorder.createDialogRequests,
        )
        assertTrue(recorder.createRequests.isEmpty())
    }

    @Test
    fun `requestMerge should require selected workflow before delegating`() {
        val recorder = RecordingEnvironment()

        host(recorder).requestMerge()

        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.worktree.selectFirst")),
            recorder.statusTexts,
        )
        assertTrue(recorder.mergeRequests.isEmpty())
    }

    @Test
    fun `requestMerge should delegate current workflow id`() {
        val recorder = RecordingEnvironment().apply {
            currentWorkflow = workflow(id = "wf-merge")
        }

        host(recorder).requestMerge()

        assertEquals(
            listOf(SpecWorkflowWorktreeMergeRequest(workflowId = "wf-merge")),
            recorder.mergeRequests,
        )
        assertTrue(recorder.statusTexts.isEmpty())
    }

    private fun host(recorder: RecordingEnvironment): SpecWorkflowWorktreeUiHost {
        return SpecWorkflowWorktreeUiHost(
            currentWorkflow = { recorder.currentWorkflow },
            resolveSuggestedBaseBranch = {
                recorder.suggestedBaseBranchFailure?.let { throw it }
                recorder.suggestedBaseBranch
            },
            showCreateDialogUi = { request ->
                recorder.createDialogRequests += request
                recorder.createDialogResult
            },
            createWorktree = { request ->
                recorder.createRequests += request
            },
            mergeWorktree = { request ->
                recorder.mergeRequests += request
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
        )
    }

    private fun workflow(
        id: String,
        title: String = "Workflow $id",
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = title,
            description = "workflow",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.REQUIREMENTS,
        )
    }

    private class RecordingEnvironment {
        var currentWorkflow: SpecWorkflow? = null
        var suggestedBaseBranch: String = "main"
        var suggestedBaseBranchFailure: Throwable? = null
        var createDialogResult: SpecWorkflowWorktreeCreateRequest? = null
        val createDialogRequests = mutableListOf<SpecWorkflowWorktreeCreateDialogRequest>()
        val createRequests = mutableListOf<SpecWorkflowWorktreeCreateRequest>()
        val mergeRequests = mutableListOf<SpecWorkflowWorktreeMergeRequest>()
        val statusTexts = mutableListOf<String>()
    }
}
