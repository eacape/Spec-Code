package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextTelemetryTest {

    @Test
    fun `determineContextTelemetrySeverity should respect configured thresholds`() {
        assertEquals(ContextTelemetrySeverity.SKIP, determineContextTelemetrySeverity(elapsedMs = 149))
        assertEquals(ContextTelemetrySeverity.INFO, determineContextTelemetrySeverity(elapsedMs = 150))
        assertEquals(ContextTelemetrySeverity.WARN, determineContextTelemetrySeverity(elapsedMs = 500))
    }

    @Test
    fun `ProjectStructureCacheStats should compute hit rate and periodic hit logging`() {
        val stats = ProjectStructureCacheStats(
            hitCount = 20,
            missCount = 5,
            lastInvalidationReason = "vfs-change",
        )

        assertEquals(80, stats.hitRatePercent())
        assertTrue(stats.shouldEmitPeriodicHitLog())
        assertTrue(stats.summary().contains("lastInvalidation=vfs-change"))

        val belowInterval = stats.copy(hitCount = 19)
        assertFalse(belowInterval.shouldEmitPeriodicHitLog())
    }

    @Test
    fun `CodeGraphBuildTelemetry summary should include scope and limit hits`() {
        val telemetry = CodeGraphBuildTelemetry(
            rootFilePath = "/repo/src/Main.kt",
            rootFileName = "Main.kt",
            trigger = "active-editor",
            elapsedMs = 612,
            nodeCount = 6,
            edgeCount = 4,
            dependencyEdgeCount = 2,
            callEdgeCount = 2,
            dependencyReferenceScans = 17,
            callReferenceScans = 11,
            namedElementCount = 3,
            maxDependencies = 2,
            maxCallEdges = 8,
            dependencyLimitHit = true,
            callLimitHit = false,
            outcome = "success",
        )

        val summary = telemetry.summary()

        assertTrue(summary.contains("trigger=active-editor"))
        assertTrue(summary.contains("file=Main.kt"))
        assertTrue(summary.contains("path=/repo/src/Main.kt"))
        assertTrue(summary.contains("elapsedMs=612"))
        assertTrue(summary.contains("dependencyReferenceScans=17"))
        assertTrue(summary.contains("dependencyLimit=2/hit=true"))
        assertTrue(summary.contains("callLimit=8/hit=false"))
    }
}
