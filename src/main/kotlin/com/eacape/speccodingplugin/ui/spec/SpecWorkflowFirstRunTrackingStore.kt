package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.spec.WorkflowTemplates
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

internal data class SpecWorkflowFirstRunTrackingSnapshot(
    val createAttemptCount: Int,
    val createSuccessCount: Int,
    val lastAttemptTemplate: WorkflowTemplate?,
    val lastSuccessTemplate: WorkflowTemplate?,
    val lastSuccessWorkflowId: String?,
    val lastSuccessArtifactFileName: String?,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
)

@Service(Service.Level.PROJECT)
@State(
    name = "SpecWorkflowFirstRunTracking",
    storages = [Storage("specWorkflowFirstRunTracking.xml")],
)
internal class SpecWorkflowFirstRunTrackingStore :
    PersistentStateComponent<SpecWorkflowFirstRunTrackingStore.FirstRunTrackingState> {

    private var state = FirstRunTrackingState()

    @Synchronized
    override fun getState(): FirstRunTrackingState = state.copy()

    @Synchronized
    override fun loadState(state: FirstRunTrackingState) {
        this.state = state.copy(
            createAttemptCount = state.createAttemptCount.coerceAtLeast(0),
            createSuccessCount = state.createSuccessCount.coerceAtLeast(0),
            lastAttemptTemplate = normalizeTemplateName(state.lastAttemptTemplate),
            lastSuccessTemplate = normalizeTemplateName(state.lastSuccessTemplate),
            lastSuccessWorkflowId = normalizeText(state.lastSuccessWorkflowId),
            lastSuccessArtifactFileName = normalizeText(state.lastSuccessArtifactFileName),
            lastAttemptAt = normalizeTimestamp(state.lastAttemptAt),
            lastSuccessAt = normalizeTimestamp(state.lastSuccessAt),
            updatedAt = normalizeTimestamp(state.updatedAt),
        )
        if (this.state.createSuccessCount > this.state.createAttemptCount) {
            this.state.createAttemptCount = this.state.createSuccessCount
        }
        this.state.updatedAt = maxOf(
            this.state.updatedAt,
            this.state.lastAttemptAt,
            this.state.lastSuccessAt,
        )
    }

    @Synchronized
    fun snapshot(): SpecWorkflowFirstRunTrackingSnapshot {
        return SpecWorkflowFirstRunTrackingSnapshot(
            createAttemptCount = state.createAttemptCount,
            createSuccessCount = state.createSuccessCount,
            lastAttemptTemplate = state.lastAttemptTemplate.toWorkflowTemplateOrNull(),
            lastSuccessTemplate = state.lastSuccessTemplate.toWorkflowTemplateOrNull(),
            lastSuccessWorkflowId = state.lastSuccessWorkflowId,
            lastSuccessArtifactFileName = state.lastSuccessArtifactFileName,
            lastAttemptAt = state.lastAttemptAt.takeIf { it > 0L },
            lastSuccessAt = state.lastSuccessAt.takeIf { it > 0L },
        )
    }

    @Synchronized
    fun recordWorkflowCreateAttempt(
        template: WorkflowTemplate,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val timestamp = normalizeTimestamp(timestampMillis)
        state = state.copy(
            createAttemptCount = state.createAttemptCount + 1,
            lastAttemptTemplate = template.name,
            lastAttemptAt = timestamp,
            updatedAt = timestamp,
        )
    }

    @Synchronized
    fun recordWorkflowCreateSuccess(
        workflowId: String,
        template: WorkflowTemplate,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val normalizedWorkflowId = normalizeText(workflowId) ?: return
        val timestamp = normalizeTimestamp(timestampMillis)
        val nextSuccessCount = state.createSuccessCount + 1
        val nextAttemptCount = maxOf(state.createAttemptCount, nextSuccessCount)
        val shouldBackfillAttempt = state.lastAttemptAt <= 0L || state.lastAttemptTemplate.isNullOrBlank()
        state = state.copy(
            createAttemptCount = nextAttemptCount,
            createSuccessCount = nextSuccessCount,
            lastAttemptTemplate = if (shouldBackfillAttempt) template.name else state.lastAttemptTemplate,
            lastSuccessTemplate = template.name,
            lastSuccessWorkflowId = normalizedWorkflowId,
            lastSuccessArtifactFileName = firstVisibleArtifact(template),
            lastAttemptAt = if (shouldBackfillAttempt) timestamp else state.lastAttemptAt,
            lastSuccessAt = timestamp,
            updatedAt = timestamp,
        )
    }

    data class FirstRunTrackingState(
        var createAttemptCount: Int = 0,
        var createSuccessCount: Int = 0,
        var lastAttemptTemplate: String? = null,
        var lastSuccessTemplate: String? = null,
        var lastSuccessWorkflowId: String? = null,
        var lastSuccessArtifactFileName: String? = null,
        var lastAttemptAt: Long = 0L,
        var lastSuccessAt: Long = 0L,
        var updatedAt: Long = 0L,
    )

    companion object {
        internal fun getInstance(project: Project): SpecWorkflowFirstRunTrackingStore = project.service()

        internal fun firstVisibleArtifact(template: WorkflowTemplate): String {
            if (template == WorkflowTemplate.DIRECT_IMPLEMENT) {
                return StageId.TASKS.artifactFileName.orEmpty()
            }
            return WorkflowTemplates
                .definitionOf(template)
                .stagePlan
                .firstNotNullOfOrNull { item -> item.id.artifactFileName }
                ?: StageId.TASKS.artifactFileName.orEmpty()
        }
    }
}

private fun normalizeTimestamp(value: Long): Long {
    return value.takeIf { it > 0L } ?: 0L
}

private fun normalizeText(value: String?): String? {
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

private fun normalizeTemplateName(value: String?): String? {
    return value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.takeIf { candidate -> runCatching { WorkflowTemplate.valueOf(candidate) }.isSuccess }
}

private fun String?.toWorkflowTemplateOrNull(): WorkflowTemplate? {
    val candidate = this ?: return null
    return runCatching { WorkflowTemplate.valueOf(candidate) }.getOrNull()
}
