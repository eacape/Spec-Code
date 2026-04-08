package com.eacape.speccodingplugin.telemetry

import kotlin.math.roundToInt

internal enum class HookWatcherTelemetrySeverity {
    SKIP,
    INFO,
    WARN,
}

internal object HookWatcherTelemetryThresholds {
    const val INFO_SLOW_POLL_MS = 250L
    const val WARN_SLOW_POLL_MS = 1_000L
    const val SUMMARY_LOG_INTERVAL_POLLS = 20L
}

internal enum class HookWatcherPollOutcome {
    SKIPPED_NO_BASE_PATH,
    SKIPPED_NO_GIT_DIR,
    GIT_COMMAND_FAILED,
    INITIALIZED,
    UNCHANGED,
    NON_COMMIT_HEAD_CHANGE,
    TRIGGERED,
    FAILED,
}

internal fun determineHookWatcherTelemetrySeverity(
    elapsedMs: Long,
    infoThresholdMs: Long = HookWatcherTelemetryThresholds.INFO_SLOW_POLL_MS,
    warnThresholdMs: Long = HookWatcherTelemetryThresholds.WARN_SLOW_POLL_MS,
): HookWatcherTelemetrySeverity {
    return when {
        elapsedMs >= warnThresholdMs -> HookWatcherTelemetrySeverity.WARN
        elapsedMs >= infoThresholdMs -> HookWatcherTelemetrySeverity.INFO
        else -> HookWatcherTelemetrySeverity.SKIP
    }
}

internal fun estimateHookWatcherWorkspacePollsPerMinute(
    openProjectCount: Int,
    configuredPollIntervalMs: Long,
): Int {
    if (openProjectCount <= 0 || configuredPollIntervalMs <= 0L) {
        return 0
    }
    return ((openProjectCount.toDouble() * 60_000.0) / configuredPollIntervalMs.toDouble()).roundToInt()
}

internal fun estimateHookWatcherWorkspaceGitCommandsPerMinute(
    workspacePollsPerMinute: Int,
    averageGitCommandsPerPoll: Double,
): Int {
    if (workspacePollsPerMinute <= 0 || averageGitCommandsPerPoll <= 0.0) {
        return 0
    }
    return (workspacePollsPerMinute.toDouble() * averageGitCommandsPerPoll).roundToInt()
}

internal data class HookWatcherPollObservation(
    val outcome: HookWatcherPollOutcome,
    val elapsedMs: Long,
    val openProjectCount: Int,
    val gitCommandCount: Int,
    val failedGitCommandCount: Int,
    val timedOutGitCommandCount: Int,
    val effectivePollIntervalMs: Long = 0L,
)

internal data class HookWatcherSlowPollTelemetry(
    val outcome: HookWatcherPollOutcome,
    val elapsedMs: Long,
    val gitCommandCount: Int,
    val failedGitCommandCount: Int,
    val timedOutGitCommandCount: Int,
    val openProjectCount: Int,
    val configuredPollIntervalMs: Long,
    val effectivePollIntervalMs: Long,
    val workspacePollsPerMinute: Int,
    val estimatedWorkspaceGitCommandsPerMinute: Int,
) {
    fun summary(): String {
        return buildString {
            append("outcome=").append(outcome)
            append(", elapsedMs=").append(elapsedMs)
            append(", gitCommands=").append(gitCommandCount)
            append(", failedGitCommands=").append(failedGitCommandCount)
            append(", timedOutGitCommands=").append(timedOutGitCommandCount)
            append(", openProjects=").append(openProjectCount)
            append(", configuredIntervalMs=").append(configuredPollIntervalMs)
            append(", effectiveIntervalMs=").append(effectivePollIntervalMs)
            append(", workspacePollsPerMinute=").append(workspacePollsPerMinute)
            append(", estimatedWorkspaceGitCommandsPerMinute=").append(estimatedWorkspaceGitCommandsPerMinute)
        }
    }
}

internal data class HookWatcherSummaryTelemetry(
    val pollCount: Long,
    val triggeredPollCount: Long,
    val hitRatePercent: Int,
    val failedPollCount: Long,
    val gitCommandCount: Long,
    val failedGitCommandCount: Long,
    val timedOutGitCommandCount: Long,
    val averageElapsedMs: Long,
    val maxElapsedMs: Long,
    val openProjectCount: Int,
    val configuredPollIntervalMs: Long,
    val effectivePollIntervalMs: Long,
    val workspacePollsPerMinute: Int,
    val estimatedWorkspaceGitCommandsPerMinute: Int,
) {
    fun summary(): String {
        return buildString {
            append("polls=").append(pollCount)
            append(", triggeredPolls=").append(triggeredPollCount)
            append(", hitRate=").append(hitRatePercent).append('%')
            append(", failedPolls=").append(failedPollCount)
            append(", gitCommands=").append(gitCommandCount)
            append(", failedGitCommands=").append(failedGitCommandCount)
            append(", timedOutGitCommands=").append(timedOutGitCommandCount)
            append(", avgElapsedMs=").append(averageElapsedMs)
            append(", maxElapsedMs=").append(maxElapsedMs)
            append(", openProjects=").append(openProjectCount)
            append(", configuredIntervalMs=").append(configuredPollIntervalMs)
            append(", effectiveIntervalMs=").append(effectivePollIntervalMs)
            append(", workspacePollsPerMinute=").append(workspacePollsPerMinute)
            append(", estimatedWorkspaceGitCommandsPerMinute=").append(estimatedWorkspaceGitCommandsPerMinute)
        }
    }
}

