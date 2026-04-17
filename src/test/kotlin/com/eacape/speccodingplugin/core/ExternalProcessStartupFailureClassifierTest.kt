package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class ExternalProcessStartupFailureClassifierTest {

    @Test
    fun `classify should prioritize missing working directory`() {
        val missingDir = File("build/external-process-missing-dir-${System.nanoTime()}")

        val kind = ExternalProcessStartupFailureClassifier.classify(
            startupErrorMessage = "CreateProcess error=2, The system cannot find the file specified",
            workingDirectory = missingDir,
        )

        assertEquals(ExternalProcessStartupFailureKind.WORKING_DIRECTORY_UNAVAILABLE, kind)
    }

    @Test
    fun `classify should detect access denied errors`() {
        val kind = ExternalProcessStartupFailureClassifier.classify(
            startupErrorMessage = "CreateProcess error=5, Access is denied",
        )

        assertEquals(ExternalProcessStartupFailureKind.ACCESS_DENIED, kind)
    }

    @Test
    fun `classify should detect missing executable with custom hints`() {
        val kind = ExternalProcessStartupFailureClassifier.classify(
            startupErrorMessage = "missing tool",
            missingExecutableHints = setOf("missing tool"),
        )

        assertEquals(ExternalProcessStartupFailureKind.EXECUTABLE_NOT_FOUND, kind)
    }

    @Test
    fun `classify should detect generic executable lookup failures`() {
        val kind = ExternalProcessStartupFailureClassifier.classify(
            startupErrorMessage = "sh: demo-command: command not found",
        )

        assertEquals(ExternalProcessStartupFailureKind.EXECUTABLE_NOT_FOUND, kind)
    }

    @Test
    fun `classify should fall back to startup failed`() {
        val kind = ExternalProcessStartupFailureClassifier.classify(
            startupErrorMessage = "git unavailable",
        )

        assertEquals(ExternalProcessStartupFailureKind.STARTUP_FAILED, kind)
    }
}
