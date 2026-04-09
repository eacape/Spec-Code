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
    val firstAttemptAt: Long? = null,
    val firstSuccessAt: Long? = null,
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
        val normalizedAttemptCount = state.createAttemptCount.coerceAtLeast(0)
        val normalizedSuccessCount = state.createSuccessCount.coerceAtLeast(0)
        val normalizedLastAttemptAt = normalizeTimestamp(state.lastAttemptAt)
        val normalizedLastSuccessAt = normalizeTimestamp(state.lastSuccessAt)
        var normalizedFirstAttemptAt = normalizeTimestamp(state.firstAttemptAt)
        var normalizedFirstSuccessAt = normalizeTimestamp(state.firstSuccessAt)
        if (normalizedFirstAttemptAt <= 0L && normalizedAttemptCount > 0) {
            normalizedFirstAttemptAt = normalizedLastAttemptAt
                .takeIf { it > 0L }
                ?: normalizedLastSuccessAt.takeIf { normalizedSuccessCount > 0 && it > 0L }
                ?: 0L
        }
        if (normalizedFirstSuccessAt <= 0L && normalizedSuccessCount > 0) {
            normalizedFirstSuccessAt = normalizedLastSuccessAt.takeIf { it > 0L } ?: 0L
        }
        if (normalizedFirstSuccessAt > 0L && normalizedFirstAttemptAt <= 0L) {
            normalizedFirstAttemptAt = normalizedFirstSuccessAt
        }
        if (normalizedFirstSuccessAt > 0L && normalizedFirstAttemptAt > normalizedFirstSuccessAt) {
            normalizedFirstAttemptAt = normalizedFirstSuccessAt
        }
        this.state = state.copy(
            createAttemptCount = normalizedAttemptCount,
            createSuccessCount = normalizedSuccessCount,
            lastAttemptTemplate = normalizeTemplateName(state.lastAttemptTemplate),
            lastSuccessTemplate = normalizeTemplateName(state.lastSuccessTemplate),
            lastSuccessWorkflowId = normalizeText(state.lastSuccessWorkflowId),
            lastSuccessArtifactFileName = normalizeText(state.lastSuccessArtifactFileName),
            firstAttemptAt = normalizedFirstAttemptAt,
            firstSuccessAt = normalizedFirstSuccessAt,
            lastAttemptAt = maxOf(normalizedLastAttemptAt, normalizedFirstAttemptAt),
            lastSuccessAt = maxOf(normalizedLastSuccessAt, normalizedFirstSuccessAt),
            updatedAt = normalizeTimestamp(state.updatedAt),
        )
        if (this.state.createSuccessCount > this.state.createAttemptCount) {
            this.state.createAttemptCount = this.state.createSuccessCount
        }
        this.state.updatedAt = maxOf(
            this.state.updatedAt,
            this.state.firstAttemptAt,
            this.state.firstSuccessAt,
            this.state.lastAttemptAt,
            this.state.lastSuccessAt,
        )
    }

    @Synchronized
    fun snapshot(): SpecWorkflowFirstRunTrackingSnapshot {
        val firstAttemptAt = state.firstAttemptAt.takeIf { it > 0L }
        val firstSuccessAt = state.firstSuccessAt.takeIf { it > 0L }
        return SpecWorkflowFirstRunTrackingSnapshot(
            createAttemptCount = state.createAttemptCount,
            createSuccessCount = state.createSuccessCount,
            lastAttemptTemplate = state.lastAttemptTemplate.toWorkflowTemplateOrNull(),
            lastSuccessTemplate = state.lastSuccessTemplate.toWorkflowTemplateOrNull(),
            lastSuccessWorkflowId = state.lastSuccessWorkflowId,
            lastSuccessArtifactFileName = state.lastSuccessArtifactFileName,
            lastAttemptAt = state.lastAttemptAt.takeIf { it > 0L },
            lastSuccessAt = state.lastSuccessAt.takeIf { it > 0L },
            firstAttemptAt = firstAttemptAt,
            firstSuccessAt = firstSuccessAt,
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
            firstAttemptAt = state.firstAttemptAt.takeIf { it > 0L } ?: timestamp,
            lastAttemptAt = maxOf(state.lastAttemptAt, timestamp),
            updatedAt = maxOf(state.updatedAt, timestamp),
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
        val normalizedFirstAttemptAt = when {
            state.firstAttemptAt > 0L -> state.firstAttemptAt
            state.lastAttemptAt > 0L -> state.lastAttemptAt
            else -> timestamp
        }
        state = state.copy(
            createAttemptCount = nextAttemptCount,
            createSuccessCount = nextSuccessCount,
            firstAttemptAt = normalizedFirstAttemptAt,
            firstSuccessAt = state.firstSuccessAt.takeIf { it > 0L } ?: timestamp,
            lastAttemptTemplate = state.lastAttemptTemplate?.takeIf { it.isNotBlank() } ?: template.name,
            lastSuccessTemplate = template.name,
            lastSuccessWorkflowId = normalizedWorkflowId,
            lastSuccessArtifactFileName = firstVisibleArtifact(template),
            lastAttemptAt = maxOf(state.lastAttemptAt, normalizedFirstAttemptAt),
            lastSuccessAt = maxOf(state.lastSuccessAt, timestamp),
            updatedAt = maxOf(state.updatedAt, timestamp),
        )
    }

    data class FirstRunTrackingState(
        var createAttemptCount: Int = 0,
        var createSuccessCount: Int = 0,
        var lastAttemptTemplate: String? = null,
        var lastSuccessTemplate: String? = null,
        var lastSuccessWorkflowId: String? = null,
        var lastSuccessArtifactFileName: String? = null,
        var firstAttemptAt: Long = 0L,
        var firstSuccessAt: Long = 0L,
        var lastAttemptAt: Long = 0L,
        var lastSuccessAt: Long = 0L,
        var updatedAt: Long = 0L,
    )

    companion object {
        internal const val FIRST_VISIBLE_ARTIFACT_TARGET_MILLIS: Long = 5 * 60 * 1000L

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
