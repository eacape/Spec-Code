package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowTemplate

internal data class SpecWorkflowCreateRequest(
    val title: String,
    val description: String,
    val template: WorkflowTemplate,
    val verifyEnabled: Boolean?,
    val changeIntent: SpecChangeIntent,
    val baselineWorkflowId: String?,
)

internal data class SpecWorkflowCreateOutcome(
    val workflow: SpecWorkflow,
    val expectedFirstVisibleArtifactFileName: String,
    val firstVisibleArtifactMaterialized: Boolean,
)

internal class SpecWorkflowCreateCoordinator(
    private val createWorkflow: (SpecWorkflowCreateRequest) -> Result<SpecWorkflow>,
    private val recordCreateAttempt: (WorkflowTemplate, Long) -> Unit,
    private val recordCreateSuccess: (String, WorkflowTemplate, Long) -> Unit,
    private val firstVisibleArtifactExists: (workflowId: String, artifactFileName: String) -> Boolean,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {

    fun create(request: SpecWorkflowCreateRequest): Result<SpecWorkflowCreateOutcome> {
        recordCreateAttempt(request.template, currentTimeMillis())
        return createWorkflow(request).map { workflow ->
            val expectedArtifactFileName = SpecWorkflowFirstRunTrackingStore.firstVisibleArtifact(workflow.template)
            val firstVisibleArtifactMaterialized = firstVisibleArtifactExists(workflow.id, expectedArtifactFileName)
            if (firstVisibleArtifactMaterialized) {
                recordCreateSuccess(workflow.id, workflow.template, currentTimeMillis())
            }
            SpecWorkflowCreateOutcome(
                workflow = workflow,
                expectedFirstVisibleArtifactFileName = expectedArtifactFileName,
                firstVisibleArtifactMaterialized = firstVisibleArtifactMaterialized,
            )
        }
    }
}
