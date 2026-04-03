package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDeltaBaselineRef
import com.eacape.speccodingplugin.spec.SpecSnapshotTrigger
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.eacape.speccodingplugin.spec.SpecWorkflowSnapshotEntry
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkflowVerifyDeltaCoordinatorTest {

    @Test
    fun `runVerification should normalize workflow id then reload and show completion summary`() {
        val recorder = RecordingEnvironment()
        val coordinator = coordinator(recorder)

        coordinator.runVerification(" wf-1 ")

        assertEquals(listOf("wf-1"), recorder.runVerificationCalls)
        assertEquals(1, recorder.reloadCount)
        assertEquals(listOf("verification finished"), recorder.statusTexts)
    }

    @Test
    fun `openVerificationDocument should report unavailable when file does not exist`() {
        val recorder = RecordingEnvironment().apply {
            locateArtifactPath = Path.of("build", "missing-verification.md")
        }
        val coordinator = coordinator(recorder)

        coordinator.openVerificationDocument("wf-2")

        assertEquals(listOf(LocateArtifactCall("wf-2", StageId.VERIFY)), recorder.locateArtifactCalls)
        assertTrue(recorder.openedPaths.isEmpty())
        assertEquals(
            listOf(SpecCodingBundle.message("spec.action.verify.document.unavailable.title")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `openVerificationDocument should open existing verification artifact`() {
        val tempFile = Files.createTempFile("spec-workflow-verify", ".md")
        try {
            val recorder = RecordingEnvironment().apply {
                locateArtifactPath = tempFile
            }
            val coordinator = coordinator(recorder)

            coordinator.openVerificationDocument("wf-3")

            assertEquals(listOf(tempFile), recorder.openedPaths)
            assertTrue(recorder.statusTexts.isEmpty())
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `compareBaseline should compare against workflow baseline and show delta dialog`() {
        val recorder = RecordingEnvironment().apply {
            compareByWorkflowIdResult = Result.success(delta())
        }
        val coordinator = coordinator(recorder)
        val workflow = workflow(id = "wf-4")

        coordinator.compareBaseline(
            SpecWorkflowVerifyDeltaCompareRequest(
                targetWorkflow = workflow,
                choice = SpecWorkflowReferenceBaselineChoice(
                    workflowId = "wf-base",
                    title = "Baseline",
                ),
            ),
        )

        assertEquals(listOf(CompareWorkflowCall("wf-base", "wf-4")), recorder.compareWorkflowCalls)
        assertEquals(listOf(DeltaDialogCall(workflow, delta())), recorder.deltaDialogCalls)
        assertEquals(listOf(SpecCodingBundle.message("spec.delta.generated")), recorder.statusTexts)
    }

    @Test
    fun `compareBaseline should compare against pinned baseline`() {
        val recorder = RecordingEnvironment().apply {
            compareByBaselineResult = Result.success(delta())
        }
        val coordinator = coordinator(recorder)

        coordinator.compareBaseline(
            SpecWorkflowVerifyDeltaCompareRequest(
                targetWorkflow = workflow(id = "wf-5"),
                choice = SpecWorkflowPinnedDeltaBaselineChoice(
                    baseline = SpecDeltaBaselineRef(
                        baselineId = "baseline-1",
                        workflowId = "wf-5",
                        snapshotId = "snapshot-1",
                        createdAt = 1L,
                        label = "Pinned Baseline",
                    ),
                ),
            ),
        )

        assertEquals(listOf(CompareBaselineCall("wf-5", "baseline-1")), recorder.compareBaselineCalls)
        assertEquals(listOf(SpecCodingBundle.message("spec.delta.generated")), recorder.statusTexts)
    }

    @Test
    fun `compareBaseline should report compare failure`() {
        val recorder = RecordingEnvironment().apply {
            compareByWorkflowIdResult = Result.failure(IllegalStateException("compare failed"))
        }
        val coordinator = coordinator(recorder)

        coordinator.compareBaseline(
            SpecWorkflowVerifyDeltaCompareRequest(
                targetWorkflow = workflow(id = "wf-6"),
                choice = SpecWorkflowReferenceBaselineChoice(
                    workflowId = "wf-base",
                    title = "Baseline",
                ),
            ),
        )

        assertTrue(recorder.deltaDialogCalls.isEmpty())
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.workflow.error",
                    "compare failed",
                ),
            ),
            recorder.statusTexts,
        )
    }

    @Test
    fun `pinBaseline should report unavailable when workflow has no snapshots`() {
        val recorder = RecordingEnvironment().apply {
            snapshots = emptyList()
        }
        val coordinator = coordinator(recorder)

        coordinator.pinBaseline("wf-7")

        assertEquals(listOf("wf-7"), recorder.listSnapshotCalls)
        assertTrue(recorder.pinBaselineCalls.isEmpty())
        assertEquals(
            listOf(SpecCodingBundle.message("spec.toolwindow.verifyDelta.pin.unavailable")),
            recorder.statusTexts,
        )
    }

    @Test
    fun `pinBaseline should pin first snapshot then reload and report saved label`() {
        val recorder = RecordingEnvironment().apply {
            snapshots = listOf(
                SpecWorkflowSnapshotEntry(
                    snapshotId = "snapshot-9",
                    workflowId = "wf-8",
                    trigger = SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER,
                    createdAt = 9L,
                ),
            )
            pinResult = Result.success(
                SpecDeltaBaselineRef(
                    baselineId = "baseline-9",
                    workflowId = "wf-8",
                    snapshotId = "snapshot-9",
                    createdAt = 9L,
                    label = "Pinned snapshot-9",
                ),
            )
        }
        val coordinator = coordinator(recorder)

        coordinator.pinBaseline(" wf-8 ")

        assertEquals(listOf("wf-8"), recorder.listSnapshotCalls)
        assertEquals(
            listOf(
                PinBaselineCall(
                    workflowId = "wf-8",
                    snapshotId = "snapshot-9",
                    label = SpecCodingBundle.message(
                        "spec.toolwindow.verifyDelta.pin.autoLabel",
                        "snapshot-9",
                    ),
                ),
            ),
            recorder.pinBaselineCalls,
        )
        assertEquals(1, recorder.reloadCount)
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.toolwindow.verifyDelta.pin.saved",
                    "Pinned snapshot-9",
                ),
            ),
            recorder.statusTexts,
        )
    }

    private fun coordinator(
        recorder: RecordingEnvironment,
    ): SpecWorkflowVerifyDeltaCoordinator {
        return SpecWorkflowVerifyDeltaCoordinator(
            runVerificationWorkflow = { workflowId, onCompleted ->
                recorder.runVerificationCalls += workflowId
                onCompleted(recorder.verificationSummary)
            },
            locateArtifact = { workflowId, stageId ->
                recorder.locateArtifactCalls += LocateArtifactCall(workflowId, stageId)
                recorder.locateArtifactPath
            },
            openFile = { path ->
                recorder.openedPaths.add(path)
                recorder.openFileResult
            },
            runIo = { action ->
                action()
            },
            invokeLater = { action ->
                action()
            },
            compareByWorkflowId = { baselineWorkflowId, targetWorkflowId ->
                recorder.compareWorkflowCalls += CompareWorkflowCall(baselineWorkflowId, targetWorkflowId)
                recorder.compareByWorkflowIdResult
            },
            compareByDeltaBaseline = { workflowId, baselineId ->
                recorder.compareBaselineCalls += CompareBaselineCall(workflowId, baselineId)
                recorder.compareByBaselineResult
            },
            listWorkflowSnapshots = { workflowId ->
                recorder.listSnapshotCalls += workflowId
                recorder.snapshots
            },
            pinDeltaBaseline = { workflowId, snapshotId, label ->
                recorder.pinBaselineCalls += PinBaselineCall(workflowId, snapshotId, label)
                recorder.pinResult
            },
            showDeltaDialog = { workflow, delta ->
                recorder.deltaDialogCalls += DeltaDialogCall(workflow, delta)
            },
            reloadCurrentWorkflow = {
                recorder.reloadCount += 1
            },
            setStatusText = { text ->
                recorder.statusTexts += text
            },
            renderFailureMessage = { error ->
                error.message ?: "unknown"
            },
        )
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
        )
    }

    private fun delta(): SpecWorkflowDelta {
        return SpecWorkflowDelta(
            baselineWorkflowId = "wf-base",
            targetWorkflowId = "wf-target",
            phaseDeltas = emptyList(),
        )
    }

    private class RecordingEnvironment {
        var verificationSummary: String = "verification finished"
        var locateArtifactPath: Path = Path.of("build", "verification.md")
        var openFileResult: Boolean = true
        var compareByWorkflowIdResult: Result<SpecWorkflowDelta> =
            Result.failure(IllegalStateException("missing compare result"))
        var compareByBaselineResult: Result<SpecWorkflowDelta> =
            Result.failure(IllegalStateException("missing baseline result"))
        var snapshots: List<SpecWorkflowSnapshotEntry> = listOf(
            SpecWorkflowSnapshotEntry(
                snapshotId = "snapshot-1",
                workflowId = "wf-1",
                trigger = SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER,
                createdAt = 1L,
            ),
        )
        var pinResult: Result<SpecDeltaBaselineRef> =
            Result.failure(IllegalStateException("missing pin result"))
        val runVerificationCalls = mutableListOf<String>()
        val locateArtifactCalls = mutableListOf<LocateArtifactCall>()
        val openedPaths = mutableListOf<Path>()
        val compareWorkflowCalls = mutableListOf<CompareWorkflowCall>()
        val compareBaselineCalls = mutableListOf<CompareBaselineCall>()
        val listSnapshotCalls = mutableListOf<String>()
        val pinBaselineCalls = mutableListOf<PinBaselineCall>()
        val deltaDialogCalls = mutableListOf<DeltaDialogCall>()
        val statusTexts = mutableListOf<String>()
        var reloadCount: Int = 0
    }

    private data class LocateArtifactCall(
        val workflowId: String,
        val stageId: StageId,
    )

    private data class CompareWorkflowCall(
        val baselineWorkflowId: String,
        val targetWorkflowId: String,
    )

    private data class CompareBaselineCall(
        val workflowId: String,
        val baselineId: String,
    )

    private data class PinBaselineCall(
        val workflowId: String,
        val snapshotId: String,
        val label: String,
    )

    private data class DeltaDialogCall(
        val workflow: SpecWorkflow,
        val delta: SpecWorkflowDelta,
    )
}
