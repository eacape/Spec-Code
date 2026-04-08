package com.eacape.speccodingplugin.context

import kotlin.math.roundToInt

internal enum class ContextTelemetrySeverity {
    SKIP,
    INFO,
    WARN,
}

internal object ContextTelemetryThresholds {
    const val INFO_SLOW_PATH_MS = 150L
    const val WARN_SLOW_PATH_MS = 500L
    const val CACHE_HIT_LOG_INTERVAL = 20L
}

internal fun determineContextTelemetrySeverity(
    elapsedMs: Long,
    infoThresholdMs: Long = ContextTelemetryThresholds.INFO_SLOW_PATH_MS,
    warnThresholdMs: Long = ContextTelemetryThresholds.WARN_SLOW_PATH_MS,
): ContextTelemetrySeverity {
    return when {
        elapsedMs >= warnThresholdMs -> ContextTelemetrySeverity.WARN
        elapsedMs >= infoThresholdMs -> ContextTelemetrySeverity.INFO
        else -> ContextTelemetrySeverity.SKIP
    }
}

internal data class ProjectStructureCacheStats(
    val hitCount: Long,
    val missCount: Long,
    val lastInvalidationReason: String,
) {
    val totalRequests: Long
        get() = hitCount + missCount

    fun hitRatePercent(): Int {
        if (totalRequests <= 0L) {
            return 0
        }
        return ((hitCount.toDouble() / totalRequests.toDouble()) * 100.0).roundToInt()
    }

    fun shouldEmitPeriodicHitLog(
        logEveryHits: Long = ContextTelemetryThresholds.CACHE_HIT_LOG_INTERVAL,
    ): Boolean {
        return hitCount > 0L && logEveryHits > 0L && hitCount % logEveryHits == 0L
    }

    fun summary(): String {
        return "cacheHits=$hitCount, cacheMisses=$missCount, hitRate=${hitRatePercent()}%, lastInvalidation=$lastInvalidationReason"
    }
}

internal data class CodeGraphCacheStats(
    val hitCount: Long,
    val missCount: Long,
    val lastInvalidationReason: String,
) {
    val totalRequests: Long
        get() = hitCount + missCount

    fun hitRatePercent(): Int {
        if (totalRequests <= 0L) {
            return 0
        }
        return ((hitCount.toDouble() / totalRequests.toDouble()) * 100.0).roundToInt()
    }

    fun shouldEmitPeriodicHitLog(
        logEveryHits: Long = ContextTelemetryThresholds.CACHE_HIT_LOG_INTERVAL,
    ): Boolean {
        return hitCount > 0L && logEveryHits > 0L && hitCount % logEveryHits == 0L
    }

    fun summary(): String {
        return "cacheHits=$hitCount, cacheMisses=$missCount, hitRate=${hitRatePercent()}%, lastInvalidation=$lastInvalidationReason"
    }
}

internal data class ContextCollectionTelemetry(
    val operationKey: String,
    val elapsedMs: Long,
    val candidateItemCount: Int,
    val budgetAcceptedItemCount: Int,
    val finalItemCount: Int,
    val budgetStats: ContextCollectionBudgetStats,
    val tokenEstimate: Int,
    val tokenBudget: Int,
    val maxFileItems: Int,
    val maxSymbolItems: Int,
    val maxContentBytes: Int,
    val maxCollectionTimeMs: Long,
    val wasTokenTrimmed: Boolean,
    val budgetDropSummary: String,
    val skippedStages: List<String>,
) {
    fun summary(): String {
        val stageSummary = if (skippedStages.isEmpty()) {
            "none"
        } else {
            skippedStages.joinToString(separator = "|")
        }
        return buildString {
            append("operation=").append(operationKey)
            append(", elapsedMs=").append(elapsedMs).append("/").append(maxCollectionTimeMs)
            append(", candidateItems=").append(candidateItemCount)
            append(", budgetAcceptedItems=").append(budgetAcceptedItemCount)
            append(", finalItems=").append(finalItemCount)
            append(", ").append(budgetStats.summary())
            append("/limits(files=").append(maxFileItems)
            append(", symbols=").append(maxSymbolItems)
            append(", bytes=").append(maxContentBytes).append(")")
            append(", tokens=").append(tokenEstimate).append("/").append(tokenBudget)
            append(", tokenTrimmed=").append(wasTokenTrimmed)
            append(", budgetDrops=").append(budgetDropSummary)
            append(", skippedStages=").append(stageSummary)
        }
    }
}

internal data class CodeGraphBuildTelemetry(
    val rootFilePath: String?,
    val rootFileName: String?,
    val trigger: String,
    val elapsedMs: Long,
    val nodeCount: Int,
    val edgeCount: Int,
    val dependencyEdgeCount: Int,
    val callEdgeCount: Int,
    val dependencyReferenceScans: Int,
    val callReferenceScans: Int,
    val namedElementCount: Int,
    val maxDependencies: Int,
    val maxCallEdges: Int,
    val dependencyLimitHit: Boolean,
    val callLimitHit: Boolean,
    val outcome: String,
) {
    fun summary(): String {
        val fileLabel = rootFileName ?: rootFilePath ?: "unknown"
        return buildString {
            append("trigger=").append(trigger)
            append(", outcome=").append(outcome)
            append(", file=").append(fileLabel)
            rootFilePath?.let {
                append(", path=").append(it)
            }
            append(", elapsedMs=").append(elapsedMs)
            append(", nodes=").append(nodeCount)
            append(", edges=").append(edgeCount)
            append(", dependencyEdges=").append(dependencyEdgeCount)
            append(", callEdges=").append(callEdgeCount)
            append(", dependencyReferenceScans=").append(dependencyReferenceScans)
            append(", callReferenceScans=").append(callReferenceScans)
            append(", namedElements=").append(namedElementCount)
            append(", dependencyLimit=").append(maxDependencies).append("/hit=").append(dependencyLimitHit)
            append(", callLimit=").append(maxCallEdges).append("/hit=").append(callLimitHit)
        }
    }
}
