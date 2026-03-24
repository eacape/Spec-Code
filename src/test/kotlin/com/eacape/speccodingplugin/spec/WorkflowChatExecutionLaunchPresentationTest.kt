package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowChatExecutionLaunchPresentationTest {

    @Test
    fun `codec should round trip execution launch presentation`() {
        val presentation = WorkflowChatExecutionLaunchPresentation(
            workflowId = "wf-chat-launch",
            taskId = "T-017",
            taskTitle = "Tighten workflow chat execution request UX",
            runId = "run-017",
            focusedStage = StageId.TASKS,
            trigger = ExecutionTrigger.USER_EXECUTE,
            launchSurface = WorkflowChatExecutionLaunchSurface.TASK_ROW,
            taskStatusBeforeExecution = TaskStatus.PENDING,
            taskPriority = TaskPriority.P1,
            sections = listOf(
                WorkflowChatExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                    itemCount = 3,
                    previewItems = listOf("requirements.md: tighten chat execution request UX"),
                    truncated = true,
                ),
                WorkflowChatExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT,
                    itemCount = 2,
                    previewItems = listOf("src/main/kotlin/com/eacape/speccodingplugin/spec/SpecTaskExecutionService.kt"),
                ),
            ),
            supplementalInstruction = "Keep the execution launch card concise.",
            degradationReasons = listOf("No structured code context snapshot was persisted in this version."),
            rawPromptDebugAvailable = true,
        )

        val encoded = WorkflowChatExecutionLaunchPresentationCodec.encodeToJson(presentation)
        val decoded = WorkflowChatExecutionLaunchPresentationCodec.decodeFromJson(encoded)

        assertNotNull(decoded)
        assertEquals(presentation, decoded)
    }

    @Test
    fun `resolver should degrade legacy raw prompt into compact execution notice`() {
        val payload = WorkflowChatExecutionLaunchRestoreResolver.resolve(
            presentation = null,
            rawPromptContent = legacyExecutionPrompt(),
            workflowId = "wf-chat-launch",
            taskId = "T-017",
            runId = "run-017",
            trigger = ExecutionTrigger.USER_EXECUTE,
        )

        assertTrue(payload is WorkflowChatExecutionLaunchRestorePayload.LegacyCompact)
        val notice = (payload as WorkflowChatExecutionLaunchRestorePayload.LegacyCompact).notice
        assertEquals("wf-chat-launch", notice.workflowId)
        assertEquals("T-017", notice.taskId)
        assertEquals("Tighten workflow chat execution request UX", notice.taskTitle)
        assertEquals("run-017", notice.runId)
        assertEquals(StageId.TASKS, notice.focusedStage)
        assertEquals(ExecutionTrigger.USER_EXECUTE, notice.trigger)
        assertEquals(
            WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA,
            notice.fallbackReason,
        )
        assertTrue(notice.sectionKinds.contains(WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES))
        assertTrue(notice.sectionKinds.contains(WorkflowChatExecutionPresentationSectionKind.CLARIFICATION_CONCLUSIONS))
        assertTrue(notice.sectionKinds.contains(WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT))
        assertTrue(notice.supplementalInstructionPresent)
        assertTrue(notice.rawPromptDebugAvailable)
    }

    @Test
    fun `decoded task execution metadata should prefer structured launch presentation`() {
        val run = TaskExecutionRun(
            runId = "run-017",
            taskId = "T-017",
            status = TaskExecutionRunStatus.QUEUED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-18T09:00:00Z",
        )
        val presentation = WorkflowChatExecutionLaunchPresentation(
            workflowId = "wf-chat-launch",
            taskId = "T-017",
            taskTitle = "Tighten workflow chat execution request UX",
            runId = "run-017",
            focusedStage = StageId.TASKS,
            trigger = ExecutionTrigger.USER_EXECUTE,
            launchSurface = WorkflowChatExecutionLaunchSurface.TASK_ROW,
            sections = listOf(
                WorkflowChatExecutionPresentationSection(
                    kind = WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES,
                    itemCount = 1,
                    previewItems = listOf("requirements.md: execution request card should replace raw prompt"),
                ),
            ),
            rawPromptDebugAvailable = true,
        )

        val encodedMetadata = TaskExecutionSessionMetadataCodec.encode(
            run = run,
            workflowId = "wf-chat-launch",
            requestId = "request-017",
            providerId = "mock",
            modelId = "mock-model-v1",
            previousRunId = null,
            launchPresentation = presentation,
            launchDebugPayload = WorkflowChatExecutionLaunchDebugPayload(
                rawPrompt = legacyExecutionPrompt(),
            ),
        )

        val decoded = TaskExecutionSessionMetadataCodec.decode(encodedMetadata)
        val payload = decoded.resolveExecutionLaunchRestorePayload(legacyExecutionPrompt())

        assertEquals(presentation, decoded.launchPresentation)
        assertEquals(legacyExecutionPrompt(), decoded.resolveExecutionLaunchRawPrompt())
        assertTrue(payload is WorkflowChatExecutionLaunchRestorePayload.Presentation)
        assertEquals(
            presentation,
            (payload as WorkflowChatExecutionLaunchRestorePayload.Presentation).launch,
        )
    }

    @Test
    fun `decoded task execution metadata should reuse debug raw prompt when visible content is condensed`() {
        val run = TaskExecutionRun(
            runId = "run-018",
            taskId = "T-018",
            status = TaskExecutionRunStatus.QUEUED,
            trigger = ExecutionTrigger.USER_EXECUTE,
            startedAt = "2026-03-18T09:10:00Z",
        )
        val encodedMetadata = TaskExecutionSessionMetadataCodec.encode(
            run = run,
            workflowId = "wf-chat-launch",
            requestId = "request-018",
            providerId = "mock",
            modelId = "mock-model-v1",
            previousRunId = null,
            launchDebugPayload = WorkflowChatExecutionLaunchDebugPayload(
                rawPrompt = legacyExecutionPrompt(),
            ),
        )

        val decoded = TaskExecutionSessionMetadataCodec.decode(encodedMetadata)
        val payload = decoded.resolveExecutionLaunchRestorePayload(
            """
                ## Execution Request
                - Task: T-018 · condensed
            """.trimIndent(),
        )

        assertTrue(payload is WorkflowChatExecutionLaunchRestorePayload.LegacyCompact)
        val notice = (payload as WorkflowChatExecutionLaunchRestorePayload.LegacyCompact).notice
        assertEquals("T-017", notice.taskId)
        assertEquals(
            WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA,
            notice.fallbackReason,
        )
        assertTrue(notice.rawPromptDebugAvailable)
    }

    private fun legacyExecutionPrompt(): String {
        return """
            Interaction mode: workflow
            Workflow=wf-chat-launch (docs: .spec-coding/specs/wf-chat-launch/{requirements,design,tasks}.md)
            Execution action: EXECUTE_WITH_AI
            Run ID: run-017
            
            ## Task
            Task ID: T-017
            Task Title: Tighten workflow chat execution request UX
            Task Status: PENDING
            Priority: P1
            
            ## Stage Context
            Current phase: IMPLEMENT
            Current stage: TASKS
            
            ## Dependencies
            - T-010 / COMPLETED / Persist workflow chat binding
            
            ## Artifact Summaries
            - requirements.md: workflow chat should show a structured execution request card.
            - design.md: execution launch presentation metadata should be restorable.
            
            ## Confirmed Clarification Conclusions
            - hide the raw execution prompt by default.
            
            ## Candidate Related Files
            - src/main/kotlin/com/eacape/speccodingplugin/spec/SpecTaskExecutionService.kt
            - src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt
            
            ## Supplemental Instruction
            Keep the launch card concise.
            
            ## Execution Request
            Use the task-scoped context above to execute this structured task.
        """.trimIndent()
    }
}
