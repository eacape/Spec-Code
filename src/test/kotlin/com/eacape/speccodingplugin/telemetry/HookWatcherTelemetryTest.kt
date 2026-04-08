package com.eacape.speccodingplugin.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookWatcherTelemetryTest {

    @Test
    fun `determineHookWatcherTelemetrySeverity should respect thresholds`() {
        assertEquals(HookWatcherTelemetrySeverity.SKIP, determineHookWatcherTelemetrySeverity(elapsedMs = 249))
        assertEquals(HookWatcherTelemetrySeverity.INFO, determineHookWatcherTelemetrySeverity(elapsedMs = 250))
        assertEquals(HookWatcherTelemetrySeverity.WARN, determineHookWatcherTelemetrySeverity(elapsedMs = 1_000))
    }

    @Test
    fun `HookWatcherTelemetryTracker should emit slow poll telemetry with workspace cost estimate`() {
        val tracker = HookWatcherTelemetryTracker(
            configuredPollIntervalMs = 3_000L,
            summaryEveryPolls = 10L,
        )

        val event = tracker.record(
            HookWatcherPollObservation(
                outcome = HookWatcherPollOutcome.GIT_COMMAND_FAILED,
                elapsedMs = 320L,
                openProjectCount = 3,
                gitCommandCount = 2,
                failedGitCommandCount = 1,
                timedOutGitCommandCount = 1,
            ),
        )

        val slowPoll = event.slowPoll
        assertNotNull(slowPoll)
        assertEquals(HookWatcherPollOutcome.GIT_COMMAND_FAILED, slowPoll?.outcome)
        assertEquals(60, slowPoll?.workspacePollsPerMinute)
        assertEquals(120, slowPoll?.estimatedWorkspaceGitCommandsPerMinute)
        assertTrue(slowPoll?.summary()?.contains("timedOutGitCommands=1") == true)
        assertNull(event.summary)
    }

    @Test
    fun `HookWatcherTelemetryTracker should use effective backoff interval when provided`() {
        val tracker = HookWatcherTelemetryTracker(
            configuredPollIntervalMs = 3_000L,
            summaryEveryPolls = 10L,
        )

        val event = tracker.record(
            HookWatcherPollObservation(
                outcome = HookWatcherPollOutcome.UNCHANGED,
                elapsedMs = 260L,
                openProjectCount = 3,
                gitCommandCount = 2,
                failedGitCommandCount = 0,
                timedOutGitCommandCount = 0,
                effectivePollIntervalMs = 6_000L,
            ),
        )

        val slowPoll = event.slowPoll
        assertNotNull(slowPoll)
        assertEquals(6_000L, slowPoll?.effectivePollIntervalMs)
        assertEquals(30, slowPoll?.workspacePollsPerMinute)
        assertEquals(60, slowPoll?.estimatedWorkspaceGitCommandsPerMinute)
        assertTrue(slowPoll?.summary()?.contains("effectiveIntervalMs=6000") == true)
    }

    @Test
    fun `HookWatcherTelemetryTracker should emit periodic summary with hit rate and aggregated cost`() {
        val tracker = HookWatcherTelemetryTracker(
            configuredPollIntervalMs = 3_000L,
            summaryEveryPolls = 5L,
        )

        repeat(4) {
            val event = tracker.record(
                HookWatcherPollObservation(
                    outcome = HookWatcherPollOutcome.UNCHANGED,
                    elapsedMs = 80L,
                    openProjectCount = 2,
                    gitCommandCount = 1,
                    failedGitCommandCount = 0,
                    timedOutGitCommandCount = 0,
                ),
            )
            assertNull(event.summary)
        }

        val event = tracker.record(
            HookWatcherPollObservation(
                outcome = HookWatcherPollOutcome.TRIGGERED,
                elapsedMs = 140L,
                openProjectCount = 2,
                gitCommandCount = 3,
                failedGitCommandCount = 1,
                timedOutGitCommandCount = 0,
            ),
        )

        val summary = event.summary
        assertNotNull(summary)
        assertEquals(5L, summary?.pollCount)
        assertEquals(1L, summary?.triggeredPollCount)
        assertEquals(20, summary?.hitRatePercent)
        assertEquals(7L, summary?.gitCommandCount)
        assertEquals(1L, summary?.failedGitCommandCount)
        assertEquals(40, summary?.workspacePollsPerMinute)
        assertEquals(56, summary?.estimatedWorkspaceGitCommandsPerMinute)
        assertEquals(3_000L, summary?.effectivePollIntervalMs)
        assertTrue(summary?.summary()?.contains("hitRate=20%") == true)
    }
}
