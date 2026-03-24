package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecStrongConstraintWorkflowRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage
    private lateinit var artifactService: SpecArtifactService
    private lateinit var tasksService: SpecTasksService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
        artifactService = SpecArtifactService(project)
        tasksService = SpecTasksService(project)
    }

    @Test
    fun `template mutations should stay locked and migrate through cloned workflow`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            """.trimIndent() + "\n",
        )
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val source = engine.createWorkflow(
            title = "Locked Template Source",
            description = "task 70 template regression",
        ).getOrThrow()

        val preview = engine.previewTemplateSwitch(source.id, WorkflowTemplate.FULL_SPEC).getOrThrow()
        val applyFailure = engine.applyTemplateSwitch(source.id, preview.previewId).exceptionOrNull()
        val cloned = engine.cloneWorkflowWithTemplate(
            workflowId = source.id,
            previewId = preview.previewId,
            title = "Cloned Full Spec Workflow",
            description = "created by task 70",
        ).getOrThrow()

        assertTrue(applyFailure is TemplateMutationLockedError)
        assertEquals(WorkflowTemplate.DIRECT_IMPLEMENT, engine.reloadWorkflow(source.id).getOrThrow().template)
        assertEquals(WorkflowTemplate.FULL_SPEC, cloned.workflow.template)
        assertTrue(cloned.generatedArtifacts.contains("requirements.md"))
        assertTrue(cloned.generatedArtifacts.contains("design.md"))
        assertTrue(cloned.copiedArtifacts.contains("tasks.md"))
        assertTrue(artifactService.readArtifact(cloned.workflow.id, StageId.REQUIREMENTS)?.contains("# Requirements Document") == true)
        assertTrue(artifactService.readArtifact(cloned.workflow.id, StageId.DESIGN)?.contains("# Design Document") == true)

        val clonedAudit = storage.listAuditEvents(cloned.workflow.id).getOrThrow()
            .last { it.eventType == SpecAuditEventType.WORKFLOW_CLONED_WITH_TEMPLATE }
        assertEquals("TARGET", clonedAudit.details["cloneRole"])
        assertEquals(source.id, clonedAudit.details["sourceWorkflowId"])
        assertEquals("DIRECT_IMPLEMENT", clonedAudit.details["sourceTemplate"])
        assertEquals("FULL_SPEC", clonedAudit.details["targetTemplate"])
    }

    @Test
    fun `task button execution flow should keep tasks artifact related files and verification result in sync`() {
        writeProjectConfig(
            """
            schemaVersion: 1
            defaultTemplate: DIRECT_IMPLEMENT
            """.trimIndent() + "\n",
        )
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val workflow = engine.createWorkflow(
            title = "Task Button Flow",
            description = "task 70 task execution regression",
        ).getOrThrow()
        val createdTask = tasksService.addTask(
            workflowId = workflow.id,
            title = "Execute from spec page",
            priority = TaskPriority.P1,
        )

        tasksService.updateRelatedFiles(
            workflowId = workflow.id,
            taskId = createdTask.id,
            files = listOf(
                "src/main/kotlin/SpecWorkflowPanel.kt",
                tempDir.resolve("docs/../README.md").toString(),
            ),
            auditContext = mapOf(
                "triggerSource" to "SPEC_PAGE_TASK_BUTTON",
                "focusedStage" to "IMPLEMENT",
                "taskAction" to "COMPLETE",
            ),
        )
        tasksService.updateVerificationResult(
            workflowId = workflow.id,
            taskId = createdTask.id,
            verificationResult = TaskVerificationResult(
                conclusion = VerificationConclusion.PASS,
                runId = "verify-task-70",
                summary = "Spec page completion flow is consistent",
                at = "2026-03-13T11:30:00Z",
            ),
            auditContext = mapOf(
                "triggerSource" to "SPEC_PAGE_TASK_BUTTON",
                "focusedStage" to "IMPLEMENT",
                "taskAction" to "COMPLETE",
            ),
        )
        tasksService.transitionStatus(
            workflowId = workflow.id,
            taskId = createdTask.id,
            to = TaskStatus.COMPLETED,
            auditContext = mapOf(
                "triggerSource" to "SPEC_PAGE_TASK_BUTTON",
                "focusedStage" to "IMPLEMENT",
                "taskAction" to "COMPLETE",
            ),
        )

        val parsedTask = tasksService.parse(workflow.id).single { it.id == createdTask.id }
        val persisted = artifactService.readArtifact(workflow.id, StageId.TASKS).orEmpty()
        val taskEvents = storage.listAuditEvents(workflow.id).getOrThrow()
            .filter { event ->
                event.eventType == SpecAuditEventType.TASK_STATUS_CHANGED ||
                    event.eventType == SpecAuditEventType.RELATED_FILES_UPDATED ||
                    event.eventType == SpecAuditEventType.TASK_VERIFICATION_RESULT_UPDATED
            }

        assertEquals(TaskStatus.COMPLETED, parsedTask.status)
        assertEquals(listOf("README.md", "src/main/kotlin/SpecWorkflowPanel.kt"), parsedTask.relatedFiles)
        assertEquals(VerificationConclusion.PASS, parsedTask.verificationResult?.conclusion)
        assertEquals("verify-task-70", parsedTask.verificationResult?.runId)
        assertTrue(persisted.contains("status: COMPLETED"))
        assertTrue(persisted.contains("relatedFiles:\n  - README.md\n  - src/main/kotlin/SpecWorkflowPanel.kt"))
        assertTrue(persisted.contains("verificationResult:"))
        assertTrue(persisted.contains("conclusion: PASS"))
        assertTrue(persisted.contains("runId: verify-task-70"))
        assertEquals(
            listOf(
                SpecAuditEventType.RELATED_FILES_UPDATED,
                SpecAuditEventType.TASK_VERIFICATION_RESULT_UPDATED,
                SpecAuditEventType.TASK_STATUS_CHANGED,
            ),
            taskEvents.takeLast(3).map(SpecAuditEvent::eventType),
        )
        assertTrue(taskEvents.takeLast(3).all { it.details["taskId"] == createdTask.id })
        assertTrue(taskEvents.takeLast(3).all { it.details["triggerSource"] == "SPEC_PAGE_TASK_BUTTON" })
    }

    @Test
    fun `clarification confirmation should write artifact keep raw context in audit and record history snapshot`() {
        val engine = SpecEngine(project, storage, generationHandler = ::generateValidDocument)
        val workflow = engine.createWorkflow(
            title = "Clarification Regression",
            description = "task 70 clarification regression",
        ).getOrThrow()
        val payload = ConfirmedClarificationPayload(
            confirmedContext = """
                **Confirmed Clarification Points**
                - Do we need offline mode?
                  - Detail: Support local fallback when the network is unavailable
            """.trimIndent(),
            questionsMarkdown = "1. Do we need offline mode?",
            structuredQuestions = listOf("Do we need offline mode?"),
            clarificationRound = 3,
        )

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflow.id,
                input = "build a todo app",
                options = GenerationOptions(
                    confirmedContext = payload.confirmedContext,
                    clarificationWriteback = payload,
                ),
            ).collect()
        }

        val reloaded = engine.reloadWorkflow(workflow.id).getOrThrow()
        val savedContent = reloaded.getDocument(SpecPhase.SPECIFY)?.content.orEmpty()
        val auditEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { it.eventType == SpecAuditEventType.CLARIFICATION_CONFIRMED }
        val history = engine.listDocumentHistory(workflow.id, SpecPhase.SPECIFY)
        val latestSnapshot = engine.loadDocumentSnapshot(
            workflowId = workflow.id,
            phase = SpecPhase.SPECIFY,
            snapshotId = history.first().snapshotId,
        ).getOrThrow()

        assertTrue(savedContent.contains("## Clarifications"))
        assertTrue(savedContent.contains("- Do we need offline mode: Support local fallback when the network is unavailable"))
        assertFalse(savedContent.contains("**Confirmed Clarification Points**"))
        assertEquals("requirements.md", auditEvent.details["file"])
        assertEquals("Clarifications", auditEvent.details["section"])
        assertEquals("3", auditEvent.details["clarificationRound"])
        assertTrue(auditEvent.details.getValue("confirmedContext").contains("offline mode"))
        assertTrue(auditEvent.details.getValue("questionsMarkdown").contains("offline mode"))
        assertFalse(history.isEmpty())
        assertTrue(latestSnapshot.content.contains("## Clarifications"))
    }

    private fun writeProjectConfig(content: String) {
        val configPath = tempDir.resolve(".spec-coding").resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, content)
    }

    private fun generateValidDocument(request: SpecGenerationRequest): SpecGenerationResult {
        val content = when (request.phase) {
            SpecPhase.SPECIFY -> """
                # Requirements Document

                ## Functional Requirements
                - Users can create tasks.

                ## Non-Functional Requirements
                - Response time should stay under 1 second.

                ## User Stories
                As a user, I want to create tasks, so that I can track work.
            """.trimIndent()

            SpecPhase.DESIGN -> """
                # Design Document

                ## Architecture Design
                - Keep workflow state deterministic.

                ## Technical Choices
                - Kotlin and IntelliJ Platform.

                ## Non-Functional Requirements
                - Preserve auditability.
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                # Implement Document

                ## Task List

                ### T-001: Bootstrap implementation
                ```spec-task
                status: PENDING
                priority: P1
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
            """.trimIndent()
        }
        val candidate = SpecDocument(
            id = "doc-${request.phase.name.lowercase()}",
            phase = request.phase,
            content = content,
            metadata = SpecMetadata(
                title = "${request.phase.displayName} Document",
                description = "Generated ${request.phase.displayName} document",
            ),
        )
        return SpecGenerationResult.Success(
            candidate.copy(validationResult = SpecValidator.validate(candidate))
        )
    }
}
