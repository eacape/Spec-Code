package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaExportFormat
import com.eacape.speccodingplugin.spec.SpecDeltaExportResult
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowDeltaCoordinatorTest {

    @Test
    fun `show should surface missing current workflow`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.show(null)

        assertEquals(
            listOf(SpecCodingBundle.message("spec.delta.error.noCurrentWorkflow")),
            recorder.statusTexts,
        )
        assertTrue(recorder.compareCalls.isEmpty())
        assertTrue(recorder.dialogRequests.isEmpty())
    }

    @Test
    fun `show should surface empty candidates when no baseline workflows remain`() {
        val recorder = RecordingEnvironment().apply {
            workflowIds = listOf("wf-target")
        }
        val coordinator = coordinator(recorder)

        coordinator.show(workflow(id = "wf-target"))

        assertEquals(
            listOf(SpecCodingBundle.message("spec.delta.emptyCandidates")),
            recorder.statusTexts,
        )
        assertTrue(recorder.selectionRequests.isEmpty())
        assertTrue(recorder.compareCalls.isEmpty())
    }

    @Test
    fun `show should require baseline selection when dialog confirms without workflow id`() {
        val recorder = RecordingEnvironment().apply {
            workflowIds = listOf("wf-target", "wf-base")
            loadedWorkflows["wf-base"] = workflow(id = "wf-base", title = "Baseline")
            selectionResult = SpecWorkflowDeltaBaselineSelectionResult(
                confirmed = true,
                baselineWorkflowId = "   ",
            )
        }
        val coordinator = coordinator(recorder)

        coordinator.show(workflow(id = "wf-target", title = "Target"))

        assertEquals(listOf("wf-target:wf-base"), recorder.selectionRequests)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.delta.selectBaseline.required")),
            recorder.statusTexts,
        )
        assertTrue(recorder.compareCalls.isEmpty())
    }

    @Test
    fun `show should compare selected baseline and open delta dialog`() {
        val recorder = RecordingEnvironment().apply {
            workflowIds = listOf("wf-target", "wf-base")
            loadedWorkflows["wf-base"] = workflow(id = "wf-base", title = "Baseline")
            selectionResult = SpecWorkflowDeltaBaselineSelectionResult(
                confirmed = true,
                baselineWorkflowId = " wf-base ",
            )
            compareResult = Result.success(delta("wf-base", "wf-target"))
        }
        val coordinator = coordinator(recorder)
        val targetWorkflow = workflow(id = "wf-target", title = "Target")

        coordinator.show(targetWorkflow)

        assertEquals(listOf("wf-base:wf-target"), recorder.compareCalls)
        assertEquals(1, recorder.dialogRequests.size)
        assertEquals(targetWorkflow.id, recorder.dialogRequests.single().targetWorkflow.id)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.delta.generated")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `show should render compare failure without opening dialog`() {
        val recorder = RecordingEnvironment().apply {
            workflowIds = listOf("wf-target", "wf-base")
            loadedWorkflows["wf-base"] = workflow(id = "wf-base", title = "Baseline")
            selectionResult = SpecWorkflowDeltaBaselineSelectionResult(
                confirmed = true,
                baselineWorkflowId = "wf-base",
            )
            compareResult = Result.failure(IllegalStateException("compare failed"))
        }
        val coordinator = coordinator(recorder)

        coordinator.show(workflow(id = "wf-target"))

        assertTrue(recorder.dialogRequests.isEmpty())
        assertEquals(
            listOf(SpecCodingBundle.message("spec.workflow.error", "compare failed")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `showComparisonResult should delegate history diff and export callbacks`() {
        val exportResult = SpecDeltaExportResult(
            workflowId = "wf-target",
            format = SpecDeltaExportFormat.MARKDOWN,
            fileName = "delta.md",
            filePath = Path.of("exports", "delta.md"),
        )
        val recorder = RecordingEnvironment().apply {
            exportResultByFormat[SpecDeltaExportFormat.MARKDOWN] = Result.success(exportResult)
        }
        val coordinator = coordinator(recorder)
        val targetWorkflow = workflow(
            id = "wf-target",
            phase = SpecPhase.DESIGN,
            documentContent = "design content",
        )
        val delta = delta("wf-base", "wf-target")

        coordinator.showComparisonResult(targetWorkflow, delta)

        val request = recorder.dialogRequests.single()
        request.onOpenHistoryDiff(SpecPhase.DESIGN)
        assertEquals(
            listOf("wf-target:DESIGN:design content"),
            recorder.historyDiffCalls,
        )

        assertEquals(Result.success(exportResult), request.onExportReport(SpecDeltaExportFormat.MARKDOWN))
        request.onReportExported(exportResult)
        assertEquals(
            listOf(SpecCodingBundle.message("spec.delta.export.done", "delta.md")),
            recorder.statusTexts,
        )
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowDeltaCoordinator {
        return SpecWorkflowDeltaCoordinator(
            listWorkflowIds = { recorder.workflowIds },
            loadWorkflow = { workflowId ->
                recorder.loadedWorkflows[workflowId]
                    ?.let { workflow -> Result.success(workflow) }
                    ?: Result.failure(IllegalArgumentException("missing workflow $workflowId"))
            },
            compareByWorkflowId = { baselineWorkflowId, targetWorkflowId ->
                recorder.compareCalls += "$baselineWorkflowId:$targetWorkflowId"
                recorder.compareResult
            },
            runIo = { task -> task() },
            invokeLater = { action -> action() },
            selectBaselineWorkflow = { currentWorkflowId, workflowOptions ->
                recorder.selectionRequests += currentWorkflowId + ":" +
                    workflowOptions.joinToString(",") { option -> option.workflowId }
                recorder.selectionResult
            },
            showDeltaDialog = { request ->
                recorder.dialogRequests += request
            },
            showHistoryDiff = { workflowId, phase, currentDocument ->
                recorder.historyDiffCalls += "$workflowId:${phase.name}:${currentDocument?.content.orEmpty()}"
            },
            exportReport = { _, format ->
                recorder.exportResultByFormat[format]
                    ?: Result.failure(IllegalStateException("missing export result for $format"))
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            renderFailureMessage = { error ->
                error.message ?: SpecCodingBundle.message("common.unknown")
            },
        )
    }

    private fun workflow(
        id: String,
        title: String = id,
        phase: SpecPhase = SpecPhase.IMPLEMENT,
        documentContent: String = "$id ${phase.name.lowercase()} content",
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            title = title,
            description = "$title description",
            currentPhase = phase,
            documents = mapOf(
                phase to document(phase, documentContent),
            ),
            status = WorkflowStatus.IN_PROGRESS,
        )
    }

    private fun document(
        phase: SpecPhase,
        content: String,
    ): SpecDocument {
        return SpecDocument(
            id = "${phase.name.lowercase()}-$content",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.outputFileName,
                description = content,
            ),
        )
    }

    private fun delta(
        baselineWorkflowId: String,
        targetWorkflowId: String,
    ): SpecWorkflowDelta {
        return SpecWorkflowDelta(
            baselineWorkflowId = baselineWorkflowId,
            targetWorkflowId = targetWorkflowId,
            phaseDeltas = emptyList(),
        )
    }

    private class RecordingEnvironment {
        var workflowIds: List<String> = emptyList()
        val loadedWorkflows = linkedMapOf<String, SpecWorkflow>()
        var selectionResult = SpecWorkflowDeltaBaselineSelectionResult(confirmed = false)
        var compareResult: Result<SpecWorkflowDelta> = Result.failure(IllegalStateException("missing compare result"))
        val exportResultByFormat =
            linkedMapOf<SpecDeltaExportFormat, Result<SpecDeltaExportResult>>()
        val selectionRequests = mutableListOf<String>()
        val compareCalls = mutableListOf<String>()
        val dialogRequests = mutableListOf<SpecWorkflowDeltaDialogRequest>()
        val historyDiffCalls = mutableListOf<String>()
        val statusTexts = mutableListOf<String>()
    }
}
