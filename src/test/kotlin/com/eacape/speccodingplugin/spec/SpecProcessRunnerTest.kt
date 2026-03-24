package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecProcessRunnerTest {

    @TempDir
    lateinit var tempDir: Path

    private val runner = SpecProcessRunner()

    @Test
    fun `prepare should normalize working directory and merge verify defaults`() {
        val projectRoot = Files.createDirectories(tempDir.resolve("project"))
        Files.createDirectories(projectRoot.resolve("nested"))

        val request = runner.prepare(
            projectRoot = projectRoot,
            verifyConfig = SpecVerifyConfig(
                defaultWorkingDirectory = "nested",
                defaultTimeoutMs = 45_000,
                defaultOutputLimitChars = 2_048,
                redactionPatterns = listOf("(?i)session=\\S+"),
            ),
            command = SpecVerifyCommand(
                id = "fixture-echo",
                command = javaCommand(projectRoot, "echo"),
                redactionPatterns = listOf("payload=A+"),
            ),
        )

        assertEquals(projectRoot.resolve("nested").toAbsolutePath().normalize(), request.workingDirectory)
        assertEquals(45_000, request.timeoutMs)
        assertEquals(2_048, request.outputLimitChars)
        assertEquals(listOf("(?i)session=\\S+", "payload=A+"), request.redactionPatterns)
    }

    @Test
    fun `prepare should reject working directory outside project root`() {
        val projectRoot = Files.createDirectories(tempDir.resolve("project"))

        val error = assertThrows(VerifyCommandWorkingDirectoryError::class.java) {
            runner.prepare(
                projectRoot = projectRoot,
                verifyConfig = SpecVerifyConfig(),
                command = SpecVerifyCommand(
                    id = "bad-dir",
                    command = javaCommand(projectRoot, "echo"),
                    workingDirectory = "../outside",
                ),
            )
        }

        assertTrue(error.message?.contains("escapes project root") == true)
    }

    @Test
    fun `prepare should reject command tokens with line breaks`() {
        val projectRoot = Files.createDirectories(tempDir.resolve("project"))

        val error = assertThrows(InvalidVerifyCommandError::class.java) {
            runner.prepare(
                projectRoot = projectRoot,
                verifyConfig = SpecVerifyConfig(),
                command = SpecVerifyCommand(
                    id = "bad-command",
                    command = listOf("gradle", "test\n--offline"),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("line breaks or NUL", ignoreCase = true))
    }

    @Test
    fun `execute should redact sensitive output and truncate long streams`() {
        val projectRoot = Files.createDirectories(tempDir.resolve("project"))

        val request = runner.prepare(
            projectRoot = projectRoot,
            verifyConfig = SpecVerifyConfig(
                defaultOutputLimitChars = 64,
            ),
            command = SpecVerifyCommand(
                id = "echo",
                command = javaCommand(projectRoot, "echo"),
            ),
        )

        val result = runner.execute(request)

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.redacted)
        assertTrue(result.stdout.contains("token=<redacted>"))
        assertTrue(result.stderr.contains("password=<redacted>"))
        assertFalse(result.stdout.contains("super-secret-token"))
        assertFalse(result.stderr.contains("hunter2"))
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stdout.contains("[truncated"))
    }

    @Test
    fun `execute should redact extended secret formats`() {
        val projectRoot = Files.createDirectories(tempDir.resolve("project"))

        val request = runner.prepare(
            projectRoot = projectRoot,
            verifyConfig = SpecVerifyConfig(
                defaultOutputLimitChars = 4_096,
            ),
            command = SpecVerifyCommand(
                id = "echo-extended",
                command = javaCommand(projectRoot, "echo-extended"),
            ),
        )

        val result = runner.execute(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.redacted)
        assertTrue(result.stdout.contains("Authorization: Basic <redacted>"))
        assertTrue(result.stdout.contains("{\"apiKey\":\"<redacted>\",\"access_token\":\"<redacted>\"}"))
        assertTrue(result.stdout.contains("https://user:<redacted>@example.com/resource"))
        assertFalse(result.stdout.contains("dXNlcjpwYXNz"))
        assertFalse(result.stdout.contains("sk-live-secret"))
        assertFalse(result.stdout.contains("ghp_secretToken"))
        assertFalse(result.stdout.contains("super-secret"))
    }

    @Test
    fun `sanitizeCommandForDisplay should redact inline command arguments`() {
        val sanitized = runner.sanitizeCommandForDisplay(
            commandId = "curl",
            command = listOf(
                "curl",
                "-H",
                "Authorization: Bearer ghp_super-secret",
                "--data",
                "{\"apiKey\":\"sk-live-secret\"}",
                "https://user:super-secret@example.com/resource",
            ),
            customPatterns = emptyList(),
        )

        assertTrue(sanitized.redacted)
        assertTrue(sanitized.text.contains("Authorization: Bearer <redacted>"))
        assertTrue(sanitized.text.contains("{\"apiKey\":\"<redacted>\"}"))
        assertTrue(sanitized.text.contains("https://user:<redacted>@example.com/resource"))
        assertFalse(sanitized.text.contains("ghp_super-secret"))
        assertFalse(sanitized.text.contains("sk-live-secret"))
        assertFalse(sanitized.text.contains("super-secret@example.com"))
    }

    @Test
    fun `execute should terminate command when timeout is reached`() {
        val projectRoot = Files.createDirectories(tempDir.resolve("project"))

        val request = runner.prepare(
            projectRoot = projectRoot,
            verifyConfig = SpecVerifyConfig(
                defaultTimeoutMs = 750,
            ),
            command = SpecVerifyCommand(
                id = "sleep",
                command = javaCommand(projectRoot, "sleep", "5000"),
            ),
        )

        val result = runner.execute(request)

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue(result.durationMs >= 700)
    }

    private fun javaCommand(projectRoot: Path, vararg args: String): List<String> {
        val sourceFile = writeFixtureSource(projectRoot)
        return buildList {
            add(javaExecutable().toString())
            add(sourceFile.toString())
            addAll(args)
        }
    }

    private fun writeFixtureSource(projectRoot: Path): Path {
        val sourceFile = projectRoot.resolve("VerifyFixture.java")
        if (Files.exists(sourceFile)) {
            return sourceFile
        }
        Files.writeString(
            sourceFile,
            """
            public class VerifyFixture {
                public static void main(String[] args) throws Exception {
                    String mode = args.length == 0 ? "" : args[0];
                    if ("echo".equals(mode)) {
                        System.out.println("token=super-secret-token");
                        System.out.println("payload=" + "A".repeat(256));
                        System.err.println("password=hunter2");
                        return;
                    }
                    if ("echo-extended".equals(mode)) {
                        System.out.println("Authorization: Basic dXNlcjpwYXNz");
                        System.out.println("{\"apiKey\":\"sk-live-secret\",\"access_token\":\"ghp_secretToken\"}");
                        System.out.println("https://user:super-secret@example.com/resource");
                        return;
                    }
                    if ("sleep".equals(mode)) {
                        long millis = args.length > 1 ? Long.parseLong(args[1]) : 5000L;
                        Thread.sleep(millis);
                        System.out.println("slept=" + millis);
                        return;
                    }
                    throw new IllegalArgumentException("Unknown mode: " + mode);
                }
            }
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        return sourceFile
    }

    private fun javaExecutable(): Path {
        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        return Path.of(System.getProperty("java.home"), "bin", executableName)
    }
}
