package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SpecWorkflowFirstRunStatusPresentation(
    val summary: String,
    val details: List<String>,
)

internal object SpecWorkflowFirstRunStatusCoordinator {
    private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun build(
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
    ): SpecWorkflowFirstRunStatusPresentation {
        val targetDisplay = formatDuration(SpecWorkflowFirstRunTrackingStore.FIRST_VISIBLE_ARTIFACT_TARGET_MILLIS)
        val environmentLabel = when {
            readiness.fullSpecReady -> SpecCodingBundle.message("spec.dialog.firstRun.status.environment.fullSpecReady")
            readiness.quickTaskReady -> SpecCodingBundle.message("spec.dialog.firstRun.status.environment.quickTaskReady")
            else -> SpecCodingBundle.message("spec.dialog.firstRun.status.environment.blocked")
        }
        val details = mutableListOf(
            SpecCodingBundle.message("spec.dialog.firstRun.status.detail.environment", environmentLabel),
            SpecCodingBundle.message(
                "spec.dialog.firstRun.status.detail.rate",
                tracking.createSuccessCount,
                tracking.createAttemptCount,
            ),
        )
        val summary = when {
            tracking.createSuccessCount > 0 -> {
                buildLastSuccessLine(tracking)?.let(details::add)
                buildFirstVisibleArtifactTimingLine(tracking, targetDisplay)?.let(details::add)
                SpecCodingBundle.message("spec.dialog.firstRun.status.summary.complete")
            }

            readiness.quickTaskReady && tracking.createAttemptCount > 0 -> {
                buildLastAttemptLine(tracking)?.let(details::add)
                details += SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.targetPending",
                    SpecWorkflowFirstRunTrackingStore.firstVisibleArtifact(WorkflowTemplate.QUICK_TASK),
                    targetDisplay,
                )
                details += SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.nextReady",
                    SpecWorkflowFirstRunTrackingStore.firstVisibleArtifact(WorkflowTemplate.QUICK_TASK),
                )
                SpecCodingBundle.message("spec.dialog.firstRun.status.summary.retry")
            }

            readiness.quickTaskReady -> {
                details += SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.targetPending",
                    SpecWorkflowFirstRunTrackingStore.firstVisibleArtifact(WorkflowTemplate.QUICK_TASK),
                    targetDisplay,
                )
                details += SpecCodingBundle.message(
                    "spec.dialog.firstRun.status.detail.nextReady",
                    SpecWorkflowFirstRunTrackingStore.firstVisibleArtifact(WorkflowTemplate.QUICK_TASK),
                )
                SpecCodingBundle.message("spec.dialog.firstRun.status.summary.pending")
            }

            else -> {
                details += SpecCodingBundle.message("spec.dialog.firstRun.status.detail.nextBlocked")
                SpecCodingBundle.message("spec.dialog.firstRun.status.summary.blocked")
            }
        }
        return SpecWorkflowFirstRunStatusPresentation(
            summary = summary,
            details = details,
        )
    }

    private fun buildLastAttemptLine(tracking: SpecWorkflowFirstRunTrackingSnapshot): String? {
        val template = tracking.lastAttemptTemplate ?: return null
        val attemptedAt = tracking.lastAttemptAt?.let(::formatTimestamp) ?: return null
        return SpecCodingBundle.message(
            "spec.dialog.firstRun.status.detail.lastAttempt",
            SpecWorkflowOverviewPresenter.templateLabel(template),
            attemptedAt,
        )
    }

    private fun buildLastSuccessLine(tracking: SpecWorkflowFirstRunTrackingSnapshot): String? {
        val template = tracking.lastSuccessTemplate ?: return null
        val workflowId = tracking.lastSuccessWorkflowId ?: return null
        val artifact = tracking.lastSuccessArtifactFileName ?: return null
        val succeededAt = tracking.lastSuccessAt?.let(::formatTimestamp) ?: return null
        return SpecCodingBundle.message(
            "spec.dialog.firstRun.status.detail.lastSuccess",
            SpecWorkflowOverviewPresenter.templateLabel(template),
            artifact,
            workflowId,
            succeededAt,
        )
    }

    private fun buildFirstVisibleArtifactTimingLine(
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        targetDisplay: String,
    ): String? {
        val durationMillis = firstVisibleArtifactDurationMillis(tracking) ?: return null
        val durationDisplay = formatDuration(durationMillis)
        val key = if (durationMillis <= SpecWorkflowFirstRunTrackingStore.FIRST_VISIBLE_ARTIFACT_TARGET_MILLIS) {
            "spec.dialog.firstRun.status.detail.firstSuccessWithinTarget"
        } else {
            "spec.dialog.firstRun.status.detail.firstSuccessOverTarget"
        }
        return SpecCodingBundle.message(key, durationDisplay, targetDisplay)
    }

    private fun firstVisibleArtifactDurationMillis(tracking: SpecWorkflowFirstRunTrackingSnapshot): Long? {
        val firstAttemptAt = tracking.firstAttemptAt ?: return null
        val firstSuccessAt = tracking.firstSuccessAt ?: return null
        return (firstSuccessAt - firstAttemptAt).takeIf { it >= 0L }
    }

    private fun formatTimestamp(timestampMillis: Long): String {
        return timestampFormatter.format(
            Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
        )
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
