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

class SpecWorkflowCreateEditCoordinatorTest {

    @Test
    fun `requestCreate should resolve default template and create workflow after dialog confirmation`() {
        val createdOutcome = SpecWorkflowCreateOutcome(
            workflow = workflow(id = "wf-created", template = WorkflowTemplate.DIRECT_IMPLEMENT),
            expectedFirstVisibleArtifactFileName = "tasks.md",
            firstVisibleArtifactMaterialized = true,
        )
        val recorder = RecordingEnvironment().apply {
            defaultTemplate = WorkflowTemplate.DIRECT_IMPLEMENT
            createDialogResult = createRequest(template = WorkflowTemplate.DIRECT_IMPLEMENT)
            createResult = Result.success(createdOutcome)
        }

        coordinator(recorder).requestCreate(
            preferredTemplate = WorkflowTemplate.QUICK_TASK,
            workflowOptions = recorder.workflowOptions,
        )

        assertEquals(
            listOf(
                "resolveDefault:QUICK_TASK",
                "showCreate:DIRECT_IMPLEMENT:wf-1,wf-2",
                "create:DIRECT_IMPLEMENT:Create DIRECT_IMPLEMENT",
                "created:wf-created",
            ),
            recorder.events,
        )
        assertEquals(
            listOf(
                SpecWorkflowCreateDialogRequest(
                    workflowOptions = recorder.workflowOptions,
                    defaultTemplate = WorkflowTemplate.DIRECT_IMPLEMENT,
                ),
            ),
            recorder.createDialogRequests,
        )
        assertEquals(listOf(createRequest(template = WorkflowTemplate.DIRECT_IMPLEMENT)), recorder.createRequests)
        assertEquals(
            listOf(createdOutcome),
            recorder.createSuccesses,
        )
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `requestCreate should stop when dialog is cancelled`() {
        val recorder = RecordingEnvironment()

        coordinator(recorder).requestCreate(
            preferredTemplate = null,
            workflowOptions = recorder.workflowOptions,
        )

        assertEquals(
            listOf(
                "resolveDefault:null",
                "showCreate:FULL_SPEC:wf-1,wf-2",
            ),
            recorder.events,
        )
        assertEquals(emptyList<SpecWorkflowCreateRequest>(), recorder.createRequests)
        assertEquals(emptyList<SpecWorkflowCreateOutcome>(), recorder.createSuccesses)
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `requestCreate should surface rendered failure when create fails`() {
        val recorder = RecordingEnvironment().apply {
            createDialogResult = createRequest(template = WorkflowTemplate.FULL_SPEC)
            createResult = Result.failure(IllegalStateException("create failed"))
        }

        coordinator(recorder).requestCreate(
            preferredTemplate = WorkflowTemplate.FULL_SPEC,
            workflowOptions = recorder.workflowOptions,
        )

        assertEquals(
            listOf(
                "resolveDefault:FULL_SPEC",
                "showCreate:FULL_SPEC:wf-1,wf-2",
                "create:FULL_SPEC:Create FULL_SPEC",
            ),
            recorder.events,
        )
        assertEquals(
            listOf("Failed to create workflow:create failed"),
            recorder.loggedFailures,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:create failed"),
            ),
            recorder.statusTexts,
        )
        assertTrue(recorder.createSuccesses.isEmpty())
    }

    @Test
    fun `requestEdit should show unknown error when workflow cannot be loaded`() {
        val recorder = RecordingEnvironment().apply {
            workflowForEdit = null
        }

        coordinator(recorder).requestEdit(" wf-missing ")

        assertEquals(
            listOf("load:wf-missing"),
            recorder.events,
        )
        assertTrue(recorder.editDialogRequests.isEmpty())
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.workflow.error",
                    SpecCodingBundle.message("common.unknown"),
                ),
            ),
            recorder.statusTexts,
        )
    }

    @Test
    fun `requestEdit should update workflow metadata after dialog confirmation`() {
        val recorder = RecordingEnvironment().apply {
            workflowForEdit = workflow(id = "wf-edit", title = "Original Title", description = "Original description")
            editDialogResult = SpecWorkflowEditDialogResult(
                title = "Updated Title",
                description = "Updated description",
            )
            updateResult = Result.success(
                workflow(
                    id = "wf-edit",
                    title = "Updated Title",
                    description = "Updated description",
                ),
            )
        }

        coordinator(recorder).requestEdit("wf-edit")

        assertEquals(
            listOf(
                "load:wf-edit",
                "showEdit:Original Title:Original description",
                "update:wf-edit:Updated Title:Updated description",
                "edited:wf-edit:Updated Title",
            ),
            recorder.events,
        )
        assertEquals(
            listOf(
                SpecWorkflowEditDialogRequest(
                    initialTitle = "Original Title",
                    initialDescription = "Original description",
                ),
            ),
            recorder.editDialogRequests,
        )
        assertEquals(
            listOf(UpdateCall("wf-edit", "Updated Title", "Updated description")),
            recorder.updateCalls,
        )
        assertTrue(recorder.statusTexts.isEmpty())
    }

    @Test
    fun `requestEdit should surface rendered failure when metadata update fails`() {
        val recorder = RecordingEnvironment().apply {
            workflowForEdit = workflow(id = "wf-edit")
            editDialogResult = SpecWorkflowEditDialogResult(
                title = "Updated Title",
                description = "Updated description",
            )
            updateResult = Result.failure(IllegalStateException("update failed"))
        }

        coordinator(recorder).requestEdit("wf-edit")

        assertEquals(
            listOf(
                "load:wf-edit",
                "showEdit:Workflow wf-edit:workflow",
                "update:wf-edit:Updated Title:Updated description",
            ),
            recorder.events,
        )
        assertEquals(
            listOf("Failed to update workflow metadata: wf-edit:update failed"),
            recorder.loggedFailures,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.workflow.error", "rendered:update failed"),
            ),
            recorder.statusTexts,
        )
        assertTrue(recorder.editSuccesses.isEmpty())
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowCreateEditCoordinator {
        return SpecWorkflowCreateEditCoordinator(
            backgroundRunner = object : SpecWorkflowCreateEditBackgroundRunner {
                override fun <T> run(request: SpecWorkflowCreateEditBackgroundRequest<T>) {
                    runCatching { request.task() }
                        .onSuccess(request.onSuccess)
                        .onFailure(request.onFailure)
                }
            },
            ui = recorder,
            createWorkflow = { request ->
                recorder.events += "create:${request.template.name}:${request.title}"
                recorder.createRequests += request
                recorder.createResult
            },
            loadWorkflowForEdit = { workflowId ->
                recorder.events += "load:$workflowId"
                recorder.workflowForEdit
            },
            updateWorkflowMetadata = { workflowId, title, description ->
                recorder.events += "update:$workflowId:$title:$description"
                recorder.updateCalls += UpdateCall(workflowId, title, description)
                recorder.updateResult
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

    private class RecordingEnvironment : SpecWorkflowCreateEditUi {
        val workflowOptions = listOf(
            NewSpecWorkflowDialog.WorkflowOption(workflowId = "wf-1", title = "Workflow 1"),
            NewSpecWorkflowDialog.WorkflowOption(workflowId = "wf-2", title = "Workflow 2"),
        )
        val events = mutableListOf<String>()
        val createDialogRequests = mutableListOf<SpecWorkflowCreateDialogRequest>()
        val createRequests = mutableListOf<SpecWorkflowCreateRequest>()
        val createSuccesses = mutableListOf<SpecWorkflowCreateOutcome>()
        val editDialogRequests = mutableListOf<SpecWorkflowEditDialogRequest>()
        val updateCalls = mutableListOf<UpdateCall>()
        val editSuccesses = mutableListOf<Pair<String, SpecWorkflow>>()
        val loggedFailures = mutableListOf<String>()
        val statusTexts = mutableListOf<String?>()
        var defaultTemplate: WorkflowTemplate = WorkflowTemplate.FULL_SPEC
        var createDialogResult: SpecWorkflowCreateRequest? = null
        var createResult: Result<SpecWorkflowCreateOutcome> =
            Result.failure(IllegalStateException("missing create result"))
        var workflowForEdit: SpecWorkflow? = null
        var editDialogResult: SpecWorkflowEditDialogResult? = null
        var updateResult: Result<SpecWorkflow> = Result.failure(IllegalStateException("missing update result"))

        override fun resolveCreateDialogDefaultTemplate(preferredTemplate: WorkflowTemplate?): WorkflowTemplate {
            events += "resolveDefault:${preferredTemplate?.name ?: "null"}"
            return defaultTemplate
        }

        override fun showCreateDialog(request: SpecWorkflowCreateDialogRequest): SpecWorkflowCreateRequest? {
            events += "showCreate:${request.defaultTemplate.name}:${request.workflowOptions.joinToString(",") { it.workflowId }}"
            createDialogRequests += request
            return createDialogResult
        }

        override fun showEditDialog(request: SpecWorkflowEditDialogRequest): SpecWorkflowEditDialogResult? {
            events += "showEdit:${request.initialTitle}:${request.initialDescription}"
            editDialogRequests += request
            return editDialogResult
        }

        override fun onCreateSuccess(outcome: SpecWorkflowCreateOutcome) {
            events += "created:${outcome.workflow.id}"
            createSuccesses += outcome
        }

        override fun onEditSuccess(workflowId: String, updated: SpecWorkflow) {
            events += "edited:$workflowId:${updated.title}"
            editSuccesses += workflowId to updated
        }

        override fun showFailure(
            error: Throwable,
            logMessage: String,
        ) {
            loggedFailures += "$logMessage:${error.message}"
            statusTexts += SpecCodingBundle.message("spec.workflow.error", "rendered:${error.message}")
        }

        override fun showUnknownWorkflowFailure() {
            statusTexts += SpecCodingBundle.message(
                "spec.workflow.error",
                SpecCodingBundle.message("common.unknown"),
            )
        }
    }

    private data class UpdateCall(
        val workflowId: String,
        val title: String,
        val description: String,
    )
}
