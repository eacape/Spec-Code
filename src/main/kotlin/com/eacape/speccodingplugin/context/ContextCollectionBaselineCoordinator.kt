package com.eacape.speccodingplugin.context

import kotlin.math.roundToInt

internal enum class ContextCollectionCachePhase(val wireName: String) {
    COLD("cold"),
    WARM("warm"),
    MIXED("mixed"),
    IDLE("idle"),
}

internal enum class ContextCollectionStageCacheOutcomeKind(val wireName: String) {
    HIT("hit"),
    MISS("miss"),
    IDLE("idle"),
}

internal data class ContextCollectionStageCacheOutcome(
    val stage: String,
    val outcome: String,
) {
    fun summary(): String = "$stage:$outcome"
}

internal data class ContextCollectionBaselineComparison(
    val phase: String,
    val coldElapsedMs: Long? = null,
    val warmElapsedMs: Long? = null,
    val savedMs: Long? = null,
    val savedPercent: Int? = null,
    val firstWarmAfterCold: Boolean = false,
) {
    fun hasComparableBaseline(): Boolean {
        return coldElapsedMs != null && warmElapsedMs != null
    }

    fun hasImprovement(): Boolean {
        return phase == ContextCollectionCachePhase.WARM.wireName &&
            hasComparableBaseline() &&
            (savedMs ?: 0L) > 0L &&
            (savedPercent ?: 0) > 0
    }

    fun shouldEmitWarmInsight(): Boolean {
        return firstWarmAfterCold && hasImprovement()
    }

    fun summary(): String {
        return buildString {
            append("phase=").append(phase)
            if (coldElapsedMs != null && warmElapsedMs != null) {
                append(", warmVsCold=").append(warmElapsedMs).append("ms/").append(coldElapsedMs).append("ms")
                savedMs?.let { append(", savedMs=").append(it) }
                savedPercent?.let { append(", savedPercent=").append(it).append('%') }
                append(", firstWarmAfterCold=").append(firstWarmAfterCold)
            }
        }
    }
}

internal class ContextCollectionBaselineTracker {
    private data class ScenarioBaseline(
        var lastColdElapsedMs: Long? = null,
        var lastComparedColdElapsedMs: Long? = null,
    )

    private val baselineByScenario = linkedMapOf<String, ScenarioBaseline>()

    @Synchronized
    fun record(
        scenarioKey: String,
        elapsedMs: Long,
        phase: ContextCollectionCachePhase,
    ): ContextCollectionBaselineComparison? {
        val normalizedScenarioKey = scenarioKey.trim()
        if (normalizedScenarioKey.isEmpty() || phase == ContextCollectionCachePhase.IDLE) {
            return null
        }

        val normalizedElapsedMs = elapsedMs.coerceAtLeast(0L)
        val baseline = baselineByScenario.getOrPut(normalizedScenarioKey) { ScenarioBaseline() }
        return when (phase) {
            ContextCollectionCachePhase.COLD -> {
                baseline.lastColdElapsedMs = normalizedElapsedMs
                baseline.lastComparedColdElapsedMs = null
                ContextCollectionBaselineComparison(phase = phase.wireName)
            }

            ContextCollectionCachePhase.WARM -> {
                val coldElapsedMs = baseline.lastColdElapsedMs
                val firstWarmAfterCold = coldElapsedMs != null && baseline.lastComparedColdElapsedMs != coldElapsedMs
                if (coldElapsedMs != null) {
                    baseline.lastComparedColdElapsedMs = coldElapsedMs
                }
                ContextCollectionBaselineComparison(
                    phase = phase.wireName,
                    coldElapsedMs = coldElapsedMs,
                    warmElapsedMs = normalizedElapsedMs,
                    savedMs = coldElapsedMs?.minus(normalizedElapsedMs),
                    savedPercent = coldElapsedMs
                        ?.takeIf { it > 0L }
                        ?.let { cold ->
                            (((cold - normalizedElapsedMs).toDouble() / cold.toDouble()) * 100.0).roundToInt()
                        },
                    firstWarmAfterCold = firstWarmAfterCold,
                )
            }

            ContextCollectionCachePhase.MIXED -> {
                ContextCollectionBaselineComparison(phase = phase.wireName)
            }

            ContextCollectionCachePhase.IDLE -> null
        }
    }
}

