package com.eacape.speccodingplugin.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class CliCommandRuntimeTest {

    @Test
    fun `start should retry with cmd fallback on windows and carry environment adjustments`() {
        val executableDir = File("build/cli-runtime-tools-${System.nanoTime()}").apply { mkdirs() }
        val capturedPlans = mutableListOf<CliCommandLaunchPlan>()
        val runtime = CliCommandRuntime(
            processStarter = { plan ->
                capturedPlans += plan
                if (capturedPlans.size == 1) {
                    error("CreateProcess error=193, %1 is not a valid Win32 application")
                }
                CompletedProcess(stdout = "Claude 1.0.0\n")
            },
            osNameProvider = { "Windows 11" },
            environmentProvider = { mapOf("Path" to "C:/Windows/System32") },
            gitBashPathProvider = { "C:/Program Files/Git/bin/bash.exe" },
        )

        val process = runtime.start(
            CliCommandRequest(
                executable = File(executableDir, "claude").path,
                args = listOf("--version"),
                redirectErrorStream = true,
            ),
        )

        assertInstanceOf(CompletedProcess::class.java, process)
        assertEquals(listOf(File(executableDir, "claude").path, "--version"), capturedPlans[0].command)
        assertEquals(listOf("cmd", "/c", File(executableDir, "claude").path, "--version"), capturedPlans[1].command)
        assertEquals("C:/Program Files/Git/bin/bash.exe", capturedPlans[0].environmentOverrides["CLAUDE_CODE_GIT_BASH_PATH"])
        assertTrue(
            capturedPlans[0].environmentOverrides.getValue("Path").startsWith(executableDir.path),
            "Expected executable parent directory to be prepended to PATH override: ${capturedPlans[0].environmentOverrides}",
        )
    }

    @Test
    fun `execute should capture merged output and exit code`() {
        val runtime = CliCommandRuntime(
            processStarter = {
                CompletedProcess(stdout = "codex 0.42.0\n")
            },
        )

        val result = runtime.execute(
            request = CliCommandRequest(
                executable = "codex",
                args = listOf("--version"),
                redirectErrorStream = true,
            ),
            timeoutMs = 100,
        )

        assertEquals("codex 0.42.0", result.output)
        assertEquals(0, result.exitCode)
        assertTrue(!result.timedOut)
        assertEquals(null, result.startupDiagnostic)
    }

    @Test
    fun `execute should classify missing executable separately`() {
        val runtime = CliCommandRuntime(
            processStarter = {
                error("CreateProcess error=2, The system cannot find the file specified")
            },
            osNameProvider = { "Windows 11" },
        )

        val result = runtime.execute(
            request = CliCommandRequest(
                executable = "missing-cli",
                args = listOf("--version"),
            ),
            timeoutMs = 100,
        )

        val diagnostic = requireNotNull(result.startupDiagnostic)
        assertEquals(CliCommandFailureKind.EXECUTABLE_NOT_FOUND, diagnostic.kind)
        assertTrue(diagnostic.renderMessage().contains("cli executable was not found"))
    }

    @Test
    fun `execute should classify explicit missing executable path separately`() {
        val runtime = CliCommandRuntime(
            processStarter = {
                error("CreateProcess error=2, The system cannot find the file specified")
            },
            osNameProvider = { "Windows 11" },
        )

        val result = runtime.execute(
            request = CliCommandRequest(
                executable = "C:/broken/claude.cmd",
                args = listOf("--version"),
            ),
            timeoutMs = 100,
        )

        val diagnostic = requireNotNull(result.startupDiagnostic)
        assertEquals(CliCommandFailureKind.EXECUTABLE_PATH_INVALID, diagnostic.kind)
        assertTrue(diagnostic.renderMessage().contains("configured cli path was not found"))
        assertTrue(diagnostic.renderDetail().contains("C:/broken/claude.cmd"))
    }

    @Test
    fun `execute should classify unavailable working directory separately`() {
        val missingDir = File("build/cli-missing-dir-${System.nanoTime()}").absoluteFile
        val runtime = CliCommandRuntime(
            processStarter = {
                error("CreateProcess error=267, The directory name is invalid")
            },
            osNameProvider = { "Windows 11" },
        )

        val result = runtime.execute(
            request = CliCommandRequest(
                executable = "codex",
                args = listOf("exec"),
                workingDirectory = missingDir,
            ),
            timeoutMs = 100,
        )

        val diagnostic = requireNotNull(result.startupDiagnostic)
        assertEquals(CliCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE, diagnostic.kind)
        assertTrue(diagnostic.renderMessage().contains(missingDir.absolutePath))
    }

    private class CompletedProcess(
        stdout: String,
        private val completedExitCode: Int = 0,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()
        private var alive = true

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun getOutputStream(): OutputStream = output

        override fun waitFor(): Int {
            alive = false
            return completedExitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            alive = false
            return true
        }

        override fun exitValue(): Int {
            check(!alive) { "Process is still alive" }
            return completedExitCode
        }

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive
    }
}
