package com.eacape.speccodingplugin.telemetry

import com.intellij.openapi.diagnostic.Logger

internal enum class PersistenceTelemetrySeverity {
    SKIP,
    INFO,
    WARN,
}

internal object PersistenceTelemetryThresholds {
    const val INFO_SLOW_PATH_MS = 100L
    const val WARN_SLOW_PATH_MS = 300L
    const val MAX_DETAIL_LENGTH = 96
}

internal fun determinePersistenceTelemetrySeverity(
    elapsedMs: Long,
    infoThresholdMs: Long = PersistenceTelemetryThresholds.INFO_SLOW_PATH_MS,
    warnThresholdMs: Long = PersistenceTelemetryThresholds.WARN_SLOW_PATH_MS,
): PersistenceTelemetrySeverity {
    return when {
        elapsedMs >= warnThresholdMs -> PersistenceTelemetrySeverity.WARN
        elapsedMs >= infoThresholdMs -> PersistenceTelemetrySeverity.INFO
        else -> PersistenceTelemetrySeverity.SKIP
    }
}

internal fun sanitizePersistenceTelemetryValue(
    raw: String?,
    maxLength: Int = PersistenceTelemetryThresholds.MAX_DETAIL_LENGTH,
): String? {
    require(maxLength > 3) { "maxLength must be greater than 3" }
    val normalized = raw
        ?.replace("\r", " ")
        ?.replace("\n", " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return if (normalized.length <= maxLength) {
        normalized
    } else {
        normalized.take(maxLength - 3) + "..."
    }
}

internal fun buildPersistenceTelemetryDetails(vararg entries: Pair<String, String?>): Map<String, String> {
    return buildMap {
        entries.forEach { (key, value) ->
            sanitizePersistenceTelemetryValue(value)?.let { normalized ->
                put(key, normalized)
            }
        }
    }
}

internal data class PersistenceOperationTelemetry(
    val component: String,
    val operation: String,
    val scope: String,
    val elapsedMs: Long,
    val outcome: String,
    val itemCount: Int? = null,
    val limit: Int? = null,
    val byteCount: Long? = null,
    val details: Map<String, String> = emptyMap(),
) {
    fun summary(): String {
        return buildString {
            append("operation=").append(operation)
            append(", outcome=").append(outcome)
            append(", scope=").append(scope)
            append(", elapsedMs=").append(elapsedMs)
            limit?.let { append(", limit=").append(it) }
            itemCount?.let { append(", itemCount=").append(it) }
            byteCount?.let { append(", byteCount=").append(it) }
            details.toSortedMap().forEach { (key, value) ->
                append(", ").append(key).append("=").append(value)
            }
        }
    }
}

internal fun emitPersistenceTelemetry(
    logger: Logger,
    telemetry: PersistenceOperationTelemetry,
    error: Throwable? = null,
) {
    emitSlowPathBaseline(
        logger = logger,
        sample = SlowPathBaselineSample(
            operationKey = "${telemetry.component}.${telemetry.operation}",
            elapsedMs = telemetry.elapsedMs,
            timedOut = false,
        ),
    )

    val severity = determinePersistenceTelemetrySeverity(telemetry.elapsedMs)
    if (severity == PersistenceTelemetrySeverity.SKIP) {
        return
    }

    val message = "${telemetry.component} slow path: ${telemetry.summary()}"
    if (error != null) {
        when (severity) {
            PersistenceTelemetrySeverity.WARN -> logger.warn(message, error)
            PersistenceTelemetrySeverity.INFO -> logger.info(
                "$message, error=${error.javaClass.simpleName}:${sanitizePersistenceTelemetryValue(error.message) ?: "unknown"}",
            )
            PersistenceTelemetrySeverity.SKIP -> Unit
        }
        return
    }

    when (severity) {
        PersistenceTelemetrySeverity.WARN -> logger.warn(message)
        PersistenceTelemetrySeverity.INFO -> logger.info(message)
        PersistenceTelemetrySeverity.SKIP -> Unit
    }
}
