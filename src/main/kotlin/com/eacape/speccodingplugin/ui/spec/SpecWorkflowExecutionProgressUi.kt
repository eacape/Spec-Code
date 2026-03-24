package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.ui.chat.ExecutionTimelineParser
import com.eacape.speccodingplugin.ui.chat.StreamingTraceAssembler
import java.time.Duration
import java.time.Instant

internal data class SpecWorkflowExecutionProgressPresentation(
    val phase: ExecutionLivePhase,
    val phaseLabel: String,
    val chipLabel: String,
    val elapsedText: String,
    val lastActivityText: String,
    val detailText: String,
    val activitySummaryText: String,
    val recentActivityTrailText: String?,
)

internal object SpecWorkflowExecutionProgressUi {
    fun resolve(
        task: StructuredTask,
        liveProgress: TaskExecutionLiveProgress?,
        now: Instant = Instant.now(),
    ): SpecWorkflowExecutionProgressPresentation? {
        val snapshot = resolveSnapshot(task, liveProgress) ?: return null
        val activity = resolveRecentActivity(liveProgress, snapshot.detailText)
        return SpecWorkflowExecutionProgressPresentation(
            phase = snapshot.phase,
            phaseLabel = phaseLabel(snapshot.phase),
            chipLabel = chipLabel(snapshot.phase),
            elapsedText = formatDuration(now, snapshot.startedAt),
            lastActivityText = formatAge(now, snapshot.lastUpdatedAt),
            detailText = snapshot.detailText,
            activitySummaryText = activity.summaryText,
            recentActivityTrailText = activity.trailText,
        )
    }

    private fun resolveSnapshot(
        task: StructuredTask,
        liveProgress: TaskExecutionLiveProgress?,
    ): ProgressSnapshot? {
        val normalizedLiveProgress = liveProgress?.takeIf { it.phase != ExecutionLivePhase.TERMINAL }
        if (normalizedLiveProgress != null) {
            return ProgressSnapshot(
                phase = normalizedLiveProgress.phase,
                startedAt = normalizedLiveProgress.startedAt,
                lastUpdatedAt = normalizedLiveProgress.lastUpdatedAt,
                detailText = normalizedLiveProgress.lastDetail
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: defaultDetail(normalizedLiveProgress.phase),
            )
        }

        val activeRun = task.activeExecutionRun ?: return null
        if (activeRun.status.isTerminal()) {
            return null
        }
        val phase = when (activeRun.status) {
            TaskExecutionRunStatus.QUEUED -> ExecutionLivePhase.QUEUED
            TaskExecutionRunStatus.RUNNING -> ExecutionLivePhase.REQUEST_DISPATCHED
            TaskExecutionRunStatus.WAITING_CONFIRMATION -> ExecutionLivePhase.WAITING_CONFIRMATION
            TaskExecutionRunStatus.FAILED,
            TaskExecutionRunStatus.CANCELLED,
            TaskExecutionRunStatus.SUCCEEDED,
            -> return null
        }
        val startedAt = parseInstant(activeRun.startedAt)
        return ProgressSnapshot(
            phase = phase,
            startedAt = startedAt,
            lastUpdatedAt = parseInstant(activeRun.finishedAt ?: activeRun.startedAt),
            detailText = activeRun.summary?.trim()?.takeIf(String::isNotBlank) ?: defaultDetail(phase),
        )
    }

    private fun phaseLabel(phase: ExecutionLivePhase): String {
        return when (phase) {
            ExecutionLivePhase.QUEUED ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.queued")

            ExecutionLivePhase.PREPARING_CONTEXT ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.preparingContext")

            ExecutionLivePhase.REQUEST_DISPATCHED ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.requestDispatched")

            ExecutionLivePhase.STREAMING ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.streaming")

            ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.waitingConfirmation")

            ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.cancelling")

            ExecutionLivePhase.TERMINAL ->
                SpecCodingBundle.message("spec.toolwindow.execution.phase.terminal")
        }
    }

    private fun chipLabel(phase: ExecutionLivePhase): String {
        return when (phase) {
            ExecutionLivePhase.QUEUED ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.queued")

            ExecutionLivePhase.PREPARING_CONTEXT ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.preparingContext")

            ExecutionLivePhase.REQUEST_DISPATCHED,
            ExecutionLivePhase.STREAMING,
            -> SpecCodingBundle.message("spec.toolwindow.execution.chip.running")

            ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.waitingConfirmation")

            ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.cancelling")

            ExecutionLivePhase.TERMINAL ->
                SpecCodingBundle.message("spec.toolwindow.execution.chip.terminal")
        }
    }

    private fun defaultDetail(phase: ExecutionLivePhase): String {
        return when (phase) {
            ExecutionLivePhase.QUEUED ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.queued")

            ExecutionLivePhase.PREPARING_CONTEXT ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.preparingContext")

            ExecutionLivePhase.REQUEST_DISPATCHED ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.requestDispatched")

            ExecutionLivePhase.STREAMING ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.streaming")

            ExecutionLivePhase.WAITING_CONFIRMATION ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.waitingConfirmation")

            ExecutionLivePhase.CANCELLING ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.cancelling")

            ExecutionLivePhase.TERMINAL ->
                SpecCodingBundle.message("spec.toolwindow.execution.detail.terminal")
        }
    }

