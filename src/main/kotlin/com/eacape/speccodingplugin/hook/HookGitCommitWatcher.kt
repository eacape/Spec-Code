package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.telemetry.HookWatcherPollObservation
import com.eacape.speccodingplugin.telemetry.HookWatcherPollOutcome
import com.eacape.speccodingplugin.telemetry.HookWatcherTelemetryEvent
import com.eacape.speccodingplugin.telemetry.HookWatcherTelemetrySeverity
import com.eacape.speccodingplugin.telemetry.HookWatcherTelemetryTracker
import com.eacape.speccodingplugin.telemetry.SlowPathBaselineSample
import com.eacape.speccodingplugin.telemetry.determineHookWatcherTelemetrySeverity
import com.eacape.speccodingplugin.telemetry.emitSlowPathBaseline
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class HookGitCommitWatcher(
    private val project: Project,
) : Disposable {
    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val telemetryTracker = HookWatcherTelemetryTracker(configuredPollIntervalMs = POLL_INTERVAL_MS)
    private val pollIntervalBackoff = HookWatcherPollIntervalBackoff(
        baseIntervalMs = POLL_INTERVAL_MS,
        maxIntervalMs = MAX_POLL_INTERVAL_MS,
    )
    private val gitCommandRuntime = HookGitCommandRuntime()

    @Volatile
    private var started = false

    @Volatile
    private var lastObservedHead: String? = null

    @Volatile
    private var initialized = false

    fun start() {
        if (started || project.isDisposed) {
            return
        }
        started = true
        scope.launch {
            while (!project.isDisposed) {
                val cycle = recordPollObservation()
                emitTelemetry(cycle.event)
                delay(cycle.nextPollIntervalMs)
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun recordPollObservation(): HookWatcherPollCycle {
        val pollStartedAt = System.nanoTime()
        val openProjectCount = currentOpenProjectCount()
        val observation = runCatching { pollHeadChange() }
            .fold(
                onSuccess = { result ->
                    HookWatcherPollObservation(
                        outcome = result.outcome,
                        elapsedMs = elapsedSince(pollStartedAt),
                        openProjectCount = openProjectCount,
                        gitCommandCount = result.gitCommandCount,
                        failedGitCommandCount = result.failedGitCommandCount,
                        timedOutGitCommandCount = result.timedOutGitCommandCount,
                    )
                },
                onFailure = { error ->
                    logger.debug("HookGitCommitWatcher poll failed", error)
                    HookWatcherPollObservation(
                        outcome = HookWatcherPollOutcome.FAILED,
                        elapsedMs = elapsedSince(pollStartedAt),
                        openProjectCount = openProjectCount,
                        gitCommandCount = 0,
                        failedGitCommandCount = 0,
                        timedOutGitCommandCount = 0,
                    )
                },
            )
        val nextPollIntervalMs = pollIntervalBackoff.intervalAfter(observation.outcome)
        emitSlowPathBaseline(
            logger = logger,
            sample = SlowPathBaselineSample(
                operationKey = "HookGitCommitWatcher.pollHead",
                elapsedMs = observation.elapsedMs,
                timedOut = observation.timedOutGitCommandCount > 0,
            ),
        )
        return HookWatcherPollCycle(
            event = telemetryTracker.record(
                observation.copy(
                    effectivePollIntervalMs = nextPollIntervalMs,
                ),
            ),
            nextPollIntervalMs = nextPollIntervalMs,
        )
    }

    private fun pollHeadChange(): HookWatcherPollResult {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) {
            return HookWatcherPollResult(outcome = HookWatcherPollOutcome.SKIPPED_NO_BASE_PATH)
        }
        if (!File(basePath, ".git").exists()) {
            return HookWatcherPollResult(outcome = HookWatcherPollOutcome.SKIPPED_NO_GIT_DIR)
        }
        var gitCommandCount = 0
        var failedGitCommandCount = 0
        var timedOutGitCommandCount = 0

        fun runGitAndTrack(vararg args: String): String? {
            val result = runGit(basePath, *args)
            gitCommandCount += 1
            if (result.failed) {
                failedGitCommandCount += 1
            }
            if (result.timedOut) {
                timedOutGitCommandCount += 1
            }
            return result.output
        }

        fun pollResult(outcome: HookWatcherPollOutcome): HookWatcherPollResult {
            return HookWatcherPollResult(
                outcome = outcome,
                gitCommandCount = gitCommandCount,
                failedGitCommandCount = failedGitCommandCount,
                timedOutGitCommandCount = timedOutGitCommandCount,
            )
        }

        val currentHead = runGitAndTrack("rev-parse", "HEAD") ?: return pollResult(HookWatcherPollOutcome.GIT_COMMAND_FAILED)
        if (!initialized) {
            lastObservedHead = currentHead
            initialized = true
            return pollResult(HookWatcherPollOutcome.INITIALIZED)
        }

        val previousHead = lastObservedHead
        if (!previousHead.isNullOrBlank() && previousHead != currentHead) {
            val reflogSummary = runGitAndTrack("reflog", "-1", "--pretty=%gs")
            val isCommitLike = reflogSummary?.contains("commit", ignoreCase = true) == true
            if (!isCommitLike) {
                lastObservedHead = currentHead
                return pollResult(HookWatcherPollOutcome.NON_COMMIT_HEAD_CHANGE)
            }
            val metadata = linkedMapOf(
                "projectName" to project.name,
                "previousCommit" to previousHead,
                "currentCommit" to currentHead,
            )
            val branch = runGitAndTrack("rev-parse", "--abbrev-ref", "HEAD")
            if (!branch.isNullOrBlank()) {
                metadata["branch"] = branch
            }
            if (!reflogSummary.isNullOrBlank()) {
                metadata["reflog"] = reflogSummary
            }
            HookManager.getInstance(project).trigger(
                event = HookEvent.GIT_COMMIT,
                triggerContext = HookTriggerContext(metadata = metadata),
            )
            lastObservedHead = currentHead
            return pollResult(HookWatcherPollOutcome.TRIGGERED)
        }
        lastObservedHead = currentHead
        return pollResult(HookWatcherPollOutcome.UNCHANGED)
    }

    private fun runGit(basePath: String, vararg args: String): HookGitCommandExecutionResult {
        return gitCommandRuntime.execute(
            basePath,
            GIT_COMMAND_TIMEOUT_MILLIS,
            *args,
        )
    }

    private fun emitTelemetry(event: HookWatcherTelemetryEvent) {
        event.slowPoll?.let { telemetry ->
            val message = "HookGitCommitWatcher[${project.name}] slow poll: ${telemetry.summary()}"
            when (determineHookWatcherTelemetrySeverity(telemetry.elapsedMs)) {
                HookWatcherTelemetrySeverity.WARN -> logger.warn(message)
                HookWatcherTelemetrySeverity.INFO -> logger.info(message)
                HookWatcherTelemetrySeverity.SKIP -> Unit
            }
        }
        event.summary?.let { summary ->
            logger.info("HookGitCommitWatcher[${project.name}] poll stats: ${summary.summary()}")
        }
    }

    private fun currentOpenProjectCount(): Int {
        return runCatching {
            ProjectManager.getInstance().openProjects.count { !it.isDisposed }
        }.getOrDefault(1).coerceAtLeast(1)
    }

    private fun elapsedSince(startedAtNanos: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
    }

    private data class HookWatcherPollResult(
        val outcome: HookWatcherPollOutcome,
        val gitCommandCount: Int = 0,
        val failedGitCommandCount: Int = 0,
        val timedOutGitCommandCount: Int = 0,
    )

    private data class HookWatcherPollCycle(
        val event: HookWatcherTelemetryEvent,
        val nextPollIntervalMs: Long,
    )

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLL_INTERVAL_MS = 15_000L
        private const val GIT_COMMAND_TIMEOUT_MILLIS = 2_000L

        fun getInstance(project: Project): HookGitCommitWatcher = project.service()
    }
}
