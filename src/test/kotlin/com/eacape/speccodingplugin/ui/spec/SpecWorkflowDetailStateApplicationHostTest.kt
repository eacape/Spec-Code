package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowSourceImportConstraints
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpecWorkflowDetailStateApplicationHostTest {

    @Test
    fun `apply auto code context should degrade failure into fallback pack`() {
        val ui = Recorder()
        val host = host(ui) { error ->
            "rendered ${error.message}"
        }
        val workflow = workflow("wf-auto-context")

        host.applyAutoCodeContext(
            workflow = workflow,
            codeContextResult = Result.failure(IllegalStateException("boom")),
        )

        assertEquals(workflow.id, ui.autoCodeContextWorkflowIds.single())
        val fallbackPack = ui.autoCodeContextPacks.single()
        assertNotNull(fallbackPack)
        assertEquals(workflow.currentPhase, fallbackPack?.phase)
        assertEquals(
            listOf("Automatic code context collection failed: rendered boom"),
            fallbackPack?.degradationReasons,
        )
    }

    @Test
    fun `apply workflow sources should preserve remembered selection and append preferred ids`() {
        val ui = Recorder()
        val host = host(ui)
        val workflow = workflow("wf-sources")
        val source1 = source("source-1")
        val source2 = source("source-2")
        val source3 = source("source-3")

        host.applyWorkflowSourcesPresentation(
            workflowId = workflow.id,
            presentation = SpecWorkflowComposerSourcePresentation(
                assets = listOf(source1, source2),
                selectedSourceIds = linkedSetOf(source2.sourceId),
                editable = true,
            ),
        )

        host.applyWorkflowSources(
            workflow = workflow,
            assets = listOf(source3, source1, source2),
            preserveSelection = true,
            preferredSourceIds = setOf(source3.sourceId),
        )

        val latestUpdate = ui.workflowSourceUpdates.last()
        assertEquals(workflow.id, latestUpdate.workflowId)
        assertEquals(listOf(source1, source2, source3), latestUpdate.assets)
        assertEquals(linkedSetOf(source2.sourceId, source3.sourceId), latestUpdate.selectedSourceIds)
        assertEquals(linkedSetOf(source2.sourceId, source3.sourceId), host.selectedSourceIds(workflow.id))
    }

    @Test
    fun `clear current workflow sources should reset source usage against latest state`() {
        val ui = Recorder()
        val host = host(ui)
        val workflow = workflow("wf-usage")
        val source1 = source("source-1")
        val source2 = source("source-2")

        host.applyWorkflowSourcesPresentation(
            workflowId = workflow.id,
            presentation = SpecWorkflowComposerSourcePresentation(
                assets = listOf(source1, source2),
                selectedSourceIds = linkedSetOf(source2.sourceId),
                editable = true,
            ),
        )

        assertEquals(listOf(source1, source2), host.currentWorkflowSources())
        assertEquals(listOf(source2.sourceId), host.resolveSourceUsage(workflow.id).selectedSourceIds)

        host.clearCurrentWorkflowSources()

        assertEquals(emptyList<WorkflowSourceAsset>(), host.currentWorkflowSources())
        assertEquals(emptyList<String>(), host.resolveSourceUsage(workflow.id).selectedSourceIds)
    }

    private fun host(
        ui: Recorder,
        renderFailureMessage: (Throwable) -> String = { error ->
            error.message ?: "unknown"
        },
    ): SpecWorkflowDetailStateApplicationHost {
        return SpecWorkflowDetailStateApplicationHost(
            detailUi = ui,
            composerSourceCoordinator = SpecWorkflowComposerSourceCoordinator(
                sourceImportConstraints = WorkflowSourceImportConstraints(),
                runBackground = { error("runBackground should not be called in this test") },
                importWorkflowSource = { _, _, _, _ ->
                    error("importWorkflowSource should not be called in this test")
                },
                renderFailureMessage = renderFailureMessage,
            ),
            renderFailureMessage = renderFailureMessage,
        )
    }

    private fun workflow(workflowId: String): SpecWorkflow {
        return SpecWorkflow(
            id = workflowId,
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to SpecDocument(
                    id = "design",
                    phase = SpecPhase.DESIGN,
                    content = "design",
                    metadata = SpecMetadata(title = "Design", description = "detail host test"),
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Workflow $workflowId",
            description = "detail host test",
            template = WorkflowTemplate.FULL_SPEC,
            currentStage = StageId.DESIGN,
            createdAt = 1L,
            updatedAt = 2L,
            changeIntent = SpecChangeIntent.FULL,
        )
    }

    private fun source(sourceId: String): WorkflowSourceAsset {
        return WorkflowSourceAsset(
            sourceId = sourceId,
            originalFileName = "$sourceId.md",
            storedRelativePath = "sources/$sourceId.md",
            mediaType = "text/markdown",
            fileSize = 128L,
            contentHash = "hash-$sourceId",
            importedAt = "2026-04-14T00:00:00Z",
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "test",
        )
    }

    private class Recorder : SpecWorkflowDetailStateApplicationUi {
        val autoCodeContextWorkflowIds = mutableListOf<String?>()
        val autoCodeContextPacks = mutableListOf<CodeContextPack?>()
        val workflowSourceUpdates = mutableListOf<WorkflowSourceUpdate>()

        override fun updateAutoCodeContext(
            workflowId: String?,
            codeContextPack: CodeContextPack?,
        ) {
            autoCodeContextWorkflowIds += workflowId
            autoCodeContextPacks += codeContextPack
        }

        override fun updateWorkflowSources(
            workflowId: String?,
            assets: List<WorkflowSourceAsset>,
            selectedSourceIds: Set<String>,
            editable: Boolean,
        ) {
            workflowSourceUpdates += WorkflowSourceUpdate(
                workflowId = workflowId,
                assets = assets,
                selectedSourceIds = selectedSourceIds.toCollection(linkedSetOf()),
                editable = editable,
            )
        }
    }

    private data class WorkflowSourceUpdate(
        val workflowId: String?,
        val assets: List<WorkflowSourceAsset>,
        val selectedSourceIds: LinkedHashSet<String>,
        val editable: Boolean,
    )
}
