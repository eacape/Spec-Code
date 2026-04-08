package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CliToolAvailabilityIssueTest {

    @Test
    fun `startup diagnostic should map to executable-not-found issue`() {
        val diagnostic = CliCommandStartupDiagnostic(
            kind = CliCommandFailureKind.EXECUTABLE_NOT_FOUND,
            executable = "missing-cli",
            workingDirectory = null,
            launchCommand = listOf("missing-cli", "--version"),
        )

        val issue = CliToolAvailabilityIssues.fromStartupDiagnostic(diagnostic)

        assertEquals(CliToolAvailabilityIssueKind.EXECUTABLE_NOT_FOUND, issue.kind)
        assertTrue(issue.renderSummary().contains("cli executable was not found"))
    }

    @Test
    fun `command failed issue should keep exit code and first output line`() {
        val issue = CliToolAvailabilityIssues.commandFailed(
            exitCode = 17,
            output = "\nerror: unsupported flag --json\nsecond line\n",
        )

        assertEquals(CliToolAvailabilityIssueKind.COMMAND_FAILED, issue.kind)
        assertTrue(issue.renderSummary().contains("17"))
        assertTrue(issue.renderSummary().contains("unsupported flag --json"))
    }

    @Test
    fun `timeout issue should render timeout seconds`() {
        val issue = CliToolAvailabilityIssues.timeout(12)

        assertEquals(CliToolAvailabilityIssueKind.COMMAND_TIMEOUT, issue.kind)
        assertEquals("cli probe timed out after 12s", issue.renderSummary())
    }
}
