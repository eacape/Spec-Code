package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.VerificationConclusion
import com.eacape.speccodingplugin.spec.VerifyRunHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SpecWorkflowVerifyDeltaPanelTest {

    @Test
    fun `embedded verify panel should hide duplicated header block`() {
        val panel = SpecWorkflowVerifyDeltaPanel(showHeader = false)

        panel.updateState(
            SpecWorkflowVerifyDeltaState(
                workflowId = "wf-1",
                verifyEnabled = true,
                verificationDocumentAvailable = true,
                verificationHistory = listOf(
                    VerifyRunHistoryEntry(
                        runId = "verify-run-1",
                        planId = "verify-plan-1",
                        executedAt = "2026-03-12T15:27:30Z",
                        occurredAtEpochMs = 1_710_000_000_000,
                        currentStage = StageId.VERIFY,
                        conclusion = VerificationConclusion.PASS,
                        summary = "Verification passed",
                        commandCount = 2,
                    ),
                ),
                baselineChoices = emptyList(),
                preferredBaselineChoiceId = null,
                canPinBaseline = false,
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("false", snapshot.getValue("headerVisible"))
        assertEquals("wf-1", snapshot.getValue("workflowId"))
        assertEquals("1", snapshot.getValue("historyCount"))
    }

    @Test
    fun `verify panel should expose icon only business actions with tooltips`() {
        val panel = SpecWorkflowVerifyDeltaPanel(showHeader = false)

        panel.updateState(
            SpecWorkflowVerifyDeltaState(
                workflowId = "wf-verify",
                verifyEnabled = true,
                verificationDocumentAvailable = true,
                verificationHistory = listOf(
                    VerifyRunHistoryEntry(
                        runId = "verify-run-2",
                        planId = "verify-plan-2",
                        executedAt = "2026-03-13T10:00:00Z",
                        occurredAtEpochMs = 1_710_000_000_000,
                        currentStage = StageId.VERIFY,
                        conclusion = VerificationConclusion.WARN,
                        summary = "Verification needs follow-up",
                        commandCount = 1,
                    ),
                ),
                baselineChoices = listOf(
                    SpecWorkflowReferenceBaselineChoice(
                        workflowId = "wf-base",
                        title = "Baseline",
                    ),
                ),
                preferredBaselineChoiceId = "workflow:wf-base",
                canPinBaseline = true,
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals("", snapshot.getValue("runText"))
        assertEquals("execute", snapshot.getValue("runIconId"))
        assertEquals("true", snapshot.getValue("runFocusable"))
        assertEquals("", snapshot.getValue("openText"))
        assertEquals("openDocument", snapshot.getValue("openIconId"))
        assertEquals("true", snapshot.getValue("openFocusable"))
        assertEquals("", snapshot.getValue("compareText"))
        assertEquals("history", snapshot.getValue("compareIconId"))
        assertEquals("true", snapshot.getValue("compareFocusable"))
        assertEquals("", snapshot.getValue("pinText"))
        assertEquals("save", snapshot.getValue("pinIconId"))
        assertEquals("true", snapshot.getValue("pinFocusable"))
        assertFalse(snapshot.getValue("runTooltip").isBlank())
        assertFalse(snapshot.getValue("openTooltip").isBlank())
        assertFalse(snapshot.getValue("compareTooltip").isBlank())
        assertFalse(snapshot.getValue("pinTooltip").isBlank())
        assertEquals(snapshot.getValue("runTooltip"), snapshot.getValue("runAccessibleDescription"))
        assertEquals(snapshot.getValue("openTooltip"), snapshot.getValue("openAccessibleDescription"))
        assertEquals(snapshot.getValue("compareTooltip"), snapshot.getValue("compareAccessibleDescription"))
        assertEquals(snapshot.getValue("pinTooltip"), snapshot.getValue("pinAccessibleDescription"))
    }

    @Test
    fun `verify panel should expose disabled reasons for unavailable icon actions`() {
        val panel = SpecWorkflowVerifyDeltaPanel(showHeader = false)

        panel.updateState(
            SpecWorkflowVerifyDeltaState(
                workflowId = "wf-verify",
                verifyEnabled = false,
                verificationDocumentAvailable = false,
                verificationHistory = emptyList(),
                baselineChoices = emptyList(),
                preferredBaselineChoiceId = null,
                canPinBaseline = false,
                refreshedAtMillis = 1_710_000_000_000,
            ),
        )

        val snapshot = panel.snapshotForTest()
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.verifyDelta.run.disabled.verifyOff"),
            snapshot.getValue("runTooltip"),
        )
        assertEquals("true", snapshot.getValue("runFocusable"))
        assertEquals(
            "${SpecCodingBundle.message("spec.toolwindow.verifyDelta.run")}. " +
                SpecCodingBundle.message("spec.toolwindow.verifyDelta.run.disabled.verifyOff"),
            snapshot.getValue("runAccessibleDescription"),
        )
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.verifyDelta.compare.disabled.noBaseline"),
            snapshot.getValue("compareTooltip"),
        )
        assertEquals("72", snapshot.getValue("dividerLocation"))
        assertEquals("0.32", snapshot.getValue("resizeWeight"))
    }
}
