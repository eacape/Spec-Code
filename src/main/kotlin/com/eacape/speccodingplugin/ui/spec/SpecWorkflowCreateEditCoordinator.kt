package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowTemplate

internal data class SpecWorkflowCreateEditBackgroundRequest<T>(
    val task: () -> T,
    val onSuccess: (T) -> Unit,
    val onFailure: (Throwable) -> Unit = {},
)

internal interface SpecWorkflowCreateEditBackgroundRunner {
    fun <T> run(request: SpecWorkflowCreateEditBackgroundRequest<T>)
}

internal data class SpecWorkflowCreateDialogRequest(
    val workflowOptions: List<NewSpecWorkflowDialog.WorkflowOption>,
    val defaultTemplate: WorkflowTemplate,
)

internal data class SpecWorkflowEditDialogRequest(
    val initialTitle: String,
    val initialDescription: String,
)

internal data class SpecWorkflowEditDialogResult(
    val title: String,
    val description: String,
)

internal interface SpecWorkflowCreateEditUi {
    fun resolveCreateDialogDefaultTemplate(preferredTemplate: WorkflowTemplate?): WorkflowTemplate

    fun showCreateDialog(request: SpecWorkflowCreateDialogRequest): SpecWorkflowCreateRequest?

    fun showEditDialog(request: SpecWorkflowEditDialogRequest): SpecWorkflowEditDialogResult?

    fun onCreateSuccess(outcome: SpecWorkflowCreateOutcome)

    fun onEditSuccess(workflowId: String, updated: SpecWorkflow)

    fun showFailure(error: Throwable, logMessage: String)

    fun showUnknownWorkflowFailure()
}

internal class SpecWorkflowCreateEditCoordinator(
    private val backgroundRunner: SpecWorkflowCreateEditBackgroundRunner,
    private val ui: SpecWorkflowCreateEditUi,
    private val createWorkflow: (SpecWorkflowCreateRequest) -> Result<SpecWorkflowCreateOutcome>,
    private val loadWorkflowForEdit: (String) -> SpecWorkflow?,
    private val updateWorkflowMetadata: (workflowId: String, title: String, description: String) -> Result<SpecWorkflow>,
) {

    fun requestCreate(
        preferredTemplate: WorkflowTemplate? = null,
        workflowOptions: List<NewSpecWorkflowDialog.WorkflowOption> = emptyList(),
    ) {
        backgroundRunner.run(
            SpecWorkflowCreateEditBackgroundRequest(
                task = {
                    ui.resolveCreateDialogDefaultTemplate(preferredTemplate)
                },
                onSuccess = createDialogReady@ { defaultTemplate ->
                    val request = ui.showCreateDialog(
                        SpecWorkflowCreateDialogRequest(
                            workflowOptions = workflowOptions,
                            defaultTemplate = defaultTemplate,
                        ),
                    ) ?: return@createDialogReady

                    backgroundRunner.run(
                        SpecWorkflowCreateEditBackgroundRequest(
                            task = {
                                createWorkflow(request).getOrThrow()
                            },
                            onSuccess = ui::onCreateSuccess,
                            onFailure = { error ->
                                ui.showFailure(
                                    error = error,
                                    logMessage = "Failed to create workflow",
                                )
                            },
                        ),
                    )
                },
            ),
        )
    }

    fun requestEdit(workflowId: String) {
        val normalizedWorkflowId = workflowId.trim().takeIf { it.isNotEmpty() } ?: return
        backgroundRunner.run(
            SpecWorkflowCreateEditBackgroundRequest(
                task = {
                    loadWorkflowForEdit(normalizedWorkflowId)
                },
                onSuccess = workflowLoaded@ { workflow ->
                    if (workflow == null) {
                        ui.showUnknownWorkflowFailure()
                        return@workflowLoaded
                    }

                    val request = ui.showEditDialog(
                        SpecWorkflowEditDialogRequest(
                            initialTitle = workflow.title.ifBlank { workflow.id },
                            initialDescription = workflow.description,
                        ),
                    ) ?: return@workflowLoaded

                    backgroundRunner.run(
                        SpecWorkflowCreateEditBackgroundRequest(
                            task = {
                                updateWorkflowMetadata(
                                    normalizedWorkflowId,
                                    request.title,
                                    request.description,
                                ).getOrThrow()
                            },
                            onSuccess = { updated ->
                                ui.onEditSuccess(normalizedWorkflowId, updated)
                            },
                            onFailure = { error ->
                                ui.showFailure(
                                    error = error,
                                    logMessage = "Failed to update workflow metadata: $normalizedWorkflowId",
                                )
                            },
                        ),
                    )
                },
                onFailure = {
                    ui.showUnknownWorkflowFailure()
                },
            ),
        )
    }
}
