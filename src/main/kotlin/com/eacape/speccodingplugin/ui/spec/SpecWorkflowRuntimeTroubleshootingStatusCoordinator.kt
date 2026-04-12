package com.eacape.speccodingplugin.ui.spec

internal data class SpecWorkflowStatusPresentation(
    val text: String,
    val actions: List<SpecWorkflowTroubleshootingAction>,
)

internal data class SpecWorkflowRuntimeTroubleshootingStatusRequest(
    val workflowId: String?,
    val text: String?,
    val trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
)

internal class SpecWorkflowRuntimeTroubleshootingStatusCoordinator(
    private val buildActions: (
        workflowId: String,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ) -> List<SpecWorkflowTroubleshootingAction>,
) {

    fun plain(text: String?): SpecWorkflowStatusPresentation {
        return SpecWorkflowStatusPresentation(
            text = text?.trim().orEmpty(),
            actions = emptyList(),
        )
    }

    fun withActions(
        text: String?,
        actions: List<SpecWorkflowTroubleshootingAction>,
    ): SpecWorkflowStatusPresentation {
        return SpecWorkflowStatusPresentation(
            text = text?.trim().orEmpty(),
            actions = actions,
        )
    }

    fun runtime(request: SpecWorkflowRuntimeTroubleshootingStatusRequest): SpecWorkflowStatusPresentation {
        val normalizedText = request.text?.trim().orEmpty()
        if (normalizedText.isBlank()) {
            return plain(request.text)
        }
        val normalizedWorkflowId = request.workflowId?.trim().orEmpty()
        if (normalizedWorkflowId.isBlank()) {
            return plain(normalizedText)
        }
        return SpecWorkflowStatusPresentation(
            text = normalizedText,
            actions = buildActions(normalizedWorkflowId, request.trigger),
        )
    }
}
