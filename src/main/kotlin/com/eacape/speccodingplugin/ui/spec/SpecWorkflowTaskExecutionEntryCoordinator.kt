package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode

internal data class SpecWorkflowTaskExecutionLaunchRequest(
    val workflowId: String,
    val taskId: String,
    val providerId: String?,
    val modelId: String?,
    val operationMode: OperationMode,
    val retry: Boolean,
    val auditContext: Map<String, String>,
)

internal class SpecWorkflowTaskExecutionEntryCoordinator(
    private val activeSessionId: () -> String?,
    private val findReusableWorkflowChatSessionId: (workflowId: String, preferredSessionId: String?) -> String?,
    private val providerDisplayName: (String) -> String,
    private val setStatusText: (String) -> Unit,
    private val execute: (SpecWorkflowTaskExecutionRequest) -> Unit,
) {

    fun requestExecution(request: SpecWorkflowTaskExecutionLaunchRequest): Boolean {
        val workflowId = request.workflowId.trim()
        if (workflowId.isBlank()) {
            return false
        }

        val providerId = request.providerId?.trim()
        if (providerId.isNullOrBlank()) {
            setStatusText(
                SpecCodingBundle.message("spec.toolwindow.tasks.execute.providerRequired"),
            )
            return false
        }

        val modelId = request.modelId?.trim().orEmpty()
        if (modelId.isBlank()) {
            setStatusText(
                SpecCodingBundle.message(
                    "spec.toolwindow.tasks.execute.modelRequired",
                    providerDisplayName(providerId),
                ),
            )
            return false
        }

        execute(
            SpecWorkflowTaskExecutionRequest(
                workflowId = workflowId,
                taskId = request.taskId,
                providerId = providerId,
                modelId = modelId,
                operationMode = request.operationMode,
                sessionId = resolveReusableSessionId(workflowId),
                retry = request.retry,
                auditContext = request.auditContext,
            ),
        )
        return true
    }

    private fun resolveReusableSessionId(workflowId: String): String? {
        return findReusableWorkflowChatSessionId(
            workflowId,
            activeSessionId(),
        )?.trim()?.ifBlank { null }
    }
}
