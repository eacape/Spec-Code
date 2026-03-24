package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImprovedChatPanelCollapsePlannerTest {

    @Test
    fun `typing after collapsed pasted text should not create a new collapse plan`() {
        val marker = "[Pasted text #1 +60 lines]"

        val plan = ImprovedChatPanel.planComposerCollapse(
            previousSnapshot = marker,
            currentInput = "$marker\nfollow-up summary",
            expandInsertedText = { inserted -> inserted },
        )

        assertNull(plan)
    }

    @Test
    fun `should remove duplicated raw pasted text when marker is also present`() {
        val marker = "[Pasted text #1 +60 lines]"
        val rawText = buildLargeText(lines = 60, prefix = "alpha")

        val cleaned = ImprovedChatPanel.deduplicatePastedTextMarkerDisplay(
            currentInput = "$rawText\n$marker",
            markerPayloads = mapOf(marker to rawText),
        )

        assertEquals(marker, cleaned)
    }

    @Test
    fun `should keep follow up text after marker untouched during deduplication`() {
        val marker = "[Pasted text #1 +60 lines]"
        val rawText = buildLargeText(lines = 60, prefix = "alpha")

        val cleaned = ImprovedChatPanel.deduplicatePastedTextMarkerDisplay(
            currentInput = "$marker\nfollow-up summary",
            markerPayloads = mapOf(marker to rawText),
        )

        assertNull(cleaned)
    }

    @Test
    fun `pasting another large block should only collapse the inserted block`() {
        val marker = "[Pasted text #1 +60 lines]"
        val previousSnapshot = "$marker\nnote\n"
        val insertedRawText = buildLargeText(lines = 55, prefix = "beta")

        val plan = ImprovedChatPanel.planComposerCollapse(
            previousSnapshot = previousSnapshot,
            currentInput = previousSnapshot + insertedRawText,
            expandInsertedText = { inserted -> inserted },
        )

        assertNotNull(plan)
        assertEquals(insertedRawText, plan!!.rawText)
        assertEquals(55, plan.lineCount)
        assertEquals(previousSnapshot.length, plan.replaceStart)
        assertEquals((previousSnapshot + insertedRawText).length, plan.replaceEndExclusive)
    }

    private fun buildLargeText(lines: Int, prefix: String): String {
        return (1..lines).joinToString("\n") { index -> "$prefix-$index" }
    }
}
