package com.eacape.speccodingplugin.session

import com.eacape.speccodingplugin.spec.ClarificationRetryState
import com.eacape.speccodingplugin.spec.ExecutionTrigger
import com.eacape.speccodingplugin.spec.SpecArtifactService
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecStorage
import com.eacape.speccodingplugin.spec.SpecTasksService
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.TaskExecutionRun
import com.eacape.speccodingplugin.spec.TaskExecutionRunStatus
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkflowChatContextAssemblerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var artifactService: SpecArtifactService
    private lateinit var storage: SpecStorage
    private lateinit var tasksService: SpecTasksService

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        artifactService = SpecArtifactService(project)
        storage = SpecStorage.getInstance(project)
        tasksService = SpecTasksService(project)
    }

    @Test
    fun `buildPrompt should include bound task stage artifact clarification and recent run context`() {
        val workflowId = "wf-chat-context"
        seedWorkflow(
            workflowId = workflowId,
            requirementsMarkdown = """
                # Requirements

                ## Summary
                Offline support must remain available.

                ## Clarifications
                - The workflow must remain file-first.
            """.trimIndent(),
            designMarkdown = """
                # Design

                ## Decisions
                - Prefer reusing the current workflow chat binding.
                - Use task-scoped execution runs for AI execution.
            """.trimIndent(),
            tasksMarkdown = """
                # Implement Document

                ## Task List

                ### T-001: Bootstrap workflow model
                ```spec-task
                status: COMPLETED
                priority: P0
                dependsOn: []
                relatedFiles:
                  - src/main/kotlin/com/eacape/speccodingplugin/spec/SpecTaskExecutionService.kt
                verificationResult: null
                ```
                - [x] Done.

                ### T-002: Build workflow chat context assembler
                ```spec-task
                status: PENDING
                priority: P0
                dependsOn:
                  - T-001
                relatedFiles:
                  - src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt
                verificationResult: null
                ```
                - [ ] Build prompt context.
            """.trimIndent(),
            taskExecutionRuns = listOf(
                TaskExecutionRun(
                    runId = "run-t-002-recent",
                    taskId = "T-002",
                    status = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                    trigger = ExecutionTrigger.USER_EXECUTE,
                    startedAt = "2026-03-14T01:10:00Z",
                    summary = "Awaiting confirmation after AI execution.",
                ),
                TaskExecutionRun(
                    runId = "run-t-001-done",
                    taskId = "T-001",
                    status = TaskExecutionRunStatus.SUCCEEDED,
                    trigger = ExecutionTrigger.USER_EXECUTE,
                    startedAt = "2026-03-13T23:00:00Z",
                    finishedAt = "2026-03-13T23:15:00Z",
                    summary = "Bootstrap completed.",
                ),
            ),
            clarificationRetryState = ClarificationRetryState(
                input = "Need a workflow-aware prompt.",
                confirmedContext = """
                    - Keep workflow chat grounded in the current stage.
                    - Surface recent task execution history.
                """.trimIndent(),
                questionsMarkdown = "1. Should workflow chat reuse task execution context?",
                structuredQuestions = listOf("Should workflow chat reuse task execution context?"),
                clarificationRound = 2,
                confirmed = true,
            ),
        )
        val assembler = WorkflowChatContextAssembler(
            project = project,
            storage = storage,
            tasksService = tasksService,
        )

        val prompt = assembler.buildPrompt(
            binding = WorkflowChatBinding(
                workflowId = workflowId,
                focusedStage = StageId.DESIGN,
                source = WorkflowChatEntrySource.SPEC_PAGE,
            ),
            userInstruction = "Refine the next implementation step.",
        )

        assertTrue(prompt.contains("Workflow=$workflowId"))
        assertTrue(prompt.contains("Current stage: IMPLEMENT"))
        assertTrue(prompt.contains("Focused stage: DESIGN"))
        assertTrue(prompt.contains("Task ID: T-002"))
        assertTrue(prompt.contains("Task Display Status: IN_PROGRESS"))
        assertTrue(prompt.contains("T-001 | COMPLETED | Bootstrap workflow model"))
        assertTrue(prompt.contains("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"))
        assertTrue(prompt.contains("requirements.md: Offline support must remain available."))
        assertTrue(prompt.contains("Use task-scoped execution runs for AI execution."))
        assertTrue(prompt.contains("The workflow must remain file-first."))
        assertTrue(prompt.contains("Keep workflow chat grounded in the current stage."))
        assertTrue(prompt.contains("run-t-002-recent | T-002 | WAITING_CONFIRMATION | USER_EXECUTE | Awaiting confirmation after AI execution."))
        assertTrue(prompt.contains("User instruction:\nRefine the next implementation step."))
    }

    @Test
    fun `buildPrompt should fall back when no workflow binding is available`() {
        val assembler = WorkflowChatContextAssembler(
            project = project,
            storage = storage,
            tasksService = tasksService,
        )

        val prompt = assembler.buildPrompt(
            binding = null,
            userInstruction = "Help me start the workflow.",
        )

        assertTrue(prompt.contains("No active workflow. If needed, run /workflow <requirements> first."))
        assertTrue(prompt.contains("User instruction:\nHelp me start the workflow."))
    }

    private fun seedWorkflow(
        workflowId: String,
        requirementsMarkdown: String,
        designMarkdown: String,
        tasksMarkdown: String,
        taskExecutionRuns: List<TaskExecutionRun>,
        clarificationRetryState: ClarificationRetryState,
    ) {
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.IMPLEMENT,
                title = "Workflow Chat Context",
                description = "workflow chat context assembler test",
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                taskExecutionRuns = taskExecutionRuns,
                clarificationRetryState = clarificationRetryState,
                stageStates = linkedMapOf(
                    StageId.REQUIREMENTS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T20:00:00Z",
                        completedAt = "2026-03-13T20:20:00Z",
                    ),
                    StageId.DESIGN to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T20:20:00Z",
                        completedAt = "2026-03-13T20:40:00Z",
                    ),
                    StageId.TASKS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T20:40:00Z",
                        completedAt = "2026-03-13T20:50:00Z",
                    ),
                    StageId.IMPLEMENT to StageState(
                        active = true,
                        status = StageProgress.IN_PROGRESS,
                        enteredAt = "2026-03-13T20:50:00Z",
                        completedAt = null,
                    ),
                    StageId.VERIFY to StageState(active = true, status = StageProgress.NOT_STARTED),
                    StageId.ARCHIVE to StageState(active = true, status = StageProgress.NOT_STARTED),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(workflowId, StageId.REQUIREMENTS, requirementsMarkdown)
        artifactService.writeArtifact(workflowId, StageId.DESIGN, designMarkdown)
        artifactService.writeArtifact(workflowId, StageId.TASKS, tasksMarkdown)
    }
}
