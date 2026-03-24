package com.eacape.speccodingplugin.ui.editor

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.FileChange
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.intellij.openapi.project.Project
import java.nio.file.Paths

internal data class EditorFileInsight(
    val aiChange: EditorAiChangeInsight? = null,
    val specAssociation: EditorSpecAssociationInsight? = null,
) {
    val hasContent: Boolean
        get() = aiChange != null || specAssociation != null
}

internal data class EditorAiChangeInsight(
    val changesetId: String,
    val description: String,
    val changeType: FileChange.ChangeType,
    val changedAtMillis: Long,
)

internal data class EditorSpecAssociationInsight(
    val specTaskId: String,
    val workflowStatus: WorkflowStatus? = null,
    val workflowPhase: SpecPhase? = null,
)

internal class EditorInsightResolver(
    private val recentChangesetsProvider: () -> List<Changeset>,
    private val activeBindingsProvider: () -> List<WorktreeBinding>,
    private val workflowProvider: (String) -> SpecWorkflow?,
) {

    fun resolve(filePath: String): EditorFileInsight {
        val normalizedPath = normalizePath(filePath) ?: return EditorFileInsight()
        return EditorFileInsight(
            aiChange = resolveAiChange(normalizedPath),
            specAssociation = resolveSpecAssociation(normalizedPath),
        )
    }

    private fun resolveAiChange(normalizedFilePath: String): EditorAiChangeInsight? {
        return recentChangesetsProvider()
            .asSequence()
            .sortedByDescending { it.timestamp.toEpochMilli() }
            .mapNotNull { changeset ->
                val matchedChange = changeset.changes.firstOrNull { change ->
                    normalizePath(change.filePath) == normalizedFilePath
                } ?: return@mapNotNull null
                EditorAiChangeInsight(
                    changesetId = changeset.id,
                    description = changeset.description,
                    changeType = matchedChange.changeType,
                    changedAtMillis = matchedChange.timestamp.toEpochMilli(),
                )
            }
            .firstOrNull()
    }

    private fun resolveSpecAssociation(normalizedFilePath: String): EditorSpecAssociationInsight? {
        val fromWorktreeBinding = activeBindingsProvider()
            .asSequence()
            .mapNotNull { binding ->
                val normalizedWorktreePath = normalizePath(binding.worktreePath) ?: return@mapNotNull null
                if (!isUnder(normalizedFilePath, normalizedWorktreePath)) return@mapNotNull null
                normalizedWorktreePath to binding.specTaskId
            }
            .maxByOrNull { it.first.length }
            ?.second

        val specTaskId = fromWorktreeBinding ?: resolveSpecTaskFromStoragePath(normalizedFilePath)
        if (specTaskId.isNullOrBlank()) {
            return null
        }

        val workflow = workflowProvider(specTaskId)
        return EditorSpecAssociationInsight(
            specTaskId = specTaskId,
            workflowStatus = workflow?.status,
            workflowPhase = workflow?.currentPhase,
        )
    }

    private fun resolveSpecTaskFromStoragePath(normalizedFilePath: String): String? {
        val markerIndex = normalizedFilePath.indexOf(SPEC_STORAGE_MARKER)
        if (markerIndex < 0) {
            return null
        }
        val remaining = normalizedFilePath.substring(markerIndex + SPEC_STORAGE_MARKER.length)
        val workflowId = remaining.substringBefore('/').trim()
        return workflowId.ifBlank { null }
    }

    private fun isUnder(path: String, root: String): Boolean {
        return path == root || path.startsWith("$root/")
    }

    private fun normalizePath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return runCatching {
            Paths.get(trimmed)
                .normalize()
                .toString()
                .replace('\\', '/')
                .trimEnd('/')
        }.getOrElse {
            trimmed.replace('\\', '/').trimEnd('/')
        }
    }

    companion object {
        private const val SPEC_STORAGE_MARKER = "/.spec-coding/specs/"
        private const val RECENT_CHANGESET_LIMIT = 40

        fun forProject(project: Project): EditorInsightResolver {
            val changesetStore = ChangesetStore.getInstance(project)
            val worktreeManager = WorktreeManager.getInstance(project)
            val specEngine = SpecEngine.getInstance(project)
            return EditorInsightResolver(
                recentChangesetsProvider = { changesetStore.getRecent(RECENT_CHANGESET_LIMIT) },
                activeBindingsProvider = { worktreeManager.listBindings(includeInactive = false) },
                workflowProvider = { workflowId -> specEngine.loadWorkflow(workflowId).getOrNull() },
            )
        }
    }
}

internal object EditorInsightPresentation {

    fun gutterAiTooltip(aiChange: EditorAiChangeInsight): String {
        return SpecCodingBundle.message(
            "editor.gutter.ai.tooltip",
            localizeChangeType(aiChange.changeType),
            shortChangesetId(aiChange.changesetId),
        )
    }

    fun gutterSpecTooltip(specAssociation: EditorSpecAssociationInsight): String {
        val workflowStatus = specAssociation.workflowStatus
        val workflowPhase = specAssociation.workflowPhase
        return if (workflowStatus == null || workflowPhase == null) {
            SpecCodingBundle.message(
                "editor.gutter.spec.tooltip.noWorkflow",
                specAssociation.specTaskId,
            )
        } else {
            SpecCodingBundle.message(
                "editor.gutter.spec.tooltip",
                specAssociation.specTaskId,
                localizeWorkflowStatus(workflowStatus),
                workflowPhase.displayName,
            )
        }
    }

    fun inlineHint(insight: EditorFileInsight): String? {
        if (!insight.hasContent) {
            return null
        }
        val parts = mutableListOf<String>()

        insight.aiChange?.let { aiChange ->
            parts += SpecCodingBundle.message(
                "editor.inline.aiSuggestion",
                localizeChangeType(aiChange.changeType),
                shortChangesetId(aiChange.changesetId),
            )
        }

        insight.specAssociation?.let { specAssociation ->
            val workflowStatus = specAssociation.workflowStatus
            val workflowPhase = specAssociation.workflowPhase
            if (workflowStatus == null || workflowPhase == null) {
                parts += SpecCodingBundle.message(
                    "editor.inline.specStatus.noWorkflow",
                    specAssociation.specTaskId,
                )
            } else {
                parts += SpecCodingBundle.message(
                    "editor.inline.specStatus",
                    specAssociation.specTaskId,
                    localizeWorkflowStatus(workflowStatus),
                    workflowPhase.displayName,
                )
            }
        }

        return parts.joinToString(" | ")
    }

    private fun localizeChangeType(changeType: FileChange.ChangeType): String {
        return when (changeType) {
            FileChange.ChangeType.CREATED -> SpecCodingBundle.message("editor.changeType.created")
            FileChange.ChangeType.MODIFIED -> SpecCodingBundle.message("editor.changeType.modified")
            FileChange.ChangeType.DELETED -> SpecCodingBundle.message("editor.changeType.deleted")
        }
    }

    private fun localizeWorkflowStatus(status: WorkflowStatus): String {
        return when (status) {
            WorkflowStatus.IN_PROGRESS -> SpecCodingBundle.message("spec.workflow.status.inProgress")
            WorkflowStatus.PAUSED -> SpecCodingBundle.message("spec.workflow.status.paused")
            WorkflowStatus.COMPLETED -> SpecCodingBundle.message("spec.workflow.status.completed")
            WorkflowStatus.FAILED -> SpecCodingBundle.message("spec.workflow.status.failed")
        }
    }

    private fun shortChangesetId(id: String): String {
        return id.take(8)
    }
}
