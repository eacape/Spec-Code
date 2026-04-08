package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class WorkflowCommandProcessRuntimeTest {

    private val existingWorkingDirectory = Path.of("").toAbsolutePath().normalize().toFile()

    @Test
    fun `execute should surface executable startup failures with structured diagnostic`() {
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> error("missing executable") },
        )

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = existingWorkingDirectory,
            timeoutSeconds = 1,
        )

        val failed = assertInstanceOf(WorkflowCommandProcessRunResult.FailedToStart::class.java, result)
        assertEquals(WorkflowCommandFailureKind.EXECUTABLE_NOT_FOUND, failed.diagnostic.kind)
        assertEquals("missing executable", failed.diagnostic.startupErrorMessage)
        assertTrue(failed.errorMessage.contains("workflow executable was not found"))
    }

    @Test
    fun `execute should classify unavailable working directory separately`() {
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> error("The directory name is invalid") },
        )
        val missingDir = Path.of("build", "workflow-missing-dir-${System.nanoTime()}").toAbsolutePath().toFile()

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = missingDir,
            timeoutSeconds = 1,
        )

        val failed = assertInstanceOf(WorkflowCommandProcessRunResult.FailedToStart::class.java, result)
        assertEquals(WorkflowCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE, failed.diagnostic.kind)
        assertTrue(failed.diagnostic.renderDetail().contains("working directory is unavailable"))
    }

    @Test
    fun `execute should classify access denied separately`() {
        val runtime = WorkflowCommandProcessRuntime(
            processStarter = { _, _ -> error("CreateProcess error=5, Access is denied") },
        )

        val result = runtime.execute(
            commandKey = "gradle verify",
            launchCommand = listOf("gradle", "verify"),
            workingDirectory = existingWorkingDirectory,
            timeoutSeconds = 1,
        )

        val failed = assertInstanceOf(WorkflowCommandProcessRunResult.FailedToStart::class.java, result)
        assertEquals(WorkflowCommandFailureKind.ACCESS_DENIED, failed.diagnostic.kind)
        assertTrue(failed.diagnostic.renderDetail().contains("access denied"))
    }
}
