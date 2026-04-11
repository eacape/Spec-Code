package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeMergeResult
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowWorktreeCoordinatorTest {

    @Test
    fun `createAndSwitch should create worktree switch it and report created branch`() {
        val recorder = RecordingEnvironment()
        val created = binding(
            id = "wt-1",
            specTaskId = "wf-1",
            branchName = "spec/wf-1-auth",
        )
        recorder.createResult = Result.success(created)
        recorder.switchResult = Result.success(created)
        val coordinator = coordinator(recorder)

        coordinator.createAndSwitch(
            SpecWorkflowWorktreeCreateRequest(
                specTaskId = "wf-1",
                shortName = "auth",
                baseBranch = "main",
            ),
        )

        assertEquals(
            listOf(CreateCall("wf-1", "auth", "main")),
            recorder.createCalls,
        )
        assertEquals(listOf("wt-1"), recorder.switchCalls)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.worktree.created", "spec/wf-1-auth")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `createAndSwitch should surface troubleshooting actions on switch failure`() {
        val recorder = RecordingEnvironment().apply {
            createResult = Result.success(binding(id = "wt-2", specTaskId = "wf-2"))
            switchResult = Result.failure(IllegalStateException("switch failed"))
            troubleshootingActions = defaultTroubleshootingActions()
        }
        val coordinator = coordinator(recorder)

        coordinator.createAndSwitch(
            SpecWorkflowWorktreeCreateRequest(
                specTaskId = "wf-2",
                shortName = "feature",
                baseBranch = "main",
            ),
        )

        assertEquals(listOf("wt-2"), recorder.switchCalls)
        assertEquals(
            listOf(
                FailureStatusCall(
                    text = SpecCodingBundle.message(
                        "spec.workflow.worktree.switchFailed",
                        "switch failed",
                    ),
                    actions = recorder.troubleshootingActions,
                ),
            ),
            recorder.failureStatuses,
        )
        assertEquals(
            listOf(
                TroubleshootingCall(
                    workflowId = "wf-2",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.WORKTREE_FAILURE,
                ),
            ),
            recorder.troubleshootingCalls,
        )
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `createAndSwitch should surface troubleshooting actions on create failure without switching`() {
        val recorder = RecordingEnvironment().apply {
            createResult = Result.failure(IllegalStateException("create failed"))
            troubleshootingActions = defaultTroubleshootingActions()
        }
        val coordinator = coordinator(recorder)

        coordinator.createAndSwitch(
            SpecWorkflowWorktreeCreateRequest(
                specTaskId = "wf-3",
                shortName = "feature",
                baseBranch = "main",
            ),
        )

        assertTrue(recorder.switchCalls.isEmpty())
        assertEquals(
            listOf(
                FailureStatusCall(
                    text = SpecCodingBundle.message(
                        "spec.workflow.worktree.createFailed",
                        "create failed",
                    ),
                    actions = recorder.troubleshootingActions,
                ),
            ),
            recorder.failureStatuses,
        )
        assertEquals(
            listOf(
                TroubleshootingCall(
                    workflowId = "wf-3",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.WORKTREE_FAILURE,
                ),
            ),
            recorder.troubleshootingCalls,
        )
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `mergeIntoBaseBranch should prefer active binding and report merged target branch`() {
        val recorder = RecordingEnvironment().apply {
            bindings = listOf(
                binding(
                    id = "wt-inactive",
                    specTaskId = "wf-4",
                    baseBranch = "release",
                    status = WorktreeStatus.MERGED,
                ),
                binding(
                    id = "wt-active",
                    specTaskId = "wf-4",
                    baseBranch = "main",
                    status = WorktreeStatus.ACTIVE,
                ),
            )
            mergeResult = Result.success(
                WorktreeMergeResult(
                    worktreeId = "wt-active",
                    sourceBranch = "spec/wf-4",
                    targetBranch = "main",
                    hasConflicts = false,
                    statusDescription = "merged",
                ),
            )
        }
        val coordinator = coordinator(recorder)

        coordinator.mergeIntoBaseBranch(
            SpecWorkflowWorktreeMergeRequest(workflowId = "wf-4"),
        )

        assertEquals(listOf(true), recorder.listBindingsArgs)
        assertEquals(listOf(MergeCall("wt-active", "main")), recorder.mergeCalls)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.worktree.merged", "main")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `mergeIntoBaseBranch should report no binding when workflow has no worktree`() {
        val recorder = RecordingEnvironment().apply {
            bindings = listOf(binding(id = "wt-other", specTaskId = "wf-other"))
        }
        val coordinator = coordinator(recorder)

        coordinator.mergeIntoBaseBranch(
            SpecWorkflowWorktreeMergeRequest(workflowId = "wf-5"),
        )

        assertTrue(recorder.mergeCalls.isEmpty())
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.worktree.noBinding")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `mergeIntoBaseBranch should surface troubleshooting actions on merge failure`() {
        val recorder = RecordingEnvironment().apply {
            bindings = listOf(binding(id = "wt-6", specTaskId = "wf-6", baseBranch = "main"))
            mergeResult = Result.failure(IllegalStateException("merge failed"))
            troubleshootingActions = defaultTroubleshootingActions()
        }
        val coordinator = coordinator(recorder)

        coordinator.mergeIntoBaseBranch(
            SpecWorkflowWorktreeMergeRequest(workflowId = "wf-6"),
        )

        assertEquals(listOf(MergeCall("wt-6", "main")), recorder.mergeCalls)
        assertEquals(
            listOf(
                FailureStatusCall(
                    text = SpecCodingBundle.message(
                        "spec.workflow.worktree.mergeFailed",
                        "merge failed",
                    ),
                    actions = recorder.troubleshootingActions,
                ),
            ),
            recorder.failureStatuses,
        )
        assertEquals(
            listOf(
                TroubleshootingCall(
                    workflowId = "wf-6",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.WORKTREE_FAILURE,
                ),
            ),
            recorder.troubleshootingCalls,
        )
        assertTrue(recorder.statusTexts.isEmpty())
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowWorktreeCoordinator {
        return SpecWorkflowWorktreeCoordinator(
            runIo = { action ->
                action()
            },
            invokeLater = { action ->
                action()
            },
            createWorktree = { specTaskId, shortName, baseBranch ->
                recorder.createCalls += CreateCall(specTaskId, shortName, baseBranch)
                recorder.createResult
            },
            switchWorktree = { worktreeId ->
                recorder.switchCalls += worktreeId
                recorder.switchResult
            },
            listBindings = { includeInactive ->
                recorder.listBindingsArgs += includeInactive
                recorder.bindings
            },
            mergeWorktree = { worktreeId, targetBranch ->
                recorder.mergeCalls += MergeCall(worktreeId, targetBranch)
                recorder.mergeResult
            },
            renderFailureMessage = { error ->
                error.message ?: "unknown"
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            showFailureStatus = { text, actions ->
                recorder.failureStatuses += FailureStatusCall(text, actions)
            },
            buildRuntimeTroubleshootingActions = { workflowId, trigger ->
                recorder.troubleshootingCalls += TroubleshootingCall(workflowId, trigger)
                recorder.troubleshootingActions
            },
        )
    }

    private fun defaultTroubleshootingActions(): List<SpecWorkflowTroubleshootingAction> {
        return listOf(
            SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"),
            SpecWorkflowTroubleshootingAction.OpenBundledDemo(label = "Open bundled demo"),
        )
    }

    private fun binding(
        id: String,
        specTaskId: String,
        branchName: String = "spec/$specTaskId",
        baseBranch: String = "main",
        status: WorktreeStatus = WorktreeStatus.ACTIVE,
    ): WorktreeBinding {
        return WorktreeBinding(
            id = id,
            specTaskId = specTaskId,
            branchName = branchName,
            worktreePath = "/tmp/$id",
            baseBranch = baseBranch,
            status = status,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private class RecordingEnvironment {
        var createResult: Result<WorktreeBinding> = Result.failure(IllegalStateException("missing create result"))
        var switchResult: Result<WorktreeBinding> = Result.failure(IllegalStateException("missing switch result"))
        var bindings: List<WorktreeBinding> = emptyList()
        var mergeResult: Result<WorktreeMergeResult> = Result.failure(IllegalStateException("missing merge result"))
        var troubleshootingActions: List<SpecWorkflowTroubleshootingAction> = emptyList()
        val createCalls = mutableListOf<CreateCall>()
        val switchCalls = mutableListOf<String>()
        val listBindingsArgs = mutableListOf<Boolean>()
        val mergeCalls = mutableListOf<MergeCall>()
        val statusTexts = mutableListOf<String>()
        val failureStatuses = mutableListOf<FailureStatusCall>()
        val troubleshootingCalls = mutableListOf<TroubleshootingCall>()
    }

    private data class CreateCall(
        val specTaskId: String,
        val shortName: String,
        val baseBranch: String,
    )

    private data class MergeCall(
        val worktreeId: String,
        val targetBranch: String,
    )

    private data class FailureStatusCall(
        val text: String,
        val actions: List<SpecWorkflowTroubleshootingAction>,
    )

    private data class TroubleshootingCall(
        val workflowId: String,
        val trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    )
}