internal data class HookWatcherTelemetryEvent(
    val slowPoll: HookWatcherSlowPollTelemetry?,
    val summary: HookWatcherSummaryTelemetry?,
)

internal class HookWatcherTelemetryTracker(
    private val configuredPollIntervalMs: Long,
    private val summaryEveryPolls: Long = HookWatcherTelemetryThresholds.SUMMARY_LOG_INTERVAL_POLLS,
) {
    private var pollCount = 0L
    private var triggeredPollCount = 0L
    private var failedPollCount = 0L
    private var gitCommandCount = 0L
    private var failedGitCommandCount = 0L
    private var timedOutGitCommandCount = 0L
    private var totalElapsedMs = 0L
    private var maxElapsedMs = 0L

    fun record(observation: HookWatcherPollObservation): HookWatcherTelemetryEvent {
        pollCount += 1
        totalElapsedMs += observation.elapsedMs.coerceAtLeast(0L)
        maxElapsedMs = maxOf(maxElapsedMs, observation.elapsedMs.coerceAtLeast(0L))
        gitCommandCount += observation.gitCommandCount.coerceAtLeast(0)
        failedGitCommandCount += observation.failedGitCommandCount.coerceAtLeast(0)
        timedOutGitCommandCount += observation.timedOutGitCommandCount.coerceAtLeast(0)

        if (observation.outcome == HookWatcherPollOutcome.TRIGGERED) {
            triggeredPollCount += 1
        }
        if (observation.outcome == HookWatcherPollOutcome.GIT_COMMAND_FAILED || observation.outcome == HookWatcherPollOutcome.FAILED) {
            failedPollCount += 1
        }

        return HookWatcherTelemetryEvent(
            slowPoll = buildSlowPollTelemetry(observation),
            summary = buildSummaryTelemetry(
                openProjectCount = observation.openProjectCount,
                effectivePollIntervalMs = observation.effectivePollIntervalMs,
            ),
        )
    }

    private fun buildSlowPollTelemetry(observation: HookWatcherPollObservation): HookWatcherSlowPollTelemetry? {
        if (determineHookWatcherTelemetrySeverity(observation.elapsedMs) == HookWatcherTelemetrySeverity.SKIP) {
            return null
        }
        val effectivePollIntervalMs = observation.effectivePollIntervalMs
            .takeIf { it > 0L }
            ?: configuredPollIntervalMs
        val workspacePollsPerMinute = estimateHookWatcherWorkspacePollsPerMinute(
            openProjectCount = observation.openProjectCount,
            configuredPollIntervalMs = effectivePollIntervalMs,
        )
        return HookWatcherSlowPollTelemetry(
            outcome = observation.outcome,
            elapsedMs = observation.elapsedMs,
            gitCommandCount = observation.gitCommandCount,
            failedGitCommandCount = observation.failedGitCommandCount,
            timedOutGitCommandCount = observation.timedOutGitCommandCount,
            openProjectCount = observation.openProjectCount,
            configuredPollIntervalMs = configuredPollIntervalMs,
            effectivePollIntervalMs = effectivePollIntervalMs,
            workspacePollsPerMinute = workspacePollsPerMinute,
            estimatedWorkspaceGitCommandsPerMinute = estimateHookWatcherWorkspaceGitCommandsPerMinute(
                workspacePollsPerMinute = workspacePollsPerMinute,
                averageGitCommandsPerPoll = observation.gitCommandCount.toDouble(),
            ),
        )
    }

    private fun buildSummaryTelemetry(
        openProjectCount: Int,
        effectivePollIntervalMs: Long,
    ): HookWatcherSummaryTelemetry? {
        if (summaryEveryPolls <= 0L || pollCount % summaryEveryPolls != 0L) {
            return null
        }
        val resolvedPollIntervalMs = effectivePollIntervalMs
            .takeIf { it > 0L }
            ?: configuredPollIntervalMs
        val workspacePollsPerMinute = estimateHookWatcherWorkspacePollsPerMinute(
            openProjectCount = openProjectCount,
            configuredPollIntervalMs = resolvedPollIntervalMs,
        )
        val averageGitCommandsPerPoll = if (pollCount <= 0L) {
            0.0
        } else {
            gitCommandCount.toDouble() / pollCount.toDouble()
        }
        return HookWatcherSummaryTelemetry(
            pollCount = pollCount,
            triggeredPollCount = triggeredPollCount,
            hitRatePercent = calculateHitRatePercent(triggeredPollCount, pollCount),
            failedPollCount = failedPollCount,
            gitCommandCount = gitCommandCount,
            failedGitCommandCount = failedGitCommandCount,
            timedOutGitCommandCount = timedOutGitCommandCount,
            averageElapsedMs = if (pollCount <= 0L) 0L else totalElapsedMs / pollCount,
            maxElapsedMs = maxElapsedMs,
            openProjectCount = openProjectCount,
            configuredPollIntervalMs = configuredPollIntervalMs,
            effectivePollIntervalMs = resolvedPollIntervalMs,
            workspacePollsPerMinute = workspacePollsPerMinute,
            estimatedWorkspaceGitCommandsPerMinute = estimateHookWatcherWorkspaceGitCommandsPerMinute(
                workspacePollsPerMinute = workspacePollsPerMinute,
                averageGitCommandsPerPoll = averageGitCommandsPerPoll,
            ),
        )
    }

    private fun calculateHitRatePercent(triggeredPolls: Long, totalPolls: Long): Int {
        if (triggeredPolls <= 0L || totalPolls <= 0L) {
            return 0
        }
        return ((triggeredPolls.toDouble() / totalPolls.toDouble()) * 100.0).roundToInt()
    }
}
