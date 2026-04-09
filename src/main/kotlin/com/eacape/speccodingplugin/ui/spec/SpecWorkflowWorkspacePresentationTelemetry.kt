package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.telemetry.SlowPathBaselineSample
import com.eacape.speccodingplugin.telemetry.emitSlowPathBaseline
import com.intellij.openapi.diagnostic.Logger

internal enum class SpecWorkflowWorkspacePresentationTelemetrySeverity {
    SKIP,
    INFO,
    WARN,
}

internal object SpecWorkflowWorkspacePresentationTelemetryThresholds {
    const val INFO_SLOW_PATH_MS = 48L
    const val WARN_SLOW_PATH_MS = 120L
}

internal fun determineSpecWorkflowWorkspacePresentationTelemetrySeverity(
    elapsedMs: Long,
    infoThresholdMs: Long = SpecWorkflowWorkspacePresentationTelemetryThresholds.INFO_SLOW_PATH_MS,
    warnThresholdMs: Long = SpecWorkflowWorkspacePresentationTelemetryThresholds.WARN_SLOW_PATH_MS,
): SpecWorkflowWorkspacePresentationTelemetrySeverity {
    return when {
        elapsedMs >= warnThresholdMs -> SpecWorkflowWorkspacePresentationTelemetrySeverity.WARN
        elapsedMs >= infoThresholdMs -> SpecWorkflowWorkspacePresentationTelemetrySeverity.INFO
        else -> SpecWorkflowWorkspacePresentationTelemetrySeverity.SKIP
    }
}

internal data class SpecWorkflowWorkspacePresentationObservation(
    val workflowId: String,
    val currentStage: StageId,
    val focusedStage: StageId,
    val taskCount: Int,
    val liveTaskCount: Int,
    val visibleSectionCount: Int,
    val syncSelection: Boolean,
)

internal data class SpecWorkflowWorkspacePresentationTelemetry(
    val workflowId: String,
    val currentStage: StageId,
    val focusedStage: StageId,
    val taskCount: Int,
    val liveTaskCount: Int,
    val visibleSectionCount: Int,
    val syncSelection: Boolean,
    val elapsedMs: Long,
) {
    fun operationKey(): String = "SpecWorkflowPanel.updateWorkspacePresentation"

    fun summary(): String {
        return buildString {
            append("workflowId=").append(workflowId)
            append(", currentStage=").append(currentStage.name)
            append(", focusedStage=").append(focusedStage.name)
            append(", elapsedMs=").append(elapsedMs)
            append(", taskCount=").append(taskCount)
            append(", liveTaskCount=").append(liveTaskCount)
            append(", visibleSectionCount=").append(visibleSectionCount)
            append(", syncSelection=").append(syncSelection)
        }
    }
}

internal class SpecWorkflowWorkspacePresentationTelemetryTracker(
    private val logger: Logger,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
    private val emitTelemetry: (
        Logger,
        SpecWorkflowWorkspacePresentationTelemetry,
    ) -> Unit = ::emitSpecWorkflowWorkspacePresentationTelemetry,
) {
    fun markStart(): Long = nanoTimeProvider()

    fun record(
        startedAtNanos: Long,
        observation: SpecWorkflowWorkspacePresentationObservation,
    ) {
        emitTelemetry(
            logger,
            SpecWorkflowWorkspacePresentationTelemetry(
                workflowId = observation.workflowId,
                currentStage = observation.currentStage,
                focusedStage = observation.focusedStage,
                taskCount = observation.taskCount,
                liveTaskCount = observation.liveTaskCount,
                visibleSectionCount = observation.visibleSectionCount,
                syncSelection = observation.syncSelection,
                elapsedMs = elapsedMsSince(startedAtNanos),
            ),
        )
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long {
        return ((nanoTimeProvider() - startedAtNanos).coerceAtLeast(0L)) / 1_000_000
    }
}

internal fun emitSpecWorkflowWorkspacePresentationTelemetry(
    logger: Logger,
    telemetry: SpecWorkflowWorkspacePresentationTelemetry,
) {
    emitSlowPathBaseline(
        logger = logger,
        sample = SlowPathBaselineSample(
            operationKey = telemetry.operationKey(),
            elapsedMs = telemetry.elapsedMs,
        ),
    )

    when (determineSpecWorkflowWorkspacePresentationTelemetrySeverity(telemetry.elapsedMs)) {
        SpecWorkflowWorkspacePresentationTelemetrySeverity.WARN ->
            logger.warn("Spec workflow workspace UI slow path: ${telemetry.summary()}")

        SpecWorkflowWorkspacePresentationTelemetrySeverity.INFO ->
            logger.info("Spec workflow workspace UI slow path: ${telemetry.summary()}")

        SpecWorkflowWorkspacePresentationTelemetrySeverity.SKIP -> Unit
    }
}