internal object ContextCollectionBaselineCoordinator {
    fun scenarioKey(
        operationKey: String,
        config: ContextConfig,
    ): String {
        return buildString {
            append(operationKey)
            append("[selected=").append(config.includeSelectedCode)
            append(",scope=").append(config.includeContainingScope)
            append(",file=").append(config.includeCurrentFile)
            append(",imports=").append(config.includeImportDependencies)
            append(",structure=").append(config.includeProjectStructure)
            append(",graph=").append(config.preferGraphRelatedContext)
            append(",maxFiles=").append(config.maxFileItems)
            append(",maxSymbols=").append(config.maxSymbolItems)
            append(",maxBytes=").append(config.maxContentBytes)
            append(",maxMs=").append(config.maxCollectionTimeMs)
            append(",tokenBudget=").append(config.tokenBudget)
            append(']')
        }
    }

    fun attachRunOutcomes(
        before: ContextCollectionCacheTelemetry?,
        after: ContextCollectionCacheTelemetry?,
    ): ContextCollectionCacheTelemetry? {
        val cacheTelemetry = after ?: return null
        return cacheTelemetry.copy(
            runOutcomes = buildList {
                stageOutcome("codeGraph", before?.codeGraph, after.codeGraph)?.let(::add)
                stageOutcome("relatedFiles", before?.relatedFiles, after.relatedFiles)?.let(::add)
                stageOutcome("projectStructure", before?.projectStructure, after.projectStructure)?.let(::add)
            },
        )
    }

    fun determinePhase(cacheTelemetry: ContextCollectionCacheTelemetry?): ContextCollectionCachePhase {
        val outcomes = cacheTelemetry?.runOutcomes.orEmpty()
        val activeOutcomes = outcomes.filterNot { it.outcome == ContextCollectionStageCacheOutcomeKind.IDLE.wireName }
        if (activeOutcomes.isEmpty()) {
            return ContextCollectionCachePhase.IDLE
        }

        val allMisses = activeOutcomes.all { it.outcome == ContextCollectionStageCacheOutcomeKind.MISS.wireName }
        if (allMisses) {
            return ContextCollectionCachePhase.COLD
        }

        val allHits = activeOutcomes.all { it.outcome == ContextCollectionStageCacheOutcomeKind.HIT.wireName }
        if (allHits) {
            return ContextCollectionCachePhase.WARM
        }

        return ContextCollectionCachePhase.MIXED
    }

    private fun stageOutcome(
        stage: String,
        before: CodeGraphCacheStats?,
        after: CodeGraphCacheStats?,
    ): ContextCollectionStageCacheOutcome? {
        return stageOutcome(stage, before?.toCounters(), after?.toCounters())
    }

    private fun stageOutcome(
        stage: String,
        before: RelatedFileCacheStats?,
        after: RelatedFileCacheStats?,
    ): ContextCollectionStageCacheOutcome? {
        return stageOutcome(stage, before?.toCounters(), after?.toCounters())
    }

    private fun stageOutcome(
        stage: String,
        before: ProjectStructureCacheStats?,
        after: ProjectStructureCacheStats?,
    ): ContextCollectionStageCacheOutcome? {
        return stageOutcome(stage, before?.toCounters(), after?.toCounters())
    }

    private fun stageOutcome(
        stage: String,
        before: ContextCollectionCacheCounters?,
        after: ContextCollectionCacheCounters?,
    ): ContextCollectionStageCacheOutcome? {
        if (before == null || after == null) {
            return null
        }

        val outcome = when {
            after.missCount > before.missCount -> ContextCollectionStageCacheOutcomeKind.MISS
            after.hitCount > before.hitCount -> ContextCollectionStageCacheOutcomeKind.HIT
            else -> ContextCollectionStageCacheOutcomeKind.IDLE
        }
        return ContextCollectionStageCacheOutcome(
            stage = stage,
            outcome = outcome.wireName,
        )
    }

    private fun CodeGraphCacheStats.toCounters(): ContextCollectionCacheCounters {
        return ContextCollectionCacheCounters(hitCount = hitCount, missCount = missCount)
    }

    private fun RelatedFileCacheStats.toCounters(): ContextCollectionCacheCounters {
        return ContextCollectionCacheCounters(hitCount = hitCount, missCount = missCount)
    }

    private fun ProjectStructureCacheStats.toCounters(): ContextCollectionCacheCounters {
        return ContextCollectionCacheCounters(hitCount = hitCount, missCount = missCount)
    }
}

private data class ContextCollectionCacheCounters(
    val hitCount: Long,
    val missCount: Long,
)
