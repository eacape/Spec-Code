package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeMergeResult
import com.eacape.speccodingplugin.worktree.WorktreeStatus

internal data class SpecWorkflowWorktreeCreateRequest(
    val specTaskId: String,
    val shortName: String,
    val baseBranch: String,
)

internal data class SpecWorkflowWorktreeMergeRequest(
    val workflowId: String,
)

internal class SpecWorkflowWorktreeCoordinator(
    private val runIo: (task: () -> Unit) -> Unit,
    private val invokeLater: (action: () -> Unit) -> Unit,
    private val createWorktree: (specTaskId: String, shortName: String, baseBranch: String) -> Result<WorktreeBinding>,
    private val switchWorktree: (worktreeId: String) -> Result<WorktreeBinding>,
    private val listBindings: (includeInactive: Boolean) -> List<WorktreeBinding>,
    private val mergeWorktree: (worktreeId: String, targetBranch: String) -> Result<WorktreeMergeResult>,
    private val renderFailureMessage: (Throwable) -> String,
    private val setStatusText: (String) -> Unit,
) {

    fun createAndSwitch(request: SpecWorkflowWorktreeCreateRequest) {
        runIo {
            createWorktree(
                request.specTaskId,
                request.shortName,
                request.baseBranch,
            ).onSuccess { binding ->
                val switchResult = switchWorktree(binding.id)
                invokeLater {
                    if (switchResult.isSuccess) {
                        setStatusText(
                            SpecCodingBundle.message(
                                "spec.workflow.worktree.created",
                                binding.branchName,
                            ),
                        )
                    } else {
                        setStatusText(
                            SpecCodingBundle.message(
                                "spec.workflow.worktree.switchFailed",
                                renderFailureMessage(switchResult.exceptionOrNull() ?: IllegalStateException("unknown")),
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                invokeLater {
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.worktree.createFailed",
                            renderFailureMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun mergeIntoBaseBranch(request: SpecWorkflowWorktreeMergeRequest) {
        runIo {
            val binding = preferredBinding(request.workflowId)
            if (binding == null) {
                invokeLater {
                    setStatusText(SpecCodingBundle.message("spec.workflow.worktree.noBinding"))
                }
                return@runIo
            }

            val mergeResult = mergeWorktree(binding.id, binding.baseBranch)
            invokeLater {
                mergeResult.onSuccess {
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.worktree.merged",
                            it.targetBranch,
                        ),
                    )
                }.onFailure { error ->
                    setStatusText(
                        SpecCodingBundle.message(
                            "spec.workflow.worktree.mergeFailed",
                            renderFailureMessage(error),
                        ),
                    )
                }
            }
        }
    }

    private fun preferredBinding(workflowId: String): WorktreeBinding? {
        val bindings = listBindings(true)
        return bindings.firstOrNull { binding ->
            binding.specTaskId == workflowId && binding.status == WorktreeStatus.ACTIVE
        } ?: bindings.firstOrNull { binding ->
            binding.specTaskId == workflowId
        }
    }
}
