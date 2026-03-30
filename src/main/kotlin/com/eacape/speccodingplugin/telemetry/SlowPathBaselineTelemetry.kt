package com.eacape.speccodingplugin.telemetry

import com.intellij.openapi.diagnostic.Logger
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object SlowPathBaselineTelemetryThresholds {
    const val SUMMARY_LOG_INTERVAL_SAMPLES = 25L
    const val MAX_SAMPLES_PER_OPERATION = 64
    const val TOP_OPERATION_COUNT = 3
}

internal data class SlowPathBaselineSample(
    val operationKey: String,
    val elapsedMs: Long,
    val timedOut: Boolean = false,
)

internal data class SlowPathOperationBaseline(
    val operationKey: String,
    val sampleCount: Int,
    val averageElapsedMs: Long,
    val p95ElapsedMs: Long,
    val maxElapsedMs: Long,
    val timeoutCount: Int,
) {
    fun timeoutRatioPercent(): Int {
        if (sampleCount <= 0 || timeoutCount <= 0) {
            return 0
        }
        return ((timeoutCount.toDouble() / sampleCount.toDouble()) * 100.0).roundToInt()
    }

    fun summary(): String {
        return buildString {
            append(operationKey)
            append("{samples=").append(sampleCount)
            append(", avgMs=").append(averageElapsedMs)
            append(", p95Ms=").append(p95ElapsedMs)
            append(", maxMs=").append(maxElapsedMs)
            append(", timeoutRatio=").append(timeoutRatioPercent()).append("%}")
        }
    }
}

internal data class SlowPathBaselineSummary(
    val totalSamples: Long,
    val trackedOperations: Int,
    val topOperations: List<SlowPathOperationBaseline>,
) {
    fun summary(): String {
        return buildString {
            append("totalSamples=").append(totalSamples)
            append(", trackedOperations=").append(trackedOperations)
            append(", top3=")
            append(
                topOperations.joinToString(separator = "; ") { operation ->
                    operation.summary()
                },
            )
        }
    }
}

internal class SlowPathBaselineTracker(
    private val summaryEverySamples: Long = SlowPathBaselineTelemetryThresholds.SUMMARY_LOG_INTERVAL_SAMPLES,
    private val maxSamplesPerOperation: Int = SlowPathBaselineTelemetryThresholds.MAX_SAMPLES_PER_OPERATION,
    private val topOperationCount: Int = SlowPathBaselineTelemetryThresholds.TOP_OPERATION_COUNT,
) {
    private data class RecordedSample(
        val elapsedMs: Long,
        val timedOut: Boolean,
    )

    private val samplesByOperation = linkedMapOf<String, ArrayDeque<RecordedSample>>()
    private var totalSamples = 0L

    @Synchronized
    fun record(sample: SlowPathBaselineSample): SlowPathBaselineSummary? {
        val operationKey = sample.operationKey.trim()
        if (operationKey.isBlank()) {
            return null
        }

        val bucket = samplesByOperation.getOrPut(operationKey) { ArrayDeque() }
        if (bucket.size >= maxSamplesPerOperation) {
            bucket.removeFirst()
        }
        bucket.addLast(
            RecordedSample(
                elapsedMs = sample.elapsedMs.coerceAtLeast(0L),
                timedOut = sample.timedOut,
            ),
        )

        totalSamples += 1
        if (summaryEverySamples <= 0L || totalSamples % summaryEverySamples != 0L) {
            return null
        }

        val topOperations = samplesByOperation.entries
            .map { (operation, samples) -> buildOperationBaseline(operation, samples) }
            .sortedWith(
                compareByDescending<SlowPathOperationBaseline> { it.p95ElapsedMs }
                    .thenByDescending { it.averageElapsedMs }
                    .thenByDescending { it.maxElapsedMs }
                    .thenByDescending { it.timeoutRatioPercent() }
                    .thenBy { it.operationKey },
            )
            .take(topOperationCount.coerceAtLeast(1))

        return SlowPathBaselineSummary(
            totalSamples = totalSamples,
            trackedOperations = samplesByOperation.size,
            topOperations = topOperations,
        )
    }

    private fun buildOperationBaseline(
        operationKey: String,
        samples: ArrayDeque<RecordedSample>,
    ): SlowPathOperationBaseline {
        val normalizedSamples = samples.toList()
        val elapsedSamples = normalizedSamples.map(RecordedSample::elapsedMs)
        val sortedElapsedSamples = elapsedSamples.sorted()
        val sampleCount = elapsedSamples.size.coerceAtLeast(1)
        val averageElapsedMs = (elapsedSamples.sum().toDouble() / sampleCount.toDouble()).roundToInt().toLong()
        val p95Index = ceil(sampleCount.toDouble() * 0.95).toInt().coerceAtLeast(1) - 1
        return SlowPathOperationBaseline(
            operationKey = operationKey,
            sampleCount = normalizedSamples.size,
            averageElapsedMs = averageElapsedMs,
            p95ElapsedMs = sortedElapsedSamples[p95Index],
            maxElapsedMs = sortedElapsedSamples.lastOrNull() ?: 0L,
            timeoutCount = normalizedSamples.count { it.timedOut },
        )
    }
}

internal object RuntimeSlowPathBaselineRegistry {
    private val tracker = SlowPathBaselineTracker()

    fun record(sample: SlowPathBaselineSample): SlowPathBaselineSummary? {
        return tracker.record(sample)
    }
}

internal fun emitSlowPathBaseline(
    logger: Logger,
    sample: SlowPathBaselineSample,
) {
    RuntimeSlowPathBaselineRegistry.record(sample)?.let { summary ->
        logger.info("Runtime slow path baselines: ${summary.summary()}")
    }
}
