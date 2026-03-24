package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.file.Path

data class SpecVerificationQuickFixResult(
    val workflowId: String,
    val verificationDocumentPath: Path,
    val created: Boolean,
    val scopeTaskIds: List<String>,
)

class SpecVerificationQuickFixService(
    project: Project,
    private val storage: SpecStorage = SpecStorage.getInstance(project),
    private val artifactService: SpecArtifactService = SpecArtifactService(project),
    private val tasksService: SpecTasksService = SpecTasksService(project),
) {

    fun scaffoldVerificationArtifact(
        workflowId: String,
        scopeTaskIds: List<String>,
        trigger: String = TRIGGER_EDITOR_POPUP,
    ): SpecVerificationQuickFixResult {
        val normalizedWorkflowId = workflowId.trim()
        require(normalizedWorkflowId.isNotEmpty()) { "workflowId cannot be blank." }

        val workflow = storage.loadWorkflow(normalizedWorkflowId).getOrThrow()
        val tasks = runCatching { tasksService.parse(normalizedWorkflowId) }.getOrDefault(emptyList())
        val tasksById = tasks.associateBy(StructuredTask::id)
        val normalizedScopeIds = scopeTaskIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val scopeTasks = normalizedScopeIds
            .mapNotNull(tasksById::get)
            .sortedBy(StructuredTask::id)

        val writeResult = artifactService.writeArtifactIfMissing(
            workflowId = normalizedWorkflowId,
            stageId = StageId.VERIFY,
            content = renderSkeleton(normalizedWorkflowId, scopeTasks),
        )

        if (writeResult.created) {
            storage.appendAuditEvent(
                workflowId = normalizedWorkflowId,
                eventType = SpecAuditEventType.ARTIFACT_SCAFFOLDED,
                details = linkedMapOf(
                    "trigger" to trigger,
                    "template" to workflow.template.name,
                    "currentStage" to workflow.currentStage.name,
                    "file" to StageId.VERIFY.artifactFileName.orEmpty(),
                    "scopeTaskCount" to scopeTasks.size.toString(),
                    "scopeTaskIds" to scopeTasks.joinToString(",") { task -> task.id },
                ),
            ).getOrThrow()
        }

        return SpecVerificationQuickFixResult(
            workflowId = normalizedWorkflowId,
            verificationDocumentPath = writeResult.path,
            created = writeResult.created,
            scopeTaskIds = scopeTasks.map(StructuredTask::id),
        )
    }

    private fun renderSkeleton(workflowId: String, scopeTasks: List<StructuredTask>): String {
        return buildString {
            append("# Verification Document\n\n")
            append("## Verification Scope\n")
            append("- Workflow: `$workflowId`\n")
            if (scopeTasks.isEmpty()) {
                append("- Related tasks: none selected\n")
            } else {
                append("- Related tasks:\n")
                scopeTasks.forEach { task ->
                    append("  - `${task.id}` ${task.title}\n")
                }
            }

            append("\n## Verification Method\n")
            append("- TODO: Describe automated and manual verification approach.\n")
            append("- TODO: Describe environment summary if needed (IDE/SDK/OS).\n")

            append("\n## Commands\n")
            append("```bash\n")
            append("# TODO: add verification commands\n")
            append("```\n")

            append("\n## Result\n")
            append(
                SpecYamlCodec.encodeMap(
                    linkedMapOf(
                        "conclusion" to VerificationConclusion.WARN.name,
                        "runId" to "manual",
                        "at" to "TODO",
                        "summary" to "TODO",
                    ),
                ),
            )
        }
    }

    companion object {
        const val TRIGGER_EDITOR_POPUP: String = "editor-popup"
    }
}
