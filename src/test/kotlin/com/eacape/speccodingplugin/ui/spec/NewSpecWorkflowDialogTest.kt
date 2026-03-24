package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewSpecWorkflowDialogTest {

    @Test
    fun `templateSupportsRequirementScope should only allow requirement templates`() {
        assertTrue(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.FULL_SPEC))
        assertFalse(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.QUICK_TASK))
        assertFalse(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.DESIGN_REVIEW))
        assertFalse(NewSpecWorkflowDialog.templateSupportsRequirementScope(WorkflowTemplate.DIRECT_IMPLEMENT))
    }

    @Test
    fun `normalizeChangeIntent should fallback to full for templates without requirements`() {
        assertEquals(
            SpecChangeIntent.INCREMENTAL,
            NewSpecWorkflowDialog.normalizeChangeIntent(
                template = WorkflowTemplate.FULL_SPEC,
                requestedIntent = SpecChangeIntent.INCREMENTAL,
            ),
        )
        assertEquals(
            SpecChangeIntent.FULL,
            NewSpecWorkflowDialog.normalizeChangeIntent(
                template = WorkflowTemplate.QUICK_TASK,
                requestedIntent = SpecChangeIntent.INCREMENTAL,
            ),
        )
        assertEquals(
            SpecChangeIntent.FULL,
            NewSpecWorkflowDialog.normalizeChangeIntent(
                template = WorkflowTemplate.DIRECT_IMPLEMENT,
                requestedIntent = SpecChangeIntent.FULL,
            ),
        )
    }

    @Test
    fun `buildTemplatePresentation should describe full spec flow with optional verify artifact`() {
        val presentation = NewSpecWorkflowDialog.buildTemplatePresentation(WorkflowTemplate.FULL_SPEC)

        assertTrue(presentation.description.isNotBlank())
        assertTrue(presentation.bestFor.isNotBlank())
        assertEquals(
            listOf(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.REQUIREMENTS),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.DESIGN),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                SpecCodingBundle.message(
                    "spec.dialog.template.optionalValue",
                    SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
                ),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.ARCHIVE),
            ).joinToString(" -> "),
            presentation.stageSummary,
        )
        assertEquals(
            listOf(
                "requirements.md",
                "design.md",
                "tasks.md",
                SpecCodingBundle.message("spec.dialog.template.optionalValue", "verification.md"),
            ).joinToString(", "),
            presentation.artifactSummary,
        )
    }

    @Test
    fun `buildTemplatePresentation should omit verify stage and artifact when verify unchecked`() {
        val presentation = NewSpecWorkflowDialog.buildTemplatePresentation(
            template = WorkflowTemplate.QUICK_TASK,
            verifyEnabled = false,
        )

        assertEquals(
            listOf(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.ARCHIVE),
            ).joinToString(" -> "),
            presentation.stageSummary,
        )
        assertEquals("tasks.md", presentation.artifactSummary)
    }

    @Test
    fun `buildTemplatePresentation should include verify stage and artifact when verify checked`() {
        val presentation = NewSpecWorkflowDialog.buildTemplatePresentation(
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            verifyEnabled = true,
        )

        assertEquals(
            listOf(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.ARCHIVE),
            ).joinToString(" -> "),
            presentation.stageSummary,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.dialog.template.generatedValue", "tasks.md"),
                "verification.md",
            ).joinToString(", "),
            presentation.artifactSummary,
        )
    }

    @Test
    fun `buildTemplatePresentation should explain direct implement scaffolded artifacts`() {
        val presentation = NewSpecWorkflowDialog.buildTemplatePresentation(WorkflowTemplate.DIRECT_IMPLEMENT)

        assertTrue(presentation.description.contains("tasks.md"))
        assertEquals(
            listOf(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                SpecCodingBundle.message(
                    "spec.dialog.template.optionalValue",
                    SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
                ),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.ARCHIVE),
            ).joinToString(" -> "),
            presentation.stageSummary,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.dialog.template.generatedValue", "tasks.md"),
                SpecCodingBundle.message("spec.dialog.template.optionalValue", "verification.md"),
            ).joinToString(", "),
            presentation.artifactSummary,
        )
    }
}
