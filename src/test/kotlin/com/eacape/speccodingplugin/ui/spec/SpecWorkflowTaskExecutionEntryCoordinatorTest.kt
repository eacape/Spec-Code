package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.OperationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowTaskExecutionEntryCoordinatorTest {

    @Test
    fun `requestExecution should build execution request with reusable session`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        val accepted = coordinator.requestExecution(
            launchRequest(
                workflowId = " wf-1 ",
                taskId = "T-1",
                providerId = " provider-1 ",
                modelId = " model-1 ",
            ),
        )

        assertTrue(accepted)
        assertEquals(
            listOf(
                SessionLookupCall(
                    workflowId = "wf-1",
                    taskId = "T-1",
                    preferredSessionId = "preferred-session",
                ),
            ),
            recorder.sessionLookups,
        )
        assertEquals(
            SpecWorkflowTaskExecutionRequest(
                workflowId = "wf-1",
                taskId = "T-1",
                providerId = "provider-1",
                modelId = "model-1",
                operationMode = OperationMode.AUTO,
                sessionId = "reusable-session",
                retry = false,
                auditContext = linkedMapOf("source" to "panel"),
            ),
            recorder.executionRequest,
        )
        assertTrue(recorder.statusTexts.isEmpty())
        assertTrue(recorder.failureStatuses.isEmpty())
    }

    @Test
    fun `requestExecution should require provider selection`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        val accepted = coordinator.requestExecution(
            launchRequest(providerId = " "),
        )

        assertFalse(accepted)
        assertTrue(recorder.statusTexts.isEmpty())
        assertEquals(
            listOf(
                FailureStatusPresentation(
                    text = SpecCodingBundle.message("spec.toolwindow.tasks.execute.providerRequired"),
                    actions = recorder.runtimeActions,
                ),
            ),
            recorder.failureStatuses,
        )
        assertEquals(
            listOf(
                TroubleshootingRequest(
                    workflowId = "wf-1",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_PRECHECK,
                ),
            ),
            recorder.troubleshootingRequests,
        )
        assertTrue(recorder.sessionLookups.isEmpty())
        assertNull(recorder.executionRequest)
    }

    @Test
    fun `requestExecution should require model selection and use provider display name`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        val accepted = coordinator.requestExecution(
            launchRequest(
                providerId = "provider-2",
                modelId = " ",
            ),
        )

        assertFalse(accepted)
        assertEquals(listOf("provider-2"), recorder.providerDisplayNames)
        assertTrue(recorder.statusTexts.isEmpty())
        assertEquals(
            listOf(
                FailureStatusPresentation(
                    text = SpecCodingBundle.message(
                        "spec.toolwindow.tasks.execute.modelRequired",
                        "Provider provider-2",
                    ),
                    actions = recorder.runtimeActions,
                ),
            ),
            recorder.failureStatuses,
        )
        assertEquals(
            listOf(
                TroubleshootingRequest(
                    workflowId = "wf-1",
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.TASK_EXECUTION_PRECHECK,
                ),
            ),
            recorder.troubleshootingRequests,
        )
        assertTrue(recorder.sessionLookups.isEmpty())
        assertNull(recorder.executionRequest)
    }

    @Test
    fun `requestExecution should keep retry flag and allow missing reusable session`() {
        val recorder = RecordingEnvironment().apply {
            reusableSessionId = null
            preferredSessionId = null
        }
        val coordinator = coordinator(recorder)

        val accepted = coordinator.requestExecution(
            launchRequest(
                taskId = "T-9",
                retry = true,
            ),
        )

        assertTrue(accepted)
        assertEquals(
            listOf(SessionLookupCall(workflowId = "wf-1", taskId = "T-9", preferredSessionId = null)),
            recorder.sessionLookups,
        )
        assertEquals(true, recorder.executionRequest?.retry)
        assertNull(recorder.executionRequest?.sessionId)
        assertTrue(recorder.failureStatuses.isEmpty())
    }

    private fun coordinator(
        recorder: RecordingEnvironment,
    ): SpecWorkflowTaskExecutionEntryCoordinator {
        return SpecWorkflowTaskExecutionEntryCoordinator(
            activeSessionId = {
                recorder.preferredSessionId
            },
            findReusableWorkflowChatSessionId = { workflowId, taskId, preferredSessionId ->
                recorder.sessionLookups += SessionLookupCall(workflowId, taskId, preferredSessionId)
                recorder.reusableSessionId
            },
            providerDisplayName = { providerId ->
                recorder.providerDisplayNames += providerId
                "Provider $providerId"
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            showFailureStatus = { text, actions ->
                recorder.failureStatuses += FailureStatusPresentation(text, actions)
            },
            buildRuntimeTroubleshootingActions = { workflowId, trigger ->
                recorder.troubleshootingRequests += TroubleshootingRequest(workflowId, trigger)
                recorder.runtimeActions
            },
            execute = { request ->
                recorder.executionRequest = request
            },
        )
    }

    private fun launchRequest(
        workflowId: String = "wf-1",
        taskId: String = "T-1",
        providerId: String? = "provider-1",
        modelId: String? = "model-1",
        retry: Boolean = false,
    ): SpecWorkflowTaskExecutionLaunchRequest {
        return SpecWorkflowTaskExecutionLaunchRequest(
            workflowId = workflowId,
            taskId = taskId,
            providerId = providerId,
            modelId = modelId,
            operationMode = OperationMode.AUTO,
            retry = retry,
            auditContext = linkedMapOf("source" to "panel"),
        )
    }

    private inner class RecordingEnvironment {
        var preferredSessionId: String? = "preferred-session"
        var reusableSessionId: String? = "reusable-session"
        val sessionLookups = mutableListOf<SessionLookupCall>()
        val providerDisplayNames = mutableListOf<String>()
        val statusTexts = mutableListOf<String>()
        val failureStatuses = mutableListOf<FailureStatusPresentation>()
        val troubleshootingRequests = mutableListOf<TroubleshootingRequest>()
        val runtimeActions = listOf(
            SpecWorkflowTroubleshootingAction.OpenSettings(label = "Open settings"),
            SpecWorkflowTroubleshootingAction.OpenBundledDemo(label = "Open bundled demo"),
        )
        var executionRequest: SpecWorkflowTaskExecutionRequest? = null
    }

    private data class SessionLookupCall(
        val workflowId: String,
        val taskId: String,
        val preferredSessionId: String?,
    )

    private data class TroubleshootingRequest(
        val workflowId: String,
        val trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    )

    private data class FailureStatusPresentation(
        val text: String,
        val actions: List<SpecWorkflowTroubleshootingAction>,
    )
}
