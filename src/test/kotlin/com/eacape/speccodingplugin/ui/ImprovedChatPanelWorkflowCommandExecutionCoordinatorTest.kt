package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class ImprovedChatPanelWorkflowCommandExecutionCoordinatorTest {

    @Test
    fun `execute in background should emit running status and changeset plan for completed command`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins {}")
        val beforeSnapshot = WorkspaceChangesetCollector.capture(tempDir)
        val runningStatuses = mutableListOf<String>()
        val sanitizedInputs = mutableListOf<String>()
        val dispatchRequest = shellCommandRequest("gradle verify")
        val coordinator = ImprovedChatPanelWorkflowCommandExecutionCoordinator(
            timeoutSeconds = 1800,
            outputLimitChars = 12_000,
            captureBeforeSnapshot = { beforeSnapshot },
            executeCommand = { command, onStarted ->
                assertEquals("gradle verify", command)
                onStarted?.invoke()
                ImprovedChatPanelWorkflowCommandRunOutcome.Completed(
                    ImprovedChatPanelWorkflowCommandExecutionResult(
                        success = true,
                        exitCode = 0,
                        output = " verification passed \n",
                    ),
                )
            },
            sanitizeDisplayOutput = { output ->
                sanitizedInputs += output
                output.trim()
            },
            showRunningStatus = runningStatuses::add,
        )

        val result = coordinator.executeInBackground(
            ImprovedChatPanelWorkflowCommandBackgroundRequest(
                dispatchRequest = dispatchRequest,
                shouldHideStatus = true,
            ),
        )

        assertEquals(
            listOf(ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildBackgroundRunningStatus("gradle verify")),
            runningStatuses,
        )
        assertEquals(listOf(" verification passed \n"), sanitizedInputs)
        assertEquals(dispatchRequest.operationRequest, result.operationRequest)
        assertEquals(true, result.feedback.shouldHideStatus)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            result.feedback.conversationMessageKind,
        )
        assertTrue(result.feedback.conversationMessage.orEmpty().contains("verification passed"))
        val changesetPlan = result.changesetPlan
        assertNotNull(changesetPlan)
        assertEquals("gradle verify", changesetPlan?.command)
        assertEquals(beforeSnapshot, changesetPlan?.beforeSnapshot)
        assertEquals(true, changesetPlan?.execution?.success)
    }

    @Test
    fun `execute in background should map failed start to error feedback without changeset`() {
        val sanitizerCalled = AtomicBoolean(false)
        val coordinator = ImprovedChatPanelWorkflowCommandExecutionCoordinator(
            timeoutSeconds = 1800,
            outputLimitChars = 12_000,
            captureBeforeSnapshot = { null },
            executeCommand = { _, _ ->
                ImprovedChatPanelWorkflowCommandRunOutcome.FailedToStart("shell unavailable")
            },
            sanitizeDisplayOutput = {
                sanitizerCalled.set(true)
                it
            },
            showRunningStatus = { error("Running status should not be emitted") },
        )

        val result = coordinator.executeInBackground(
            ImprovedChatPanelWorkflowCommandBackgroundRequest(
                dispatchRequest = shellCommandRequest("gradle verify"),
                shouldHideStatus = false,
            ),
        )

        assertFalse(sanitizerCalled.get())
        assertNull(result.changesetPlan)
        assertEquals(false, result.feedback.operationRecordedSuccess)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.ERROR,
            result.feedback.conversationMessageKind,
        )
    }

    @Test
    fun `execute in background should keep duplicate run as system feedback without changeset`() {
        val sanitizerCalled = AtomicBoolean(false)
        val runningStatuses = mutableListOf<String>()
        val coordinator = ImprovedChatPanelWorkflowCommandExecutionCoordinator(
            timeoutSeconds = 1800,
            outputLimitChars = 12_000,
            captureBeforeSnapshot = { null },
            executeCommand = { _, _ ->
                ImprovedChatPanelWorkflowCommandRunOutcome.AlreadyRunning
            },
            sanitizeDisplayOutput = {
                sanitizerCalled.set(true)
                it
            },
            showRunningStatus = runningStatuses::add,
        )

        val result = coordinator.executeInBackground(
            ImprovedChatPanelWorkflowCommandBackgroundRequest(
                dispatchRequest = shellCommandRequest("gradle verify"),
                shouldHideStatus = false,
            ),
        )

        assertFalse(sanitizerCalled.get())
        assertTrue(runningStatuses.isEmpty())
        assertNull(result.changesetPlan)
        assertEquals(
            ImprovedChatPanelWorkflowCommandFeedbackMessageKind.SYSTEM,
            result.feedback.conversationMessageKind,
        )
    }

    private fun shellCommandRequest(command: String): ImprovedChatPanelShellCommandDispatchRequest {
        return ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = command,
            requestDescription = "Workflow quick action command: $command",
        ) ?: error("Expected dispatch request")
    }
}
