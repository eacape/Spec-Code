package com.eacape.speccodingplugin.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextCollectionBaselineCoordinatorTest {

    @Test
    fun `attachRunOutcomes should derive hit miss and idle outcomes from cache deltas`() {
        val before = ContextCollectionCacheTelemetry(
            codeGraph = CodeGraphCacheStats(
                hitCount = 0,
                missCount = 1,
                lastInvalidationReason = "cold-start",
            ),
            relatedFiles = RelatedFileCacheStats(
                hitCount = 2,
                missCount = 1,
                lastInvalidationReason = "document-change:main.ts",
            ),
            projectStructure = ProjectStructureCacheStats(
                hitCount = 4,
                missCount = 2,
                lastInvalidationReason = "vfs-create:docs/guide.md",
            ),
        )
        val after = ContextCollectionCacheTelemetry(
            codeGraph = CodeGraphCacheStats(
                hitCount = 1,
                missCount = 1,
                lastInvalidationReason = "cold-start",
            ),
            relatedFiles = RelatedFileCacheStats(
                hitCount = 2,
                missCount = 2,
                lastInvalidationReason = "document-change:main.ts",
            ),
            projectStructure = ProjectStructureCacheStats(
                hitCount = 4,
                missCount = 2,
                lastInvalidationReason = "vfs-create:docs/guide.md",
            ),
        )

        val telemetry = ContextCollectionBaselineCoordinator.attachRunOutcomes(before, after)

        assertEquals(
            listOf("codeGraph:hit", "relatedFiles:miss", "projectStructure:idle"),
            telemetry?.runOutcomes?.map { outcome -> outcome.summary() },
        )
        assertEquals(
            ContextCollectionCachePhase.MIXED,
            ContextCollectionBaselineCoordinator.determinePhase(telemetry),
        )
    }

    @Test
    fun `baseline tracker should compare warm run against previous cold run`() {
        val tracker = ContextCollectionBaselineTracker()
        val scenarioKey = ContextCollectionBaselineCoordinator.scenarioKey(
            operationKey = "collectContext",
            config = ContextConfig(
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                includeImportDependencies = true,
                includeProjectStructure = false,
                preferGraphRelatedContext = false,
            ),
        )

        val cold = tracker.record(
            scenarioKey = scenarioKey,
            elapsedMs = 320,
            phase = ContextCollectionCachePhase.COLD,
        )
        val warm = tracker.record(
            scenarioKey = scenarioKey,
            elapsedMs = 90,
            phase = ContextCollectionCachePhase.WARM,
        )

        assertEquals("cold", cold?.phase)
        assertEquals("warm", warm?.phase)
        assertEquals(320L, warm?.coldElapsedMs)
        assertEquals(90L, warm?.warmElapsedMs)
        assertEquals(230L, warm?.savedMs)
        assertEquals(72, warm?.savedPercent)
        assertTrue(warm?.firstWarmAfterCold == true)
        assertTrue(warm?.hasComparableBaseline() == true)
        assertTrue(warm?.hasImprovement() == true)
        assertTrue(warm?.shouldEmitWarmInsight() == true)
        assertTrue(warm?.summary()?.contains("warmVsCold=90ms/320ms") == true)
        assertTrue(warm?.summary()?.contains("savedPercent=72%") == true)
    }

    @Test
    fun `baseline tracker should only flag the first warm run after a cold baseline`() {
        val tracker = ContextCollectionBaselineTracker()
        val scenarioKey = ContextCollectionBaselineCoordinator.scenarioKey(
            operationKey = "collectContext",
            config = ContextConfig(
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                includeImportDependencies = true,
                includeProjectStructure = true,
                preferGraphRelatedContext = true,
            ),
        )

        tracker.record(
            scenarioKey = scenarioKey,
            elapsedMs = 280,
            phase = ContextCollectionCachePhase.COLD,
        )
        val firstWarm = tracker.record(
            scenarioKey = scenarioKey,
            elapsedMs = 120,
            phase = ContextCollectionCachePhase.WARM,
        )
        val secondWarm = tracker.record(
            scenarioKey = scenarioKey,
            elapsedMs = 110,
            phase = ContextCollectionCachePhase.WARM,
        )

        assertTrue(firstWarm?.firstWarmAfterCold == true)
        assertTrue(firstWarm?.shouldEmitWarmInsight() == true)
        assertTrue(secondWarm?.firstWarmAfterCold == false)
        assertTrue(secondWarm?.shouldEmitWarmInsight() == false)
    }
}
