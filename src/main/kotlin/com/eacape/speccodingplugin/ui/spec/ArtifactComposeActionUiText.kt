package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus

internal object ArtifactComposeActionUiText {
    fun actionLabel(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(actionLabelKey(mode))
    }

    fun actionLabelKey(mode: ArtifactComposeActionMode): String {
        return when (mode) {
            ArtifactComposeActionMode.GENERATE -> "spec.detail.generate"
            ArtifactComposeActionMode.REVISE -> "spec.detail.revise"
        }
    }

    fun clarificationConfirmLabel(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(clarificationConfirmLabelKey(mode))
    }

    fun clarificationConfirmLabelKey(mode: ArtifactComposeActionMode): String {
        return when (mode) {
            ArtifactComposeActionMode.GENERATE -> "spec.detail.clarify.confirmGenerate"
            ArtifactComposeActionMode.REVISE -> "spec.detail.clarify.confirmRevise"
        }
    }

    fun inputPlaceholder(
        mode: ArtifactComposeActionMode,
        phase: SpecPhase?,
        isClarifying: Boolean,
        checklistMode: Boolean,
    ): String {
        val key = when {
            checklistMode -> when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.detail.clarify.input.placeholder.checklist"
                ArtifactComposeActionMode.REVISE -> "spec.detail.clarify.input.placeholder.checklist.revise"
            }

            isClarifying -> when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.detail.clarify.input.placeholder"
                ArtifactComposeActionMode.REVISE -> "spec.detail.clarify.input.placeholder.revise"
            }

            phase == SpecPhase.DESIGN -> when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.detail.input.placeholder.design"
                ArtifactComposeActionMode.REVISE -> "spec.detail.input.placeholder.design.revise"
            }

            phase == SpecPhase.IMPLEMENT -> when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.detail.input.placeholder.implement"
                ArtifactComposeActionMode.REVISE -> "spec.detail.input.placeholder.implement.revise"
            }

            mode == ArtifactComposeActionMode.REVISE -> "spec.detail.input.placeholder.revise"
            else -> "spec.detail.input.placeholder"
        }
        return SpecCodingBundle.message(key)
    }

    fun inputRequired(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(
            when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.detail.input.required"
                ArtifactComposeActionMode.REVISE -> "spec.detail.input.required.revise"
            },
        )
    }

    fun clarificationHint(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(
            when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.workflow.clarify.hint"
                ArtifactComposeActionMode.REVISE -> "spec.workflow.clarify.hint.revise"
            },
        )
    }

    fun clarificationGenerating(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(
            when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.workflow.clarify.generating"
                ArtifactComposeActionMode.REVISE -> "spec.workflow.clarify.generating.revise"
            },
        )
    }

    fun clarificationSkippedProceed(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(
            when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.workflow.clarify.skippedProceed"
                ArtifactComposeActionMode.REVISE -> "spec.workflow.clarify.skippedProceed.revise"
            },
        )
    }

    fun clarificationCancelled(mode: ArtifactComposeActionMode): String {
        return SpecCodingBundle.message(
            when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.workflow.clarify.cancelled"
                ArtifactComposeActionMode.REVISE -> "spec.workflow.clarify.cancelled.revise"
            },
        )
    }

    fun activeProgress(mode: ArtifactComposeActionMode, progress: Int): String {
        return SpecCodingBundle.message(
            when (mode) {
                ArtifactComposeActionMode.GENERATE -> "spec.detail.generating.percent"
                ArtifactComposeActionMode.REVISE -> "spec.detail.revising.percent"
            },
            progress,
        )
    }

    fun processPrepare(mode: ArtifactComposeActionMode): String = SpecCodingBundle.message(processKey(mode, "prepare"))

    fun processCall(mode: ArtifactComposeActionMode, progress: Int): String =
        SpecCodingBundle.message(processKey(mode, "call"), progress)

    fun processNormalize(mode: ArtifactComposeActionMode): String = SpecCodingBundle.message(processKey(mode, "normalize"))

    fun processValidate(mode: ArtifactComposeActionMode): String = SpecCodingBundle.message(processKey(mode, "validate"))

    fun processSave(mode: ArtifactComposeActionMode): String = SpecCodingBundle.message(processKey(mode, "save"))

    fun processCompleted(mode: ArtifactComposeActionMode): String = SpecCodingBundle.message(processKey(mode, "completed"))

    fun processValidationFailed(mode: ArtifactComposeActionMode, error: String): String =
        SpecCodingBundle.message(processKey(mode, "validationFailed"), error)

    fun processFailed(mode: ArtifactComposeActionMode, error: String): String =
        SpecCodingBundle.message(processKey(mode, "failed"), error)

    fun primaryActionDisabledReason(
        mode: ArtifactComposeActionMode,
        status: WorkflowStatus,
        isGeneratingActive: Boolean,
        isEditing: Boolean,
    ): String? {
        return when {
            isGeneratingActive -> SpecCodingBundle.message(
                when (mode) {
                    ArtifactComposeActionMode.GENERATE -> "spec.detail.action.disabled.running.generate"
                    ArtifactComposeActionMode.REVISE -> "spec.detail.action.disabled.running.revise"
                },
            )

            status != WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message(
                when (mode) {
                    ArtifactComposeActionMode.GENERATE -> "spec.detail.action.disabled.status.generate"
                    ArtifactComposeActionMode.REVISE -> "spec.detail.action.disabled.status.revise"
                },
                workflowStatusLabel(status),
            )

            isEditing -> SpecCodingBundle.message(
                when (mode) {
                    ArtifactComposeActionMode.GENERATE -> "spec.detail.action.disabled.editing.generate"
                    ArtifactComposeActionMode.REVISE -> "spec.detail.action.disabled.editing.revise"
                },
            )

            else -> null
        }
    }

    fun clarificationConfirmDisabledReason(
        mode: ArtifactComposeActionMode,
        status: WorkflowStatus,
        isGeneratingActive: Boolean,
        clarificationLocked: Boolean,
    ): String? {
        return when {
            isGeneratingActive -> primaryActionDisabledReason(
                mode = mode,
                status = status,
                isGeneratingActive = true,
                isEditing = false,
            )

            status != WorkflowStatus.IN_PROGRESS -> primaryActionDisabledReason(
                mode = mode,
                status = status,
                isGeneratingActive = false,
                isEditing = false,
            )

            clarificationLocked -> SpecCodingBundle.message(
                when (mode) {
                    ArtifactComposeActionMode.GENERATE -> "spec.detail.clarify.confirm.disabled.generate"
                    ArtifactComposeActionMode.REVISE -> "spec.detail.clarify.confirm.disabled.revise"
                },
            )

            else -> null
        }
    }

    private fun processKey(mode: ArtifactComposeActionMode, suffix: String): String {
        return when (mode) {
            ArtifactComposeActionMode.GENERATE -> "spec.workflow.process.generate.$suffix"
            ArtifactComposeActionMode.REVISE -> "spec.workflow.process.revise.$suffix"
        }
    }

    private fun workflowStatusLabel(status: WorkflowStatus): String {
        return SpecCodingBundle.message(
            when (status) {
                WorkflowStatus.IN_PROGRESS -> "spec.workflow.status.inProgress"
                WorkflowStatus.PAUSED -> "spec.workflow.status.paused"
                WorkflowStatus.COMPLETED -> "spec.workflow.status.completed"
                WorkflowStatus.FAILED -> "spec.workflow.status.failed"
            },
        )
    }
}
