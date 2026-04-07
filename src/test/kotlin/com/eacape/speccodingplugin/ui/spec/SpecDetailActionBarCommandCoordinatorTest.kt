package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailActionBarCommandCoordinatorTest {

    @Test
    fun `resolveGenerate should submit trimmed input when text is present`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveGenerate(
            input = "  refine architecture  ",
            phase = SpecPhase.SPECIFY,
            canReuseLastInput = false,
        )

        assertTrue(plan is SpecDetailActionBarGeneratePlan.Submit)
        assertEquals("refine architecture", (plan as SpecDetailActionBarGeneratePlan.Submit).input)
    }

    @Test
    fun `resolveGenerate should allow blank input for implement phase`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveGenerate(
            input = "   ",
            phase = SpecPhase.IMPLEMENT,
            canReuseLastInput = false,
        )

        assertTrue(plan is SpecDetailActionBarGeneratePlan.Submit)
        assertEquals("", (plan as SpecDetailActionBarGeneratePlan.Submit).input)
    }

    @Test
    fun `resolveGenerate should request input hint for blank specify input`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveGenerate(
            input = "   ",
            phase = SpecPhase.SPECIFY,
            canReuseLastInput = false,
        )

        assertTrue(plan is SpecDetailActionBarGeneratePlan.InputRequired)
        assertEquals(SpecPhase.SPECIFY, (plan as SpecDetailActionBarGeneratePlan.InputRequired).phase)
    }

    @Test
    fun `resolveOpenEditor should prefer selected document phase over artifact fallback`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveOpenEditor(
            selectedPhase = SpecPhase.DESIGN,
            artifactFileName = "design.md",
        )

        assertTrue(plan is SpecDetailActionBarOpenEditorPlan.PhaseDocument)
        assertEquals(SpecPhase.DESIGN, (plan as SpecDetailActionBarOpenEditorPlan.PhaseDocument).phase)
    }

    @Test
    fun `resolveOpenEditor should ignore missing phase and artifact`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveOpenEditor(
            selectedPhase = null,
            artifactFileName = null,
        )

        assertEquals(SpecDetailActionBarOpenEditorPlan.Ignore, plan)
    }

    @Test
    fun `resolveEdit should request explicit revision start when phase is locked`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveEdit(
            SpecDetailPanelViewState(
                artifactOnlyView = false,
                displayedDocumentPhase = SpecPhase.DESIGN,
                editablePhase = SpecPhase.DESIGN,
                revisionLockedPhase = SpecPhase.DESIGN,
                selectedDocumentAvailable = true,
                artifactOpenAvailable = false,
            ),
        )

        assertTrue(plan is SpecDetailActionBarEditPlan.StartEditing)
        assertEquals(SpecPhase.DESIGN, (plan as SpecDetailActionBarEditPlan.StartEditing).explicitRevisionPhase)
    }

    @Test
    fun `resolveEdit should ignore when editable phase is unavailable`() {
        val plan = SpecDetailActionBarCommandCoordinator.resolveEdit(
            SpecDetailPanelViewState(
                artifactOnlyView = true,
                displayedDocumentPhase = null,
                editablePhase = null,
                revisionLockedPhase = null,
                selectedDocumentAvailable = false,
                artifactOpenAvailable = true,
            ),
        )

        assertEquals(SpecDetailActionBarEditPlan.Ignore, plan)
    }
}
