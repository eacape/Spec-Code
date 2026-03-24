package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArtifactComposeActionUiTextTest {

    @Test
    fun `revise mode should resolve revise specific process and clarification copy`() {
        assertEquals(
            SpecCodingBundle.message("spec.workflow.process.revise.prepare"),
            ArtifactComposeActionUiText.processPrepare(ArtifactComposeActionMode.REVISE),
        )
        assertEquals(
            SpecCodingBundle.message("spec.workflow.process.revise.call", 42),
            ArtifactComposeActionUiText.processCall(ArtifactComposeActionMode.REVISE, 42),
        )
        assertEquals(
            SpecCodingBundle.message("spec.workflow.clarify.hint.revise"),
            ArtifactComposeActionUiText.clarificationHint(ArtifactComposeActionMode.REVISE),
        )
        assertEquals(
            SpecCodingBundle.message("spec.workflow.clarify.generating.revise"),
            ArtifactComposeActionUiText.clarificationGenerating(ArtifactComposeActionMode.REVISE),
        )
    }

    @Test
    fun `disabled reason should follow revise mode and workflow status`() {
        assertEquals(
            SpecCodingBundle.message(
                "spec.detail.action.disabled.status.revise",
                SpecCodingBundle.message("spec.workflow.status.paused"),
            ),
            ArtifactComposeActionUiText.primaryActionDisabledReason(
                mode = ArtifactComposeActionMode.REVISE,
                status = WorkflowStatus.PAUSED,
                isGeneratingActive = false,
                isEditing = false,
            ),
        )
        assertEquals(
            SpecCodingBundle.message("spec.detail.action.disabled.running.revise"),
            ArtifactComposeActionUiText.primaryActionDisabledReason(
                mode = ArtifactComposeActionMode.REVISE,
                status = WorkflowStatus.IN_PROGRESS,
                isGeneratingActive = true,
                isEditing = false,
            ),
        )
    }
}
