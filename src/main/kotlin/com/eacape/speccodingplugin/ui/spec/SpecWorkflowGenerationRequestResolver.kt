package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage

internal class SpecWorkflowGenerationRequestResolver(
    private val generationCoordinator: SpecWorkflowGenerationCoordinator,
    private val selectedWorkflowId: () -> String?,
    private val currentWorkflow: () -> SpecWorkflow?,
    private val resolveProviderId: () -> String?,
    private val resolveModelId: () -> String?,
    private val resolveWorkflowSourceUsage: (String) -> WorkflowSourceUsage,
    private val setStatusText: (String) -> Unit,
    private val setRuntimeTroubleshootingStatus: (
        workflowId: String?,
        text: String?,
        trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    ) -> Unit,
) {

    fun resolveSelectedWorkflow(): SpecWorkflow? {
        val workflowId = selectedWorkflowId()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val workflow = currentWorkflow()?.takeIf { current -> current.id == workflowId }
        if (workflow == null) {
            setStatusText(
                SpecCodingBundle.message(
                    "spec.workflow.error",
                    SpecCodingBundle.message("common.unknown"),
                ),
            )
        }
        return workflow
    }

    fun resolveGenerationContext(): SpecWorkflowGenerationContext? {
        val workflowId = selectedWorkflowId()
        val workflowSourceUsage = workflowId?.let(resolveWorkflowSourceUsage) ?: WorkflowSourceUsage()
        return when (
            val resolution = generationCoordinator.resolveGenerationContext(
                selectedWorkflowId = workflowId,
                currentWorkflow = currentWorkflow(),
                providerId = resolveProviderId(),
                modelId = resolveModelId(),
                workflowSourceUsage = workflowSourceUsage,
            )
        ) {
            is SpecWorkflowGenerationContextResolution.Success -> resolution.context
            is SpecWorkflowGenerationContextResolution.Failure -> {
                setRuntimeTroubleshootingStatus(
                    workflowId,
                    resolution.statusMessage,
                    SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_PRECHECK,
                )
                null
            }
        }
    }
}
