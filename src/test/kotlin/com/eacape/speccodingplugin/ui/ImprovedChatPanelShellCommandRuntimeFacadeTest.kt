package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class ImprovedChatPanelShellCommandRuntimeFacadeTest {

    @Test
    fun `prepare execution should delegate to configured planner`() {
        var observedRequest: ImprovedChatPanelShellCommandExecutionRequest? = null
        val expectedPlan = ImprovedChatPanelShellCommandExecutionPlan.NoOp
        val facade = ImprovedChatPanelShellCommandRuntimeFacade(
            prepareExecutionDelegate = { request ->
                observedRequest = request
                expectedPlan
            },
            executeInBackgroundDelegate = { error("Should not execute background path") },
            prepareStopDelegate = { error("Should not prepare stop") },
            performStopDelegate = { error("Should not perform stop") },
            disposeRuntime = {},
        )
        val request = ImprovedChatPanelShellCommandExecutionRequest(
            command = "npm test",
            requestDescription = "Workflow quick action command: npm test",
            target = ImprovedChatPanelShellCommandExecutionTarget.Background,
        )

        val result = facade.prepareExecution(request)

        assertSame(expectedPlan, result)
        assertSame(request, observedRequest)
    }

    @Test
    fun `execute in background should delegate to configured runtime`() {
        var observedRequest: ImprovedChatPanelWorkflowCommandBackgroundRequest? = null
        val dispatchRequest = dispatchRequest("npm test")
        val expectedResult = ImprovedChatPanelWorkflowCommandBackgroundResult(
            feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildAlreadyRunningFeedback("npm test"),
            persistAsync = true,
            operationRequest = dispatchRequest.operationRequest,
        )
        val facade = ImprovedChatPanelShellCommandRuntimeFacade(
            prepareExecutionDelegate = { error("Should not prepare execution") },
            executeInBackgroundDelegate = { request ->
                observedRequest = request
                expectedResult
            },
            prepareStopDelegate = { error("Should not prepare stop") },
            performStopDelegate = { error("Should not perform stop") },
            disposeRuntime = {},
        )
        val request = ImprovedChatPanelWorkflowCommandBackgroundRequest(
            dispatchRequest = dispatchRequest,
            shouldHideStatus = true,
        )

        val result = facade.executeInBackground(request)

        assertSame(expectedResult, result)
        assertSame(request, observedRequest)
    }

    @Test
    fun `prepare stop and perform stop should delegate to configured stop runtime`() {
        var observedCommand: String? = null
        var observedStopPlan: ImprovedChatPanelWorkflowCommandStopExecutionPlan? = null
        val expectedStopPlan = ImprovedChatPanelWorkflowCommandStopExecutionPlan(
            normalizedCommand = "npm test",
            immediateFeedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopStoppingFeedback("npm test"),
            persistAsync = true,
            shouldAttemptStop = true,
        )
        val expectedOutcome = ImprovedChatPanelWorkflowCommandExecutionOutcomePlan(
            feedback = ImprovedChatPanelWorkflowCommandFeedbackCoordinator.buildStopFailedFeedback(
                command = "npm test",
                errorDetail = "stop failed",
            ),
            persistAsync = false,
        )
        val facade = ImprovedChatPanelShellCommandRuntimeFacade(
            prepareExecutionDelegate = { error("Should not prepare execution") },
            executeInBackgroundDelegate = { error("Should not execute background path") },
            prepareStopDelegate = { command ->
                observedCommand = command
                expectedStopPlan
            },
            performStopDelegate = { stopPlan ->
                observedStopPlan = stopPlan
                expectedOutcome
            },
            disposeRuntime = {},
        )

        val stopPlan = facade.prepareStop("npm test")
        val outcome = facade.performStop(expectedStopPlan)

        assertSame(expectedStopPlan, stopPlan)
        assertEquals("npm test", observedCommand)
        assertSame(expectedStopPlan, observedStopPlan)
        assertSame(expectedOutcome, outcome)
    }

    @Test
    fun `dispose should delegate to runtime cleanup`() {
        val disposed = AtomicBoolean(false)
        val facade = ImprovedChatPanelShellCommandRuntimeFacade(
            prepareExecutionDelegate = { ImprovedChatPanelShellCommandExecutionPlan.NoOp },
            executeInBackgroundDelegate = { error("Should not execute background path") },
            prepareStopDelegate = { null },
            performStopDelegate = { null },
            disposeRuntime = { disposed.set(true) },
        )

        facade.dispose()

        assertTrue(disposed.get())
    }

    private fun dispatchRequest(command: String): ImprovedChatPanelShellCommandDispatchRequest {
        return ImprovedChatPanelShellCommandDispatchCoordinator.buildDispatchRequest(
            command = command,
            requestDescription = "Workflow quick action command: $command",
        ) ?: error("Dispatch request should be created for $command")
    }
}
