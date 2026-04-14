package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowCreateEditUiHostTest {

    @Test
    fun `resolveCreateDialogDefaultTemplate should log and fall back when configured default cannot be loaded`() {
        val recorder = RecordingEnvironment().apply {
            configuredDefaultFailure = IllegalStateException("load failed")
        }

        val resolved = host(recorder).resolveCreateDialogDefaultTemplate(WorkflowTemplate.FULL_SPEC)

        assertEquals(WorkflowTemplate.FULL_SPEC, resolved)
        assertEquals(
            listOf("Failed to load default workflow template for create dialog:load failed"),
            recorder.loggedFailures,
        )
    }

    @Test
    fun `showCreateDialog and showEditDialog should delegate to configured dialog adapters`() {
        val recorder = RecordingEnvironment().apply {
            createDialogResult = createRequest(template = WorkflowTemplate.DIRECT_IMPLEMENT)
            editDialogResult = SpecWorkflowEditDialogResult(
                title = "Updated title",
                description = "Updated description",
            )
        }

        val createResult = host(recorder).showCreateDialog(
            SpecWorkflowCreateDialogRequest(
                workflowOptions = recorder.workflowOptions,
                defaultTemplate = WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
        )
        val editResult = host(recorder).showEditDialog(
            SpecWorkflowEditDialogRequest(
                initialTitle = "Original title",
                initialDescription = "Original description",
            ),
        )

        assertEquals(listOf("showCreate:DIRECT_IMPLEMENT:wf-1,wf-2", "showEdit:Original title:Original description"), recorder.events)
        assertEquals(createRequest(template = WorkflowTemplate.DIRECT_IMPLEMENT), createResult)
        assertEquals(
            SpecWorkflowEditDialogResult(
                title = "Updated title",
                description = "Updated description",
            ),
            editResult,
        )
    }

    @Test
    fun `onCreateSuccess should highlight refresh publish and report missing first artifact`() {
        val recorder = RecordingEnvironment()

        host(recorder).onCreateSuccess(
            SpecWorkflowCreateOutcome(
                workflow = workflow(id = "wf-created", template = WorkflowTemplate.QUICK_TASK),
                expectedFirstVisibleArtifactFileName = "tasks.md",
                firstVisibleArtifactMaterialized = false,
            ),
        )

        assertEquals(
            listOf("highlight:wf-created", "refresh:wf-created", "publish:wf-created"),
            recorder.events,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.workflow.create.firstArtifactMissing",
                    "tasks.md",
                    "wf-created",
                ),
            ),
            recorder.statusTexts,
        )
    }

    @Test
    fun `onEditSuccess should clear status update opened workflow and refresh selected workflow when reopened`() {
        val recorder = RecordingEnvironment().apply {
            openedWorkflowIds += "wf-edit"
        }

        host(recorder).onEditSuccess(
            workflowId = "wf-edit",
            updated = workflow(id = "wf-edit", title = "Updated title"),
        )

        assertEquals(listOf(null), recorder.statusTexts)
        assertEquals(
            listOf("highlight:wf-edit", "applyUpdated:wf-edit:Updated title", "refresh:wf-edit"),
            recorder.events,
        )
    }

    @Test
    fun `onEditSuccess should avoid reopened workflow application when edited workflow is not open`() {
        val recorder = RecordingEnvironment()

        host(recorder).onEditSuccess(
            workflowId = "wf-edit",
            updated = workflow(id = "wf-edit", title = "Updated title"),
        )

        assertEquals(listOf(null), recorder.statusTexts)
        assertEquals(listOf("highlight:wf-edit", "refresh:null"), recorder.events)
        assertTrue(recorder.updatedWorkflows.isEmpty())
    }

    @Test
    fun `showFailure and showUnknownWorkflowFailure should render status messages`() {
        val recorder = RecordingEnvironment()
        val uiHost = host(recorder)

        uiHost.showFailure(
            error = IllegalStateException("create failed"),
            logMessage = "Failed to create workflow",
        )
        uiHost.showUnknownWorkflowFailure()

        assertEquals(
            listOf("Failed to create workflow:create failed"),
            recorder.loggedFailures,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:create failed"),
                SpecCodingBundle.message(
                    "spec.workflow.error",
                    SpecCodingBundle.message("common.unknown"),
                ),
            ),
            recorder.statusTexts,
        )
    }

    private fun host(recorder: RecordingEnvironment): SpecWorkflowCreateEditUiHost {
        return SpecWorkflowCreateEditUiHost(
            resolveConfiguredDefaultTemplate = {
                recorder.configuredDefaultFailure?.let { throw it }
                recorder.configuredDefaultTemplate
            },
            showCreateDialogUi = { request ->
                recorder.events += "showCreate:${request.defaultTemplate.name}:${request.workflowOptions.joinToString(",") { it.workflowId }}"
                recorder.createDialogRequests += request
                recorder.createDialogResult
            },
            showEditDialogUi = { request ->
                recorder.events += "showEdit:${request.initialTitle}:${request.initialDescription}"
                recorder.editDialogRequests += request
                recorder.editDialogResult
            },
            highlightWorkflow = { workflowId ->
                recorder.events += "highlight:$workflowId"
            },
            refreshWorkflows = { workflowId ->
                recorder.events += "refresh:${workflowId ?: "null"}"
            },
            publishWorkflowSelection = { workflowId ->
                recorder.events += "publish:$workflowId"
            },
            isWorkflowOpened = { workflowId ->
                recorder.openedWorkflowIds.contains(workflowId)
            },
            applyUpdatedWorkflowToOpenedUi = { updated ->
                recorder.events += "applyUpdated:${updated.id}:${updated.title}"
                recorder.updatedWorkflows += updated
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            renderFailureMessage = { error ->
                "rendered:${error.message}"
            },
            logFailure = { message, error ->
                recorder.loggedFailures += "$message:${error.message}"
            },
        )
    }

    private fun createRequest(template: WorkflowTemplate): SpecWorkflowCreateRequest {
        return SpecWorkflowCreateRequest(
            title = "Create ${template.name}",
            description = "workflow create request",
            template = template,
            verifyEnabled = false,
            changeIntent = SpecChangeIntent.FULL,
            baselineWorkflowId = null,
        )
    }

    private fun workflow(
        id: String,
        title: String = "Workflow $id",
        description: String = "workflow",
        template: WorkflowTemplate = WorkflowTemplate.FULL_SPEC,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = title,
            description = description,
            template = template,
            currentStage = StageId.REQUIREMENTS,
        )
    }

    private class RecordingEnvironment {
        val workflowOptions = listOf(
            NewSpecWorkflowDialog.WorkflowOption(workflowId = "wf-1", title = "Workflow 1"),
            NewSpecWorkflowDialog.WorkflowOption(workflowId = "wf-2", title = "Workflow 2"),
        )
        val events = mutableListOf<String>()
        val createDialogRequests = mutableListOf<SpecWorkflowCreateDialogRequest>()
        val editDialogRequests = mutableListOf<SpecWorkflowEditDialogRequest>()
        val updatedWorkflows = mutableListOf<SpecWorkflow>()
        val statusTexts = mutableListOf<String?>()
        val loggedFailures = mutableListOf<String>()
        val openedWorkflowIds = mutableSetOf<String>()
        var configuredDefaultTemplate: WorkflowTemplate? = WorkflowTemplate.QUICK_TASK
        var configuredDefaultFailure: Throwable? = null
        var createDialogResult: SpecWorkflowCreateRequest? = null
        var editDialogResult: SpecWorkflowEditDialogResult? = null
    }
}
