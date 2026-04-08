package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.telemetry.HookWatcherPollOutcome

internal class HookWatcherPollIntervalBackoff(
    private val baseIntervalMs: Long,
    private val mediumBackoffAfterPassivePolls: Int = 3,
    private val maxBackoffAfterPassivePolls: Int = 10,
    private val maxIntervalMs: Long = baseIntervalMs * 4,
) {
    private var consecutivePassivePolls = 0

    init {
        require(baseIntervalMs > 0L) { "baseIntervalMs must be positive" }
        require(mediumBackoffAfterPassivePolls > 0) { "mediumBackoffAfterPassivePolls must be positive" }
        require(maxBackoffAfterPassivePolls >= mediumBackoffAfterPassivePolls) {
            "maxBackoffAfterPassivePolls must be >= mediumBackoffAfterPassivePolls"
        }
        require(maxIntervalMs >= baseIntervalMs) { "maxIntervalMs must be >= baseIntervalMs" }
    }

    fun intervalAfter(outcome: HookWatcherPollOutcome): Long {
        consecutivePassivePolls = if (outcome in passiveOutcomes) {
            consecutivePassivePolls + 1
        } else {
            0
        }

        return when {
            consecutivePassivePolls >= maxBackoffAfterPassivePolls -> maxIntervalMs
            consecutivePassivePolls >= mediumBackoffAfterPassivePolls -> (baseIntervalMs * 2).coerceAtMost(maxIntervalMs)
            else -> baseIntervalMs
        }
    }

    private companion object {
        private val passiveOutcomes = setOf(
            HookWatcherPollOutcome.SKIPPED_NO_BASE_PATH,
            HookWatcherPollOutcome.SKIPPED_NO_GIT_DIR,
            HookWatcherPollOutcome.GIT_COMMAND_FAILED,
            HookWatcherPollOutcome.UNCHANGED,
            HookWatcherPollOutcome.NON_COMMIT_HEAD_CHANGE,
            HookWatcherPollOutcome.FAILED,
        )
    }
}
