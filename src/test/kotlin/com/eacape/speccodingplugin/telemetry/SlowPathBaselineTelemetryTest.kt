package com.eacape.speccodingplugin.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlowPathBaselineTelemetryTest {

    @Test
    fun `SlowPathOperationBaseline should report rounded timeout ratio`() {
        val baseline = SlowPathOperationBaseline(
            operationKey = "HookGitCommitWatcher.pollHead",
            sampleCount = 3,
            averageElapsedMs = 700,
            p95ElapsedMs = 1_200,
            maxElapsedMs = 1_200,
            timeoutCount = 1,
        )

        assertEquals(33, baseline.timeoutRatioPercent())
        assertTrue(baseline.summary().contains("timeoutRatio=33%"))
    }

    @Test
    fun `SlowPathBaselineTracker should emit top3 ranked by p95`() {
        val tracker = SlowPathBaselineTracker(
            summaryEverySamples = 9,
            maxSamplesPerOperation = 8,
            topOperationCount = 3,
        )

        val samples = listOf(
            SlowPathBaselineSample("CodeGraphService.buildFromActiveEditor", 200),
            SlowPathBaselineSample("CodeGraphService.buildFromActiveEditor", 600),
            SlowPathBaselineSample("HookGitCommitWatcher.pollHead", 100),
            SlowPathBaselineSample("HookGitCommitWatcher.pollHead", 1_200, timedOut = true),
            SlowPathBaselineSample("HookGitCommitWatcher.pollHead", 800),
            SlowPathBaselineSample("SessionManager.searchSessions", 250),
            SlowPathBaselineSample("SessionManager.searchSessions", 450),
            SlowPathBaselineSample("ProjectStructureScanner.getProjectTreeMiss", 400),
            SlowPathBaselineSample("SessionManager.searchSessions", 300),
        )

        samples.dropLast(1).forEach { sample ->
            assertNull(tracker.record(sample))
        }

        val summary = tracker.record(samples.last())
        assertNotNull(summary)
        assertEquals(9L, summary?.totalSamples)
        assertEquals(4, summary?.trackedOperations)
        assertEquals(
            listOf(
                "HookGitCommitWatcher.pollHead",
                "CodeGraphService.buildFromActiveEditor",
                "SessionManager.searchSessions",
            ),
            summary?.topOperations?.map { operation -> operation.operationKey },
        )
        assertEquals(33, summary?.topOperations?.first()?.timeoutRatioPercent())
        assertTrue(summary?.summary()?.contains("top3=") == true)
    }

    @Test
    fun `SlowPathBaselineTracker should keep a bounded rolling sample window`() {
        val tracker = SlowPathBaselineTracker(
            summaryEverySamples = 4,
            maxSamplesPerOperation = 3,
            topOperationCount = 1,
        )

        tracker.record(SlowPathBaselineSample("SpecStorage.persistWorkflowMetadata", 100))
        tracker.record(SlowPathBaselineSample("SpecStorage.persistWorkflowMetadata", 200))
        tracker.record(SlowPathBaselineSample("SpecStorage.persistWorkflowMetadata", 300))
        val summary = tracker.record(SlowPathBaselineSample("SpecStorage.persistWorkflowMetadata", 1_000))

        val topOperation = summary?.topOperations?.single()
        assertNotNull(topOperation)
        assertEquals(3, topOperation?.sampleCount)
        assertEquals(500L, topOperation?.averageElapsedMs)
        assertEquals(1_000L, topOperation?.p95ElapsedMs)
        assertEquals(1_000L, topOperation?.maxElapsedMs)
    }
}