    private fun formatDuration(
        now: Instant,
        startedAt: Instant,
    ): String {
        val duration = Duration.between(startedAt, now).coerceAtLeast(Duration.ZERO)
        val totalSeconds = duration.seconds
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun formatAge(
        now: Instant,
        at: Instant,
    ): String {
        val duration = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        val totalSeconds = duration.seconds
        return when {
            totalSeconds >= 86_400 -> "${totalSeconds / 86_400}d ago"
            totalSeconds >= 3_600 -> "${totalSeconds / 3_600}h ago"
            totalSeconds >= 60 -> "${totalSeconds / 60}m ago"
            else -> "${totalSeconds}s ago"
        }
    }

    private fun parseInstant(value: String?): Instant {
        val normalized = value?.trim().orEmpty()
        return runCatching { Instant.parse(normalized) }.getOrElse { Instant.now() }
    }

    private fun resolveRecentActivity(
        liveProgress: TaskExecutionLiveProgress?,
        fallbackDetail: String,
    ): ActivityPresentation {
        if (liveProgress == null) {
            return ActivityPresentation(
                summaryText = fallbackDetail,
                trailText = null,
            )
        }

        val traceAssembler = StreamingTraceAssembler()
        liveProgress.recentEvents.forEach(traceAssembler::onStructuredEvent)
        val traceItems = traceAssembler
            .snapshot(
                content = "",
                includeRawContent = false,
                finalizeRunningItems = liveProgress.phase == ExecutionLivePhase.TERMINAL,
            )
            .items
        val preferredActivities = traceItems
            .mapNotNull(::formatPreferredActivity)
            .takeLast(MAX_RECENT_ACTIVITY_ITEMS)
        val fallbackActivities = traceItems
            .mapNotNull(::formatFallbackActivity)
            .takeLast(MAX_RECENT_ACTIVITY_ITEMS)
        val recentActivities = (preferredActivities.ifEmpty { fallbackActivities })
            .map(::compactActivityLine)

        if (recentActivities.isEmpty()) {
            return ActivityPresentation(
                summaryText = fallbackDetail,
                trailText = null,
            )
        }

        val summaryText = recentActivities.last()
        val trailText = when {
            recentActivities.size > 1 -> compactActivityTrail(recentActivities.joinToString(" -> "))
            recentActivities.single() != fallbackDetail -> recentActivities.single()
            else -> null
        }
        return ActivityPresentation(
            summaryText = summaryText,
            trailText = trailText,
        )
    }

    private fun formatPreferredActivity(item: StreamingTraceAssembler.TraceItem): String? {
        val detail = item.detail.trim().takeIf(String::isNotBlank) ?: return null
        return when (item.kind) {
            ExecutionTimelineParser.Kind.READ ->
                SpecCodingBundle.message("spec.toolwindow.execution.activity.read", detail)

            ExecutionTimelineParser.Kind.EDIT ->
                SpecCodingBundle.message("spec.toolwindow.execution.activity.edit", detail)

            ExecutionTimelineParser.Kind.VERIFY ->
                SpecCodingBundle.message("spec.toolwindow.execution.activity.verify", detail)

            ExecutionTimelineParser.Kind.TASK -> detail

            ExecutionTimelineParser.Kind.THINK,
            ExecutionTimelineParser.Kind.TOOL,
            ExecutionTimelineParser.Kind.OUTPUT,
            -> null
        }
    }

    private fun formatFallbackActivity(item: StreamingTraceAssembler.TraceItem): String? {
        val detail = item.detail.trim().takeIf(String::isNotBlank) ?: return null
        return when (item.kind) {
            ExecutionTimelineParser.Kind.TOOL ->
                SpecCodingBundle.message("spec.toolwindow.execution.activity.tool", detail)

            ExecutionTimelineParser.Kind.OUTPUT -> detail

            else -> null
        }
    }

    private fun compactActivityLine(value: String): String {
        return compactText(value, MAX_ACTIVITY_LINE_LENGTH)
    }

    private fun compactActivityTrail(value: String): String {
        return compactText(value, MAX_ACTIVITY_TRAIL_LENGTH)
    }

    private fun compactText(
        value: String,
        maxLength: Int,
    ): String {
        val normalized = value
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLength) {
            return normalized
        }
        return normalized.take(maxLength - 3).trimEnd() + "..."
    }

    private data class ProgressSnapshot(
        val phase: ExecutionLivePhase,
        val startedAt: Instant,
        val lastUpdatedAt: Instant,
        val detailText: String,
    )

    private data class ActivityPresentation(
        val summaryText: String,
        val trailText: String?,
    )

    private const val MAX_RECENT_ACTIVITY_ITEMS = 3
    private const val MAX_ACTIVITY_LINE_LENGTH = 72
    private const val MAX_ACTIVITY_TRAIL_LENGTH = 180
}
