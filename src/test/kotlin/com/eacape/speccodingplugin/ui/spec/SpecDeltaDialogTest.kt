package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecArtifactDelta
import com.eacape.speccodingplugin.spec.SpecDeltaArtifact
import com.eacape.speccodingplugin.spec.SpecDeltaStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDeltaDialogTest {

    @Test
    fun `shouldShowInChangedOnly should hide unchanged and keep changed statuses`() {
        assertFalse(SpecDeltaDialog.shouldShowInChangedOnly(SpecDeltaStatus.UNCHANGED))
        assertTrue(SpecDeltaDialog.shouldShowInChangedOnly(SpecDeltaStatus.ADDED))
        assertTrue(SpecDeltaDialog.shouldShowInChangedOnly(SpecDeltaStatus.MODIFIED))
        assertTrue(SpecDeltaDialog.shouldShowInChangedOnly(SpecDeltaStatus.REMOVED))
    }

    @Test
    fun `parseStatusFilter should return all for invalid values`() {
        assertEquals(SpecDeltaDialog.StatusFilterOption.ALL, SpecDeltaDialog.parseStatusFilter(null))
        assertEquals(SpecDeltaDialog.StatusFilterOption.ALL, SpecDeltaDialog.parseStatusFilter(""))
        assertEquals(SpecDeltaDialog.StatusFilterOption.ALL, SpecDeltaDialog.parseStatusFilter("INVALID"))
        assertEquals(SpecDeltaDialog.StatusFilterOption.MODIFIED, SpecDeltaDialog.parseStatusFilter("MODIFIED"))
    }

    @Test
    fun `shouldShow should apply status filter and changed only together`() {
        assertTrue(
            SpecDeltaDialog.shouldShow(
                status = SpecDeltaStatus.ADDED,
                filterOption = SpecDeltaDialog.StatusFilterOption.ALL,
                changedOnly = false,
            )
        )

        assertFalse(
            SpecDeltaDialog.shouldShow(
                status = SpecDeltaStatus.UNCHANGED,
                filterOption = SpecDeltaDialog.StatusFilterOption.ALL,
                changedOnly = true,
            )
        )

        assertTrue(
            SpecDeltaDialog.shouldShow(
                status = SpecDeltaStatus.MODIFIED,
                filterOption = SpecDeltaDialog.StatusFilterOption.MODIFIED,
                changedOnly = true,
            )
        )

        assertFalse(
            SpecDeltaDialog.shouldShow(
                status = SpecDeltaStatus.REMOVED,
                filterOption = SpecDeltaDialog.StatusFilterOption.MODIFIED,
                changedOnly = false,
            )
        )

        assertTrue(
            SpecDeltaDialog.shouldShow(
                status = SpecDeltaStatus.UNCHANGED,
                filterOption = SpecDeltaDialog.StatusFilterOption.UNCHANGED,
                changedOnly = false,
            )
        )

        assertFalse(
            SpecDeltaDialog.shouldShow(
                status = SpecDeltaStatus.UNCHANGED,
                filterOption = SpecDeltaDialog.StatusFilterOption.UNCHANGED,
                changedOnly = true,
            )
        )
    }

    @Test
    fun `canPreviewDiff should require at least one non blank side`() {
        val emptyDelta = SpecArtifactDelta(
            artifact = SpecDeltaArtifact.REQUIREMENTS,
            status = SpecDeltaStatus.UNCHANGED,
            baselineContent = null,
            targetContent = "   ",
        )
        val changedDelta = emptyDelta.copy(targetContent = "# Updated")

        assertFalse(SpecDeltaDialog.canPreviewDiff(emptyDelta))
        assertTrue(SpecDeltaDialog.canPreviewDiff(changedDelta))
    }

    @Test
    fun `contentForDiff should normalize null and windows newlines`() {
        assertEquals("", SpecDeltaDialog.contentForDiff(null))
        assertEquals("a\nb\nc", SpecDeltaDialog.contentForDiff("a\r\nb\rc"))
    }
}
