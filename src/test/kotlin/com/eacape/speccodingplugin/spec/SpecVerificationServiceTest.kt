package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecVerificationServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var configService: SpecProjectConfigService
    private lateinit var artifactService: SpecArtifactService
    private lateinit var tasksService: SpecTasksService
    private lateinit var verificationService: SpecVerificationService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        configService = SpecProjectConfigService(project)
        artifactService = SpecArtifactService(project)
        tasksService = SpecTasksService(project)
        verificationService = SpecVerificationService(project, storage, configService, SpecProcessRunner())
    }

    @Test
    fun `preview should return plan id normalized commands and policy summary`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              defaultWorkingDirectory: .
              defaultTimeoutMs: 45000
              defaultOutputLimitChars: 4096
              redactionPatterns:
                - "(?i)sessionId=\\S+"
              commands:
                - id: gradle-test
                  displayName: Gradle tests
                  command:
                    - ./gradlew.bat
                    - test
                    - --offline
                  workingDirectory: .
                  timeoutMs: 60000
                  outputLimitChars: 8192
                  redactionPatterns:
                    - "(?i)password=\\S+"
            """.trimIndent(),
        )
        val workflow = createWorkflow(workflowId = "wf-verify-preview")

        val plan = verificationService.preview(workflow.id)
        val preparedRequests = verificationService.resolveExecutionRequests(workflow.id, plan.planId)

        assertTrue(WorkflowIdGenerator.isValid(plan.planId, "verify-plan"))
        assertEquals(workflow.id, plan.workflowId)
        assertEquals(StageId.VERIFY, plan.currentStage)
        assertEquals(VerifyPlanConfigSource.WORKFLOW_PINNED, plan.policy.configSource)
        assertEquals(workflow.configPinHash, plan.policy.workflowConfigPinHash)
        assertEquals(workflow.configPinHash, plan.policy.effectiveConfigHash)
        assertEquals(false, plan.policy.usesPinnedSnapshot)
        assertEquals(true, plan.policy.confirmationRequired)
        assertEquals(listOf("Review verify commands before execution."), plan.policy.confirmationReasons)
        assertEquals(listOf("gradle-test"), plan.commands.map(VerifyPlanCommand::commandId))
        assertEquals(listOf("./gradlew.bat", "test", "--offline"), plan.commands.single().command)
        assertEquals(tempDir.toAbsolutePath().normalize(), plan.commands.single().workingDirectory)
        assertEquals(60_000, plan.commands.single().timeoutMs)
        assertEquals(8_192, plan.commands.single().outputLimitChars)
        assertEquals(
            listOf("(?i)sessionId=\\S+", "(?i)password=\\S+"),
            plan.commands.single().redactionPatterns,
        )
        assertEquals(listOf("gradle-test"), preparedRequests.map(VerifyCommandExecutionRequest::commandId))
    }

    @Test
    fun `preview should fall back to pinned config snapshot when project verify config drifts`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              commands:
                - id: gradle-test
                  command:
                    - ./gradlew.bat
                    - test
            """.trimIndent(),
        )
        val workflow = createWorkflow(workflowId = "wf-verify-pinned")

        Files.createDirectories(tempDir.resolve("scripts"))
        writeConfig(
            """
            schemaVersion: 1
            verify:
              defaultWorkingDirectory: scripts
              commands:
                - id: npm-test
                  command:
                    - npm
                    - test
            """.trimIndent(),
        )

        val plan = verificationService.preview(workflow.id)

        assertEquals(VerifyPlanConfigSource.WORKFLOW_PINNED, plan.policy.configSource)
        assertEquals(true, plan.policy.usesPinnedSnapshot)
        assertEquals(workflow.configPinHash, plan.policy.effectiveConfigHash)
        assertEquals(listOf("gradle-test"), plan.commands.map(VerifyPlanCommand::commandId))
        assertTrue(
            plan.policy.confirmationReasons.contains(
                "Current project config differs from the workflow pin; using the pinned config snapshot for this plan.",
            ),
        )
    }

    @Test
    fun `resolvePlan should reject stale plan after workflow stage changes`() {
        writeConfig(
            """
            schemaVersion: 1
            verify:
              commands:
                - id: gradle-test
                  command:
                    - ./gradlew.bat
                    - test
            """.trimIndent(),
        )
        val workflow = createWorkflow(
            workflowId = "wf-verify-stale",
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
        )
        val plan = verificationService.preview(workflow.id)

        val updatedWorkflow = workflow.copy(
            currentPhase = phaseFor(StageId.ARCHIVE),
            currentStage = StageId.ARCHIVE,
            verifyEnabled = false,
            stageStates = buildStageStates(currentStage = StageId.ARCHIVE, verifyEnabled = false),
            updatedAt = workflow.updatedAt + 1,
        )
        storage.saveWorkflow(updatedWorkflow).getOrThrow()

        assertThrows(StaleVerifyPlanError::class.java) {
            verificationService.resolvePlan(workflow.id, plan.planId)
        }
    }

    @Test
    fun `run should execute commands write verification artifact update scoped tasks and append audit event`() {
        writeVerifyConfig(
            commandId = "java-echo",
            command = javaCommand(tempDir, "echo"),
            outputLimitChars = 4096,
        )
        val workflow = createWorkflow(workflowId = "wf-verify-run-pass")
        writeTasks(
            workflow.id,
            """
            # Implement Document

            ## Task List

            ### T-001: Verify implementation
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-002: Leave untouched
            ```spec-task
            status: IN_PROGRESS
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            """.trimIndent(),
        )

        val plan = verificationService.preview(workflow.id)
        val result = verificationService.run(workflow.id, plan.planId, listOf("T-001"))

        assertTrue(WorkflowIdGenerator.isValid(result.runId, prefix = "verify-run"))
        assertEquals(plan.planId, result.planId)
        assertEquals(VerificationConclusion.PASS, result.conclusion)
        assertEquals(listOf("T-001"), result.updatedTasks.map(StructuredTask::id))
        assertEquals(1, result.commandResults.size)
        assertEquals(0, result.commandResults.single().exitCode)

        val verificationDocument = artifactService.readArtifact(workflow.id, StageId.VERIFY)
        assertNotNull(verificationDocument)
        assertTrue(verificationDocument!!.contains("# Verification Document"))
        assertTrue(verificationDocument.contains("conclusion: PASS"))
        assertTrue(verificationDocument.contains("runId: ${result.runId}"))
        assertTrue(verificationDocument.contains("`T-001` Verify implementation"))
        assertTrue(verificationDocument.contains("<redacted>"))
        assertFalse(verificationDocument.contains("super-secret-token"))

        val parsedTasks = tasksService.parse(workflow.id).associateBy(StructuredTask::id)
        assertEquals(VerificationConclusion.PASS, parsedTasks.getValue("T-001").verificationResult?.conclusion)
        assertEquals(result.runId, parsedTasks.getValue("T-001").verificationResult?.runId)
        assertEquals(null, parsedTasks.getValue("T-002").verificationResult)

        val runEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.VERIFICATION_RUN_COMPLETED }
        assertEquals(plan.planId, runEvent.details["planId"])
        assertEquals(result.runId, runEvent.details["runId"])
        assertEquals("PASS", runEvent.details["conclusion"])
        assertEquals("T-001", runEvent.details["scopeTaskIds"])
        assertEquals("verification.md", runEvent.details["verificationFile"])
    }

    @Test
    fun `run should mark verification as warn when output is truncated`() {
        writeVerifyConfig(
            commandId = "java-echo",
            command = javaCommand(tempDir, "echo"),
            outputLimitChars = 48,
        )
        val workflow = createWorkflow(workflowId = "wf-verify-run-warn")
        writeTasks(
            workflow.id,
            """
            # Implement Document

            ## Task List

            ### T-001: Verify truncation handling
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            """.trimIndent(),
        )

        val plan = verificationService.preview(workflow.id)
        val result = verificationService.run(workflow.id, plan.planId, listOf("T-001"))

        assertEquals(VerificationConclusion.WARN, result.conclusion)
        assertTrue(result.summary.contains("truncated"))
        assertTrue(result.commandResults.single().truncated)

        val verificationDocument = artifactService.readArtifact(workflow.id, StageId.VERIFY)
        assertNotNull(verificationDocument)
        assertTrue(verificationDocument!!.contains("conclusion: WARN"))
        assertTrue(verificationDocument.contains("- Truncated: `yes`"))
        assertEquals(
            VerificationConclusion.WARN,
            tasksService.parse(workflow.id).single().verificationResult?.conclusion,
        )
    }

    @Test
    fun `run should redact persisted command preview and append security audit fields`() {
        writeVerifyConfig(
            commandId = "java-echo-secret-arg",
            command = javaCommand(
                tempDir,
                "echo",
                "Authorization: Bearer ghp_super-secret",
                "{\"apiKey\":\"sk-live-secret\"}",
            ),
            outputLimitChars = 4_096,
        )
        val workflow = createWorkflow(workflowId = "wf-verify-run-security-audit")
        writeTasks(
            workflow.id,
            """
            # Implement Document

            ## Task List

            ### T-001: Verify secure command audit
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            """.trimIndent(),
        )

        val plan = verificationService.preview(workflow.id)
        verificationService.run(workflow.id, plan.planId, listOf("T-001"))

        val verificationDocument = artifactService.readArtifact(workflow.id, StageId.VERIFY).orEmpty()
        val runEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.VERIFICATION_RUN_COMPLETED }
        val expectedExecutable = javaExecutable().fileName.toString()

        assertTrue(verificationDocument.contains("Command preview redacted: `yes`"))
        assertTrue(verificationDocument.contains("Authorization: Bearer <redacted>"))
        assertTrue(verificationDocument.contains("{\"apiKey\":\"<redacted>\"}"))
        assertFalse(verificationDocument.contains("ghp_super-secret"))
        assertFalse(verificationDocument.contains("sk-live-secret"))
        assertEquals("java-echo-secret-arg=$expectedExecutable", runEvent.details["commandExecutables"])
        assertEquals("java-echo-secret-arg=.", runEvent.details["commandWorkingDirectories"])
        assertEquals("java-echo-secret-arg", runEvent.details["commandPreviewRedactedIds"])
        assertEquals("java-echo-secret-arg=7", runEvent.details["commandRedactionRuleCounts"])
        val digestEntry = runEvent.details["commandSecurityDigests"].orEmpty()
        assertTrue(digestEntry.startsWith("java-echo-secret-arg="))
        assertEquals(64, digestEntry.substringAfter('=').length)
    }

    @Test
    fun `run should record failure conclusion when command exits non-zero`() {
        writeVerifyConfig(
            commandId = "java-fail",
            command = javaCommand(tempDir, "boom"),
            outputLimitChars = 4096,
        )
        val workflow = createWorkflow(workflowId = "wf-verify-run-fail")
        writeTasks(
            workflow.id,
            """
            # Implement Document

            ## Task List

            ### T-001: Verify failing command
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            """.trimIndent(),
        )

        val plan = verificationService.preview(workflow.id)
        val result = verificationService.run(workflow.id, plan.planId, listOf("T-001"))

        assertEquals(VerificationConclusion.FAIL, result.conclusion)
        assertTrue((result.commandResults.single().exitCode ?: 0) != 0)
        assertTrue(result.summary.contains("failed or timed out"))

        val verificationDocument = artifactService.readArtifact(workflow.id, StageId.VERIFY)
        assertNotNull(verificationDocument)
        assertTrue(verificationDocument!!.contains("conclusion: FAIL"))
        assertTrue(verificationDocument.contains("Outcome: `EXIT"))
        assertEquals(
            VerificationConclusion.FAIL,
            tasksService.parse(workflow.id).single().verificationResult?.conclusion,
        )

        val runEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.VERIFICATION_RUN_COMPLETED }
        assertEquals("FAIL", runEvent.details["conclusion"])
        assertEquals("java-fail", runEvent.details["failedCommandIds"])
    }

    @Test
    fun `listRunHistory should parse verification audit events newest first`() {
        val workflow = createWorkflow(workflowId = "wf-verify-history")
        storage.appendAuditEvent(
            workflowId = workflow.id,
            eventType = SpecAuditEventType.VERIFICATION_RUN_COMPLETED,
            details = mapOf(
                "planId" to "verify-plan-001",
                "runId" to "verify-run-001",
                "executedAt" to "2026-03-10T09:00:00Z",
                "currentStage" to "VERIFY",
                "conclusion" to "PASS",
                "commandCount" to "1",
                "scopeTaskIds" to "T-001",
                "failedCommandIds" to "",
                "truncatedCommandIds" to "",
                "redactedCommandIds" to "cmd-1",
                "summary" to "Initial verification passed.",
            ),
        ).getOrThrow()
        Thread.sleep(5)
        storage.appendAuditEvent(
            workflowId = workflow.id,
            eventType = SpecAuditEventType.VERIFICATION_RUN_COMPLETED,
            details = mapOf(
                "planId" to "verify-plan-002",
                "runId" to "verify-run-002",
                "executedAt" to "2026-03-10T10:15:00Z",
                "currentStage" to "VERIFY",
                "conclusion" to "FAIL",
                "commandCount" to "2",
                "scopeTaskIds" to "T-001, T-003",
                "failedCommandIds" to "cmd-2",
                "truncatedCommandIds" to "cmd-1",
                "redactedCommandIds" to "cmd-1, cmd-2",
                "summary" to "Follow-up verification failed.",
            ),
        ).getOrThrow()

        val history = verificationService.listRunHistory(workflow.id)

        assertEquals(listOf("verify-run-002", "verify-run-001"), history.map(VerifyRunHistoryEntry::runId))
        assertEquals(VerificationConclusion.FAIL, history.first().conclusion)
        assertEquals(StageId.VERIFY, history.first().currentStage)
        assertEquals(2, history.first().commandCount)
        assertEquals(listOf("T-001", "T-003"), history.first().scopeTaskIds)
        assertEquals(listOf("cmd-2"), history.first().failedCommandIds)
        assertEquals(listOf("cmd-1"), history.first().truncatedCommandIds)
        assertEquals(listOf("cmd-1", "cmd-2"), history.first().redactedCommandIds)
    }

    private fun createWorkflow(
        workflowId: String,
        currentStage: StageId = StageId.VERIFY,
        verifyEnabled: Boolean = true,
    ): SpecWorkflow {
        val configPin = configService.createConfigPin(configService.load())
        storage.saveConfigPinSnapshot(workflowId, configPin).getOrThrow()
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = phaseFor(currentStage),
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Verify Preview Workflow",
            description = "verification preview",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = buildStageStates(currentStage = currentStage, verifyEnabled = verifyEnabled),
            currentStage = currentStage,
            verifyEnabled = verifyEnabled,
            configPinHash = configPin.hash,
            createdAt = 1L,
            updatedAt = 2L,
        )
        storage.saveWorkflow(workflow).getOrThrow()
        return workflow
    }

    private fun buildStageStates(
        currentStage: StageId,
        verifyEnabled: Boolean,
    ): Map<StageId, StageState> {
        val currentIndex = StageId.entries.indexOf(currentStage)
        val timestamp = "2026-03-09T00:00:00Z"
        return StageId.entries.associateWith { stageId ->
            val active = stageId != StageId.VERIFY || verifyEnabled
            val stageIndex = StageId.entries.indexOf(stageId)
            val status = when {
                !active -> StageProgress.NOT_STARTED
                stageId == currentStage -> StageProgress.IN_PROGRESS
                stageIndex < currentIndex -> StageProgress.DONE
                else -> StageProgress.NOT_STARTED
            }
            StageState(
                active = active,
                status = status,
                enteredAt = if (status == StageProgress.NOT_STARTED) null else timestamp,
                completedAt = if (status == StageProgress.DONE) timestamp else null,
            )
        }
    }

    private fun phaseFor(stageId: StageId): SpecPhase {
        return when (stageId) {
            StageId.REQUIREMENTS -> SpecPhase.SPECIFY
            StageId.DESIGN -> SpecPhase.DESIGN
            StageId.TASKS,
            StageId.IMPLEMENT,
            StageId.VERIFY,
            StageId.ARCHIVE,
            -> SpecPhase.IMPLEMENT
        }
    }

    private fun writeConfig(raw: String) {
        val configPath = configService.configPath()
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "$raw\n", StandardCharsets.UTF_8)
    }

    private fun writeVerifyConfig(
        commandId: String,
        command: List<String>,
        outputLimitChars: Int,
        timeoutMs: Int = 15_000,
    ) {
        val renderedConfig = buildString {
            appendLine("schemaVersion: 1")
            appendLine("verify:")
            appendLine("  defaultWorkingDirectory: .")
            appendLine("  defaultTimeoutMs: $timeoutMs")
            appendLine("  defaultOutputLimitChars: $outputLimitChars")
            appendLine("  commands:")
            appendLine("    - id: $commandId")
            appendLine("      command:")
            command.forEach { token ->
                appendLine("        - ${yamlString(token)}")
            }
        }.trimEnd()
        writeConfig(renderedConfig)
    }

    private fun writeTasks(workflowId: String, markdown: String) {
        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
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

    private fun yamlString(value: String): String {
        return "'${value.replace("'", "''")}'"
    }
}
