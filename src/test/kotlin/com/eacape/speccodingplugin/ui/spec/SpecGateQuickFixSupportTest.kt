package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateQuickFixDescriptor
import com.eacape.speccodingplugin.spec.GateQuickFixKind
import com.eacape.speccodingplugin.spec.GateStatus
import com.eacape.speccodingplugin.spec.MissingRequirementsSectionsQuickFixPayload
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.Violation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecGateQuickFixSupportTest {

    @Test
    fun `presentations should expose repair tasks title`() {
        val presentations = SpecGateQuickFixSupport.presentations(
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
        )

        assertEquals(1, presentations.size)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.repairTasks"),
            presentations.single().title,
        )
    }

    @Test
    fun `presentations should expose repair requirements title`() {
        val presentations = SpecGateQuickFixSupport.presentations(
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
        )

        assertEquals(1, presentations.size)
        assertEquals(
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.repairRequirements"),
            presentations.single().title,
        )
    }

    @Test
    fun `summary should expose all quick fix titles for missing requirements sections`() {
        val violation = missingRequirementsViolation()

        val summary = SpecGateQuickFixSupport.summary(violation)
        val presentations = SpecGateQuickFixSupport.presentations(violation)

        assertEquals(3, presentations.size)
        assertTrue(summary!!.contains(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill")))
        assertTrue(summary.contains(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarifyThenFill")))
        assertTrue(summary.contains(SpecCodingBundle.message("spec.toolwindow.gate.quickFix.manualEdit")))
        assertTrue(presentations.first().detail!!.contains(SpecCodingBundle.message("spec.requirements.section.functional")))
        assertTrue(
            presentations.first().detail!!.contains(
                SpecCodingBundle.message("spec.requirements.section.acceptanceCriteria"),
            ),
        )
    }

    @Test
    fun `popup text should include disabled reason when quick fix is unavailable`() {
        val presentation = SpecGateQuickFixSupport.presentations(
            Violation(
                ruleId = "stage-completion-checks",
                severity = GateStatus.ERROR,
                fileName = "requirements.md",
                line = 1,
                message = "Missing requirements sections",
                quickFixes = listOf(
                    GateQuickFixDescriptor(
                        kind = GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS,
                        payload = MissingRequirementsSectionsQuickFixPayload(
                            listOf(RequirementsSectionId.USER_STORIES),
                        ),
                        enabled = false,
                        disabledReason = "AI is offline",
                    ),
                ),
            ),
        ).single()

        assertTrue(presentation.popupText().contains("AI is offline"))
    }

    private fun missingRequirementsViolation(): Violation {
        val payload = MissingRequirementsSectionsQuickFixPayload(
            listOf(
                RequirementsSectionId.FUNCTIONAL,
                RequirementsSectionId.ACCEPTANCE_CRITERIA,
            ),
        )
        return Violation(
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
        )
    }
}
