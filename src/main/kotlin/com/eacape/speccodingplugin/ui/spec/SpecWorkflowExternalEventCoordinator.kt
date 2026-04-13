package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate
import java.util.Locale

internal sealed interface SpecWorkflowExternalEventAction {
    data class CreateWorkflow(
        val preferredTemplate: WorkflowTemplate?,
    ) : SpecWorkflowExternalEventAction

    data class OpenWorkflow(
        val request: SpecToolWindowOpenRequest,
    ) : SpecWorkflowExternalEventAction

    data class RefreshWorkflows(
        val selectWorkflowId: String?,
    ) : SpecWorkflowExternalEventAction

    data class ScheduleDocumentReload(
        val workflowId: String,
    ) : SpecWorkflowExternalEventAction
}

internal class SpecWorkflowExternalEventCoordinator(
    specDocumentFileNames: Set<String>,
) {
    private val normalizedSpecDocumentFileNames = specDocumentFileNames
        .mapTo(linkedSetOf()) { fileName -> fileName.lowercase(Locale.ROOT) }

    fun resolveCreateWorkflow(preferredTemplate: WorkflowTemplate?): SpecWorkflowExternalEventAction.CreateWorkflow {
        return SpecWorkflowExternalEventAction.CreateWorkflow(preferredTemplate)
    }

    fun resolveSelectWorkflow(workflowId: String): SpecWorkflowExternalEventAction.OpenWorkflow? {
        val normalizedWorkflowId = normalize(workflowId) ?: return null
        return SpecWorkflowExternalEventAction.OpenWorkflow(
            SpecToolWindowOpenRequest(workflowId = normalizedWorkflowId),
        )
    }

    fun resolveOpenWorkflow(request: SpecToolWindowOpenRequest): SpecWorkflowExternalEventAction.OpenWorkflow? {
        val normalizedWorkflowId = normalize(request.workflowId) ?: return null
        return SpecWorkflowExternalEventAction.OpenWorkflow(
            request.copy(
                workflowId = normalizedWorkflowId,
                taskId = normalize(request.taskId),
            ),
        )
    }

    fun resolveWorkflowChanged(event: SpecWorkflowChangedEvent): SpecWorkflowExternalEventAction.RefreshWorkflows? {
        if (event.reason == SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED) {
            return null
        }
        return SpecWorkflowExternalEventAction.RefreshWorkflows(
            selectWorkflowId = normalize(event.workflowId),
        )
    }

    fun resolveDocumentReload(
        eventPaths: List<String>,
        basePath: String?,
        selectedWorkflowId: String?,
    ): SpecWorkflowExternalEventAction.ScheduleDocumentReload? {
        if (eventPaths.isEmpty()) {
            return null
        }
        val normalizedBasePath = normalizePath(basePath) ?: return null
        val normalizedWorkflowId = normalize(selectedWorkflowId) ?: return null
        val workflowPathSegment = normalizedWorkflowId.lowercase(Locale.ROOT)
        val targetPrefix = "$normalizedBasePath/.spec-coding/specs/$workflowPathSegment/"
        val hasMatchingDocumentChange = eventPaths.asSequence()
            .mapNotNull(::normalizePath)
            .any { normalizedPath ->
                if (!normalizedPath.startsWith(targetPrefix)) {
                    return@any false
                }
                val fileName = normalizedPath.substringAfterLast('/')
                fileName in normalizedSpecDocumentFileNames
            }
        return normalizedWorkflowId.takeIf { hasMatchingDocumentChange }
            ?.let(SpecWorkflowExternalEventAction::ScheduleDocumentReload)
    }

    private fun normalizePath(value: String?): String? {
        return normalize(
            value
                ?.replace('\\', '/')
                ?.trim()
                ?.trimEnd('/'),
        )?.lowercase(Locale.ROOT)
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
