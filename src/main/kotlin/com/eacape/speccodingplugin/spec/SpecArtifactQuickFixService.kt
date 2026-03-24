package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.file.Path

data class SpecArtifactQuickFixResult(
    val workflowId: String,
    val template: WorkflowTemplate,
    val currentStage: StageId,
    val createdArtifacts: List<Path>,
    val existingArtifacts: List<Path>,
)

class SpecArtifactQuickFixService(
    project: Project,
    private val storage: SpecStorage = SpecStorage.getInstance(project),
    private val artifactService: SpecArtifactService = SpecArtifactService(project),
) {

    fun scaffoldMissingArtifacts(
        workflowId: String,
        trigger: String = TRIGGER_EDITOR_POPUP,
    ): SpecArtifactQuickFixResult {
        val workflow = storage.loadWorkflow(workflowId).getOrThrow()
        val writes = artifactService.ensureMissingArtifacts(workflow)
        val createdArtifacts = writes.filter { it.created }.map { it.path }
        val existingArtifacts = writes.filterNot { it.created }.map { it.path }

        if (createdArtifacts.isNotEmpty()) {
            storage.appendAuditEvent(
                workflowId = workflowId,
                eventType = SpecAuditEventType.ARTIFACT_SCAFFOLDED,
                details = linkedMapOf(
                    "trigger" to trigger,
                    "template" to workflow.template.name,
                    "currentStage" to workflow.currentStage.name,
                    "createdCount" to createdArtifacts.size.toString(),
                    "createdFiles" to createdArtifacts.joinToString(",") { it.fileName.toString() },
                ),
            ).getOrThrow()
        }

        return SpecArtifactQuickFixResult(
            workflowId = workflow.id,
            template = workflow.template,
            currentStage = workflow.currentStage,
            createdArtifacts = createdArtifacts,
            existingArtifacts = existingArtifacts,
        )
    }

    companion object {
        const val TRIGGER_EDITOR_POPUP: String = "editor-popup"
    }
}
