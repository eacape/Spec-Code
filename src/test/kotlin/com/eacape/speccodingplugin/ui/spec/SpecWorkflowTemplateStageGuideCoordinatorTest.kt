package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowTemplateStageGuideCoordinatorTest {

    @Test
    fun `build should keep full spec stage order and mark verify optional by default`() {
        val guide = SpecWorkflowTemplateStageGuideCoordinator.build(WorkflowTemplate.FULL_SPEC)

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
            guide.stageSummary,
        )
        assertTrue(
            guide.stageMeaningSummary.contains(
                "${SpecWorkflowOverviewPresenter.stageLabel(StageId.REQUIREMENTS)}:",
            ),
        )
        assertTrue(
            guide.stageMeaningSummary.contains(
                "${
                    SpecCodingBundle.message(
                        "spec.dialog.template.optionalValue",
                        SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
                    )
                }:",
            ),
        )
    }

    @Test
    fun `build should omit verify when verify is disabled`() {
        val guide = SpecWorkflowTemplateStageGuideCoordinator.build(
            template = WorkflowTemplate.QUICK_TASK,
            verifyEnabled = false,
        )

        assertEquals(
            listOf(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.TASKS),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.ARCHIVE),
            ).joinToString(" -> "),
            guide.stageSummary,
        )
        assertFalse(guide.stageMeaningSummary.contains(SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY)))
    }

    @Test
    fun `build should show verify as active when verify is enabled`() {
        val guide = SpecWorkflowTemplateStageGuideCoordinator.build(
            template = WorkflowTemplate.DIRECT_IMPLEMENT,
            verifyEnabled = true,
        )

        assertEquals(
            listOf(
                SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
                SpecWorkflowOverviewPresenter.stageLabel(StageId.ARCHIVE),
            ).joinToString(" -> "),
            guide.stageSummary,
        )
        assertTrue(guide.stageMeaningSummary.contains("${SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY)}:"))
        assertFalse(
            guide.stageMeaningSummary.contains(
                "${
                    SpecCodingBundle.message(
                        "spec.dialog.template.optionalValue",
                        SpecWorkflowOverviewPresenter.stageLabel(StageId.VERIFY),
                    )
                }:",
            ),
        )
    }

    @Test
    fun `build should keep optional implement marker for design review`() {
        val guide = SpecWorkflowTemplateStageGuideCoordinator.build(WorkflowTemplate.DESIGN_REVIEW)

        val optionalImplementLabel = SpecCodingBundle.message(
            "spec.dialog.template.optionalValue",
            SpecWorkflowOverviewPresenter.stageLabel(StageId.IMPLEMENT),
        )
        assertTrue(guide.stageSummary.contains(optionalImplementLabel))
        assertTrue(guide.stageMeaningSummary.contains("$optionalImplementLabel:"))
    }
}
