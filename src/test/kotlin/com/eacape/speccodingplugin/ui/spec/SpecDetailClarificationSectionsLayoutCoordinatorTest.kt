package com.eacape.speccodingplugin.ui.spec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecDetailClarificationSectionsLayoutCoordinatorTest {

    @Test
    fun `should hide preview section when preview content is not visible`() {
        val plan = SpecDetailClarificationSectionsLayoutCoordinator.buildPlan(
            questionsExpanded = true,
            previewExpanded = true,
            previewContentVisible = false,
            splitPaneHeight = 240,
            expandedResizeWeight = 0.58,
            expandedDividerSize = 4,
            collapsedSectionHeight = 36,
        )

        assertTrue(plan.questionsBodyVisible)
        assertFalse(plan.previewBodyVisible)
        assertFalse(plan.previewSectionVisible)
        assertFalse(plan.attachPreviewSection)
        assertTrue(plan.questionsToggle.enabled)
        assertFalse(plan.previewToggle.enabled)
        assertEquals(1.0, plan.resizeWeight)
        assertEquals(0, plan.dividerSize)
        assertNull(plan.dividerLocation)
    }

    @Test
    fun `should keep balanced divider when both sections are expanded`() {
        val plan = SpecDetailClarificationSectionsLayoutCoordinator.buildPlan(
            questionsExpanded = true,
            previewExpanded = true,
            previewContentVisible = true,
            splitPaneHeight = 200,
            expandedResizeWeight = 0.58,
            expandedDividerSize = 4,
            collapsedSectionHeight = 36,
        )

        assertTrue(plan.questionsBodyVisible)
        assertTrue(plan.previewBodyVisible)
        assertTrue(plan.previewSectionVisible)
        assertTrue(plan.attachPreviewSection)
        assertTrue(plan.previewToggle.enabled)
        assertEquals(0.58, plan.resizeWeight)
        assertEquals(4, plan.dividerSize)
        assertEquals(113, plan.dividerLocation)
    }

    @Test
    fun `should collapse divider to minimum top when only preview stays expanded`() {
        val plan = SpecDetailClarificationSectionsLayoutCoordinator.buildPlan(
            questionsExpanded = false,
            previewExpanded = true,
            previewContentVisible = true,
            splitPaneHeight = 200,
            expandedResizeWeight = 0.58,
            expandedDividerSize = 4,
            collapsedSectionHeight = 36,
        )

        assertFalse(plan.questionsBodyVisible)
        assertTrue(plan.previewBodyVisible)
        assertEquals(36, plan.dividerLocation)
    }

    @Test
    fun `should collapse divider to maximum top when only questions stay expanded`() {
        val plan = SpecDetailClarificationSectionsLayoutCoordinator.buildPlan(
            questionsExpanded = true,
            previewExpanded = false,
            previewContentVisible = true,
            splitPaneHeight = 200,
            expandedResizeWeight = 0.58,
            expandedDividerSize = 4,
            collapsedSectionHeight = 36,
        )

        assertTrue(plan.questionsBodyVisible)
        assertFalse(plan.previewBodyVisible)
        assertEquals(160, plan.dividerLocation)
    }

    @Test
    fun `should skip divider target when split pane height is not ready`() {
        val plan = SpecDetailClarificationSectionsLayoutCoordinator.buildPlan(
            questionsExpanded = true,
            previewExpanded = true,
            previewContentVisible = true,
            splitPaneHeight = 0,
            expandedResizeWeight = 0.58,
            expandedDividerSize = 4,
            collapsedSectionHeight = 36,
        )

        assertTrue(plan.previewSectionVisible)
        assertNull(plan.dividerLocation)
    }
}
