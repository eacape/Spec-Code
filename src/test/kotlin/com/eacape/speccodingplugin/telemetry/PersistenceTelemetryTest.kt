package com.eacape.speccodingplugin.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersistenceTelemetryTest {

    @Test
    fun `determinePersistenceTelemetrySeverity should respect thresholds`() {
        assertEquals(PersistenceTelemetrySeverity.SKIP, determinePersistenceTelemetrySeverity(elapsedMs = 99))
        assertEquals(PersistenceTelemetrySeverity.INFO, determinePersistenceTelemetrySeverity(elapsedMs = 100))
        assertEquals(PersistenceTelemetrySeverity.WARN, determinePersistenceTelemetrySeverity(elapsedMs = 300))
    }

    @Test
    fun `sanitizePersistenceTelemetryValue should normalize whitespace and truncate`() {
        assertNull(sanitizePersistenceTelemetryValue(" \n "))

        val normalized = sanitizePersistenceTelemetryValue("line-1\r\nline-2", maxLength = 32)
        assertEquals("line-1  line-2", normalized)

        val truncated = sanitizePersistenceTelemetryValue("abcdefghijklmnopqrstuvwxyz", maxLength = 10)
        assertEquals("abcdefg...", truncated)
    }

    @Test
    fun `PersistenceOperationTelemetry summary should include counts and sorted details`() {
        val telemetry = PersistenceOperationTelemetry(
            component = "SessionManager",
            operation = "searchSessions",
            scope = "sessions",
            elapsedMs = 180,
            outcome = "success",
            limit = 20,
            itemCount = 3,
            byteCount = 512,
            details = buildPersistenceTelemetryDetails(
                "query" to "workflow restore",
                "filter" to "SPEC",
            ),
        )

        val summary = telemetry.summary()

        assertTrue(summary.contains("operation=searchSessions"))
        assertTrue(summary.contains("scope=sessions"))
        assertTrue(summary.contains("limit=20"))
        assertTrue(summary.contains("itemCount=3"))
        assertTrue(summary.contains("byteCount=512"))
        assertTrue(summary.contains("filter=SPEC"))
        assertTrue(summary.contains("query=workflow restore"))
        assertTrue(summary.indexOf("filter=SPEC") < summary.indexOf("query=workflow restore"))
    }
}
