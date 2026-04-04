package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPreviewSurfaceCoordinatorTest {

    @Test
    fun `workflow preview should default to preview card when clarification is absent`() {
        val plan = SpecDetailPreviewSurfaceCoordinator.forWorkflow(
            hasClarificationState = false,
            isClarificationGenerating = false,
        )

        assertEquals(SpecDetailPreviewSurfaceCard.PREVIEW, plan.card)
        assertTrue(plan.clarificationPreviewVisible)
    }

    @Test
    fun `clarification generating should keep clarify card and hide clarification preview`() {
        val plan = SpecDetailPreviewSurfaceCoordinator.forClarification(isGenerating = true)

        assertEquals(SpecDetailPreviewSurfaceCard.CLARIFY, plan.card)
        assertFalse(plan.clarificationPreviewVisible)
    }

    @Test
    fun `clarification draft should keep clarify card and show clarification preview`() {
        val plan = SpecDetailPreviewSurfaceCoordinator.forClarification(isGenerating = false)

        assertEquals(SpecDetailPreviewSurfaceCard.CLARIFY, plan.card)
        assertTrue(plan.clarificationPreviewVisible)
    }

    @Test
    fun `validation fallback should force preview card even when clarification state remains`() {
        val plan = SpecDetailPreviewSurfaceCoordinator.forPreview(
            hasClarificationState = true,
            isClarificationGenerating = false,
        )

        assertEquals(SpecDetailPreviewSurfaceCard.PREVIEW, plan.card)
        assertTrue(plan.clarificationPreviewVisible)
    }

    @Test
    fun `refresh should preserve preview override while clarification state exists`() {
        val plan = SpecDetailPreviewSurfaceCoordinator.preserveCurrent(
            currentCard = SpecDetailPreviewSurfaceCard.PREVIEW,
            hasClarificationState = true,
            isClarificationGenerating = false,
        )

        assertEquals(SpecDetailPreviewSurfaceCard.PREVIEW, plan.card)
        assertTrue(plan.clarificationPreviewVisible)
    }
}
