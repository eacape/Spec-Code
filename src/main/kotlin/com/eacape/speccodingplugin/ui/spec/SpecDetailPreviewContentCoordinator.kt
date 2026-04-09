package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal enum class SpecDetailPreviewValidationTone {
    MUTED,
    SUCCESS,
    ERROR,
}

internal data class SpecDetailPreviewValidationPlan(
    val text: String?,
    val tone: SpecDetailPreviewValidationTone,
)

internal data class SpecDetailPreviewContentPlan(
    val markdownContent: String,
    val interactivePhase: SpecPhase? = null,
    val keepGeneratingLabel: Boolean,
    val validationMessage: SpecDetailPreviewValidationPlan? = null,
)

internal object SpecDetailPreviewContentCoordinator {

    fun forActivePreview(
        workflow: SpecWorkflow,
        selectedPhase: SpecPhase?,
        workbenchArtifactBinding: SpecWorkflowStageArtifactBinding?,
        isGeneratingActive: Boolean,
        keepGeneratingIndicator: Boolean,
        revisionLockedPhase: SpecPhase?,
        isEditing: Boolean,
    ): SpecDetailPreviewContentPlan {
        return if (SpecDetailPanelViewState.isArtifactOnlyView(workbenchArtifactBinding)) {
            forWorkbenchArtifactPreview(
                binding = requireNotNull(workbenchArtifactBinding),
                isGeneratingActive = isGeneratingActive,
                keepGeneratingIndicator = keepGeneratingIndicator,
            )
        } else {
            forDocumentPreview(
                workflow = workflow,
                phase = selectedPhase ?: workflow.currentPhase,
                workbenchArtifactBinding = workbenchArtifactBinding,
                isGeneratingActive = isGeneratingActive,
                keepGeneratingIndicator = keepGeneratingIndicator,
                revisionLockedPhase = revisionLockedPhase,
                isEditing = isEditing,
            )
        }
    }

    fun forDocumentPreview(
        workflow: SpecWorkflow,
        phase: SpecPhase,
        workbenchArtifactBinding: SpecWorkflowStageArtifactBinding?,
        isGeneratingActive: Boolean,
        keepGeneratingIndicator: Boolean,
        revisionLockedPhase: SpecPhase?,
        isEditing: Boolean,
    ): SpecDetailPreviewContentPlan {
        val document = workflow.documents[phase]
        val workbenchBinding = workbenchArtifactBinding?.takeIf { it.documentPhase == phase }
        return buildDocumentPlan(
            phase = phase,
            document = document,
            workbenchBinding = workbenchBinding,
            isGeneratingActive = isGeneratingActive,
            keepGeneratingIndicator = keepGeneratingIndicator,
            revisionLockedPhase = revisionLockedPhase,
            isEditing = isEditing,
        )
    }

    fun forValidationFailure(
        markdownContent: String,
        validationMessage: String,
    ): SpecDetailPreviewContentPlan {
        return SpecDetailPreviewContentPlan(
            markdownContent = markdownContent,
            keepGeneratingLabel = false,
            validationMessage = SpecDetailPreviewValidationPlan(
                text = validationMessage,
                tone = SpecDetailPreviewValidationTone.ERROR,
            ),
        )
    }

    private fun forWorkbenchArtifactPreview(
        binding: SpecWorkflowStageArtifactBinding,
        isGeneratingActive: Boolean,
        keepGeneratingIndicator: Boolean,
    ): SpecDetailPreviewContentPlan {
        val artifactName = binding.fileName ?: binding.title
        val generating = isGeneratingActive && keepGeneratingIndicator
        return SpecDetailPreviewContentPlan(
            markdownContent = binding.previewContent
                ?: binding.emptyStateMessage
                ?: SpecCodingBundle.message("spec.detail.workbench.missing", artifactName),
            keepGeneratingLabel = generating,
            validationMessage = if (generating) {
                null
            } else {
                SpecDetailPreviewValidationPlan(
                    text = if (binding.available) {
                        SpecCodingBundle.message("spec.detail.workbench.readOnly", artifactName)
                    } else {
                        binding.unavailableMessage
                            ?: SpecCodingBundle.message("spec.detail.workbench.unavailable", artifactName)
                    },
                    tone = if (binding.available) {
                        SpecDetailPreviewValidationTone.MUTED
                    } else {
                        SpecDetailPreviewValidationTone.ERROR
                    },
                )
            },
        )
    }

    private fun buildDocumentPlan(
        phase: SpecPhase,
        document: SpecDocument?,
        workbenchBinding: SpecWorkflowStageArtifactBinding?,
        isGeneratingActive: Boolean,
        keepGeneratingIndicator: Boolean,
        revisionLockedPhase: SpecPhase?,
        isEditing: Boolean,
    ): SpecDetailPreviewContentPlan {
        val usesWorkbenchEmptyState = document != null &&
            document.content.isBlank() &&
            !workbenchBinding?.emptyStateMessage.isNullOrBlank()
        val generating = isGeneratingActive && keepGeneratingIndicator
        val validationPlan = when {
            generating -> null
            !isEditing && revisionLockedPhase == phase -> SpecDetailPreviewValidationPlan(
                text = SpecCodingBundle.message("spec.detail.revision.locked.banner", phaseStepperTitle(phase)),
                tone = SpecDetailPreviewValidationTone.MUTED,
            )

            document?.validationResult != null -> SpecDetailPreviewValidationPlan(
                text = if (document.validationResult.valid) {
                    SpecCodingBundle.message("spec.detail.validation.passed")
                } else {
                    SpecCodingBundle.message("spec.detail.validation.failed")
                },
                tone = if (document.validationResult.valid) {
                    SpecDetailPreviewValidationTone.SUCCESS
                } else {
                    SpecDetailPreviewValidationTone.ERROR
                },
            )

            document != null -> null
            else -> SpecDetailPreviewValidationPlan(
                text = workbenchBinding?.unavailableMessage,
                tone = SpecDetailPreviewValidationTone.MUTED,
            )
        }
        return SpecDetailPreviewContentPlan(
            markdownContent = when {
                usesWorkbenchEmptyState -> workbenchBinding?.emptyStateMessage.orEmpty()
                document != null -> document.content
                else -> workbenchBinding?.emptyStateMessage
                    ?: SpecCodingBundle.message("spec.detail.noDocumentForPhase", phase.displayName.lowercase())
            },
            interactivePhase = if (document != null && !usesWorkbenchEmptyState) phase else null,
            keepGeneratingLabel = generating,
            validationMessage = validationPlan,
        )
    }

    private fun phaseStepperTitle(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }
}
