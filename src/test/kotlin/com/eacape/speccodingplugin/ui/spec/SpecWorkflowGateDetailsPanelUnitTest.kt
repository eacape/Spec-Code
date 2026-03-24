package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.GateQuickFixDescriptor
import com.eacape.speccodingplugin.spec.GateQuickFixKind
import com.eacape.speccodingplugin.spec.GateResult
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.MissingRequirementsSectionsQuickFixPayload
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.Violation
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowGateDetailsPanelUnitTest {

    private val project: Project = mockk(relaxed = true)

    @Test
    fun `triggerSelectedQuickFixForTest should route ai repair with workflow id and missing sections`() {
        var capturedWorkflowId: String? = null
        var capturedSections: List<RequirementsSectionId> = emptyList()
        val payload = MissingRequirementsSectionsQuickFixPayload(
            listOf(RequirementsSectionId.NON_FUNCTIONAL, RequirementsSectionId.USER_STORIES),
        )
        val panel = SpecWorkflowGateDetailsPanel(
            project = project,
            showHeader = true,
            onAiFillRequested = { workflowId, sections ->
                capturedWorkflowId = workflowId
                capturedSections = sections
                true
            },
            aiFillUnavailableReasonProvider = { null },
        )

        panel.updateGateResult(
            workflowId = "wf-unit-ai-fill",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "stage-completion-checks",
                        severity = GateStatus.ERROR,
                        fileName = "requirements.md",
                        line = 1,
                        message = "Missing requirements sections",
                        quickFixes = listOf(
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                                payload = payload,
                            ),
                        ),
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
        assertTrue(panel.triggerQuickFixForTest(0, GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS))
        assertEquals("wf-unit-ai-fill", capturedWorkflowId)
        assertEquals(payload.missingSections, capturedSections)
    }

    @Test
    fun `triggerQuickFixForTest should route tasks repair with workflow id`() {
        var capturedWorkflowId: String? = null
        val panel = SpecWorkflowGateDetailsPanel(
            project = project,
            showHeader = true,
            onRepairTasksRequested = { workflowId ->
                capturedWorkflowId = workflowId
                true
            },
        )

        panel.updateGateResult(
            workflowId = "wf-unit-repair-tasks",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "tasks-syntax",
                        severity = GateStatus.ERROR,
                        fileName = "tasks.md",
                        line = 3,
                        message = "Task heading must be canonical",
                        quickFixes = listOf(
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.REPAIR_TASKS_ARTIFACT,
                            ),
                        ),
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.triggerQuickFixForTest(0, GateQuickFixKind.REPAIR_TASKS_ARTIFACT))
        assertEquals("wf-unit-repair-tasks", capturedWorkflowId)
    }

    @Test
    fun `triggerQuickFixForTest should route requirements repair with workflow id`() {
        var capturedWorkflowId: String? = null
        val panel = SpecWorkflowGateDetailsPanel(
            project = project,
            showHeader = true,
            onRepairRequirementsRequested = { workflowId ->
                capturedWorkflowId = workflowId
                true
            },
        )

        panel.updateGateResult(
            workflowId = "wf-unit-repair-requirements",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "document-validation",
                        severity = GateStatus.ERROR,
                        fileName = "requirements.md",
                        line = 1,
                        message = "requirements.md still contains TODO placeholders.",
                        quickFixes = listOf(
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.REPAIR_REQUIREMENTS_ARTIFACT,
                            ),
                        ),
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )

        assertTrue(panel.triggerQuickFixForTest(0, GateQuickFixKind.REPAIR_REQUIREMENTS_ARTIFACT))
        assertEquals("wf-unit-repair-requirements", capturedWorkflowId)
    }

    @Test
    fun `selected quick fixes should keep clarify and manual enabled when ai fill is unavailable`() {
        val payload = MissingRequirementsSectionsQuickFixPayload(
            listOf(RequirementsSectionId.NON_FUNCTIONAL),
        )
        val panel = SpecWorkflowGateDetailsPanel(
            project = project,
            showHeader = true,
            aiFillUnavailableReasonProvider = { "AI is offline" },
        )

        panel.updateGateResult(
            workflowId = "wf-unit-disabled",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "stage-completion-checks",
                        severity = GateStatus.ERROR,
                        fileName = "requirements.md",
                        line = 1,
                        message = "Missing requirements sections",
                        quickFixes = listOf(
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                                payload = payload,
                            ),
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS,
                                payload = payload,
                            ),
                            GateQuickFixDescriptor(
                                kind = GateQuickFixKind.OPEN_FOR_MANUAL_EDIT,
                                payload = payload,
                            ),
                        ),
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
        panel.selectViolationForTest(0)

        val popupTexts = panel.selectedQuickFixPopupTextsForTest()

        assertEquals(listOf(false, true, true), panel.selectedQuickFixEnabledStatesForTest())
        assertTrue(popupTexts[0].contains("AI is offline"))
        assertFalse(popupTexts[1].contains("AI is offline"))
        assertFalse(popupTexts[2].contains("AI is offline"))
    }

    @Test
    fun `preferred height should grow with violation count before workspace cap applies`() {
        val panel = SpecWorkflowGateDetailsPanel(
            project = project,
            showHeader = false,
        )

        panel.updateGateResult(
            workflowId = "wf-gate-height-single",
            gateResult = GateResult.fromViolations(
                listOf(
                    Violation(
                        ruleId = "stage-completion-checks",
                        severity = GateStatus.ERROR,
                        fileName = "requirements.md",
                        line = 1,
                        message = "Single violation",
                    ),
                ),
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
        val singleViolationHeight = panel.preferredSize.height

        panel.updateGateResult(
            workflowId = "wf-gate-height-many",
            gateResult = GateResult.fromViolations(
                (1..6).map { index ->
                    Violation(
                        ruleId = "stage-completion-checks-$index",
                        severity = GateStatus.ERROR,
                        fileName = "requirements.md",
                        line = index,
                        message = "Violation $index",
                    )
                },
            ),
            refreshedAtMillis = 1_710_000_000_000,
        )
        val manyViolationsHeight = panel.preferredSize.height

        assertTrue(manyViolationsHeight > singleViolationHeight)
    }
}
