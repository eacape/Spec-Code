package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactDraftState
import com.eacape.speccodingplugin.spec.GenerationOptions
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.toStageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowGenerationRequestResolverTest {

    @Test
    fun `resolveSelectedWorkflow should return current workflow when selection matches`() {
        val workflow = workflow(id = "wf-1", phase = SpecPhase.DESIGN)
        val harness = Harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow,
        )

        val resolved = harness.resolver.resolveSelectedWorkflow()

        assertEquals(workflow, resolved)
        assertEquals(emptyList<String>(), harness.statusTexts)
    }

    @Test
    fun `resolveSelectedWorkflow should surface unknown error when selection is stale`() {
        val harness = Harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow(id = "wf-2", phase = SpecPhase.SPECIFY),
        )

        val resolved = harness.resolver.resolveSelectedWorkflow()

        assertNull(resolved)
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.workflow.error",
                    SpecCodingBundle.message("common.unknown"),
                ),
            ),
            harness.statusTexts,
        )
    }

    @Test
    fun `resolveGenerationContext should delegate workflow source usage and normalize provider model`() {
        val workflow = workflow(id = "wf-1", phase = SpecPhase.IMPLEMENT)
        val usage = WorkflowSourceUsage(selectedSourceIds = listOf("source-1"))
        val harness = Harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow,
            providerId = " provider-1 ",
            modelId = " model-1 ",
            workflowSourceUsage = usage,
        )

        val context = harness.resolver.resolveGenerationContext()

        assertEquals(
            SpecWorkflowGenerationContext(
                workflowId = "wf-1",
                phase = SpecPhase.IMPLEMENT,
                options = workflow.resolveComposeActionMode(SpecPhase.IMPLEMENT).let { composeMode ->
                    GenerationOptions(
                        providerId = "provider-1",
                        model = "model-1",
                        workflowSourceUsage = usage,
                        composeActionMode = composeMode,
                    )
                },
            ),
            context,
        )
        assertEquals(listOf("wf-1"), harness.workflowSourceUsageRequests)
        assertEquals(emptyList<TroubleshootingStatusCall>(), harness.troubleshootingStatuses)
    }

    @Test
    fun `resolveGenerationContext should surface generation precheck troubleshooting on failure`() {
        val workflow = workflow(id = "wf-1", phase = SpecPhase.SPECIFY)
        val harness = Harness(
            selectedWorkflowId = "wf-1",
            currentWorkflow = workflow,
            providerId = null,
            modelId = null,
        )

        val context = harness.resolver.resolveGenerationContext()

        assertNull(context)
        assertEquals(
            listOf(
                TroubleshootingStatusCall(
                    workflowId = "wf-1",
                    text = SpecCodingBundle.message("spec.workflow.generation.providerRequired"),
                    trigger = SpecWorkflowRuntimeTroubleshootingTrigger.GENERATION_PRECHECK,
                ),
            ),
            harness.troubleshootingStatuses,
        )
    }

    private class Harness(
        private val selectedWorkflowId: String? = null,
        private val currentWorkflow: SpecWorkflow? = null,
        private val providerId: String? = "provider-1",
        private val modelId: String? = "model-1",
        private val workflowSourceUsage: WorkflowSourceUsage = WorkflowSourceUsage(),
    ) {
        val statusTexts = mutableListOf<String>()
        val troubleshootingStatuses = mutableListOf<TroubleshootingStatusCall>()
        val workflowSourceUsageRequests = mutableListOf<String>()

        private val generationCoordinator = SpecWorkflowGenerationCoordinator(
            providerDisplayName = { provider -> provider },
            renderFailureMessage = { _, fallback -> fallback },
        )

        val resolver = SpecWorkflowGenerationRequestResolver(
            generationCoordinator = generationCoordinator,
            selectedWorkflowId = { selectedWorkflowId },
            currentWorkflow = { currentWorkflow },
            resolveProviderId = { providerId },
            resolveModelId = { modelId },
            resolveWorkflowSourceUsage = { workflowId ->
                workflowSourceUsageRequests += workflowId
                workflowSourceUsage
            },
            setStatusText = { text ->
                statusTexts += text
            },
            setRuntimeTroubleshootingStatus = { workflowId, text, trigger ->
                troubleshootingStatuses += TroubleshootingStatusCall(
                    workflowId = workflowId,
                    text = text,
                    trigger = trigger,
                )
            },
        )
    }

    private data class TroubleshootingStatusCall(
        val workflowId: String?,
        val text: String?,
        val trigger: SpecWorkflowRuntimeTroubleshootingTrigger,
    )

    private companion object {
        fun workflow(
            id: String,
            phase: SpecPhase,
        ): SpecWorkflow {
            return SpecWorkflow(
                id = id,
                currentPhase = phase,
                documents = mapOf(
                    phase to SpecDocument(
                        id = "${phase.name.lowercase()}-1",
                        phase = phase,
                        content = "",
                        metadata = SpecMetadata(
                            title = phase.displayName,
                            description = "",
                        ),
                    ),
                ),
                status = WorkflowStatus.IN_PROGRESS,
                artifactDraftStates = mapOf(phase.toStageId() to ArtifactDraftState.UNMATERIALIZED),
            )
        }
    }
}
