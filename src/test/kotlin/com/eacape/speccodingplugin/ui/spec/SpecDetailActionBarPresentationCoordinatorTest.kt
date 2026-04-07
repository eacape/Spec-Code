package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecDetailActionBarPresentationCoordinatorTest {

    @Test
    fun `generate mode should use default action tooltips and pause icon`() {
        val presentation = SpecDetailActionBarPresentationCoordinator.resolve(
            composeMode = ArtifactComposeActionMode.GENERATE,
            workflowStatus = WorkflowStatus.IN_PROGRESS,
            editRequiresExplicitRevisionStart = false,
            customIcons = customIcons(),
        )

        assertEquals("execute", SpecWorkflowIcons.debugId(presentation.generate.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.generate"), presentation.generate.tooltip)
        assertEquals("pause", SpecWorkflowIcons.debugId(presentation.pauseResume.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.pause"), presentation.pauseResume.tooltip)
        assertEquals("edit", SpecWorkflowIcons.debugId(presentation.edit.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.edit"), presentation.edit.tooltip)
        assertEquals("save", SpecWorkflowIcons.debugId(presentation.save.icon))
    }

    @Test
    fun `revise mode should switch confirm tooltip and locked edit icon`() {
        val presentation = SpecDetailActionBarPresentationCoordinator.resolve(
            composeMode = ArtifactComposeActionMode.REVISE,
            workflowStatus = WorkflowStatus.PAUSED,
            editRequiresExplicitRevisionStart = true,
            customIcons = customIcons(),
        )

        assertEquals("execute", SpecWorkflowIcons.debugId(presentation.generate.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.revise"), presentation.generate.tooltip)
        assertEquals("execute", SpecWorkflowIcons.debugId(presentation.confirmGenerate.icon))
        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.confirmRevise"),
            presentation.confirmGenerate.tooltip,
        )
        assertEquals("resume", SpecWorkflowIcons.debugId(presentation.pauseResume.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.resume"), presentation.pauseResume.tooltip)
        assertEquals("add", SpecWorkflowIcons.debugId(presentation.edit.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.revision.start"), presentation.edit.tooltip)
    }

    private fun customIcons(): SpecDetailActionBarCustomIcons {
        return SpecDetailActionBarCustomIcons(
            save = SpecWorkflowIcons.Save,
            startRevision = SpecWorkflowIcons.Add,
        )
    }
}
