package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecProjectConfigServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var service: SpecProjectConfigService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        service = SpecProjectConfigService(project)
    }

    @Test
    fun `load should return defaults when config file is missing`() {
        val config = service.load()

        assertEquals(SpecProjectConfig.SUPPORTED_SCHEMA_VERSION, config.schemaVersion)
        assertEquals(WorkflowTemplate.FULL_SPEC, config.defaultTemplate)
        assertEquals(WorkflowTemplate.entries.toSet(), config.templates.keys)
        assertEquals(WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC), config.policyFor(WorkflowTemplate.FULL_SPEC).definition)
        assertEquals(SpecGatePolicy(), config.gate)
        assertTrue(config.rules.isEmpty())
        assertEquals(SpecVerifyConfig(), config.verify)
    }

    @Test
    fun `load should merge template gate and rule overrides`() {
        writeConfig(
            """
            schemaVersion: 1
            defaultTemplate: QUICK_TASK
            templates:
              FULL_SPEC:
                verifyEnabled: true
              DIRECT_IMPLEMENT:
                stagePlan:
                  - IMPLEMENT
                  - id: VERIFY
                    optional: true
                    defaultEnabled: true
                  - ARCHIVE
            gate:
              allowWarning: false
              allowJump: false
            rules:
              task-structure:
                enabled: false
                severity: WARNING
              verify-conclusion:
                severity: ERROR
            verify:
              defaultWorkingDirectory: .
              defaultTimeoutMs: 45000
              defaultOutputLimitChars: 4096
              redactionPatterns:
                - '(?i)session[_-]?id=\\S+'
              commands:
                - id: gradle-test
                  displayName: Gradle tests
                  command:
                    - ./gradlew.bat
                    - test
                    - --offline
                  workingDirectory: .spec-coding
                  timeoutMs: 60000
                  outputLimitChars: 8192
                  redactionPatterns:
                    - '(?i)password=\\S+'
            """.trimIndent(),
        )

        val config = service.load()

        assertEquals(WorkflowTemplate.QUICK_TASK, config.defaultTemplate)
        assertTrue(config.policyFor(WorkflowTemplate.FULL_SPEC).verifyEnabledByDefault)
        assertEquals(
            listOf(StageId.IMPLEMENT, StageId.VERIFY, StageId.ARCHIVE),
            config.policyFor(WorkflowTemplate.DIRECT_IMPLEMENT).definition.stagePlan.map { it.id },
        )

        assertFalse(config.gate.allowWarningAdvance)
        assertFalse(config.gate.allowJump)
        assertTrue(config.gate.requireWarningConfirmation)
        assertTrue(config.gate.jumpRequiresMinimalGate)
        assertTrue(config.gate.allowRollback)

        assertEquals(2, config.rules.size)
        assertEquals(false, config.rules.getValue("task-structure").enabled)
        assertEquals(GateStatus.WARNING, config.rules.getValue("task-structure").severityOverride)
        assertEquals(true, config.rules.getValue("verify-conclusion").enabled)
        assertEquals(GateStatus.ERROR, config.rules.getValue("verify-conclusion").severityOverride)

        assertEquals(".", config.verify.defaultWorkingDirectory)
        assertEquals(45_000, config.verify.defaultTimeoutMs)
        assertEquals(4_096, config.verify.defaultOutputLimitChars)
        assertEquals(listOf("(?i)session[_-]?id=\\\\S+"), config.verify.redactionPatterns)
        assertEquals(1, config.verify.commands.size)
        assertEquals(
            SpecVerifyCommand(
                id = "gradle-test",
                displayName = "Gradle tests",
                command = listOf("./gradlew.bat", "test", "--offline"),
                workingDirectory = ".spec-coding",
                timeoutMs = 60_000,
                outputLimitChars = 8_192,
                redactionPatterns = listOf("(?i)password=\\\\S+"),
            ),
            config.verify.commands.single(),
        )
    }

    @Test
    fun `load should reject unsupported schema version`() {
        writeConfig(
            """
            schemaVersion: 2
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.load()
        }
        assertTrue(error.message?.contains("Unsupported config schemaVersion") == true)
    }

    @Test
    fun `load should upgrade legacy config without schemaVersion`() {
        writeConfig(
            """
            defaultTemplate: QUICK_TASK
            gate:
              allowWarningAdvance: false
            """.trimIndent(),
        )

        val config = service.load()
        val pin = service.createConfigPin(config)

        assertEquals(SpecProjectConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion)
        assertEquals(WorkflowTemplate.QUICK_TASK, config.defaultTemplate)
        assertFalse(config.gate.allowWarningAdvance)
        assertTrue(pin.snapshotYaml.contains("schemaVersion: 1"))
    }

    @Test
    fun `load should reject invalid template stage plan`() {
        writeConfig(
            """
            schemaVersion: 1
            templates:
              QUICK_TASK:
                stagePlan:
                  - TASKS
                  - IMPLEMENT
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.load()
        }
        assertTrue(error.message?.contains("must end with ARCHIVE") == true)
    }

    @Test
    fun `load should reject invalid verify redaction regex`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              redactionPatterns:
                - "[unterminated"
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.load()
        }

        assertTrue(error.message?.contains("verify.redactionPatterns[0]") == true)
    }

    @Test
    fun `createConfigPin should return deterministic sha256 hash and snapshot yaml`() {
        writeConfig(
            """
            schemaVersion: 1
            defaultTemplate: QUICK_TASK
            gate:
              allowWarningAdvance: false
            """.trimIndent(),
        )

        val config = service.load()
        val first = service.createConfigPin(config)
        val second = service.createConfigPin(config)

        assertEquals(first.hash, second.hash)
        assertTrue(first.hash.matches(Regex("^[a-f0-9]{64}$")))
        assertEquals(first.snapshotYaml, second.snapshotYaml)
        assertTrue(first.snapshotYaml.contains("schemaVersion: 1"))
        assertTrue(first.snapshotYaml.contains("defaultTemplate: QUICK_TASK"))
        assertTrue(first.snapshotYaml.contains("verify:"))
    }

    @Test
    fun `createConfigPin should change hash when effective config changes`() {
        writeConfig(
            """
            schemaVersion: 1
            defaultTemplate: QUICK_TASK
            """.trimIndent(),
        )
        val firstHash = service.createConfigPin(service.load()).hash

        writeConfig(
            """
            schemaVersion: 1
            defaultTemplate: FULL_SPEC
            """.trimIndent(),
        )
        val secondHash = service.createConfigPin(service.load()).hash

        assertNotEquals(firstHash, secondHash)
    }

    private fun writeConfig(raw: String) {
        val configPath = service.configPath()
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            "$raw\n",
            StandardCharsets.UTF_8,
        )
    }
}
