package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecArtifactDelta
import com.eacape.speccodingplugin.spec.SpecDeltaArtifact
import com.eacape.speccodingplugin.spec.SpecDeltaBaselineRef
import com.eacape.speccodingplugin.spec.SpecDeltaStatus
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.SpecWorkflowDelta
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowVerifyDeltaStateBuilderTest {

    @Test
    fun `build should prefer reference baseline and surface summary`() {
        val recorder = RecordingEnvironment().apply {
            verificationHistory = listOf(historyEntry("run-1"))
            baselineWorkflowTitles["wf-base"] = "Baseline Title"
            deltaBaselines = listOf(
                SpecDeltaBaselineRef(
                    baselineId = "baseline-1",
                    workflowId = "wf-base",
                    snapshotId = "snapshot-1",
                    createdAt = 100L,
                ),
            )
            compareByWorkflowIdResult = Result.success(
                delta(
                    baselineWorkflowId = "wf-base",
                    targetWorkflowId = "wf-target",
                    artifactStatuses = listOf(
                        SpecDeltaStatus.ADDED,
                        SpecDeltaStatus.MODIFIED,
                        SpecDeltaStatus.UNCHANGED,
                    ),
                ),
            )
            hasSnapshots = true
            verificationArtifactAvailable = true
        }
        val builder = builder(recorder)

        val state = builder.build(
            workflow = workflow(
                id = "wf-target",
                baselineWorkflowId = "wf-base",
                verifyEnabled = false,
                verifyStageActive = true,
            ),
            refreshedAtMillis = 1234L,
        )

        assertTrue(state.verifyEnabled)
        assertTrue(state.verificationDocumentAvailable)
        assertEquals(listOf(historyEntry("run-1")), state.verificationHistory)
        assertEquals(
            listOf(
                SpecWorkflowReferenceBaselineChoice(
                    workflowId = "wf-base",
                    title = "Baseline Title",
                ),
                SpecWorkflowPinnedDeltaBaselineChoice(recorder.deltaBaselines.single()),
            ),
            state.baselineChoices,
        )
        assertEquals("workflow:wf-base", state.preferredBaselineChoiceId)
        assertEquals(
            SpecCodingBundle.message(
                "spec.delta.summary",
                "wf-base",
                "wf-target",
                1,
                1,
                0,
                1,
            ),
            state.deltaSummary,
        )
        assertTrue(state.canPinBaseline)
        assertEquals(listOf("wf-base:wf-target"), recorder.compareByWorkflowIdCalls)
        assertTrue(recorder.compareByDeltaBaselineCalls.isEmpty())
    }

    @Test
    fun `build should fall back to pinned baseline when no reference baseline is configured`() {
        val recorder = RecordingEnvironment().apply {
            deltaBaselines = listOf(
                SpecDeltaBaselineRef(
                    baselineId = "baseline-42",
                    workflowId = "wf-base",
                    snapshotId = "snapshot-42",
                    createdAt = 420L,
                ),
            )
            compareByDeltaBaselineResult = Result.success(
                delta(
                    baselineWorkflowId = "wf-base",
                    targetWorkflowId = "wf-target",
                    artifactStatuses = listOf(
                        SpecDeltaStatus.MODIFIED,
                        SpecDeltaStatus.MODIFIED,
                    ),
                ),
            )
        }
        val builder = builder(recorder)

        val state = builder.build(
            workflow = workflow(id = "wf-target"),
            refreshedAtMillis = 2000L,
        )

        assertEquals(
            listOf(SpecWorkflowPinnedDeltaBaselineChoice(recorder.deltaBaselines.single())),
            state.baselineChoices,
        )
        assertEquals("baseline:baseline-42", state.preferredBaselineChoiceId)
        assertEquals(
            SpecCodingBundle.message(
                "spec.delta.summary",
                "wf-base",
                "wf-target",
                0,
                2,
                0,
                0,
            ),
            state.deltaSummary,
        )
        assertEquals(listOf("wf-target:baseline-42"), recorder.compareByDeltaBaselineCalls)
        assertTrue(recorder.compareByWorkflowIdCalls.isEmpty())
    }

    @Test
    fun `build should degrade gracefully when verification and delta services fail`() {
        val recorder = RecordingEnvironment().apply {
            verificationHistoryFailure = IllegalStateException("history unavailable")
            deltaBaselinesFailure = IllegalStateException("baselines unavailable")
            compareByWorkflowIdResult = Result.failure(IllegalStateException("compare unavailable"))
            hasSnapshotsFailure = IllegalStateException("snapshots unavailable")
        }
        val builder = builder(recorder)

        val state = builder.build(
            workflow = workflow(
                id = "wf-target",
                baselineWorkflowId = "wf-base",
            ),
            refreshedAtMillis = 3000L,
        )

        assertFalse(state.verifyEnabled)
        assertFalse(state.verificationDocumentAvailable)
        assertTrue(state.verificationHistory.isEmpty())
        assertEquals(
            listOf(
                SpecWorkflowReferenceBaselineChoice(
                    workflowId = "wf-base",
                    title = "wf-base",
                ),
            ),
            state.baselineChoices,
        )
        assertNull(state.deltaSummary)
        assertFalse(state.canPinBaseline)
        assertEquals(listOf("wf-base:wf-target"), recorder.compareByWorkflowIdCalls)
        assertEquals(
            listOf(
                "Unable to load verification history for workflow wf-target",
                "Unable to load delta baselines for workflow wf-target",
                "Unable to inspect workflow snapshots for wf-target",
                "Unable to build delta summary for workflow wf-target",
            ),
            recorder.loggedFailures,
        )
    }

    private fun builder(recorder: RecordingEnvironment): SpecWorkflowVerifyDeltaStateBuilder {
        return SpecWorkflowVerifyDeltaStateBuilder(
            listVerificationHistory = { workflowId ->
                recorder.verificationHistoryFailure?.let { throw it }
                recorder.verificationHistoryByWorkflowId[workflowId] ?: recorder.verificationHistory
            },
            resolveBaselineWorkflowTitle = { workflowId ->
                recorder.baselineWorkflowTitles[workflowId]
            },
            listDeltaBaselines = { workflowId ->
                recorder.deltaBaselinesFailure?.let { throw it }
                recorder.deltaBaselinesByWorkflowId[workflowId] ?: recorder.deltaBaselines
            },
            hasWorkflowSnapshots = { workflowId ->
                recorder.hasSnapshotsFailure?.let { throw it }
                recorder.hasSnapshotsByWorkflowId[workflowId] ?: recorder.hasSnapshots
            },
            compareByWorkflowId = { baselineWorkflowId, targetWorkflowId ->
                recorder.compareByWorkflowIdCalls += "$baselineWorkflowId:$targetWorkflowId"
                recorder.compareByWorkflowIdResult
            },
            compareByDeltaBaseline = { workflowId, baselineId ->
                recorder.compareByDeltaBaselineCalls += "$workflowId:$baselineId"
                recorder.compareByDeltaBaselineResult
            },
            hasVerificationArtifact = { workflowId ->
                recorder.verificationArtifactAvailabilityByWorkflowId[workflowId] ?: recorder.verificationArtifactAvailable
            },
            logFailure = { message, _ ->
                recorder.loggedFailures += message
            },
        )
    }

    private fun workflow(
        id: String,
        baselineWorkflowId: String? = null,
        verifyEnabled: Boolean = false,
        verifyStageActive: Boolean = false,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            baselineWorkflowId = baselineWorkflowId,
            verifyEnabled = verifyEnabled,
            stageStates = mapOf(
                StageId.VERIFY to StageState(
                    active = verifyStageActive,
                    status = StageProgress.NOT_STARTED,
                ),
            ),
        )
    }

    private fun historyEntry(runId: String): VerifyRunHistoryEntry {
        return VerifyRunHistoryEntry(
            runId = runId,
            planId = "plan-$runId",
            executedAt = "2026-04-14T00:00:00Z",
            occurredAtEpochMs = 0L,
            currentStage = StageId.VERIFY,
            conclusion = VerificationConclusion.PASS,
            summary = "summary-$runId",
            commandCount = 1,
        )
    }

    private fun delta(
        baselineWorkflowId: String,
        targetWorkflowId: String,
        artifactStatuses: List<SpecDeltaStatus>,
    ): SpecWorkflowDelta {
        return SpecWorkflowDelta(
            baselineWorkflowId = baselineWorkflowId,
            targetWorkflowId = targetWorkflowId,
            phaseDeltas = emptyList(),
            artifactDeltas = artifactStatuses.mapIndexed { index, status ->
                SpecArtifactDelta(
                    artifact = when (index) {
                        0 -> SpecDeltaArtifact.REQUIREMENTS
                        1 -> SpecDeltaArtifact.DESIGN
                        2 -> SpecDeltaArtifact.TASKS
                        else -> SpecDeltaArtifact.VERIFICATION
                    },
                    status = status,
                    baselineContent = "baseline-$index",
                    targetContent = "target-$index",
                )
            },
        )
    }

    private class RecordingEnvironment {
        var verificationHistory: List<VerifyRunHistoryEntry> = emptyList()
        val verificationHistoryByWorkflowId = linkedMapOf<String, List<VerifyRunHistoryEntry>>()
        var verificationHistoryFailure: Throwable? = null
        val baselineWorkflowTitles = linkedMapOf<String, String?>()
        var deltaBaselines: List<SpecDeltaBaselineRef> = emptyList()
        val deltaBaselinesByWorkflowId = linkedMapOf<String, List<SpecDeltaBaselineRef>>()
        var deltaBaselinesFailure: Throwable? = null
        var hasSnapshots: Boolean = false
        val hasSnapshotsByWorkflowId = linkedMapOf<String, Boolean>()
        var hasSnapshotsFailure: Throwable? = null
        var compareByWorkflowIdResult: Result<SpecWorkflowDelta> =
            Result.failure(IllegalStateException("missing workflow comparison"))
        var compareByDeltaBaselineResult: Result<SpecWorkflowDelta> =
            Result.failure(IllegalStateException("missing baseline comparison"))
        var verificationArtifactAvailable: Boolean = false
        val verificationArtifactAvailabilityByWorkflowId = linkedMapOf<String, Boolean>()
        val compareByWorkflowIdCalls = mutableListOf<String>()
        val compareByDeltaBaselineCalls = mutableListOf<String>()
        val loggedFailures = mutableListOf<String>()
    }
}
