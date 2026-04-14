package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecProjectConfigService
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.intellij.openapi.project.Project

internal class SpecWorkflowCreateEditUiHost(
    private val resolveConfiguredDefaultTemplate: () -> WorkflowTemplate?,
    private val showCreateDialogUi: (SpecWorkflowCreateDialogRequest) -> SpecWorkflowCreateRequest?,
    private val showEditDialogUi: (SpecWorkflowEditDialogRequest) -> SpecWorkflowEditDialogResult?,
    private val highlightWorkflow: (String) -> Unit,
    private val refreshWorkflows: (String?) -> Unit,
    private val publishWorkflowSelection: (String) -> Unit,
    private val isWorkflowOpened: (String) -> Boolean,
    private val applyUpdatedWorkflowToOpenedUi: (SpecWorkflow) -> Unit,
    private val setStatusText: (String?) -> Unit,
    private val renderFailureMessage: (Throwable) -> String,
    private val logFailure: (message: String, error: Throwable) -> Unit = { _, _ -> },
) : SpecWorkflowCreateEditUi {

    constructor(
        project: Project,
        highlightWorkflow: (String) -> Unit,
        refreshWorkflows: (String?) -> Unit,
        publishWorkflowSelection: (String) -> Unit,
        isWorkflowOpened: (String) -> Boolean,
        applyUpdatedWorkflowToOpenedUi: (SpecWorkflow) -> Unit,
        setStatusText: (String?) -> Unit,
        renderFailureMessage: (Throwable) -> String,
        logFailure: (message: String, error: Throwable) -> Unit = { _, _ -> },
    ) : this(
        resolveConfiguredDefaultTemplate = {
            SpecProjectConfigService(project).load().defaultTemplate
        },
        showCreateDialogUi = { request ->
            val dialog = NewSpecWorkflowDialog(
                project = project,
                workflowOptions = request.workflowOptions,
                defaultTemplate = request.defaultTemplate,
            )
            if (!dialog.showAndGet()) {
                null
            } else {
                SpecWorkflowCreateRequest(
                    title = dialog.resultTitle.orEmpty(),
                    description = dialog.resultDescription.orEmpty(),
                    template = dialog.resultTemplate,
                    verifyEnabled = dialog.resultVerifyEnabled,
                    changeIntent = dialog.resultChangeIntent,
                    baselineWorkflowId = dialog.resultBaselineWorkflowId,
                )
            }
        },
        showEditDialogUi = { request ->
            val dialog = EditSpecWorkflowDialog(
                initialTitle = request.initialTitle,
                initialDescription = request.initialDescription,
            )
            if (!dialog.showAndGet()) {
                null
            } else {
                SpecWorkflowEditDialogResult(
                    title = dialog.resultTitle.orEmpty(),
                    description = dialog.resultDescription.orEmpty(),
                )
            }
        },
        highlightWorkflow = highlightWorkflow,
        refreshWorkflows = refreshWorkflows,
        publishWorkflowSelection = publishWorkflowSelection,
        isWorkflowOpened = isWorkflowOpened,
        applyUpdatedWorkflowToOpenedUi = applyUpdatedWorkflowToOpenedUi,
        setStatusText = setStatusText,
        renderFailureMessage = renderFailureMessage,
        logFailure = logFailure,
    )

    override fun resolveCreateDialogDefaultTemplate(preferredTemplate: WorkflowTemplate?): WorkflowTemplate {
        val configuredDefaultTemplate = runCatching {
            resolveConfiguredDefaultTemplate()
        }.getOrElse { error ->
            logFailure("Failed to load default workflow template for create dialog", error)
            null
        }
        return SpecWorkflowEntryPaths.resolveDefaultTemplate(
            preferredTemplate = preferredTemplate,
            configuredDefault = configuredDefaultTemplate,
        )
    }

    override fun showCreateDialog(request: SpecWorkflowCreateDialogRequest): SpecWorkflowCreateRequest? {
        return showCreateDialogUi.invoke(request)
    }

    override fun showEditDialog(request: SpecWorkflowEditDialogRequest): SpecWorkflowEditDialogResult? {
        return showEditDialogUi.invoke(request)
    }

    override fun onCreateSuccess(outcome: SpecWorkflowCreateOutcome) {
        val workflowId = outcome.workflow.id
        highlightWorkflow(workflowId)
        refreshWorkflows(workflowId)
        publishWorkflowSelection(workflowId)
        if (!outcome.firstVisibleArtifactMaterialized) {
            setStatusText(
                SpecCodingBundle.message(
                    "spec.workflow.create.firstArtifactMissing",
                    outcome.expectedFirstVisibleArtifactFileName,
                    workflowId,
                ),
            )
        }
    }

    override fun onEditSuccess(workflowId: String, updated: SpecWorkflow) {
        val reopenWorkspace = isWorkflowOpened(workflowId)
        setStatusText(null)
        highlightWorkflow(workflowId)
        if (reopenWorkspace) {
            applyUpdatedWorkflowToOpenedUi(updated)
        }
        refreshWorkflows(workflowId.takeIf { reopenWorkspace })
    }

    override fun showFailure(
        error: Throwable,
        logMessage: String,
    ) {
        logFailure(logMessage, error)
        setStatusText(
            SpecCodingBundle.message(
                "spec.workflow.error",
                renderFailureMessage(error),
            ),
        )
    }

    override fun showUnknownWorkflowFailure() {
        setStatusText(
            SpecCodingBundle.message(
                "spec.workflow.error",
                SpecCodingBundle.message("common.unknown"),
            ),
        )
    }
}
