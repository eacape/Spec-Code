package com.eacape.speccodingplugin.ui.spec

internal enum class SpecDetailPreviewSurfaceCard {
    PREVIEW,
    EDIT,
    CLARIFY,
}

internal data class SpecDetailPreviewSurfacePlan(
    val card: SpecDetailPreviewSurfaceCard,
    val clarificationPreviewVisible: Boolean,
)

internal object SpecDetailPreviewSurfaceCoordinator {

    fun preserveCurrent(
        currentCard: SpecDetailPreviewSurfaceCard,
        hasClarificationState: Boolean,
        isClarificationGenerating: Boolean,
    ): SpecDetailPreviewSurfacePlan {
        return buildPlan(
            card = currentCard,
            hasClarificationState = hasClarificationState,
            isClarificationGenerating = isClarificationGenerating,
        )
    }

    fun forWorkflow(
        hasClarificationState: Boolean,
        isClarificationGenerating: Boolean,
    ): SpecDetailPreviewSurfacePlan {
        return if (hasClarificationState) {
            forClarification(isGenerating = isClarificationGenerating)
        } else {
            forPreview()
        }
    }

    fun forPreview(
        hasClarificationState: Boolean = false,
        isClarificationGenerating: Boolean = false,
    ): SpecDetailPreviewSurfacePlan {
        return buildPlan(
            card = SpecDetailPreviewSurfaceCard.PREVIEW,
            hasClarificationState = hasClarificationState,
            isClarificationGenerating = isClarificationGenerating,
        )
    }

    fun forEdit(): SpecDetailPreviewSurfacePlan {
        return buildPlan(
            card = SpecDetailPreviewSurfaceCard.EDIT,
            hasClarificationState = false,
            isClarificationGenerating = false,
        )
    }

    fun forClarification(isGenerating: Boolean): SpecDetailPreviewSurfacePlan {
        return buildPlan(
            card = SpecDetailPreviewSurfaceCard.CLARIFY,
            hasClarificationState = true,
            isClarificationGenerating = isGenerating,
        )
    }

    private fun buildPlan(
        card: SpecDetailPreviewSurfaceCard,
        hasClarificationState: Boolean,
        isClarificationGenerating: Boolean,
    ): SpecDetailPreviewSurfacePlan {
        return SpecDetailPreviewSurfacePlan(
            card = card,
            clarificationPreviewVisible = !hasClarificationState || !isClarificationGenerating,
        )
    }
}
