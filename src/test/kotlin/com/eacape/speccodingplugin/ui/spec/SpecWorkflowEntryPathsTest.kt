package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowEntryPathsTest {

    @Test
    fun `prioritized templates should keep quick task and full spec at the front`() {
        assertEquals(
            listOf(
                WorkflowTemplate.QUICK_TASK,
                WorkflowTemplate.FULL_SPEC,
                WorkflowTemplate.DESIGN_REVIEW,
                WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
            SpecWorkflowEntryPaths.prioritizedTemplates(),
        )
    }

    @Test
    fun `resolve default template should keep explicit preferred template`() {
        assertEquals(
            WorkflowTemplate.FULL_SPEC,
            SpecWorkflowEntryPaths.resolveDefaultTemplate(
                preferredTemplate = WorkflowTemplate.FULL_SPEC,
                configuredDefault = WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
        )
    }

    @Test
    fun `resolve default template should fall back to quick task for advanced configured defaults`() {
        assertEquals(
            WorkflowTemplate.QUICK_TASK,
            SpecWorkflowEntryPaths.resolveDefaultTemplate(
                preferredTemplate = null,
                configuredDefault = WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
        )
    }

    @Test
    fun `is primary template should only flag quick task and full spec`() {
        assertTrue(SpecWorkflowEntryPaths.isPrimaryTemplate(WorkflowTemplate.QUICK_TASK))
        assertTrue(SpecWorkflowEntryPaths.isPrimaryTemplate(WorkflowTemplate.FULL_SPEC))
        assertFalse(SpecWorkflowEntryPaths.isPrimaryTemplate(WorkflowTemplate.DESIGN_REVIEW))
    }

    @Test
    fun `advanced templates should keep non primary templates in stable order`() {
        assertEquals(
            listOf(
                WorkflowTemplate.DESIGN_REVIEW,
                WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
            SpecWorkflowEntryPaths.advancedTemplates(),
        )
        assertTrue(SpecWorkflowEntryPaths.isAdvancedTemplate(WorkflowTemplate.DIRECT_IMPLEMENT))
        assertFalse(SpecWorkflowEntryPaths.isAdvancedTemplate(WorkflowTemplate.QUICK_TASK))
    }

    @Test
    fun `primary entry should group advanced templates behind one beta secondary choice`() {
        assertEquals(
            SpecWorkflowPrimaryEntry.QUICK_TASK,
            SpecWorkflowEntryPaths.primaryEntryForTemplate(WorkflowTemplate.QUICK_TASK),
        )
        assertEquals(
            SpecWorkflowPrimaryEntry.FULL_SPEC,
            SpecWorkflowEntryPaths.primaryEntryForTemplate(WorkflowTemplate.FULL_SPEC),
        )
        assertEquals(
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE,
            SpecWorkflowEntryPaths.primaryEntryForTemplate(WorkflowTemplate.DIRECT_IMPLEMENT),
        )
    }

    @Test
    fun `template for primary entry should resolve advanced selection and fallback safely`() {
        assertEquals(
            WorkflowTemplate.QUICK_TASK,
            SpecWorkflowEntryPaths.templateForPrimaryEntry(SpecWorkflowPrimaryEntry.QUICK_TASK),
        )
        assertEquals(
            WorkflowTemplate.DIRECT_IMPLEMENT,
            SpecWorkflowEntryPaths.templateForPrimaryEntry(
                entry = SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE,
                advancedTemplate = WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
        )
        assertEquals(
            WorkflowTemplate.DESIGN_REVIEW,
            SpecWorkflowEntryPaths.templateForPrimaryEntry(
                entry = SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE,
                advancedTemplate = WorkflowTemplate.QUICK_TASK,
            ),
        )
    }
}
