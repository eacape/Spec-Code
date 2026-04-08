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
    fun `CodeGraphCacheStats should compute hit rate and keep invalidation reason`() {
        val stats = CodeGraphCacheStats(
            hitCount = 6,
            missCount = 2,
            lastInvalidationReason = "psi-change:Main.kt",
        )

        assertEquals(75, stats.hitRatePercent())
        assertTrue(stats.summary().contains("lastInvalidation=psi-change:Main.kt"))
        assertFalse(stats.shouldEmitPeriodicHitLog())
        assertTrue(stats.copy(hitCount = 20).shouldEmitPeriodicHitLog())
    }

    @Test
    fun `ContextCollectionTelemetry summary should include budgets and skipped stages`() {
        val telemetry = ContextCollectionTelemetry(
            operationKey = "collectForItems",
            elapsedMs = 82,
            candidateItemCount = 5,
            budgetAcceptedItemCount = 3,
            finalItemCount = 2,
            budgetStats = ContextCollectionBudgetStats(
                filePaths = setOf("/repo/src/Main.kt"),
                symbolItemCount = 1,
                totalContentBytes = 320,
            ),
            tokenEstimate = 80,
            tokenBudget = 8000,
            maxFileItems = 4,
            maxSymbolItems = 6,
            maxContentBytes = 4096,
            maxCollectionTimeMs = 250,
            wasTokenTrimmed = true,
            budgetDropSummary = "byte-budget=1",
            skippedStages = listOf("project-structure:time-budget"),
        )

        val summary = telemetry.summary()

        assertTrue(summary.contains("operation=collectForItems"))
        assertTrue(summary.contains("elapsedMs=82/250"))
        assertTrue(summary.contains("budgetAcceptedItems=3"))
        assertTrue(summary.contains("files=1, symbols=1, bytes=320"))
        assertTrue(summary.contains("budgetDrops=byte-budget=1"))
        assertTrue(summary.contains("skippedStages=project-structure:time-budget"))
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
