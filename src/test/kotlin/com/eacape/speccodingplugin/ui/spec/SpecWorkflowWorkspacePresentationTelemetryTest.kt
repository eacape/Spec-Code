package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.telemetry.RuntimeSlowPathBaselineRegistry
import com.intellij.openapi.diagnostic.Logger
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpecWorkflowWorkspacePresentationTelemetryTest {

    @BeforeEach
    fun setUp() {
        RuntimeSlowPathBaselineRegistry.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        RuntimeSlowPathBaselineRegistry.resetForTest()
    }

    @Test
    fun `determineSpecWorkflowWorkspacePresentationTelemetrySeverity should respect thresholds`() {
        assertEquals(
            SpecWorkflowWorkspacePresentationTelemetrySeverity.SKIP,
            determineSpecWorkflowWorkspacePresentationTelemetrySeverity(elapsedMs = 47),
        )
        assertEquals(
            SpecWorkflowWorkspacePresentationTelemetrySeverity.INFO,
            determineSpecWorkflowWorkspacePresentationTelemetrySeverity(elapsedMs = 48),
        )
        assertEquals(
            SpecWorkflowWorkspacePresentationTelemetrySeverity.WARN,
            determineSpecWorkflowWorkspacePresentationTelemetrySeverity(elapsedMs = 120),
        )
    }

    @Test
    fun `SpecWorkflowWorkspacePresentationTelemetry summary should include workflow and workload details`() {
        val telemetry = SpecWorkflowWorkspacePresentationTelemetry(
            workflowId = "wf-summary",
            currentStage = StageId.TASKS,
            focusedStage = StageId.IMPLEMENT,
            taskCount = 5,
            liveTaskCount = 2,
            visibleSectionCount = 4,
            syncSelection = true,
            elapsedMs = 88,
        )

        val summary = telemetry.summary()

        assertTrue(summary.contains("workflowId=wf-summary"))
        assertTrue(summary.contains("currentStage=TASKS"))
        assertTrue(summary.contains("focusedStage=IMPLEMENT"))
        assertTrue(summary.contains("taskCount=5"))
        assertTrue(summary.contains("liveTaskCount=2"))
        assertTrue(summary.contains("visibleSectionCount=4"))
        assertTrue(summary.contains("syncSelection=true"))
        assertTrue(summary.contains("elapsedMs=88"))
    }

    @Test
    fun `tracker should record runtime baseline even when workspace refresh stays below log threshold`() {
        val logger = mockk<Logger>(relaxed = true)
        val tracker = SpecWorkflowWorkspacePresentationTelemetryTracker(
            logger = logger,
            nanoTimeProvider = sequenceNanoTimeProvider(0L, 20_000_000L),
        )

        val startedAt = tracker.markStart()
        tracker.record(startedAt, observation())

        val runtimeBaseline = RuntimeSlowPathBaselineRegistry.snapshot()

        assertNotNull(runtimeBaseline)
        assertEquals(1L, runtimeBaseline?.totalSamples)
        assertEquals(1, runtimeBaseline?.trackedOperations)
        assertEquals(
            listOf("SpecWorkflowPanel.updateWorkspacePresentation"),
            runtimeBaseline?.topOperations?.map { baseline -> baseline.operationKey },
        )
        assertEquals(20L, runtimeBaseline?.topOperations?.single()?.averageElapsedMs)
        verify(exactly = 0) { logger.info(any<String>()) }
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `tracker should warn when workspace refresh crosses warn threshold`() {
        val logger = mockk<Logger>(relaxed = true)
        val tracker = SpecWorkflowWorkspacePresentationTelemetryTracker(
            logger = logger,
            nanoTimeProvider = sequenceNanoTimeProvider(0L, 135_000_000L),
        )
        val warningMessage = slot<String>()

        val startedAt = tracker.markStart()
        tracker.record(
            startedAt,
            observation(
                workflowId = "wf-warn",
                currentStage = StageId.IMPLEMENT,
                focusedStage = StageId.IMPLEMENT,
                taskCount = 7,
                liveTaskCount = 3,
                visibleSectionCount = 5,
                syncSelection = true,
            ),
        )

        verify(exactly = 1) { logger.warn(capture(warningMessage)) }
        assertTrue(warningMessage.captured.contains("workflowId=wf-warn"))
        assertTrue(warningMessage.captured.contains("currentStage=IMPLEMENT"))
        assertTrue(warningMessage.captured.contains("visibleSectionCount=5"))
        assertTrue(warningMessage.captured.contains("elapsedMs=135"))
        val runtimeBaseline = RuntimeSlowPathBaselineRegistry.snapshot()
        assertEquals(135L, runtimeBaseline?.topOperations?.single()?.p95ElapsedMs)
    }

    private fun observation(
        workflowId: String = "wf-telemetry",
        currentStage: StageId = StageId.TASKS,
        focusedStage: StageId = StageId.VERIFY,
        taskCount: Int = 4,
        liveTaskCount: Int = 1,
        visibleSectionCount: Int = 3,
        syncSelection: Boolean = false,
    ): SpecWorkflowWorkspacePresentationObservation {
        return SpecWorkflowWorkspacePresentationObservation(
            workflowId = workflowId,
            currentStage = currentStage,
            focusedStage = focusedStage,
            taskCount = taskCount,
            liveTaskCount = liveTaskCount,
            visibleSectionCount = visibleSectionCount,
            syncSelection = syncSelection,
        )
    }

    private fun sequenceNanoTimeProvider(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        val lastValue = values.lastOrNull() ?: 0L
        return {
            if (queue.isEmpty()) {
                lastValue
            } else {
                queue.removeFirst()
            }
        }
    }
}
