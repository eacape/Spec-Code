package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SpecTasksServiceTest {

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
    fun `parseDocument should sort structured tasks by id and preserve handwritten task markdown`() {
        val markdown = """
            # Implement Document

            ## Task List

            ### T-010: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            #### Handwritten note
            This note must stay attached to T-010.
            ### T-002: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Finish this first.
            ### Notes
            This is not a structured task heading and must remain in the current task body.
            ## Test Plan
            - [ ] Re-run tasks service checks.
        """.trimIndent()

        val parsed = tasksService.parseDocument(markdown)

        assertTrue(parsed.issues.isEmpty())
        assertEquals(listOf("T-002", "T-010"), parsed.tasksById.map { task -> task.id })
        assertEquals(listOf("T-010", "T-002"), parsed.taskSectionsInSourceOrder.map { section -> section.entry.id })
        assertTrue(
            parsed.taskSectionsById.first { section -> section.entry.id == "T-002" }
                .sectionMarkdown
                .contains("### Notes\nThis is not a structured task heading"),
        )
        assertTrue(
            parsed.taskSectionsById.first { section -> section.entry.id == "T-010" }
                .sectionMarkdown
                .contains("#### Handwritten note\nThis note must stay attached to T-010."),
        )
        assertTrue(parsed.trailingMarkdown.startsWith("## Test Plan"))
    }

    @Test
    fun `stabilizeTaskArtifact should rewrite tasks md in id order and keep trailing sections`() {
        val workflowId = "wf-tasks-service"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-010: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            #### Handwritten note
            This note must stay attached to T-010.
            ### T-002: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Finish this first.
            ### Notes
            This is not a structured task heading and must remain in the current task body.
            ## Test Plan
            - [ ] Re-run tasks service checks.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val stabilized = tasksService.stabilizeTaskArtifact(workflowId)
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals(listOf("T-002", "T-010"), stabilized?.tasksById?.map { task -> task.id })
        assertTrue(persisted.indexOf("### T-002: First task") < persisted.indexOf("### T-010: Second task"))
        assertTrue(persisted.contains("### Notes\nThis is not a structured task heading and must remain in the current task body."))
        assertTrue(persisted.contains("#### Handwritten note\nThis note must stay attached to T-010."))
        assertTrue(persisted.indexOf("## Test Plan") > persisted.indexOf("### T-010: Second task"))
    }

    @Test
    fun `addTask should allocate next id fill defaults and preserve handwritten sections`() {
        val workflowId = "wf-tasks-add"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-003: Third task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            ### T-001: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Handwritten paragraph that must stay with the first task.

            ## Test Plan
            - [ ] Keep trailing section.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val createdTask = tasksService.addTask(
            workflowId = workflowId,
            title = "Fourth task",
            priority = TaskPriority.P2,
            dependsOn = listOf("T-001"),
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals("T-004", createdTask.id)
        assertEquals(TaskStatus.PENDING, createdTask.status)
        assertEquals(TaskPriority.P2, createdTask.priority)
        assertEquals(listOf("T-001"), createdTask.dependsOn)
        assertTrue(createdTask.relatedFiles.isEmpty())
        assertNull(createdTask.verificationResult)
        assertTrue(persisted.indexOf("### T-001: First task") < persisted.indexOf("### T-003: Third task"))
        assertTrue(persisted.indexOf("### T-003: Third task") < persisted.indexOf("### T-004: Fourth task"))
        assertTrue(persisted.contains("Handwritten paragraph that must stay with the first task."))
        assertTrue(persisted.contains("## Test Plan\n- [ ] Keep trailing section."))
        assertTrue(persisted.contains("dependsOn:\n  - T-001"))
        assertTrue(persisted.contains("verificationResult: null"))
    }

    @Test
    fun `addTask should insert before trailing sections when task list is empty`() {
        val workflowId = "wf-tasks-empty-add"
        val markdown = """
            # Implement Document

            ## Task List

            ## Test Plan
            - [ ] Existing verification notes.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val createdTask = tasksService.addTask(
            workflowId = workflowId,
            title = "Bootstrap task",
            priority = TaskPriority.P0,
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals("T-001", createdTask.id)
        assertTrue(persisted.indexOf("### T-001: Bootstrap task") < persisted.indexOf("## Test Plan"))
        assertTrue(persisted.contains("status: PENDING"))
    }

    @Test
    fun `removeTask should delete only target section and keep trailing markdown`() {
        val workflowId = "wf-tasks-remove"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            First task body.

            ### T-002: Second task
            ```spec-task
            status: IN_PROGRESS
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Second task body.
            #### Keep this note
            This note must remain after deletion.

            ## Test Plan
            - [ ] Run targeted checks.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        tasksService.removeTask(workflowId, "T-001")
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertFalse(persisted.contains("### T-001: First task"))
        assertTrue(persisted.contains("### T-002: Second task"))
        assertTrue(persisted.contains("#### Keep this note\nThis note must remain after deletion."))
        assertTrue(persisted.contains("## Test Plan\n- [ ] Run targeted checks."))
    }

    @Test
    fun `removeTask should fail when task id is missing`() {
        val workflowId = "wf-tasks-remove-missing"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val error = assertThrows(MissingStructuredTaskError::class.java) {
            tasksService.removeTask(workflowId, "T-999")
        }

        assertEquals("Task not found: T-999", error.message)
    }

    @Test
    fun `transitionStatus should update only target spec-task block and append audit event`() {
        val workflowId = "wf-tasks-transition"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            #### Custom note
            This note must stay untouched.

            ### T-002: Second task
            ```spec-task
            status: IN_PROGRESS
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Second task body.

            ## Test Plan
            - [ ] Verify task transitions.
        """.trimIndent()
        val expected = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: BLOCKED
            priority: P0
            dependsOn:
              - T-002
            relatedFiles: []
            verificationResult: null
            ```
            - [ ] Keep this checklist.
            #### Custom note
            This note must stay untouched.

            ### T-002: Second task
            ```spec-task
            status: IN_PROGRESS
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Second task body.

            ## Test Plan
            - [ ] Verify task transitions.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        tasksService.transitionStatus(
            workflowId = workflowId,
            taskId = "t-001",
            to = TaskStatus.BLOCKED,
            reason = "  waiting for follow-up  ",
            auditContext = mapOf(
                "triggerSource" to "SPEC_PAGE_TASK_BUTTON",
                "focusedStage" to "IMPLEMENT",
            ),
        )

        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val updatedTask = tasksService.parse(workflowId).first { task -> task.id == "T-001" }
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow().last()

        assertEquals("$expected\n", persisted)
        assertEquals(TaskStatus.BLOCKED, updatedTask.status)
        assertEquals(SpecAuditEventType.TASK_STATUS_CHANGED, auditEvent.eventType)
        assertEquals("T-001", auditEvent.details["taskId"])
        assertEquals("First task", auditEvent.details["title"])
        assertEquals("PENDING", auditEvent.details["fromStatus"])
        assertEquals("BLOCKED", auditEvent.details["toStatus"])
        assertEquals("waiting for follow-up", auditEvent.details["reason"])
        assertEquals("SPEC_PAGE_TASK_BUTTON", auditEvent.details["triggerSource"])
        assertEquals("IMPLEMENT", auditEvent.details["focusedStage"])
    }

    @Test
    fun `transitionStatus should reject invalid task state changes without touching artifact or audit`() {
        val workflowId = "wf-tasks-transition-invalid"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            First task body.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val originalPersisted = artifactService.readArtifact(workflowId, StageId.TASKS)

        val error = assertThrows(InvalidTaskStateTransitionError::class.java) {
            tasksService.transitionStatus(
                workflowId = workflowId,
                taskId = "T-001",
                to = TaskStatus.IN_PROGRESS,
            )
        }

        assertEquals(
            "Invalid task state transition for T-001: PENDING -> IN_PROGRESS",
            error.message,
        )
        assertEquals(originalPersisted, artifactService.readArtifact(workflowId, StageId.TASKS))
        assertTrue(storage.listAuditEvents(workflowId).getOrThrow().isEmpty())
    }

    @Test
    fun `transitionStatus should reject cancelling task while downstream tasks are not cancelled`() {
        val workflowId = "wf-tasks-transition-cancel-blocked"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: Shared dependency
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-002: Completed dependent
            ```spec-task
            status: COMPLETED
            priority: P1
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```

            ### T-003: Cancelled dependent
            ```spec-task
            status: CANCELLED
            priority: P1
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val originalPersisted = artifactService.readArtifact(workflowId, StageId.TASKS)

        val error = assertThrows(TaskCancellationBlockedByDependentsError::class.java) {
            tasksService.transitionStatus(
                workflowId = workflowId,
                taskId = "T-001",
                to = TaskStatus.CANCELLED,
            )
        }

        assertEquals(
            "Task T-001 cannot be cancelled while dependent tasks are not CANCELLED: T-002",
            error.message,
        )
        assertEquals(originalPersisted, artifactService.readArtifact(workflowId, StageId.TASKS))
        assertTrue(storage.listAuditEvents(workflowId).getOrThrow().isEmpty())
    }

    @Test
    fun `transitionStatus should allow cancelling task after all downstream tasks are cancelled`() {
        val workflowId = "wf-tasks-transition-cancel-allowed"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: Shared dependency
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-002: Cancelled dependent
            ```spec-task
            status: CANCELLED
            priority: P1
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```

            ### T-003: Another cancelled dependent
            ```spec-task
            status: CANCELLED
            priority: P1
            dependsOn:
              - T-001
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        tasksService.transitionStatus(
            workflowId = workflowId,
            taskId = "T-001",
            to = TaskStatus.CANCELLED,
        )

        val updatedTask = tasksService.parse(workflowId).first { task -> task.id == "T-001" }
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow().last()

        assertEquals(TaskStatus.CANCELLED, updatedTask.status)
        assertEquals(SpecAuditEventType.TASK_STATUS_CHANGED, auditEvent.eventType)
        assertEquals("CANCELLED", auditEvent.details["toStatus"])
    }

    @Test
    fun `addTask should normalize dependsOn ids to sorted distinct canonical values`() {
        val workflowId = "wf-tasks-add-depends-on"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-002: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val createdTask = tasksService.addTask(
            workflowId = workflowId,
            title = "Third task",
            priority = TaskPriority.P2,
            dependsOn = listOf(" t-002 ", "T-001", "t-001", "T-002"),
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()

        assertEquals(listOf("T-001", "T-002"), createdTask.dependsOn)
        assertTrue(
            persisted.contains("dependsOn:\n  - T-001\n  - T-002"),
            "Expected normalized dependsOn list in persisted artifact.",
        )
    }

    @Test
    fun `updateDependsOn should reject missing or self dependencies without touching artifact`() {
        val workflowId = "wf-tasks-update-depends-on-invalid"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```

            ### T-002: Second task
            ```spec-task
            status: PENDING
            priority: P1
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val originalPersisted = artifactService.readArtifact(workflowId, StageId.TASKS)

        val selfDependencyError = assertThrows(TaskSelfDependencyError::class.java) {
            tasksService.updateDependsOn(workflowId, "T-001", listOf("t-001"))
        }
        assertEquals("Task T-001 cannot depend on itself", selfDependencyError.message)

        val missingDependencyError = assertThrows(MissingTaskDependencyError::class.java) {
            tasksService.updateDependsOn(workflowId, "T-001", listOf("T-999"))
        }
        assertEquals(
            "Task T-001 depends on missing task id: T-999",
            missingDependencyError.message,
        )
        assertEquals(originalPersisted, artifactService.readArtifact(workflowId, StageId.TASKS))
        assertTrue(storage.listAuditEvents(workflowId).getOrThrow().isEmpty())
    }

    @Test
    fun `updateRelatedFiles should normalize dedupe sort and append audit event`() {
        val workflowId = "wf-tasks-related-files"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
            Handwritten paragraph that must stay untouched.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val updatedTask = tasksService.updateRelatedFiles(
            workflowId = workflowId,
            taskId = "T-001",
            files = listOf(
                " ./src\\main\\kotlin\\App.kt ",
                tempDir.resolve("docs/../README.md").toString(),
                "src/main/kotlin/App.kt",
            ),
            auditContext = mapOf(
                "documentBinding" to "tasks.md",
                "taskAction" to "COMPLETE",
            ),
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow().last()

        assertEquals(listOf("README.md", "src/main/kotlin/App.kt"), updatedTask.relatedFiles)
        assertTrue(
            persisted.contains("relatedFiles:\n  - README.md\n  - src/main/kotlin/App.kt"),
            "Expected normalized relatedFiles list in persisted artifact.",
        )
        assertTrue(persisted.contains("Handwritten paragraph that must stay untouched."))
        assertEquals(SpecAuditEventType.RELATED_FILES_UPDATED, auditEvent.eventType)
        assertEquals("T-001", auditEvent.details["taskId"])
        assertEquals("First task", auditEvent.details["title"])
        assertEquals("2", auditEvent.details["fileCount"])
        assertEquals("README.md, src/main/kotlin/App.kt", auditEvent.details["relatedFiles"])
        assertEquals("tasks.md", auditEvent.details["documentBinding"])
        assertEquals("COMPLETE", auditEvent.details["taskAction"])
    }

    @Test
    fun `updateRelatedFiles should reject paths outside project root without touching artifact or audit`() {
        val workflowId = "wf-tasks-related-files-outside-root"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val originalPersisted = artifactService.readArtifact(workflowId, StageId.TASKS)

        val error = assertThrows(RelatedFileOutsideProjectRootError::class.java) {
            tasksService.updateRelatedFiles(
                workflowId = workflowId,
                taskId = "T-001",
                files = listOf("../secret.txt"),
            )
        }

        assertTrue(error.message!!.contains("Task T-001 related file escapes project root"))
        assertEquals(originalPersisted, artifactService.readArtifact(workflowId, StageId.TASKS))
        assertTrue(storage.listAuditEvents(workflowId).getOrThrow().isEmpty())
    }

    @Test
    fun `updateRelatedFiles should reject paths with line breaks without touching artifact or audit`() {
        val workflowId = "wf-tasks-related-files-line-break"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: First task
            ```spec-task
            status: IN_PROGRESS
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val originalPersisted = artifactService.readArtifact(workflowId, StageId.TASKS)

        val error = assertThrows(InvalidTaskRelatedFileError::class.java) {
            tasksService.updateRelatedFiles(
                workflowId = workflowId,
                taskId = "T-001",
                files = listOf("src/main/kotlin/App.kt\nmalicious.yaml"),
            )
        }

        assertTrue(error.message.orEmpty().contains("line breaks or NUL"))
        assertEquals(originalPersisted, artifactService.readArtifact(workflowId, StageId.TASKS))
        assertTrue(storage.listAuditEvents(workflowId).getOrThrow().isEmpty())
    }

    @Test
    fun `updateVerificationResult should serialize deterministically and append audit event`() {
        val workflowId = "wf-tasks-verification-result-update"
        val markdown = """
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
            Handwritten paragraph that must stay untouched.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val updatedTask = tasksService.updateVerificationResult(
            workflowId = workflowId,
            taskId = "t-001",
            verificationResult = TaskVerificationResult(
                conclusion = VerificationConclusion.WARN,
                runId = "  run-verify-001  ",
                summary = "  Warning: flaky assertion\nNeeds manual follow-up  ",
                at = " 2026-03-09T10:11:12Z ",
            ),
            auditContext = mapOf(
                "documentSummary" to "Ship implementation changes",
                "triggerSource" to "SPEC_PAGE_TASK_BUTTON",
            ),
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val reparsedTask = tasksService.parse(workflowId).single()
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow().last()
        val expectedVerificationYaml = SpecYamlCodec.encodeMap(
            linkedMapOf(
                "verificationResult" to linkedMapOf(
                    "conclusion" to "WARN",
                    "runId" to "run-verify-001",
                    "summary" to "Warning: flaky assertion\nNeeds manual follow-up",
                    "at" to "2026-03-09T10:11:12Z",
                ),
            ),
        ).trimEnd()

        assertEquals(
            TaskVerificationResult(
                conclusion = VerificationConclusion.WARN,
                runId = "run-verify-001",
                summary = "Warning: flaky assertion\nNeeds manual follow-up",
                at = "2026-03-09T10:11:12Z",
            ),
            updatedTask.verificationResult,
        )
        assertEquals(updatedTask.verificationResult, reparsedTask.verificationResult)
        assertTrue(persisted.contains(expectedVerificationYaml))
        assertTrue(persisted.contains("Handwritten paragraph that must stay untouched."))
        assertEquals(SpecAuditEventType.TASK_VERIFICATION_RESULT_UPDATED, auditEvent.eventType)
        assertEquals("T-001", auditEvent.details["taskId"])
        assertEquals("Verify implementation", auditEvent.details["title"])
        assertEquals("SET", auditEvent.details["action"])
        assertEquals("WARN", auditEvent.details["conclusion"])
        assertEquals("run-verify-001", auditEvent.details["runId"])
        assertEquals("2026-03-09T10:11:12Z", auditEvent.details["at"])
        assertEquals("Ship implementation changes", auditEvent.details["documentSummary"])
        assertEquals("SPEC_PAGE_TASK_BUTTON", auditEvent.details["triggerSource"])
    }

    @Test
    fun `clearVerificationResult should reset field to null and append audit event`() {
        val workflowId = "wf-tasks-verification-result-clear"
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: Verify implementation
            ```spec-task
            status: COMPLETED
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult:
              conclusion: PASS
              runId: run-verify-002
              summary: looks good
              at: "2026-03-09T11:12:13Z"
            ```
            Handwritten paragraph that must stay untouched.
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)

        val updatedTask = tasksService.clearVerificationResult(
            workflowId = workflowId,
            taskId = "T-001",
        )
        val persisted = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val reparsedTask = tasksService.parse(workflowId).single()
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow().last()

        assertNull(updatedTask.verificationResult)
        assertNull(reparsedTask.verificationResult)
        assertTrue(persisted.contains("verificationResult: null"))
        assertTrue(persisted.contains("Handwritten paragraph that must stay untouched."))
        assertEquals(SpecAuditEventType.TASK_VERIFICATION_RESULT_UPDATED, auditEvent.eventType)
        assertEquals("T-001", auditEvent.details["taskId"])
        assertEquals("Verify implementation", auditEvent.details["title"])
        assertEquals("CLEARED", auditEvent.details["action"])
        assertEquals("PASS", auditEvent.details["previousConclusion"])
        assertEquals("run-verify-002", auditEvent.details["previousRunId"])
        assertEquals("2026-03-09T11:12:13Z", auditEvent.details["previousAt"])
    }

    @Test
    fun `updateVerificationResult should reject blank fields without touching artifact or audit`() {
        val workflowId = "wf-tasks-verification-result-invalid"
        val markdown = """
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
        """.trimIndent()

        artifactService.writeArtifact(workflowId, StageId.TASKS, markdown)
        val originalPersisted = artifactService.readArtifact(workflowId, StageId.TASKS)

        val error = assertThrows(InvalidTaskVerificationResultError::class.java) {
            tasksService.updateVerificationResult(
                workflowId = workflowId,
                taskId = "T-001",
                verificationResult = TaskVerificationResult(
                    conclusion = VerificationConclusion.FAIL,
                    runId = "run-verify-003",
                    summary = "   ",
                    at = "2026-03-09T12:13:14Z",
                ),
            )
        }

        assertEquals(
            "Task T-001 has invalid verificationResult field `summary`: must be a non-blank string",
            error.message,
        )
        assertEquals(originalPersisted, artifactService.readArtifact(workflowId, StageId.TASKS))
        assertTrue(storage.listAuditEvents(workflowId).getOrThrow().isEmpty())
    }

    @Test
    fun `parseDocument should reuse cached parsed tasks document for identical markdown`() {
        val markdown = """
            # Implement Document

            ## Task List

            ### T-001: Cache hit
            ```spec-task
            status: PENDING
            priority: P0
            dependsOn: []
            relatedFiles: []
            verificationResult: null
            ```
        """.trimIndent()

        val first = tasksService.parseDocument(markdown)
        val second = tasksService.parseDocument(markdown.replace("\n", "\r\n"))

        assertSame(first, second)
    }
}
