package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode

internal enum class SpecDetailPreviewStatusTone {
    MUTED,
    INFO,
    GENERATING,
}

internal data class SpecDetailPreviewStatusPlan(
    val text: String,
    val tone: SpecDetailPreviewStatusTone,
)

internal object SpecDetailPreviewStatusCoordinator {

    fun noWorkflow(): SpecDetailPreviewStatusPlan {
        return SpecDetailPreviewStatusPlan(
            text = SpecCodingBundle.message("spec.detail.noWorkflow"),
            tone = SpecDetailPreviewStatusTone.MUTED,
        )
    }

    fun clarificationHint(mode: ArtifactComposeActionMode): SpecDetailPreviewStatusPlan {
        return SpecDetailPreviewStatusPlan(
            text = ArtifactComposeActionUiText.clarificationHint(mode),
            tone = SpecDetailPreviewStatusTone.INFO,
        )
    }

    fun generating(
        mode: ArtifactComposeActionMode,
        progressPercent: Int,
        frame: String,
        isClarificationGenerating: Boolean,
    ): SpecDetailPreviewStatusPlan {
        val message = if (isClarificationGenerating) {
            ArtifactComposeActionUiText.clarificationGenerating(mode)
        } else {
            ArtifactComposeActionUiText.activeProgress(mode, progressPercent)
        }
        return SpecDetailPreviewStatusPlan(
            text = listOf(message, frame).filter { it.isNotBlank() }.joinToString(" "),
            tone = SpecDetailPreviewStatusTone.GENERATING,
        )
    }
}
