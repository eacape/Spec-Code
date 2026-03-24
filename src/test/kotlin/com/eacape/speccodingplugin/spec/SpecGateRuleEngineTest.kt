package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecGateRuleEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var configService: SpecProjectConfigService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        configService = SpecProjectConfigService(project)
    }

    @Test
    fun `evaluate should skip disabled rules with explainable summary`() {
        val rule = testRule(
            id = "custom-disabled",
            severity = GateStatus.ERROR,
            remediationHint = "Fix custom-disabled",
        )
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))

        val result = engine.evaluate(
            request = stageRequest(),
            projectConfig = configService.load().copy(
                rules = mapOf(
                    rule.id to SpecRulePolicy(enabled = false),
                ),
            ),
        )

        assertEquals(GateStatus.PASS, result.status)
        val evaluation = result.ruleResults.single()
        assertFalse(evaluation.enabled)
        assertEquals("Disabled by project config.", evaluation.summary)
        assertTrue(evaluation.violations.isEmpty())
    }

    @Test
    fun `evaluate should apply severity override and remediation hint`() {
        val rule = testRule(
            id = "custom-warning",
            severity = GateStatus.ERROR,
            remediationHint = "Add the missing detail",
        )
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))

        val result = engine.evaluate(
            request = stageRequest(),
            projectConfig = configService.load().copy(
                rules = mapOf(
                    rule.id to SpecRulePolicy(severityOverride = GateStatus.WARNING),
                ),
            ),
        )

        assertEquals(GateStatus.WARNING, result.status)
        val violation = result.violations.single()
        assertEquals(GateStatus.WARNING, violation.severity)
        assertEquals("Add the missing detail", violation.fixHint)

        val evaluation = result.ruleResults.single()
        assertTrue(evaluation.severityOverridden)
        assertEquals(GateStatus.WARNING, evaluation.effectiveSeverity)
        assertTrue(evaluation.summary.contains("Found 1 violation"))
    }

    @Test
    fun `evaluate should report pass summary for applicable rules without violations`() {
        val rule = object : Rule {
            override val id: String = "custom-pass"
            override val description: String = "Pass rule"
            override val defaultSeverity: GateStatus = GateStatus.ERROR
            override val remediationHint: String? = null

            override fun appliesTo(stage: StageId): Boolean = stage == StageId.REQUIREMENTS

            override fun evaluate(ctx: RuleContext): List<Violation> = emptyList()
        }
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))

        val result = engine.evaluate(
            request = stageRequest(),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.PASS, result.status)
        val evaluation = result.ruleResults.single()
        assertTrue(evaluation.enabled)
        assertEquals(listOf(StageId.REQUIREMENTS), evaluation.appliedStages)
        assertTrue(evaluation.summary.contains("Passed for REQUIREMENTS"))
        assertTrue(evaluation.violations.isEmpty())
    }

    @Test
    fun `evaluate should report missing required artifact for active stage`() {
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(FixedArtifactNamingRule(), RequiredArtifactRule()),
        )

        val result = engine.evaluate(
            request = stageRequest(workflowId = "spec-test-missing-artifact"),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val requiredResult = result.ruleResults.first { it.ruleId == "artifact-required" }
        assertEquals(listOf(StageId.REQUIREMENTS), requiredResult.appliedStages)
        assertEquals(1, requiredResult.violations.size)
        assertTrue(requiredResult.violations.single().message.contains("requirements.md"))

        val namingResult = result.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertTrue(namingResult.violations.isEmpty())
    }

    @Test
    fun `evaluate should prefer fixed naming violation over missing artifact when alias exists`() {
        val workflowId = "spec-test-fixed-naming"
        artifactService.writeArtifact(workflowId, "requirement.md", "# misplaced requirement\n")
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(FixedArtifactNamingRule(), RequiredArtifactRule()),
        )

        val result = engine.evaluate(
            request = stageRequest(workflowId = workflowId),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val namingResult = result.ruleResults.first { it.ruleId == "artifact-fixed-naming" }
        assertEquals(1, namingResult.violations.size)
        assertEquals("requirement.md", namingResult.violations.single().fileName)
        assertTrue(namingResult.violations.single().message.contains("requirements.md"))

        val requiredResult = result.ruleResults.first { it.ruleId == "artifact-required" }
        assertTrue(requiredResult.violations.isEmpty())
    }

    @Test
    fun `evaluate should ignore inactive verify artifact for baseline rules`() {
        val stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = false))
        val workflow = SpecWorkflow(
            id = "spec-test-inactive-verify",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            stageStates = stagePlan.initialStageStates("2026-03-09T00:00:00Z"),
            currentStage = StageId.IMPLEMENT,
        )
        val request = StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.IMPLEMENT,
            targetStage = StageId.ARCHIVE,
            evaluatedStages = listOf(StageId.VERIFY),
            stagePlan = stagePlan,
            workflow = workflow,
        )
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(FixedArtifactNamingRule(), RequiredArtifactRule()),
        )

        val result = engine.evaluate(
            request = request,
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.PASS, result.status)
        assertTrue(result.ruleResults.all { it.violations.isEmpty() })
    }

    @Test
    fun `evaluate should report task syntax violations for malformed headings`() {
        val workflowId = "spec-test-task-syntax"
        val markdown = """
            ## 任务列表

            ### Task 1
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()
        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(TasksSyntaxRule()),
        )

        val result = engine.evaluate(
            request = tasksStageRequest(workflowId, markdown),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val syntaxResult = result.ruleResults.single()
        assertEquals("tasks-syntax", syntaxResult.ruleId)
        assertEquals(1, syntaxResult.violations.size)
        assertEquals(3, syntaxResult.violations.single().line)
        assertTrue(syntaxResult.violations.single().message.contains("### T-001: Title"))
        assertEquals(
            listOf(GateQuickFixKind.REPAIR_TASKS_ARTIFACT),
            syntaxResult.violations.single().quickFixes.map { quickFix -> quickFix.kind },
        )
    }

    @Test
    fun `evaluate should expose requirements repair quick fix for scaffold placeholders`() {
        val markdown = """
            # Requirements Document

            ## Functional Requirements
            - [ ] TODO: Describe required behavior.

            ## Non-Functional Requirements
            - [ ] TODO: Describe performance, security, and reliability constraints.

            ## User Stories
            As a <role>, I want <capability>, so that <benefit>.

            ## Acceptance Criteria
            - [ ] TODO: Add measurable acceptance criteria.
        """.trimIndent()
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(DocumentValidationRule()),
        )

        val result = engine.evaluate(
            request = stageRequest(workflowId = "spec-test-requirements-quick-fix", requirementsContent = markdown),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val validationResult = result.ruleResults.single()
        assertEquals("document-validation", validationResult.ruleId)
        assertTrue(
            validationResult.violations
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
    fun `evaluate should report duplicate task ids and missing dependencies`() {
        val workflowId = "spec-test-task-duplicates"
        val markdown = """
            ## 任务列表

            ### T-001: 先完成基础建模
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 定义模型

            ### T-001: 再接接口层
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-999
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 接入服务
        """.trimIndent()
        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(TasksSyntaxRule(), TaskUniqueIdRule(), TaskDependencyExistsRule()),
        )

        val result = engine.evaluate(
            request = tasksStageRequest(workflowId, markdown),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val duplicateResult = result.ruleResults.first { it.ruleId == "tasks-id-unique" }
        assertEquals(1, duplicateResult.violations.size)
        assertEquals(13, duplicateResult.violations.single().line)
        assertTrue(duplicateResult.violations.single().message.contains("duplicated"))

        val dependencyResult = result.ruleResults.first { it.ruleId == "tasks-dependency-exists" }
        assertEquals(1, dependencyResult.violations.size)
        assertEquals(17, dependencyResult.violations.single().line)
        assertTrue(dependencyResult.violations.single().message.contains("T-999"))
    }

    @Test
    fun `evaluate should report task state consistency when dependency is unfinished`() {
        val workflowId = "spec-test-task-state"
        val markdown = """
            ## 任务列表

            ### T-001: 完成前置设计
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 完成设计

            ### T-002: 开始实现
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] 编写实现
        """.trimIndent()
        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val engine = SpecGateRuleEngine(
            artifactService,
            listOf(TasksSyntaxRule(), TaskDependencyExistsRule(), TaskStateConsistencyRule()),
        )

        val result = engine.evaluate(
            request = tasksStageRequest(workflowId, markdown),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val stateResult = result.ruleResults.first { it.ruleId == "tasks-state-consistency" }
        assertEquals(1, stateResult.violations.size)
        assertEquals(15, stateResult.violations.single().line)
        assertTrue(stateResult.violations.single().message.contains("IN_PROGRESS"))
        assertTrue(stateResult.violations.single().message.contains("PENDING"))
    }

    @Test
    fun `evaluate should map verification conclusions for verify stage`() {
        val markdown = """
            ## 任务列表

            ### T-001: Warn path
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult:
              conclusion: WARN
              runId: verify-001
              summary: flaky assertion remains
              at: "2026-03-09T10:00:00Z"
            ```

            ### T-002: Fail path
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult:
              conclusion: FAIL
              runId: verify-002
              summary: integration suite failed
              at: "2026-03-09T10:10:00Z"
            ```
        """.trimIndent()
        val engine = SpecGateRuleEngine(artifactService, listOf(VerifyConclusionRule()))

        val result = engine.evaluate(
            request = verifyStageRequest(workflowId = "spec-test-verify-conclusion", markdown = markdown),
            projectConfig = configService.load(),
        )

        assertEquals(GateStatus.ERROR, result.status)
        val evaluation = result.ruleResults.single()
        assertEquals("verify-conclusion", evaluation.ruleId)
        assertEquals(2, evaluation.violations.size)
        assertEquals(listOf(GateStatus.WARNING, GateStatus.ERROR), evaluation.violations.map(Violation::severity))
        assertTrue(evaluation.violations.first().message.contains("T-001"))
        assertTrue(evaluation.violations.last().message.contains("T-002"))
    }

    @Test
    fun `evaluate should keep original severity when verify conclusion is downgraded`() {
        val markdown = """
            ## 任务列表

            ### T-010: Downgraded fail
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult:
              conclusion: FAIL
              runId: verify-010
              summary: regression still failing
              at: "2026-03-09T11:00:00Z"
            ```
        """.trimIndent()
        val engine = SpecGateRuleEngine(artifactService, listOf(VerifyConclusionRule()))

        val result = engine.evaluate(
            request = verifyStageRequest(workflowId = "spec-test-verify-downgrade", markdown = markdown),
            projectConfig = configService.load().copy(
                rules = mapOf(
                    "verify-conclusion" to SpecRulePolicy(severityOverride = GateStatus.WARNING),
                ),
            ),
        )

        assertEquals(GateStatus.WARNING, result.status)
        val violation = result.violations.single()
        assertEquals(GateStatus.WARNING, violation.severity)
        assertEquals(GateStatus.ERROR, violation.originalSeverity)
    }

    @Test
    fun `evaluate should reuse cached rule evaluations when fingerprints are unchanged`() {
        val rule = CountingRule(id = "custom-cache", severity = GateStatus.ERROR)
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))
        val request = stageRequest(workflowId = "spec-test-rule-cache")
        val config = configService.load()

        val first = engine.evaluate(request = request, projectConfig = config)
        val second = engine.evaluate(request = request, projectConfig = config)

        assertEquals(first, second)
        assertEquals(1, rule.evaluationCount)
    }

    @Test
    fun `evaluate should invalidate cached rule evaluations when document fingerprint changes`() {
        val rule = CountingRule(id = "custom-cache-invalidate", severity = GateStatus.ERROR)
        val engine = SpecGateRuleEngine(artifactService, listOf(rule))
        val config = configService.load()

        engine.evaluate(
            request = stageRequest(
                workflowId = "spec-test-rule-cache-invalidate",
                requirementsContent = "## Requirements\n- first snapshot",
            ),
            projectConfig = config,
        )
        engine.evaluate(
            request = stageRequest(
                workflowId = "spec-test-rule-cache-invalidate",
                requirementsContent = "## Requirements\n- updated snapshot",
            ),
            projectConfig = config,
        )

        assertEquals(2, rule.evaluationCount)
    }

    private fun stageRequest(
        workflowId: String = "spec-test-rule-framework",
        requirementsContent: String = "## Requirements\n- details\n\n## Non-Functional Requirements\n- stability\n\n## User Stories\nAs a user, I want traceable rules.",
    ): StageTransitionRequest {
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to SpecDocument(
                    id = "requirements-doc",
                    phase = SpecPhase.SPECIFY,
                    content = requirementsContent,
                    metadata = SpecMetadata(
                        title = "Requirements",
                        description = "rule test",
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            stageStates = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
                .buildStagePlan(StageActivationOptions.of(verifyEnabled = false))
                .initialStageStates("2026-03-09T00:00:00Z"),
            currentStage = StageId.REQUIREMENTS,
        )
        return StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.REQUIREMENTS,
            targetStage = StageId.DESIGN,
            evaluatedStages = listOf(StageId.REQUIREMENTS),
            stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
                .buildStagePlan(StageActivationOptions.of(verifyEnabled = false)),
            workflow = workflow,
        )
    }

    private fun verifyStageRequest(workflowId: String, markdown: String): StageTransitionRequest {
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
            documents = mapOf(
                SpecPhase.IMPLEMENT to SpecDocument(
                    id = "verify-tasks-doc",
                    phase = SpecPhase.IMPLEMENT,
                    content = markdown,
                    metadata = SpecMetadata(
                        title = "Tasks",
                        description = "verify rule test",
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            stageStates = stageStates,
            currentStage = StageId.VERIFY,
            verifyEnabled = true,
        )
        return StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.VERIFY,
            targetStage = StageId.ARCHIVE,
            evaluatedStages = listOf(StageId.VERIFY),
            stagePlan = stagePlan,
            workflow = workflow,
        )
    }

    private fun tasksStageRequest(workflowId: String, markdown: String): StageTransitionRequest {
        val stagePlan = WorkflowTemplates.definitionOf(WorkflowTemplate.FULL_SPEC)
            .buildStagePlan(StageActivationOptions.of(verifyEnabled = false))
        val workflow = SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to SpecDocument(
                    id = "tasks-doc",
                    phase = SpecPhase.IMPLEMENT,
                    content = markdown,
                    metadata = SpecMetadata(
                        title = "Tasks",
                        description = "task rule test",
                    ),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            stageStates = stagePlan.initialStageStates("2026-03-09T00:00:00Z"),
            currentStage = StageId.TASKS,
        )
        return StageTransitionRequest(
            workflowId = workflow.id,
            transitionType = StageTransitionType.ADVANCE,
            fromStage = StageId.TASKS,
            targetStage = StageId.IMPLEMENT,
            evaluatedStages = listOf(StageId.TASKS),
            stagePlan = stagePlan,
            workflow = workflow,
        )
    }

    private fun testRule(
        id: String,
        severity: GateStatus,
        remediationHint: String,
    ): Rule {
        return object : Rule {
            override val id: String = id
            override val description: String = "Synthetic rule $id"
            override val defaultSeverity: GateStatus = severity
            override val remediationHint: String = remediationHint

            override fun appliesTo(stage: StageId): Boolean = stage == StageId.REQUIREMENTS

            override fun evaluate(ctx: RuleContext): List<Violation> {
                return listOf(
                    Violation(
                        ruleId = id,
                        severity = severity,
                        fileName = "requirements.md",
                        line = 1,
                        message = "Synthetic violation for $id",
                    ),
                )
            }
        }
    }

    private class CountingRule(
        override val id: String,
        private val severity: GateStatus,
    ) : Rule {
        override val description: String = "Counting rule $id"
        override val defaultSeverity: GateStatus = severity
        override val remediationHint: String = "Fix $id"
        var evaluationCount: Int = 0
            private set

        override fun appliesTo(stage: StageId): Boolean = stage == StageId.REQUIREMENTS

        override fun evaluate(ctx: RuleContext): List<Violation> {
            evaluationCount += 1
            return listOf(
                Violation(
                    ruleId = id,
                    severity = severity,
                    fileName = "requirements.md",
                    line = 1,
                    message = "Synthetic violation for $id",
                ),
            )
        }
    }
}
