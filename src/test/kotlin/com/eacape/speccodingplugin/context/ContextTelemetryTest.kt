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
            lastInvalidationReason = "root-content-change:Main.kt",
        )

        assertEquals(75, stats.hitRatePercent())
        assertTrue(stats.summary().contains("lastInvalidation=root-content-change:Main.kt"))
        assertFalse(stats.shouldEmitPeriodicHitLog())
        assertTrue(stats.copy(hitCount = 20).shouldEmitPeriodicHitLog())
    }

    @Test
    fun `RelatedFileCacheStats should compute hit rate and periodic hit logging`() {
        val stats = RelatedFileCacheStats(
            hitCount = 20,
            missCount = 4,
            lastInvalidationReason = "vfs-rename:src/web/shared/helper.ts",
        )

        assertEquals(83, stats.hitRatePercent())
        assertTrue(stats.shouldEmitPeriodicHitLog())
        assertTrue(stats.summary().contains("lastInvalidation=vfs-rename:src/web/shared/helper.ts"))
        assertFalse(stats.copy(hitCount = 19).shouldEmitPeriodicHitLog())
    }

    @Test
    fun `RelatedFileDiscoveryTelemetry summary should include heuristic semantic and unresolved breakdown`() {
        val telemetry = RelatedFileDiscoveryTelemetry(
            currentFileName = "main.ts",
            language = "typescript",
            heuristicReferenceCount = 3,
            heuristicResolvedCount = 2,
            semanticResolvedCount = 0,
            finalItemCount = 2,
            unresolvedReferences = listOf("typescript:./missing"),
            skippedLayers = listOf("semantic:unavailable"),
        )

        val summary = telemetry.summary()

        assertTrue(summary.contains("file=main.ts"))
        assertTrue(summary.contains("language=typescript"))
        assertTrue(summary.contains("heuristicRefs=3"))
        assertTrue(summary.contains("heuristicResolved=2"))
        assertTrue(summary.contains("semanticResolved=0"))
        assertTrue(summary.contains("unresolved=typescript:./missing"))
        assertTrue(summary.contains("skippedLayers=semantic:unavailable"))
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
            cacheView = ContextCollectionCacheTelemetry(
                codeGraph = CodeGraphCacheStats(
                    hitCount = 4,
                    missCount = 2,
                    lastInvalidationReason = "root-content-change:Main.kt",
                ),
                relatedFiles = RelatedFileCacheStats(
                    hitCount = 3,
                    missCount = 1,
                    lastInvalidationReason = "document-change:main.ts",
                ),
                runOutcomes = listOf(
                    ContextCollectionStageCacheOutcome(stage = "codeGraph", outcome = "hit"),
                    ContextCollectionStageCacheOutcome(stage = "relatedFiles", outcome = "miss"),
                ),
            ),
            baselineComparison = ContextCollectionBaselineComparison(
                phase = "mixed",
            ),
        )

        val summary = telemetry.summary()

        assertTrue(summary.contains("operation=collectForItems"))
        assertTrue(summary.contains("elapsedMs=82/250"))
        assertTrue(summary.contains("budgetAcceptedItems=3"))
        assertTrue(summary.contains("files=1, symbols=1, bytes=320"))
        assertTrue(summary.contains("budgetDrops=byte-budget=1"))
        assertTrue(summary.contains("skippedStages=project-structure:time-budget"))
        assertTrue(summary.contains("cacheView=codeGraph{cacheHits=4"))
        assertTrue(summary.contains("relatedFiles{cacheHits=3"))
        assertTrue(summary.contains("runOutcomes=codeGraph:hit|relatedFiles:miss"))
        assertTrue(summary.contains("baseline=phase=mixed"))
    }

    @Test
    fun `shouldEmitContextTelemetryInfo should emit for first warm improvement even when run is fast`() {
        val telemetry = ContextCollectionTelemetry(
            operationKey = "collectContext",
            elapsedMs = 48,
            candidateItemCount = 4,
            budgetAcceptedItemCount = 4,
            finalItemCount = 4,
            budgetStats = ContextCollectionBudgetStats(
                filePaths = setOf("/repo/src/Main.kt"),
                symbolItemCount = 0,
                totalContentBytes = 240,
            ),
            tokenEstimate = 60,
            tokenBudget = 8000,
            maxFileItems = 10,
            maxSymbolItems = 24,
            maxContentBytes = 48_000,
            maxCollectionTimeMs = 250,
            wasTokenTrimmed = false,
            budgetDropSummary = "none",
            skippedStages = emptyList(),
            cacheView = ContextCollectionCacheTelemetry(
                runOutcomes = listOf(
                    ContextCollectionStageCacheOutcome(stage = "codeGraph", outcome = "hit"),
                    ContextCollectionStageCacheOutcome(stage = "relatedFiles", outcome = "hit"),
                    ContextCollectionStageCacheOutcome(stage = "projectStructure", outcome = "hit"),
                ),
            ),
            baselineComparison = ContextCollectionBaselineComparison(
                phase = "warm",
                coldElapsedMs = 180,
                warmElapsedMs = 48,
                savedMs = 132,
                savedPercent = 73,
                firstWarmAfterCold = true,
            ),
        )

        assertTrue(shouldEmitContextTelemetryInfo(telemetry))
    }

    @Test
    fun `shouldEmitContextTelemetryInfo should suppress repeated warm runs without new cold baseline`() {
        val telemetry = ContextCollectionTelemetry(
            operationKey = "collectContext",
            elapsedMs = 44,
            candidateItemCount = 4,
            budgetAcceptedItemCount = 4,
            finalItemCount = 4,
            budgetStats = ContextCollectionBudgetStats(
                filePaths = setOf("/repo/src/Main.kt"),
                symbolItemCount = 0,
                totalContentBytes = 240,
            ),
            tokenEstimate = 60,
            tokenBudget = 8000,
            maxFileItems = 10,
            maxSymbolItems = 24,
            maxContentBytes = 48_000,
            maxCollectionTimeMs = 250,
            wasTokenTrimmed = false,
            budgetDropSummary = "none",
            skippedStages = emptyList(),
            cacheView = ContextCollectionCacheTelemetry(
                runOutcomes = listOf(
                    ContextCollectionStageCacheOutcome(stage = "codeGraph", outcome = "hit"),
                    ContextCollectionStageCacheOutcome(stage = "relatedFiles", outcome = "hit"),
                    ContextCollectionStageCacheOutcome(stage = "projectStructure", outcome = "hit"),
                ),
            ),
            baselineComparison = ContextCollectionBaselineComparison(
                phase = "warm",
                coldElapsedMs = 180,
                warmElapsedMs = 44,
                savedMs = 136,
                savedPercent = 76,
                firstWarmAfterCold = false,
            ),
        )

        assertFalse(shouldEmitContextTelemetryInfo(telemetry))
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
