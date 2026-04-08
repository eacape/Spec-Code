package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.telemetry.HookWatcherPollOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HookWatcherPollIntervalBackoffTest {

    @Test
    fun `passive polls should back off in bounded steps`() {
        val backoff = HookWatcherPollIntervalBackoff(
            baseIntervalMs = 3_000L,
            maxIntervalMs = 15_000L,
        )

        assertEquals(3_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))
        assertEquals(3_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))
        assertEquals(6_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))

        repeat(6) {
            assertEquals(6_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))
        }

        assertEquals(15_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))
        assertEquals(15_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))
    }

    @Test
    fun `triggered commit should reset backoff to base interval`() {
        val backoff = HookWatcherPollIntervalBackoff(
            baseIntervalMs = 3_000L,
            maxIntervalMs = 15_000L,
        )

        repeat(10) {
            backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED)
        }

        assertEquals(3_000L, backoff.intervalAfter(HookWatcherPollOutcome.TRIGGERED))
        assertEquals(3_000L, backoff.intervalAfter(HookWatcherPollOutcome.UNCHANGED))
    }

    @Test
    fun `git command failures should also participate in passive backoff`() {
        val backoff = HookWatcherPollIntervalBackoff(
            baseIntervalMs = 3_000L,
            maxIntervalMs = 15_000L,
        )

        assertEquals(3_000L, backoff.intervalAfter(HookWatcherPollOutcome.GIT_COMMAND_FAILED))
        assertEquals(3_000L, backoff.intervalAfter(HookWatcherPollOutcome.GIT_COMMAND_FAILED))
        assertEquals(6_000L, backoff.intervalAfter(HookWatcherPollOutcome.GIT_COMMAND_FAILED))
    }
}
