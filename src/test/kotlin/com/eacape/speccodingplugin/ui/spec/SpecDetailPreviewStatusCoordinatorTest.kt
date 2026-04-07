package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecDetailPreviewStatusCoordinatorTest {

    @Test
    fun `noWorkflow should use muted status copy`() {
        assertEquals(
            SpecDetailPreviewStatusPlan(
                text = SpecCodingBundle.message("spec.detail.noWorkflow"),
                tone = SpecDetailPreviewStatusTone.MUTED,
            ),
            SpecDetailPreviewStatusCoordinator.noWorkflow(),
        )
    }

    @Test
    fun `clarificationHint should use info tone with clarification copy`() {
        assertEquals(
            SpecDetailPreviewStatusPlan(
                text = ArtifactComposeActionUiText.clarificationHint(ArtifactComposeActionMode.REVISE),
                tone = SpecDetailPreviewStatusTone.INFO,
            ),
            SpecDetailPreviewStatusCoordinator.clarificationHint(ArtifactComposeActionMode.REVISE),
        )
    }

    @Test
    fun `generating should use active progress copy when not clarifying`() {
        assertEquals(
            SpecDetailPreviewStatusPlan(
                text = "${ArtifactComposeActionUiText.activeProgress(ArtifactComposeActionMode.GENERATE, 42)} ...",
                tone = SpecDetailPreviewStatusTone.GENERATING,
            ),
            SpecDetailPreviewStatusCoordinator.generating(
                mode = ArtifactComposeActionMode.GENERATE,
                progressPercent = 42,
                frame = "...",
                isClarificationGenerating = false,
            ),
        )
    }

    @Test
    fun `generating should use clarification loading copy when clarifying`() {
        assertEquals(
            SpecDetailPreviewStatusPlan(
                text = "${ArtifactComposeActionUiText.clarificationGenerating(ArtifactComposeActionMode.REVISE)} ...",
                tone = SpecDetailPreviewStatusTone.GENERATING,
            ),
            SpecDetailPreviewStatusCoordinator.generating(
                mode = ArtifactComposeActionMode.REVISE,
                progressPercent = 42,
                frame = "...",
                isClarificationGenerating = true,
            ),
        )
    }
}
