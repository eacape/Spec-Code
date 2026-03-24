package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineStageTransitionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `advanceWorkflow should persist stage transition audit and snapshots`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Advance Workflow",
            description = "stage advance",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate requirements").collect()
        }

        val result = engine.advanceWorkflow(created.id).getOrThrow()

        assertEquals(StageTransitionType.ADVANCE, result.transitionType)
        assertEquals(StageId.REQUIREMENTS, result.fromStage)
        assertEquals(StageId.DESIGN, result.targetStage)
        assertEquals(GateStatus.PASS, result.gateResult.status)
        assertNotNull(result.beforeSnapshotId)
        assertNotNull(result.afterSnapshotId)

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageId.DESIGN, reloaded.currentStage)
        assertEquals(StageProgress.DONE, reloaded.stageStates.getValue(StageId.REQUIREMENTS).status)
        assertEquals(StageProgress.IN_PROGRESS, reloaded.stageStates.getValue(StageId.DESIGN).status)

        val event = loadAuditEvents(created.id)
            .last { it.eventType == SpecAuditEventType.STAGE_ADVANCED }
        assertEquals("REQUIREMENTS", event.details["fromStage"])
        assertEquals("DESIGN", event.details["toStage"])
        assertEquals("PASS", event.details["gateStatus"])
        assertEquals(result.beforeSnapshotId, event.details["beforeSnapshotId"])
        assertEquals(result.afterSnapshotId, event.details["afterSnapshotId"])
    }

    @Test
    fun `jump and rollback should be locked once workflow state is completion driven`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Locked Stage Mutation Workflow",
            description = "manual stage changes disabled",
        ).getOrThrow()

        val jumpPreview = engine.previewStageTransition(
            created.id,
            transitionType = StageTransitionType.JUMP,
            targetStage = StageId.TASKS,
        )
        assertTrue(jumpPreview.isFailure)
        assertTrue(jumpPreview.exceptionOrNull() is ManualStageMutationLockedError)

        val jump = engine.jumpToStage(created.id, StageId.TASKS)
        assertTrue(jump.isFailure)
        assertTrue(jump.exceptionOrNull() is ManualStageMutationLockedError)

        val rollback = engine.rollbackToStage(created.id, StageId.REQUIREMENTS)
        assertTrue(rollback.isFailure)
        assertTrue(rollback.exceptionOrNull() is ManualStageMutationLockedError)

        val goBack = engine.goBackToPreviousPhase(created.id)
        assertTrue(goBack.isFailure)
        assertTrue(goBack.exceptionOrNull() is ManualStageMutationLockedError)

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageId.REQUIREMENTS, reloaded.currentStage)
    }

    @Test
    fun `advanceWorkflow should block when current stage has pending clarification`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Clarification Block Workflow",
            description = "pending clarification should block advance",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate requirements").collect()
        }
        engine.saveClarificationRetryState(
            workflowId = created.id,
            state = ClarificationRetryState(
                input = "generate requirements",
                confirmedContext = "Need to confirm offline mode",
                questionsMarkdown = "1. Do we need offline mode?",
                structuredQuestions = listOf("Do we need offline mode?"),
                clarificationRound = 1,
                confirmed = false,
            ),
        ).getOrThrow()

        val blocked = engine.advanceWorkflow(created.id)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        val completionRule = gateResult.ruleResults.first { it.ruleId == "stage-completion-checks" }
        assertTrue(
            completionRule.violations.any { violation ->
                violation.message == SpecCodingBundle.message("spec.toolwindow.overview.blockers.common.clarificationPending")
            },
        )
    }

    @Test
    fun `advanceWorkflow should block when requirements still use scaffold placeholders`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Requirements Scaffold Workflow",
            description = "scaffold should block advance",
        ).getOrThrow()

        val blocked = engine.advanceWorkflow(created.id)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        assertEquals(GateStatus.ERROR, gateResult.status)
        val validationRule = gateResult.ruleResults.first { it.ruleId == "document-validation" }
        assertTrue(
            validationRule.violations.any { violation ->
                violation.message.contains("TODO placeholders")
            },
        )
        assertTrue(
            validationRule.violations.any { violation ->
                violation.message.contains("<role>/<capability>/<benefit>")
            },
        )
        assertTrue(
            validationRule.violations
                .filter { violation ->
                    violation.message.contains("TODO placeholders") ||
                        violation.message.contains("<role>/<capability>/<benefit>")
                }
                .all { violation ->
                    violation.quickFixes.map { quickFix -> quickFix.kind } ==
                        listOf(GateQuickFixKind.REPAIR_REQUIREMENTS_ARTIFACT)
                },
        )
    }

    @Test
    fun `preview advance from requirements should expose quick fixes for missing sections`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val artifactService = SpecArtifactService(project)
        val created = engine.createWorkflow(
            title = "Missing Requirements Sections Workflow",
            description = "quick fix metadata",
        ).getOrThrow()

        artifactService.writeArtifact(
            workflowId = created.id,
            stageId = StageId.REQUIREMENTS,
            content = """
                # Requirements Document

                ## Functional Requirements
                - Keep gate repair metadata structured.
            """.trimIndent(),
        )
        engine.reloadWorkflow(created.id).getOrThrow()

        val preview = engine.previewStageTransition(
            workflowId = created.id,
            transitionType = StageTransitionType.ADVANCE,
        ).getOrThrow()

        val violation = preview.gateResult.violations.first { gateViolation ->
            gateViolation.ruleId == "stage-completion-checks" &&
                gateViolation.fileName == "requirements.md"
        }

        assertEquals(
            listOf(
                GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS,
                GateQuickFixKind.OPEN_FOR_MANUAL_EDIT,
            ),
            violation.quickFixes.map { quickFix -> quickFix.kind },
        )
        val payload = violation.quickFixes.first().payload as? MissingRequirementsSectionsQuickFixPayload
        assertEquals(
            listOf(
                RequirementsSectionId.NON_FUNCTIONAL,
                RequirementsSectionId.USER_STORIES,
                RequirementsSectionId.ACCEPTANCE_CRITERIA,
            ),
            payload?.missingSections,
        )
    }

    @Test
    fun `previewStageTransition should expose structured warning result without mutating workflow`() {
        val warningGate = SpecStageGateEvaluator {
            GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "warn-preview",
                        severity = GateStatus.WARNING,
                        fileName = "requirements.md",
                        line = 2,
                        message = "preview warning",
                    ),
                ),
            )
        }
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { SpecGenerationResult.Failure("unused") },
            stageGateEvaluator = warningGate,
        )
        val created = engine.createWorkflow(
            title = "Preview Workflow",
            description = "preview gate",
        ).getOrThrow()

        val preview = engine.previewStageTransition(created.id, StageTransitionType.ADVANCE).getOrThrow()

        assertEquals(created.id, preview.workflowId)
        assertEquals(StageTransitionType.ADVANCE, preview.transitionType)
        assertEquals(StageId.REQUIREMENTS, preview.fromStage)
        assertEquals(StageId.DESIGN, preview.targetStage)
        assertEquals(GateStatus.WARNING, preview.gateResult.status)
        assertEquals(1, preview.gateResult.aggregation.warningCount)
        assertEquals("warn-preview", preview.gateResult.warningConfirmation?.warnings?.single()?.ruleId)

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(StageId.REQUIREMENTS, reloaded.currentStage)
    }

    @Test
    fun `advanceWorkflow should block implement stage until structured tasks are settled`() {
        val passGate = SpecStageGateEvaluator { GateResult.fromViolations(emptyList()) }
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { SpecGenerationResult.Failure("unused") },
            stageGateEvaluator = passGate,
        )
        val workflowId = "spec-test-implement-completion"
        persistImplementWorkflow(
            workflowId = workflowId,
            tasksMarkdown = """
                ## Tasks

                ### T-001: Finish implementation
                ```spec-task
                status: IN_PROGRESS
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
            """.trimIndent() + "\n",
        )

        val blocked = engine.advanceWorkflow(workflowId)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        assertEquals(GateStatus.ERROR, gateResult.status)
        val completionRule = gateResult.ruleResults.first { it.ruleId == "stage-completion-checks" }
        assertTrue(
            completionRule.violations.any { violation ->
                violation.message == SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.progress")
            },
        )
    }

    @Test
    fun `advanceWorkflow should use rule severity override from project config`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            rules:
              artifact-fixed-naming:
                severity: WARNING
            """.trimIndent(),
        )
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Rule Override Workflow",
            description = "warning override",
        ).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(created.id)
        Files.move(
            workflowDir.resolve("requirements.md"),
            workflowDir.resolve("requirement.md"),
        )

        val blocked = engine.advanceWorkflow(created.id)
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull() is StageWarningConfirmationRequiredError)

        val result = engine.advanceWorkflow(created.id) { true }.getOrThrow()

        assertEquals(GateStatus.WARNING, result.gateResult.status)
        assertFalse(result.gateResult.ruleResults.isEmpty())
        val ruleResult = result.gateResult.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertTrue(ruleResult.severityOverridden)
        assertEquals(GateStatus.WARNING, ruleResult.effectiveSeverity)
        assertTrue(ruleResult.violations.isNotEmpty())
        assertTrue(ruleResult.violations.all { it.severity == GateStatus.WARNING })
        assertTrue(ruleResult.violations.all { !it.fixHint.isNullOrBlank() })
    }

    @Test
    fun `advanceWorkflow should block when active artifact uses non canonical file name`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Naming Workflow",
            description = "fixed naming",
        ).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(created.id)
        val canonicalPath = workflowDir.resolve("requirements.md")
        val aliasPath = workflowDir.resolve("requirement.md")
        Files.move(canonicalPath, aliasPath)

        val blocked = engine.advanceWorkflow(created.id)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        assertEquals(GateStatus.ERROR, gateResult.status)
        val namingRule = gateResult.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertEquals(1, namingRule.violations.size)
        assertEquals("requirement.md", namingRule.violations.single().fileName)
        assertTrue(namingRule.violations.single().message.contains("requirements.md"))
        val requiredRule = gateResult.ruleResults.first { it.ruleId == "artifact-required" }
        assertTrue(requiredRule.violations.isEmpty())
    }

    @Test
    fun `advanceWorkflow should block when tasks document violates structured task syntax`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val created = engine.createWorkflow(
            title = "Tasks Rule Workflow",
            description = "tasks syntax",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate requirements").collect()
        }
        engine.advanceWorkflow(created.id).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(created.id, "generate design").collect()
        }
        engine.advanceWorkflow(created.id).getOrThrow()

        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(created.id)
        Files.writeString(
            workflowDir.resolve("tasks.md"),
            """
                ## 任务列表

                ### T-001 Broken heading
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )

        val blocked = engine.advanceWorkflow(created.id)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        assertEquals(GateStatus.ERROR, gateResult.status)
        val syntaxRule = gateResult.ruleResults.first { it.ruleId == "tasks-syntax" }
        assertEquals(1, syntaxRule.violations.size)
        assertTrue(syntaxRule.violations.single().message.contains("### T-001: Title"))
        assertEquals(
            listOf(GateQuickFixKind.REPAIR_TASKS_ARTIFACT),
            syntaxRule.violations.single().quickFixes.map { quickFix -> quickFix.kind },
        )
        val completionRule = gateResult.ruleResults.first { it.ruleId == "stage-completion-checks" }
        assertTrue(
            completionRule.violations.any { violation ->
                violation.message == SpecCodingBundle.message("spec.toolwindow.overview.blockers.tasks.structured") &&
                    violation.quickFixes.map { quickFix -> quickFix.kind } == listOf(GateQuickFixKind.REPAIR_TASKS_ARTIFACT)
            },
        )
    }

    @Test
    fun `advanceWorkflow should audit downgraded verify conclusion before warning confirmation`() {
        writeProjectConfig(
            """
                schemaVersion: 1
                rules:
                  verify-conclusion:
                    severity: WARNING
            """.trimIndent(),
        )
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val workflowId = "spec-test-verify-downgrade-audit"
        persistVerifyWorkflow(
            workflowId = workflowId,
            recordVerificationRun = true,
            tasksMarkdown = """
                ## 任务列表

                ### T-001: Verify downgrade
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult:
                  conclusion: FAIL
                  runId: verify-001
                  summary: regression suite failed
                  at: "2026-03-09T12:00:00Z"
                ```
            """.trimIndent() + "\n",
        )

        val blocked = engine.advanceWorkflow(workflowId)

        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull() is StageWarningConfirmationRequiredError)
        val warningError = blocked.exceptionOrNull() as StageWarningConfirmationRequiredError
        assertEquals(GateStatus.WARNING, warningError.gateResult.status)
        assertEquals(1, warningError.gateResult.aggregation.warningCount)
        val downgradeEvent = loadAuditEvents(workflowId)
            .last { it.eventType == SpecAuditEventType.GATE_RULE_DOWNGRADED }
        assertEquals("VERIFY", downgradeEvent.details["fromStage"])
        assertEquals("ARCHIVE", downgradeEvent.details["toStage"])
        assertEquals("1", downgradeEvent.details["downgradeCount"])
        assertEquals("verify-conclusion:ERROR->WARNING", downgradeEvent.details["downgradedRules"])
        assertTrue(downgradeEvent.details.getValue("downgradedViolations").contains("tasks.md"))

        val result = engine.advanceWorkflow(workflowId) { true }.getOrThrow()

        assertEquals(GateStatus.WARNING, result.gateResult.status)
        val warningAudit = loadAuditEvents(workflowId)
            .last { it.eventType == SpecAuditEventType.GATE_WARNING_CONFIRMED }
        assertEquals("ADVANCE", warningAudit.details["transitionType"])
        assertEquals("verify-conclusion", warningAudit.details["warningRules"])
        val verifyRule = result.gateResult.ruleResults.first { it.ruleId == "verify-conclusion" }
        assertEquals(GateStatus.WARNING, verifyRule.violations.single().severity)
        assertEquals(GateStatus.ERROR, verifyRule.violations.single().originalSeverity)
    }

    @Test
    fun `advanceWorkflow should block verify stage until verification history exists`() {
        val passGate = SpecStageGateEvaluator { GateResult.fromViolations(emptyList()) }
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { SpecGenerationResult.Failure("unused") },
            stageGateEvaluator = passGate,
        )
        val workflowId = "spec-test-verify-completion"
        persistVerifyWorkflow(
            workflowId = workflowId,
            recordVerificationRun = false,
            tasksMarkdown = """
                ## Tasks

                ### T-001: Verify workflow
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles:
                  - src/main/kotlin/App.kt
                verificationResult:
                  conclusion: PASS
                  runId: verify-001
                  summary: verification passed
                  at: "2026-03-09T12:00:00Z"
                ```
            """.trimIndent() + "\n",
        )

        val blocked = engine.advanceWorkflow(workflowId)

        assertTrue(blocked.isFailure)
        val error = blocked.exceptionOrNull()
        assertTrue(error is StageTransitionBlockedByGateError)
        val gateResult = (error as StageTransitionBlockedByGateError).gateResult
        val completionRule = gateResult.ruleResults.first { it.ruleId == "stage-completion-checks" }
        assertTrue(
            completionRule.violations.any { violation ->
                violation.message == SpecCodingBundle.message("spec.toolwindow.overview.blockers.verify.run")
            },
        )
    }

    private fun loadAuditEvents(workflowId: String): List<SpecAuditEvent> {
        val auditPath = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflowId)
            .resolve(".history")
            .resolve("audit.yaml")
        return SpecAuditLogCodec.decodeDocuments(Files.readString(auditPath))
    }

    private fun writeProjectConfig(raw: String) {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "$raw\n", StandardCharsets.UTF_8)
    }

    private fun persistImplementWorkflow(workflowId: String, tasksMarkdown: String) {
        val stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = true))
        val stageStates = stagePlan.initialStageStates("2026-03-09T00:00:00Z").toMutableMap()
        stageStates[StageId.REQUIREMENTS] = stageStates.getValue(StageId.REQUIREMENTS).copy(status = StageProgress.DONE)
        stageStates[StageId.DESIGN] = stageStates.getValue(StageId.DESIGN).copy(status = StageProgress.DONE)
        stageStates[StageId.TASKS] = stageStates.getValue(StageId.TASKS).copy(status = StageProgress.DONE)
        stageStates[StageId.IMPLEMENT] = stageStates.getValue(StageId.IMPLEMENT).copy(status = StageProgress.IN_PROGRESS)
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Implement Completion Workflow",
            description = "implement completion checks",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = stageStates,
            currentStage = StageId.IMPLEMENT,
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(workflowId)
        Files.writeString(workflowDir.resolve("tasks.md"), tasksMarkdown, StandardCharsets.UTF_8)
    }

    private fun persistVerifyWorkflow(workflowId: String, tasksMarkdown: String, recordVerificationRun: Boolean = false) {
        val stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = true))
        val stageStates = stagePlan.initialStageStates("2026-03-09T00:00:00Z").toMutableMap()
        stageStates[StageId.REQUIREMENTS] = stageStates.getValue(StageId.REQUIREMENTS).copy(status = StageProgress.DONE)
        stageStates[StageId.DESIGN] = stageStates.getValue(StageId.DESIGN).copy(status = StageProgress.DONE)
        stageStates[StageId.TASKS] = stageStates.getValue(StageId.TASKS).copy(status = StageProgress.DONE)
        stageStates[StageId.IMPLEMENT] = stageStates.getValue(StageId.IMPLEMENT).copy(status = StageProgress.DONE)
        stageStates[StageId.VERIFY] = stageStates.getValue(StageId.VERIFY).copy(status = StageProgress.IN_PROGRESS)
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Verify Downgrade Workflow",
            description = "verify conclusion audit",
            template = WorkflowTemplate.FULL_SPEC,
            stageStates = stageStates,
            currentStage = StageId.VERIFY,
            verifyEnabled = true,
        )
        storage.saveWorkflow(workflow).getOrThrow()
        val workflowDir = tempDir.resolve(".spec-coding").resolve("specs").resolve(workflowId)
        Files.writeString(workflowDir.resolve("tasks.md"), tasksMarkdown, StandardCharsets.UTF_8)
        Files.writeString(
            workflowDir.resolve("verification.md"),
            "# Verification Document\n\n## Result\n- FAIL\n",
            StandardCharsets.UTF_8,
        )
        if (recordVerificationRun) {
            storage.appendAuditEvent(
                workflowId = workflowId,
                eventType = SpecAuditEventType.VERIFICATION_RUN_COMPLETED,
                details = mapOf(
                    "planId" to "verify-plan-001",
                    "runId" to "verify-run-001",
                    "executedAt" to "2026-03-09T12:00:00Z",
                    "currentStage" to "VERIFY",
                    "conclusion" to "PASS",
                    "commandCount" to "1",
                    "scopeTaskIds" to "T-001",
                    "failedCommandIds" to "",
                    "truncatedCommandIds" to "",
                    "redactedCommandIds" to "",
                    "summary" to "Verification completed.",
                ),
            ).getOrThrow()
        }
    }

    private fun invalidRequirementsDocument(): SpecDocument {
        return SpecDocument(
            id = "invalid-requirements",
            phase = SpecPhase.SPECIFY,
            content = """
                ## Functional Requirements
                - Validate warning downgrade handling

                ## Non-Functional Requirements
                - Keep audit metadata stable

                ## User Stories
                - As a user, I want validation to fail without bypassing completion checks.

                ## Acceptance Criteria
                - [ ] Validation still reports an error
            """.trimIndent(),
            metadata = SpecMetadata(
                title = "Invalid Requirements",
                description = "validation error with complete sections",
            ),
            validationResult = ValidationResult(
                valid = false,
                errors = listOf("Validation failed intentionally for rule severity override testing."),
            ),
        )
    }

    private suspend fun generateValidDocument(request: SpecGenerationRequest): SpecGenerationResult {
        val content = when (request.phase) {
            SpecPhase.SPECIFY -> """
                ## 功能需求
                - 用户可推进工作流

                ## 非功能需求
                - 需要稳定审计

                ## 用户故事
                As a user, I want to advance stages safely.

                ## 验收标准
                - [ ] 可以推进到下一阶段
            """.trimIndent()

            SpecPhase.DESIGN -> """
                ## 架构设计
                - 状态机服务

                ## 技术选型
                - Kotlin

                ## 数据模型
                - StageTransitionResult

                ## API 设计
                - advanceWorkflow()

                ## 非功能设计
                - 审计与快照
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                ## 任务列表

                ### T-001: 更新状态机
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] 编写状态机实现

                ## 实现步骤
                1. 编写状态机
                2. 增加测试

                ## 测试计划
                - [ ] 单元测试
            """.trimIndent()
        }
        val candidate = SpecDocument(
            id = "doc-${request.phase.name.lowercase()}",
            phase = request.phase,
            content = content,
            metadata = SpecMetadata(
                title = "${request.phase.displayName} Document",
                description = "generated",
            ),
        )
        return SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
    }
}
